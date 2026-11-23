package loop.runner.claudecode

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * m4-03, story 103 (bl-155, bl-122): a turn that reads the `task_notification` line of a background task run started
 * in an earlier turn of the session is a wake turn, wherever it reads it, and the runner says so with one
 * [RunnerEvent.WakeTurn]. A run started in the same turn makes none, and runs are matched by `run_id`, not `task_id`.
 */
class WakeTurnTest {
    @TempDir
    lateinit var workspace: Path

    private val wake = Fixtures.lines("background_wake")
    private val next = Fixtures.lines("idle_next_turn")

    @Test
    fun `a notification read while idle, of a run started in turn 1, makes turn 2 a wake turn`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2]), idleAfter = listOf(taskNotification("t1", "r1"), wake[3], wake[4])),
                Replay.Turn(next),
            )

        val (one, two) = turns(claude, 2)

        assertEquals(emptyList(), one.wakes())
        val marked = two.wakes().single()
        val started = assertIs<RunnerEvent.TurnStarted>(two.first())
        assertEquals(RunnerEvent.WakeTurn(started.sessionId, started.turnId), marked)
        // Right after the notification line, before the turn's terminal event.
        val at = two.indexOf(marked)
        assertEquals(taskNotification("t1", "r1"), (two[at - 1] as RunnerEvent.Output).line)
        assertIs<RunnerEvent.TurnCompleted>(two.last())
        assertEquals(listOf(taskNotification("t1", "r1")) + wake.drop(3) + next, two.stdout())
    }

    @Test
    fun `a notification inside a wake's own init and result makes a wake turn`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2]), idleAfter = listOf(wake[0], taskNotification("t1", "r1"))),
                Replay.Turn(next),
                lateIdle = listOf(wake[3], withOrigin(wake[4], "r1")),
            )

        val (_, two) = turns(claude, 2)

        assertEquals(1, two.wakes().size)
        assertEquals(listOf(wake[0], taskNotification("t1", "r1"), wake[3], withOrigin(wake[4], "r1")) + next, two.stdout())
        assertIs<RunnerEvent.TurnCompleted>(two.last())
        // The wake ran to its result before the turn's input was written.
        assertEquals(2, claude.inputs.size)
    }

    @Test
    fun `a notification folded into the turn's answer makes a wake turn`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2])),
                Replay.Turn(listOf(next[0], taskNotification("t1", "r1"), wake[3], next[1])),
            )

        val (one, two) = turns(claude, 2)

        assertEquals(emptyList(), one.wakes())
        assertEquals(1, two.wakes().size)
        assertIs<RunnerEvent.TurnCompleted>(two.last())
    }

    @Test
    fun `a run started and notified in the same turn makes no wake turn, nor does a later turn without a notification`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), taskNotification("t1", "r1"), wake[1], wake[2])),
                Replay.Turn(listOf(next[0], taskStarted("t2", "r2"), taskNotification("t2", "r2"), next[1])),
                Replay.Turn(next),
            )

        val all = turns(claude, 3)

        assertEquals(listOf(0, 0, 0), all.map { it.wakes().size })
        all.forEach { assertIs<RunnerEvent.TurnCompleted>(it.last()) }
    }

    @Test
    fun `a restarted task keeps its task_id with a new run_id, and only its run decides`() {
        // As task id_089 of the recording: started again in later turns with a new run_id each time.
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), taskNotification("t1", "r1"), wake[1], wake[2])),
                Replay.Turn(listOf(next[0], taskStarted("t1", "r2"), taskNotification("t1", "r2"), taskStarted("t1", "r3"), next[1])),
                Replay.Turn(listOf(next[0], taskNotification("t1", "r3"), next[1])),
            )

        val all = turns(claude, 3)

        assertEquals(listOf(0, 0, 1), all.map { it.wakes().size })
    }

    @Test
    fun `two notifications in one turn make one wake turn`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), taskStarted("t2", "r2"), wake[1], wake[2])),
                Replay.Turn(listOf(next[0], taskNotification("t1", "r1"), taskNotification("t2", "r2"), next[1])),
            )

        val (_, two) = turns(claude, 2)

        assertEquals(1, two.wakes().size)
    }

    @Test
    fun `a subagent line read while idle does not hold the next turn's input (bl-122)`() {
        // Before m4-03 it set waking, so the turn waited for a result line that only a wake writes, past its timeout.
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2]), idleAfter = listOf(SUBAGENT_LINE)),
                Replay.Turn(next),
            )
        val settings = ClaudeCodeSettings(command = "claude", turnTimeout = 1.seconds)

        val (_, two) = turns(claude, 2, settings)

        assertIs<RunnerEvent.TurnCompleted>(two.last())
        assertEquals(listOf(SUBAGENT_LINE) + next, two.stdout())
        assertEquals(emptyList(), two.wakes())
    }

    @Test
    fun `a wake's result read after the input does not end the turn, its usage counts in it`() {
        // Attempt 10c2f759 turn 2: the wake's init and result (origin task-notification) came after the turn began.
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2])),
                Replay.Turn(listOf(wake[0], taskNotification("t1", "r1"), wake[3], withOrigin(wake[4], "r1")) + next),
            )

        val (_, two) = turns(claude, 2)

        assertEquals(listOf(wake[0], taskNotification("t1", "r1"), wake[3], withOrigin(wake[4], "r1")) + next, two.stdout())
        val reports = two.filterIsInstance<RunnerEvent.Usage>()
        assertEquals(listOf(listOf(1500L, 49_500L, 13_400_000L, 189_000L), listOf(1600L, 49_550L, 13_400_010L, 189_005L)), reports.map { it.tokens() })
        assertEquals(listOf(9.75, 10.0), reports.map { it.totalCostUsd })
        assertEquals(1, two.wakes().size)
        assertIs<RunnerEvent.TurnCompleted>(two.last())
    }

    @Test
    fun `a result after an interrupt request ends the turn, even a wake's`() {
        val claude =
            Replay(
                Replay.Turn(listOf(wake[0], taskStarted("t1", "r1"), wake[1], wake[2])),
                Replay.Turn(listOf(taskNotification("t1", "r1"))),
                onInterrupt = listOf(withOrigin(INTERRUPTED_RESULT, "r1")),
            )

        val two =
            runBlocking {
                withTimeout(20.seconds) {
                    val session = checkNotNull(runner(claude).openSession(context()))
                    try {
                        session.runTurn("turn 1", 1).toList()
                        val second = async { session.runTurn("turn 2", 2).toList() }
                        delay(300.milliseconds)
                        session.interrupt()
                        second.await()
                    } finally {
                        session.stop()
                    }
                }
            }

        assertEquals("turn_failed: error_during_execution", assertIs<RunnerEvent.TurnFailed>(two.last()).reason)
        assertEquals(1, two.wakes().size)
    }

    @Test
    fun `replayed turn by turn, attempt 10c2f759 has wake turns 2, 3, 4 and 6, ordinary turns 1 and 5`() {
        // The recorded runner lines of the second pilot app issue, each turn's lines written after its input, as
        // wake_turns_10c2f759.turns.json delimits them. Turns 2 and 4 hold a wake's own init and result, whose
        // origin.kind task-notification keeps it from ending the turn.
        val lines = Fixtures.lines("wake_turns_10c2f759")
        val turnsJson =
            Json
                .parseToJsonElement(Fixtures::class.java.getResource("/streams/wake_turns_10c2f759.turns.json")!!.toURI().toPath().toFile().readText())
                .jsonObject
                .getValue("turns")
                .jsonArray
                .map { it.jsonObject }
        val bounds = turnsJson.map { it.int("input_after_line") to it.int("last_line") }
        val recorded = bounds.map { (after, last) -> lines.subList(after, last) }
        val claude = Replay(*recorded.map { Replay.Turn(it) }.toTypedArray())

        val all = turns(claude, recorded.size, pause = 50.milliseconds)

        assertEquals(recorded, all.map { it.stdout() }, "each turn ends where the recording says")
        assertEquals(listOf(false, true, true, true, false, true), all.map { it.wakes().size == 1 })
        assertEquals(listOf(0, 1, 1, 1, 0, 1), all.map { it.wakes().size })
        val outcomes = all.map { if (it.last() is RunnerEvent.TurnCompleted) "completed" else "failed" }
        assertEquals(turnsJson.map { it.getValue("outcome").jsonPrimitive.content }, outcomes, "each turn ends as recorded")
    }

    /** Runs [count] turns of one session, `turn <n>` as input, [pause] between them, then stops it. */
    private fun turns(
        claude: Replay,
        count: Int,
        settings: ClaudeCodeSettings = ClaudeCodeSettings(command = "claude"),
        pause: Duration = 300.milliseconds,
    ): List<List<RunnerEvent>> =
        runBlocking {
            withTimeout(30.seconds) {
                val session = checkNotNull(runner(claude, settings).openSession(context()))
                try {
                    (1..count).map { n ->
                        if (n > 1) delay(pause)
                        session.runTurn("turn $n", n).toList()
                    }
                } finally {
                    session.stop()
                }
            }
        }

    private fun runner(
        claude: Replay,
        settings: ClaudeCodeSettings = ClaudeCodeSettings(command = "claude"),
    ): ClaudeCodeRunner = ClaudeCodeRunner(settings, HOST_ENV, emptySet(), claude.launch, stopGrace = 2.seconds, groupAlive = { false })

    private fun List<RunnerEvent>.wakes(): List<RunnerEvent.WakeTurn> = filterIsInstance<RunnerEvent.WakeTurn>()

    private fun List<RunnerEvent>.stdout(): List<String> = filterIsInstance<RunnerEvent.Output>().filter { it.stream == Stream.Stdout }.map { it.line }

    private fun RunnerEvent.Usage.tokens(): List<Long> = listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "t-1",
                    identifier = "t-1",
                    title = "Wake",
                    state = "todo",
                    priority = null,
                    labels = listOf("ready-for-agent"),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )

    /**
     * A scripted long lived process: it answers its n-th user message with the lines of [turns] n, then, 100 ms later,
     * the lines it writes while idle after that turn, and [lateIdle] 300 ms after those, once. An interrupt request is
     * answered with [onInterrupt]. Every stdin line it read is kept in [inputs].
     */
    private class Replay(
        vararg val turns: Turn,
        private val lateIdle: List<String> = emptyList(),
        private val onInterrupt: List<String> = emptyList(),
    ) {
        data class Turn(
            val answer: List<String>,
            val idleAfter: List<String> = emptyList(),
        )

        val inputs = ConcurrentLinkedQueue<String>()

        val launch: (LaunchSpec) -> Flow<ProcessEvent> = { spec ->
            flow {
                emit(ProcessEvent.Started(3000L))
                var n = 0
                var late = lateIdle
                for (line in checkNotNull(spec.stdinLines)) {
                    inputs += line
                    if (INTERRUPT in line) {
                        onInterrupt.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                        continue
                    }
                    val turn = turns.getOrNull(n++) ?: continue
                    turn.answer.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                    if (turn.idleAfter.isNotEmpty()) {
                        delay(100)
                        turn.idleAfter.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                        if (late.isNotEmpty()) {
                            delay(300)
                            late.forEach { emit(ProcessEvent.Line(Stream.Stdout, it)) }
                            late = emptyList()
                        }
                    }
                }
                emit(ProcessEvent.Exited(0))
            }
        }
    }

    private companion object {
        val HOST_ENV: Map<String, String> = mapOf("PATH" to System.getenv("PATH"), "HOME" to System.getProperty("user.home"))

        const val INTERRUPT = "\"subtype\":\"interrupt\""

        /** A background subagent's message, as Claude Code writes it while the main session is idle. */
        const val SUBAGENT_LINE =
            """{"type":"assistant","message":{"id":"msg_sub","type":"message","role":"assistant","content":[{"type":"text",""" +
                """"text":"still working"}],"usage":{"input_tokens":3,"output_tokens":7,"cache_read_input_tokens":900,""" +
                """"cache_creation_input_tokens":0}},"parent_tool_use_id":"toolu_agent","session_id":"claude-wake-session"}"""

        /** The result Claude Code wrote after an interrupt request (m3-12), trimmed. */
        const val INTERRUPTED_RESULT =
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"claude-wake-session",""" +
                """"total_cost_usd":0.42,"usage":{"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0,""" +
                """"cache_creation_input_tokens":0},"permission_denials":[]}"""

        /** A `task_started` line in the shape Claude Code 2.1.319 writes it, for a background command. */
        fun taskStarted(
            task: String,
            run: String,
        ): String =
            """{"type":"system","subtype":"task_started","task_id":"$task","run_id":"$run","tool_use_id":"toolu_bg",""" +
                """"description":"./gradlew build","task_type":"local_bash","uuid":"s-$run","session_id":"claude-wake-session"}"""

        /** A `task_notification` line in the shape Claude Code 2.1.319 writes it, once a background run is done. */
        fun taskNotification(
            task: String,
            run: String,
        ): String =
            """{"type":"system","subtype":"task_notification","task_id":"$task","run_id":"$run","tool_use_id":"toolu_bg",""" +
                """"status":"completed","output_file":"out.txt","summary":"build passed","uuid":"n-$run","session_id":"claude-wake-session"}"""

        /** [line], a `result` line, with the `origin` Claude Code gives the result of a wake it ran for run [run]. */
        fun withOrigin(
            line: String,
            run: String,
        ): String =
            JsonObject(
                Json.parseToJsonElement(line).jsonObject +
                    (
                        "origin" to
                            buildJsonObject {
                                put("kind", "task-notification")
                                put("producer", "session-task")
                                put("runId", run)
                            }
                    ),
            ).toString()
    }
}
