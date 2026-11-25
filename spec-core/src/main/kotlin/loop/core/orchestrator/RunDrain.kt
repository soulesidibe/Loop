package loop.core.orchestrator

import loop.api.EventSink
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.core.events.RunDrained
import loop.core.events.RunDraining
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.waiting.MergePhase
import loop.core.waiting.Wait
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the orchestrator holds at one moment, for the [RunDrain]: the issues whose attempt runs, the waits of the last
 * read of the store with the writes since, the retry queue by issue id with each identifier, whether the delivery queue
 * is paused (bl-175) and the issues whose merge job runs now.
 */
internal class DrainFacts(
    val running: List<Issue>,
    val waits: List<Wait>,
    val queued: Map<String, String>,
    val queuePaused: Boolean = false,
    val mergeJobs: Set<String> = emptySet(),
)

/** What still runs during a drain, by issue identifier, each list in name order. Empty lists mean the run can stop. */
internal data class DrainWork(
    /** The running attempts. */
    val attempts: List<String>,
    /** The waits that need no human: checks, merge work and approvals already given. */
    val waits: List<String>,
    /** The queued retries of the issues whose attempt ran when the drain began. */
    val retries: List<String>,
) {
    val isEmpty: Boolean get() = attempts.isEmpty() && waits.isEmpty() && retries.isEmpty()
}

/**
 * The drain of a run (m4-10, bl-110), a run state between `loop run` and `loop stop`: once [requested] says `loop drain`
 * asked for it, no candidate is dispatched, the running attempts finish with their verifications, CI waits, merge checks
 * and merges, then the run stops by itself. A retry of an issue whose attempt ran when the drain began still runs, it is
 * the same work; a retry of any other issue stays queued for the next `loop run`. A wait that needs a human, an approval
 * not given yet or a merge check behind a paused delivery queue, does not hold the drain and stays in the store.
 *
 * Held in memory only: a drain ends with its run, and a restart dispatches again. Not thread safe: the orchestrator calls
 * it from its dispatch coroutine.
 */
internal class RunDrain(
    private val requested: () -> Boolean,
    private val sink: EventSink,
    private val sinkName: String,
    private val log: KeyValueLog,
    private val clock: Clock,
    private val runId: RunId,
    private val projectId: ProjectId,
) {
    /** When the run noticed the drain, null while it dispatches. */
    private var began: Instant? = null

    /** The ids of the issues whose attempt ran when the drain began: their retries still run. */
    private var drained: Set<String> = emptySet()

    private val context = LogContext(runId = runId, projectId = projectId)

    /** True once the run noticed the drain. */
    val draining: Boolean get() = began != null

    /**
     * Called at the start of each dispatch: the first time the drain is asked for, it begins with the issues of [facts]
     * that run now and records `run_draining` with what still runs.
     */
    suspend fun notice(facts: DrainFacts) {
        if (began != null || !requested()) return
        began = clock.now()
        drained = facts.running.map { it.id }.toSet()
        val work = remaining(facts)
        val data =
            mapOfNotNull(
                RunDraining.ATTEMPTS to work.attempts.joined(),
                RunDraining.WAITS to work.waits.joined(),
                RunDraining.RETRIES to work.retries.joined(),
            )
        record(RunDraining.event(ts = clock.now(), projectId = projectId, data = data, runId = runId))
        log.log(LogLevel.INFO, RUN, LogOutcome.STARTED, context, fields = mapOf(DRAIN to true) + data)
    }

    /** [candidates] while the run dispatches, none during a drain. */
    fun candidates(candidates: List<Issue>): List<Issue> = if (draining) emptyList() else candidates

    /** The ids of [queued] whose retries must not fire: during a drain, every issue whose attempt did not run when it began. */
    fun heldBack(queued: Set<String>): Set<String> = if (draining) queued - drained else emptySet()

    /** What still runs, by the rules of the class comment. */
    fun remaining(facts: DrainFacts): DrainWork {
        // A merge job that already runs holds the drain, even behind a paused queue.
        val waits = facts.waits.filter { it.issueId in facts.mergeJobs || holdsDrain(it, facts.queuePaused) }
        return DrainWork(
            attempts = facts.running.map { it.identifier }.sorted(),
            waits = waits.map { it.identifier }.sorted(),
            retries =
                facts.queued
                    .filterKeys { it in drained }
                    .values
                    .sorted(),
        )
    }

    /**
     * Called after each dispatch: true when the run drains and nothing of [facts] still runs, after recording
     * `run_drained`. The caller then stops the run.
     */
    suspend fun drained(facts: DrainFacts): Boolean {
        val since = began ?: return false
        if (!remaining(facts).isEmpty) return false
        val drainedMs = (clock.now() - since).inWholeMilliseconds
        record(
            RunDrained.event(
                ts = clock.now(),
                projectId = projectId,
                data = mapOf(RunDrained.DRAINED_MS to drainedMs.toString()),
                runId = runId,
            ),
        )
        log.log(LogLevel.INFO, RUN, LogOutcome.COMPLETED, context, fields = mapOf(DRAIN to true, RunDrained.DRAINED_MS to drainedMs))
        return true
    }

    private suspend fun record(event: RecordedEvent) {
        log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
    }

    private fun List<String>.joined(): String? = takeIf { it.isNotEmpty() }?.joinToString(",")

    private companion object {
        const val RUN = "run"
        const val DRAIN = "drain"
    }
}

/**
 * Whether [wait] holds a drain (m4-10): a wait that needs no human does, one that needs a human does not and stays in
 * the store for the next `loop run`. A check wait holds. A merge wait holds unless it is a merge check behind a paused
 * delivery queue ([queuePaused], bl-175), which waits for `loop approve` or a restart. An approval holds once given, by
 * a human or by the auto policy, since the run delivers it at its next tick. An ask (m4-31) needs a human, so it does
 * not hold. Shared with `loop status`.
 */
fun holdsDrain(
    wait: Wait,
    queuePaused: Boolean,
): Boolean =
    when (wait) {
        is Wait.Verify -> true
        is Wait.Merge -> !(queuePaused && wait.phase == MergePhase.Check)
        is Wait.Approval -> wait.approved
        is Wait.Ask, is Wait.Other -> false
    }
