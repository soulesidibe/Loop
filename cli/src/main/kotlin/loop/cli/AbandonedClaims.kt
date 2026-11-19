package loop.cli

import loop.api.ProjectId
import loop.api.RunId
import loop.core.events.AttemptFinished
import loop.core.events.ClaimReleased
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.store.SqliteEventStore
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Releases the claims a dead process of [projectId] left in the store (m2-02), called once the run lock is
 * held, after the abandoned Attempts are closed and before the first dispatch. With the lock held, no live
 * process of this Project holds a claim (ADR 0001), so every stored claim belongs to a dead one. Each is
 * deleted and recorded as a `claim_released` with the reason `abandoned`, in the Run, Attempt and session of
 * the claim, with `detected_by_run` set to [runId], and a warning goes to the operator log. The issue is then
 * dispatched by the usual paths, once: through its stored retry when it has one, else as a candidate. Never
 * throws: a failure is logged and the run goes on, and a claim not released is tried again by the next run.
 */
internal suspend fun releaseAbandonedClaims(
    projectId: ProjectId,
    runId: RunId,
    store: SqliteEventStore,
    log: KeyValueLog,
    clock: Clock,
) {
    val context = LogContext(runId = runId, projectId = projectId)
    try {
        val claims =
            store.claims(projectId).getOrElse { error ->
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, context, reason = error.sentence())
                return
            }
        for (claim in claims) {
            val claimContext = context.copy(issueId = claim.issueId, issueIdentifier = claim.identifier, attemptId = claim.attemptId)
            val released = store.release(projectId, claim.issueId)
            if (released.isFailure) {
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, claimContext, reason = released.exceptionOrNull()?.sentence())
                continue
            }
            val event =
                ClaimReleased.event(
                    ts = clock.now(),
                    projectId = projectId,
                    data =
                        listOfNotNull(
                            ClaimReleased.REASON to ABANDONED,
                            ClaimReleased.DETECTED_BY_RUN to runId.value,
                            claim.runtimeSessionId?.let { ClaimReleased.RUNTIME_SESSION_ID to it },
                        ).toMap(),
                    runId = claim.runId,
                    issueId = claim.issueId,
                    issueIdentifier = claim.identifier,
                    attemptId = claim.attemptId,
                    sessionId = claim.sessionId,
                )
            store.record(event).onFailure { error ->
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, claimContext, reason = error.sentence())
            }
            log.log(
                LogLevel.WARN,
                "claim",
                LogOutcome.RELEASED,
                claimContext,
                fields = mapOf("previous_run" to claim.runId?.value),
                reason = ABANDONED,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(LogLevel.WARN, "claim", LogOutcome.FAILED, context, reason = "cannot release abandoned claims: ${e.sentence()}")
    }
}

private const val ABANDONED = AttemptFinished.ABANDONED
