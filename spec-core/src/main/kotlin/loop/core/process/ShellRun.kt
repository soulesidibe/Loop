package loop.core.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What one [ShellRun] did. [exitCode] is null when the script timed out or could not start;
 * [startError] is set in the second case. [output] is the tail of stdout and stderr merged.
 */
data class ShellResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val output: String,
    /** True when the process group could not be stopped (the final KILL failed). */
    val stopFailed: Boolean = false,
    /** True when [output] is a cut tail: its start, up to the first whole line, was dropped. */
    val outputTruncated: Boolean = false,
    /** Why the script could not start (perl, which starts the group, is missing), null when it started. */
    val startError: String? = null,
    /** The whole output, stdout and stderr merged as written, when the run kept it (m2-05), else null. */
    val fullOutput: String? = null,
    /** True when [fullOutput] went past [ShellRun.FULL_OUTPUT_MAX] and its head was dropped. */
    val fullOutputTruncated: Boolean = false,
)

/**
 * Runs a script with `sh -lc <script>` in [cwd], in its own process group (plan R7), shared by
 * hooks and m2-03's checks. Once the shell exits, or the timeout ends, or the caller is cancelled,
 * the whole group is stopped, so a background child cannot outlive the script or hold its output
 * pipe open. Output is read on a separate thread into a bounded tail and read for at most
 * [drainTimeout] after the stop (plan R6). With `keepAll`, the whole output is kept as well, for a
 * check's evidence (m2-05). With `onStopped`, a cancel of the caller hands the run so far, its tail
 * included, once the group is stopped and the tail drained, before the cancel is thrown on (bl-04).
 */
class ShellRun(
    private val grace: Duration = 2.seconds,
    private val drainTimeout: Duration = 500.milliseconds,
    private val start: (List<String>, Path, Map<String, String>) -> SessionProcess = SessionProcess::start,
) {
    suspend fun run(
        script: String,
        cwd: Path,
        env: Map<String, String>,
        timeoutMs: Long,
        keepAll: Boolean = false,
        onStopped: ((ShellResult) -> Unit)? = null,
    ): ShellResult {
        val started = TimeSource.Monotonic.markNow()
        val session =
            try {
                // A quick blocking call on the caller's dispatcher: nothing suspends between the start
                // and the try below, so a cancel is seen inside it and the finally stops the group.
                start(listOf("sh", "-lc", script), cwd, env)
            } catch (e: IOException) {
                val error = e.message ?: "unknown error"
                return ShellResult(null, false, started.elapsedNow().inWholeMilliseconds, "", startError = error)
            }
        val tail = OutputTail(OUTPUT_MAX)
        val full = if (keepAll) OutputCapture(FULL_OUTPUT_MAX) else null
        // Outside the caller's scope: a blocking read on a pipe held by an orphan cannot be interrupted.
        val readers = CoroutineScope(Dispatchers.IO + Job())
        val reader =
            readers.launch {
                tail.drain(session.process.inputStream) { bytes, count -> full?.append(bytes, count) }
            }
        var stopped = false
        try {
            val exitCode =
                withTimeoutOrNull(timeoutMs) {
                    session.process
                        .onExit()
                        .await()
                        .exitValue()
                }
            val stopFailed = !session.stop(grace)
            stopped = true
            if (withTimeoutOrNull(drainTimeout) { reader.join() } == null) {
                // A process that escaped the group may still hold the pipe: release the blocked reader.
                runCatching { session.process.inputStream.close() }
            }
            return ShellResult(
                exitCode = exitCode,
                timedOut = exitCode == null,
                durationMs = started.elapsedNow().inWholeMilliseconds,
                output = tail.text(),
                stopFailed = stopFailed,
                outputTruncated = tail.truncated,
                fullOutput = full?.text(),
                fullOutputTruncated = full?.truncated ?: false,
            )
        } catch (e: CancellationException) {
            if (onStopped != null && !stopped) {
                withContext(NonCancellable) {
                    val stopFailed = !session.stop(grace)
                    stopped = true
                    withTimeoutOrNull(drainTimeout) { reader.join() }
                    onStopped(
                        ShellResult(
                            exitCode = null,
                            timedOut = false,
                            durationMs = started.elapsedNow().inWholeMilliseconds,
                            output = tail.text(),
                            stopFailed = stopFailed,
                            outputTruncated = tail.truncated,
                        ),
                    )
                }
            }
            throw e
        } finally {
            if (!stopped) withContext(NonCancellable) { session.stop(grace) }
            runCatching { session.process.inputStream.close() }
            readers.cancel()
        }
    }

    companion object {
        /** Bytes of output kept, the tail (plan R3). */
        const val OUTPUT_MAX = 8 * 1024

        /** Bytes of output a run that keeps it all holds at most, the tail past that (m2-05). */
        const val FULL_OUTPUT_MAX = 16 * 1024 * 1024
    }
}
