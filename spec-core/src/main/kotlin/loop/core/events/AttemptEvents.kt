package loop.core.events

import loop.api.RecordedEvent
import loop.core.events.HumanText.words
import loop.core.orchestrator.AttemptPhase
import loop.core.workflow.PromptBudget
import loop.core.workflow.PromptSlotSizes
import loop.core.workflow.TokenCounters
import java.util.Locale

/** The start of an attempt; `loop trace` reads it for the attempt header, the human log for the attempt's lines. */
object AttemptStarted : EventDefinition("attempt_started") {
    /** m1-12: the build that ran the attempt. */
    const val LOOP_VERSION = "loop_version"

    /** m1-15: the WORKFLOW.md version the attempt ran with. */
    const val CONFIG_VERSION = "config_version"

    /**
     * m3-09: `true` when the attempt's runner declares occupancy, else `false`: only the prompt limit and the turn cap
     * hold for it, and `loop status` says so. Absent on attempts recorded before m3-09.
     */
    const val OCCUPANCY = "occupancy"

    /**
     * m4-42: the attempt's ledger setup, as its ledger row names it: the tracker id, the issue kind, the runner kind,
     * the runner's model and the prompt template version. `kind` and `model` are absent when the setup has none. An
     * exporter puts them on every span of the attempt.
     */
    const val TRACKER = "tracker"
    const val KIND = "kind"
    const val RUNNER = "runner"
    const val MODEL = "model"
    const val TEMPLATE_VERSION = "template_version"

    override val sample =
        mapOf(
            LOOP_VERSION to "abc123",
            CONFIG_VERSION to "0123456789abcdef",
            OCCUPANCY to "true",
            TRACKER to "default",
            KIND to "code",
            RUNNER to "claude-code",
            MODEL to "claude-opus-5-5",
            TEMPLATE_VERSION to "fedcba9876543210",
        )
}

/** The end of an attempt; an attempt a later run found left open by a dead process shows in the timeline (m1-36). */
object AttemptFinished : EventDefinition("attempt_finished") {
    const val OUTCOME = "outcome"
    const val REASON = "reason"
    const val ERROR = "error"
    const val EXIT_REASON = "exit_reason"
    const val TURNS = "turns"

    /** The run that found the attempt abandoned (m1-36). */
    const val DETECTED_BY_RUN = "detected_by_run"

    const val SUCCEEDED = "succeeded"
    const val CANCELED = "canceled"

    /** The reason of an attempt a dead process left open. */
    const val ABANDONED = "abandoned"

    private const val BUDGET = "budget"
    private const val TOKEN_CAP = "token_cap"
    private const val STALLED = "stalled"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(OUTCOME to CANCELED, REASON to ABANDONED, DETECTED_BY_RUN to "r2")

    /** Only an abandoned attempt's end is a timeline line: its line shows when a later run detected it. */
    override fun showsInTimeline(event: RecordedEvent): Boolean = event.data[REASON] == ABANDONED

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(OUTCOME, event.data[OUTCOME])
        add(REASON, event.data[REASON])
        event.data[DETECTED_BY_RUN]?.let { word("$DETECTED_BY_RUN=${context.id(it)}") }
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val name = facts.attemptName
        return when (data[OUTCOME]) {
            SUCCEEDED -> {
                val state = facts.issueState?.let { ", the issue is in $it" }
                "$name succeeded" + (state ?: data[EXIT_REASON]?.let { ", ${words(it)}" }.orEmpty())
            }
            CANCELED -> "$name canceled, ${words(data[REASON] ?: CANCELED)}"
            else -> "$name failed, ${words(data[REASON] ?: "unknown reason")}"
        }
    }

    /**
     * The reason the trace header prints after the counts: a spend budget (m2-20) or the token cap it replaced, a stall (m1-09), or why a
     * canceled attempt was canceled, `stop_requested` and the reconciliation reasons (m1-16) printed as words, `abandoned`
     * as is (m1-36). Other failure reasons stay on the Failed line only.
     */
    fun headerReason(data: Map<String, String>): String? {
        data[EXIT_REASON]?.let { return it }
        val outcome = data[OUTCOME]
        val reason = data[REASON]
        return when {
            reason == null -> null
            reason == BUDGET || reason == TOKEN_CAP || reason == STALLED -> reason
            outcome == CANCELED && reason != CANCELED ->
                when (reason) {
                    HumanText.STOP_REQUESTED -> "stop requested"
                    HumanText.SIGNAL -> "signal received"
                    HumanText.INTERNAL_ERROR -> "internal error"
                    // m1-16: reconciliation stopped the attempt; the reason says where the issue went.
                    "terminal" -> "issue terminal"
                    "not_active" -> "issue not active"
                    "not_eligible" -> "issue not eligible"
                    "not_found" -> "issue not found"
                    else -> reason
                }
            else -> null
        }
    }
}

