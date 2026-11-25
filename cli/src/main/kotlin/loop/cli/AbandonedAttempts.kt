package loop.cli

import loop.api.ProjectId
import loop.api.ResourceHolder
import loop.api.ResourceProvider
import loop.api.RunId
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.ResourceReleased
import loop.core.events.RunStopped
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.resources.ResourceLocks
import loop.store.SqliteEventStore
import loop.store.StoredEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Closes the Attempts a dead process of [projectId] left open (m1-36), called once the run lock is held, after
 * the attempt directory sweep and before the first dispatch. With the lock held, no live process of this Project
 * runs an Attempt (ADR 0001), so every `attempt_started` without its `attempt_finished` belongs to a dead one.
 * Each gets an `attempt_finished` with the outcome `canceled` and the reason `abandoned`, in the Run and for the
 * Issue of its `attempt_started`, with `detected_by_run` set to [runId], and a warning in the operator log. The
 * dead process then gets a `run_stopped` with the reason `abandoned` when it recorded none after those Attempts
 * started. An abandoned Attempt is not a failure: nothing is counted or scheduled. Never throws: a failure is
 * logged and the run goes on, and what the store did not record is tried again by the next run.
 */
internal suspend fun closeAbandonedAttempts(
    projectId: ProjectId,
    runId: RunId,
    store: SqliteEventStore,
    log: KeyValueLog,
    clock: Clock,
) {
    val context = LogContext(runId = runId, projectId = projectId)
    try {
        val events =
            store.eventsOfKinds(projectId, setOf(AttemptStarted.name, AttemptFinished.name, RunStopped.name)).getOrElse { error ->
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, context, reason = error.sentence())
                return
            }
        val open = openAttempts(events)
        val closedPerRun = mutableMapOf<RunId, Int>()
        for (stored in open) {
            val started = stored.event
            val attemptContext =
                context.copy(issueId = started.issueId, issueIdentifier = started.issueIdentifier, attemptId = started.attemptId)
            val finished =
                AttemptFinished.event(
                    ts = clock.now(),
                    projectId = started.projectId,
                    data =
                        mapOf(
                            AttemptFinished.OUTCOME to AttemptFinished.CANCELED,
                            AttemptFinished.REASON to ABANDONED,
                            AttemptFinished.DETECTED_BY_RUN to runId.value,
                        ),
                    runId = started.runId,
                    issueId = started.issueId,
                    issueIdentifier = started.issueIdentifier,
                    attemptId = started.attemptId,
                )
            store
                .record(finished)
                .onSuccess {
                    started.runId?.let { run -> closedPerRun.merge(run, 1, Int::plus) }
                    log.log(
                        LogLevel.WARN,
                        "attempt",
                        LogOutcome.FAILED,
                        attemptContext,
                        fields = mapOf("previous_run" to started.runId?.value),
                        reason = ABANDONED,
                    )
                }.onFailure { error ->
                    log.log(LogLevel.WARN, "store", LogOutcome.FAILED, attemptContext, reason = error.sentence())
                }
        }
        for ((deadRun, closed) in closedPerRun) {
            val lastStart = open.filter { it.event.runId == deadRun }.maxOf { it.seq }
            val stopped = events.any { it.event.kind == RunStopped.name && it.event.runId == deadRun && it.seq > lastStart }
            if (stopped) continue
            val event =
                RunStopped.event(
                    ts = clock.now(),
                    projectId = projectId,
                    data =
                        mapOf(
                            RunStopped.REASON to ABANDONED,
                            RunStopped.ATTEMPTS_CANCELED to closed.toString(),
                            RunStopped.DETECTED_BY_RUN to runId.value,
                        ),
                    runId = deadRun,
                )
            store.record(event).onFailure { error ->
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, context, reason = error.sentence())
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(LogLevel.WARN, "attempt", LogOutcome.FAILED, context, reason = "cannot close abandoned attempts: ${e.sentence()}")
    }
}

/**
 * Frees the resource locks a dead process of [projectId] left (m4-50), called once the run lock is held, after the
 * abandoned attempts are closed. With the lock held no live process of this Project runs an Attempt, so every lock and
 * queue row of the Project belongs to a dead one: the attempts [closeAbandonedAttempts] finds, and any attempt whose
 * free at its end failed. Each freed lock goes to the oldest request of its queue and records `resource_released` with
 * the reason `abandoned`. Then each of [providers] gets `recover` once with the holders of every lock left on the
 * machine, of every project, so it removes what a crash left on a resource nobody holds. Never throws: a failure is
 * logged and the run goes on, and a lock the store did not free is freed by the next run. A free that failed calls no
 * `recover`, since its rows could not be read.
 */
internal suspend fun freeAbandonedLocks(
    projectId: ProjectId,
    locks: ResourceLocks,
    providers: List<ResourceProvider>,
    log: KeyValueLog,
    context: LogContext,
) {
    try {
        val freed =
            locks.freeProject(projectId, ResourceReleased.ABANDONED).getOrElse { error ->
                log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, context, reason = "cannot free abandoned locks: ${error.sentence()}")
                return
            }
        for (lock in freed.released) {
            val lockContext = context.copy(issueId = lock.issueId, issueIdentifier = lock.issueIdentifier, attemptId = lock.attemptId)
            val fields = mapOf("kind" to lock.kind, "resource_id" to lock.resourceId)
            log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, lockContext, fields = fields, reason = ResourceReleased.ABANDONED)
        }
        if (providers.isEmpty()) return
        val holders =
            freed.rows.locks.map { ResourceHolder(it.projectId, it.kind, it.resourceId, it.attemptId.value) }
        for (provider in providers) {
            try {
                provider.recover(holders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fields = mapOf("provider" to provider.javaClass.name)
                log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, context, fields = fields, reason = "recover failed: ${e.sentence()}")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, context, reason = "cannot free abandoned locks: ${e.sentence()}")
    }
}

/** The `attempt_started` of every Attempt in [events] that has no `attempt_finished`, in seq order. */
private fun openAttempts(events: List<StoredEvent>): List<StoredEvent> {
    val ordered = events.sortedBy { it.seq }
    val finished = ordered.filter { it.event.kind == AttemptFinished.name }.mapNotNull { it.event.attemptId }.toSet()
    return ordered
        .filter { it.event.kind == AttemptStarted.name && it.event.attemptId != null && it.event.attemptId !in finished }
        .distinctBy { it.event.attemptId }
}

private const val ABANDONED = AttemptFinished.ABANDONED
