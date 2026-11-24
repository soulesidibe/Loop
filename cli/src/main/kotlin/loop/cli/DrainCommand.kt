package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import java.io.IOException

/**
 * `loop drain [path-to-WORKFLOW.md]` (m4-10, bl-110): marks the live `loop run` of the project as draining, through the
 * drain marker next to its run file. From its next poll the run dispatches no candidate, lets its running attempts
 * finish, verify and deliver, then stops by itself with exit code 0. The command returns at once: a drain can wait for a
 * CI wait of up to `ci_timeout_ms`, and `loop stop` still stops the run at once. With no live run it says so and exits
 * nonzero.
 */
class DrainCommand(
    private val host: Host,
) : GuardedCommand("drain", host) {
    private val workflowPath by argument(
        name = "path-to-WORKFLOW.md",
        help = "The workflow file, default ./WORKFLOW.md",
    ).optional()

    override fun help(context: Context): String =
        "Let the running attempts of the project's run finish, start nothing new, then stop the run"

    override fun body(scope: RedactorScope) {
        val project = host.openProject(workflowPath ?: DEFAULT_WORKFLOW, Access.WRITE, scope)
        val files = RunFiles(host.loopHome, project.projectId)
        val probe =
            try {
                files.probe()
            } catch (e: IOException) {
                RunFiles.Probe.Unreadable(e)
            }
        val noRun = "no run in progress for project ${project.projectId.value}. Nothing to drain"
        val record =
            when (probe) {
                RunFiles.Probe.Nothing -> fail(noRun, EXIT_NO_RUN)
                is RunFiles.Probe.Stale -> fail("$noRun, removed a stale run file", EXIT_NO_RUN)
                is RunFiles.Probe.Unreadable ->
                    fail("cannot read the run file at ${files.runFile}: ${probe.error.sentence()}", EXIT_RUN_FILE)
                is RunFiles.Probe.Live -> probe.record
            }
        val alive = ProcessHandle.of(record.pid).filter { it.isAlive && record.startMatches(it) }.isPresent
        if (!alive) {
            val holder = "the run file at ${files.runFile} names pid ${record.pid}, which is not the run that holds it"
            fail("$holder. Nothing to drain", EXIT_RUN_FILE)
        }
        val run = record.runId.take(SHORT_ID)
        val asked =
            try {
                files.requestDrain(record.runId)
            } catch (e: IOException) {
                fail("cannot write the drain marker at ${files.drainMarker}: ${e.ioSentence()}", EXIT_RUN_FILE)
            }
        if (!asked) {
            echo("Run $run (pid ${record.pid}) is already draining.")
            return
        }
        echo(
            "Run $run (pid ${record.pid}) is draining: nothing new starts, the running attempts finish, then the run stops. " +
                "loop status shows what still runs, loop stop stops it at once.",
        )
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop drain: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val DEFAULT_WORKFLOW = "WORKFLOW.md"
        const val EXIT_NO_RUN = 1
        const val EXIT_RUN_FILE = 3
        const val SHORT_ID = 8
    }
}
