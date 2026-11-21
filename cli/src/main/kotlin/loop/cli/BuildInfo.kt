package loop.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path

/**
 * The build's version (m1-12), from the `build-info.properties` file Gradle writes at the root of
 * the distribution and the start script names in the `loop.buildInfo` property. The value is a
 * release tag such as `0.1.0`, a label such as `0.1.0-5-g<hex>` after it, or a 12 hex commit without
 * a tag, each with an optional `-dirty`, or `unknown` (bl-159); it is read as an opaque label, never
 * compared as a semver.
 */
internal object BuildInfo {
    const val UNKNOWN: String = "unknown"

    /** The version of the running build: [UNKNOWN] without the property, as in tests run outside the launcher. */
    fun current(): String = versionOf(System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path(it) })

    /** The `version` of [file]; [UNKNOWN] when there is no file, it cannot be read, or the value is malformed. */
    fun versionOf(file: Path?): String {
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

    /** A single printable token, so the value never breaks a trace line or the log. */
    private val VALID = Regex("[A-Za-z0-9._-]{1,64}")
}
