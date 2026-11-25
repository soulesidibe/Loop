package loop.cli

import loop.api.AttemptId
import loop.core.resources.ReadOnlyChecks
import loop.core.resources.ResourceGrants
import loop.core.verify.CheckResult
import loop.core.workflow.CheckConfig
import kotlin.time.Duration

/**
 * m4-26: runs [run] for [check], a check of the verification or of the merge check, with the env of the resource it
 * holds. A `command` check that declares `resource: <kind>` takes a lock of that kind through [grants], in queue order
 * and under the machine limits like any grant, gets it prepared, and [run] gets what the prepare returned as
 * `LOOP_RESOURCE_<KEY>` env; the resource is released after the check. Any other check runs at once with no extra env.
 * The wait comes before [run], so the check's timeout starts when the check runs and a wait is never a failed check;
 * [waiting] hears every few minutes how long it has waited. A lock that cannot be taken or prepared is a check that
 * could not run, with the reason.
 *
 * A check of the verification carries its attempt in `LOOP_ATTEMPT_ID`, and the locks that attempt still holds in the
 * pool of the kind are given back first, so the check never waits on its own attempt; the merge check has none.
 */
internal suspend fun withCheckResource(
    grants: ResourceGrants,
    check: CheckConfig,
    env: Map<String, String>,
    waiting: (kind: String, waited: Duration) -> Unit = { _, _ -> },
    run: suspend (Map<String, String>) -> CheckResult,
): CheckResult {
    val kind = (check as? CheckConfig.Command)?.resource ?: return run(emptyMap())
    val issueId = env[ISSUE_ID].orEmpty()
    val identifier = env[ISSUE_IDENTIFIER] ?: issueId
    val attemptId = env[ATTEMPT_ID]?.takeIf { it.isNotEmpty() }?.let(::AttemptId)
    return grants
        .withCheckResource(kind, issueId, identifier, attemptId, waiting = { waiting(kind, it) }) { prepared ->
            run(ReadOnlyChecks.env(prepared))
        }.getOrElse { error ->
            CheckResult(null, timedOut = false, durationMs = 0, startError = "cannot hold a $kind resource: ${error.sentence()}")
        }
}

private const val ISSUE_ID = "LOOP_ISSUE_ID"
private const val ISSUE_IDENTIFIER = "LOOP_ISSUE_IDENTIFIER"
private const val ATTEMPT_ID = "LOOP_ATTEMPT_ID"
