package loop.api

import java.nio.file.Path

/**
 * What a runner needs to execute one attempt: its ids, the issue, the workspace
 * it runs in and the environment Loop exports to the agent process.
 */
public data class AttemptContext(
    public val runId: RunId,
    public val projectId: ProjectId,
    public val issue: Issue,
    public val attemptId: AttemptId,
    public val workspace: Path,
    public val env: Map<String, String>,
)
