package loop.core.events

/**
 * m4-30: a human was told that a run needs them, through the channel [CHANNEL], about [EVENT] (`parked`,
 * `approval_waiting`, `delivery_failed` or `question`), with the [COMMAND] to type next. [CHANNEL_ERROR] is set when the
 * channel could not tell them; `hooks.on_human_needed` still ran. Silent in the human log: the channel already printed
 * the line.
 */
object HumanNotified : KeyedEvent("human_notified", HumanNotified.EVENT, HumanNotified.CHANNEL, HumanNotified.COMMAND) {
    const val EVENT = "event"
    const val CHANNEL = "channel"
    const val REASON = "reason"
    const val URL = "url"
    const val COMMAND = "command"
    const val CHANNEL_ERROR = "channel_error"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.SILENT
    override val sample =
        mapOf(
            EVENT to "approval_waiting",
            CHANNEL to "terminal",
            REASON to "a verified result waits for approval",
            COMMAND to "loop approve bl-1",
        )
}

/**
 * m4-30: a hook that never stops the run failed, timed out or could not start, so far only `on_human_needed`: [HOOK],
 * the [EXIT_CODE] when it exited, whether it [TIMED_OUT], and the [REASON]. Its `hook_finished` holds its output; the
 * run goes on. Silent in the human log, where that `hook_finished` already says it failed.
 */
object HookFailed : KeyedEvent("hook_failed", HookFailed.HOOK, HookFailed.EXIT_CODE, HookFailed.TIMED_OUT) {
    const val HOOK = "hook"
    const val EXIT_CODE = "exit_code"
    const val TIMED_OUT = "timed_out"
    const val REASON = "reason"

    override val trace = TraceShow.TIMELINE_AND_ISSUE_LEVEL
    override val human = HumanShow.SILENT
    override val sample =
        mapOf(HOOK to "on_human_needed", EXIT_CODE to "3", TIMED_OUT to "false", REASON to "hook on_human_needed failed with exit code 3")
}
