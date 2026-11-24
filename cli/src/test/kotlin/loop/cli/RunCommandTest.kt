package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Plan seam 7, end to end through the installed launcher; acceptance 1, 2, 4, 5. */
class RunCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `issue ends in review and the script file is in the workspace`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val hello = fixture.workspace("t-1").resolve("hello.txt")
        assertTrue(hello.isRegularFile(), "no hello.txt in the workspace\n$result")
        assertEquals("hello from t-1", hello.readText().trim())
    }

    @Test
    fun `only eligible issues are dispatched`() {
        fixture.issue("t-1")
        val untouched =
            listOf(
                fixture.issue("t-2", labels = listOf("needs-triage")),
                fixture.issue("t-3", state = "review"),
                fixture.issue("t-4", state = "done"),
                fixture.issue("t-5", state = "needs_info"),
            ).associateWith { it.readBytes() }

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"))
        untouched.forEach { (file, bytes) -> assertContentEquals(bytes, file.readBytes(), "$file changed") }
        listOf("t-2", "t-3", "t-4", "t-5").forEach { id ->
            assertFalse(fixture.workspace(id).exists(), "workspace created for $id")
        }
        val dispatched = fixture.query("SELECT DISTINCT issue_id FROM events WHERE issue_id IS NOT NULL")
        assertEquals(listOf("default:t-1"), dispatched.map { it["issue_id"] })
    }

    @Test
    fun `question round trip through needs_info resumes in the same workspace`() {
        val file = fixture.issue("t-1")
        showDescriptionInPrompt()
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |if [ -f notes.txt ]; then
            |  cat > prompt-2.txt
            |  loop tool move review
            |else
            |  echo draft > notes.txt
            |  loop tool comment "Should the export include archived items?"
            |  loop tool move needs_info
            |fi
            |
            """.trimMargin(),
        )

        val first = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, first.exitCode, first.toString())
        assertEquals("needs_info", fixture.stateOf("t-1"), first.toString())
        assertContains(file.readText(), "agent ")
        assertContains(file.readText(), ": Should the export include archived items?")
        assertTrue(fixture.workspace("t-1").resolve("notes.txt").isRegularFile(), "no notes.txt\n$first")

        val second = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, second.exitCode, second.toString())
        assertEquals("No eligible issue.", second.stdout.trim())
        assertEquals(1, attemptsStarted("t-1"))

        file.writeText(
            file.readText().trimEnd() + "\n\n2026-11-08 soule: Yes, include them.\n",
        )
        setState(file, "needs_info", "todo")

        val third = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, third.exitCode, third.toString())
        assertEquals("review", fixture.stateOf("t-1"), third.toString())
        assertEquals(2, attemptsStarted("t-1"))
        val workspace = fixture.workspace("t-1")
        assertTrue(workspace.resolve("notes.txt").isRegularFile())
        assertContains(workspace.resolve("prompt-2.txt").readText(), "Yes, include them.")
    }

    @Test
    fun `bl-06 a template without issue description warns at startup that the agent misses the body and the answers`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertContains(result.stderr.lines(), DESCRIPTION_WARNING, result.toString())
    }

    @Test
    fun `m3-09 with the command runner, loop run says at startup that only the prompt limit and the turn cap hold`() {
        fixture.issue("t-1")
        showDescriptionInPrompt()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stderr.lines().filter { "event=context_occupancy" in it }
        assertEquals(1, lines.size, result.toString())
        val line = lines.single()
        assertTrue(KEY_VALUE_LINE.matches(line), line)
        assertContains(line, "level=warn")
        assertContains(line, "runner=command")
        assertContains(
            line,
            "reason=\"the command runner reports no context occupancy, " +
                "so only the prompt limit and the turn cap (agent.max_turns) hold for it\"",
        )
        // Said at startup, before the run started line and before any attempt.
        val index = result.stderr.lines().indexOf(line)
        val started = result.stderr.lines().indexOfFirst { "event=run outcome=started" in it }
        assertTrue(index in 0 until started, result.stderr)
    }

    @Test
    fun `m3-11 the command runner reports no occupancy, so its session never rotates, its turns run on one session`() {
        fixture.issue("t-1")
        fixture.writeWorkflow(extra = "agent:\n  max_turns: 3\ncontext:\n  max_rotations: 1")
        fixture.agentScript.writeText("#!/bin/sh\necho working\n")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val kinds = fixture.query("SELECT kind, data FROM events WHERE issue_id = 'default:t-1' ORDER BY seq")
        assertEquals(0, kinds.count { it["kind"] == "session_rotated" }, "$kinds")
        assertEquals(0, kinds.count { it["kind"] == "context_usage" }, "$kinds")
        assertEquals(3, kinds.count { it["kind"] == "turn_completed" }, "$kinds")
        val threads =
            kinds
                .filter {
                    it["kind"] == "turn_started"
                }.map { Regex("\"thread_id\":\"([^\"]+)\"").find(it["data"].orEmpty())?.groupValues?.get(1) }
        assertEquals(1, threads.distinct().size, "$threads")
    }

    @Test
    fun `bl-06 a template that uses issue description gives no such warning`() {
        fixture.issue("t-1")
        showDescriptionInPrompt()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertFalse(result.stderr.contains("issue.description"), result.toString())
    }

    @Test
    fun `bl-06 the description warning never stops the run, preflight passes and the issue is dispatched`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr.lines(), DESCRIPTION_WARNING, result.toString())
        val preflights = fixture.query("SELECT json_extract(data, '\$.result') AS result FROM events WHERE kind = 'preflight'")
        assertEquals(listOf(mapOf("result" to "ok")), preflights)
        assertEquals(1, attemptsStarted("t-1"), result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
    }

    @Test
    fun `rework after review resumes in the same workspace`() {
        val file = fixture.issue("t-1")
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |if [ -f work.txt ]; then
            |  echo second > second.txt
            |else
            |  echo first > work.txt
            |fi
            |loop tool move review
            |
            """.trimMargin(),
        )

        val first = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, first.exitCode, first.toString())
        assertEquals("review", fixture.stateOf("t-1"), first.toString())

        val second = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, second.exitCode, second.toString())
        assertEquals("No eligible issue.", second.stdout.trim())
        assertEquals(1, attemptsStarted("t-1"))

        setState(file, "review", "todo")

        val third = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, third.exitCode, third.toString())
        assertEquals("review", fixture.stateOf("t-1"), third.toString())
        assertEquals(2, attemptsStarted("t-1"))
        val workspace = fixture.workspace("t-1")
        assertTrue(workspace.resolve("work.txt").isRegularFile())
        assertTrue(workspace.resolve("second.txt").isRegularFile(), "the second attempt did not see work.txt\n$third")
    }

    /** The issue description, with its comments, reaches the agent in the prompt. */
    private fun showDescriptionInPrompt() {
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("{{ issue.title }}\n", "{{ issue.title }}\n{{ issue.description }}\n"),
        )
    }

    /** Rewrites only the `state:` line, so a comment already in the file survives. */
    private fun setState(
        file: Path,
        from: String,
        to: String,
    ) {
        val text = file.readText()
        val rewritten = text.replace("\nstate: $from\n", "\nstate: $to\n")
        assertNotEquals(text, rewritten)
        file.writeText(rewritten)
    }

    private fun attemptsStarted(issueId: String): Int =
        fixture
            .query("SELECT count(*) AS n FROM events WHERE kind = 'attempt_started' AND issue_id = 'default:$issueId'")
            .single()["n"]!!
            .toInt()

    @Test
    fun `store lives under LOOP_HOME and every row has a project id`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(fixture.storeFile.isRegularFile(), "no store at ${fixture.storeFile}")
        val total = fixture.query("SELECT count(*) AS n FROM events").single()["n"]!!.toInt()
        assertTrue(total > 0, "no event recorded")
        val missing =
            fixture.query("SELECT count(*) AS n FROM events WHERE project_id IS NULL OR project_id = ''").single()["n"]
        assertEquals("0", missing)
        val projects = fixture.query("SELECT DISTINCT project_id FROM events").map { it["project_id"] }
        assertEquals(listOf(fixture.projectId()), projects)

        val unexpected =
            Files
                .walk(fixture.projectDir)
                .use { paths ->
                    paths
                        .filter { it.isRegularFile() }
                        .map { it.relativeTo(fixture.projectDir).toString() }
                        .toList()
                }.filterNot { it == "WORKFLOW.md" || it.startsWith("workspaces/") || it.matches(ISSUE_FILE) }
        assertEquals(emptyList(), unexpected, "files written under the project")
        assertTrue(
            fixture.homeDir
                .toFile()
                .listFiles()
                .isNullOrEmpty(),
            "files written under HOME",
        )
    }

    @Test
    fun `stderr lines carry the issue and session ids in key value form`() {
        fixture.issue("t-1")
        // Keeps the bl-06 startup warning, which is not a log line, off stderr.
        showDescriptionInPrompt()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val sessionId =
            fixture
                .query("SELECT session_id FROM events WHERE session_id IS NOT NULL LIMIT 1")
                .single()["session_id"]!!
        val lines = result.stderr.lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "no log line on stderr")
        lines.forEach { line ->
            assertTrue(KEY_VALUE_LINE.matches(line), "not key=value: $line")
            assertContains(line, " outcome=")
        }
        assertTrue(
            lines.any {
                "issue_id=t-1 " in it && "issue_identifier=t-1" in it && "session_id=$sessionId" in it
            },
            "no line with issue_id, issue_identifier and session_id\n${result.stderr}",
        )
    }

    @Test
    fun `stdout summarizes the attempts`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        assertTrue(lines.any { SUMMARY_LINE.matches(it) }, "no summary line for t-1\n${result.stdout}")
        assertEquals("1 attempt, 1 succeeded, 0 failed.", lines.last())
    }

    @Test
    fun `stdout says when no issue is eligible`() {
        fixture.issue("t-2", labels = listOf("needs-triage"))

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("No eligible issue.", result.stdout.trim())
        assertContains(result.stderr, "event=poll outcome=idle")
    }

    @Test
    fun `run defaults to WORKFLOW md in the current folder`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", "--once", cwd = fixture.projectDir)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"))
    }

    @Test
    fun `missing WORKFLOW md exits nonzero with a message naming the path`() {
        val missing = root.resolve("nowhere/WORKFLOW.md")

        val result = fixture.loop("run", missing.toString(), "--once")

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, missing.toString())
        assertFalse(fixture.storeFile.exists(), "store created for a missing workflow")
    }

    @Test
    fun `a configured state the tracker does not define stops startup`() {
        val file = fixture.issue("t-1")
        val before = file.readBytes()
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("active_states: [todo]", "active_states: [ready]"),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(
            result.stderr,
            "tracker.active_states has \"ready\", which is not a state of the files tracker. " +
                "Valid states: todo, in_progress, review, needs_info, done, canceled.",
        )
        assertContentEquals(before, file.readBytes())
        assertFalse(fixture.storeFile.exists(), "store created for an invalid workflow")
    }

    @Test
    fun `unknown variable in the template launches no attempt`() {
        fixture.issue("t-1")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("Work on {{ issue.identifier }}", "Work on {{ issue.nope }}"),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(1, result.exitCode, result.toString())
        assertContains(
            result.stderr.lines(),
            "loop run: the prompt template uses \"issue.nope\", which is not a variable.",
            result.toString(),
        )
        assertFalse(fixture.storeFile.exists(), "store created for an invalid template")
        assertFalse(fixture.workspace("t-1").exists(), "workspace created for an invalid template")
    }

    @Test
    fun `run once records a passing preflight event`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val preflights =
            fixture.query(
                "SELECT json_extract(data, '\$.result') AS result, issue_id, attempt_id FROM events WHERE kind = 'preflight'",
            )
        assertEquals(listOf(mapOf("result" to "ok", "issue_id" to null, "attempt_id" to null)), preflights)
    }

    @Test
    fun `unknown filter in the template launches no attempt`() {
        fixture.issue("t-1")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("{{ issue.title }}", "{{ issue.title | nope }}"),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(1, result.exitCode, result.toString())
        assertContains(result.stderr, "loop run: the prompt template uses the filter \"nope\", which does not exist.")
        assertNoStackTrace(result)
        assertFalse(fixture.storeFile.exists(), "store created for an invalid template")
        assertFalse(fixture.workspace("t-1").exists(), "workspace created for an invalid template")
    }

    @Test
    fun `unset variable fails startup naming it`() {
        fixture.issue("t-1")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("root: ./workspaces", "root: \$WS_ROOT"),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("WS_ROOT" to null))

        assertEquals(1, result.exitCode, result.toString())
        assertContains(result.stderr, "loop run: workspace.root uses \$WS_ROOT, which is not set.")
        assertNoStackTrace(result)
        assertFalse(fixture.storeFile.exists(), "store created for an unset variable")
        assertEquals("todo", fixture.stateOf("t-1"))
    }

    @Test
    fun `set variable resolves the workspace root`() {
        fixture.issue("t-1")
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("root: ./workspaces", "root: \$WS_ROOT"),
        )
        val workspaces = fixture.projectDir.resolve("workspaces").toString()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("WS_ROOT" to workspaces))

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(fixture.workspace("t-1").resolve("hello.txt").isRegularFile(), result.toString())
    }

    @Test
    fun `a blocker without an issue file is warned about in the operator log and the issue waits`() {
        val file = fixture.issue("t-2")
        file.writeText(file.readText().replace("blocked_by: []", "blocked_by: [t-9]"))

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val warning = result.stderr.lines().filter { "level=warn" in it && "t-2: blocked_by t-9 has no issue file" in it }
        assertEquals(1, warning.size, result.toString())
        assertContains(warning.single(), "so t-2 stays blocked")
        assertEquals("todo", fixture.stateOf("t-2"))
    }

    @Test
    fun `17_3 a malformed issue file is logged as omitted and the other issues are dispatched`() {
        fixture.issue("t-1")
        val broken = fixture.issueFile("t-2").resolveSibling("t-2-broken.md")
        broken.writeText("---\nid: t-2\nstate: todo\n---\nno title\n")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"))
        // Logged on each state list read: the startup sweep of terminal states, then the candidate fetch.
        val warnings = result.stderr.lines().filter { "level=warn event=tracker outcome=failed" in it && "t-2-broken.md" in it }
        assertTrue(warnings.isNotEmpty(), result.toString())
        warnings.forEach { assertContains(it, "reason=\"Malformed issue at ") }
        assertFalse(fixture.workspace("t-2").exists(), "workspace created for the malformed t-2")
    }

    @Test
    fun `explicit path wins over WORKFLOW md in the current folder`() {
        fixture.issue("t-1")
        val elsewhere = root.resolve("elsewhere").createDirectories()
        elsewhere.resolve("WORKFLOW.md").writeText("---\n- not a map\n---\nbroken\n")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", cwd = elsewhere)

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"))
    }

    @Test
    fun `missing default WORKFLOW md exits nonzero with a clean message`() {
        val empty = root.resolve("empty").createDirectories()

        val result = fixture.loop("run", "--once", cwd = empty)

        assertEquals(1, result.exitCode, result.toString())
        assertContains(result.stderr, "loop run: no workflow file at ")
        assertContains(result.stderr, "WORKFLOW.md.")
        assertNoStackTrace(result)
        assertFalse(fixture.storeFile.exists(), "store created for a missing workflow")
    }

    private fun assertNoStackTrace(result: LoopResult) {
        assertFalse("Exception" in result.stderr || "\tat " in result.stderr, "stack trace on stderr\n$result")
    }

    @Test
    fun `SIGTERM to loop run kills the agent processes before the JVM exits`() {
        fixture.issue("t-1")
        val pidFile = root.resolve("agent-child.pid")
        // The agent leaves a background child and keeps the attempt running until loop run is stopped.
        fixture.agentScript.writeText("#!/bin/sh\nsleep 68 &\necho \$! > '$pidFile'\nsleep 30\n")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            val pid =
                awaitPid(pidFile) ?: run {
                    running.process.destroy()
                    fail("the agent never wrote its child pid\n${running.await(30)}")
                }
            // SIGTERM to the JVM, as a service manager would send.
            running.process.destroy()
            val result = running.await(30)

            assertTrue(awaitDead(pid), "the agent child outlived loop run\n$result")
            // m1-34: the run unwound within the shutdown grace, so the stop is clean.
            assertEquals(0, result.exitCode, result.toString())
            assertSignalStop(result)
        } finally {
            running.process.destroyForcibly()
            readPid(pidFile)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun `SIGINT to loop run stops it cleanly with exit 0`() {
        fixture.issue("t-1")
        val pidFile = root.resolve("agent.pid")
        fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$pidFile'\nsleep 30\n")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            val pid =
                awaitPid(pidFile) ?: run {
                    running.process.destroy()
                    fail("the agent never wrote its pid\n${running.await(30)}")
                }
            // SIGINT to the JVM, as Ctrl-C in a terminal sends.
            signal("INT", running.process.pid())
            val result = running.await(30)

            assertTrue(awaitDead(pid), "the agent outlived loop run\n$result")
            assertEquals(0, result.exitCode, result.toString())
            assertSignalStop(result)
        } finally {
            running.process.destroyForcibly()
            readPid(pidFile)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun `SIGTERM to loop run --once keeps the signal exit code`() {
        fixture.issue("t-1")
        val pidFile = root.resolve("agent.pid")
        fixture.agentScript.writeText("#!/bin/sh\necho \$\$ > '$pidFile'\nsleep 30\n")
        val running = fixture.start("run", fixture.workflow.toString(), "--once")
        try {
            val pid =
                awaitPid(pidFile) ?: run {
                    running.process.destroy()
                    fail("the agent never wrote its pid\n${running.await(30)}")
                }
            running.process.destroy()
            val result = running.await(30)

            assertTrue(awaitDead(pid), "the agent outlived loop run\n$result")
            // m1-34: --once is unchanged, a signal keeps the JVM's code, 128 + 15.
            assertEquals(143, result.exitCode, result.toString())
        } finally {
            running.process.destroyForcibly()
            readPid(pidFile)?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    /** The run end line of a stop by a signal, and the released run file. */
    private fun assertSignalStop(result: LoopResult) {
        val end = result.stderr.lines().filter { "event=run" in it && "outcome=completed" in it }
        assertEquals(1, end.size, result.toString())
        assertContains(end.single(), "reason=signal")
        assertContains(end.single(), "attempts_canceled=1")
        assertFalse(fixture.loopHome.resolve("runs/${fixture.projectId()}.json").exists(), "run file left\n$result")
    }

    /** Sends SIG[name] to [pid] with kill(1). */
    private fun signal(
        name: String,
        pid: Long,
    ) {
        val kill = ProcessBuilder("kill", "-$name", pid.toString()).redirectErrorStream(true).start()
        assertTrue(kill.waitFor(10, TimeUnit.SECONDS) && kill.exitValue() == 0, kill.inputStream.bufferedReader().readText())
    }

    @Test
    fun `three turns on one session then review`() {
        fixture.issue("t-1")
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |n="${'$'}LOOP_TURN_NUMBER"
            |cat > "turn-${'$'}n.stdin"
            |echo "${'$'}LOOP_THREAD_ID" > "turn-${'$'}n.thread"
            |if [ "${'$'}n" = "3" ]; then loop tool move review; fi
            |
            """.trimMargin(),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val workspace = fixture.workspace("t-1")
        val threads = (1..3).map { workspace.resolve("turn-$it.thread").readText().trim() }
        assertEquals(1, threads.toSet().size, threads.toString())
        assertTrue(threads.first().isNotEmpty(), threads.toString())
        assertContains(workspace.resolve("turn-1.stdin").readText(), "Work on t-1")
        listOf(2, 3).forEach { n ->
            val input = workspace.resolve("turn-$n.stdin").readText()
            assertTrue(input.startsWith("Continue working on"), "turn $n stdin: $input")
        }
        assertFalse(workspace.resolve("turn-4.stdin").exists())
        assertEquals(1, fixture.query("SELECT DISTINCT attempt_id FROM events WHERE kind = 'attempt_started'").size)
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE phase = 'LaunchingAgentProcess'").size)

        val trace = fixture.loop("trace", "t-1", "--full-ids", cwd = fixture.projectDir)

        assertEquals(0, trace.exitCode, trace.toString())
        val started = trace.stdout.lines().filter { "turn_started" in it }
        assertEquals(3, started.size, trace.stdout)
        started.forEachIndexed { index, line ->
            assertContains(line, "number=${index + 1}/")
            assertContains(line, "thread=${threads.first()}")
        }
        assertEquals(1, trace.stdout.lines().count { it.startsWith("Attempt ") }, trace.stdout)
    }

    private fun readPid(file: Path): Long? = if (file.exists()) file.readText().trim().toLongOrNull() else null

    private fun awaitPid(file: Path): Long? {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (readPid(file) == null && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return readPid(file)
    }

    private fun awaitDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        return !isAlive(pid)
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private companion object {
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
        val ISSUE_FILE = Regex("""\.loop/issues/[^/]+\.md""")
        private const val VALUE = """("(?:[^"\\]|\\.)*"|[^\s"]+)"""
        val KEY_VALUE_LINE = Regex("""^[a-z_]+=$VALUE( [a-z_]+=$VALUE)*$""")
        val SUMMARY_LINE = Regex("""^t-1\s+succeeded\s+review\s+\d+s$""")
        const val DESCRIPTION_WARNING =
            "loop run: warning: the prompt template never uses issue.description, " +
                "so the agent will not see the issue body or the answers under ## Comments."
    }
}
