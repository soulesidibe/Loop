package loop.core.workflow

import loop.api.PluginProfile
import loop.api.TrackerAdapter
import loop.api.TrackerContext
import loop.api.TrackerProvider
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m2-25: `kinds.code.delivery.pull_request` turns on pull request delivery. Without it delivery stays the m2-24 move.
 * On the `files` tracker `repo` and `token` are required; on another tracker they default to its own settings.
 */
class PullRequestConfigTest {
    private val folder: Path = createTempDirectory("loop-pull-request").toRealPath()

    private val env = mapOf("LOOP_PR_TOKEN" to "ghp_pullRequestToken0123456789abcdefgh", "BLANK" to " ")

    /** A tracker standing for `github`, which reads no setting, so `repo` and `token` may default to it, and `files`. */
    private fun tracker(kind: String) =
        object : TrackerProvider {
            override val kind: String = kind
            override val profile: PluginProfile = PluginProfile(emptyList())
            override val defaultActiveStates: List<String> = listOf("open")
            override val defaultTerminalStates: List<String> = listOf("closed")
            override val definedStates: List<String> = listOf("open", "closed")

            override fun parse(
                provider: Map<String, Any?>,
                env: Map<String, String>,
            ): Any? = null

            override fun create(
                settings: Any?,
                context: TrackerContext,
            ): TrackerAdapter = throw UnsupportedOperationException("never built here")
        }

    private val loader = WorkflowLoader(env = env, trackerCheck = TrackerCheck.of(listOf(tracker("other"), tracker("files"))))

    private fun write(
        pullRequest: String?,
        tracker: String = "files",
    ): Path =
        folder.resolve("WORKFLOW.md").also {
            val line = pullRequest?.let { "\n    delivery:\n      pull_request: $it" }.orEmpty()
            it.writeText(
                """
                |---
                |tracker:
                |  kind: $tracker
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |kinds:
                |  code:$line
                |    verify:
                |      checks: []
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun config(
        pullRequest: String?,
        tracker: String = "files",
    ): PullRequestConfig? =
        loader
            .load(write(pullRequest, tracker))
            .getOrThrow()
            .config.kinds
            ?.code
            ?.delivery
            ?.pullRequest

    private fun failure(
        pullRequest: String,
        tracker: String = "files",
    ): Throwable = checkNotNull(loader.load(write(pullRequest, tracker)).exceptionOrNull())

    private fun error(
        pullRequest: String,
        tracker: String = "files",
    ): String = assertIs<WorkflowError.InvalidConfig>(failure(pullRequest, tracker)).message!!

    @Test
    fun `without pull_request there is no pull request delivery`() {
        assertNull(config(null))
    }

    @Test
    fun `on the files tracker repo and token are read, base and remote have defaults`() {
        val config = config("{ repo: acme/widgets, token: \$LOOP_PR_TOKEN }")

        assertEquals(PullRequestConfig(repo = "acme/widgets", tokenEnvName = "LOOP_PR_TOKEN"), config)
        assertEquals("main", config?.base)
        assertEquals("origin", config?.remote)
        assertNull(config?.apiUrl)
    }

    @Test
    fun `every key can be set`() {
        val config =
            config(
                "{ repo: acme/widgets, token: \$LOOP_PR_TOKEN, base: develop, remote: upstream, api_url: \"http://127.0.0.1:1/api\" }",
            )

        assertEquals(
            PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN", base = "develop", remote = "upstream", apiUrl = "http://127.0.0.1:1/api"),
            config,
        )
    }

    @Test
    fun `on another tracker repo and token default to the tracker's`() {
        assertEquals(PullRequestConfig(), config("{}", tracker = "other"))
    }

    @Test
    fun `the files tracker refuses a missing repo or token`() {
        assertEquals(
            "kinds.code.delivery.pull_request.repo is required with the files tracker",
            error("{ token: \$LOOP_PR_TOKEN }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.token is required with the files tracker",
            error("{ repo: acme/widgets }"),
        )
    }

    @Test
    fun `an unset or blank token variable is refused, naming the variable only`() {
        val unset = assertIs<WorkflowError.MissingEnvVar>(failure("{ repo: acme/widgets, token: \$NOT_SET }"))
        assertEquals("kinds.code.delivery.pull_request.token uses \$NOT_SET, which is not set", unset.message)
        val blank = assertIs<WorkflowError.MissingEnvVar>(failure("{ repo: acme/widgets, token: \$BLANK }", tracker = "other"))
        assertEquals("kinds.code.delivery.pull_request.token uses \$BLANK, which is empty", blank.message)
    }

    @Test
    fun `shapes that cannot deliver as meant are config errors`() {
        assertEquals("kinds.code.delivery.pull_request must be a map", error("~"))
        assertEquals("kinds.code.delivery.pull_request must be a map", error("yes"))
        assertEquals(
            "kinds.code.delivery.pull_request.repo must be owner/name, got \"widgets\"",
            error("{ repo: widgets, token: \$LOOP_PR_TOKEN }"),
        )
        assertEquals(
            "kinds.code.delivery.pull_request.token must be a \$NAME reference, never the token itself",
            error("{ repo: acme/widgets, token: ghp_literal }"),
        )
        assertEquals("kinds.code.delivery.pull_request.base must not be blank", error("{ base: \"\" }", tracker = "other"))
        assertEquals("kinds.code.delivery.pull_request.remote must not be blank", error("{ remote: \" \" }", tracker = "other"))
        assertEquals(
            "kinds.code.delivery.pull_request.api_url must be an http or https address, got \"ftp://x\"",
            error("{ api_url: \"ftp://x\" }", tracker = "other"),
        )
    }

    @Test
    fun `api_url must be https, http only for a loopback host`() {
        for (url in listOf("https://ghe.example.com/api/v3", "http://127.0.0.1:8080/api", "http://localhost:1/api", "http://[::1]:9/api")) {
            assertEquals(url, config("{ api_url: \"$url\" }", tracker = "other")?.apiUrl, url)
        }
        for (url in listOf(
            "http://ghe.example.com/api/v3",
            "http://10.0.0.1/api",
            "http://127.0.0.1.example.com/api",
            "HTTP://example.com",
        )) {
            assertEquals(
                "kinds.code.delivery.pull_request.api_url must be an https address, http is allowed only for a loopback host, " +
                    "got \"$url\"",
                error("{ api_url: \"$url\" }", tracker = "other"),
                url,
            )
        }
    }

    @Test
    fun `the policy still reads beside pull_request`() {
        write("{ repo: acme/widgets, token: \$LOOP_PR_TOKEN }")
        val path = folder.resolve("WORKFLOW.md")
        path.writeText(
            path.toFile().readText().replace("    delivery:\n", "    delivery:\n      policy: auto\n      auto_merge: { enabled: true }\n"),
        )

        val delivery =
            loader
                .load(path)
                .getOrThrow()
                .config.kinds
                ?.code
                ?.delivery

        assertEquals(DeliveryPolicy.Auto, delivery?.policy)
        assertEquals("acme/widgets", delivery?.pullRequest?.repo)
    }
}
