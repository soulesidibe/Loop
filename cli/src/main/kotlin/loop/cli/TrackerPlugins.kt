package loop.cli

import loop.api.TrackerProvider
import loop.core.workflow.TrackerCheck
import java.nio.file.Path

/**
 * The tracker plugins of a host (m3-05, plan 12): every [TrackerProvider] found with `ServiceLoader`, like the runner
 * plugins, first on the classpath, where `files` and `github` register, then in each JAR of the plugin folder.
 * `tracker.kind` selects the provider whose kind matches. As the [TrackerCheck] of the loader, it refuses an unknown
 * kind naming the kinds found.
 */
class TrackerPlugins private constructor(
    private val found: List<Found>,
) : TrackerCheck {
    /** A provider and where it was found: [PluginFolder.CLASSPATH], or the path of its JAR. */
    data class Found(
        val provider: TrackerProvider,
        val source: String,
    )

    /** The kinds found, classpath first in `ServiceLoader` order, then the JARs' by file name. */
    val kinds: List<String> = found.map { it.provider.kind }

    private val check = TrackerCheck.of(found.map { it.provider })

    /** The provider of [kind], or null when none was found. */
    fun provider(kind: String): TrackerProvider? = found.firstOrNull { it.provider.kind == kind }?.provider

    override fun select(kind: String): Result<TrackerProvider> = check.select(kind)

    /** The sentence for a `tracker.kind` no provider was found for, naming the kinds found, sorted. */
    fun unsupported(kind: String): String = TrackerCheck.unsupported(kind, kinds)

    companion object {
        /** The registry of [found], or a failure naming both providers when two share a kind. */
        fun of(found: List<Found>): Result<TrackerPlugins> {
            val duplicate =
                found.groupBy { it.provider.kind }.values.firstOrNull { it.size > 1 }
                    ?: return Result.success(TrackerPlugins(found))
            val (first, second) = duplicate
            return Result.failure(
                IllegalStateException(
                    "two tracker plugins provide tracker.kind \"${first.provider.kind}\": ${first.describe()} and ${second.describe()}",
                ),
            )
        }

        /** The providers of the classpath, then those of each JAR in [pluginsDir], an absent folder holding none. */
        fun discover(pluginsDir: Path): Result<TrackerPlugins> =
            PluginFolder.open(pluginsDir).fold(onSuccess = ::discover, onFailure = { Result.failure(it) })

        /** The providers of the classpath, then those of each JAR of [folder]. A JAR or a provider that cannot load fails, naming it. */
        fun discover(folder: PluginFolder): Result<TrackerPlugins> =
            runCatching { folder.providers(TrackerProvider::class.java, "tracker plugins").map { Found(it.provider, it.source) } }
                .fold(onSuccess = ::of, onFailure = { Result.failure(it) })

        /** The classpath's providers only, for a loader or a tracker built without a host (tests, the startup redactor). */
        val classpath: TrackerPlugins by lazy { discover(PluginFolder.CLASSPATH_ONLY).getOrThrow() }

        private fun Found.describe(): String = "${provider.javaClass.name} from ${PluginFolder.describe(source)}"
    }
}
