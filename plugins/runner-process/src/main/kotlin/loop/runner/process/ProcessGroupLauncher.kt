package loop.runner.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import loop.api.RunnerEvent.Output.Stream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What to start: [argv] run in [cwd] with exactly [environment] (nothing is inherited from
 * the host, see [hostEnvironment]), [stdin] written then closed. With [stdinLines] (m3-07),
 * stdin stays open after [stdin]: each text received from the channel is written and flushed
 * as it comes, and stdin is closed once the channel is closed.
 */
data class LaunchSpec(
    val argv: List<String>,
    val cwd: Path,
    val environment: Map<String, String>,
    val stdin: String = "",
    val stdinLines: ReceiveChannel<String>? = null,
)

/** What a launched process did, in order. */
sealed interface ProcessEvent {
    /** The process started with [pid], which is also its session and process group id. */
    data class Started(
        val pid: Long,
    ) : ProcessEvent

    /** The process could not be started; nothing follows. */
    data class SpawnFailed(
        val message: String,
    ) : ProcessEvent

    /** One line on [stream]. Problems met while cleaning up arrive as `loop:` lines on stderr. */
    data class Line(
        val stream: Stream,
        val text: String,
    ) : ProcessEvent

    /** The process exited with [exitCode] and its group is cleaned up; always the last event. */
    data class Exited(
        val exitCode: Int,
    ) : ProcessEvent
}

/**
 * Starts processes in their own process group and cleans the group up after them.
 *
 * Process group: the process runs in its own session, started through `perl` (required on
 * PATH) calling `setsid`, so its process group id is its pid and it has no controlling
 * terminal. Every process it starts, children and grandchildren through other scripts
 * alike, stays in that group unless it leaves on purpose (for example with `setsid` itself).
 *
 * If perl cannot be started the flow emits [ProcessEvent.SpawnFailed]; if setsid or exec
 * fails the launcher exits 127 with a `loop:` line on stderr.
 *
 * End of a process: once it exits, with any exit code, background work it left always gets
 * SIGTERM through the group, and output is read for at most [drainTimeout] more, so a
 * process that keeps stdout open cannot hold the flow open. Cancelling the collection sends
 * SIGTERM to the group. In both cases a group still alive after [gracePeriod] gets SIGKILL.
 * Descendants seen in periodic snapshots are killed too, a second net for processes that
 * left the group. A signal that cannot be sent, or processes still alive after SIGKILL, are
 * reported as `loop:` lines on stderr before [ProcessEvent.Exited], or through [warn] after
 * a cancel, when no event can be emitted any more.
 *
 * A caller applies its own timeouts by cancelling the collection: the group is killed the
 * same way.
 *
 * Containers: Loop needs an init that reaps orphans (`docker run --init`, tini), otherwise
 * zombie members keep the group probe true until [gracePeriod] ends.
 */
