package loop.resource.android

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import loop.api.ResourceProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** m4-28: the Android provider against fake `adb` and `emulator` scripts on its `PATH`, checked on the arguments they recorded. */
class AndroidResourceProviderTest {
    @TempDir
    lateinit var root: Path

    private val android by lazy { FakeAndroid(root) }

    /** Processes a test starts to stand for an emulator, destroyed after it. */
    private val spawned = mutableListOf<Process>()

    @AfterEach
    fun stopFakeEmulators() {
        android.shutdown()
        spawned.forEach { it.destroyForcibly() }
    }

    /** A process that runs until the test ends, standing for an emulator Loop started. */
    private fun liveProcess(): Process = ProcessBuilder("sleep", "60").start().also { spawned += it }

    /** The pid of a process that already exited. */
    private fun deadPid(): Long = ProcessBuilder("true").start().also { it.waitFor(5, TimeUnit.SECONDS) }.pid()

    private fun startOf(process: Process): Long =
        process
            .toHandle()
            .info()
            .startInstant()
            .get()
            .toEpochMilli()

    /** Writes the record a crashed run of Loop left for [AVD] on emulator-5554, its emulator the process [pid] started at [started]. */
    private fun crashedRecord(
        pid: Long,
        started: Long,
    ) = BootRecords(android.stateDir).write(
        BootRecord("pixel_api35", "emulator-5554", pid = pid, owner = "dead-run", attempt = "attempt-0", started = started),
    )

    @Test
    fun `the provider is registered for ServiceLoader and serves android-device and avd`() {
        val found = ServiceLoader.load(ResourceProvider::class.java).filterIsInstance<AndroidResourceProvider>()

        assertEquals(1, found.size)
        assertEquals(setOf("android-device", "avd"), found.single().kinds)
    }

    @Test
    fun `a connected device is granted by its serial and no emulator starts`() {
        android.connect("R58M123")

        val prepared = android.provider().prepare(DEVICE, ATTEMPT)

        assertEquals(mapOf("serial" to "R58M123"), prepared)
        assertEquals(listOf("adb devices"), android.calls())
    }

