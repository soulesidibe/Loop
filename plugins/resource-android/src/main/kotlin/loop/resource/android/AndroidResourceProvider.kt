package loop.resource.android

import loop.api.AttemptId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import loop.api.ResourceProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The timeouts of the Android provider (m4-28): [command] for each `adb` call, [boot] from the start of an emulator to
 * `sys.boot_completed` = 1, [stop] for an emulator to leave `adb devices` after `emu kill`, and [poll], the pause
 * between two reads while waiting.
 */
internal data class AndroidTimeouts(
    val command: Duration = 30.seconds,
    val boot: Duration = 300.seconds,
    val stop: Duration = 30.seconds,
    val poll: Duration = 1.seconds,
)

/**
 * The Android resource provider (m4-28, story 125, PLAN 6.9 and 12), serving `android-device` and `avd`. The `id` of
 * an `android-device` entry is the device's serial, the `id` of an `avd` entry the name of the AVD.
 *
 * [prepare] grants a connected device by its serial, checked with `adb devices`. For an AVD it grants an emulator of
 * that AVD already connected, else boots one, `emulator -avd <name> -no-snapshot-save -port <port>` in the background
 * as a child of `loop run`, on the first free even port from 5554, waits for `adb wait-for-device` and
 * `sys.boot_completed`, and grants `emulator-<port>`. The attempt that holds the lock is written on the record of the
 * boot and names its log (bl-208). A prepare run again for the same attempt waits for the emulator the first one booted
 * when it still runs, and stops what is left of it when not, so it is idempotent. [release] stops an emulator it booted with `adb -s <serial> emu
 * kill`, and leaves a real device, or an emulator started outside Loop, as it is. [readOnlyCheck] confirms an emulator
 * it booted is gone. [recover] stops each emulator it booted whose AVD no live lock holds and whose booting process of
 * Loop is dead, and never touches a real device.
 *
 * The emulators it booted are written under [stateDir], `<LOOP_HOME>/resources/android/` by default, so a later
 * process finds what a crashed one left. Every `adb` and `emulator` call has a timeout of [timeouts], and a failure
 * names the command and its exit code. The limit of 2 Android devices and emulators is Loop's, never the provider's.
 */