class ProcessGroupLauncher(
    private val gracePeriod: Duration = 2.seconds,
    private val drainTimeout: Duration = 500.milliseconds,
    private val signaller: GroupSignaller = ShellGroupSignaller,
    private val warn: (String) -> Unit = { line -> System.err.println(line) },
    /** Starts the built process; a seam for tests. */
    private val start: (ProcessBuilder) -> Process = { it.start() },
) {
    /** A cold flow: collecting it starts the process, cancelling the collection kills its group. */
    fun launch(spec: LaunchSpec): Flow<ProcessEvent> =
        channelFlow {
            val builder =
                ProcessBuilder(listOf("perl", "-MPOSIX=setsid", "-e", LAUNCHER) + spec.argv)
                    .directory(spec.cwd.toFile())
            builder.environment().apply {
                clear()
                putAll(spec.environment)
            }

            // An already cancelled collection never spawns a process.
            ensureActive()
            // A quick blocking call on the collector's dispatcher, so nothing suspends between the
            // start and the try that stops the group. The next cancellable point is inside that try.
            val process =
                try {
                    start(builder)
                } catch (e: IOException) {
                    send(ProcessEvent.SpawnFailed("loop requires perl on PATH: ${e.message}"))
                    return@channelFlow
                }
            // The launcher execs the command in place after setsid, so the session and group ids are the pid.
            val pgid = process.pid()

            // Readers live outside the flow's scope: a blocking read on a pipe held by an
            // orphaned process cannot be interrupted, so the flow must be able to stop waiting.
            val readers = CoroutineScope(Dispatchers.IO + Job())
            val seen: MutableSet<ProcessHandle> = ConcurrentHashMap.newKeySet()
            // Unbounded so the readers never wait on a slow collector; closed when the flow stops reading.
            val lines = Channel<ProcessEvent.Line>(Channel.UNLIMITED)
            val dropped = AtomicInteger()
            // Problems met while killing the group, in order and without repeats.
            val problems = LinkedHashSet<String>()
            var cleanedUp = false
            var reported = false
            try {
                send(ProcessEvent.Started(pgid))
                val forwarder = launch { for (line in lines) send(line) }
                readers.launch { writeStdin(process, spec.stdin, spec.stdinLines) }
                val stdout = readers.launch { drain(process.inputStream, Stream.Stdout, lines, dropped) }
                val stderr = readers.launch { drain(process.errorStream, Stream.Stderr, lines, dropped) }
                readers.launch { trackDescendants(process, seen) }
                val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                // Background work left by the process ends with it. Signalled before the drain so
                // the pipes it holds close and the drain rarely needs its timeout.
                val term = signalGroup(pgid, SIGTERM)
                if (term is GroupSignalResult.Failed) problems += cannotSignal(pgid, term)
                // The drain timeout starts once the process has exited.
                val drained = withTimeoutOrNull(drainTimeout) { joinAll(stdout, stderr) } != null
                lines.close()
                forwarder.join()
                if (!drained) {
                    val message = "loop: output drain timed out after $drainTimeout, ${dropped.get()} lines dropped so far"
                    send(ProcessEvent.Line(Stream.Stderr, message))
                }
                // Killed before the exit is reported, so what went wrong still reaches the collector.
                killTree(process, pgid, seen, problems)
                cleanedUp = true
                problems.forEach { send(ProcessEvent.Line(Stream.Stderr, it)) }
                reported = true
                send(ProcessEvent.Exited(exitCode))
            } finally {
                if (!cleanedUp) killTree(process, pgid, seen, problems)
                // Cancelled before the problems were emitted: the collector is gone, so they go to the log.
                if (!reported) problems.forEach(warn)
                readers.cancel()
            }
        }

    private suspend fun writeStdin(
        process: Process,
        stdin: String,
        more: ReceiveChannel<String>?,
    ) {
        try {
            process.outputStream.use { out ->
                out.write(stdin.toByteArray(Charsets.UTF_8))
                out.flush()
                more?.let { texts ->
                    for (text in texts) {
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
        } catch (ignored: IOException) {
            // The process exited or closed stdin before reading all of it. Its exit code tells the outcome.
        }
    }

    /** Reads [input] to its end; lines read after [lines] is closed are counted in [dropped]. */
    private fun drain(
        input: InputStream,
        stream: Stream,
        lines: SendChannel<ProcessEvent.Line>,
        dropped: AtomicInteger,
    ) {
        try {
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (lines.trySend(ProcessEvent.Line(stream, line)).isClosed) dropped.incrementAndGet()
                }
            }
        } catch (ignored: IOException) {
            // The stream was closed while the process was being killed.
        }
    }

    /**
     * Records the process's descendants every [SNAPSHOT_MS] while it runs: once the process or
     * an intermediate process exits, its children are reparented and no longer listed.
     * Best effort, a child spawned and orphaned between two snapshots is missed. The process
     * group is the main net, this one only matters for processes that left the group.
     */
    private suspend fun trackDescendants(
        process: Process,
        seen: MutableSet<ProcessHandle>,
    ) {
        while (process.isAlive) {
            process.toHandle().descendants().forEach { seen += it }
            delay(SNAPSHOT_MS)
        }
    }

    /**
     * Kills the process group [pgid] and the descendants seen in snapshots: SIGTERM first,
     * then SIGKILL when something is still alive after [gracePeriod]. Returns at once when
     * the group is known to be empty and nothing else is alive. A signal that could not be
     * sent never counts as an empty group. Problems are added to [problems].
     */
    private suspend fun killTree(
        process: Process,
        pgid: Long,
        seen: Set<ProcessHandle>,
        problems: MutableSet<String>,
    ) {
        withContext(NonCancellable) {
            // Snapshot before the root dies, once it is gone its children are reparented and no longer listed.
            val descendants = (process.toHandle().descendants().toList() + seen).distinct()
            val term = signalGroup(pgid, SIGTERM)
            if (term is GroupSignalResult.Failed) problems += cannotSignal(pgid, term)
            if (term == GroupSignalResult.Empty && !process.isAlive && descendants.none { it.isAlive }) {
                return@withContext
            }
            descendants.forEach { it.destroy() }
            process.destroy()
            val settled =
                withTimeoutOrNull(gracePeriod) {
                    while (process.isAlive || descendants.any { it.isAlive } || !groupEmpty(pgid)) delay(POLL_MS)
                } != null
            if (settled) return@withContext
            sendKill(pgid, problems)
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            reportSurvivors(process, pgid, descendants, problems)
        }
    }

    /** SIGKILL to the group, retried once when it could not be sent. */
    private suspend fun sendKill(
        pgid: Long,
        problems: MutableSet<String>,
    ) {
        repeat(KILL_ATTEMPTS) { attempt ->
            val kill = signalGroup(pgid, SIGKILL)
            if (kill !is GroupSignalResult.Failed) return
            problems += cannotSignal(pgid, kill)
            if (attempt < KILL_ATTEMPTS - 1) delay(POLL_MS)
        }
    }

    /** Waits briefly for SIGKILL to take effect, then reports the group and the pids still alive. */
    private suspend fun reportSurvivors(
        process: Process,
        pgid: Long,
        descendants: List<ProcessHandle>,
        problems: MutableSet<String>,
    ) {
        val gone =
            withTimeoutOrNull(KILL_SETTLE_MS) {
                while (process.isAlive || descendants.any { it.isAlive } || !groupEmpty(pgid)) delay(POLL_MS)
            } != null
        if (gone) return
        val pids = (listOf(process.toHandle()) + descendants).filter { it.isAlive }.map { it.pid() }
        problems += "loop: processes still alive after SIGKILL in process group $pgid, known pids: $pids"
    }

    /** True only when the probe says the group has no member; a failed probe counts as not empty. */
    private suspend fun groupEmpty(pgid: Long): Boolean = signalGroup(pgid, SIGNAL_PROBE) == GroupSignalResult.Empty

    private suspend fun signalGroup(
        pgid: Long,
        signal: String,
    ): GroupSignalResult = withContext(Dispatchers.IO) { signaller.signal(pgid, signal) }

    private fun cannotSignal(
        pgid: Long,
        failed: GroupSignalResult.Failed,
    ): String = "loop: could not signal process group $pgid: ${failed.message}"

    private companion object {
        const val POLL_MS = 100L
        const val SNAPSHOT_MS = 200L
        const val KILL_SETTLE_MS = 500L
        const val KILL_ATTEMPTS = 2
        const val SIGTERM = "TERM"
        const val SIGKILL = "KILL"
        const val SIGNAL_PROBE = "0"

        /**
         * Perl script that makes the process a session and process group leader, then execs
         * its arguments in place, so the pid, the session id and the group id are the same.
         * Exits 127 with a `loop:` line on stderr if setsid or exec fails.
         */
        const val LAUNCHER =
            "defined(setsid()) or do { print STDERR \"loop: setsid failed: \$!\\n\"; POSIX::_exit(127) }; " +
                "exec { \$ARGV[0] } @ARGV; " +
                "print STDERR \"loop: exec failed: \$!\\n\"; POSIX::_exit(127)"
    }
}

/** What sending a signal to a process group found. */
sealed interface GroupSignalResult {
    /** The group had at least one member. */
    data object Members : GroupSignalResult

    /** The group has no member left (ESRCH). */
    data object Empty : GroupSignalResult

    /** The signal could not be sent, so the group's state is unknown. */
    data class Failed(
        val message: String,
    ) : GroupSignalResult
}

/** Sends a signal (`TERM`, `KILL`, or `0` to probe) to every process of a group. Blocking. */
fun interface GroupSignaller {
    fun signal(
        pgid: Long,
        signal: String,
    ): GroupSignalResult
}

/**
 * Signals a group with the POSIX `kill` builtin, the same in dash and bash. Exit 0 means the
 * group had members; a nonzero exit with "No such process" on stderr means it is empty; any
 * other outcome is a failure. The C locale keeps that message in English.
 */
object ShellGroupSignaller : GroupSignaller {
    /** Sends signal `$1` to process group `$2`. */
    private const val KILL_GROUP = "kill -s \"\$1\" -- \"-\$2\""
    private const val NO_SUCH_PROCESS = "No such process"

    override fun signal(
        pgid: Long,
        signal: String,
    ): GroupSignalResult =
        try {
            val builder =
                ProcessBuilder("sh", "-c", KILL_GROUP, "sh", signal, pgid.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["LC_ALL"] = "C"
            val kill = builder.start()
            kill.outputStream.close()
            val stderr =
                kill.errorStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                    .trim()
            val exitCode = kill.waitFor()
            when {
                exitCode == 0 -> GroupSignalResult.Members
                NO_SUCH_PROCESS in stderr -> GroupSignalResult.Empty
                else -> GroupSignalResult.Failed("kill exited $exitCode: $stderr")
            }
        } catch (e: IOException) {
            GroupSignalResult.Failed(e.message ?: "cannot start sh")
        }
}
