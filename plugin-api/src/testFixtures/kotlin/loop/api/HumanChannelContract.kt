package loop.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The behavior every [HumanChannel] must show (m4-30): each notice reaches the human as one message that names the
 * issue, the event, the reason, the URL when there is one and the next command to type. A channel's test extends this
 * class and supplies a channel and what the human was shown through it, as
 * `loop.core.channel.TerminalChannelContractTest` does for the terminal channel.
 */
public abstract class HumanChannelContract {
    /** A new channel. [shown] reads what the human was shown through the last channel built. */
    protected abstract fun channel(): HumanChannel

    /** Every message the human was shown through the last channel [channel] built, in order, one entry per message. */
    protected abstract fun shown(): List<String>

    private fun notice(
        event: HumanEvent,
        reason: String = "a reason",
        url: String? = null,
        identifier: String = "m4-30",
    ) = HumanNotice(
        issueId = "default:$identifier",
        identifier = identifier,
        event = event,
        reason = reason,
        url = url,
        at = Instant.parse("2026-11-25T10:00:00Z"),
    )

    /** The one message the human was shown, failing when there is none or more than one. */
    private fun onlyMessage(): String {
        val shown = shown()
        assertEquals(1, shown.size, "the notice did not show exactly one message: $shown")
        return shown.single()
    }

    @Test
    public fun `each event reaches the human as one message naming the issue and the event`() {
        val channel = channel()

        HumanEvent.KNOWN.forEachIndexed { index, event ->
            val result = channel.notify(notice(event, identifier = "m4-3$index"))

            assertTrue(result.isSuccess, "notify of ${event.wire} failed: ${result.exceptionOrNull()}")
            assertEquals(index + 1, shown().size, "notify of ${event.wire} did not show exactly one message: ${shown()}")
            val message = shown().last()
            assertTrue("m4-3$index" in message, "the message of ${event.wire} does not name the issue: $message")
            assertTrue(event.wire in message, "the message of ${event.wire} does not name the event: $message")
        }
    }

    @Test
    public fun `the message names the next command to type`() {
        val channel = channel()
        val expected =
            mapOf(
                HumanEvent.Parked to "loop resume m4-30",
                HumanEvent.ApprovalWaiting to "loop approve m4-30",
                HumanEvent.DeliveryFailed to "loop approve m4-30",
                HumanEvent.Question to "loop answer m4-30",
            )

        expected.forEach { (event, command) ->
            channel.notify(notice(event))

            assertTrue(command in shown().last(), "the message of ${event.wire} does not name \"$command\": ${shown().last()}")
        }
    }

    @Test
    public fun `the message carries the reason and the URL of the notice`() {
        val channel = channel()

        channel.notify(notice(HumanEvent.ApprovalWaiting, reason = "verified, 3 checks passed", url = "https://github.com/o/r/pull/7"))

        val message = onlyMessage()
        assertTrue("verified, 3 checks passed" in message, "the message does not carry the reason: $message")
        assertTrue("https://github.com/o/r/pull/7" in message, "the message does not carry the URL: $message")
    }

    @Test
    public fun `a notice without a URL shows no URL`() {
        val channel = channel()

        channel.notify(notice(HumanEvent.Parked, reason = "max_attempts"))

        val message = onlyMessage()
        assertFalse("null" in message, "the message shows a missing URL: $message")
        assertFalse("http" in message, "the message shows a URL the notice does not hold: $message")
    }

    @Test
    public fun `a reason over several lines still reaches the human as one message`() {
        val channel = channel()

        val result = channel.notify(notice(HumanEvent.DeliveryFailed, reason = "push refused\nremote: protected branch\n"))

        assertTrue(result.isSuccess, "notify failed: ${result.exceptionOrNull()}")
        val message = onlyMessage()
        assertTrue("push refused" in message && "remote: protected branch" in message, "the message lost part of the reason: $message")
    }
}
