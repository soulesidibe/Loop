package loop.core.events

import loop.api.RecordedEvent

/**
 * m4-41: `loop replay <attempt> --verify` ran the checks of the current `WORKFLOW.md` again on the state an attempt's
 * verdict recorded. Issue level: it carries the issue and no attempt id or run id, so the attempt's own events stay as
 * they were, and names the replayed attempt in [ATTEMPT]. Only a run writes the human log, so it has no sentence there.
 */
object ReplayVerdict : EventDefinition("replay_verdict") {
    const val ATTEMPT = "attempt"
    const val VERDICT = "verdict"
    const val RECORDED_VERDICT = "recorded_verdict"
    const val FINGERPRINT = "fingerprint"
    const val RECORDED_FINGERPRINT = "recorded_fingerprint"

    /** Where the checks ran: [WORKSPACE_COPY] or [WORKTREE]. */
    const val SITE = "site"

    /** How many checks the replay ran, reruns not counted, as `verdict` counts them. */
    const val CHECKS = "checks"

    /** Why the replayed verdict is `unverified`, absent otherwise. */
    const val REASON = "reason"

    /** A copy of the kept workspace, at the recorded fingerprint. */
    const val WORKSPACE_COPY = "workspace_copy"

    /** A temporary git worktree of the project repository at the recorded commit. */
    const val WORKTREE = "worktree"

    override val trace = TraceShow.ISSUE_LEVEL
    override val sample =
        mapOf(
            ATTEMPT to "a1",
            VERDICT to "verified",
            RECORDED_VERDICT to "verified",
            FINGERPRINT to "git:abc:def:0",
            RECORDED_FINGERPRINT to "git:abc:def:0",
            SITE to WORKTREE,
            CHECKS to "2",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        data[ATTEMPT]?.let { add(ATTEMPT, context.id(it)) }
        word(data[VERDICT])
        add("recorded", data[RECORDED_VERDICT])
        add(FINGERPRINT, data[FINGERPRINT])
        add(SITE, data[SITE])
        add(CHECKS, data[CHECKS])
        add(REASON, data[REASON])
    }
}
