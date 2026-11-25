package loop.core.channel

import loop.api.HumanChannel
import loop.api.HumanChannelContract

/** m4-30: the cases of [HumanChannelContract] against the terminal channel. */
class TerminalChannelContractTest : HumanChannelContract() {
    private var lines = mutableListOf<String>()

    override fun channel(): HumanChannel {
        val shown = mutableListOf<String>()
        lines = shown
        return TerminalChannel { shown += it }
    }

    override fun shown(): List<String> = lines
}
