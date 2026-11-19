package loop.cli

import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The plugin JARs of a host (m3-04, m3-05): each `*.jar` file directly in `<LOOP_HOME>/plugins/`, by file name, with
 * its own class loader, whose parent is the host's, so a plugin shares the plugin API. Opened once per host, so the
 * runner and the tracker plugins of one JAR share its class loader. The loaders stay open for the process.
 */
class PluginFolder private constructor(
    private val jars: List<Pair<Path, URLClassLoader>>,
) {
    /** A provider of a plugin interface and where it was found: [CLASSPATH], or the path of its JAR. */
    data class Found<P>(
        val provider: P,
        val source: String,
    )

    /**
     * The providers of [type] on the classpath, in `ServiceLoader` order, then those each JAR defines, by file name.
     * A JAR's loader also sees the classpath's providers through its parent; they are left out. A provider that
     * cannot load fails, naming the classpath or the JAR and the [plugins] it was looking for, such as `runner plugins`.
     */
    fun <P : Any> providers(
        type: Class<P>,
        plugins: String,
    ): List<Found<P>> {
        val classpath =
            try {
                ServiceLoader.load(type, PluginFolder::class.java.classLoader).map { Found(it, CLASSPATH) }
            } catch (e: ServiceConfigurationError) {
                throw IllegalStateException("cannot load the $plugins of the classpath: ${e.sentence()}", e)
            }
        return classpath +
            jars.flatMap { (jar, loader) ->
                try {
                    ServiceLoader
                        .load(type, loader)
                        .stream()
                        .filter { it.type().classLoader === loader }
                        .map { Found(it.get(), jar.toString()) }
                        .toList()
                } catch (e: ServiceConfigurationError) {
                    throw IllegalStateException("cannot load the $plugins of $jar: ${e.sentence()}", e)
                }
            }
    }

    companion object {
        /** The source of a provider found on the classpath. */
        const val CLASSPATH = "classpath"

        /** No plugin JAR: only the classpath's providers. */
        val CLASSPATH_ONLY: PluginFolder = PluginFolder(emptyList())

        /** The JARs of [pluginsDir], an absent folder holding none; a folder that cannot be listed fails, naming it. */
        fun open(pluginsDir: Path): Result<PluginFolder> {
            if (!pluginsDir.isDirectory()) return Result.success(CLASSPATH_ONLY)
            val jars =
                try {
                    pluginsDir.listDirectoryEntries("*.jar").filter { it.isRegularFile() }.sortedBy { it.name }
                } catch (e: IOException) {
                    return Result.failure(IllegalStateException("cannot list the plugin folder $pluginsDir: ${e.sentence()}", e))
                }
            return Result.success(
                PluginFolder(
                    jars.map {
                        it to
                            URLClassLoader(arrayOf(it.toUri().toURL()), PluginFolder::class.java.classLoader)
                    },
                ),
            )
        }

        /** How a message names [source]: `the classpath`, or the JAR's path. */
        fun describe(source: String): String = if (source == CLASSPATH) "the classpath" else source
    }
}
