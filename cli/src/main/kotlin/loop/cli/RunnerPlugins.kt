package loop.cli

import loop.api.RunnerConfigException
import loop.api.RunnerProvider
import loop.core.workflow.RunnerCheck
import loop.core.workflow.RunnerProfile
import loop.core.workflow.RunnerSettings
import java.nio.file.Path

/**
 * The runner plugins of a host (m3-04, plan 12): every [RunnerProvider] found with `ServiceLoader`, first on the
 * classpath, then in each JAR of the plugin folder, `<LOOP_HOME>/plugins/`, by file name, each with its own class
 * loader. `runner.kind` selects the provider whose kind matches. As the [RunnerCheck] of the loader, it refuses an
 * unknown kind naming the kinds found, gives the selected provider's profile (m3-05), which the loader checks the
 * section against first, and asks the provider to validate its own section.
 */
class RunnerPlugins private constructor(
    private val found: List<Found>,
) : RunnerCheck {
    /** A provider and where it was found: [PluginFolder.CLASSPATH], or the path of its JAR. */
    data class Found(
        val provider: RunnerProvider,
        val source: String,
    )

    /** The kinds found, classpath first in `ServiceLoader` order, then the JARs' by file name. */
    val kinds: List<String> = found.map { it.provider.kind }

    /** The provider of [kind], or null when none was found. */
    fun provider(kind: String): RunnerProvider? = found.firstOrNull { it.provider.kind == kind }?.provider

    override fun profile(kind: String): RunnerProfile? = provider(kind)?.let { RunnerProfile(it.section, it.profile) }

    override fun problem(settings: RunnerSettings): String? {
        val provider = provider(settings.kind) ?: return unsupported(settings.kind)
        return try {
            provider.validate(settings.section(provider.section))
            null
        } catch (e: RunnerConfigException) {
            e.sentence()
        }
    }

    /** The sentence for a `runner.kind` no provider was found for, naming the kinds found. */
    fun unsupported(kind: String): String {
        if (kinds.isEmpty()) return "runner.kind \"$kind\" is not supported, no runner plugin was found"
        val names = if (kinds.size == 1) kinds.single() else kinds.dropLast(1).joinToString(", ") + " or " + kinds.last()
        return "runner.kind \"$kind\" is not supported, use $names"
    }

    companion object {
        /** The registry of [found], or a failure naming both providers when two share a kind. */
        fun of(found: List<Found>): Result<RunnerPlugins> {
            val duplicate =
                found.groupBy { it.provider.kind }.values.firstOrNull { it.size > 1 }
                    ?: return Result.success(RunnerPlugins(found))
            val (first, second) = duplicate
            return Result.failure(
                IllegalStateException(
                    "two runner plugins provide runner.kind \"${first.provider.kind}\": ${first.describe()} and ${second.describe()}",
                ),
            )
        }

        /**
         * The providers of the classpath, then those of each JAR in [pluginsDir], an absent folder holding none.
         * A JAR or a provider that cannot load fails, naming it.
         */
        fun discover(pluginsDir: Path): Result<RunnerPlugins> =
            PluginFolder.open(pluginsDir).fold(onSuccess = ::discover, onFailure = { Result.failure(it) })

        /** The providers of the classpath, then those of each JAR of [folder]. */
        fun discover(folder: PluginFolder): Result<RunnerPlugins> =
            runCatching { folder.providers(RunnerProvider::class.java, "runner plugins").map { Found(it.provider, it.source) } }
                .fold(onSuccess = ::of, onFailure = { Result.failure(it) })

        /** The classpath's providers only, for a loader built without a host (tests, the startup redactor). */
        val classpath: RunnerPlugins by lazy { discover(PluginFolder.CLASSPATH_ONLY).getOrThrow() }

        private fun Found.describe(): String = "${provider.javaClass.name} from ${PluginFolder.describe(source)}"
    }
}

/**
 * The section `runners.<key>` as written, an empty map when absent, for its runner plugin to read. A section that is
 * not a map is refused like any invalid section.
 */
fun RunnerSettings.section(key: String): Map<String, Any?> =
    when (val value = sections[key]) {
        null -> emptyMap()
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
        else -> throw RunnerConfigException("runners.$key must be a map")
    }

/** The provider of `runner.kind`, which exists once the workflow loaded through these plugins. */
internal fun RunnerPlugins.providerFor(settings: RunnerSettings): RunnerProvider =
    provider(settings.kind) ?: throw IllegalStateException(unsupported(settings.kind))