/** A phase of an attempt, its spec phase name in [RecordedEvent.phase]. */
object Phase : EventDefinition("phase") {
    const val WORKSPACE_KEY = "workspace_key"
    const val WORKSPACE = "workspace"

    /** m1-17: the rendered prompt's size, with the size of each prompt slot under [PromptSlotSizes.key]. */
    const val PROMPT_CHARS = "prompt_chars"
    const val RUNTIME_SESSION_ID = "runtime_session_id"
    const val EXIT_CODE = "exit_code"
    const val ISSUE_STATE = "issue_state"
    const val EXIT_REASON = "exit_reason"
    const val TURNS = "turns"
    const val REASON = "reason"

    override val trace = TraceShow.TIMELINE

    /** Only a prompt over its limit gets a line (m3-13), cut or not (m3-14), see [humanSentence]. */
    override val human = HumanShow.ISSUE

    /** A prompt over its limit, so the sample shows in the trace and writes the one human line a phase can write. */
    override val sample =
        mapOf(
            PROMPT_CHARS to "900",
            PromptBudget.PROMPT_TOKENS to "300",
            PromptBudget.PROMPT_LIMIT_TOKENS to "200",
            PromptBudget.OVER_LIMIT to "true",
            PromptBudget.TOKEN_COUNTER to TokenCounters.ESTIMATE,
        )
    override val samplePhase = AttemptPhase.BuildingPrompt.name

    override fun traceName(event: RecordedEvent): String = event.phase ?: name

    /**
     * m3-13: a `BuildingPrompt` whose prompt is over its limit, the one phase with `over_limit` set to true. Since m3-14 a
     * prompt cut to fit says what each slot lost, and whether the cut prompt is still over its limit.
     */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? {
        val tokens = data[PromptBudget.PROMPT_TOKENS]
        val limit = data[PromptBudget.PROMPT_LIMIT_TOKENS]
        val cuts = PromptSlotSizes.NAMES.mapNotNull { slot -> data[PromptSlotSizes.cutKey(slot)]?.toIntOrNull()?.let { slot to it } }
        val sentTokens = tokens?.toIntOrNull()
        val total = cuts.sumOf { it.second }
        if (total > 0 && sentTokens != null) {
            val slots = cuts.filter { it.second > 0 }.joinToString(", ") { (slot, cut) -> "$slot $cut" }
            val still = if (data[PromptBudget.OVER_LIMIT] == "true") ". It is still $tokens tokens, over its limit" else ""
            return "the prompt was ${sentTokens + total} tokens, over its limit of $limit tokens, so Loop cut $total tokens: $slots$still"
        }
        if (data[PromptBudget.OVER_LIMIT] != "true") return null
        // m3-14: the fill ran and no slot it may cut had anything to give, the template or a title for example.
        val nothing = if (cuts.isNotEmpty()) ", and no slot Loop may cut holds any of it" else ""
        return "the prompt is $tokens tokens, over its limit of $limit tokens$nothing. It is sent whole"
    }

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        if (event.phase == AttemptPhase.BuildingPrompt.name) {
            // m1-17: the prompt's slot sizes by name, in priority order; absent before m1-17.
            add(PROMPT_CHARS, event.data[PROMPT_CHARS])
            PromptSlotSizes.NAMES.forEach { slot -> add(slot, event.data[PromptSlotSizes.key(slot)]) }
            // m3-13: the prompt budget in tokens; absent before m3-13.
            add(PromptBudget.PROMPT_TOKENS, event.data[PromptBudget.PROMPT_TOKENS])
            PromptSlotSizes.NAMES.forEach { slot -> add("${slot}_tokens", event.data[PromptSlotSizes.tokenKey(slot)]) }
            add(PromptBudget.PROMPT_LIMIT_TOKENS, event.data[PromptBudget.PROMPT_LIMIT_TOKENS])
            add(PromptBudget.OVER_LIMIT, event.data[PromptBudget.OVER_LIMIT])
            add(PromptBudget.TOKEN_COUNTER, event.data[PromptBudget.TOKEN_COUNTER])
            // m3-14: the tokens each slot lost to the fill; absent when nothing was cut and before m3-14.
            PromptSlotSizes.NAMES.forEach { slot -> add(PromptSlotSizes.cutKey(slot), event.data[PromptSlotSizes.cutKey(slot)]) }
        } else {
            event.data.forEach { (key, value) -> add(key, value) }
        }
        // The thread is known once its first turn starts, so the launch line borrows it.
        if (event.phase == AttemptPhase.LaunchingAgentProcess.name) {
            event.sessionId?.let { word("session=${context.id(it.value)}") }
            context.thread?.let { word("thread=${context.id(it)}") }
        }
        if (event.phase == AttemptPhase.StreamingTurn.name) {
            event.turnId?.let { word("turn=${context.id(it.value)}") }
        }
    }

    /** The workspace path a PreparingWorkspace phase recorded once the workspace was ready, else null. */
    fun workspaceOf(event: RecordedEvent): String? =
        if (event.phase == AttemptPhase.PreparingWorkspace.name) event.data[WORKSPACE] else null

    /** True for the Succeeded phase, whose [ISSUE_STATE] is the state the attempt left the issue in. */
    fun isSucceeded(event: RecordedEvent): Boolean = event.phase == AttemptPhase.Succeeded.name
}