    @Test
    fun `a device that is not connected, or connected but not ready, is refused naming its serial`() {
        val absent = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE, ATTEMPT) }
        android.connect("R58M123", state = "unauthorized")
        val unauthorized = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE, ATTEMPT) }

        assertEquals("android-device R58M123: the device is not connected, adb devices lists none", absent.message)
        assertEquals("android-device R58M123: the device is unauthorized, not ready", unauthorized.message)
        assertTrue(android.calls().none { it.startsWith("emulator") }, "${android.calls()}")
    }

    @Test
    fun `releasing a device leaves it connected and its read only check passes`() {
        android.connect("R58M123")
        val provider = android.provider()
        provider.prepare(DEVICE, ATTEMPT)

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

        val prepared = provider.prepare(AVD, ATTEMPT)

        assertEquals(mapOf("serial" to "emulator-5556"), prepared)
        assertEquals(listOf("emulator-5556"), android.emulatorsOf("pixel_api35"))
        val calls = android.calls()
        assertTrue("emulator -avd pixel_api35 -no-snapshot-save -port 5556" in calls, "$calls")
        assertTrue("adb -s emulator-5556 wait-for-device" in calls, "$calls")
        assertTrue("adb -s emulator-5556 shell getprop sys.boot_completed" in calls, "$calls")
        val waited = calls.indexOf("adb -s emulator-5556 wait-for-device")
        assertTrue(waited < calls.indexOf("adb -s emulator-5556 shell getprop sys.boot_completed"), "$calls")

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

        val prepared = provider.prepare(AVD, ATTEMPT)
        provider.release(AVD)

        assertEquals(mapOf("serial" to "emulator-5560"), prepared)
        assertTrue(android.connected("emulator-5560"))
        assertTrue(android.calls().none { it.startsWith("emulator") || "emu kill" in it }, "${android.calls()}")
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
    }

    @Test
    fun `recover stops an emulator Loop booted for an attempt with no live lock and leaves a real device alone`() {
        android.connect("R58M123")
        android.provider().prepare(AVD, ATTEMPT)
        android.provider().prepare(DEVICE, ATTEMPT)

        android.provider().recover(listOf(ResourceHolder(PROJECT, "android-device", "R58M123", "attempt-2")))

        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertTrue("adb -s emulator-5554 emu kill" in android.calls(), "${android.calls()}")
        assertTrue(android.connected("R58M123"), "recover disconnected the real device")
        assertTrue(android.calls().none { "-s R58M123" in it }, "${android.calls()}")
    }

    @Test
    fun `recover leaves an emulator whose avd a live lock holds, and one whose booting run is alive`() {
        android.provider().prepare(AVD, ATTEMPT)

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
        // Long enough for the fake emulator to connect and write its 0 on a loaded build machine.
        val provider = android.provider(boot = 10.seconds)

        val failure = assertFailsWith<ResourceException> { provider.prepare(AVD, ATTEMPT) }

        assertEquals(
            "avd pixel_api35: emulator-5554 did not boot in 10s, the last sys.boot_completed read was \"0\"",
            failure.message,
        )
        assertTrue("adb -s emulator-5554 emu kill" in android.calls(), "${android.calls()}")
        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
    }

    @Test
    fun `a failing adb call fails with the command and its exit code`() {
        android.flag("fail-devices", "7")

        val failure = assertFailsWith<ResourceException> { android.provider().prepare(DEVICE, ATTEMPT) }

        assertEquals("android-device R58M123: adb devices exited with code 7: error: fake devices failure", failure.message)
    }

    @Test
    fun `a failing emu kill fails release with the command and its exit code`() {
        val provider = android.provider(stop = 1.seconds)
        provider.prepare(AVD, ATTEMPT)
        android.flag("fail-emu-kill", "4")

        val failure = assertFailsWith<ResourceException> { provider.release(AVD) }

        assertEquals("avd pixel_api35: adb -s emulator-5554 emu kill exited with code 4: error: fake emu-kill failure", failure.message)
    }

    @Test
    fun `an emulator that exits before booting fails with its command and exit code`() {
        android.flag("emulator-exit", "1")

        val failure = assertFailsWith<ResourceException> { android.provider().prepare(AVD, ATTEMPT) }

        assertEquals(
            "avd pixel_api35: emulator -avd pixel_api35 -no-snapshot-save -port 5554 exited with code 1: " +
                "PANIC: Unknown AVD name [pixel_api35]",
            failure.message,
        )
    }

    @Test
    fun `an adb call that hangs fails after the command timeout naming the command`() {
        android.flag("hang-devices")

        val failure = assertFailsWith<ResourceException> { android.provider(command = 1.seconds).prepare(DEVICE, ATTEMPT) }

        assertEquals("android-device R58M123: adb devices timed out after 1s", failure.message)
    }

    @Test
    fun `an emulator left up after release is reported by the read only check`() {
        val provider = android.provider(stop = 1.seconds)
        provider.prepare(AVD, ATTEMPT)
        android.flag("sticky")

        provider.release(AVD)

        assertEquals(
            listOf("the emulator emulator-5554 of avd pixel_api35 that Loop booted is still running after release"),
            provider.readOnlyCheck(AVD),
        )
        assertFalse(android.emulatorsOf("pixel_api35").isEmpty())
    }

    @Test
    fun `bl-208 the attempt is written on the record of the boot and names the emulator's log`() {
        android.provider().prepare(AVD, ATTEMPT)

        val record = android.stateDir.resolve("booted/pixel_api35.properties").readText()
        assertTrue("attempt=attempt-1" in record, record)
        assertTrue(android.stateDir.resolve("logs/emulator-5554-attempt-1.log").exists(), "the log is named by the attempt")
    }

    @Test
    fun `bl-208 a prepare run again for the same attempt after an interrupted boot waits for that emulator, never boots another`() {
        android.flag("boot-never")
        val provider = android.provider(boot = 10.seconds)
        val first = Thread { runCatching { provider.prepare(AVD, ATTEMPT) } }
        first.start()
        val deadline = System.currentTimeMillis() + 5_000
        while ((android.calls().none { "getprop" in it } || android.bootValue("emulator-5554") != "0") &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        first.interrupt()
        first.join(5_000)
        assertFalse(first.isAlive, "the interrupted prepare did not stop")
        assertEquals(listOf("emulator-5554"), android.emulatorsOf("pixel_api35"), "the emulator of the lost prepare still runs")
        android.connect("emulator-5554", state = "offline")
        android.bootCompleted("emulator-5554")

        val again = provider.prepare(AVD, ATTEMPT)
        provider.release(AVD)

        assertEquals(mapOf("serial" to "emulator-5554"), again)
        assertEquals(1, android.calls().count { it.startsWith("emulator -avd") }, "${android.calls()}")
        assertEquals(emptyList(), android.emulatorsOf("pixel_api35"))
        assertEquals(emptyList(), provider.readOnlyCheck(AVD))
    }

    @Test
    fun `bl-219 a failed boot stops only the emulator it started, never the one recorded for another holder of the avd`() {
        android.flag("boot-never")
        val provider = android.provider(boot = 5.seconds)
        var failure: Throwable? = null
        val first = Thread { failure = runCatching { provider.prepare(AVD, ATTEMPT) }.exceptionOrNull() }
        first.start()
        val deadline = System.currentTimeMillis() + 5_000
        while ((android.calls().none { "getprop" in it } || android.bootValue("emulator-5554") != "0") &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        // Another holder of the avd recorded its own emulator while the boot of attempt-1 waited.
        val other = liveProcess()
        android.runningEmulator("emulator-5556", "pixel_api35")
        BootRecords(android.stateDir).write(
            BootRecord(
                "pixel_api35",
                "emulator-5556",
                pid = other.pid(),
                owner = "other-run",
                attempt = "attempt-2",
                started = startOf(other),
            ),
        )

        first.join(20_000)

        assertFalse(first.isAlive, "the failed boot did not end")
        assertTrue(failure is ResourceException, "$failure")
        assertTrue("adb -s emulator-5554 emu kill" in android.calls(), "${android.calls()}")
        assertTrue(other.isAlive, "the failed boot killed the emulator of the other holder")
        assertTrue(android.calls().none { "-s emulator-5556 emu kill" in it }, "${android.calls()}")
        assertEquals(listOf("emulator-5556"), android.emulatorsOf("pixel_api35"))
        val record = android.stateDir.resolve("booted/pixel_api35.properties").readText()
        assertTrue("attempt=attempt-2" in record, record)
    }

    @Test
    fun `recover never stops an emulator on the serial of a record whose emulator is dead, such as one started by hand on that port`() {
        crashedRecord(pid = deadPid(), started = 1)
        android.runningEmulator("emulator-5554", "pixel_api35")

        android.provider().recover(emptyList())

        assertTrue(android.connected("emulator-5554"), "recover stopped an emulator Loop did not start")
        assertTrue(android.calls().none { "emu kill" in it }, "${android.calls()}")
        assertFalse(android.stateDir.resolve("booted/pixel_api35.properties").exists(), "the stale record is kept")
    }

    @Test
    fun `prepare drops a record whose emulator is dead before granting one already running, so release leaves that one running`() {
        crashedRecord(pid = deadPid(), started = 1)
        android.runningEmulator("emulator-5554", "pixel_api35")
        val provider = android.provider()

        val prepared = provider.prepare(AVD, ATTEMPT)
        provider.release(AVD)

        assertEquals(mapOf("serial" to "emulator-5554"), prepared)
        assertTrue(android.connected("emulator-5554"), "release stopped an emulator Loop did not start")
        assertTrue(android.calls().none { "emu kill" in it || it.startsWith("emulator") }, "${android.calls()}")
    }

    @Test
    fun `a recorded pid now taken by another process, its start time different, is never killed`() {
        val other = liveProcess()
        crashedRecord(pid = other.pid(), started = startOf(other) - 1)
        android.runningEmulator("emulator-5554", "pixel_api35")

        android.provider().recover(emptyList())

        assertTrue(other.isAlive, "recover killed a process that is not the emulator Loop started")
        assertTrue(android.connected("emulator-5554"))
        assertTrue(android.calls().none { "emu kill" in it }, "${android.calls()}")
    }

    @Test
    fun `recover kills the live emulator of a crashed run that is not yet in adb devices`() {
        val emulator = liveProcess()
        crashedRecord(pid = emulator.pid(), started = startOf(emulator))

        android.provider().recover(emptyList())

        assertTrue(emulator.waitFor(5, TimeUnit.SECONDS), "the emulator of the crashed run still runs")
        assertTrue(android.calls().none { "emu kill" in it }, "${android.calls()}")
        assertFalse(android.stateDir.resolve("booted/pixel_api35.properties").exists(), "the record is kept")
    }

    @Test
    fun `a clean release deletes the emulator's log, and recover deletes logs older than 7 days`() {
        val provider = android.provider()
        provider.prepare(AVD, ATTEMPT)
        val log = android.stateDir.resolve("logs/emulator-5554-attempt-1.log")
        assertTrue(log.exists())

        provider.release(AVD)
        val logs = android.stateDir.resolve("logs").createDirectories()
        val old = logs.resolve("emulator-5556-attempt-0.log").also { it.writeText("old") }
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusMillis(8.days.inWholeMilliseconds)))
        val recent = logs.resolve("emulator-5558-attempt-2.log").also { it.writeText("recent") }
        android.provider().recover(emptyList())

        assertFalse(log.exists(), "the log of a clean release is kept")
        assertFalse(old.exists(), "a log older than 7 days is kept")
        assertTrue(recent.exists(), "a recent log was deleted")
    }

    @Test
    fun `with no adb on the PATH a prepare fails naming adb, and with adb but no emulator a boot fails naming emulator`() {
        val empty = root.resolve("empty").createDirectories()
        val onlyAdb = root.resolve("only-adb").createDirectories()
        Files.createSymbolicLink(onlyAdb.resolve("adb"), root.resolve("bin/adb"))

        fun provider(path: Path) =
            AndroidResourceProvider(
                environment = mapOf("PATH" to path.toString(), "FAKE_ANDROID_MACHINE" to root.toString()),
                stateDir = android.stateDir,
                timeouts = AndroidTimeouts(command = 5.seconds),
                owner = "no-tools",
                ownerAlive = { false },
                portFree = { true },
            )
        val noAdb = assertFailsWith<ResourceException> { provider(empty).prepare(DEVICE, ATTEMPT) }
        val noEmulator = assertFailsWith<ResourceException> { provider(onlyAdb).prepare(AVD, ATTEMPT) }

        assertEquals(
            "android-device R58M123: adb is not on the PATH, nor under ANDROID_HOME or ANDROID_SDK_ROOT in platform-tools/",
            noAdb.message,
        )
        assertEquals(
            "avd pixel_api35: emulator is not on the PATH, nor under ANDROID_HOME or ANDROID_SDK_ROOT in emulator/",
            noEmulator.message,
        )
        assertFalse(android.stateDir.resolve("booted/pixel_api35.properties").exists(), "a boot that never started left its record")
    }

    private companion object {
        val PROJECT = ProjectId("p-1")
        val DEVICE = ResourceDeclaration(kind = "android-device", id = "R58M123")
        val AVD = ResourceDeclaration(kind = "avd", id = "pixel_api35")
        val ATTEMPT = AttemptId("attempt-1")
    }
}
