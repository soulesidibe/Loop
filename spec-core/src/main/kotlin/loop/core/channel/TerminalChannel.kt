package loop.core.channel

import loop.api.HumanChannel
import loop.api.HumanNotice

/**
 * The `terminal` channel (m4-30, PLAN 12), the core [HumanChannel] and the default of `channel:`: one line per notice,
 * written through [write], which `loop run` points at its stderr. The line names the identifier, the event, the reason,
 * the URL when there is one and the next command to type, for example
 * `loop run: m4-30 needs you, approval_waiting: verified, waits for approval. Next: loop approve m4-30`.
 */
class TerminalChannel(
    private val write: (String) -> Unit,
) : HumanChannel {
    override fun notify(notice: HumanNotice): Result<Unit> =
        try {
            write(line(notice))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        /** The characters of the reason kept on the line, so the line stays readable. */
        const val REASON_MAX: Int = 200

        /** The one line of [notice], with no line break. */
        fun line(notice: HumanNotice): String {
            val reason = cut(oneLine(notice.reason).trimEnd('.'))
            val about = listOfNotNull(reason.ifEmpty { null }, notice.url?.let(::oneLine)?.ifEmpty { null }).joinToString(", ")
            val what = if (about.isEmpty()) notice.event.wire else "${notice.event.wire}: $about"
            val next = notice.nextCommand?.let { " Next: $it" }.orEmpty()
            return "loop run: ${oneLine(notice.identifier)} needs you, $what.$next"
        }

        private fun cut(text: String): String = if (text.length > REASON_MAX) text.take(REASON_MAX).trimEnd() + "..." else text

        private fun oneLine(text: String): String = text.trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}
