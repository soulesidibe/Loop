package loop.cli

import loop.api.RecordedEvent
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.CheckStarted
import loop.core.events.DeliveryFailed
import loop.core.events.MergeCheckFinished
import loop.core.events.MergeCheckStarted
import loop.core.events.Verdict
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.orchestrator.RunObserver
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.TimeUnit

/** One running sleep assertion process; [stop] ends it, and its assertion with it. */
fun interface AwakeChild {
    fun stop()
}

/** Starts the sleep assertion process of [KeepAwake], a seam so tests fake `caffeinate`. */
fun interface AwakeLauncher {
    /** Starts [command]; a failure, such as an [IOException] for a missing program, says why it did not start. */
    fun start(command: List<String>): Result<AwakeChild>

    companion object {
        /** The launcher of the system named [osName]: [ProcessAwakeLauncher] on macOS, null elsewhere. */
        fun forSystem(osName: String = System.getProperty("os.name").orEmpty()): AwakeLauncher? =
            if (osName.startsWith("Mac")) ProcessAwakeLauncher else null
    }
}

/** Starts the command as a child process with no input or output; stop sends SIGTERM, then SIGKILL after a grace. */
object ProcessAwakeLauncher : AwakeLauncher {
    private const val STOP_GRACE_MS = 2_000L

    override fun start(command: List<String>): Result<AwakeChild> =
        try {
            val process =
                ProcessBuilder(command)
                    .redirectInput(Redirect.from(File("/dev/null")))
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start()
            Result.success(
                AwakeChild {
                    process.destroy()
                    try {
                        if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    } catch (e: InterruptedException) {
                        process.destroyForcibly()
                        Thread.currentThread().interrupt()
                    }
                },
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: UnsupportedOperationException) {
            Result.failure(e)
        }
}

/**
 * m4-09, bl-156: holds an idle sleep assertion on macOS while at least one attempt, verification or merge check runs, and
 * releases it when none does, so an idle overnight poll still lets the Mac sleep. The assertion is a
 * `caffeinate -i -w <pid>` child of `loop run` [pid]: `-w` ends it if `loop run` dies before [close].
 *
 * The run's events say what runs, since a poll's running count misses verifications and merge checks and lags the tick's
 * launches: an attempt from `attempt_started` to `attempt_finished`, a verification from its first `check_started` to its
 * `verdict` or a canceled check, a merge check from `merge_check_started` to `merge_check_finished` or `delivery_failed`.
 * An attempt of an issue ends the verification and merge check of that issue, and a poll with no running attempt ends
 * every attempt, in case an end event was never recorded.
 *
 * With no [launcher] (not macOS) or one that cannot start, one line says so at the first work and Loop goes on. Called on
 * the orchestrator's threads and the shutdown hook's, so every call holds one lock.
 */
class KeepAwake(
    private val launcher: AwakeLauncher?,
    private val log: KeyValueLog,
    private val context: LogContext,
    private val pid: Long = ProcessHandle.current().pid(),
) : RunObserver,
    AutoCloseable {
    private val lock = Any()
    private val attempts = mutableSetOf<String>()
    private val verifications = mutableSetOf<String>()
    private val mergeChecks = mutableSetOf<String>()
    private var child: AwakeChild? = null

    /** Set once the assertion cannot be held on this machine, so it is never tried again. */
    private var unavailable = false
    private var closed = false

    override fun recorded(event: RecordedEvent) {
        val issue = event.issueId ?: return
        synchronized(lock) {
            when (event.kind) {
                AttemptStarted.name -> {
                    endIssue(issue)
                    attempts += event.attemptId?.value ?: issue
                }
                AttemptFinished.name -> {
                    endIssue(issue)
                    attempts -= event.attemptId?.value ?: issue
                }
                CheckStarted.name -> verifications += issue
                CheckFinished.name -> if (event.data[CheckEvent.CANCELED] == "true") verifications -= issue
                Verdict.name -> verifications -= issue
                MergeCheckStarted.name -> mergeChecks += issue
                MergeCheckFinished.name, DeliveryFailed.name -> mergeChecks -= issue
                else -> return
            }
            follow()
        }
    }

    override fun polled(
        eligible: List<String>,
        running: Int,
        maxRunning: Int,
    ) {
        if (running > 0) return
        synchronized(lock) {
            attempts.clear()
            follow()
        }
    }

    /** Releases the assertion for good, at the end of the run; later events start nothing. */
    override fun close() {
        synchronized(lock) {
            closed = true
            release()
        }
    }

    private fun endIssue(issue: String) {
        verifications -= issue
        mergeChecks -= issue
    }

    /** Starts or stops the assertion so it is held exactly while something runs. */
    private fun follow() {
        val busy = attempts.isNotEmpty() || verifications.isNotEmpty() || mergeChecks.isNotEmpty()
        when {
            busy && child == null && !closed && !unavailable -> hold()
            !busy -> release()
        }
    }

    private fun hold() {
        if (launcher == null) {
            unavailable = true
            log.log(LogLevel.INFO, EVENT, LogOutcome.FAILED, context, reason = "not macOS, so loop run does not keep the machine awake")
            return
        }
        val command = listOf(PROGRAM, "-i", "-w", pid.toString())
        launcher
            .start(command)
            .onSuccess { started ->
                child = started
                log.log(LogLevel.INFO, EVENT, LogOutcome.STARTED, context, fields = mapOf("command" to command.joinToString(" ")))
            }.onFailure { error ->
                unavailable = true
                log.log(
                    LogLevel.WARN,
                    EVENT,
                    LogOutcome.FAILED,
                    context,
                    reason = "cannot start $PROGRAM, so the Mac may sleep while work runs: ${error.message ?: error::class.simpleName}",
                )
            }
    }

    private fun release() {
        val held = child ?: return
        child = null
        try {
            held.stop()
            log.log(LogLevel.INFO, EVENT, LogOutcome.RELEASED, context)
        } catch (e: Exception) {
            // An observer never throws; `-w` still ends the assertion with `loop run`.
            log.log(LogLevel.WARN, EVENT, LogOutcome.FAILED, context, reason = "cannot stop $PROGRAM: ${e.message ?: e::class.simpleName}")
        }
    }

    private companion object {
        const val EVENT = "keep_awake"
        const val PROGRAM = "caffeinate"
    }
}

/** Shows every call to each of [observers] in order, so the human log and [KeepAwake] follow one run. */
internal class RunObservers(
    private vararg val observers: RunObserver,
) : RunObserver {
    override fun recorded(event: RecordedEvent) = observers.forEach { it.recorded(event) }

    override fun runnerLine(
        event: RecordedEvent,
        line: String,
    ) = observers.forEach { it.runnerLine(event, line) }

    override fun polled(
        eligible: List<String>,
        running: Int,
        maxRunning: Int,
    ) = observers.forEach { it.polled(eligible, running, maxRunning) }
}
