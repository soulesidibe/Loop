package loop.cli

import loop.core.events.IssueCommentRequested
import loop.core.events.IssueMoveRequested
import loop.core.events.Tracker
import loop.core.orchestrator.ToolRequest

/*
 * The tracker tools, `move` and `comment` (m1-10, m1-23, m2-17): their validation and queueing, shared by the
 * `loop tool move` and `loop tool comment` commands and by the `move` and `comment` tools of `loop tool mcp`, so a
 * request is the same whichever way the agent sent it, with the same refusals, replies and events. Each returns the
 * line the agent reads once its request is queued. A refusal goes through [AttemptTool.refuse], which prints it and
 * throws, and queues and records nothing.
 */

/**
 * Queues a move of the attempt's issue to [state] and records `issue_move_requested`. A state the tracker does not
 * define, or an attempt that is not live, is refused.
 */
internal suspend fun AttemptTool.queueMove(
    env: AttemptTool.Env,
    state: String,
): String {
    val defined = env.config.definedStates
    if (state !in defined) {
        refuse(
            "\"$state\" is not a state of the ${env.config.trackerKind} tracker. " +
                "Valid states: ${defined.joinToString(", ")}. ${AttemptTool.NOTHING_QUEUED}",
            AttemptTool.EXIT_REJECTED_ARGUMENT,
        )
    }
    return openStore(env).use { store ->
        val started = liveAttempt(store, env)
        enqueue(env, ToolRequest.Move(state), "the move of ${env.issueName}")
        record(store, env, started, IssueMoveRequested, mapOf(Tracker.TO to state), "queued")
        "Queued a move of ${env.issueName} to \"$state\". ${AttemptTool.APPLIED_WHEN_TURN_ENDS}"
    }
}

/**
 * Queues a comment on the attempt's issue and records `issue_comment_requested` with its length, never its text.
 * The text is redacted (token patterns) before it is trimmed and queued; one that is then empty, or an attempt that
 * is not live, is refused.
 */
internal suspend fun AttemptTool.queueComment(
    env: AttemptTool.Env,
    text: String,
): String {
    val written = redact(text).trim()
    if (written.isEmpty()) refuse(EMPTY_COMMENT, AttemptTool.EXIT_REJECTED_ARGUMENT)
    return openStore(env).use { store ->
        val started = liveAttempt(store, env)
        enqueue(env, ToolRequest.Comment(written), "the comment on ${env.issueName}")
        val chars = written.length
        record(store, env, started, IssueCommentRequested, mapOf(Tracker.CHARS to chars.toString()), "queued")
        "Queued a comment on ${env.issueName} ($chars characters). ${AttemptTool.APPLIED_WHEN_TURN_ENDS}"
    }
}

private const val EMPTY_COMMENT =
    "the comment is empty. Pass the text to write, for example " +
        "loop tool comment \"Should the export include archived items?\". ${AttemptTool.NOTHING_QUEUED}"
