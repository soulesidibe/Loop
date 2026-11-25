package loop.core.waiting

import loop.api.ProjectId
import loop.api.WaitReason
import loop.api.WaitingIssues
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The waits of one project (m2-04, m2-24, m2-26): the `Waiting(verify | approval | merge)` records in the [store]
 * and their transitions, with the copy in memory that decides for this process. Built per project from the store,
 * so the orchestrator, `loop approve` and `loop status` share it (ADR 0001); it does not do the work a wait waits
 * for: running checks and delivering stay with the orchestrator, merging is `loop.core.merge` (bl-85).
 *
 * The store is the source of truth, read once per tick by [read]: [current] then shows that read plus every write
 * made through this object since, the store as a read now would show it. The memory, [isBusy] and
 * [awaitsApproval], is what this process holds: a wait it could not write still keeps its issue waiting here, and a
 * store that cannot be read changes nothing in it. A store that throws is a failed [Result], like one that fails.
 * Not thread safe: like the orchestrator, it is used from one coroutine at a time.
 */
class Waits(
    private val store: WaitingIssues,
    private val projectId: ProjectId,
) {
    /** What each issue waits for in this process, by issue id. */
    private val held = mutableMapOf<String, WaitReason>()

    /**
     * The issues whose merge work runs now (m2-26), each true when the run holds an agent slot: one that started in
     * phase `check`, not one that started in phase `merge` (bl-173).
     */
    private val checking = mutableMapOf<String, Boolean>()

    /** The last read of the store, by issue id in the order it gave, plus the writes made through this object since. */
    private var stored = LinkedHashMap<String, Wait>()

    /** The ids of the issues waiting in this process: never dispatched, their retries never fire. */
    val busy: Set<String> get() = held.keys.toSet()

    /** The ids of the issues whose verification runs in this process (m4-17): their area stays busy. */
    val verifying: Set<String> get() = held.filterValues { it == WaitReason.Verify }.keys.toSet()

    /** True while [issueId] waits in this process, for any reason. */
    fun isBusy(issueId: String): Boolean = issueId in held

    /** True while [issueId] waits for approval in this process (m2-24). */
    fun awaitsApproval(issueId: String): Boolean = held[issueId] == WaitReason.Approval

    /**
     * Reads every wait of the project from the store, once per tick. An approval or a merge wait keeps its issue
     * waiting from then on, a verify wait only once its own path resumes it ([hold]). A failed read shows no wait in
     * [current] for this tick and changes nothing in memory.
     */
    suspend fun read(): Result<List<Wait>> {
        val read = guarded { store.waitingIssues(projectId) }.map { records -> records.map(Wait::of) }
        stored = LinkedHashMap()
        read.getOrNull()?.forEach { wait ->
            stored[wait.issueId] = wait
            if (wait is Wait.Approval || wait is Wait.Merge) hold(wait)
        }
        return read
    }

    /** The waits of the last [read], of [reason] when given, with the writes made since. */
    fun current(reason: WaitReason? = null): List<Wait> = stored.values.filter { reason == null || it.reason == reason }

    /** Keeps the issue of [wait] waiting in this process, for the wait's reason, without writing the store. */
    fun hold(wait: Wait) {
        held[wait.issueId] = wait.reason
    }

    /** Lets [issueId] go in this process, without writing the store: its stored wait, if any, is the next start's. */
    fun forget(issueId: String) {
        held -= issueId
    }

    /**
     * Writes [wait] to the store, replacing the issue's record, then holds it for its reason, written or not: a
     * transition from one reason to another is one call. The write's failure, which the caller logs.
     */
    suspend fun save(wait: Wait): Result<Unit> {
        val written = guarded { store.startWaiting(wait.toRecord()) }
        if (written.isSuccess) stored[wait.issueId] = wait
        hold(wait)
        return written
    }

    /** Removes the wait of [wait]'s issue from the store, then lets the issue go, removed or not. The removal's failure. */
    suspend fun end(wait: Wait): Result<Unit> {
        val ended = guarded { store.endWaiting(projectId, wait.issueId) }
        if (ended.isSuccess) stored -= wait.issueId
        held -= wait.issueId
        return ended
    }

    /**
     * Marks the approval wait of [issueId] approved by [by] at [at] (m2-24), through the store's own [WaitingIssues.approve],
     * one transaction in a store that has one, never a read then a write of its own. The wait as it is after the call,
     * whatever its reason, null when [issueId] does not wait.
     */
    suspend fun approve(
        issueId: String,
        by: String,
        at: Instant,
    ): Result<Wait?> = guarded { store.approve(projectId, issueId, by, at) }.map { it?.let(Wait::of) }

    /** The number of merge checks running now, each holding an agent slot (m2-26): phase `check` work only (bl-173). */
    val checks: Int get() = checking.count { it.value }

    /** True while the merge work of [issueId] runs, its merge check or its merge phase. */
    fun isChecking(issueId: String): Boolean = issueId in checking

    /** Counts the merge work of [issueId] as running, holding an agent slot when [holdsSlot]. */
    fun checkStarted(
        issueId: String,
        holdsSlot: Boolean = true,
    ) {
        checking[issueId] = holdsSlot
    }

    /** Counts the merge work of [issueId] as ended. */
    fun checkEnded(issueId: String) {
        checking -= issueId
    }

    private suspend fun <T> guarded(call: suspend () -> Result<T>): Result<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
