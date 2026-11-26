package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.workflow.CheckConfig
import java.nio.file.Path

/**
 * bl-188: a check runner that holds each run until a test releases it, and counts the runs in flight at once, so a test
 * can see whether the build gate ever let two builds overlap. Every run passes with [exitCode].
 */
class HeldBuilds(
    private val exitCode: Int = 0,
) : CheckRunner {
    /** The workspace of each run, in the order the runs started. */
    val started = mutableListOf<Path>()

    /** The runs in flight now, and the most ever in flight at once. */
    var running = 0
        private set
    var most = 0
        private set

    private val held = ArrayDeque<CompletableDeferred<Unit>>()

    override suspend fun run(
        check: CheckConfig,
        cwd: Path,
        env: Map<String, String>,
    ): CheckResult {
        val release = CompletableDeferred<Unit>()
        held.addLast(release)
        started.add(cwd)
        running++
        most = maxOf(most, running)
        try {
            release.await()
        } finally {
            running--
        }
        return CheckResult(exitCode, timedOut = false, durationMs = 1200)
    }

    /** Lets the oldest held run end. */
    fun releaseNext() {
        held.removeFirst().complete(Unit)
    }
}
