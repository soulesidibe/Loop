package loop.core.workspace

import java.nio.file.Path

/**
 * The `LOOP_*` variables every hook gets, inside an attempt or not (plan R9):
 * `LOOP_WORKFLOW`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER` and `LOOP_WORKSPACE_KEY`.
 * Callers add their own ids on top. The hook runner puts them over the host
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
        "LOOP_ISSUE_ID" to issueId,
        "LOOP_ISSUE_IDENTIFIER" to issueIdentifier,
        "LOOP_WORKSPACE_KEY" to key.value,
    )
