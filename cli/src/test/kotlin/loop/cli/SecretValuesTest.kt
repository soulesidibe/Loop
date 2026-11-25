package loop.cli

import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plan seam 3: the secret set is the credential refs of `WORKFLOW.md`, by env name. */
class SecretValuesTest {
    @TempDir
    lateinit var root: Path

    private fun workflow(
        env: Map<String, String>,
        provider: String = "",
        kinds: String = "",
        trackers: String = "",
    ): WorkflowFile {
        val file = root.resolve("WORKFLOW.md")
        file.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: files\n" +
                "  provider:\n" +
                "    dir: \$LOOP_TEST_ISSUES\n" +
                provider +
                trackers +
                "workspace:\n" +
                "  root: \$LOOP_TEST_ROOT\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: agent.sh\n" +
                kinds +
                "---\n" +
                "Work on {{ issue.identifier }}\n",
        )
        return WorkflowLoader(env = env, home = root).load(file).getOrThrow()
    }

    private val paths get() = mapOf("LOOP_TEST_ISSUES" to root.resolve("issues").toString(), "LOOP_TEST_ROOT" to "/tmp/ws-root")

    @Test
    fun `a tracker provider credential ref is a secret and path refs are not`() {
        val env = paths + ("LOOP_TRACKER_TOKEN" to "tracker-token-value")

        val secrets = secretValues(workflow(env, provider = "    token: \$LOOP_TRACKER_TOKEN\n"), env)

        assertEquals(mapOf("LOOP_TRACKER_TOKEN" to "tracker-token-value"), secrets)
    }

    @Test
    fun `m2-25 the pull request delivery token is a secret, and a short one is named as not redacted`() {
        val env = paths + mapOf("LOOP_PR_TOKEN" to "pull-request-token-value", "LOOP_SHORT" to "abc")
        val kinds = { name: String ->
            "kinds:\n  code:\n    delivery:\n      pull_request: { repo: acme/widgets, token: \$$name }\n    verify:\n      checks: []\n"
        }

        assertEquals(mapOf("LOOP_PR_TOKEN" to "pull-request-token-value"), secretValues(workflow(env, kinds = kinds("LOOP_PR_TOKEN")), env))
        assertEquals(listOf("LOOP_SHORT"), shortSecretNames(workflow(env, kinds = kinds("LOOP_SHORT")), env))
    }

    @Test
    fun `a credential ref nested in a tracker provider map or list is a secret too, as the snapshot omits it`() {
        val env = paths + mapOf("LOOP_NESTED_TOKEN" to "nested-token-value", "LOOP_LISTED_TOKEN" to "listed-token-value")
        val provider =
            "    auth:\n" +
                "      token: \$LOOP_NESTED_TOKEN\n" +
                "      note: not \$LOOP_PARTIAL\n" +
                "    keys:\n" +
                "      - \$LOOP_LISTED_TOKEN\n"

        val secrets = secretValues(workflow(env, provider), env)

        assertEquals(mapOf("LOOP_NESTED_TOKEN" to "nested-token-value", "LOOP_LISTED_TOKEN" to "listed-token-value"), secrets)
    }

    @Test
    fun `the anthropic keys of the host env are secrets`() {
        val env = paths + mapOf("ANTHROPIC_API_KEY" to "api-key-value", "ANTHROPIC_AUTH_TOKEN" to "auth-token-value")

        val secrets = secretValues(workflow(env), env)

        assertEquals(mapOf("ANTHROPIC_API_KEY" to "api-key-value", "ANTHROPIC_AUTH_TOKEN" to "auth-token-value"), secrets)
    }

    @Test
    fun `the google keys of the host env are secrets and short ones are named`() {
        val env = paths + mapOf("GEMINI_API_KEY" to "gemini-key-value", "GOOGLE_API_KEY" to "google-key-value")

        val secrets = secretValues(workflow(env), env)

        assertEquals(mapOf("GEMINI_API_KEY" to "gemini-key-value", "GOOGLE_API_KEY" to "google-key-value"), secrets)

        val shortEnv = paths + mapOf("GEMINI_API_KEY" to "abc", "GOOGLE_API_KEY" to "   ")
        assertEquals(emptyMap(), secretValues(workflow(shortEnv), shortEnv))
        assertEquals(listOf("GEMINI_API_KEY"), shortSecretNames(workflow(shortEnv), shortEnv))
    }

    @Test
    fun `the startup redactor learns the google keys before any load`() {
        val env = paths + mapOf("GEMINI_API_KEY" to "gemini-key-value", "GOOGLE_API_KEY" to "google-key-value")
        workflow(env)
        val loader = WorkflowLoader(env = env, home = root)

        val redacted =
            startupRedactor(root.resolve("WORKFLOW.md"), loader, env)
                .redact("keys gemini-key-value and google-key-value")

        assertEquals("keys [redacted:GEMINI_API_KEY] and [redacted:GOOGLE_API_KEY]", redacted)
    }

    @Test
    fun `a value under 4 characters is not a secret but a short name, and unset or blank values are neither`() {
        val env = paths + mapOf("LOOP_SHORT_TOKEN" to "abc", "LOOP_BLANK_TOKEN" to "  ")
        val provider = "    short: \$LOOP_SHORT_TOKEN\n    blank: \$LOOP_BLANK_TOKEN\n    unset: \$LOOP_UNSET_TOKEN\n"
        val workflow = workflow(env, provider)

        assertEquals(emptyMap(), secretValues(workflow, env))
        assertEquals(listOf("LOOP_SHORT_TOKEN"), shortSecretNames(workflow, env))
    }

    @Test
    fun `m4-11 a credential ref of an extra tracker is a secret like the primary's, and a short one is named`() {
        val env = paths + mapOf("LOOP_SIDE_TOKEN" to "side-tracker-token-value", "LOOP_SIDE_SHORT" to "abc")
        val trackers =
            "trackers:\n" +
                "  - id: side\n" +
                "    kind: files\n" +
                "    provider:\n" +
                "      dir: \$LOOP_TEST_ISSUES\n" +
                "      token: \$LOOP_SIDE_TOKEN\n" +
                "      short: \$LOOP_SIDE_SHORT\n"

        val workflow = workflow(env, trackers = trackers)

        assertEquals(mapOf("LOOP_SIDE_TOKEN" to "side-tracker-token-value"), secretValues(workflow, env))
        assertEquals(listOf("LOOP_SIDE_SHORT"), shortSecretNames(workflow, env))
    }

    @Test
    fun `m4-11 the startup redactor learns an extra tracker's secret before any load`() {
        val env = paths + ("LOOP_SIDE_TOKEN" to "side-tracker-token-value")
        val trackers = "trackers:\n  - id: side\n    kind: files\n    provider: { token: \$LOOP_SIDE_TOKEN }\n"
        workflow(env, trackers = trackers)
        val loader = WorkflowLoader(env = env, home = root)

        val redacted = startupRedactor(root.resolve("WORKFLOW.md"), loader, env).redact("token side-tracker-token-value")

        assertEquals(false, "side-tracker-token-value" in redacted, redacted)
    }
}
