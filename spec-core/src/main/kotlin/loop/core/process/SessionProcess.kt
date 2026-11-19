package loop.core.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A process started in its own session, so its process group id is its pid and
 * every process it starts stays in that group unless it leaves on purpose (plan R7).
 * The session is made through `perl` (required on PATH) calling `setsid`, like the
 * command runner; this is a local copy, a follow up moves the runner onto it.
 *
 * Stdout and stderr are merged on [Process.getInputStream]; stdin is closed at start.
 */
class SessionProcess private constructor(
    val process: Process,
) {
    private val pgid: Long = process.pid()

    /**
     * Stops the whole group: SIGTERM to the group and to the descendants seen in a
     * snapshot taken first (a second net for processes that left the group), then
     * SIGKILL to whatever is still alive after [grace]. Never cancelled half way.
     * Returns false when the final SIGKILL to the group could not be sent.
     */
    suspend fun stop(grace: Duration): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val descendants = process.toHandle().descendants().toList()
            val term = signalGroup(pgid, SIGTERM)
            if (term == GroupSignal.Empty && !process.isAlive && descendants.none { it.isAlive }) return@withContext true
            descendants.forEach { it.destroy() }
            process.destroy()
            val settled =
                withTimeoutOrNull(grace) {
                    while (process.isAlive || descendants.any { it.isAlive } || !groupEmpty()) delay(POLL_MS)
                } != null
            if (settled) return@withContext true
            val kill = signalGroup(pgid, SIGKILL)
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            kill != GroupSignal.Failed
        }

    private fun groupEmpty(): Boolean = signalGroup(pgid, SIGNAL_PROBE) == GroupSignal.Empty

    /** What sending a signal to a process group found. */
    private enum class GroupSignal { Members, Empty, Failed }

    companion object {
        private const val POLL_MS = 50L
        private const val SIGTERM = "TERM"
        private const val SIGKILL = "KILL"
        private const val SIGNAL_PROBE = "0"

        /** Sends signal `$1` to process group `$2`, with the POSIX `kill` builtin. */
        private const val KILL_GROUP = "kill -s \"\$1\" -- \"-\$2\""
        private const val NO_SUCH_PROCESS = "No such process"

        /**
         * Makes the process a session and group leader, then execs the command in place,
         * so the pid, the session id and the group id are the same. Exits 127 with a
         * `loop:` line on stderr if setsid or exec fails.
         */
        private const val LAUNCHER =
            "defined(setsid()) or do { print STDERR \"loop: setsid failed: \$!\\n\"; POSIX::_exit(127) }; " +
                "exec { \$ARGV[0] } @ARGV; " +
                "print STDERR \"loop: exec failed: \$!\\n\"; POSIX::_exit(127)"

        /**
         * Starts [command] in [cwd] in a new session, with the host environment plus [env], or with
         * [env] alone when [inheritEnv] is false (m2-03 checks). Throws [IOException] when perl cannot be started.
         */
        fun start(
            command: List<String>,
            cwd: Path,
            env: Map<String, String>,
            inheritEnv: Boolean = true,
        ): SessionProcess {
            val builder =
                ProcessBuilder(listOf("perl", "-MPOSIX=setsid", "-e", LAUNCHER) + command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
            if (!inheritEnv) builder.environment().clear()
            builder.environment().putAll(env)
            val process = builder.start()
            process.outputStream.close()
            return SessionProcess(process)
        }

        /** Exit 0: members; "No such process": empty (the C locale keeps it in English); else failed. */
        private fun signalGroup(
            pgid: Long,
            signal: String,
        ): GroupSignal =
            try {
                val builder =
                    ProcessBuilder("sh", "-c", KILL_GROUP, "sh", signal, pgid.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                builder.environment()["LC_ALL"] = "C"
                val kill = builder.start()
                kill.outputStream.close()
                val stderr = kill.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                when {
                    kill.waitFor() == 0 -> GroupSignal.Members
                    NO_SUCH_PROCESS in stderr -> GroupSignal.Empty
                    else -> GroupSignal.Failed
                }
            } catch (e: IOException) {
                GroupSignal.Failed
            }
    }
}
