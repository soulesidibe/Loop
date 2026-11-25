package loop.runner.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * What a runner's startup checks found: [error], the end of a sentence, stops `loop run`;
 * [warnings], also ends of sentences, are printed and the run goes on. [fields] are facts the
 * checks read, such as the runner's version, which `loop run` adds to its `run` `started` log line.
 */
data class PreflightResult(
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val fields: Map<String, String> = emptyMap(),
)

/** What `<command> --version` gave (bl-210): its exit code and output, or why it could not run. */
sealed interface VersionProbe {
    /** The command ran: its exit code and its stdout and stderr together. */
    data class Ran(
        val exitCode: Int,
        val output: String,
    ) : VersionProbe

    /** The command could not start, or did not end in time. */
    data class NotRun(
        val reason: String,
    ) : VersionProbe
}

/**
 * The startup checks of `loop run` with `runner.kind: claude-code`. The runner relies on
 * `--permission-mode bypassPermissions`, which Claude Code refuses as root. A check that
 * cannot be made (unknown uid, unreadable managed settings) does not stop the run, it warns.
 *
 * With a [command], `<command> --version` must name a version of the major of [pinnedVersion]
 * (bl-210), as the codex and agy checks do, since another major may change the stream JSON format
 * the runner reads. Another major stops the run, an output with no readable version warns. The
 * version read goes to [PreflightResult.fields] as `runner_version`. Without a command, as in a
 * check of the uid and settings alone, no version is checked.
 */
