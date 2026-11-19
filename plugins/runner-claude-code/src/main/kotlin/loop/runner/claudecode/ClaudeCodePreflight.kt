package loop.runner.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * What a runner's startup checks found: [error], the end of a sentence, stops `loop run`;
 * [warnings], also ends of sentences, are printed and the run goes on.
 */
data class PreflightResult(
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * The startup checks of `loop run` with `runner.kind: claude-code`. The runner relies on
 * `--permission-mode bypassPermissions`, which Claude Code refuses as root. A check that
 * cannot be made (unknown uid, unreadable managed settings) does not stop the run, it warns.
 */
class ClaudeCodePreflight(
    private val uid: () -> Long? = ::currentUid,
    private val managedSettings: Path = defaultManagedSettings(),
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
        return PreflightResult(warnings = warnings)
    }

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
