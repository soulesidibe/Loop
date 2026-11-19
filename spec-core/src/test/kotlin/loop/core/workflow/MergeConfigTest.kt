package loop.core.workflow

import loop.api.MergeMethod
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m2-26: `kinds.code.delivery.pull_request.merge` turns on the merge check and the merge. Absent keeps m2-25. Present,
 * `method` is `merge` (the default) or `squash`, `ci` is required and only `none` is accepted, and
 * `kinds.code.delivery.on_delivered.move_to` is required and must be a terminal state. Under `policy: auto` it needs the
 * m2-28 opt in.
 */
class MergeConfigTest {
    private val folder: Path = createTempDirectory("loop-merge-config").toRealPath()

    private val loader = WorkflowLoader(env = mapOf("LOOP_PR_TOKEN" to "ghp_pullRequestToken0123456789abcdefgh"))

    private fun write(
        merge: String?,
        onDelivered: String? = "{ move_to: done }",
        policy: String = "human",
    ): Path =
        folder.resolve("WORKFLOW.md").also {
            val mergeLine = merge?.let { "\n        merge: $it" }.orEmpty()
            val deliveredLine = onDelivered?.let { "\n      on_delivered: $it" }.orEmpty()
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
                |      policy: $policy
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}LOOP_PR_TOKEN$mergeLine$deliveredLine
                |    verify:
                |      checks: []
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun delivery(
        merge: String?,
        onDelivered: String? = "{ move_to: done }",
    ): DeliveryConfig =
        checkNotNull(
            loader
                .load(write(merge, onDelivered))
                .getOrThrow()
                .config.kinds
                ?.code
                ?.delivery,
        )

    private fun error(
        merge: String?,
        onDelivered: String? = "{ move_to: done }",
        policy: String = "human",
    ): String = assertIs<WorkflowError.InvalidConfig>(loader.load(write(merge, onDelivered, policy)).exceptionOrNull()).message!!

    @Test
    fun `without merge the pull request delivery is m2-25's`() {
        val delivery = delivery(null, onDelivered = null)

        assertNull(delivery.pullRequest?.merge)
        assertNull(delivery.onDeliveredMoveTo)
    }

    @Test
    fun `merge reads its method, ci and the on_delivered move`() {
        val delivery = delivery("{ method: squash, ci: none }")

        assertEquals(MergeConfig(MergeMethod.Squash, CiMode.None), delivery.pullRequest?.merge)
        assertEquals("done", delivery.onDeliveredMoveTo)
    }

    @Test
    fun `the method defaults to merge`() {
        assertEquals(MergeConfig(MergeMethod.Merge, CiMode.None), delivery("{ ci: none }").pullRequest?.merge)
    }

    @Test
    fun `an unknown method is refused, rebase included`() {
        assertEquals(
            "kinds.code.delivery.pull_request.merge.method must be merge or squash, got \"rebase\"",
            error("{ method: rebase, ci: none }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.method must be merge or squash, got \"fast\"",
            error("{ method: fast, ci: none }"),
        )
    }

    @Test
    fun `a missing or unknown ci is refused`() {
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci is required, none or actions",
            error("{ method: merge }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci must be none or actions, got \"travis\"",
            error("{ ci: travis }"),
        )
    }

    @Test
    fun `m3-15, ci actions reads its keys with their defaults`() {
        assertEquals(
            MergeConfig(MergeMethod.Merge, CiMode.Actions, ciTimeoutMs = 2_700_000, ciNoRun = CiNoRun.Fail, ciNoRunGraceMs = 300_000),
            delivery("{ ci: actions }").pullRequest?.merge,
        )
        assertEquals(
            MergeConfig(MergeMethod.Squash, CiMode.Actions, ciTimeoutMs = 1_200_000, ciNoRun = CiNoRun.Pass, ciNoRunGraceMs = 60_000),
            delivery("{ method: squash, ci: Actions, ci_timeout_ms: 1200000, ci_no_run: PASS, ci_no_run_grace_ms: 60000 }")
                .pullRequest
                ?.merge,
        )
    }

    @Test
    fun `m3-15, bad ci values are refused`() {
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_timeout_ms must be a positive integer, got 0",
            error("{ ci: actions, ci_timeout_ms: 0 }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_no_run_grace_ms must be a positive integer, got \"soon\"",
            error("{ ci: actions, ci_no_run_grace_ms: soon }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_no_run must be fail or pass, got \"wait\"",
            error("{ ci: actions, ci_no_run: wait }"),
        )
    }

    @Test
    fun `m3-15, a grace not below the timeout is refused`() {
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_no_run_grace_ms (600000) must be below " +
                "kinds.code.delivery.pull_request.merge.ci_timeout_ms (600000)",
            error("{ ci: actions, ci_timeout_ms: 600000, ci_no_run_grace_ms: 600000 }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_no_run_grace_ms (300000) must be below " +
                "kinds.code.delivery.pull_request.merge.ci_timeout_ms (120000)",
            error("{ ci: actions, ci_timeout_ms: 120000 }"),
            "the default grace counts too",
        )
    }

    @Test
    fun `m3-15, ci keys under ci none are refused`() {
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_timeout_ms, ci_no_run need ci: actions, ci is none",
            error("{ ci: none, ci_timeout_ms: 600000, ci_no_run: pass }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.merge.ci_no_run_grace_ms need ci: actions, ci is none",
            error("{ ci: none, ci_no_run_grace_ms: 1000 }"),
        )
    }

    @Test
    fun `merge must be a map`() {
        assertEquals("kinds.code.delivery.pull_request.merge must be a map", error("~"))
    }

    @Test
    fun `merge needs the on_delivered move`() {
        assertEquals(
            "kinds.code.delivery.on_delivered.move_to is required with kinds.code.delivery.pull_request.merge",
            error("{ ci: none }", onDelivered = null),
        )
        assertEquals("kinds.code.delivery.on_delivered must be a map", error("{ ci: none }", onDelivered = "~"))
    }

    @Test
    fun `the on_delivered move must be a terminal state of the tracker`() {
        assertEquals(
            "kinds.code.delivery.on_delivered.move_to is \"review\", which is not a terminal state, use one of done, canceled",
            error("{ ci: none }", onDelivered = "{ move_to: review }"),
        )
    }

    @Test
    fun `merge under the auto policy needs the opt in and a reviewer, m2-28`() {
        assertEquals(
            "kinds.code.delivery.policy is auto, which needs the opt in kinds.code.delivery.auto_merge.enabled: true",
            error("{ ci: none }", policy = "auto"),
        )
    }
}
