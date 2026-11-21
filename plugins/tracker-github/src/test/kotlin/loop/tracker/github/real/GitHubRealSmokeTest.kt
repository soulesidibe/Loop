package loop.tracker.github.real

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.RealIntegration
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * m3-25, story 96, spec 17.8 items 1 to 4: the GitHub tracker against the real playground repository, the `github`
 * target of the real integration profile (see [RealIntegration] and `docs/spec-decisions.md`). Skipped unless
 * `LOOP_REAL_INTEGRATION` names `github`; then it fails when `LOOP_PLAYGROUND_GITHUB_TOKEN` is missing, or when a
 * tracker call does not do what it should.
 *
 * The run is isolated: a fresh [RealIntegration.runId] names two labels of its own and the one issue it creates. The
 * tracker is scoped to the first label, so it sees no other issue, and its required label is the second, `<id>-ready`,
 * never `ready-for-agent`, so no Loop pointed at the playground ever takes the issue as a candidate. At the end the
 * issue is closed and both labels are deleted, whatever happened before; a fine grained token cannot delete an issue,
 * so each run leaves one closed issue on the playground.
 */
class GitHubRealSmokeTest {
    @Test
    fun `m3-25 17_8 the github tracker fetches, reads, comments on and moves its own playground issue, left closed`() {
        val env = System.getenv()
        RealIntegration.require("github", env) { GitHubRealProfile.missing(env) }
        val id = RealIntegration.runId()
        val ready = "$id-ready"
        val settings = GitHubRealProfile.settings(env, id).getOrThrow()
        val run = GitHubRealRun(id, settings)

        val failure = runCatching { runBlocking { withTimeout(RUN_CAP) { run.exercise(ready) } } }.exceptionOrNull()
        val cleanup = runBlocking { run.cleanUp() }
        println("m3-25 real github run $id on ${GitHubRealProfile.REPO}:\n" + run.steps.joinToString("\n") { "  $it" })

        if (failure != null) {
            cleanup.forEach(failure::addSuppressed)
            throw failure
        }
        if (cleanup.isNotEmpty()) {
            throw AssertionError("the cleanup of $id failed, see the suppressed errors; ${run.leftBehind()}").apply {
                cleanup.forEach(::addSuppressed)
            }
        }
    }

    private companion object {
        val RUN_CAP = 3.minutes
    }
}