class ClaudeCodePreflight(
    private val command: String? = null,
    private val uid: () -> Long? = ::currentUid,
    private val managedSettings: Path = defaultManagedSettings(),
    private val hostEnv: Map<String, String> = System.getenv(),
    private val pinnedVersion: String = ClaudeCodeSettings.PINNED_VERSION,
    private val versionProbe: (List<String>, Map<String, String>) -> VersionProbe = ::runVersion,
) {
    fun check(): PreflightResult {
        val warnings = mutableListOf<String>()
        when (uid()) {
            ROOT -> return PreflightResult(error = "the claude-code runner cannot run as root, run loop as a regular user")
            null -> warnings += "could not read the user id with id -u, so the claude-code runner did not check for root"
        }
        val settings =
            try {
                readManagedSettings()
            } catch (e: IOException) {
                warnings += unreadable(e)
                null
            } catch (e: IllegalArgumentException) {
                warnings += unreadable(e)
                null
            }
        val mode = ((settings?.get("permissions") as? JsonObject)?.get("disableBypassPermissionsMode") as? JsonPrimitive)
        if (mode?.isString == true && mode.content == DISABLE) {
            val error =
                "the claude-code runner needs bypassPermissions mode, but $managedSettings sets " +
                    "permissions.disableBypassPermissionsMode to \"disable\". Remove that setting or use runner.kind: command"
            return PreflightResult(error = error, warnings = warnings)
        }
        val version = checkVersion() ?: return PreflightResult(warnings = warnings)
        return version.copy(warnings = warnings + version.warnings)
    }

    /**
     * Runs `<command> --version` and compares the major it names with the pinned one. Null when
     * there is no command to check; otherwise the version's error, warning or recorded field.
     */
    private fun checkVersion(): PreflightResult? {
        val command = command ?: return null
        val pinnedMajor = pinnedVersion.substringBefore('.')
        val unchecked =
            "so the claude-code runner did not check that Claude Code is of the pinned major version $pinnedMajor (pinned $pinnedVersion)"
        val output =
            when (val probe = versionProbe(listOf(command, VERSION_FLAG), hostEnv)) {
                is VersionProbe.NotRun -> return warning("could not run $command $VERSION_FLAG, $unchecked: ${probe.reason}")
                is VersionProbe.Ran ->
                    probe.output.takeIf { probe.exitCode == 0 }
                        ?: return warning("$command $VERSION_FLAG exited ${probe.exitCode}, $unchecked")
            }
        // The pattern bounds what the messages quote of the output.
        val found =
            SEMVER.find(output)?.value
                ?: return warning("could not read a version in the output of $command $VERSION_FLAG, $unchecked")
        val fields = mapOf(RUNNER_VERSION to found)
        if (found.substringBefore('.') != pinnedMajor) {
            return PreflightResult(
                error = "Claude Code $found is not of the pinned major version $pinnedMajor (pinned $pinnedVersion)",
                fields = fields,
            )
        }
        return PreflightResult(fields = fields)
    }

    private fun warning(message: String): PreflightResult = PreflightResult(warnings = listOf(message))

    /** The managed settings, or null when the file is missing or not a JSON object. */
    private fun readManagedSettings(): JsonObject? {
        if (!managedSettings.isRegularFile()) return null
        val settings = Json.parseToJsonElement(managedSettings.readText())
        return settings as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
    }

    private fun unreadable(e: Exception): String =
        "could not read the Claude Code managed settings $managedSettings, so the claude-code runner " +
            "did not check that bypassPermissions mode is allowed: ${e.message?.lineSequence()?.firstOrNull()?.trimEnd('.')}"

    companion object {
        private const val ROOT = 0L
        private const val DISABLE = "disable"
        private const val VERSION_FLAG = "--version"
        private const val RUNNER_VERSION = "runner_version"
        private const val VERSION_TIMEOUT_SECONDS = 10L

        /** At most this much of the `--version` output is read. */
        private const val VERSION_OUTPUT_LIMIT = 64 * 1024

        /** A version such as `2.1.330`, with at most 9 digits a part, so a quote of it stays short. */
        private val SEMVER = Regex("""(?<![\d.])\d{1,9}\.\d{1,9}\.\d{1,9}(?![\d])""")

        /**
         * Runs [argv] with exactly [env], its stdin closed, and gives its exit code and its stdout and
         * stderr together, or why it did not run: it could not start or did not end within 10 seconds,
         * in which case it is killed.
         */
        fun runVersion(
            argv: List<String>,
            env: Map<String, String>,
        ): VersionProbe {
            val process =
                try {
                    ProcessBuilder(argv)
                        .redirectErrorStream(true)
                        .also {
                            it.environment().clear()
                            it.environment().putAll(env)
                        }.start()
                } catch (e: IOException) {
                    val reason = e.message?.let { it.lineSequence().first().trimEnd('.') }
                    return VersionProbe.NotRun(reason ?: e.javaClass.simpleName)
                }
            try {
                process.outputStream.close()
                var output = ""
                val reader =
                    Thread {
                        output =
                            runCatching {
                                process.inputStream.use { it.readNBytes(VERSION_OUTPUT_LIMIT) }.toString(Charsets.UTF_8)
                            }.getOrDefault("")
                    }.apply {
                        isDaemon = true
                        start()
                    }
                if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    return VersionProbe.NotRun("it did not end within $VERSION_TIMEOUT_SECONDS seconds")
                }
                reader.join(1_000)
                return VersionProbe.Ran(process.exitValue(), output)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return VersionProbe.NotRun("interrupted")
            } finally {
                process.destroyForcibly()
            }
        }

        /** The uid of this process from `id -u`, or null when it cannot be read. */
        fun currentUid(): Long? =
            try {
                val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
                process.outputStream.close()
                val out =
                    process.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .trim()
                if (process.waitFor() == 0) out.toLongOrNull() else null
            } catch (e: IOException) {
                null
            }

        /** Claude Code's managed settings file on this OS. */
        fun defaultManagedSettings(): Path =
            if (System.getProperty("os.name").startsWith("Mac")) {
                Path("/Library/Application Support/ClaudeCode/managed-settings.json")
            } else {
                Path("/etc/claude-code/managed-settings.json")
            }
    }
}
