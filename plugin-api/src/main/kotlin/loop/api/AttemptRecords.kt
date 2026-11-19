package loop.api

import kotlin.time.Instant

/**
 * Appends records to the file of one Attempt, kept after the Attempt ends: the rendered prompt
 * (m1-13), the full runner stream (m2-18) and the full text of the tool replies trimmed for the agent (m3-14). Every text goes through the run's [Redactor] before
 * it is written. Blocking: the caller picks the thread. Implementations return a failed [Result]
 * instead of throwing, so a failed write never stops the Attempt.
 */
public fun interface AttemptRecordWriter {
    /**
     * Appends [record] to the file of [attemptId] in [projectId] and gives its sequence number in that file,
     * from 1. A failure means it was not written.
     */
    public fun append(
        projectId: ProjectId,
        attemptId: AttemptId,
        record: AttemptRecord,
    ): Result<Long>
}

/** One record of an Attempt file. The writer adds its `type` and its sequence number. */
public sealed interface AttemptRecord {
    public val ts: Instant

    /** The prompt rendered for [turn], as the runner received it. */
    public data class Prompt(
        override val ts: Instant,
        public val turn: Int,
        public val text: String,
    ) : AttemptRecord

    /** One runner output [line] in full, on its [stream] (m2-18). The `runner_output` event keeps a preview. */
    public data class RunnerOutput(
        override val ts: Instant,
        public val stream: RunnerEvent.Output.Stream,
        public val line: String,
    ) : AttemptRecord

    /**
     * The full [text] of a reply of Loop's MCP [tool] that reached the agent trimmed to `context.tool_output_tokens`
     * (m3-14).
     */
    public data class ToolOutput(
        override val ts: Instant,
        public val tool: String,
        public val text: String,
    ) : AttemptRecord
}
