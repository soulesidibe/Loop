package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kotlinx.coroutines.runBlocking
import loop.api.RunId
import loop.core.events.RunStopped
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `loop stop [path-to-WORKFLOW.md]` (m1-09): stops the live `loop run` of the project, found
 * through its run file. Prints to stdout on success, to stderr with the `loop stop:` prefix on failure.
 */
class StopCommand(
    private val host: Host,
) : GuardedCommand("stop", host) {
    private val workflowPath by argument(
        name = "path-to-WORKFLOW.md",
        help = "The workflow file, default ./WORKFLOW.md",
    ).optional()

    override fun help(context: Context): String = "Stop the running loop run of the project, keeping its workspaces"

    override fun body(scope: RedactorScope) {
        val project = host.openProject(workflowPath ?: DEFAULT_WORKFLOW, Access.WRITE, scope)
        val files = RunFiles(host.loopHome, project.projectId)
        report(probeRunFile(files), files, project, retried = false)
    }

    /** Prints what [probe] found, or stops the live run it names. */
    private fun report(
        probe: RunFiles.Probe,
        files: RunFiles,
        project: OpenedProject,
        retried: Boolean,
    ) {
        val projectId = project.projectId
        when (probe) {
            RunFiles.Probe.Nothing -> echo("No run in progress for project ${projectId.value}. Nothing to stop.")
            is RunFiles.Probe.Stale ->
                echo(
                    probe.record?.let { "Removed a stale run file for pid ${it.pid}, that process is gone. Nothing to stop." }
                        ?: "Removed a stale run file that could not be read. Nothing to stop.",
                )
            is RunFiles.Probe.Unreadable -> failUnreadable(files, probe.error)
            is RunFiles.Probe.Live -> {
                val record = probe.record
                val handle = ProcessHandle.of(record.pid).filter { it.isAlive }.orElse(null)
                when {
                    // The run ended between the probe and this check: look again, once.
                    handle == null && !retried -> report(probeRunFile(files), files, project, retried = true)
                    handle == null || !record.startMatches(handle) ->
                        fail(
                            "the run file at ${files.runFile} names pid ${record.pid}, which is not the run that holds it. " +
                                "Nothing was stopped",
                            EXIT_RUN_FILE,
                        )
                    else -> stop(files, record, handle, project)
                }
            }
        }
    }

    /** [RunFiles.probe], an I/O failure being an unreadable run file. */
    private fun probeRunFile(files: RunFiles): RunFiles.Probe =
        try {
            files.probe()
        } catch (e: IOException) {
            failUnreadable(files, e)
        }

    private fun failUnreadable(
        files: RunFiles,
        error: Throwable,
    ): Nothing = fail("cannot read the run file at ${files.runFile}: ${error.sentence()}", EXIT_RUN_FILE)

    /**
     * Creates the stop marker, sends SIGTERM to the checked [handle] and waits [STOP_WAIT_SECONDS].
     * Never SIGKILL: the run must unwind to kill its agents' process groups and record the stop.
     */
    private fun stop(
        files: RunFiles,
        record: RunRecord,
        handle: ProcessHandle,
        project: OpenedProject,
    ) {
        val run = record.runId.take(SHORT_ID)
        try {
            Files.createDirectories(files.dir)
            Files.writeString(files.stopMarker, record.runId)
        } catch (e: IOException) {
            failUnreadable(files, e)
        }
        val began = System.nanoTime()
        if (!handle.destroy()) {
            Files.deleteIfExists(files.stopMarker)
            fail("could not send SIGTERM to pid ${record.pid}", EXIT_TIMEOUT)
        }
        val exited =
            try {
                handle.onExit().get(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
                true
            } catch (e: TimeoutException) {
                false
            }
        if (!exited) {
            fail(
                "run $run (pid ${record.pid}) did not exit within ${STOP_WAIT_SECONDS}s of SIGTERM. " +
                    "The run file is kept and its agent processes may still be running",
                EXIT_TIMEOUT,
            )
        }
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began)
        val canceled =
            attemptsCanceled(project, RunId(record.runId))
                ?.let { count -> ", $count ${if (count == 1) "attempt" else "attempts"} canceled" }
                ?: ""
        echo("Stopped run $run (pid ${record.pid}) in ${seconds}s$canceled, workspaces kept.")
    }

    /** `attempts_canceled` of the run's `run_stopped` event, or null when it is missing or the store cannot be read. */
    private fun attemptsCanceled(
        project: OpenedProject,
        runId: RunId,
    ): Int? {
        val store = project.store().getOrNull() ?: return null
        return store.use {
            runBlocking { store.eventsForRun(project.projectId, runId) }
                .getOrNull()
                ?.lastOrNull { it.event.kind == RunStopped.name }
                ?.event
                ?.data
                ?.get(RunStopped.ATTEMPTS_CANCELED)
                ?.toIntOrNull()
        }
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop stop: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val DEFAULT_WORKFLOW = "WORKFLOW.md"
        const val EXIT_RUN_FILE = 3
        const val EXIT_TIMEOUT = 4
        const val STOP_WAIT_SECONDS = 15L
        const val SHORT_ID = 8
    }
}