/** The fields every turn event records. */
abstract class TurnEvent(
    name: String,
) : EventDefinition(name) {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(TURN to "1", MAX_TURNS to "20", THREAD_ID to "thread-1", TURN_ID to "turn-1")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        word("number=${event.data[TURN] ?: "?"}/${event.data[MAX_TURNS] ?: "?"}")
        event.data[TURN_ID]?.let { word("turn=${context.id(it)}") }
        more(event, this)
    }

    /** What this turn event adds after the turn number and id. */
    protected open fun more(
        event: RecordedEvent,
        details: TraceDetails,
    ) = Unit

    companion object {
        const val TURN = "turn"
        const val MAX_TURNS = "max_turns"
        const val THREAD_ID = "thread_id"
        const val TURN_ID = "turn_id"

        /** m2-20: the turn went past a spend budget and was cut. */
        const val CAPPED = "capped"

        /** m3-12: `hard_limit` when Loop interrupted the turn because its session reached `context.hard_limit`. */
        const val INTERRUPTED = "interrupted"

        /**
         * m4-03: `true` on the end of a wake turn, one that read the notification of a background task run an earlier
         * turn started. It does not count against `agent.max_turns`; absent for an ordinary turn.
         */
        const val WAKE = "wake"
        const val REASON = "reason"

        /** bl-39: the runner's failure category, `runner_unavailable`, absent for an ordinary failure. */
        const val CATEGORY = "category"

        /** bl-39: the wait the runtime asked for with its category, absent when it gave none. */
        const val RETRY_AFTER_MS = "retry_after_ms"
    }
}

object TurnStarted : TurnEvent("turn_started") {
    override fun more(
        event: RecordedEvent,
        details: TraceDetails,
    ) {
        event.data[THREAD_ID]?.let { details.word("thread=${details.context.id(it)}") }
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "turn ${data[TURN]} of ${data[MAX_TURNS]} started"
}

object TurnCompleted : TurnEvent("turn_completed") {
    override fun more(
        event: RecordedEvent,
        details: TraceDetails,
    ) {
        details.add(WAKE, event.data[WAKE])
        details.add(INTERRUPTED, event.data[INTERRUPTED])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val wake = if (data[WAKE] == "true") ", a wake turn" else ""
        return if (data[INTERRUPTED] != null) {
            "turn ${data[TURN]}$wake was interrupted at the hard limit" + facts.spend
        } else {
            "turn ${data[TURN]} finished$wake" + facts.spend
        }
    }
}

object TurnFailed : TurnEvent("turn_failed") {
    override val sample = super.sample + (REASON to "exit_code=1")

