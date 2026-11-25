package loop.api

import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * m4-30: [HumanChannelContract] against an in memory channel that keeps each message, then against channels that
 * break one rule each, which fail the contract with an assertion error.
 */
class FakeHumanChannelContractTest : HumanChannelContract() {
    private var last = FakeChannel()

    override fun channel(): HumanChannel = FakeChannel().also { last = it }

    override fun shown(): List<String> = last.messages

    /** Keeps one message per notice, its reason on one line. */
    private open class FakeChannel : HumanChannel {
        val messages = mutableListOf<String>()

        open fun message(notice: HumanNotice): String =
            listOfNotNull(
                "${notice.identifier} ${notice.event.wire}: ${notice.reason.lines().filter { it.isNotBlank() }.joinToString(" ")}",
                notice.url,
                notice.nextCommand?.let { "next: $it" },
            ).joinToString(", ")

        override fun notify(notice: HumanNotice): Result<Unit> {
            messages += message(notice)
            return Result.success(Unit)
        }
    }

    /** One wrong channel per rule: each one fails the case named. */
    @Nested
    inner class BrokenChannels {
        private fun contract(build: () -> FakeChannel): HumanChannelContract {
            var channel = build()
            return object : HumanChannelContract() {
                override fun channel(): HumanChannel = build().also { channel = it }

                override fun shown(): List<String> = channel.messages
            }
        }

        @Test
        fun `a channel that leaves the event out fails`() {
            val contract =
                contract {
                    object : FakeChannel() {
                        override fun message(notice: HumanNotice) = super.message(notice).replace(notice.event.wire, "event")
                    }
                }

            assertFailsWith<AssertionError> { contract.`each event reaches the human as one message naming the issue and the event`() }
        }

        @Test
        fun `a channel that leaves the next command out fails`() {
            val contract =
                contract {
                    object : FakeChannel() {
                        override fun message(notice: HumanNotice) = "${notice.identifier} ${notice.event.wire}: ${notice.reason}"
                    }
                }

            assertFailsWith<AssertionError> { contract.`the message names the next command to type`() }
        }

        @Test
        fun `a channel that shows a missing URL as null fails`() {
            val contract =
                contract {
                    object : FakeChannel() {
                        override fun message(notice: HumanNotice) = super.message(notice) + ", url ${notice.url}"
                    }
                }

            assertFailsWith<AssertionError> { contract.`a notice without a URL shows no URL`() }
        }

        @Test
        fun `a channel that sends one message per line of the reason fails`() {
            val contract =
                contract {
                    object : FakeChannel() {
                        override fun notify(notice: HumanNotice): Result<Unit> {
                            notice.reason.lines().filter { it.isNotBlank() }.forEach { messages += "${notice.identifier}: $it" }
                            return Result.success(Unit)
                        }
                    }
                }

            assertFailsWith<AssertionError> { contract.`a reason over several lines still reaches the human as one message`() }
        }
    }
}
