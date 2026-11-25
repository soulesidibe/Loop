package loop.core.channel

import loop.api.HumanEvent
import loop.api.HumanNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-30: the terminal channel's own cases, one line per notice on the `loop run` terminal, with the identifier, the
 * event and the next command. The cases every channel shares run in [TerminalChannelContractTest].
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
