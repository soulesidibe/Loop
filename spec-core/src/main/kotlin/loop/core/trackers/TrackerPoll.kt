package loop.core.trackers

import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a read over several trackers gave (m4-13): the [issues] of the trackers that answered, merged in tracker order,
 * and the error of each tracker that failed, by tracker id. An issue of a failed tracker is missing from [issues]
 * without having left anything, so a caller asks [answered] before it reads a missing issue as gone.
 */
class TrackerRead internal constructor(
    val issues: List<Issue>,
    val failures: Map<String, Throwable>,
    private val trackerOf: (String) -> String,
    /** How many trackers this read asked. */
    private val asked: Int,
) {
    /** True when the tracker of [issueId] answered this read. */
    fun answered(issueId: String): Boolean = trackerOf(issueId) !in failures

    /** True when every tracker asked failed, so the read shows nothing at all. */
    val nothingAnswered: Boolean get() = asked > 0 && failures.size == asked
}

/**
 * The per tracker loop of the orchestrator (m4-13, story 113): each tracker of [adapters] is polled with its own
 * active states and read with its own terminal states, and a tracker that fails is reported in [TrackerRead.failures]
 * and skipped while the others go on. As a [TrackerAdapter] it routes each by id read and write to the issue's own
 * tracker, read from the issue's namespaced id (m4-12), and hands the id over unchanged: each adapter of a
 * [TrackerRegistry] is a [NamespacedTracker], which gives its native adapter the native id. Writes follow
 * [WriteRouting] (m4-14): one for an issue whose tracker is not here fails, never falling back to the primary.
 *
 * With one tracker every id is read from it, and a lone adapter that is not namespaced takes every write, so it works
 * as before. The adapters are built once, at startup; [poll] and [terminal] take the states of the workflow in effect,
 * so a reload of a tracker's states reaches the next tick, and an adapter whose tracker left the workflow is no longer
 * read.
 */
