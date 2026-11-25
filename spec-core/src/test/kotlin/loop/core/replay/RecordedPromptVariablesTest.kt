package loop.core.replay

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** m4-40: the prompt variables of an attempt, recovered from what the store recorded of its issue. */
class RecordedPromptVariablesTest {
    @Test
    fun `a first dispatch with after_create recovers every variable as a fresh first render`() {
        val events = listOf(started(A1), hook(A1, "after_create"), finished(A1))

        val recovered = RecordedPromptVariables.recover(AttemptId(A1), events, declaresContextSources = false)

        assertEquals(null, recovered.attempt)
        assertEquals(null, recovered.retryKind)
        assertEquals(null, recovered.verifyFindings)
        assertEquals(false, recovered.workspaceReused)
        assertEquals(null, recovered.sessionBrief)
        assertEquals(null, recovered.contextSources)
        assertEquals(null, recovered.answer)
        assertEquals(emptyList(), recovered.missing)
    }

    @Test
    fun `a first dispatch with no after_create names workspace_reused and the brief that depends on it`() {
        val recovered = RecordedPromptVariables.recover(AttemptId(A1), listOf(started(A1)), declaresContextSources = false)

        assertEquals(false, recovered.workspaceReused, "rendered false")
        assertEquals(listOf("loop.workspace_reused", "loop.session_brief"), recovered.missing)
    }

    @Test
    fun `a continuation retry recovers its attempt and retry kind from retry_scheduled`() {
        val events =
            listOf(
                started(A1),
                finished(A1),
                retry(attempt = "1", kind = "continuation"),
                started(A2),
                hook(A2, "before_run"),
            )

        val recovered = RecordedPromptVariables.recover(AttemptId(A2), events, declaresContextSources = false)

        assertEquals(1, recovered.attempt)
        assertEquals("continuation", recovered.retryKind)
        assertEquals(null, recovered.verifyFindings)
        assertEquals(listOf("loop.workspace_reused", "loop.session_brief"), recovered.missing)
    }

    @Test
    fun `an error retry is error, a refuted one is refuted and its findings are not recovered`() {
        val error = listOf(started(A1), finished(A1), retry(attempt = "2", kind = "error", error = "exit_code=1"), started(A2))
        val refuted = listOf(started(A1), finished(A1), retry(attempt = "2", kind = "error", error = "refuted"), started(A2))
        val reaction = listOf(started(A1), finished(A1), retry(attempt = "3", kind = "error", error = "reaction"), started(A2))

        val e = RecordedPromptVariables.recover(AttemptId(A2), error, declaresContextSources = false)
        val r = RecordedPromptVariables.recover(AttemptId(A2), refuted, declaresContextSources = false)
        val x = RecordedPromptVariables.recover(AttemptId(A2), reaction, declaresContextSources = false)

        assertEquals(2 to "error", e.attempt to e.retryKind)
        assertEquals(false, "loop.verify_findings" in e.missing)
        assertEquals(2 to "refuted", r.attempt to r.retryKind)
        assertEquals(true, "loop.verify_findings" in r.missing, "${r.missing}")
        assertEquals(3 to "reaction", x.attempt to x.retryKind)
        assertEquals(true, "loop.verify_findings" in x.missing, "${x.missing}")
    }

    @Test
    fun `a sent back dispatch recovers the reason as its findings`() {
        val events =
            listOf(
                started(A1),
                finished(A1),
                event("sent_back", data = mapOf("by" to "alice", "reason" to "  The button label is wrong, use Save.\n")),
                started(A2),
                hook(A2, "after_create"),
            )

        val recovered = RecordedPromptVariables.recover(AttemptId(A2), events, declaresContextSources = false)

        assertEquals(null, recovered.attempt)
        assertEquals("sent_back", recovered.retryKind)
        assertEquals("Sent back by alice: The button label is wrong, use Save.", recovered.verifyFindings)
        assertEquals(emptyList(), recovered.missing)
    }

    @Test
    fun `an answered dispatch is answer, and its answer is not recovered`() {
        val events = listOf(started(A1), finished(A1), event("question_answered", data = mapOf("by" to "bob")), started(A2))

        val recovered = RecordedPromptVariables.recover(AttemptId(A2), events, declaresContextSources = false)

        assertEquals("answer", recovered.retryKind)
        assertEquals(true, "loop.answer" in recovered.missing, "${recovered.missing}")
    }

    @Test
    fun `only the events after the previous attempt started count`() {
        val events =
            listOf(
                started(A1),
                retry(attempt = "1", kind = "error", error = "exit_code=1"),
                started(A2),
                finished(A2),
                retry(attempt = "2", kind = "continuation"),
                event("retry_dropped", data = mapOf("reason" to "not_active")),
                started(A3),
            )

        val third = RecordedPromptVariables.recover(AttemptId(A3), events, declaresContextSources = false)
        val second = RecordedPromptVariables.recover(AttemptId(A2), events, declaresContextSources = false)

        assertEquals(null to null, third.attempt to third.retryKind, "the retry was dropped")
        assertEquals(1 to "error", second.attempt to second.retryKind)
    }

    @Test
    fun `context sources the kind declares are not recovered`() {
        val events = listOf(started(A1), hook(A1, "after_create"))

        val recovered = RecordedPromptVariables.recover(AttemptId(A1), events, declaresContextSources = true)

        assertEquals(listOf("loop.context_sources"), recovered.missing)
    }

    private fun started(attempt: String) = event("attempt_started", attempt)

    private fun finished(attempt: String) = event("attempt_finished", attempt)

    private fun hook(
        attempt: String,
        name: String,
    ) = event("hook_finished", attempt, mapOf("hook" to name, "exit_code" to "0"))

    private fun retry(
        attempt: String,
        kind: String,
        error: String? = null,
    ) = event("retry_scheduled", data = listOfNotNull("attempt" to attempt, "kind" to kind, error?.let { "error" to it }).toMap())

    private fun event(
        kind: String,
        attempt: String? = null,
        data: Map<String, String> = emptyMap(),
    ) = RecordedEvent(
        ts = Instant.parse("2026-11-25T10:00:00Z"),
        projectId = ProjectId("p"),
        kind = kind,
        issueId = "default:t-1",
        issueIdentifier = "t-1",
        attemptId = attempt?.let(::AttemptId),
        data = data,
    )

    private companion object {
        const val A1 = "a1"
        const val A2 = "a2"
        const val A3 = "a3"
    }
}
