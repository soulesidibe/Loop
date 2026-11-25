package loop.resource.android

import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** m4-28: the Android provider against fake `adb` and `emulator` scripts on its `PATH`, checked on the arguments they recorded. */
class AndroidResourceProviderTest {
    @TempDir
    lateinit var root: Path

    private val android by lazy { FakeAndroid(root) }

    @AfterEach
    fun stopFakeEmulators() = android.shutdown()

    @Test
    fun `a connected device is granted by its serial and no emulator starts`() {
        android.connect("R58M123")

        val prepared = android.provider().prepare(DEVICE)

        assertEquals(mapOf("serial" to "R58M123"), prepared)
        assertEquals(listOf("adb devices"), android.calls())
    }

    @Test
    fun `a device that is not connected, or connected but not ready, is refused naming its serial`() {
        val absent = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE) }
        android.connect("R58M123", state = "unauthorized")
        val unauthorized = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE) }

        assertEquals("android-device R58M123: the device is not connected, adb devices lists none", absent.message)
        assertEquals("android-device R58M123: the device is unauthorized, not ready", unauthorized.message)
        assertTrue(android.calls().none { it.startsWith("emulator") }, "${android.calls()}")
    }

    @Test
    fun `releasing a device leaves it connected and its read only check passes`() {
        android.connect("R58M123")
        val provider = android.provider()
        provider.prepare(DEVICE)

        provider.release(DEVICE)

        assertTrue(android.connected("R58M123"))
        assertEquals(emptyList(), provider.readOnlyCheck(DEVICE))
        assertTrue(android.calls().none { "emu kill" in it }, "${android.calls()}")
    }

    @Test
    fun `with no emulator of the avd connected, the avd is booted, waited for and granted, and release kills it`() {
        android.connect("R58M123")
        android.runningEmulator("emulator-5554", "other_avd")
        val provider = android.provider()

        val prepared = provider.prepare(AVD)

        assertEquals(mapOf("serial" to "emulator-5556"), prepared)
        assertEquals(listOf("emulator-5556"), android.emulatorsOf("pixel_api35"))
        val calls = android.calls()
        assertTrue("emulator -avd pixel_api35 -no-snapshot-save -port 5556" in calls, "$calls")
        assertTrue("adb -s emulator-5556 wait-for-device" in calls, "$calls")
        assertTrue("adb -s emulator-5556 shell getprop sys.boot_completed" in calls, "$calls")
        assertTrue(calls.indexOf("adb -s emulator-5556 wait-for-device") < calls.indexOf("adb -s emulator-5556 shell getprop sys.boot_completed"))

        provider.release(AVD)

        assertTrue("adb -s emulator-5556 emu kill" in android.calls(), "${android.calls()}")
        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
        assertTrue(android.connected("R58M123"), "the real device was disconnected")
        assertTrue(android.connected("emulator-5554"), "the other avd's emulator was killed")
        assertTrue(android.calls().none { "-s R58M123" in it || "-s emulator-5554 emu kill" in it }, "${android.calls()}")
    }

    @Test
    fun `an emulator of the avd already running outside Loop is granted as it is and left running on release`() {
        android.runningEmulator("emulator-5560", "pixel_api35")
        val provider = android.provider()

        val prepared = provider.prepare(AVD)
        provider.release(AVD)

        assertEquals(mapOf("serial" to "emulator-5560"), prepared)
        assertTrue(android.connected("emulator-5560"))
        assertTrue(android.calls().none { it.startsWith("emulator") || "emu kill" in it }, "${android.calls()}")
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
    }

    @Test
    fun `recover stops an emulator Loop booted for an attempt with no live lock and leaves a real device alone`() {
        android.connect("R58M123")
        android.provider().prepare(AVD)
        android.provider().prepare(DEVICE)

        android.provider().recover(listOf(ResourceHolder(PROJECT, "android-device", "R58M123", "attempt-2")))

        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertTrue("adb -s emulator-5554 emu kill" in android.calls(), "${android.calls()}")
        assertTrue(android.connected("R58M123"), "recover disconnected the real device")
        assertTrue(android.calls().none { "-s R58M123" in it }, "${android.calls()}")
    }

    @Test
    fun `recover leaves an emulator whose avd a live lock holds, and one whose booting run is alive`() {
        android.provider().prepare(AVD)

        android.provider().recover(listOf(ResourceHolder(PROJECT, "avd", "pixel_api35", "attempt-1")))
        val alive =
            AndroidResourceProvider(
                environment = mapOf("PATH" to "${root.resolve("bin")}:/usr/bin:/bin", "FAKE_ANDROID_MACHINE" to root.toString()),
                stateDir = android.stateDir,
                timeouts = AndroidTimeouts(),
                owner = "another-live-run",
                ownerAlive = { true },
                portFree = { true },
            )
        alive.recover(emptyList())

        assertEquals(listOf("emulator-5554"), android.emulatorsOf("pixel_api35"))
        assertTrue(android.calls().none { "emu kill" in it }, "${android.calls()}")
    }

    @Test
    fun `recover with nothing booted calls no adb`() {
        android.provider().recover(emptyList())

        assertEquals(emptyList(), android.calls())
    }

    @Test
    fun `a boot that never completes fails after the timeout naming the last sys_boot_completed value, and the emulator is stopped`() {
        android.flag("boot-never")
        val provider = android.provider(boot = 1.seconds)

        val failure = assertFailsWith<ResourceException> { provider.prepare(AVD) }

        assertEquals(
            "avd pixel_api35: emulator-5554 did not boot in 1s, the last sys.boot_completed read was \"0\"",
            failure.message,
        )
        assertTrue("adb -s emulator-5554 emu kill" in android.calls(), "${android.calls()}")
        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
    }

    @Test
    fun `a failing adb call fails with the command and its exit code`() {
        android.flag("fail-devices", "7")

        val failure = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE) }

        assertEquals("android-device R58M123: adb devices exited with code 7: error: fake devices failure", failure.message)
    }

    @Test
    fun `a failing emu kill fails release with the command and its exit code`() {
        val provider = android.provider(stop = 1.seconds)
        provider.prepare(AVD)
        android.flag("fail-emu-kill", "4")

        val failure = assertFailsWith<ResourceException> { provider.release(AVD) }

        assertEquals("avd pixel_api35: adb -s emulator-5554 emu kill exited with code 4: error: fake emu-kill failure", failure.message)
    }

    @Test
    fun `an emulator that exits before booting fails with its command and exit code`() {
        android.flag("emulator-exit", "1")

        val failure = assertFailsWith<ResourceException> { android.provider().prepare(AVD) }

        assertEquals(
            "avd pixel_api35: emulator -avd pixel_api35 -no-snapshot-save -port 5554 exited with code 1: " +
                "PANIC: Unknown AVD name [pixel_api35]",
            failure.message,
        )
    }

    @Test
    fun `an adb call that hangs fails after the command timeout naming the command`() {
        android.flag("hang-devices")

        val failure = assertFailsWith<ResourceException> { android.provider(command = 1.seconds).prepare(DEVICE) }

        assertEquals("android-device R58M123: adb devices timed out after 1s", failure.message)
    }

    @Test
    fun `an emulator left up after release is reported by the read only check`() {
        val provider = android.provider(stop = 1.seconds)
        provider.prepare(AVD)
        android.flag("sticky")

        provider.release(AVD)

        assertEquals(
            listOf("the emulator emulator-5554 of avd pixel_api35 that Loop booted is still running after release"),
            provider.readOnlyCheck(AVD),
        )
        assertFalse(android.emulatorsOf("pixel_api35").isEmpty())
    }

    private companion object {
        val PROJECT = ProjectId("p-1")
        val DEVICE = ResourceDeclaration(kind = "android-device", id = "R58M123")
        val AVD = ResourceDeclaration(kind = "avd", id = "pixel_api35")
    }
}