class TrackerPoll(
    private val primary: String,
    /** The adapter of each tracker id, the primary first. */
    private val adapters: Map<String, TrackerAdapter>,
) : TrackerAdapter {
    init {
        require(primary in adapters) { "the primary tracker $primary has no adapter" }
    }

    /** The tracker ids, the primary first. */
    val ids: List<String> get() = adapters.keys.toList()

    /** The primary tracker's states. The tool config snapshot of an attempt takes [definedStatesOf] its issue (m4-14). */
    override val definedStates: List<String> get() = adapterOf(primary).definedStates

    /** Every write goes to the tracker of its issue, or fails naming a tracker the registry does not have (m4-14). */
    private val writes = WriteRouting(primary, adapters)

    /** The states of the tracker of [issueId] (m4-14), the primary's when no tracker of this poll has it. */
    fun definedStatesOf(issueId: String): List<String> = writes.route(issueId).getOrNull()?.definedStates ?: definedStates

    /** The tracker id of [issueId]: its namespace when a tracker of this poll has it, else the primary's. */
    fun trackerOf(issueId: String): String {
        if (adapters.size == 1) return primary
        return NamespacedId.read(issueId, primary).tracker.takeIf { it in adapters } ?: primary
    }

    /** Every tracker of [config] that has an adapter, polled with its own active states. */
    suspend fun poll(config: TrackersConfig): TrackerRead = byStates(config) { it.activeStates }

    /** Every tracker of [config] that has an adapter, read with its own terminal states (spec 8.6). */
    suspend fun terminal(config: TrackersConfig): TrackerRead = byStates(config) { it.terminalStates }

    /** [ids] read by id, each from its own tracker; the ids of a tracker that fails are left unread. */
    suspend fun readByIds(ids: List<String>): TrackerRead {
        val issues = mutableListOf<Issue>()
        val failures = linkedMapOf<String, Throwable>()
        val byTracker = ids.groupBy(::trackerOf)
        for ((tracker, own) in byTracker) {
            safely { adapterOf(tracker).fetchIssuesByIds(own) }.fold(
                onSuccess = { issues += it },
                onFailure = { failures[tracker] = it },
            )
        }
        return TrackerRead(issues, failures, ::trackerOf, byTracker.size)
    }

    /**
     * [issues] with the cross tracker blockers of their `Blocked by` lines checked against the trackers of [config]
     * (m4-16): see [CrossTrackerBlockers.check]. The dispatch calls it once per tick on its eligible candidates.
     */
    suspend fun withCrossTrackerBlockers(
        issues: List<Issue>,
        config: TrackersConfig,
    ): List<Issue> = CrossTrackerBlockers.check(issues, config, this)

    /** Every tracker read with the same [states]; fails when one of them fails. The orchestrator calls [poll]. */
    override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
        val issues = mutableListOf<Issue>()
        for (adapter in adapters.values) {
            issues += safely { adapter.fetchIssuesByStates(states) }.getOrElse { return Result.failure(it) }
        }
        return Result.success(issues)
    }

    /**
     * [ids] read by id from their own trackers, all or nothing: a caller that cannot tell the trackers apart fails
     * closed. The orchestrator's reconciliation and retries call [readByIds] instead.
     */
    override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
        val read = readByIds(ids)
        return read.failures.values
            .firstOrNull()
            ?.let { Result.failure(it) } ?: Result.success(read.issues)
    }

    override suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> = writes.moveIssue(issueId, toState)

    override suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> = writes.addComment(issueId, comment)

    override suspend fun moveIssue(
        issue: Issue,
        toState: String,
    ): Result<Unit> = writes.moveIssue(issue, toState)

    override suspend fun addComment(
        issue: Issue,
        comment: IssueComment,
    ): Result<Unit> = writes.addComment(issue, comment)

    override suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> = writes.updateLabels(issueId, add, remove)

    override suspend fun completeIssue(issue: Issue): Result<Issue> = adapterOf(trackerOf(issue.id)).completeIssue(issue)

    /** Creates an issue in the tracker [tracker] (m4-33), see [WriteRouting.createIssue]. */
    suspend fun createIssueIn(
        tracker: String,
        title: String,
        body: String,
        labels: List<String>,
    ): Result<Issue> = writes.createIssue(tracker, title, body, labels)

    override fun toString(): String = "TrackerPoll(${adapters.keys.joinToString()})"

    private fun adapterOf(tracker: String): TrackerAdapter = adapters.getValue(tracker)

    private suspend fun byStates(
        config: TrackersConfig,
        states: (TrackerEntry) -> List<String>,
    ): TrackerRead {
        val issues = mutableListOf<Issue>()
        val failures = linkedMapOf<String, Throwable>()
        var asked = 0
        for ((id, adapter) in adapters) {
            val entry = config.entry(id) ?: continue
            asked++
            safely { adapter.fetchIssuesByStates(states(entry)) }.fold(
                onSuccess = { issues += it },
                onFailure = { failures[id] = it },
            )
        }
        return TrackerRead(issues, failures, ::trackerOf, asked)
    }

    /** A tracker call with a thrown exception turned into a failed Result; a cancel still propagates. */
    private suspend fun <T> safely(call: suspend () -> Result<T>): Result<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /** The per tracker loop of [registry], each tracker with its namespaced adapter, the primary first. */
        fun of(registry: TrackerRegistry): TrackerPoll =
            TrackerPoll(registry.primary.id, registry.config.ids.associateWith { checkNotNull(registry.adapter(it)) })

        /** [tracker] itself when it is a [TrackerPoll], else a poll of that one tracker, the [primary]. */
        fun of(
            tracker: TrackerAdapter,
            primary: String,
        ): TrackerPoll = tracker as? TrackerPoll ?: TrackerPoll(primary, mapOf(primary to tracker))
    }
}
