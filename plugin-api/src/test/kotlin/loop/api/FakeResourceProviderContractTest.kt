package loop.api

import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * m4-24: [ResourceProviderContract] against an in memory provider over a fake machine, then against providers that
 * break one rule each, which fail the contract with an assertion error.
 */
class FakeResourceProviderContractTest : ResourceProviderContract() {
    private val machine = FakeMachine()

    override fun provider(): ResourceProvider = FakeProvider(machine)

    override fun resource(): ResourceDeclaration = SIMULATOR

    override fun leftBehind(resource: ResourceDeclaration): Boolean = resource.id in machine.booted

    override fun dirty(resource: ResourceDeclaration): Boolean = machine.changed.add(resource.id)

    /** One wrong provider per rule: each one fails the case named, never with a skip. */
    @Nested
    inner class BrokenProviders {
        private fun contract(build: (FakeMachine) -> ResourceProvider): ResourceProviderContract {
            val machine = FakeMachine()
            return object : ResourceProviderContract() {
                override fun provider(): ResourceProvider = build(machine)

                override fun resource(): ResourceDeclaration = SIMULATOR

                override fun leftBehind(resource: ResourceDeclaration): Boolean = resource.id in machine.booted

                override fun dirty(resource: ResourceDeclaration): Boolean = machine.changed.add(resource.id)
            }
        }

        @Test
        fun `a provider of another kind fails`() {
            val contract =
                contract {
                    object : ResourceProvider by FakeProvider(it) {
                        override val kinds = setOf("avd")
                    }
                }

            assertFailsWith<AssertionError> { contract.`the provider serves at least one kind, the kind of the resource among them`() }
        }

        @Test
        fun `a provider whose prepare returns a blank value fails`() {
            val contract =
                contract {
                    object : ResourceProvider by FakeProvider(it) {
                        override fun prepare(resource: ResourceDeclaration) = mapOf("udid" to " ")
                    }
                }

            assertFailsWith<AssertionError> { contract.`prepare returns what the agent needs as non blank keys and values`() }
        }

        @Test
        fun `a provider whose read only check misses a difference fails`() {
            val contract =
                contract {
                    object : ResourceProvider by FakeProvider(it) {
                        override fun readOnlyCheck(resource: ResourceDeclaration) = emptyList<String>()
                    }
                }

            assertFailsWith<AssertionError> { contract.`a difference the holder left is reported by the read only check after release`() }
        }

        @Test
        fun `a provider whose recover keeps what a crashed run left fails`() {
            val contract =
                contract {
                    object : ResourceProvider by FakeProvider(it) {
                        override fun recover(liveHolders: List<ResourceHolder>) = Unit
                    }
                }

            assertFailsWith<AssertionError> {
                contract.`recover with no live holder of the resource removes what a crashed run left, and the resource prepares again`()
            }
        }

        @Test
        fun `a provider whose recover also honors holders of other kinds fails`() {
            val contract =
                contract { machine ->
                    object : ResourceProvider by FakeProvider(machine) {
                        override fun recover(liveHolders: List<ResourceHolder>) {
                            machine.booted.removeAll { id -> liveHolders.none { it.resourceId == id } }
                        }
                    }
                }

            assertFailsWith<AssertionError> {
                contract.`recover with no live holder of the resource removes what a crashed run left, and the resource prepares again`()
            }
        }

        @Test
        fun `a provider whose recover removes the resource of a live holder fails`() {
            val contract =
                contract { machine ->
                    object : ResourceProvider by FakeProvider(machine) {
                        override fun recover(liveHolders: List<ResourceHolder>) = machine.booted.clear()
                    }
                }

            assertFailsWith<AssertionError> { contract.`recover leaves the resource of a live holder alone`() }
        }
    }

    /** A machine of simulators: the ids [booted] and those a holder [changed]. */
    class FakeMachine {
        val booted = mutableSetOf<String>()
        val changed = mutableSetOf<String>()
    }

    /** Boots a simulator on prepare and shuts it down on release; recover shuts down those no live holder of its kind has. */
    class FakeProvider(
        private val machine: FakeMachine,
    ) : ResourceProvider {
        override val kinds: Set<String> = setOf("ios-simulator")

        override fun prepare(resource: ResourceDeclaration): Map<String, String> {
            machine.booted += resource.id
            return mapOf("udid" to "udid-${resource.id}")
        }

        override fun release(resource: ResourceDeclaration) {
            machine.booted -= resource.id
        }

        override fun readOnlyCheck(resource: ResourceDeclaration): List<String> =
            if (resource.id in machine.changed) listOf("${resource.id} has an app installed") else emptyList()

        override fun recover(liveHolders: List<ResourceHolder>) {
            val held = liveHolders.filter { it.kind in kinds }.map { it.resourceId }.toSet()
            machine.booted.removeAll { it !in held }
        }
    }

    private companion object {
        val SIMULATOR = ResourceDeclaration(kind = "ios-simulator", id = "iphone-16", can = listOf("ui tests"))
    }
}
