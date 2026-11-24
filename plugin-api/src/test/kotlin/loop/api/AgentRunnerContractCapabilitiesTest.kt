package loop.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The capability cases of [AgentRunnerContract] catch a runner whose declared [RunnerCapabilities] do not match what
 * it does (m3-06). Each test builds the contract around an in memory runner, honest or deliberately wrong, and runs
 * one case: the honest runner passes it, each wrong one fails it with an assertion error, never a skip.
 */
class AgentRunnerContractCapabilitiesTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `an honest runner passes every capability case`() {
        val contract = contractFor(ScriptedRunner(HONEST))

        contract.`openSession matches the declared continuation`()
        contract.`Usage is reported exactly when the runner declares usage`()
        contract.`a cost is reported exactly when the runner declares cost`()
        contract.`ContextUsage is reported exactly when the runner declares occupancy`()
        contract.`Usage values are absolute and never decrease within a session`()
        contract.`a TurnFailed category is null or runner_unavailable`()
        contract.`a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`()
    }

    @Test
    fun `a runner whose stop reports a turn event fails the contract`() {
        val runner = ScriptedRunner(HONEST, stopReports = { listOf(RunnerEvent.TurnCompleted(it, TurnId("turn-1"), 0)) })

        assertFailsContract("its stop reported an event other than Usage and StopGraceExpired") {
            contractFor(runner).`a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`()
        }
    }

    @Test
    fun `a runner whose stop reports usage below the last turn's fails the contract`() {
        val runner = ScriptedRunner(HONEST, stopReports = { listOf(RunnerEvent.Usage(it, TurnId("turn-1"), 1, 1, 1, 1, 0.5)) })

        assertFailsContract("usage went down at the stop") {
            contractFor(runner).`a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`()
        }
    }

    @Test
    fun `a runner whose stop reports usage of another thread fails the contract`() {
        val other = SessionId("other-turn-1")
        val runner = ScriptedRunner(HONEST, stopReports = { listOf(RunnerEvent.Usage(other, TurnId("turn-1"), 500, 500, 500, 500, 0.5)) })

        assertFailsContract("is not of the session's thread") {
            contractFor(runner).`a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`()
        }
    }

    @Test
    fun `a runner whose stop reports usage after StopGraceExpired fails the contract`() {
        val runner =
            ScriptedRunner(HONEST, stopReports = {
                listOf(
                    RunnerEvent.StopGraceExpired(it, TurnId("turn-1"), graceMs = 5_000, wake = true, wakeMessages = 1),
                    RunnerEvent.Usage(it, TurnId("turn-1"), 500, 500, 500, 500, 0.5),
                )
            })

        assertFailsContract("StopGraceExpired is not last") {
            contractFor(runner).`a stop with a report gives only absolute usage of the session and at most one StopGraceExpired, last`()
        }
    }

    @Test
    fun `a runner that runs a session but declares no continuation fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(continuation = false))

        assertFailsContract("declares no continuation, but openSession returned a session") {
            contractFor(runner).`openSession matches the declared continuation`()
        }
    }

    @Test
    fun `a runner that declares continuation but opens no session fails the contract`() {
        val runner = ScriptedRunner(HONEST, sessions = false)

        assertFailsContract("declares continuation, but openSession returned null") {
            contractFor(runner).`openSession matches the declared continuation`()
        }
    }

    @Test
    fun `a runner that does not override resumeSession keeps today's behavior and passes the contract`() {
        val runner = ScriptedRunner(HONEST)
        val contract = contractFor(runner)

        val ctx = AttemptContext(RunId("run-1"), ProjectId("project-1"), ISSUE, AttemptId("attempt-1"), workspace, emptyMap())

        assertNull(runner.resumeSession(ctx, "runtime-1"), "the default resumes nothing")
        contract.`resumeSession returns a session only with continuation`()
        assertFailsWith<TestAbortedException> {
            contract.`a resumed session starts with SessionStarted and ends with exactly one terminal event`()
        }
    }

    @Test
    fun `a runner that resumes a session passes the contract`() {
        val runner = ScriptedRunner(HONEST, resumes = true)
        val contract = contractFor(runner)

        contract.`resumeSession returns a session only with continuation`()
        contract.`a resumed session starts with SessionStarted and ends with exactly one terminal event`()
        assertEquals(listOf("contract-runtime-session", "contract-runtime-session"), runner.resumed)
    }

    @Test
    fun `a runner that resumes a session but declares no continuation fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(continuation = false), resumes = true)

        assertFailsContract("declares no continuation, but resumeSession returned a session") {
            contractFor(runner).`resumeSession returns a session only with continuation`()
        }
    }

    @Test
    fun `a runner whose resumed session starts with TurnStarted fails the contract`() {
        val runner = ScriptedRunner(HONEST, resumes = true, resumedFirstTurn = 2)

        assertFailsContract("turn 1 of a resumed session") {
            contractFor(runner).`a resumed session starts with SessionStarted and ends with exactly one terminal event`()
        }
    }

    @Test
    fun `a runner that reports usage but declares none fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(usage = false, cost = false), cost = null)

        assertFailsContract("declares no usage, but the succeeding run reported") {
            contractFor(runner).`Usage is reported exactly when the runner declares usage`()
        }
    }

    @Test
    fun `a runner that declares usage but reports none fails the contract`() {
        val runner = ScriptedRunner(HONEST, usage = false)

        assertFailsContract("declares usage, but the succeeding run reported none") {
            contractFor(runner).`Usage is reported exactly when the runner declares usage`()
        }
    }

    @Test
    fun `a runner that reports a cost but declares none fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(cost = false))

        assertFailsContract("declares no cost, but the succeeding run reported") {
            contractFor(runner).`a cost is reported exactly when the runner declares cost`()
        }
    }

    @Test
    fun `a runner that declares cost but reports none fails the contract`() {
        val runner = ScriptedRunner(HONEST, cost = null)

        assertFailsContract("declares cost, but the succeeding run reported none") {
            contractFor(runner).`a cost is reported exactly when the runner declares cost`()
        }
    }

    @Test
    fun `a runner that reports occupancy but declares none fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(occupancy = false), occupancy = true)

        assertFailsContract("declares no occupancy, but the succeeding run reported") {
            contractFor(runner).`ContextUsage is reported exactly when the runner declares occupancy`()
        }
    }

    @Test
    fun `a runner that declares occupancy but reports none fails the contract`() {
        val runner = ScriptedRunner(HONEST.copy(occupancy = true), occupancy = false)

        assertFailsContract("declares occupancy, but the succeeding run reported none") {
            contractFor(runner).`ContextUsage is reported exactly when the runner declares occupancy`()
        }
    }

    @Test
    fun `a runner whose usage goes down between turns fails the contract`() {
        val runner = ScriptedRunner(HONEST, tokens = { turn, _ -> 1_000L - turn })

        assertFailsContract("usage went down in the two turn session run") {
            contractFor(runner).`Usage values are absolute and never decrease within a session`()
        }
    }

    @Test
    fun `a runner that reports per turn deltas within a turn fails the contract`() {
        val runner = ScriptedRunner(HONEST, tokens = { _, report -> if (report == 0) 50L else 10L })

        assertFailsContract("usage went down in the succeeding run") {
            contractFor(runner).`Usage values are absolute and never decrease within a session`()
        }
    }

    @Test
    fun `capabilities refuse a cost without usage`() {
        assertFailsWith<IllegalArgumentException> { HONEST.copy(usage = false) }
    }

    /** Runs [case] and checks it fails with an assertion whose message holds [fragment], so no other check caught it. */
    private fun assertFailsContract(
        fragment: String,
        case: () -> Unit,
    ) {
        val failure = assertFailsWith<AssertionError> { case() }
        assertContains(failure.message.orEmpty(), fragment)
    }

    private fun contractFor(runner: AgentRunner): AgentRunnerContract =
        object : AgentRunnerContract() {
            override fun succeedingRunner(report: Path): AgentRunner = runner

            override fun failingRunner(): AgentRunner = (runner as ScriptedRunner).failing()

            override fun hangingRunner(pidFile: Path): AgentRunner = error("not used by the capability cases")
        }.also {
            it.workspace = workspace
            it.scratch = scratch
        }

    /**
     * A runner in memory: each turn emits its start, two [RunnerEvent.Usage] reports when [usage] (tokens from
     * [tokens], by turn and report, cost [cost]), a [RunnerEvent.ContextUsage] when [occupancy], then its end. A
     * session's stop with a report callback reports [stopReports] of the session id of its turn 1, by default a wake's
     * usage above every turn's.
     */
    private class ScriptedRunner(
        override val capabilities: RunnerCapabilities,
        private val sessions: Boolean = true,
        private val usage: Boolean = true,
        private val cost: Double? = 0.5,
        private val occupancy: Boolean = false,
        private val tokens: (turn: Int, report: Int) -> Long = { turn, report -> 100L * turn + report },
        private val fails: Boolean = false,
        private val stopReports: (
            SessionId,
        ) -> List<RunnerEvent> = { listOf(RunnerEvent.Usage(it, TurnId("turn-1"), 500, 500, 500, 500, cost)) },
        private val resumes: Boolean = false,
        private val resumedFirstTurn: Int = 1,
    ) : AgentRunner {
        fun failing(): ScriptedRunner =
            ScriptedRunner(capabilities, sessions, usage, cost, occupancy, tokens, fails = true, stopReports, resumes, resumedFirstTurn)

        /** The runtime session ids [resumeSession] was asked to continue. */
        val resumed = mutableListOf<String>()

        override fun resumeSession(
            ctx: AttemptContext,
            runtimeSessionId: String,
        ): AgentSession? {
            if (!resumes) return super.resumeSession(ctx, runtimeSessionId)
            resumed += runtimeSessionId
            return session(firstTurn = resumedFirstTurn)
        }

        override fun run(
            ctx: AttemptContext,
            prompt: String,
        ): Flow<RunnerEvent> = turn(1)

        override fun openSession(ctx: AttemptContext): AgentSession? = if (!sessions) null else session(firstTurn = 1)

        /** A session whose turn `n` plays [turn] number `n + firstTurn - 1`, so a [firstTurn] of 2 starts with TurnStarted. */
        private fun session(firstTurn: Int): AgentSession =
            object : AgentSession {
                override fun runTurn(
                    input: String,
                    turn: Int,
                ): Flow<RunnerEvent> = turn(turn + firstTurn - 1)

                override suspend fun stop() = Unit

                override suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
                    if (usage) stopReports(SessionId("thread-turn-1")).forEach { report(it) }
                }
            }

        private fun turn(turn: Int): Flow<RunnerEvent> =
            flow {
                val turnId = TurnId("turn-$turn")
                val sessionId = SessionId("thread-${turnId.value}")
                emit(
                    if (turn == 1) {
                        RunnerEvent.SessionStarted(sessionId, "thread", turnId)
                    } else {
                        RunnerEvent.TurnStarted(sessionId, "thread", turnId, turn)
                    },
                )
                if (usage) {
                    repeat(2) { report ->
                        val total = tokens(turn, report)
                        emit(RunnerEvent.Usage(sessionId, turnId, total, total, total, total, cost))
                    }
                }
                if (occupancy) emit(RunnerEvent.ContextUsage(sessionId, turnId, 1_000))
                emit(
                    if (fails) {
                        RunnerEvent.TurnFailed(sessionId, turnId, 1, "turn_failed: exit code 1")
                    } else {
                        RunnerEvent.TurnCompleted(sessionId, turnId, 0)
                    },
                )
            }
    }

    private companion object {
        val ISSUE = Issue("issue-1", "c-01", "Contract", "todo", null, emptyList(), emptyList(), null, null)

        val HONEST =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Files,
                usage = true,
                cost = true,
                occupancy = false,
                sandbox = false,
            )
    }
}