    /** A `turn_failed` for input required: the `turn_input_required` line already says it. */
    override fun showsInTimeline(event: RecordedEvent): Boolean = event.data[REASON]?.startsWith(TurnInputRequired.name) != true

    override fun more(
        event: RecordedEvent,
        details: TraceDetails,
    ) {
        details.add(WAKE, event.data[WAKE])
        details.add(CATEGORY, event.data[CATEGORY])
        details.add(REASON, event.data[REASON])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val reason = data[REASON]?.let { ", ${words(it)}" }.orEmpty()
        return "turn ${data[TURN]} failed$reason" + facts.spend
    }
}

/** The agent asked for input, which ends its turn. */
object TurnInputRequired : EventDefinition("turn_input_required") {
    const val REASON = "reason"

    override val trace = TraceShow.TIMELINE
    override val sample = mapOf(REASON to "which branch?")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(REASON, event.data[REASON]) }
}

/** The agent was silent past the stall timeout (m1-09). */
object AttemptStalled : EventDefinition("attempt_stalled") {
    const val SILENT_MS = "silent_ms"
    const val STALL_TIMEOUT_MS = "stall_timeout_ms"

    override val sample = mapOf(SILENT_MS to "300000", STALL_TIMEOUT_MS to "300000")
}

/**
 * bl-136: the 10 second grace Loop gave an interrupted turn (m3-12) ran out before the runtime ended it, so Loop cancelled
 * the runner's flow and the usage the runtime had not reported yet is lost. Recorded on that turn with [GRACE_MS], the
 * session's latest occupancy ([OCCUPANCY_TOKENS], and [WINDOW] and [FRACTION] when the window is known) and the latest
 * usage known then ([ATTEMPT_TOKENS] and [TOTAL_COST_USD], absolute for the attempt as on `usage`), each absent when
 * unknown.
 */
object InterruptGraceExpired : EventDefinition("interrupt_grace_expired") {
    const val GRACE_MS = "grace_ms"
    const val OCCUPANCY_TOKENS = SessionRotated.OCCUPANCY_TOKENS
    const val WINDOW = SessionRotated.WINDOW
    const val FRACTION = SessionRotated.FRACTION
    const val ATTEMPT_TOKENS = Usage.ATTEMPT_TOKENS
    const val TOTAL_COST_USD = Usage.TOTAL_COST_USD

    private val TRACE_KEYS = listOf(GRACE_MS, OCCUPANCY_TOKENS, WINDOW, FRACTION, ATTEMPT_TOKENS, TOTAL_COST_USD)

    override val trace = TraceShow.TIMELINE
    override val sample =
        mapOf(
            GRACE_MS to "10000",
            OCCUPANCY_TOKENS to "300000",
            WINDOW to "1000000",
            FRACTION to "0.3000",
            ATTEMPT_TOKENS to "1200000",
            TOTAL_COST_USD to "3.38",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { TRACE_KEYS.forEach { add(it, event.data[it]) } }
}

/**
 * m4-04: the stop of an agent session gave the runtime its stop grace ([GRACE_MS], 5 seconds for Claude Code) and it was
 * still there, so the runner killed it and what the runtime had not reported yet is lost. [WAKE] says whether a wake
 * was running, [WAKE_MESSAGES] how many of its model messages counted in the spend from their own lines, and
 * [ATTEMPT_TOKENS] and [TOTAL_COST_USD] are the attempt's usage known after them, absolute as on `usage`, each absent
 * when unknown.
 */
object StopGraceExpired : EventDefinition("stop_grace_expired") {
    const val GRACE_MS = "grace_ms"
    const val WAKE = "wake"
    const val WAKE_MESSAGES = "wake_messages"
    const val ATTEMPT_TOKENS = Usage.ATTEMPT_TOKENS
    const val TOTAL_COST_USD = Usage.TOTAL_COST_USD

    private val TRACE_KEYS = listOf(GRACE_MS, WAKE, WAKE_MESSAGES, ATTEMPT_TOKENS, TOTAL_COST_USD)

