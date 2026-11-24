package loop.core.orchestrator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.core.orchestrator.NetworkResume.Choice
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** m4-46: after a network hold, the next attempt continues the session of the last one only when it is cheap to. */
class NetworkResumeTest {
    private val resume = NetworkResume()

    @Test
    fun `a held attempt whose last occupancy is below the soft limit resumes its session`() {
        held(fraction = 0.12)

        assertEquals(Choice.Resume("claude-session-1", 0.12), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `a held attempt at or above the soft limit starts fresh`() {
        held(fraction = SOFT_LIMIT)
        assertEquals(Choice.Fresh(NetworkResume.AT_SOFT_LIMIT), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))

        held(fraction = 0.35)
        assertEquals(Choice.Fresh(NetworkResume.AT_SOFT_LIMIT), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `the soft limit of the next dispatch decides`() {
        held(fraction = 0.15)

        assertEquals(Choice.Fresh(NetworkResume.AT_SOFT_LIMIT), resume.choose(ISSUE, CLAUDE, softLimit = 0.10))
    }

    @Test
    fun `an attempt the hold did not take starts fresh, an ordinary failure or the third hit counted as one`() {
        resume.sessionEnded(ISSUE, ATTEMPT, CLAUDE, "claude-session-1", 0.05)
        resume.attemptEnded(ISSUE, ATTEMPT, held = false)

        assertEquals(Choice.Fresh(NetworkResume.NOT_HELD), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `a first dispatch, or one after a restart, starts fresh`() {
        assertEquals(Choice.Fresh(NetworkResume.NOT_HELD), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `a session with no runtime session id, no occupancy or no known window starts fresh`() {
        held(runtimeSessionId = null)
        assertEquals(Choice.Fresh(NetworkResume.NO_RUNTIME_SESSION), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))

        held(fraction = null)
        assertEquals(Choice.Fresh(NetworkResume.NO_OCCUPANCY), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `the choice is used once, a second dispatch starts fresh`() {
        held()

        assertEquals(Choice.Resume("claude-session-1", 0.12), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
        assertEquals(Choice.Fresh(NetworkResume.NOT_HELD), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `the hold of another attempt of the issue does not resume a session that ended earlier`() {
        resume.sessionEnded(ISSUE, ATTEMPT, CLAUDE, "claude-session-1", 0.05)
        // A later attempt that started no session, its workspace failed for example, cannot have been held; a stale
        // hold of it would otherwise resume the first attempt's session.
        resume.attemptEnded(ISSUE, AttemptId("a2"), held = true)

        assertEquals(Choice.Fresh(NetworkResume.NOT_HELD), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `another runner kind than the held session's starts fresh`() {
        held()

        assertEquals(Choice.Fresh(NetworkResume.RUNNER_CHANGED), resume.choose(ISSUE, "codex-app-server", SOFT_LIMIT))
    }

    @Test
    fun `each issue keeps its own last session`() {
        held()
        resume.sessionEnded("other", AttemptId("b1"), CLAUDE, "claude-session-9", 0.30)
        resume.attemptEnded("other", AttemptId("b1"), held = true)

        assertEquals(Choice.Fresh(NetworkResume.AT_SOFT_LIMIT), resume.choose("other", CLAUDE, SOFT_LIMIT))
        assertEquals(Choice.Resume("claude-session-1", 0.12), resume.choose(ISSUE, CLAUDE, SOFT_LIMIT))
    }

    @Test
    fun `a resume opens the session with resumeSession`() {
        val runner = Runner(resumes = true)

        val session = NetworkResume.open(runner, context(), Choice.Resume("claude-session-1", 0.12))

        assertSame(runner.resumedSession, session)
        assertEquals(listOf("claude-session-1"), runner.resumed)
        assertEquals(0, runner.opened)
    }

    @Test
    fun `a runner that does not override resumeSession opens a fresh session, as Codex does`() {
        val runner = Runner(resumes = false)

        val session = NetworkResume.open(runner, context(), Choice.Resume("thread-1", 0.12))

        assertSame(runner.freshSession, session)
        assertEquals(1, runner.opened)
    }

    @Test
    fun `a fresh choice never asks the runner to resume`() {
        val runner = Runner(resumes = true)

        val session = NetworkResume.open(runner, context(), Choice.Fresh(NetworkResume.AT_SOFT_LIMIT))

        assertSame(runner.freshSession, session)
        assertEquals(emptyList(), runner.resumed)
    }

    @Test
    fun `a runner without continuation opens nothing`() {
        val runner = Runner(resumes = false, sessions = false)

        assertNull(NetworkResume.open(runner, context(), Choice.Resume("claude-session-1", 0.12)))
    }

    /** Records a held attempt of [ISSUE] whose last session reported [runtimeSessionId] and [fraction]. */
    private fun held(
        runtimeSessionId: String? = "claude-session-1",
        fraction: Double? = 0.12,
    ) {
        resume.sessionEnded(ISSUE, ATTEMPT, CLAUDE, runtimeSessionId, fraction)
        resume.attemptEnded(ISSUE, ATTEMPT, held = true)
    }

    private fun context(): AttemptContext =
        AttemptContext(
            RunId("r1"),
            ProjectId("p1"),
            Issue("i1", "m4-46", "Resume", "todo", null, emptyList(), emptyList(), null, null),
            AttemptId("a2"),
            Path.of("/tmp/w"),
            emptyMap(),
        )

    /** A runner that resumes when [resumes], else keeps the default of [AgentRunner.resumeSession]. */
    private class Runner(
        private val resumes: Boolean,
        private val sessions: Boolean = true,
    ) : AgentRunner {
        val freshSession = Session()
        val resumedSession = Session()
        val resumed = mutableListOf<String>()
        var opened = 0

        override val capabilities =
            RunnerCapabilities(
                continuation = sessions,
                loopTools = RunnerCapabilities.LoopTools.Files,
                usage = false,
                cost = false,
                occupancy = true,
                sandbox = false,
            )

        override fun run(
            ctx: AttemptContext,
            prompt: String,
        ): Flow<RunnerEvent> = emptyFlow()

        override fun openSession(ctx: AttemptContext): AgentSession? {
            if (!sessions) return null
            opened++
            return freshSession
        }

        override fun resumeSession(
            ctx: AttemptContext,
            runtimeSessionId: String,
        ): AgentSession? {
            if (!resumes) return super.resumeSession(ctx, runtimeSessionId)
            resumed += runtimeSessionId
            return resumedSession
        }
    }

    private class Session : AgentSession {
        override fun runTurn(
            input: String,
            turn: Int,
        ): Flow<RunnerEvent> = emptyFlow()

        override suspend fun stop() = Unit
    }

    private companion object {
        const val ISSUE = "i1"
        const val CLAUDE = "claude-code"
        const val SOFT_LIMIT = 0.20
        val ATTEMPT = AttemptId("a1")
    }
}
