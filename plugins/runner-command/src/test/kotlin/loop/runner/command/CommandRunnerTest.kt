package loop.runner.command

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CommandRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var scratch: Path

    private val timeout = 10.seconds

    private fun context(env: Map<String, String> = emptyMap()): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "m1-02",
                    title = "Walking skeleton",
                    state = "todo",
                    priority = null,
                    labels = listOf("ready-for-agent"),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = env,
        )

    private fun runAll(
        command: String,
        prompt: String = "",
        env: Map<String, String> = emptyMap(),
    ): List<RunnerEvent> =
        runBlocking {
            withTimeout(timeout) { CommandRunner(command).run(context(env), prompt).toList() }
        }

    private fun List<RunnerEvent>.lines(stream: Stream): List<String> =
        filterIsInstance<RunnerEvent.Output>().filter { it.stream == stream }.map { it.line }

    @Test
    fun `the command runs in the workspace`() {
        val events = runAll("pwd -P")

        assertEquals(listOf(workspace.toRealPath().toString()), events.lines(Stream.Stdout))
    }

    @Test
    fun `the context env is visible to the script`() {
        val events = runAll("printf '%s\\n' \"\$LOOP_ISSUE_IDENTIFIER\"", env = mapOf("LOOP_ISSUE_IDENTIFIER" to "m1-02"))

        assertEquals(listOf("m1-02"), events.lines(Stream.Stdout))
    }

    @Test
    fun `the session id is the thread id and the turn id, exported to the script`() {
        val events = runAll("printf '%s\\n%s\\n' \"\$LOOP_SESSION_ID\" \"\$LOOP_TURN_ID\"")

        val started = assertIs<RunnerEvent.SessionStarted>(events.first())
        val completed = assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertEquals("${started.threadId}-${completed.turnId.value}", started.sessionId.value)
        assertEquals(started.sessionId, completed.sessionId)
        assertEquals(completed.turnId, started.turnId)
        assertEquals(listOf(started.sessionId.value, completed.turnId.value), events.lines(Stream.Stdout))
    }

    @Test
    fun `the prompt arrives on stdin`() {
        val events = runAll("cat", prompt = "first line\nsecond line\n")

        assertEquals(listOf("first line", "second line"), events.lines(Stream.Stdout))
    }

    @Test
    fun `a large prompt to a script that never reads stdin does not deadlock`() {
        val prompt = "x".repeat(256 * 1024)

        val events = runAll("sleep 0.2; exit 0", prompt = prompt)

        assertIs<RunnerEvent.TurnCompleted>(events.last())
    }

    @Test
    fun `the second turn keeps the thread id, gets turn number 2 and its input on stdin`() {
        val session =
            checkNotNull(
                CommandRunner(
                    "cat; printf '%s|%s|%s\\n' \"\$LOOP_THREAD_ID\" \"\$LOOP_TURN_NUMBER\" \"\$LOOP_SESSION_ID\"",
                ).openSession(context()),
            )

        val (first, second) =
            runBlocking {
                withTimeout(timeout) {
                    val one = session.runTurn("full prompt\n", 1).toList()
                    val two = session.runTurn("guidance\n", 2).toList()
                    session.stop()
                    one to two
                }
            }

        val started = assertIs<RunnerEvent.SessionStarted>(first.first())
        val resumed = assertIs<RunnerEvent.TurnStarted>(second.first())
        assertEquals(started.threadId, resumed.threadId)
        assertEquals(2, resumed.turn)
        assertEquals(
            listOf("full prompt", "${started.threadId}|1|${started.sessionId.value}"),
            first.lines(Stream.Stdout),
        )
        assertEquals(
            listOf("guidance", "${started.threadId}|2|${resumed.sessionId.value}"),
            second.lines(Stream.Stdout),
        )
    }

    @Test
    fun `exit 0 gives TurnCompleted`() {
        val events = runAll("exit 0")

        val completed = assertIs<RunnerEvent.TurnCompleted>(events.last())
        assertEquals(0, completed.exitCode)
    }

    @Test
    fun `exit 3 gives TurnFailed with reason turn_failed and exit code 3`() {
        val events = runAll("exit 3")

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(3, failed.exitCode)
        assertEquals("turn_failed: exit code 3", failed.reason)
    }

    @Test
    fun `a spawn failure gives TurnFailed with reason agent_not_found, the command and the message without a final period`() {
        val missing = scratch.resolve("no-such-workspace")
        val ctx = context().copy(workspace = missing)

        val events = runBlocking { withTimeout(timeout) { CommandRunner("exit 0").run(ctx, "").toList() } }

        val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
        assertEquals(null, failed.exitCode)
        assertTrue(failed.reason.startsWith("agent_not_found: exit 0: "), failed.reason)
        assertTrue(failed.reason.length > "agent_not_found: exit 0: ".length, failed.reason)
        assertTrue(!failed.reason.endsWith("."), failed.reason)
    }

    @Test
    fun `a removed name and the Anthropic keys are absent from the script env`() {
        val runner =
            CommandRunner(
                command =
                    "printf '%s|%s|%s\\n' \"\${LOOP_TEST_SECRET-unset}\" " +
                        "\"\${ANTHROPIC_API_KEY-unset}\" \"\${ANTHROPIC_AUTH_TOKEN-unset}\"",
                hostEnv =
                    mapOf(
                        "PATH" to System.getenv().getValue("PATH"),
                        "LOOP_TEST_SECRET" to "secret",
                        "ANTHROPIC_API_KEY" to "sk-host",
                        "ANTHROPIC_AUTH_TOKEN" to "token-host",
                    ),
                removedEnvNames = setOf("LOOP_TEST_SECRET"),
            )

        val events = runBlocking { withTimeout(timeout) { runner.run(context(), "").toList() } }

        assertEquals(listOf("unset|unset|unset"), events.lines(Stream.Stdout))
    }

    @Test
    fun `agent env values reach the script with the workspace placeholder replaced, over the host value`() {
        val runner =
            CommandRunner(
                command = "printf '%s|%s\\n' \"\$GRADLE_OPTS\" \"\$PLAIN_VALUE\"",
                hostEnv = mapOf("PATH" to System.getenv().getValue("PATH"), "GRADLE_OPTS" to "-Dhost"),
                addedEnv = mapOf("GRADLE_OPTS" to "-Dbase={workspace}/daemons", "PLAIN_VALUE" to "plain"),
            )
        val ctx = context()

        val events = runBlocking { withTimeout(timeout) { runner.run(ctx, "").toList() } }

        assertEquals(listOf("-Dbase=${ctx.workspace}/daemons|plain"), events.lines(Stream.Stdout))
    }

    @Test
    fun `stdout and stderr lines are emitted as output`() {
        val events = runAll("echo to-out; echo to-err >&2")

        assertEquals(listOf("to-out"), events.lines(Stream.Stdout))
        assertEquals(listOf("to-err"), events.lines(Stream.Stderr))
    }

    @Test
    fun `a background child left by the script does not hold the turn open`() {
        val pidFile = childPidFile("orphan.pid")
        // 61 and 62 tell the leaking test apart in `ps` if a child ever survives.
        val command = "sleep 61 & echo \$! > '$pidFile'; exit 0"
        // Collected on its own thread so a hung flow cannot keep the cleanup in @AfterEach from killing the child.
        val collection =
            CompletableFuture.supplyAsync {
                runBlocking { CommandRunner(command).run(context(), "").toList() }
            }

        val events = collection.get(5, TimeUnit.SECONDS)

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val pid = checkNotNull(readPid(pidFile)) { "the script wrote the child pid" }
        assertTrue(awaitDead(pid), "the child is gone after the turn")
    }

    @Test
    fun `cancelling the collector kills a background child of the script`() {
        val pidFile = childPidFile("child.pid")
        val command = "sleep 62 & echo \$! > '$pidFile'; echo ready; wait"
        runBlocking {
            withTimeout(timeout) {
                CommandRunner(command)
                    .run(context(), "")
                    .first { it is RunnerEvent.Output && it.line == "ready" }
            }
        }

        val pid = checkNotNull(readPid(pidFile)) { "the script wrote the child pid" }
        assertTrue(awaitDead(pid), "the child is gone after cancel")
    }

    @Test
    fun `a child orphaned by a subshell before the script exits is gone after the turn`() {
        val pidFile = childPidFile("subshell-orphan.pid")
        // The subshell exits at once, so the child is not a job of the script and is reparented before the script exits.
        val events = runOnOwnThread("(sleep 65 & echo \$! > '$pidFile'); exit 0")

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val pid = checkNotNull(readPid(pidFile)) { "the subshell wrote the child pid" }
        assertTrue(awaitDead(pid), "the orphaned child is gone after the turn")
    }

    @Test
    fun `a grandchild started by a second script is gone after the turn`() {
        val pidFile = childPidFile("grandchild.pid")
        writeAgentScript(sleepSeconds = 63, pidFile = pidFile)

        val events = runOnOwnThread("sh ./agent.sh; exit 0")

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val pid = checkNotNull(readPid(pidFile)) { "the second script wrote the grandchild pid" }
        assertTrue(awaitDead(pid), "the grandchild is gone after the turn")
    }

    @Test
    fun `cancelling the collector kills a grandchild started by a second script`() {
        val pidFile = childPidFile("grandchild-cancel.pid")
        writeAgentScript(sleepSeconds = 64, pidFile = pidFile)
        runBlocking {
            withTimeout(timeout) {
                CommandRunner("sh ./agent.sh; echo ready; sleep 30")
                    .run(context(), "")
                    .first { it is RunnerEvent.Output && it.line == "ready" }
            }
        }

        val pid = checkNotNull(readPid(pidFile)) { "the second script wrote the grandchild pid" }
        assertTrue(awaitDead(pid), "the grandchild is gone after cancel")
    }

    @Test
    fun `a group signal that fails on TERM still leaves no survivor and is reported on stderr`() {
        val pidFile = childPidFile("failed-signal.pid")
        val runner =
            CommandRunner(
                command = "sleep 67 & echo \$! > '$pidFile'; exit 0",
                gracePeriod = 500.milliseconds,
                drainTimeout = 500.milliseconds,
                signaller = FailingTermSignaller,
            )

        val events = runOnOwnThread(runner)

        assertIs<RunnerEvent.TurnCompleted>(events.last())
        val pid = checkNotNull(readPid(pidFile)) { "the script wrote the child pid" }
        assertTrue(awaitDead(pid), "the child is gone although TERM could not be sent")
        assertTrue(
            events.lines(Stream.Stderr).any {
                it.startsWith("loop: could not signal process group ") && it.endsWith(": ${FailingTermSignaller.MESSAGE}")
            },
            "no stderr line about the failed signal in ${events.lines(Stream.Stderr)}",
        )
    }

    /** Collects the whole flow on its own thread, so a hung flow cannot keep @AfterEach from killing the children. */
    private fun runOnOwnThread(command: String): List<RunnerEvent> = runOnOwnThread(CommandRunner(command))

    private fun runOnOwnThread(runner: CommandRunner): List<RunnerEvent> =
        CompletableFuture
            .supplyAsync { runBlocking { runner.run(context(), "").toList() } }
            .get(5, TimeUnit.SECONDS)

    /** Fails every TERM as a fork failure would, sends the other signals for real. */
    private object FailingTermSignaller : GroupSignaller {
        override fun signal(
            pgid: Long,
            signal: String,
        ): GroupSignalResult =
            if (signal == "TERM") {
                GroupSignalResult.Failed(MESSAGE)
            } else {
                ShellGroupSignaller.signal(pgid, signal)
            }

        const val MESSAGE = "fake fork failure"
    }

    /** Writes `agent.sh` in the workspace: it starts a background sleep, records its pid, then exits. */
    private fun writeAgentScript(
        sleepSeconds: Int,
        pidFile: Path,
    ) {
        workspace.resolve("agent.sh").writeText("sleep $sleepSeconds &\necho \$! > '$pidFile'\n")
    }

    /** Pid files of long lived children; @AfterEach kills every survivor, even when the test failed, and fails on it. */
    private val childPidFiles = mutableListOf<Path>()

    private fun childPidFile(name: String): Path = scratch.resolve(name).also { childPidFiles.add(it) }

    /** Force kills recorded children that are still alive and returns their pids. */
    private fun killChildren(): List<Long> =
        childPidFiles.mapNotNull(::readPid).filter(::isAlive).onEach { pid ->
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }

    @AfterEach
    fun `no child outlives its test`() {
        val survivors = killChildren()
        assertTrue(survivors.isEmpty(), "children still alive after the test, now killed: $survivors")
    }

    private fun readPid(file: Path): Long? = if (Files.exists(file)) file.readText().trim().toLongOrNull() else null

    private fun awaitDead(pid: Long): Boolean {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(50)
        return !isAlive(pid)
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
