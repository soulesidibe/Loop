package loop.cli

import loop.api.AttemptId
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.waiting.Wait
import loop.store.StoredEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The rules that turn stored events, waiting and parked records into `loop status` rows (m1-14, m2-04). */
class RuntimeSnapshotTest {
    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val live = RunId("live-run")
    private val old = RunId("old-run")
    private var seq = 0L

    private fun event(
        kind: String,
        issue: String,
        attempt: String? = null,
        run: RunId = live,
        data: Map<String, String> = emptyMap(),
    ): StoredEvent {
        seq++
        return StoredEvent(
            seq,
            RecordedEvent(
                ts = Instant.parse("2026-11-11T10:00:00Z").plus(kotlin.time.Duration.parse("${seq}s")),
                projectId = project,
                kind = kind,
                runId = run,
                issueId = issue,
                issueIdentifier = issue.uppercase(),
                attemptId = attempt?.let(::AttemptId),
                data = data,
            ),
        )
    }

    private fun parked(
        issue: String,
        reason: ParkReason = ParkReason.MaxAttempts,
    ) = ParkedIssue(
        projectId = project,
        issueId = issue,
        identifier = issue.uppercase(),
        reason = reason,
        state = "todo",
        labels = setOf("ready-for-agent"),
        parkedAt = Instant.parse("2026-11-11T09:00:00Z"),
        runId = old,
    )

    @Test
    fun `an attempt of the live run without its end is running, numbered across runs`() {
        val events =
            listOf(
                event("attempt_started", "a", "a-1", run = old),
                event("attempt_finished", "a", "a-1", run = old),
                event("attempt_started", "a", "a-2"),
                event("turn_started", "a", "a-2"),
                event("usage", "a", "a-2", data = mapOf("attempt_tokens" to "1200")),
                event("turn_started", "a", "a-2"),
                event("usage", "a", "a-2", data = mapOf("attempt_tokens" to "3400")),
            )

        val snapshot = runtimeSnapshot(events, live, emptyList())

        val row = snapshot.running.single()
        assertEquals("A", row.identifier)
        assertEquals(AttemptRef(2, AttemptId("a-2")), row.attempt)
        assertEquals("3400", row.tokens)
        assertEquals(2, row.turns)
        assertEquals(emptyList(), snapshot.retrying)
    }

    @Test
    fun `an attempt left open by a dead run is not running`() {
        val events = listOf(event("attempt_started", "a", "a-1", run = old))

        assertEquals(emptyList(), runtimeSnapshot(events, live, emptyList()).running)
    }

    @Test
    fun `without a live run nothing runs or retries, parked issues still show`() {
        val events =
            listOf(
                event("attempt_started", "a", "a-1"),
                event("attempt_started", "b", "b-1"),
                event("attempt_finished", "b", "b-1"),
                event("retry_scheduled", "b", "b-1", data = mapOf("kind" to "error", "attempt" to "1")),
            )

        val snapshot = runtimeSnapshot(events, null, listOf(parked("c")))

        assertEquals(emptyList(), snapshot.running)
        assertEquals(emptyList(), snapshot.retrying)
        assertEquals(listOf("C"), snapshot.parked.map { it.identifier })
    }

    @Test
    fun `bl-39 the live run's latest runner hold without a lift after it is the hold, each new hold replacing the last`() {
        val first = mapOf("reason" to "turn_failed: API Error: 403", "hold" to "1", "probe_at" to "2026-11-11T10:01:00Z")
        val second = mapOf("reason" to "turn_failed: API Error: 429", "hold" to "2", "probe_at" to "2026-11-11T10:03:00Z")
        val events =
            listOf(
                event("runner_hold_started", "a", "a-1", run = old, data = first),
                event("runner_hold_started", "a", "a-1", data = first),
                event("runner_hold_started", "b", "b-1", data = second),
            )

        val hold = runtimeSnapshot(events, live, emptyList()).hold

        assertEquals(HoldRow(hold = "2", probeAt = "2026-11-11T10:03:00Z", reason = "turn_failed: API Error: 429"), hold)
        assertEquals(null, runtimeSnapshot(events.take(1), live, emptyList()).hold, "a hold of a dead run is no hold")
        assertEquals(null, runtimeSnapshot(events, null, emptyList()).hold, "no hold without a live run")
        val lifted = events + event("runner_hold_lifted", "c", "c-1", data = mapOf("holds" to "2", "held_ms" to "180000"))
        assertEquals(null, runtimeSnapshot(lifted, live, emptyList()).hold, "a lift ends the hold")
    }

    @Test
    fun `a scheduled retry is retrying until an attempt starts, the retry is dropped or the issue parks`() {
        val retry = mapOf("kind" to "error", "attempt" to "2", "due_at" to "2026-11-11T10:05:00Z", "error" to "boom")
        val events =
            listOf(
                event("attempt_started", "b", "b-1"),
                event("attempt_finished", "b", "b-1"),
                event("retry_scheduled", "b", "b-1", data = retry),
                event("attempt_started", "c", "c-1"),
                event("attempt_finished", "c", "c-1"),
                event("retry_scheduled", "c", "c-1", data = retry),
                event("attempt_started", "c", "c-2"),
                event("attempt_finished", "c", "c-2"),
                event("retry_scheduled", "d", null, data = retry),
                event("retry_dropped", "d", null, data = mapOf("reason" to "terminal")),
                event("retry_scheduled", "e", null, data = retry),
                event("issue_parked", "e", null, data = mapOf("reason" to "max_attempts")),
            )

        val retrying = runtimeSnapshot(events, live, emptyList()).retrying

        assertEquals(listOf("B"), retrying.map { it.identifier })
        val row = retrying.single()
        assertEquals(AttemptRef(1, AttemptId("b-1")), row.attempt)
        assertEquals("error", row.kind)
        assertEquals("2", row.retry)
        assertEquals("2026-11-11T10:05:00Z", row.dueAt)
        assertEquals("boom", row.error)
    }

