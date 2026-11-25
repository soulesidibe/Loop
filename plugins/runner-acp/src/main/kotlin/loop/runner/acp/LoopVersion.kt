package loop.runner.acp

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Loop's version, sent as `clientInfo.version` in ACP `initialize`. Read from the `build-info.properties`
 * file named by the `loop.buildInfo` system property, falling back to [UNKNOWN] outside the launcher.
 */
internal object LoopVersion {
    const val UNKNOWN: String = "unknown"

    /** The version of the running build. */
    fun current(): String = of(System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) })

    /** The `version` of [file]; [UNKNOWN] when missing, unreadable or malformed. */
    fun of(file: Path?): String {
        if (file == null || !Files.isRegularFile(file)) return UNKNOWN
        val properties =
            try {
                Properties().apply { Files.newBufferedReader(file, Charsets.UTF_8).use { load(it) } }
            } catch (e: Exception) {
                return UNKNOWN
            }
        return properties.getProperty(KEY)?.takeIf { VALID.matches(it) } ?: UNKNOWN
    }

    private const val PROPERTY = "loop.buildInfo"
    private const val KEY = "version"

    private val VALID = Regex("[A-Za-z0-9._-]{1,64}")
}
