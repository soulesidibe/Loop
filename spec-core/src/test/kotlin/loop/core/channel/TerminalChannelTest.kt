package loop.core.channel

import loop.api.HumanEvent
import loop.api.HumanNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-30: the terminal channel. `spec-core` tests do not see the `plugin-api` test fixtures, so the first five cases
 * repeat the cases of `loop.api.HumanChannelContract` against it, one for one; the others are its own: one line per
 * notice on the `loop run` terminal, with the identifier, the event and the next command.
 */
class TerminalChannelTest {
    private val lines = mutableListOf<String>()
    private val channel = TerminalChannel { lines += it }

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

    private fun onlyLine(): String {
        assertEquals(1, lines.size, "the notice did not print exactly one line: $lines")
        return lines.single()
    }

    // The cases of HumanChannelContract.

    @Test
    fun `each event reaches the human as one message naming the issue and the event`() {
        HumanEvent.KNOWN.forEachIndexed { index, event ->
            val result = channel.notify(notice(event, identifier = "m4-3$index"))

            assertTrue(result.isSuccess, "notify of ${event.wire} failed: ${result.exceptionOrNull()}")
            assertEquals(index + 1, lines.size, "notify of ${event.wire} did not show exactly one message: $lines")
            assertTrue("m4-3$index" in lines.last(), lines.last())
            assertTrue(event.wire in lines.last(), lines.last())
        }
    }

    @Test
    fun `the message names the next command to type`() {
        val expected =
            mapOf(
                HumanEvent.Parked to "loop resume m4-30",
                HumanEvent.ApprovalWaiting to "loop approve m4-30",
                HumanEvent.DeliveryFailed to "loop approve m4-30",
                HumanEvent.Question to "loop answer m4-30",
            )

        expected.forEach { (event, command) ->
            channel.notify(notice(event))

            assertTrue(command in lines.last(), "the message of ${event.wire} does not name \"$command\": ${lines.last()}")
        }
    }

    @Test
    fun `the message carries the reason and the URL of the notice`() {
        channel.notify(notice(HumanEvent.ApprovalWaiting, reason = "verified, 3 checks passed", url = "https://github.com/o/r/pull/7"))

        val line = onlyLine()
        assertTrue("verified, 3 checks passed" in line, line)
        assertTrue("https://github.com/o/r/pull/7" in line, line)
    }

    @Test
    fun `a notice without a URL shows no URL`() {
        channel.notify(notice(HumanEvent.Parked, reason = "max_attempts"))

        val line = onlyLine()
        assertFalse("null" in line, line)
        assertFalse("http" in line, line)
    }

    @Test
    fun `a reason over several lines still reaches the human as one message`() {
        val result = channel.notify(notice(HumanEvent.DeliveryFailed, reason = "push refused\nremote: protected branch\n"))

        assertTrue(result.isSuccess, "notify failed: ${result.exceptionOrNull()}")
        val line = onlyLine()
        assertTrue("push refused" in line && "remote: protected branch" in line, line)
    }

    // The terminal channel's own cases.

    @Test
    fun `a notice prints one line with the identifier, the event, the reason, the URL and the next command`() {
        channel.notify(notice(HumanEvent.ApprovalWaiting, reason = "verified, waits for approval", url = "https://github.com/o/r/pull/7"))

        assertEquals(
            listOf(
                "loop run: m4-30 needs you, approval_waiting: verified, waits for approval, https://github.com/o/r/pull/7. " +
                    "Next: loop approve m4-30",
            ),
            lines,
        )
    }

    @Test
    fun `a park prints the resume command`() {
        channel.notify(notice(HumanEvent.Parked, reason = "max_attempts: exit code 1"))

        assertEquals(listOf("loop run: m4-30 needs you, parked: max_attempts: exit code 1. Next: loop resume m4-30"), lines)
    }

    @Test
    fun `the line holds no line break, a reason over several lines is joined with spaces`() {
        channel.notify(notice(HumanEvent.DeliveryFailed, reason = "push refused\r\n  remote: protected branch\n"))

        val line = onlyLine()
        assertFalse('\n' in line || '\r' in line, line)
        assertTrue("push refused remote: protected branch." in line, line)
    }

    @Test
    fun `a long reason is cut, so the line stays readable`() {
        channel.notify(notice(HumanEvent.Parked, reason = "x".repeat(2_000)))

        val line = onlyLine()
        assertTrue(line.length < 400, "the line is ${line.length} characters")
        assertTrue(line.endsWith("Next: loop resume m4-30"), line)
    }

    @Test
    fun `an event this Loop does not know prints no next command`() {
        channel.notify(notice(HumanEvent("reaction"), reason = "ci failed"))

        assertEquals(listOf("loop run: m4-30 needs you, reaction: ci failed."), lines)
    }

    @Test
    fun `a writer that throws is a failed result, never a throw`() {
        val broken = TerminalChannel { error("stderr is closed") }

        val result = broken.notify(notice(HumanEvent.Parked))

        assertTrue(result.isFailure)
    }
}
