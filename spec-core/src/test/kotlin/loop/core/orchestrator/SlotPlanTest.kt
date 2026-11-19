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
}