    override val trace = TraceShow.TIMELINE
    override val sample =
        mapOf(
            GRACE_MS to "5000",
            WAKE to "true",
            WAKE_MESSAGES to "2",
            ATTEMPT_TOKENS to "1200000",
            TOTAL_COST_USD to "3.38",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { TRACE_KEYS.forEach { add(it, event.data[it]) } }
}

/** The agent session could not be stopped (m3-07): its process group may still run, or a file it held is left. */
object SessionStopFailed : EventDefinition("session_stop_failed") {
    const val REASON = "reason"

    override val sample = mapOf(REASON to "could not stop the claude-code process group 4242")
}

/**
 * m3-11: the agent session crossed `context.soft_limit` of its window during a turn, so at that turn's end Loop stopped
 * it and started a new one in the same workspace with a session brief. m3-12: or it reached `context.hard_limit`, so
 * Loop interrupted the turn at once and rotated, with [REASON] = [HARD_LIMIT]. Recorded when the new session's first
 * turn starts, so it names the new thread; when that turn ends before any start, at its end without the new ids.
 */
object SessionRotated : EventDefinition("session_rotated") {
    const val REASON = "reason"

    /** The session's latest occupancy, the input size of its latest main session request. */
    const val OCCUPANCY_TOKENS = "occupancy_tokens"
    const val WINDOW = "window"

    /** [OCCUPANCY_TOKENS] over [WINDOW], 4 decimals. */
    const val FRACTION = "fraction"

    /** The rotation's number in the attempt, from 1. */
    const val ROTATION = "rotation"
    const val MAX_ROTATIONS = "max_rotations"
    const val OLD_THREAD_ID = "old_thread_id"
    const val OLD_RUNTIME_SESSION_ID = "old_runtime_session_id"
    const val NEW_THREAD_ID = "new_thread_id"
    const val NEW_RUNTIME_SESSION_ID = "new_runtime_session_id"

    /** The [REASON] of a rotation at the soft limit, at a turn's end. */
    const val SOFT_LIMIT = "soft_limit"

    /** The [REASON] of a rotation at the hard limit, which interrupted the turn (m3-12). */
    const val HARD_LIMIT = "hard_limit"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample =
        mapOf(
            REASON to SOFT_LIMIT,
            OCCUPANCY_TOKENS to "250000",
            WINDOW to "1000000",
            FRACTION to "0.2500",
            ROTATION to "1",
            MAX_ROTATIONS to "5",
            OLD_THREAD_ID to "1b2c3d4e-old",
            NEW_THREAD_ID to "5f6a7b8c-new",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(REASON, data[REASON])
        word("$ROTATION=${data[ROTATION] ?: "?"}/${data[MAX_ROTATIONS] ?: "?"}")
        listOf(OCCUPANCY_TOKENS, WINDOW, FRACTION).forEach { add(it, data[it]) }
        data[OLD_THREAD_ID]?.let { word("from=${context.id(it)}") }
        data[NEW_THREAD_ID]?.let { word("to=${context.id(it)}") }
        // m3-14: the budget of the new session's prompt and the tokens each slot lost, only when the fill cut it.
        listOf(PromptBudget.PROMPT_TOKENS, PromptBudget.PROMPT_LIMIT_TOKENS, PromptBudget.OVER_LIMIT).forEach { add(it, data[it]) }
        PromptSlotSizes.NAMES.forEach { slot -> add(PromptSlotSizes.cutKey(slot), data[PromptSlotSizes.cutKey(slot)]) }
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val share =
            data[FRACTION]?.toDoubleOrNull()?.let { " at ${String.format(Locale.ROOT, "%.0f", it * 100)} percent of its window" }
        val rotation = "rotation ${data[ROTATION]} of at most ${data[MAX_ROTATIONS]}${cutClause(data)}"
        if (data[REASON] == HARD_LIMIT) return "interrupted its turn${share.orEmpty()} and started a new session, $rotation"
        return "started a new session${share.orEmpty()}, $rotation"
    }

