package loop.cli

import loop.core.events.FollowupRequested
import loop.core.events.Tracker
import loop.core.orchestrator.ToolRequest
import loop.core.trackers.Followups
import loop.mcp.ToolReply

/*
 * The `file_followup` tool of `loop tool mcp` (m4-33, PLAN 6.10), for work the agent found outside its issue. Like
 * `comment` it only validates and queues: when the turn ends Loop files the follow up labelled `needs-triage` and
 * comments its id on the issue, see [Followups]. Every answer is one JSON object, like `ask`'s: `status` is `queued`, or
 * `error` for a structured failure, a tool error with an `error` code and a `message` that queued nothing, after which
 * the turn goes on.
 */

/**
 * Queues the follow up [title] with [body] for the attempt's issue, both redacted (token patterns) and trimmed, and
 * records `followup_requested`. A blank title fails with `empty_title`; past [Followups.MAX_PER_ATTEMPT] requests in the
 * attempt the call fails with `too_many_followups`; an attempt that is not live fails through [AttemptTool.refuse], as
 * for `comment`.
 */
internal suspend fun AttemptTool.queueFollowup(
    env: AttemptTool.Env,
    title: String,
    body: String,
): ToolReply {
    val written = redact(title).trim()
    if (written.isEmpty()) return structuredFailure("empty_title", "The title is empty. Give the follow up a short title.")
    val text = redact(body).trim()
    return openStore(env).use { store ->
        val started = liveAttempt(store, env)
        val requested =
            store
                .eventsForAttempt(env.attemptId)
                .getOrElse { refuse("cannot read the store: ${it.sentence()}. ${AttemptTool.NOTHING_QUEUED}", AttemptTool.EXIT_STORE) }
                .count { it.event.kind == FollowupRequested.name }
        if (Followups.refusal(requested) != null) {
            return@use structuredFailure(
                "too_many_followups",
                "You already filed $requested follow ups in this attempt, and Loop files at most ${Followups.MAX_PER_ATTEMPT} " +
                    "per attempt. Go on with your issue.",
            )
        }
        enqueue(env, ToolRequest.Followup(written, text), "the follow up of ${env.issueName}")
        record(store, env, started, FollowupRequested, mapOf(Tracker.CHARS to written.length.toString()), "queued")
        ToolReply(
            structuredJson(
                STATUS_QUEUED,
                "Queued a follow up of ${env.issueName}. When this turn ends, Loop files it labelled " +
                    "${Followups.LABEL} and comments its id on ${env.issueName}. Do not call file_followup again for it.",
            ),
        )
    }
}

private const val STATUS_QUEUED = "queued"
