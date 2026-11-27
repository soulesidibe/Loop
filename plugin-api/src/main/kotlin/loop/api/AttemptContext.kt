package loop.api

import java.nio.file.Path

/**
 * What a runner needs to execute one attempt: its ids, the issue, the workspace
 * it runs in, the environment Loop exports to the agent process, and the optional
 * host-side [baseRef] (never exported in [env]) for runner pre-turn workspace checks.
 */
public data class AttemptContext(
    public val runId: RunId,
    public val projectId: ProjectId,
    public val issue: Issue,
    public val attemptId: AttemptId,
    public val workspace: Path,
    public val env: Map<String, String>,
    public val baseRef: String? = null,
    /**
     * bl-254: true when the workflow's `workspace.git` is `clone` and [workspace] is one of its clone workspaces. The
     * mode comes from the workflow, never from what the workspace's `.git` looks like: a runner's own git there checks
     * the clone's `.git` layout first and pins `GIT_DIR`, `GIT_COMMON_DIR` and `GIT_WORK_TREE` to the clone. False by
     * default, the `hooks` mode, which keeps every runner's behavior.
     */
    public val cloneWorkspace: Boolean = false,
)
