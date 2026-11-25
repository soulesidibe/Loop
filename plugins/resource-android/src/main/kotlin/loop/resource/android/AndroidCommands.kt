package loop.resource.android

import loop.api.ResourceException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.time.Duration

/** What one `adb` call did: it exited with [exitCode] and wrote [output], stdout and stderr merged, or it [timedOut]. */
internal data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

/**
 * Runs `adb` and starts `emulator` (m4-28) with exactly [environment], each found on its `PATH`, then under
 * `ANDROID_HOME` and `ANDROID_SDK_ROOT` (`platform-tools/adb`, `emulator/emulator`). Every call has a timeout. Output
 * goes to a temporary file of [scratch], never a pipe, so a background process an `adb` call starts, such as its
 * server, never holds the call open. A failure names the command as written, `adb -s <serial> emu kill`, and its exit
 * code, never the full path.
 */
internal class AndroidCommands(
    private val environment: Map<String, String>,
    private val scratch: Path,
) {
    /** Runs `adb` with [args], waiting at most [timeout]. A command that cannot start throws [ResourceException]. */
    fun adb(
        args: List<String>,
        timeout: Duration,
    ): CommandResult {
        val program = find("adb", "platform-tools")
        Files.createDirectories(scratch)
        val out = Files.createTempFile(scratch, "adb-", ".out")
        try {
            val process = start(listOf(program.toString()) + args, out.toFile(), "adb", args)
            val ended =
                try {
                    process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Loop interrupts a call past its own timeout (bl-208): the adb child goes, the interrupt stays set.
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    Thread.currentThread().interrupt()
                    throw ResourceException("${display("adb", args)} was interrupted", e)
                }
            if (!ended) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return CommandResult(-1, out.readText(), timedOut = true)
            }
            return CommandResult(process.exitValue(), out.readText())
        } finally {
            out.deleteIfExists()
        }
    }

    /** Runs `adb` with [args] and returns its output, or throws naming the command, its exit code or its timeout. */
    fun adbChecked(
        args: List<String>,
        timeout: Duration,
    ): String {
        val result = adb(args, timeout)
        if (result.timedOut) throw ResourceException("${display("adb", args)} timed out after $timeout")
        if (result.exitCode != 0) throw ResourceException(failed(display("adb", args), result.exitCode, result.output))
        return result.output
    }

    /** Starts `emulator` with [args] in the background, its output in [log], and returns the process. */
    fun startEmulator(
        args: List<String>,
        log: Path,
    ): Process = start(listOf(find("emulator", "emulator").toString()) + args, log.toFile(), "emulator", args)

    private fun start(
        argv: List<String>,
        out: File,
        name: String,
        args: List<String>,
    ): Process {
        val builder =
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        return try {
            builder.start()
        } catch (e: IOException) {
            throw ResourceException("${display(name, args)} could not start: ${e.message}", e)
        }
    }

    /** [name] on the `PATH` of [environment], else in [sdkFolder] of `ANDROID_HOME` or `ANDROID_SDK_ROOT`. */
    private fun find(
        name: String,
        sdkFolder: String,
    ): Path {
        val onPath =
            environment["PATH"]
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { Path.of(it, name) }
        val inSdk =
            listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull {
                    environment[it]?.takeIf(String::isNotBlank)
                }.map { Path.of(it, sdkFolder, name) }
        return (onPath + inSdk).firstOrNull { it.isExecutable() }
            ?: throw ResourceException("$name is not on the PATH, nor under ANDROID_HOME or ANDROID_SDK_ROOT in $sdkFolder/")
    }

    companion object {
        /** The longest part of a command's output a failure quotes. */
        private const val QUOTED_CHARS = 300

        fun display(
            name: String,
            args: List<String>,
        ): String = (listOf(name) + args).joinToString(" ")

        /** `<command> exited with code <n>`, then the last line the command wrote, when it wrote one. */
        fun failed(
            command: String,
            exitCode: Int,
            output: String,
        ): String {
            val last =
                output
                    .lines()
                    .lastOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(QUOTED_CHARS)
            return "$command exited with code $exitCode" + (last?.let { ": $it" } ?: "")
        }
    }
}
