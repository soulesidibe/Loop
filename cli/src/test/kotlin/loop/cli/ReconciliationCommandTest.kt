package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reconciliation and startup cleanup (m1-16, spec 8.5 and 8.6), end to end through the installed
 * launcher: a human moves a running issue in the tracker, and `loop run` stops its agent on the next
 * tick; a terminal issue's workspace left by an earlier run is removed when `loop run` starts.
 */
class ReconciliationCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Where the slow agent marks each issue it started on, with its pid, outside the project. */
    private val startedDir by lazy { root.resolve("started").createDirectories() }

    /** An agent that marks its issue as started, then runs until it is stopped. */
    private fun useSlowAgent() {
        fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$startedDir'/\"\$LOOP_ISSUE_IDENTIFIER\"\nsleep 60\n")
    }

    /** Starts `loop run` with the slow agent and waits until the agent of [identifier] runs. */
    private fun startRunning(identifier: String): LoopFixture.RunningLoop {
        useSlowAgent()
        val running = fixture.start("run", fixture.workflow.toString())
        if (!awaitTrue { startedDir.resolve(identifier).exists() }) {
            stopForcibly(running)
            fail("the agent never started\n${running.await(30)}")
        }
        return running
    }

    private fun agentAlive(identifier: String): Boolean {
        val pid =
            startedDir
                .resolve(identifier)
                .readText()
                .trim()
                .toLong()
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /** Waits for the attempt of [identifier] to be recorded as finished, then returns its data. */
    private fun awaitFinished(identifier: String): String? {
        var data: String? = null
        awaitTrue {
            data =
                fixture
                    .query("SELECT data FROM events WHERE kind = 'attempt_finished' AND issue_identifier = '$identifier'")
                    .singleOrNull()
                    ?.get("data")
            data != null
        }
        return data
    }

    /** The data of every `hook_finished` event recorded so far. */
    private fun hookEvents(): List<String> = fixture.query("SELECT data FROM events WHERE kind = 'hook_finished'").map { it["data"] ?: "" }

    /** Waits for a `hook_finished` event of [hook], then returns its data. */
    private fun awaitHook(hook: String): String? {
        var data: String? = null
        awaitTrue {
            data = hookEvents().firstOrNull { "\"hook\":\"$hook\"" in it }
            data != null
        }
        return data
    }

    private fun stopForcibly(running: LoopFixture.RunningLoop) {
        running.process.descendants().forEach { it.destroyForcibly() }
        running.process.destroyForcibly()
    }

    @Test
    fun `a running issue moved to canceled stops its agent and removes its worktree but keeps its branch`() {
        fixture.issue("t-1")
        val repo = fixture.useGitWorktrees()
        val running = startRunning("t-1")
        try {
            val workspace = fixture.workspace("t-1").toRealPath()
            assertTrue(workspace.toString() in repo.worktrees(), repo.worktrees().toString())

            fixture.issue("t-1", state = "canceled")
            val finished = awaitFinished("t-1") ?: fail("the attempt never finished\n${running.process.isAlive}")
            // The hook deletes the folder with `git worktree remove`, then prunes and exits, and Loop
            // records `hook_finished` only after the exit. So the test waits for the event, not for the
            // folder, before it reads the event store or git (bl-15).
            val beforeRemove = awaitHook("before_remove") ?: fail("before_remove never finished\n${hookEvents()}")
            val removed = awaitTrue { !fixture.workspace("t-1").exists() }

            assertContains(finished, "\"outcome\":\"canceled\"")
            assertContains(finished, "\"reason\":\"terminal\"")
            assertFalse(agentAlive("t-1"), "the agent outlived the move to canceled")
            assertTrue(removed, "the workspace of a canceled issue is still there")
            assertFalse(workspace.toString() in repo.worktrees(), repo.worktrees().toString())
            assertTrue(repo.hasBranch("loop/t-1"), "the branch of a canceled issue was deleted")
            assertContains(beforeRemove, "\"exit_code\":\"0\"")
            assertEquals("canceled", fixture.stateOf("t-1"))

            val trace = fixture.loop("trace", "t-1", cwd = fixture.projectDir)
            assertEquals(0, trace.exitCode, trace.toString())
            assertContains(trace.stdout, "CanceledByReconciliation")
            val header = trace.stdout.lines().single { it.startsWith("Attempt 1 ") }
            assertTrue(Regex("""canceled in \d+s, issue terminal$""").containsMatchIn(header), header)
        } finally {
            val stop = fixture.loop("stop", fixture.workflow.toString())
            running.await(30)
            assertEquals(0, stop.exitCode, stop.toString())
            stopForcibly(running)
        }
    }

    @Test
    fun `a running issue moved to review stops its agent and keeps its workspace`() {
        fixture.issue("t-1")
        val running = startRunning("t-1")
        try {
            fixture.workspace("t-1").resolve("work.txt").writeText("work in progress\n")

            fixture.issue("t-1", state = "review")
            val finished = awaitFinished("t-1") ?: fail("the attempt never finished")

            assertContains(finished, "\"outcome\":\"canceled\"")
            assertContains(finished, "\"reason\":\"not_active\"")
            assertTrue(awaitTrue { !agentAlive("t-1") }, "the agent outlived the move to review")
            assertTrue(fixture.workspace("t-1").resolve("work.txt").exists(), "the workspace of a review issue was removed")
            assertEquals("review", fixture.stateOf("t-1"))
            // A stop by reconciliation is no failure: nothing is retried.
            assertEquals(emptyList(), fixture.query("SELECT kind FROM events WHERE kind = 'retry_scheduled'"))
            assertContains(fixture.humanLog.readText(), "canceled, the issue left the active states")
        } finally {
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val run = running.await(30)
            assertEquals(0, stop.exitCode, stop.toString())
            // Only loop stop's own cancels count: the reconciled attempt had already ended.
            assertContains(stop.stdout, "0 attempts canceled", message = "$stop\n$run")
            stopForcibly(running)
        }
    }

    @Test
    fun `loop run removes a terminal issue's leftover workspace at startup and keeps its branch`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        val repo = fixture.useGitWorktrees()
        val first = fixture.loop("run", "--once")
        assertEquals(0, first.exitCode, first.toString())
        assertTrue(fixture.workspace("t-1").exists() && fixture.workspace("t-2").exists(), first.toString())
        // A human marks t-1 done after review while no Loop runs; t-2 stays in review.
        fixture.issue("t-1", state = "done")
        fixture.issue("t-2", state = "review")

        val second = fixture.loop("run", "--once")

        assertEquals(0, second.exitCode, second.toString())
        assertContains(second.stdout, "No eligible issue.")
        assertFalse(fixture.workspace("t-1").exists(), "the workspace of a done issue survived the startup\n$second")
        assertTrue(repo.hasBranch("loop/t-1"), "the branch of a done issue was deleted")
        assertFalse(repo.worktrees().any { it.endsWith("/t-1") }, repo.worktrees().toString())
        assertTrue(fixture.workspace("t-2").exists(), "the workspace of a review issue was removed")
        assertContains(second.stderr, "event=workspace outcome=completed")
    }

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 100L
    }
}
