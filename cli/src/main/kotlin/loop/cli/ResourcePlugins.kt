package loop.cli

import loop.api.ResourceProvider
import loop.core.resources.ResourceCheck
import java.nio.file.Path

/**
 * The resource plugins of a host (m4-24, PLAN 6.9 and 12): every [ResourceProvider] found with `ServiceLoader` on
 * `loop.api.ResourceProvider`, like the runner and tracker plugins, first on the classpath, then in each JAR of the
 * plugin folder. A provider serves a set of `resources[].kind` values. As the [ResourceCheck] of the loader, it tells
 * which kinds a provider serves: a kind none serves is a plain lock, which the loader names in one warning.
 */
class ResourcePlugins private constructor(
    private val found: List<Found>,
) : ResourceCheck {
    /** A provider and where it was found: [PluginFolder.CLASSPATH], or the path of its JAR. */
    data class Found(
        val provider: ResourceProvider,
        val source: String,
    )

    /** The kinds served, classpath first in `ServiceLoader` order, then the JARs' by file name, each provider's sorted. */
    val kinds: List<String> = found.flatMap { it.provider.kinds.sorted() }

    /** Every provider found, in the order of [kinds]; each gets `recover` once at the startup of a run (m4-50). */
    val providers: List<ResourceProvider> = found.map { it.provider }

    /** The provider serving [kind], or null when none does: the resource is then a plain lock. */
    fun provider(kind: String): ResourceProvider? = found.firstOrNull { kind in it.provider.kinds }?.provider

    override fun serves(kind: String): Result<Boolean> = Result.success(provider(kind) != null)

    companion object {
        /** The registry of [found], or a failure naming the provider that serves no kind, or both providers when two serve one kind. */
        fun of(found: List<Found>): Result<ResourcePlugins> {
            found.firstOrNull { it.provider.kinds.isEmpty() }?.let {
                return Result.failure(IllegalStateException("the resource plugin ${it.describe()} serves no kind"))
            }
            val owners = mutableMapOf<String, Found>()
            for (entry in found) {
                for (kind in entry.provider.kinds.sorted()) {
                    owners[kind]?.let {
                        return Result.failure(
                            IllegalStateException(
                                "two resource plugins serve the resource kind \"$kind\": ${it.describe()} and ${entry.describe()}",
                            ),
                        )
                    }
                    owners[kind] = entry
                }
            }
            return Result.success(ResourcePlugins(found))
        }

        /** The providers of the classpath, then those of each JAR in [pluginsDir], an absent folder holding none. */
        fun discover(pluginsDir: Path): Result<ResourcePlugins> =
            PluginFolder.open(pluginsDir).fold(onSuccess = ::discover, onFailure = { Result.failure(it) })

        /** The providers of the classpath, then those of each JAR of [folder]. A JAR or a provider that cannot load fails, naming it. */
        fun discover(folder: PluginFolder): Result<ResourcePlugins> =
            runCatching { folder.providers(ResourceProvider::class.java, "resource plugins").map { Found(it.provider, it.source) } }
                .fold(onSuccess = ::of, onFailure = { Result.failure(it) })

        /** The classpath's providers only, for a loader built without a host (tests, the startup redactor). */
        val classpath: ResourcePlugins by lazy { discover(PluginFolder.CLASSPATH_ONLY).getOrThrow() }

        private fun Found.describe(): String = "${provider.javaClass.name} from ${PluginFolder.describe(source)}"
    }
}
