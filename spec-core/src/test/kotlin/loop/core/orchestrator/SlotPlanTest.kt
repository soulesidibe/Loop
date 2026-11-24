package loop.core.orchestrator

import loop.api.Issue
import loop.core.workflow.AgentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The two steps of the slot plan and the capacity rule they share (bl-86). */
class SlotPlanTest {
    private fun due(
        issue: Issue,
        firstDueAt: Int = 0,
    ): DueRetry = DueRetry(issue, attempt = 1, firstDueAt = FIXED_NOW + firstDueAt.seconds)

    private fun AttemptSlots.ids(): Triple<List<String>, List<String>, List<String>> =
        Triple(launches.map { it.identifier }, requeues.map { it.identifier }, skips.map { it.identifier })

    // Step one: the merge check slot.

    private data class MergeRow(
        val name: String,
        val agent: AgentConfig,
        val use: SlotUse,
        val fits: Boolean,
    )

    @Test
    fun `a merge check starts on a free slot when no other merge check runs`() {
        val rows =
            listOf(
                MergeRow("free slots", AgentConfig(maxConcurrentAgents = 2), SlotUse(listOf("todo"), mergeChecks = 0), fits = true),
                MergeRow("no attempt runs", AgentConfig(maxConcurrentAgents = 1), SlotUse(emptyList(), mergeChecks = 0), fits = true),
                MergeRow(
                    "full slots",
                    AgentConfig(maxConcurrentAgents = 2),
                    SlotUse(listOf("todo", "todo"), mergeChecks = 0),
                    fits = false,
                ),
                MergeRow("a merge check runs", AgentConfig(maxConcurrentAgents = 5), SlotUse(emptyList(), mergeChecks = 1), fits = false),
                MergeRow(
                    "a per state cap is for attempts only",
                    AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByState = mapOf("todo" to 1)),
                    SlotUse(listOf("todo"), mergeChecks = 0),
                    fits = true,
                ),
                MergeRow(
                    "the last free slot, a due retry waiting does not count",
                    AgentConfig(maxConcurrentAgents = 2),
                    SlotUse(listOf("todo"), mergeChecks = 0),
                    fits = true,
                ),
            )
        for (row in rows) assertEquals(row.fits, SlotPlan.mergeCheckFits(row.agent, row.use), row.name)
    }

    @Test
    fun `bl-92, a merge wait in phase ci holds no slot, so two attempts fill both slots and the plan is full when CI turns green`() {
        val agent = AgentConfig(maxConcurrentAgents = 2)

        // The tick of the merge check: step one gives it a slot, step two gives the other one to t-2.
        val atCheck = SlotUse(emptyList(), mergeChecks = 0)
        assertEquals(true, SlotPlan.mergeCheckFits(agent, atCheck))
        val withCheck = atCheck.copy(mergeChecks = 1)
        val first = SlotPlan.attempts(agent, withCheck, due = emptyList(), candidates = listOf(issue("t-2"), issue("t-3")))
        assertEquals(Triple(listOf("t-2"), emptyList(), listOf("t-3")), first.ids())

        // The check ended, the wait polls CI in phase ci and counts nowhere, so t-3 takes the slot of the check.
        val inCi = SlotUse(listOf("todo"), mergeChecks = 0)
        val second = SlotPlan.attempts(agent, inCi, due = emptyList(), candidates = listOf(issue("t-3")))
        assertEquals(Triple(listOf("t-3"), emptyList(), emptyList()), second.ids())

        // CI is green while t-2 and t-3 run: the plan has no slot left, so the merge phase must not ask it.
        val green = SlotUse(listOf("todo", "todo"), mergeChecks = 0)
        assertEquals(false, SlotPlan.mergeCheckFits(agent, green))
    }

    // Step two: the attempt slots.

    @Test
    fun `free slots launch every candidate in dispatch order`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 3),
                SlotUse(emptyList(), mergeChecks = 0),
                due = emptyList(),
                candidates = listOf(issue("m1-02"), issue("m1-01", priority = 1)),
            )

        assertEquals(Triple(listOf("m1-01", "m1-02"), emptyList(), emptyList()), slots.ids())
    }

    @Test
    fun `full slots launch nothing, requeue the due retries and skip the candidates`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 2),
                SlotUse(listOf("todo", "in_progress"), mergeChecks = 0),
                due = listOf(due(issue("m1-03"))),
                candidates = listOf(issue("m1-04")),
            )

        assertEquals(Triple(emptyList(), listOf("m1-03"), listOf("m1-04")), slots.ids())
    }

    @Test
    fun `slots fill in order until full, the rest is requeued or skipped`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 2),
                SlotUse(emptyList(), mergeChecks = 0),
                due = listOf(due(issue("m1-01")), due(issue("m1-02"), firstDueAt = 5)),
                candidates = listOf(issue("m1-03"), issue("m1-04")),
            )

        assertEquals(Triple(listOf("m1-01", "m1-02"), emptyList(), listOf("m1-03", "m1-04")), slots.ids())
    }

    @Test
    fun `a state at its cap skips its issues and another state takes the free slot`() {
        val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByState = mapOf("in_progress" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(listOf("in_progress"), mergeChecks = 0),
                due = emptyList(),
                candidates = listOf(issue("m1-01", state = "in_progress"), issue("m1-02")),
            )

        assertEquals(Triple(listOf("m1-02"), emptyList(), listOf("m1-01")), slots.ids())
    }

    @Test
    fun `a due retry blocked by its state's cap is requeued and another state's candidate launches (bl-77 finding 2)`() {
        val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByState = mapOf("in_progress" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(listOf("in_progress"), mergeChecks = 0),
                due = listOf(due(issue("m1-02", state = "in_progress"))),
                candidates = listOf(issue("m1-04")),
            )

        assertEquals(Triple(listOf("m1-04"), listOf("m1-02"), emptyList()), slots.ids())
    }

    @Test
    fun `a state cap counts the launches of the same tick, read on the normalized state`() {
        val agent = AgentConfig(maxConcurrentAgents = 5, maxConcurrentAgentsByState = mapOf("in_progress" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(emptyList(), mergeChecks = 0),
                due = emptyList(),
                candidates = listOf(issue("m1-01", state = "In_Progress "), issue("m1-02", state = "in_progress"), issue("m1-03")),
            )

        assertEquals(Triple(listOf("m1-01", "m1-03"), emptyList(), listOf("m1-02")), slots.ids())
    }

    @Test
    fun `a running merge check holds a slot against the attempts`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 2),
                SlotUse(listOf("todo"), mergeChecks = 1),
                due = listOf(due(issue("m1-02"))),
                candidates = listOf(issue("m1-03")),
            )

        assertEquals(Triple(emptyList(), listOf("m1-02"), listOf("m1-03")), slots.ids())
    }

    @Test
    fun `a merge check takes the last free slot ahead of a due retry`() {
        val agent = AgentConfig(maxConcurrentAgents = 2)
        val before = SlotUse(listOf("todo"), mergeChecks = 0)

        // Step one runs first in the tick and takes the last slot, so step two sees it held.
        val fits = SlotPlan.mergeCheckFits(agent, before)
        val slots = SlotPlan.attempts(agent, before.copy(mergeChecks = 1), due = listOf(due(issue("m1-02"))), candidates = emptyList())

        assertEquals(true, fits)
        assertEquals(Triple(emptyList(), listOf("m1-02"), emptyList()), slots.ids())
    }

    @Test
    fun `a due retry of priority 3 takes the last slot before a fresh candidate of priority 1`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 1),
                SlotUse(emptyList(), mergeChecks = 0),
                due = listOf(due(issue("m1-02", priority = 3))),
                candidates = listOf(issue("m1-05", priority = 1)),
            )

        assertEquals(Triple(listOf("m1-02"), emptyList(), listOf("m1-05")), slots.ids())
    }

    @Test
    fun `due retries take slots by the time each first fell due, then dispatch order`() {
        val slots =
            SlotPlan.attempts(
                AgentConfig(maxConcurrentAgents = 2),
                SlotUse(emptyList(), mergeChecks = 0),
                due =
                    listOf(
                        due(issue("m1-01", priority = 1), firstDueAt = 10),
                        due(issue("m1-03", priority = 3), firstDueAt = 0),
                        due(issue("m1-02", priority = 2), firstDueAt = 0),
                    ),
                candidates = emptyList(),
            )

        assertEquals(Triple(listOf("m1-02", "m1-03"), listOf("m1-01"), emptyList()), slots.ids())
    }

    // m4-15: the per tracker limit, `agent.max_concurrent_agents_by_tracker`.

    /** [identifier] of the tracker [tracker], with the namespaced id the core carries. */
    private fun of(
        tracker: String,
        identifier: String,
        state: String = "todo",
        priority: Int? = 2,
    ): Issue = issue(identifier, state = state, priority = priority).copy(id = "$tracker:$identifier")

    /** The tracker of a namespaced test id, as the orchestrator reads it from the workflow's trackers. */
    private val trackerOf: (String) -> String? = { id -> id.substringBefore(':', missingDelimiterValue = "").ifEmpty { null } }

    @Test
    fun `a tracker at its limit skips its issues and another tracker's candidate takes the free slot`() {
        val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByTracker = mapOf("gh" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(listOf("todo"), mergeChecks = 0, attemptTrackers = listOf("gh")),
                due = emptyList(),
                candidates = listOf(of("gh", "26", priority = 1), of("local", "l-1", priority = 3)),
                trackerOf = trackerOf,
            )

        assertEquals(Triple(listOf("l-1"), emptyList(), listOf("26")), slots.ids())
    }

    @Test
    fun `a tracker limit counts the launches of the same tick, so its second issue waits and the other tracker's launches`() {
        val agent = AgentConfig(maxConcurrentAgents = 5, maxConcurrentAgentsByTracker = mapOf("gh" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(emptyList(), mergeChecks = 0),
                due = emptyList(),
                candidates = listOf(of("gh", "26", priority = 1), of("gh", "27", priority = 1), of("local", "l-1", priority = 3)),
                trackerOf = trackerOf,
            )

        assertEquals(Triple(listOf("26", "l-1"), emptyList(), listOf("27")), slots.ids())
    }

    @Test
    fun `a due retry blocked by its tracker's limit is requeued and another tracker's candidate launches (bl-77 finding 2)`() {
        val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByTracker = mapOf("gh" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(listOf("open"), mergeChecks = 0, attemptTrackers = listOf("gh")),
                due = listOf(due(of("gh", "26"))),
                candidates = listOf(of("local", "l-1")),
                trackerOf = trackerOf,
            )

        assertEquals(Triple(listOf("l-1"), listOf("26"), emptyList()), slots.ids())
    }

    @Test
    fun `a tracker with no entry has no limit of its own`() {
        val agent = AgentConfig(maxConcurrentAgents = 5, maxConcurrentAgentsByTracker = mapOf("gh" to 1))
        val slots =
            SlotPlan.attempts(
                agent,
                SlotUse(listOf("todo", "todo"), mergeChecks = 0, attemptTrackers = listOf("local", "local")),
                due = emptyList(),
                candidates = listOf(of("local", "l-1"), of("local", "l-2")),
                trackerOf = trackerOf,
            )

        assertEquals(Triple(listOf("l-1", "l-2"), emptyList(), emptyList()), slots.ids())
    }

    @Test
    fun `a launch needs a free slot under all three limits`() {
        data class Row(
            val name: String,
            val agent: AgentConfig,
            val use: SlotUse,
            val launches: Boolean,
        )
        val issue = of("gh", "26", state = "in_progress")
        val rows =
            listOf(
                Row(
                    "room under every limit",
                    AgentConfig(2, maxConcurrentAgentsByState = mapOf("in_progress" to 1), maxConcurrentAgentsByTracker = mapOf("gh" to 1)),
                    SlotUse(emptyList(), mergeChecks = 0),
                    launches = true,
                ),
                Row(
                    "the total is full, the tracker has room",
                    AgentConfig(1, maxConcurrentAgentsByTracker = mapOf("gh" to 2)),
                    SlotUse(listOf("todo"), mergeChecks = 0, attemptTrackers = listOf("local")),
                    launches = false,
                ),
                Row(
                    "the state is at its cap, the tracker has room",
                    AgentConfig(5, maxConcurrentAgentsByState = mapOf("in_progress" to 1), maxConcurrentAgentsByTracker = mapOf("gh" to 2)),
                    SlotUse(listOf("in_progress"), mergeChecks = 0, attemptTrackers = listOf("local")),
                    launches = false,
                ),
                Row(
                    "the tracker is at its limit, the total and the state have room",
                    AgentConfig(5, maxConcurrentAgentsByState = mapOf("in_progress" to 2), maxConcurrentAgentsByTracker = mapOf("gh" to 1)),
                    SlotUse(listOf("todo"), mergeChecks = 0, attemptTrackers = listOf("gh")),
                    launches = false,
                ),
            )
        for (row in rows) {
            val slots = SlotPlan.attempts(row.agent, row.use, due = emptyList(), candidates = listOf(issue), trackerOf = trackerOf)
            assertEquals(row.launches, slots.launches.isNotEmpty(), row.name)
        }
    }

    @Test
    fun `a tracker limit caps attempts only, never the merge check slot`() {
        val agent = AgentConfig(maxConcurrentAgents = 3, maxConcurrentAgentsByTracker = mapOf("gh" to 1))

        assertEquals(true, SlotPlan.mergeCheckFits(agent, SlotUse(listOf("open"), mergeChecks = 0, attemptTrackers = listOf("gh"))))
    }

    @Test
    fun `m4-17 a candidate held by the area hook takes no slot, and only a launch planned before it can hold it`() {
        val agent = AgentConfig(maxConcurrentAgents = 2, maxConcurrentAgentsByState = mapOf("in_progress" to 0))
        val noSlot = issue("t-1", state = "in_progress", area = listOf("docs"))
        val first = issue("t-2", area = listOf("spec-core"))
        val overlapping = issue("t-3", area = listOf("spec-core/src"))
        val freeArea = issue("t-4", area = listOf("docs/agents"))
        val held = { candidate: Issue, launched: List<Issue> ->
            launched.firstOrNull { it.area.any { a -> candidate.area.any { b -> b.startsWith(a) } } }?.let { "area_busy ${it.identifier}" }
        }

        val slots =
            SlotPlan.attempts(agent, SlotUse(emptyList(), mergeChecks = 0), emptyList(), listOf(noSlot, first, overlapping, freeArea), held = held)

        // t-1 has no slot and holds nothing, so t-4 launches; t-3 is held by t-2 and leaves its slot to t-4.
        assertEquals(Triple(listOf("t-2", "t-4"), emptyList(), listOf("t-1")), slots.ids())
        assertEquals(mapOf("t-3" to "area_busy t-2"), slots.held.mapKeys { it.key.identifier })
    }
}
