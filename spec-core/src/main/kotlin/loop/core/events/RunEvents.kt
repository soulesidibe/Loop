package loop.core.events

import loop.api.RecordedEvent
import loop.core.events.HumanText.short

/** The workflow check of a tick (plan D8), run level. */
object Preflight : EventDefinition("preflight") {
    const val RESULT = "result"
    const val ERROR = "error"
    const val MESSAGE = "message"
    const val OK = "ok"
    const val FAILED = "failed"

    override val human = HumanShow.RUN
    override val sample = mapOf(RESULT to FAILED, ERROR to "invalid_config", MESSAGE to "tracker.kind is missing")

    /** True when the check failed. */
    fun failed(data: Map<String, String>): Boolean = data[RESULT] == FAILED

    /** Only the first failure and the recovery get a line. */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? {
        val failed = failed(data)
        return when {
            failed && !facts.preflightFailing -> "the workflow check failed, no dispatch until it passes: ${data[MESSAGE]}"
            !failed && facts.preflightFailing -> "the workflow check passes again"
            else -> null
        }
    }
}

/** m1-15: an edited WORKFLOW.md applied, run level. */
object ConfigReloaded : EventDefinition("config_reloaded") {
    const val CONFIG_VERSION = "config_version"
    const val PREVIOUS_VERSION = "previous_version"

    /** The unified diff of the change, redacted and cut, and `true` when it was cut. */
    const val DIFF = "diff"
    const val DIFF_TRUNCATED = "diff_truncated"

    override val human = HumanShow.RUN
    override val sample = mapOf(CONFIG_VERSION to "0123456789abcdef", PREVIOUS_VERSION to "fedcba9876543210")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "WORKFLOW.md reloaded, config ${short(data[CONFIG_VERSION])} replaces ${short(data[PREVIOUS_VERSION])}"
}

/** m1-15: an edited WORKFLOW.md rejected, the active config stays, run level. */
object ConfigReloadFailed : EventDefinition("config_reload_failed") {
    const val CONFIG_VERSION = "config_version"
    const val ACTIVE_VERSION = "active_version"
    const val ERROR = "error"
    const val MESSAGE = "message"
    const val DIFF = ConfigReloaded.DIFF
    const val DIFF_TRUNCATED = ConfigReloaded.DIFF_TRUNCATED

    override val human = HumanShow.RUN
    override val sample = mapOf(CONFIG_VERSION to "0123456789abcdef", ACTIVE_VERSION to "fedcba9876543210", MESSAGE to "bad yaml")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "WORKFLOW.md edit rejected, config ${short(data[ACTIVE_VERSION])} stays active: ${data[MESSAGE]}"
}

/** The end of a run stopped by a cancel, or of a dead run a later one detected (m1-36). */
object RunStopped : EventDefinition("run_stopped") {
    const val REASON = "reason"
    const val ATTEMPTS_CANCELED = "attempts_canceled"
    const val DETECTED_BY_RUN = AttemptFinished.DETECTED_BY_RUN

    override val sample = mapOf(REASON to HumanText.STOP_REQUESTED, ATTEMPTS_CANCELED to "1")
}

/** A claim a dead process left, released by a later run (m1-36). */
object ClaimReleased : EventDefinition("claim_released") {
    const val REASON = "reason"
    const val DETECTED_BY_RUN = AttemptFinished.DETECTED_BY_RUN
    const val RUNTIME_SESSION_ID = "runtime_session_id"

    override val sample = mapOf(REASON to AttemptFinished.ABANDONED, DETECTED_BY_RUN to "r2")
}

/**
 * bl-39: a turn failed with the category `runner_unavailable`, so the run holds every dispatch until the probe at
 * [PROBE_AT], the next due retry. Recorded once per hold period, with the issue and attempt whose failure began it.
 */
object RunnerHoldStarted : EventDefinition("runner_hold_started") {
    const val REASON = "reason"

    /** The period's number since the hold started, 1 for the first. */
    const val HOLD = "hold"
    const val DELAY_MS = "delay_ms"
    const val PROBE_AT = "probe_at"

    /** Set when the runtime asked to wait that long. */
    const val RETRY_AFTER_MS = "retry_after_ms"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.RUN
    override val sample =
        mapOf(
            REASON to "turn_failed: Failed to authenticate. API Error: 403",
            HOLD to "2",
            DELAY_MS to "120000",
            PROBE_AT to "2026-11-13T09:51:00Z",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(HOLD, data[HOLD])
        add("delay", TraceText.millis(data[DELAY_MS]))
        add("probe", data[PROBE_AT]?.let(TraceText::clock))
        add("retry_after", TraceText.millis(data[RETRY_AFTER_MS]))
        add(REASON, data[REASON])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val delay = data[DELAY_MS]?.toLongOrNull()?.let { " in ${HumanText.duration(it)}" }.orEmpty()
        val at = data[PROBE_AT]?.let { " at ${TraceText.clock(it)} UTC" }.orEmpty()
        return "runner unavailable, hold ${data[HOLD]}, no dispatch until the next probe$delay$at: ${data[REASON]}"
    }
}

/** bl-39: a successful turn lifted the hold of [RunnerHoldStarted], with the issue and attempt of that turn. */
object RunnerHoldLifted : EventDefinition("runner_hold_lifted") {
    /** The hold periods it lasted. */
    const val HOLDS = "holds"
    const val HELD_MS = "held_ms"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.RUN
    override val sample = mapOf(HOLDS to "2", HELD_MS to "180000")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(HOLDS, event.data[HOLDS])
        add("held", TraceText.millis(event.data[HELD_MS]))
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val held = data[HELD_MS]?.toLongOrNull()?.let { " after ${HumanText.duration(it)}" }.orEmpty()
        return "runner available again$held, dispatch resumes"
    }
}