    /** m3-14: what the fill cut from the new session's prompt, empty when it cut nothing. */
    private fun cutClause(data: Map<String, String>): String {
        val cuts = PromptSlotSizes.NAMES.mapNotNull { slot -> data[PromptSlotSizes.cutKey(slot)]?.toIntOrNull()?.let { slot to it } }
        val total = cuts.sumOf { it.second }
        if (total <= 0) return ""
        val slots = cuts.filter { it.second > 0 }.joinToString(", ") { (slot, cut) -> "$slot $cut" }
        val limit = data[PromptBudget.PROMPT_LIMIT_TOKENS]?.let { " of $it tokens" }.orEmpty()
        val over = data[PromptBudget.OVER_LIMIT] == "true"
        val still = if (over) ". It is still ${data[PromptBudget.PROMPT_TOKENS]} tokens, over its limit" else ""
        return ", its prompt cut by $total tokens to fit its limit$limit: $slots$still"
    }
}

/** One runner output line, cut to the store's limit; the attempt file keeps it in full. */
object RunnerOutput : EventDefinition("runner_output") {
    const val STREAM = "stream"
    const val LINE = "line"
    const val CHARS = "chars"
    const val TRUNCATED = "truncated"
    const val RECORD_SEQ = "record_seq"

    override val sample = mapOf(STREAM to "stdout", LINE to "hello", CHARS to "5")
}

/**
 * A context source of the attempt's kind was not in the workspace when the prompt rendered (m4-19): the prompt lists it
 * as missing and the attempt goes on. One event per missing path and render.
 */
object ContextSourceMissing : EventDefinition("context_source_missing") {
    /** The path as `kinds.<kind>.context.files` writes it. */
    const val PATH = "path"

    /** The name of the attempt's kind. */
    const val KIND = "kind"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(PATH to "docs/brand/voice.md", KIND to "content")

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String = "the context source ${data[PATH]} of the ${data[KIND]} kind is not in the workspace, the prompt lists it as missing"
}

/** The rendered prompt could not be written to the attempt file (m1-17). */
object PromptRecordFailed : EventDefinition("prompt_record_failed") {
    const val REASON = "reason"

    override val sample = mapOf(REASON to "disk full")
}

/** A runner line could not be written to the attempt file. */
object RunnerOutputRecordFailed : EventDefinition("runner_output_record_failed") {
    const val REASON = "reason"

    override val sample = mapOf(REASON to "disk full")
}

/** One hook run, in or outside an attempt (m1-04); a failed or timed out one prints its last output lines. */
object HookFinished : EventDefinition("hook_finished") {
    const val HOOK = "hook"
    const val COMMAND = "command"
    const val EXIT_CODE = "exit_code"
    const val TIMED_OUT = "timed_out"
    const val DURATION_MS = "duration_ms"
    const val OUTPUT = "output"
    const val OUTPUT_TRUNCATED = "output_truncated"
    private const val OUTPUT_LINES = 20

    /** The keys printed, in order; `command` and `output` are left out. */
    private val TRACE_KEYS = listOf(HOOK, EXIT_CODE, TIMED_OUT, DURATION_MS)

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample =
        mapOf(
            HOOK to "before_run",
            COMMAND to "./setup.sh",
            EXIT_CODE to "1",
            TIMED_OUT to "false",
            DURATION_MS to "1200",
            OUTPUT to "setup failed\n",
        )

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { TRACE_KEYS.forEach { key -> add(key, event.data[key]) } }

    override fun traceOutput(event: RecordedEvent): List<String> {
        val failed = event.data[TIMED_OUT] == "true" || event.data[EXIT_CODE] != "0"
        if (!failed) return emptyList()
        return (event.data[OUTPUT] ?: "")
            .lines()
            .dropLastWhile { it.isBlank() }
            .takeLast(OUTPUT_LINES)
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String? {
        val hook = data[HOOK] ?: return null
        if (data[TIMED_OUT] == "true") {
            val after = data[DURATION_MS]?.toLongOrNull()?.let { " after ${HumanText.duration(it)}" }.orEmpty()
            return "hook $hook timed out$after"
        }
        val code = data[EXIT_CODE]
        if (code == "0") return null
        val lastLine =
            data[OUTPUT]
                ?.lines()
                ?.lastOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(HumanText.TEXT_MAX)
        val exit = code?.let { " with exit code $it" } ?: ""
        return "hook $hook failed$exit" + lastLine?.let { ": $it" }.orEmpty()
    }
}