class AndroidResourceProvider internal constructor(
    environment: Map<String, String>,
    stateDir: Path,
    private val timeouts: AndroidTimeouts,
    /** The process of Loop this provider runs in, written on each emulator it boots. */
    private val owner: String,
    /** Whether the process of Loop named by an owner is still running. */
    private val ownerAlive: (String) -> Boolean,
    /** Whether a local TCP port is free, so an emulator can take it. */
    private val portFree: (Int) -> Boolean,
) : ResourceProvider {
    /** The provider `ServiceLoader` builds: the host's environment, its state under `LOOP_HOME`. */
    constructor() : this(
        environment = System.getenv(),
        stateDir = defaultStateDir(System.getenv()),
        timeouts = AndroidTimeouts(),
        owner = currentOwner(),
        ownerAlive = ::ownerAlive,
        portFree = ::portFree,
    )

    private val commands = AndroidCommands(environment, stateDir.resolve("tmp"))
    private val records = BootRecords(stateDir)
    private val logs = stateDir.resolve("logs")

    /** The emulators this provider started, by AVD, so one that exits before it booted is seen. */
    private val started = ConcurrentHashMap<String, Started>()

    override val kinds: Set<String> = setOf(DEVICE, AVD)

    override fun prepare(
        resource: ResourceDeclaration,
        attemptId: AttemptId,
    ): Map<String, String> =
        named(resource) {
            val serial =
                when (resource.kind) {
                    DEVICE -> prepareDevice(resource.id)
                    else -> prepareAvd(resource.id, attemptId.value)
                }
            mapOf(SERIAL to serial)
        }

    override fun release(resource: ResourceDeclaration) {
        if (resource.kind != AVD) return
        named(resource) { records.of(resource.id)?.let(::stop) }
    }

    override fun readOnlyCheck(resource: ResourceDeclaration): List<String> {
        if (resource.kind != AVD) return emptyList()
        return named(resource) {
            val record = records.of(resource.id) ?: return@named emptyList()
            if (runs(record, devices())) {
                listOf("the emulator ${record.serial} of avd ${record.avd} that Loop booted is still running after release")
            } else {
                emptyList()
            }
        }
    }

    override fun recover(liveHolders: List<ResourceHolder>) {
        val held = liveHolders.filter { it.kind == AVD }.map { it.resourceId }.toSet()
        val orphans = records.all().filter { it.avd !in held && it.owner != owner && !ownerAlive(it.owner) }
        val failures =
            orphans.mapNotNull { record ->
                try {
                    val gone = stop(record)
                    if (gone) null else "avd ${record.avd}: the emulator ${record.serial} is still running after ${killCommand(record)}"
                } catch (e: ResourceException) {
                    "avd ${record.avd}: ${e.message}"
                }
            }
        if (failures.isNotEmpty()) throw ResourceException(failures.joinToString("; "))
    }

    private fun prepareDevice(serial: String): String {
        val devices = devices()
        val state =
            devices[serial]
                ?: throw ResourceException(
                    "the device is not connected, adb devices lists ${devices.keys.joinToString().ifEmpty { "none" }}",
                )
        if (state != READY) throw ResourceException("the device is $state, not ready")
        return serial
    }

    private fun prepareAvd(
        avd: String,
        attempt: String,
    ): String {
        val devices = devices()
        val emulators = devices.filter { (serial, state) -> state == READY && serial.startsWith(EMULATOR_PREFIX) }.keys
        val running = emulators.firstOrNull { avdName(it) == avd }
        if (running != null) {
            waitBooted(running, TimeSource.Monotonic.markNow() + timeouts.boot, emulator = null)
            return running
        }
        val record = records.of(avd)
        // A prepare of the same attempt whose outcome was lost booted it, and it still runs: wait for that boot (bl-208).
        val mine = started[avd]?.takeIf { record != null && record.attempt == attempt && record.owner == owner && it.process.isAlive }
        if (record != null && mine != null) {
            waitOrStop(avd, record.serial, mine)
            return record.serial
        }
        // A record whose emulator no longer runs the AVD is stale: stop what is left of it before booting again.
        record?.let(::stop)
        return boot(avd, attempt)
    }

    /** Boots [avd] for [attempt] on a free port and waits for it; a boot that fails stops the emulator before throwing. */
    private fun boot(
        avd: String,
        attempt: String,
    ): String {
        val (serial, emulator) =
            records.exclusive {
                val port = freePort(devices())
                val serial = "$EMULATOR_PREFIX$port"
                records.write(BootRecord(avd, serial, pid = 0, owner = owner, attempt = attempt))
                val args = emulatorArgs(avd, port)
                val log = logs.resolve("$serial-${BootRecords.safeName(attempt)}.log")
                val process =
                    try {
                        Files.createDirectories(logs)
                        commands.startEmulator(args, log)
                    } catch (e: ResourceException) {
                        records.delete(avd)
                        throw e
                    }
                records.write(BootRecord(avd, serial, pid = process.pid(), owner = owner, attempt = attempt))
                val emulator = Started(process, args, log)
                started[avd] = emulator
                serial to emulator
            }
        waitOrStop(avd, serial, emulator)
        return serial
    }

    /** Waits for the boot of [emulator] on [serial]; one that fails stops the emulator of [avd] before throwing. */
    private fun waitOrStop(
        avd: String,
        serial: String,
        emulator: Started,
    ) {
        try {
            waitBooted(serial, TimeSource.Monotonic.markNow() + timeouts.boot, emulator)
        } catch (e: ResourceException) {
            records.of(avd)?.let { record -> runCatching { stop(record) }.exceptionOrNull()?.let(e::addSuppressed) }
            throw e
        }
    }

    /** An emulator this provider started: its process, its arguments and its log, to name it when it exits early. */
    private data class Started(
        val process: Process,
        val args: List<String>,
        val log: Path,
    )

    /**
     * Waits until [serial] is connected, with `adb wait-for-device` in slices so an [emulator] that exits is seen, then
     * until its `sys.boot_completed` reads 1, before [deadline].
     */
    private fun waitBooted(
        serial: String,
        deadline: TimeSource.Monotonic.ValueTimeMark,
        emulator: Started?,
    ) {
        while (true) {
            emulator?.let(::failIfExited)
            if (deadline.hasPassedNow()) throw notBooted(serial, value = null, error = null)
            val waited = commands.adb(listOf("-s", serial, "wait-for-device"), minOf(-deadline.elapsedNow(), waitSlice))
            if (waited.timedOut) continue
            if (waited.exitCode != 0) {
                throw ResourceException(
                    AndroidCommands.failed(
                        AndroidCommands.display("adb", listOf("-s", serial, "wait-for-device")),
                        waited.exitCode,
                        waited.output,
                    ),
                )
            }
            break
        }
        var value: String? = null
        var error: String? = null
        val getprop = listOf("-s", serial, "shell", "getprop", BOOT_COMPLETED)
        while (true) {
            emulator?.let(::failIfExited)
            val read = commands.adb(getprop, timeouts.command)
            when {
                read.timedOut -> error = "${AndroidCommands.display("adb", getprop)} timed out after ${timeouts.command}"
                read.exitCode != 0 -> error = AndroidCommands.failed(AndroidCommands.display("adb", getprop), read.exitCode, read.output)
                else -> {
                    value = read.output.trim()
                    error = null
                    if (value == "1") return
                }
            }
            if (deadline.hasPassedNow()) throw notBooted(serial, value, error)
            Thread.sleep(timeouts.poll.inWholeMilliseconds)
        }
    }

    private fun notBooted(
        serial: String,
        value: String?,
        error: String?,
    ): ResourceException {
        val read =
            when {
                value != null -> "the last $BOOT_COMPLETED read was \"$value\""
                error != null -> "no $BOOT_COMPLETED was read, the last read failed: $error"
                else -> "no $BOOT_COMPLETED was read"
            }
        return ResourceException("$serial did not boot in ${timeouts.boot}, $read")
    }

    private fun failIfExited(emulator: Started) {
        if (emulator.process.isAlive) return
        val log = if (emulator.log.exists()) emulator.log.readText() else ""
        throw ResourceException(
            AndroidCommands.failed(AndroidCommands.display("emulator", emulator.args), emulator.process.exitValue(), log),
        )
    }

    /**
     * Stops the emulator of [record] when it still runs its AVD, with `adb -s <serial> emu kill`, waits for it to leave
     * `adb devices`, and kills its process when it does not. True when it is gone, and its record with it; false when
     * it still runs, the record kept for the read only check and [recover]. A failing `emu kill` throws once the
     * process is killed.
     */
    private fun stop(record: BootRecord): Boolean {
        if (!runs(record, devices())) {
            started.remove(record.avd)?.let { kill(it.process) }
            records.delete(record.avd)
            return true
        }
        val kill = commands.adb(listOf("-s", record.serial, "emu", "kill"), timeouts.command)
        val failure =
            when {
                kill.timedOut -> "${killCommand(record)} timed out after ${timeouts.command}"
                kill.exitCode != 0 -> AndroidCommands.failed(killCommand(record), kill.exitCode, kill.output)
                else -> null
            }
        var gone = failure == null && awaitGone(record.serial)
        if (!gone) {
            val process =
                started[record.avd]?.process?.toHandle() ?: record.pid.takeIf { it > 0 }?.let { ProcessHandle.of(it).orElse(null) }
            process?.let(::kill)
            gone = awaitGone(record.serial)
        }
        if (gone) {
            started.remove(record.avd)
            records.delete(record.avd)
        }
        failure?.let { throw ResourceException(it) }
        return gone
    }

    /** Whether the serial of [record] is connected and, when it answers, still runs the record's AVD. */
    private fun runs(
        record: BootRecord,
        devices: Map<String, String>,
    ): Boolean {
        val state = devices[record.serial] ?: return false
        return state != READY || avdName(record.serial) == record.avd
    }

    private fun awaitGone(serial: String): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + timeouts.stop
        while (true) {
            if (serial !in devices()) return true
            if (deadline.hasPassedNow()) return false
            Thread.sleep(timeouts.poll.inWholeMilliseconds)
        }
    }

    private fun kill(process: Process) = kill(process.toHandle())

    private fun kill(handle: ProcessHandle) {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
    }

    /** The connected serials and their states, read from `adb devices`. */
    private fun devices(): Map<String, String> =
        commands
            .adbChecked(listOf("devices"), timeouts.command)
            .lines()
            .dropWhile { !it.startsWith("List of devices") }
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") }
            .associate { line -> line.split(Regex("\\s+"), limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() } }

    /** The AVD the emulator of [serial] runs, read with `adb -s <serial> emu avd name`. */
    private fun avdName(serial: String): String =
        commands
            .adbChecked(listOf("-s", serial, "emu", "avd", "name"), timeouts.command)
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    /** The first even port from 5554 to 5584 that no connected emulator, no record and no other process uses, with its next port. */
    private fun freePort(devices: Map<String, String>): Int {
        val serials = devices.keys + records.all().map { it.serial }
        val taken =
            serials
                .mapNotNull {
                    EMULATOR_SERIAL
                        .matchEntire(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                }.toSet()
        return (FIRST_PORT..LAST_PORT step 2).firstOrNull { it !in taken && portFree(it) && portFree(it + 1) }
            ?: throw ResourceException("no emulator port is free from $FIRST_PORT to $LAST_PORT")
    }

    private val waitSlice: Duration get() = timeouts.poll * WAIT_SLICE_POLLS

    /** Runs [block], the failures it throws prefixed with the kind and id of [resource]. */
    private fun <T> named(
        resource: ResourceDeclaration,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: ResourceException) {
            throw ResourceException("${resource.kind} ${resource.id}: ${e.message}", e)
        }

    private companion object {
        const val DEVICE = "android-device"
        const val AVD = "avd"
        const val SERIAL = "serial"
        const val READY = "device"
        const val EMULATOR_PREFIX = "emulator-"
        const val BOOT_COMPLETED = "sys.boot_completed"
        const val FIRST_PORT = 5554
        const val LAST_PORT = 5584
        const val WAIT_SLICE_POLLS = 5
        val EMULATOR_SERIAL = Regex("emulator-(\\d+)")

        fun emulatorArgs(
            avd: String,
            port: Int,
        ): List<String> = listOf("-avd", avd, "-no-snapshot-save", "-port", port.toString())

        fun killCommand(record: BootRecord): String = AndroidCommands.display("adb", listOf("-s", record.serial, "emu", "kill"))

        /** `<LOOP_HOME>/resources/android`, `LOOP_HOME` defaulting to `~/.loop` as the host reads it. */
        fun defaultStateDir(environment: Map<String, String>): Path {
            val loopHome =
                environment["LOOP_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    ?: Path.of(environment["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), ".loop")
            return loopHome
                .resolve("resources")
                .resolve("android")
                .toAbsolutePath()
                .normalize()
        }

        /** This process, as `<pid>@<start in epoch milliseconds>`, so a reused pid is never taken for it. */
        fun currentOwner(): String {
            val me = ProcessHandle.current()
            return "${me.pid()}@${startOf(me)}"
        }

        fun ownerAlive(owner: String): Boolean {
            val pid = owner.substringBefore('@').toLongOrNull() ?: return false
            val start = owner.substringAfter('@', "").toLongOrNull() ?: 0
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            return handle.isAlive && (start == 0L || startOf(handle) == start)
        }

        fun startOf(handle: ProcessHandle): Long =
            handle
                .info()
                .startInstant()
                .map { it.toEpochMilli() }
                .orElse(0)

        fun portFree(port: Int): Boolean =
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
                true
            } catch (e: IOException) {
                false
            }
    }
}
