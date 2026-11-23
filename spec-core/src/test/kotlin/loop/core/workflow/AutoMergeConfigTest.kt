package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m2-28: `policy: auto` on a `code` kind needs the opt in `kinds.code.delivery.auto_merge.enabled: true`. With
 * `pull_request.merge` it merges only when the auto merge conditions hold and the merge risk band is low, so it also
 * needs `delivery.risk.reviewer`. `auto_merge` holds the protected paths and the size limit, `risk` the sensitive and
 * one way paths, each with its defaults.
 */
class AutoMergeConfigTest {
    private val folder: Path = createTempDirectory("loop-auto-merge-config").toRealPath()

    private val loader = WorkflowLoader(env = mapOf("LOOP_PR_TOKEN" to "ghp_pullRequestToken0123456789abcdefgh"))

    private fun write(delivery: String): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |kinds:
                |  code:
                |    delivery:
                |${delivery.trimIndent().prependIndent("      ")}
                |    verify:
                |      checks: []
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun delivery(text: String): DeliveryConfig =
        checkNotNull(
            loader
                .load(write(text))
                .getOrThrow()
                .config.kinds
                ?.code
                ?.delivery,
        )

    private fun error(text: String): String = assertIs<WorkflowError.InvalidConfig>(loader.load(write(text)).exceptionOrNull()).message!!

    private val pullRequest =
        """
        pull_request:
          repo: acme/widgets
          token: ${'$'}LOOP_PR_TOKEN
          merge: { ci: none }
        on_delivered: { move_to: done }
        """.trimIndent()

    @Test
    fun `policy auto without the opt in fails preflight naming the opt in key`() {
        val result =
            runBlocking {
                Preflight(
                    loader = loader,
                    path = write("policy: auto"),
                    templates = TemplateEngine(),
                    trackerCheck = { null },
                ).check()
            }

        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "kinds.code.delivery.policy is auto, which needs the opt in kinds.code.delivery.auto_merge.enabled: true",
            ),
            assertIs<PreflightResult.Failed>(result).copy(source = null),
        )
    }

    @Test
    fun `an opt in that is false or not a boolean is refused`() {
        assertEquals(
            "kinds.code.delivery.policy is auto, which needs the opt in kinds.code.delivery.auto_merge.enabled: true",
            error("policy: auto\nauto_merge: { enabled: false }"),
        )
        assertEquals(
            "kinds.code.delivery.auto_merge.enabled must be true or false, got \"yes please\"",
            error("policy: auto\nauto_merge: { enabled: yes please }"),
        )
        assertEquals("kinds.code.delivery.auto_merge must be a map", error("policy: auto\nauto_merge: ~"))
    }

    @Test
    fun `policy auto with the opt in loads, with the defaults of auto_merge and risk`() {
        val delivery = delivery("policy: auto\nauto_merge: { enabled: true }")

        assertEquals(DeliveryPolicy.Auto, delivery.policy)
        assertEquals(AutoMergeConfig(enabled = true), delivery.autoMerge)
        assertEquals(AutoMergeConfig.DEFAULT_PROTECTED_PATHS, delivery.autoMerge.protectedPaths)
        assertEquals(AutoMergeConfig.DEFAULT_MAX_DIFF_LINES, delivery.autoMerge.maxDiffLines)
        assertEquals(AutoMergeConfig.DEFAULT_DOC_PATHS, delivery.autoMerge.docPaths)
        assertEquals(RiskConfig(), delivery.risk)
        assertTrue("WORKFLOW.md" in delivery.autoMerge.protectedPaths)
        assertTrue("docs/adr/" in delivery.autoMerge.protectedPaths)
        assertTrue("**/migrations/**" in delivery.risk.sensitivePaths)
        assertEquals(listOf("**/migrations/**"), delivery.risk.oneWayPaths)
    }

    @Test
    fun `the human policy needs no opt in and keeps auto merge off`() {
        val delivery = delivery("policy: human")

        assertEquals(AutoMergeConfig(), delivery.autoMerge)
        assertEquals(false, delivery.autoMerge.enabled)
    }

    @Test
    fun `auto_merge and risk read their keys`() {
        val delivery =
            delivery(
                """
                policy: auto
                auto_merge:
                  enabled: true
                  protected_paths: [infra/, /deploy.sh]
                  max_diff_lines: 120
                  doc_paths: [guides/, "**/*.txt"]
                risk:
                  reviewer: claude-sonnet-5-5
                  sensitive_paths: ["*.lock"]
                  one_way_paths: [db/schema/]
                  timeout_ms: 30000
                """,
            )

        assertEquals(AutoMergeConfig(true, listOf("infra/", "deploy.sh"), 120, listOf("guides/", "**/*.txt")), delivery.autoMerge)
        assertEquals(RiskConfig("claude-sonnet-5-5", listOf("*.lock"), listOf("db/schema/"), 30_000), delivery.risk)
    }

    @Test
    fun `merge under policy auto loads with the opt in and a reviewer`() {
        val delivery = delivery("policy: auto\nauto_merge: { enabled: true }\nrisk: { reviewer: claude-sonnet-5-5 }\n$pullRequest")

        assertEquals(DeliveryPolicy.Auto, delivery.policy)
        assertEquals("claude-sonnet-5-5", delivery.risk.reviewer)
        assertEquals(MergeConfig(ci = CiMode.None), delivery.pullRequest?.merge)
    }

    @Test
    fun `merge under policy auto needs the risk reviewer`() {
        assertEquals(
            "kinds.code.delivery.risk.reviewer is required with policy auto and kinds.code.delivery.pull_request.merge",
            error("policy: auto\nauto_merge: { enabled: true }\n$pullRequest"),
        )
    }

    @Test
    fun `bad values of auto_merge and risk are refused`() {
        assertEquals(
            "kinds.code.delivery.auto_merge.max_diff_lines must be a positive integer, got 0",
            error("policy: auto\nauto_merge: { enabled: true, max_diff_lines: 0 }"),
        )
        assertEquals(
            "kinds.code.delivery.auto_merge.protected_paths must not hold a blank path",
            error("policy: auto\nauto_merge: { enabled: true, protected_paths: [\" \"] }"),
        )
        assertEquals(
            "kinds.code.delivery.auto_merge.doc_paths must not hold a blank path",
            error("policy: auto\nauto_merge: { enabled: true, doc_paths: [\" \"] }"),
        )
        assertEquals("kinds.code.delivery.risk must be a map", error("policy: human\nrisk: ~"))
        assertEquals("kinds.code.delivery.risk.reviewer must not be blank", error("policy: human\nrisk: { reviewer: \" \" }"))
        assertEquals(
            "kinds.code.delivery.risk.one_way_paths must not hold a blank path",
            error("policy: human\nrisk: { one_way_paths: [\"\"] }"),
        )
    }

    @Test
    fun `an empty protected list is allowed, a project can protect nothing more than its guards`() {
        assertEquals(emptyList(), delivery("policy: auto\nauto_merge: { enabled: true, protected_paths: [] }").autoMerge.protectedPaths)
        assertNull(delivery("policy: human").risk.reviewer)
    }
}
