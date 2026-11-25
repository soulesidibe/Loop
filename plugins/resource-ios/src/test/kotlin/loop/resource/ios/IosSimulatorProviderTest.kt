package loop.resource.ios

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** m4-27: the iOS simulator provider against the fake `xcrun` on its `PATH`, on the arguments it records. */
class IosSimulatorProviderTest {
    @TempDir
    lateinit var root: Path

    private val fake by lazy { FakeXcrun(root) }

    private val attempt = AttemptId("a-1")

    private fun simulator(
        id: String = "iphone-16",
        vararg provider: Pair<String, String> = arrayOf("device_type" to "iPhone 16", "runtime" to "iOS 18.2"),
    ) = ResourceDeclaration(kind = "ios-simulator", id = id, provider = mapOf(*provider))

    private fun holder(
        resourceId: String,
        kind: String = "ios-simulator",
    ) = ResourceHolder(ProjectId("p-1"), kind, resourceId, "attempt-$resourceId")

    @Test
    fun `the provider serves ios-simulator and is registered for ServiceLoader`() {
        assertEquals(setOf("ios-simulator"), IosSimulatorProvider().kinds)
        val services = javaClass.getResource("/META-INF/services/loop.api.ResourceProvider")!!.readText().trim()
        assertEquals(IosSimulatorProvider::class.java.name, services)
    }

    @Test
    fun `prepare creates the simulator from its device type and runtime, boots it, waits for it and returns its UDID`() {
        val prepared = fake.provider().prepare(simulator(), attempt)

        val created = fake.loop("iphone-16")!!
        assertEquals(mapOf("udid" to created.udid), prepared)
        assertEquals("Booted", created.state)
        assertEquals(
            listOf(
                listOf("simctl", "list", "devices", "-j"),
                listOf("simctl", "create", "loop-sim-iphone-16-4242", "iPhone 16", "iOS 18.2"),
                listOf("simctl", "boot", created.udid),
                listOf("simctl", "bootstatus", created.udid, "-b"),
            ),
            fake.calls(),
        )
    }

    @Test
    fun `prepare without a runtime leaves the runtime to simctl`() {
        fake.provider().prepare(simulator(provider = arrayOf("device_type" to "iPad Air 11-inch (M2)")), attempt)

        assertEquals(listOf("simctl", "create", "loop-sim-iphone-16-4242", "iPad Air 11-inch (M2)"), fake.calls()[1])
    }

    @Test
    fun `prepare clones a base given by name or UDID, and leaves the base shut down`() {
        val base = fake.seed("Base iPhone")

        val byName = fake.provider().prepare(simulator(provider = arrayOf("base" to "Base iPhone")), attempt)
        fake.provider().release(simulator())
        val byUdid = fake.provider().prepare(simulator(provider = arrayOf("base" to base)), attempt)

        val clone = fake.loop("iphone-16")!!
        assertEquals(mapOf("udid" to clone.udid), byUdid)
        assertTrue(byName.getValue("udid") != byUdid.getValue("udid"))
        assertEquals("Booted", clone.state)
        assertEquals("Shutdown", fake.named("Base iPhone")!!.state)
        assertEquals(2, fake.calls().count { it == listOf("simctl", "clone", base, "loop-sim-iphone-16-4242") })
        assertTrue(fake.calls().none { it.getOrNull(1) == "create" })
    }

    @Test
    fun `prepare refuses a base that is booted or missing, and clones nothing`() {
        val base = fake.seed("Base iPhone", state = "Booted")

        val booted = assertFailsWith<ResourceException> { fake.provider().prepare(simulator(provider = arrayOf("base" to base)), attempt) }
        val missing =
            assertFailsWith<ResourceException> { fake.provider().prepare(simulator(provider = arrayOf("base" to "Nope")), attempt) }

        assertEquals("the base simulator Base iPhone ($base) of iphone-16 is Booted, a clone needs it shut down", booted.message)
        assertEquals("the base simulator \"Nope\" of iphone-16 is not on the machine", missing.message)
        assertTrue(fake.calls().none { it.getOrNull(1) == "clone" })
    }

