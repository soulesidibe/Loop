package loop.core.orchestrator

import loop.api.Issue
import loop.core.trackers.TrackerSlots
import loop.core.workflow.AgentConfig
import kotlin.time.Instant

/**
 * What holds an agent slot of the project now: the normalized state of each running attempt, as reconciliation
 * last read it, and the number of running merge checks, phase `check` work only. A restored wait's checks and a
 * delivery's merge phase take no slot (bl-173). [attemptTrackers] holds the tracker id of each running attempt, for the
 * per tracker limits (m4-15); an attempt of no tracker of the workflow is left out.
 */
internal data class SlotUse(
    val attemptStates: List<String>,
    val mergeChecks: Int,
    val attemptTrackers: List<String> = emptyList(),
)

/**
 * The attempt slots of one tick: the issues to launch, in that order ([launches]), the due retries with no slot to
 * requeue ([requeues]) and the fresh candidates with no slot to skip ([skips]). Launches are proposals: the
 * orchestrator may still fail one when it claims it and turn it into a skip or a requeue with its own reason.
 */
internal data class AttemptSlots(
    val launches: List<Issue>,
    val requeues: List<Issue>,
    val skips: List<Issue>,
    /** m4-17: the issues the plan's `held` hook kept from a slot, each with its reason, such as `area_busy`. */
    val held: Map<Issue, String> = emptyMap(),
)

/**
 * Who gets an agent slot (bl-86), in two pure steps that share one capacity rule: running attempts and running merge
 * checks together count against `agent.max_concurrent_agents`, `agent.max_concurrent_agents_by_state` and, since m4-15,
 * `agent.max_concurrent_agents_by_tracker` ([TrackerSlots]) cap attempt launches only, and at most one merge check runs
 * at a time. Step one, [mergeCheckFits], runs first in the tick, so a
 * merge check takes the last free slot ahead of due retries and candidates. Step two, [attempts], fills what is left.
 * A delivery's merge phase asks neither step: it holds no agent slot (bl-173).
 */
internal object SlotPlan {
    /** Step one: whether a merge check, phase `check` work, may start now. */
    fun mergeCheckFits(
        agent: AgentConfig,
        use: SlotUse,
    ): Boolean = use.mergeChecks == 0 && hasFreeSlot(agent, use)

    /**
     * Step two: the attempt slots for the [due] retries and the fresh [candidates], both already filtered for
     * eligibility, blockers and readiness, in [slotOrder]. A launch holds its slot for the rest of the plan, for the
     * total, for its state's cap and for its tracker's limit, so a capped state or tracker skips its later issues and
     * other states and trackers still get slots. [trackerOf] gives the tracker id of an issue id, null for none.
     * [held] (m4-17) gives the reason an issue may not launch after the launches planned before it, such as an area
     * one of them holds, or null; a held issue takes no slot and goes to [AttemptSlots.held].
     */
    fun attempts(
        agent: AgentConfig,
        use: SlotUse,
        due: List<DueRetry>,
        candidates: List<Issue>,
        trackerOf: (String) -> String? = { null },
        held: (Issue, List<Issue>) -> String? = { _, _ -> null },
    ): AttemptSlots {
        val retryDueAt = due.associate { it.issue.id to it.firstDueAt }
        val ordered = (due.map { it.issue } + candidates).distinctBy { it.id }.sortedWith(slotOrder(retryDueAt))
        val states = use.attemptStates.toMutableList()
        val trackers = use.attemptTrackers.toMutableList()
        val launches = mutableListOf<Issue>()
        val requeues = mutableListOf<Issue>()
        val skips = mutableListOf<Issue>()
        val holds = mutableMapOf<Issue, String>()
        for (issue in ordered) {
            val hold = held(issue, launches)
            if (hold != null) {
                holds[issue] = hold
                continue
            }
            val state = issue.state.normalized()
            val tracker = trackerOf(issue.id)
            if (hasFreeSlot(agent, SlotUse(states, use.mergeChecks)) &&
                stateHasRoom(agent, states, state) &&
                TrackerSlots.hasRoom(agent.maxConcurrentAgentsByTracker, trackers, tracker)
            ) {
                launches += issue
                states += state
                tracker?.let { trackers += it }
            } else if (issue.id in retryDueAt) {
                requeues += issue
            } else {
                skips += issue
            }
        }
        return AttemptSlots(launches, requeues, skips, holds)
    }

    /** The capacity rule: a running merge check holds a slot like an attempt. */
    private fun hasFreeSlot(
        agent: AgentConfig,
        use: SlotUse,
    ): Boolean = use.attemptStates.size + use.mergeChecks < agent.maxConcurrentAgents

    /** The per state cap of [state], for attempt launches only; a state without one is never capped. */
    private fun stateHasRoom(
        agent: AgentConfig,
        states: List<String>,
        state: String,
    ): Boolean {
        val cap = agent.maxConcurrentAgentsByState[state] ?: return true
        return states.count { it == state } < cap
    }

    /** Spec 8.2: priority 1 to 4 first, then the creation time (unknown last), then the identifier. */
    val dispatchOrder: Comparator<Issue> =
        compareBy<Issue> { issue -> issue.priority?.takeIf { it in 1..4 } ?: Int.MAX_VALUE }
            .thenComparator { a, b ->
                val left = a.createdAt
                val right = b.createdAt
                when {
                    left == right -> 0
                    left == null -> 1
                    right == null -> -1
                    else -> left.compareTo(right)
                }
            }.thenBy { it.identifier }

    /**
     * The order in which a tick fills its free slots (bl-34, deviation from spec 8.2): the issues with a
     * due retry first, by the time each first fell due ([retryDueAt]), then [dispatchOrder]; the new
     * candidates after them, in [dispatchOrder]. So a retry waiting for a slot is not starved by work
     * that became eligible later.
     */
    fun slotOrder(retryDueAt: Map<String, Instant>): Comparator<Issue> =
        compareBy<Issue> { it.id !in retryDueAt }
            .thenBy { retryDueAt[it.id] }
            .then(dispatchOrder)
}

/** A state as the per state caps key it, trimmed and lowercased. */
private fun String.normalized(): String = trim().lowercase()