    @Test
    fun `a dispatched retry whose attempt is still open shows as running only`() {
        val events =
            listOf(
                event("attempt_started", "b", "b-1"),
                event("retry_scheduled", "b", "b-1", data = mapOf("kind" to "error", "attempt" to "1")),
                event("attempt_finished", "b", "b-1"),
                event("attempt_started", "b", "b-2"),
            )

        val snapshot = runtimeSnapshot(events, live, emptyList())

        assertEquals(listOf(AttemptRef(2, AttemptId("b-2"))), snapshot.running.map { it.attempt })
        assertEquals(emptyList(), snapshot.retrying)
    }

    @Test
    fun `a parked issue shows the attempt that parked it, its reason and tracker state`() {
        val events =
            listOf(
                event("attempt_started", "p", "p-1", run = old),
                event("attempt_finished", "p", "p-1", run = old),
                event("attempt_started", "p", "p-2", run = old),
                event("attempt_finished", "p", "p-2", run = old),
                event("issue_parked", "p", "p-2", run = old, data = mapOf("reason" to "max_attempts")),
                event("attempt_started", "q", "q-1", run = old),
            )

        val rows = runtimeSnapshot(events, live, listOf(parked("p", ParkReason.MaxContinuations), parked("q"))).parked

        assertEquals(listOf("P", "Q"), rows.map { it.identifier })
        assertEquals(AttemptRef(2, AttemptId("p-2")), rows[0].attempt)
        assertEquals("max_continuations", rows[0].reason)
        assertEquals("todo", rows[0].state)
        // No issue_parked event names an attempt: the latest attempt of the issue.
        assertEquals(AttemptRef(1, AttemptId("q-1")), rows[1].attempt)
    }

    @Test
    fun `a parked issue with no recorded attempt has none`() {
        val rows = runtimeSnapshot(emptyList(), live, listOf(parked("p"))).parked

        assertEquals(null, rows.single().attempt)
    }

    private fun waiting(
        issue: String,
        attempt: String?,
        reason: WaitReason = WaitReason.Verify,
        since: String = "2026-11-11T10:06:00Z",
    ) = Wait.of(
        WaitingIssue(
            projectId = project,
            issueId = issue,
            identifier = issue.uppercase(),
            reason = reason,
            attemptId = attempt?.let(::AttemptId),
            runId = live,
            since = Instant.parse(since),
            data = mapOf("to" to "review"),
        ),
    )

    @Test
    fun `a waiting issue shows its reason and attempt, and is not also running or retrying`() {
        val events =
            listOf(
                event("attempt_started", "w", "w-1", run = old),
                event("attempt_finished", "w", "w-1", run = old),
                event("attempt_started", "w", "w-2"),
                event("retry_scheduled", "x", null, data = mapOf("kind" to "continuation", "attempt" to "1")),
            )

        val snapshot = runtimeSnapshot(events, live, emptyList(), listOf(waiting("w", "w-2"), waiting("x", null)))

        assertEquals(emptyList(), snapshot.running)
        assertEquals(emptyList(), snapshot.retrying)
        assertEquals(listOf("W", "X"), snapshot.waiting.map { it.identifier })
        val row = snapshot.waiting.first()
        assertEquals(AttemptRef(2, AttemptId("w-2")), row.attempt)
        assertEquals("verify", row.reason)
        assertEquals(Instant.parse("2026-11-11T10:06:00Z"), row.since)
        assertEquals(null, snapshot.waiting.last().attempt)
    }

    @Test
    fun `a waiting issue still shows without a live run, its wait survives the restart`() {
        val events = listOf(event("attempt_started", "w", "w-1", run = old))

        val snapshot = runtimeSnapshot(events, null, emptyList(), listOf(waiting("w", "w-1")))

        assertEquals(listOf(AttemptRef(1, AttemptId("w-1"))), snapshot.waiting.map { it.attempt })
    }

    @Test
    fun `bl-172 each merge wait has its place in the delivery queue, the oldest since first, ties by issue id`() {
        val waits =
            listOf(
                waiting("b", null, WaitReason.Merge, "2026-11-11T10:05:00Z"),
                waiting("v", null, WaitReason.Verify, "2026-11-11T10:00:00Z"),
                waiting("a", null, WaitReason.Merge, "2026-11-11T10:05:00Z"),
                waiting("c", null, WaitReason.Merge, "2026-11-11T10:01:00Z"),
            )

        val snapshot = runtimeSnapshot(emptyList(), null, emptyList(), waits)

        assertEquals(listOf("V" to null, "C" to 1, "A" to 2, "B" to 3), snapshot.waiting.map { it.identifier to it.queue })
    }
}
