package loop.runner.claudecode

import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The rate limit snapshot of one Claude Code session, read from its `rate_limit_event` lines (bl-209), as the Codex
 * runner reads `account/rateLimits/updated`: each line gives the `claude` limit as a [RunnerEvent.RateLimit].
 *
 * - A window a line leaves out keeps what an earlier line said of it, so a line that names only `seven_day` keeps the
 *   `five_hour` window. A window without a utilization is not reported.
 * - The windows are ordered by length, shortest first, the length read from the name: `five_hour` is 300 minutes,
 *   `seven_day` 10080. A name whose length cannot be read goes last. The first is the primary window, the second the
 *   secondary, as Codex gives its five hour and weekly windows. Any further window is not reported.
 * - `reached` is the line's status when it is not `allowed`, such as `allowed_warning` or `rejected`, taken as each
 *   line gives it, so it clears.
 * - A line that changes nothing gives no event.
 */
internal class ClaudeRateLimits {
    private data class Snapshot(
        val primary: RunnerEvent.RateLimit.Window?,
        val secondary: RunnerEvent.RateLimit.Window?,
        val reached: String?,
    )

    private val windows = mutableMapOf<String, StreamLine.RateLimit.Window>()
    private var reported: Snapshot? = null

    /** The event [line] gives with the ids of the turn that read it, null when it changes nothing. */
    fun read(
        line: StreamLine.RateLimit,
        sessionId: SessionId,
        turnId: TurnId,
    ): RunnerEvent.RateLimit? {
        windows.putAll(line.windows)
        val ordered =
            windows
                .mapNotNull { (name, window) -> windowOf(name, window) }
                .sortedWith(compareBy(nullsLast()) { it.windowMinutes })
        val snapshot = Snapshot(ordered.getOrNull(0), ordered.getOrNull(1), line.status?.takeIf { it != ALLOWED })
        if (snapshot == reported) return null
        reported = snapshot
        return RunnerEvent.RateLimit(sessionId, turnId, LIMIT_ID, null, snapshot.primary, snapshot.secondary, snapshot.reached)
    }

    private fun windowOf(
        name: String,
        window: StreamLine.RateLimit.Window,
    ): RunnerEvent.RateLimit.Window? {
        val utilization = window.utilization ?: return null
        return RunnerEvent.RateLimit.Window(
            usedPercent = (utilization * PERCENT).roundToInt(),
            windowMinutes = minutesOf(name),
            resetsAt = window.resetsAt?.let(Instant::fromEpochSeconds),
        )
    }

    /** The length of the window [name] in minutes, from a `<count>_hour` or `<count>_day` start, null when it has none. */
    private fun minutesOf(name: String): Long? {
        val match = length.find(name) ?: return null
        val (count, unit) = match.destructured
        val n = count.toLongOrNull() ?: (numbers.indexOf(count) + 1).toLong().takeIf { it > 0 } ?: return null
        return n * if (unit == "hour") MINUTES_PER_HOUR else MINUTES_PER_DAY
    }

    private companion object {
        const val LIMIT_ID = "claude"
        const val ALLOWED = "allowed"
        const val PERCENT = 100
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 1440L
        val numbers = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        val length = Regex("""^(\d+|[a-z]+)_(hour|day)s?(?:_|$)""")
    }
}
