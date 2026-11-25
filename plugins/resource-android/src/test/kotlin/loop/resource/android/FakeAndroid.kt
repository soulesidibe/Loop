package loop.resource.android

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A fake Android machine in [root] (m4-28): fake `adb` and `emulator` scripts in `bin/`, put on the `PATH` of the
 * providers it builds, over plain files. `devices/<serial>` is a connected device and holds its state, `avds/<serial>`
 * the AVD an emulator runs, `boot/<serial>` its `sys.boot_completed`. Every call of either script is appended to
 * `calls.log`. Flag files change what the fakes do: `fail-<adb command>`, or `fail-emu-<console command>`, makes that
 * command exit with the code it holds, `hang-<adb command>` makes it hang for 30 seconds, `boot-never` keeps `sys.boot_completed` at 0, `sticky` makes `emu kill` answer OK and leave the emulator up,
 * `emulator-exit` makes the emulator exit at once with the code it holds.
 */
class FakeAndroid(
    private val root: Path,
) {
    private val bin = root.resolve("bin").createDirectories()
    private val devices = root.resolve("devices").createDirectories()
    private val avds = root.resolve("avds").createDirectories()
    private val boot = root.resolve("boot").createDirectories()
    private val calls = root.resolve("calls.log")

    /** The provider's own state folder, shared by every provider of this machine, as `<LOOP_HOME>/resources/android` is. */
    val stateDir: Path = root.resolve("state")

    private val owners = AtomicInteger()

    init {
        script("adb", ADB)
        script("emulator", EMULATOR)
        calls.writeText("")
    }

    /**
     * A new provider over this machine, as a new process of Loop would be: its own owner, and every other owner seen
     * as dead, so a provider built earlier plays a crashed run.
     */
    fun provider(
        boot: Duration = 5.seconds,
        stop: Duration = 2.seconds,
        command: Duration = 5.seconds,
    ): AndroidResourceProvider =
        AndroidResourceProvider(
            environment = mapOf("PATH" to "$bin:/usr/bin:/bin", "FAKE_ANDROID_MACHINE" to root.toString()),
            stateDir = stateDir,
            timeouts = AndroidTimeouts(command = command, boot = boot, stop = stop, poll = 20.milliseconds),
            owner = "test-owner-${owners.incrementAndGet()}",
            ownerAlive = { false },
            portFree = { true },
        )

    /** Connects a real device of [serial] in [state]. */
    fun connect(
        serial: String,
        state: String = "device",
    ) {
        devices.resolve(serial).writeText("$state\n")
    }

    /** Starts, outside Loop, an emulator of [avd] already booted on [serial]. */
    fun runningEmulator(
        serial: String,
        avd: String,
    ) {
        avds.resolve(serial).writeText("$avd\n")
        boot.resolve(serial).writeText("1\n")
        connect(serial)
    }

    /** Sets the `sys.boot_completed` of the emulator on [serial] to 1, as a boot that completes. */
    fun bootCompleted(serial: String) {
        boot.resolve(serial).writeText("1\n")
    }

    fun connected(serial: String): Boolean = devices.resolve(serial).exists()

    /** The serials of the emulators of [avd] connected now, read from the files, never through a provider. */
    fun emulatorsOf(avd: String): List<String> =
        avds.listDirectoryEntries().filter { connected(it.name) && it.readText().trim() == avd }.map { it.name }

    /** Every call of the fakes so far, one line each, such as `adb -s emulator-5554 emu kill`. */
    fun calls(): List<String> = calls.readLines().filter { it.isNotBlank() }

    fun flag(
        name: String,
        value: String = "",
    ) {
        root.resolve(name).writeText(value)
    }

    fun unflag(name: String) {
        root.resolve(name).deleteIfExists()
    }

    /** Disconnects every device, so the fake emulators still running exit. */
    fun shutdown() {
        unflag("sticky")
        devices.listDirectoryEntries().forEach { it.deleteIfExists() }
    }

    private fun script(
        name: String,
        text: String,
    ) {
        val file = bin.resolve(name)
        file.writeText(text)
        Files.setPosixFilePermissions(
            file,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )
    }

    private companion object {
        val ADB =
            """
            |#!/bin/sh
            |m="${'$'}FAKE_ANDROID_MACHINE"
            |echo "adb ${'$'}*" >> "${'$'}m/calls.log"
            |serial=""
            |if [ "${'$'}1" = "-s" ]; then serial="${'$'}2"; shift 2; fi
            |cmd="${'$'}1"
            |key="${'$'}cmd"
            |if [ "${'$'}cmd" = emu ]; then key="emu-${'$'}2"; fi
            |if [ -f "${'$'}m/fail-${'$'}key" ]; then echo "error: fake ${'$'}key failure" >&2; exit "${'$'}(cat "${'$'}m/fail-${'$'}key")"; fi
            |if [ -f "${'$'}m/hang-${'$'}key" ]; then sleep 30; fi
            |case "${'$'}cmd" in
            |  devices)
            |    echo "List of devices attached"
            |    for f in "${'$'}m"/devices/*; do
            |      [ -e "${'$'}f" ] || continue
            |      echo "${'$'}(basename "${'$'}f")	${'$'}(head -n 1 "${'$'}f")"
            |    done
            |    echo ""
            |    ;;
            |  wait-for-device)
            |    while [ ! -e "${'$'}m/devices/${'$'}serial" ]; do sleep 0.02; done
            |    ;;
            |  shell)
            |    [ -e "${'$'}m/devices/${'$'}serial" ] || { echo "error: device '${'$'}serial' not found" >&2; exit 1; }
            |    cat "${'$'}m/boot/${'$'}serial" 2>/dev/null || echo ""
            |    ;;
            |  emu)
            |    [ -e "${'$'}m/devices/${'$'}serial" ] || { echo "error: device '${'$'}serial' not found" >&2; exit 1; }
            |    case "${'$'}2" in
            |      kill)
            |        echo "OK: killing emulator, bye bye"
            |        [ -e "${'$'}m/sticky" ] || rm -f "${'$'}m/devices/${'$'}serial"
            |        ;;
            |      avd)
            |        head -n 1 "${'$'}m/avds/${'$'}serial"
            |        echo "OK"
            |        ;;
            |    esac
            |    ;;
            |esac
            |
            """.trimMargin()

        val EMULATOR =
            """
            |#!/bin/sh
            |m="${'$'}FAKE_ANDROID_MACHINE"
            |echo "emulator ${'$'}*" >> "${'$'}m/calls.log"
            |avd=""
            |port=5554
            |while [ ${'$'}# -gt 0 ]; do
            |  case "${'$'}1" in
            |    -avd) avd="${'$'}2"; shift ;;
            |    -port) port="${'$'}2"; shift ;;
            |  esac
            |  shift
            |done
            |if [ -f "${'$'}m/emulator-exit" ]; then echo "PANIC: Unknown AVD name [${'$'}avd]" >&2; exit "${'$'}(cat "${'$'}m/emulator-exit")"; fi
            |s="emulator-${'$'}port"
            |sleep 0.1
            |echo "${'$'}avd" > "${'$'}m/avds/${'$'}s"
            |echo "device" > "${'$'}m/devices/${'$'}s"
            |sleep 0.1
            |if [ -f "${'$'}m/boot-never" ]; then echo 0 > "${'$'}m/boot/${'$'}s"; else echo 1 > "${'$'}m/boot/${'$'}s"; fi
            |while [ -e "${'$'}m/devices/${'$'}s" ]; do sleep 0.02; done
            |rm -f "${'$'}m/avds/${'$'}s" "${'$'}m/boot/${'$'}s"
            |
            """.trimMargin()
    }
}
