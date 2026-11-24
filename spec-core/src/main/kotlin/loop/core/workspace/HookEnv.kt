package loop.core.workspace

import loop.core.trackers.NamespacedId
import java.nio.file.Path

/**
 * The `LOOP_*` variables every hook gets, inside an attempt or not (plan R9):
 * `LOOP_WORKFLOW`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_WORKSPACE_KEY` and, since m4-12, `LOOP_TRACKER_ID`.
 * `LOOP_ISSUE_ID` is the native id of the namespaced [issueId] and `LOOP_TRACKER_ID` its tracker id, absent for a bare
 * id, which only a test hands the core. Callers add their own ids on top. The hook runner puts them over the host
 * environment; tracker credentials are never added (spec 15.3).
 */
fun hookEnv(
    workflow: Path,
    issueId: String,
    issueIdentifier: String,
    key: WorkspaceKey,
): Map<String, String> =
    mapOf(
        "LOOP_WORKFLOW" to workflow.toString(),
        "LOOP_ISSUE_ID" to NamespacedId.nativeOf(issueId),
        "LOOP_ISSUE_IDENTIFIER" to issueIdentifier,
        "LOOP_WORKSPACE_KEY" to key.value,
    ) + listOfNotNull(NamespacedId.trackerOf(issueId)?.let { "LOOP_TRACKER_ID" to it })