    @Test
    fun `prepare deletes a stale simulator of the resource, its own or a dead process's, and keeps a live process's`() {
        val own = fake.seed("loop-sim-iphone-16-4242", state = "Booted")
        val dead = fake.seed("loop-sim-iphone-16-17", state = "Shutdown")
        val otherHome = fake.seed("loop-sim-iphone-16-99", state = "Booted")
        fake.alive += 99L
        val person = fake.seed("iPhone 16")

        fake.provider().prepare(simulator(), attempt)

        assertEquals(
            listOf(listOf("simctl", "shutdown", own), listOf("simctl", "delete", own), listOf("simctl", "delete", dead)),
            fake.calls().subList(1, 4),
        )
        assertEquals(
            setOf(otherHome, person),
            fake
                .simulators()
                .filter { it.udid.startsWith("SEED") }
                .map { it.udid }
                .toSet(),
        )
        assertEquals("Booted", fake.named("loop-sim-iphone-16-99")!!.state)
        assertEquals("Booted", fake.loop("iphone-16")!!.state)
    }

    @Test
    fun `prepare refuses settings it cannot use, naming the resource and the key, before any xcrun call`() {
        val provider = fake.provider()

        fun refused(vararg settings: Pair<String, String>) =
            assertFailsWith<ResourceException> { provider.prepare(simulator(provider = arrayOf(*settings)), attempt) }.message

        assertEquals("iphone-16 needs provider.device_type, such as \"iPhone 16\", or provider.base, a simulator to clone", refused())
        assertEquals(
            "iphone-16 sets both provider.device_type and provider.base, keep one",
            refused("device_type" to "iPhone 16", "base" to "Base"),
        )
        assertEquals(
            "iphone-16 sets provider.runtime with provider.base, a clone keeps the runtime of its base",
            refused("base" to "Base", "runtime" to "iOS 18.2"),
        )
        assertEquals(
            "provider.devicetype of iphone-16 is not a key of the ios-simulator provider, use one of device_type, runtime, base, " +
                "prepare_timeout_seconds",
            refused("devicetype" to "iPhone 16"),
        )
        assertEquals(
            "provider.prepare_timeout_seconds of iphone-16 must be an integer from 1 to 3600, not \"0\"",
            refused("device_type" to "iPhone 16", "prepare_timeout_seconds" to "0"),
        )
        assertEquals(emptyList(), fake.calls())
    }

    @Test
    fun `release shuts the simulator down and deletes it, and the read only check passes only once it is gone`() {
        val provider = fake.provider()
        val udid = provider.prepare(simulator(), attempt).getValue("udid")
        val before = provider.readOnlyCheck(simulator())
        val calls = fake.calls().size

        provider.release(simulator())

        assertEquals(listOf("the simulator loop-sim-iphone-16-4242 ($udid) of iphone-16 is still on the machine, Booted"), before)
        assertEquals(
            listOf(
                listOf("simctl", "list", "devices", "-j"),
                listOf("simctl", "shutdown", udid),
                listOf("simctl", "delete", udid),
            ),
            fake.calls().drop(calls),
        )
        assertNull(fake.loop("iphone-16"))
        assertEquals(emptyList(), provider.readOnlyCheck(simulator()))
    }

    @Test
    fun `release of a simulator already shut down only deletes it, and release of none deletes nothing`() {
        val provider = fake.provider()
        val udid = provider.prepare(simulator(), attempt).getValue("udid")
        fake.setState(udid, "Shutdown")

        provider.release(simulator())
        provider.release(simulator())

        assertTrue(fake.calls().none { it.getOrNull(1) == "shutdown" })
        assertEquals(1, fake.calls().count { it.getOrNull(1) == "delete" })
    }

    @Test
    fun `the read only check of a clone reports a base left booted, and passes once the base is shut down`() {
        val base = fake.seed("Base iPhone")
        val resource = simulator(provider = arrayOf("base" to "Base iPhone"))
        val provider = fake.provider()
        provider.prepare(resource, attempt)
        fake.setState(base, "Booted")

        provider.release(resource)
        val booted = provider.readOnlyCheck(resource)
        fake.setState(base, "Shutdown")

        assertEquals(listOf("the base simulator Base iPhone ($base) of iphone-16 is Booted, not shut down"), booted)
        assertEquals(emptyList(), provider.readOnlyCheck(resource))
    }

