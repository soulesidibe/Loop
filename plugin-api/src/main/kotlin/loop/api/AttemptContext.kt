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
)
