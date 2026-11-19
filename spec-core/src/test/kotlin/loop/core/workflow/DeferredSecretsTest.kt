package loop.core.workflow

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-70: a loader built with `deferSecrets` loads a `WORKFLOW.md` whose secret variables are unset, for the commands
 * that only read the store and the log. It names each unset secret instead of failing, and builds no tracker settings
 * from a secret it could not read. Every other check still fails the load.
 */
class DeferredSecretsTest {
    private val folder: Path = createTempDirectory("loop-deferred-secrets").toRealPath()

    private val tracker =
        FakeTrackerProvider(
            profile =
                PluginProfile(
                    keys =
                        listOf(
                            ProfileKey("repo", ProfileType.STRING, required = true),
                            ProfileKey("token", ProfileType.STRING, required = true),
                            ProfileKey("page_size", ProfileType.INTEGER, default = "50"),
                        ),
                    secrets = setOf("token"),
                    limits = mapOf("page_size" to 1L..100L),
                ),
        ) { provider, env -> "settings with ${env[(provider["token"] as String).removePrefix("$")]}" }

    private fun loader(
        env: Map<String, String> = emptyMap(),
        deferSecrets: Boolean = true,
    ) = WorkflowLoader(env = env, trackerCheck = TrackerCheck.of(listOf(tracker)), deferSecrets = deferSecrets)

    private fun write(provider: String): Path = writeTrackerWorkflow(folder, "  kind: fake\n  provider:\n$provider")

    @Test
    fun `an unset tracker secret fails a load that requires secrets, naming the variable`() {
        val error = loader(deferSecrets = false).load(write("    repo: o/r\n    token: \$BL70_TOKEN\n")).exceptionOrNull()

        assertIs<WorkflowError.TrackerConfig>(error)
        assertEquals("tracker.provider.token uses \$BL70_TOKEN, which is not set", error.message)
    }

    @Test
    fun `an unset tracker secret is named, not fatal, when secrets are deferred, and no tracker settings are built`() {
        val workflow = loader().load(write("    repo: o/r\n    token: \$BL70_TOKEN\n")).getOrThrow()

        assertNull(workflow.trackerSettings)
        assertEquals(listOf(DeferredSecret("tracker.provider.token", "BL70_TOKEN", empty = false)), workflow.deferredSecrets)
        assertEquals("tracker.provider.token uses \$BL70_TOKEN, which is not set", workflow.deferredSecrets.single().sentence)
        assertEquals("o/r", workflow.config.trackerProvider["repo"]?.let { (it as ConfigValue.Str).value })
    }

    @Test
    fun `a blank tracker secret is named as empty when secrets are deferred`() {
        val workflow = loader(mapOf("BL70_TOKEN" to "  ")).load(write("    repo: o/r\n    token: \$BL70_TOKEN\n")).getOrThrow()

        assertEquals(listOf(DeferredSecret("tracker.provider.token", "BL70_TOKEN", empty = true)), workflow.deferredSecrets)
        assertEquals("tracker.provider.token uses \$BL70_TOKEN, which is empty", workflow.deferredSecrets.single().sentence)
    }

    @Test
    fun `a set tracker secret is read as usual when secrets are deferred`() {
        val workflow =
            loader(mapOf("BL70_TOKEN" to "ghp_deferredToken0123456789"))
                .load(write("    repo: o/r\n    token: \$BL70_TOKEN\n"))
                .getOrThrow()

        assertEquals("settings with ghp_deferredToken0123456789", workflow.trackerSettings)
        assertEquals(emptyList(), workflow.deferredSecrets)
    }

    @Test
    fun `every other check of the tracker section still fails a load with deferred secrets`() {
        val outOfLimits = loader().load(write("    repo: o/r\n    token: \$BL70_TOKEN\n    page_size: 500\n")).exceptionOrNull()
        val missing = loader().load(write("    repo: o/r\n")).exceptionOrNull()

        assertEquals("tracker.provider.page_size must be between 1 and 100, got 500", outOfLimits?.message)
        assertEquals("tracker.provider.token is required", missing?.message)
    }

    @Test
    fun `an unset delivery token is named, not fatal, when secrets are deferred`() {
        val path = folder.resolve("WORKFLOW.md")
        path.writeText(
            "---\ntracker:\n  kind: files\n" +
                "kinds:\n  code:\n    delivery:\n      pull_request:\n        repo: o/r\n        token: \$BL70_PR_TOKEN\n" +
                "    verify:\n      checks: []\n" +
                "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n---\nWork on {{ issue.identifier }}\n",
        )
        val files = WorkflowLoader(env = emptyMap(), deferSecrets = true)

        val workflow = files.load(path).getOrThrow()
        val strict = WorkflowLoader(env = emptyMap()).load(path).exceptionOrNull()

        assertEquals(
            listOf(DeferredSecret("kinds.code.delivery.pull_request.token", "BL70_PR_TOKEN", empty = false)),
            workflow.deferredSecrets,
        )
        assertEquals(
            "BL70_PR_TOKEN",
            workflow.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.tokenEnvName,
        )
        assertIs<WorkflowError.MissingEnvVar>(strict)
    }

    @Test
    fun `an unset runner secret is named, not fatal, when secrets are deferred`() {
        val runners =
            object : RunnerCheck {
                override fun problem(settings: RunnerSettings): String? = null

                override fun profile(kind: String): RunnerProfile =
                    RunnerProfile(
                        "command",
                        PluginProfile(
                            keys =
                                listOf(
                                    ProfileKey("command", ProfileType.STRING, required = true),
                                    ProfileKey("api_key", ProfileType.STRING),
                                ),
                            secrets = setOf("api_key"),
                        ),
                    )
            }
        val path = folder.resolve("WORKFLOW.md")
        path.writeText(
            "---\ntracker:\n  kind: files\n" +
                "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n    api_key: \$BL70_KEY\n---\nWork\n",
        )

        val workflow = WorkflowLoader(env = emptyMap(), runnerCheck = runners, deferSecrets = true).load(path).getOrThrow()
        val strict = WorkflowLoader(env = emptyMap(), runnerCheck = runners).load(path).exceptionOrNull()

        assertEquals(listOf(DeferredSecret("runners.command.api_key", "BL70_KEY", empty = false)), workflow.deferredSecrets)
        assertIs<WorkflowError.MissingEnvVar>(strict)
    }

    @Test
    fun `a path variable is not a secret and still fails a load with deferred secrets`() {
        val path = folder.resolve("WORKFLOW.md")
        path.writeText(
            "---\ntracker:\n  kind: files\nworkspace:\n  root: \$BL70_ROOT\n" +
                "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n---\nWork\n",
        )

        val error = WorkflowLoader(env = emptyMap(), deferSecrets = true).load(path).exceptionOrNull()

        assertIs<WorkflowError.MissingEnvVar>(error)
        assertTrue("BL70_ROOT" in error.message.orEmpty())
    }
}