    @Test
    fun `recover deletes the Loop simulators no live ios-simulator holder has, and leaves every other simulator alone`() {
        val held = fake.seed("loop-sim-held-4242", state = "Booted")
        val crashed = fake.seed("loop-sim-crashed-17", state = "Booted")
        val otherKind = fake.seed("loop-sim-pixel-4242", state = "Shutdown")
        val person = fake.seed("iPhone 16", state = "Booted")
        val lookalike = fake.seed("loop-iphone", state = "Booted")
        val noPid = fake.seed("loop-sim-mine", state = "Booted")

        fake.provider().recover(listOf(holder("held"), holder("pixel", kind = "avd")))

        assertEquals(listOf(held, person, lookalike, noPid), fake.simulators().map { it.udid })
        assertEquals(
            listOf(
                listOf("simctl", "list", "devices", "-j"),
                listOf("simctl", "shutdown", crashed),
                listOf("simctl", "delete", crashed),
                listOf("simctl", "delete", otherKind),
            ),
            fake.calls(),
        )
        assertTrue(fake.simulators().all { it.state == "Booted" })
    }

    @Test
    fun `recover keeps the Loop simulator of a live process with no holder in this store, such as a run of another Loop home`() {
        val otherHome = fake.seed("loop-sim-iphone-16-99", state = "Booted")
        fake.alive += 99L

        fake.provider().recover(emptyList())

        assertEquals(listOf(otherHome), fake.simulators().map { it.udid })
        assertEquals(listOf(listOf("simctl", "list", "devices", "-j")), fake.calls())
    }

    @Test
    fun `recover does nothing when no xcrun is on the PATH, as on a host with no simulators`() {
        fake.seed("loop-sim-crashed-17", state = "Booted")
        val provider = IosSimulatorProvider(Xcrun(fake.environment() + ("PATH" to root.resolve("empty").toString())))

        provider.recover(emptyList())

        assertEquals(emptyList(), fake.calls())
        assertEquals(1, fake.simulators().size)
    }

    @Test
    fun `a failing xcrun call fails with the command and its exit code`() {
        val provider = fake.provider(mapOf("FAKE_XCRUN_FAIL" to "create", "FAKE_XCRUN_FAIL_CODE" to "7"))

        val failure = assertFailsWith<ResourceException> { provider.prepare(simulator(), attempt) }

        assertEquals(
            "xcrun simctl create loop-sim-iphone-16-4242 \"iPhone 16\" \"iOS 18.2\" exited 7: " +
                "An error was encountered processing the command (domain=com.apple.CoreSimulator.SimError, code=405)",
            failure.message,
        )
    }

    @Test
    fun `a hanging wait for the boot fails once the prepare timeout passed`() {
        val provider = fake.provider(mapOf("FAKE_XCRUN_HANG" to "bootstatus"))
        val started = TimeSource.Monotonic.markNow()

        val failure =
            assertFailsWith<ResourceException> {
                provider.prepare(simulator(provider = arrayOf("device_type" to "iPhone 16", "prepare_timeout_seconds" to "1")), attempt)
            }

        val udid = fake.loop("iphone-16")!!.udid
        assertEquals("xcrun simctl bootstatus $udid -b timed out after 1s", failure.message?.replace(Regex("after [0-9.]+m?s"), "after 1s"))
        assertTrue(started.elapsedNow() < 10.seconds, "the hanging call was not stopped: ${started.elapsedNow()}")
    }

    @Test
    fun `a hanging call outside prepare fails once the command timeout passed`() {
        val provider = fake.provider(mapOf("FAKE_XCRUN_HANG" to "list"), commandTimeout = 1.seconds)

        val failure = assertFailsWith<ResourceException> { provider.release(simulator()) }

        assertEquals("xcrun simctl list devices -j timed out after 1s", failure.message)
    }
}
