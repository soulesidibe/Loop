package loop.resource.ios

import loop.api.ResourceDeclaration
import loop.api.ResourceProvider
import loop.api.ResourceProviderContract
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** m4-27: [ResourceProviderContract] against the provider and the fake `xcrun`, a simulator created from its device type. */
class IosSimulatorProviderContractTest : ResourceProviderContract() {
    @TempDir
    lateinit var root: Path

    private val fake by lazy { FakeXcrun(root) }

    override fun provider(): ResourceProvider = fake.provider()

    override fun resource(): ResourceDeclaration =
        ResourceDeclaration(
            kind = "ios-simulator",
            id = "iphone-16",
            provider = mapOf("device_type" to "iPhone 16", "runtime" to "iOS 18.2"),
        )

    override fun leftBehind(resource: ResourceDeclaration): Boolean =
        fake.simulators().any { it.name.startsWith("loop-sim-${resource.id}-") }
}

/**
 * m4-27: [ResourceProviderContract] against the provider and the fake `xcrun`, a simulator cloned from a base, which a
 * careless holder can leave booted, so the read only check has a difference to report.
 */
class IosSimulatorCloneContractTest : ResourceProviderContract() {
    @TempDir
    lateinit var root: Path

    private val fake by lazy { FakeXcrun(root) }

    private val base by lazy { fake.seed("Base iPhone") }

    override fun provider(): ResourceProvider = fake.provider()

    override fun resource(): ResourceDeclaration =
        ResourceDeclaration(kind = "ios-simulator", id = "iphone-16", provider = mapOf("base" to base))

    override fun leftBehind(resource: ResourceDeclaration): Boolean =
        fake.simulators().any { it.name.startsWith("loop-sim-${resource.id}-") }

    override fun dirty(resource: ResourceDeclaration): Boolean {
        fake.setState(base, "Booted")
        return true
    }
}
