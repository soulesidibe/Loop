package loop.runner.codex

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Loop's version, sent as `clientInfo.version` (m3-18). Read from the same `build-info.properties` file the cli reads
 * for `loop --version`, the one the start script names in the `loop.buildInfo` property: a release tag such as `0.1.0`,
 * a label such as `0.1.0-5-g<hex>` after it, or a 12 hex commit without a tag, each with an optional `-dirty` (bl-159).
 * [UNKNOWN] without the property, as in tests run outside the launcher, or when the file cannot be read or holds no
 * valid value. The plugin API carries no version, so the plugin reads the file itself.
 */
internal object LoopVersion {
    const val UNKNOWN: String = "unknown"

    /** The version of the running build. */
    fun current(): String = of(System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) })

    /** The `version` of [file]; [UNKNOWN] when there is no file, it cannot be read, or the value is malformed. */
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

    /** A single printable token, the cli's rule. */
    private val VALID = Regex("[A-Za-z0-9._-]{1,64}")
}
