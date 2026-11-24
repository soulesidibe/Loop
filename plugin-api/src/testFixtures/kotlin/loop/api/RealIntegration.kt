package loop.api

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.fail

/**
 * The opt in real integration profile (spec 17.8, m3-24): tests that run against a real agent runtime or tracker,
 * gated by assumptions so they need no Gradle change.
 *
 * [VARIABLE] names the targets to run, a comma list such as `codex` or `codex,github`. A test of a target the variable
 * does not name is aborted by a JUnit assumption, so the report shows it skipped, never passed. Once its target is
 * named, a missing credential or tool fails the test, naming each missing item, and so fails the job.
 *
 * Conventions every target follows (`docs/spec-decisions.md`, real integration profile):
 * - The settings of a target are named `LOOP_REAL_<TARGET>_<NAME>`, for example `LOOP_REAL_CODEX_COMMAND`.
 * - Credentials keep the names their tool already reads (the codex login, the playground's
 *   `LOOP_PLAYGROUND_GITHUB_TOKEN`), never a `LOOP_REAL_` name.
 * - Each run uses identifiers from [runId] and its own temporary workspace, and removes what it created.
 * - A test calls [require] in its own body, so each test is reported skipped or failed on its own.
 */
public object RealIntegration {
    /** The variable that turns the profile on, a comma list of [TARGETS]. */
    public const val VARIABLE: String = "LOOP_REAL_INTEGRATION"

    /** The targets the profile knows: `codex` (m3-24), `github` (m3-25) and `agy`. */
    public val TARGETS: Set<String> = setOf("codex", "github", "agy")

    /** The targets [env] names in [VARIABLE], trimmed and lower cased, empty when it is unset or blank. */
    public fun targets(env: Map<String, String>): Set<String> =
        env[VARIABLE]
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Skips the calling test unless [env] names [target] in [VARIABLE]. When it does, fails the test when [missing]
     * returns any item, listing them all; [missing] runs only then, so a skipped test probes nothing. A name in the
     * variable that is not one of [TARGETS] fails the test too, so a typo never reads as a skip.
     */
    public fun require(
        target: String,
        env: Map<String, String> = System.getenv(),
        missing: () -> List<String>,
    ) {
        check(target in TARGETS) { "$target is not a real integration target: ${TARGETS.sorted()}" }
        val named = targets(env)
        val unknown = named - TARGETS
        if (unknown.isNotEmpty()) {
            fail("$VARIABLE names ${unknown.sorted().joinToString(", ")}, which is no real integration target: ${TARGETS.sorted()}")
        }
        assumeTrue(target in named, "the real integration profile is off for $target: set $VARIABLE=$target to run it")
        val absent = missing()
        if (absent.isNotEmpty()) {
            fail("$VARIABLE names $target but these are missing: ${absent.joinToString("; ")}")
        }
    }

    /** A fresh identifier for one real run, `loop-real-` and 8 hex characters, for workspaces, issues and attempts. */
    public fun runId(): String =
        "loop-real-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
}
