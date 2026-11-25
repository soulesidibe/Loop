package loop.core.events

import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** How `loop trace` writes values. */
object TraceText {
    private const val TIME_START = 11
    private const val SECONDS_PREFIX = 19

    /** [value] as is when it is one plain word, else in double quotes with `\` and `"` escaped (shared with `loop status`). */
    fun quote(value: String): String {
        val plain = value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' || it == '=' || it == '\\' }
        if (plain) return value
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The `HH:MM:SS` part of a stored timestamp, or the text as is when it is too short. */
    fun clock(ts: String): String = if (ts.length >= SECONDS_PREFIX) ts.substring(TIME_START, SECONDS_PREFIX) else ts

    /** A recorded millisecond count as a duration, `1m 1.2s`, or the text as is when it is no number. */
    internal fun millis(ms: String?): String? = ms?.let { it.toLongOrNull()?.milliseconds?.toString() ?: it }
}

/** How the human log writes values. */
object HumanText {
    /** The characters of a commit sha the human log prints. */
    internal const val SHORT_SHA = 12

    /** The longest reason or output line the human log prints. */
    internal const val TEXT_MAX = 200

    private const val VERSION_CHARS = 8
    internal const val STOP_REQUESTED = "stop_requested"
    internal const val SIGNAL = "signal"
    internal const val INTERNAL_ERROR = "internal_error"

    /** A token count as people say it: `567`, `1.2K`, `7.2M`, `10M`, `1.5B`. */
    fun size(value: Long): String {
        if (value < 1000) return value.toString()
        var scaled = value.toDouble()
        for (unit in listOf("K", "M", "B")) {
            scaled /= 1000
            val rounded = Math.round(scaled * 10) / 10.0
            if (rounded < 1000 || unit == "B") {
                val text = String.format(Locale.ROOT, "%.1f", rounded).removeSuffix(".0")
                return "$text$unit"
            }
        }
        return value.toString()
    }

    /** Money in dollars with cents: `$3.38`. */
    fun money(usd: Double): String = String.format(Locale.ROOT, "$%.2f", usd)

    /** A delay as `45s`, `5m` or `2m 30s`. */
    fun duration(ms: Long): String {
        val seconds = (ms + 999) / 1000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (rest == 0L) "${minutes}m" else "${minutes}m ${rest}s"
    }

    /** `1 noun` or `n nouns`. */
    fun count(
        n: Int,
        noun: String,
    ): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** A config version as `loop trace` prints it (m1-15). */
    internal fun short(version: String?): String = version.orEmpty().take(VERSION_CHARS)

    /** m2-09: a count of acceptance commands, `1 command` or `<n> commands`. */
    internal fun commands(count: String?): String = if (count == "1") "1 command" else "$count commands"

    /** m2-09: a count of judgment calls, `1 judgment call` or `<n> judgment calls`. */
    internal fun judgmentCalls(count: String?): String = if (count == "1") "1 judgment call" else "$count judgment calls"

    /** A recorded reason in words; a reason Loop does not know is printed as it is. */
    fun words(reason: String): String =
        when {
            reason == "stalled" -> "the agent was silent for too long"
            reason == "budget" -> "the attempt passed a spend budget"
            reason == "refuted" -> "its checks refuted its last result"
            reason == "unverified" -> "its checks could not verify its last result"
            reason == "result_blocked" -> "the agent submitted a blocked result"
            reason == "result_failed" -> "the agent submitted a failed result"
            reason == "invalid_result" -> "the agent submitted an invalid result"
            reason.startsWith("exit_code=") -> "the agent exited with code ${reason.removePrefix("exit_code=")}"
            reason.startsWith("turn_input_required: ") -> "the agent asked for input: ${reason.removePrefix("turn_input_required: ")}"
            reason == "no_available_slots" -> "no free agent slot"
            reason.startsWith("blocked_by ") -> "blocked by ${reason.removePrefix("blocked_by ")}"
            reason.startsWith("not_ready (") -> "not ready, ${reason.removePrefix("not_ready (").removeSuffix(")")}"
            reason == "issue_state_refresh_error" -> "the issue could not be read after the turn"
            reason == STOP_REQUESTED -> "loop stop asked for it"
            reason == SIGNAL -> "the run got a signal"
            reason == INTERNAL_ERROR -> "an internal error stopped the run"
            reason == "not_found" -> "the issue is gone from the tracker"
            reason == "terminal" -> "the issue reached a terminal state"
            reason == "not_active" -> "the issue left the active states"
            reason == "not_eligible" -> "the issue is no longer eligible"
            reason == "max_turns" -> "it used all its turns"
            reason == "max_wake_turns" -> "it used all its wake turns"
            reason == "no_continuation" -> "the runner ran its one turn"
            reason == "waiting_approval" -> "its verified result waits for approval"
            reason == "waiting_ask" -> "it asked a human a question"
            reason == "split_issue" -> "its sessions filled up without progress"
            else -> reason.take(TEXT_MAX)
        }
}
