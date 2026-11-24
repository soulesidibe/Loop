package loop.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import loop.api.EventExporter
import loop.api.EventExporterProvider
import loop.api.EventSink
import loop.api.ExporterConfigException
import loop.api.RecordedEvent
import loop.api.RedactingEventSink
import loop.api.Redactor
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The event exporter plugins of a host (m4-42, plan 12): every [EventExporterProvider] found with `ServiceLoader`, like
 * the runner and tracker plugins, first on the classpath, where `otel` registers, then in each JAR of the plugin
 * folder. An exporter is opt in: each section of `exporters:` in `WORKFLOW.md` turns on the provider of its key, and
 * none runs when the key is absent.
 */
class ExporterPlugins private constructor(
    private val found: List<Found>,
) {
    /** A provider and where it was found: [PluginFolder.CLASSPATH], or the path of its JAR. */
    data class Found(
        val provider: EventExporterProvider,
        val source: String,
    )

    /** An exporter the workflow turned on: its [key], and the [sink] the host records into, the redactor in front. */
    class Enabled(
        val key: String,
        val sink: EventSink,
        private val exporter: EventExporter,
    ) : AutoCloseable {
        override fun close() = exporter.close()
    }

    /** The keys found, classpath first in `ServiceLoader` order, then the JARs' by file name. */
    val keys: List<String> = found.map { it.provider.key }

    /** The provider of [key], or null when none was found. */
    fun provider(key: String): EventExporterProvider? = found.firstOrNull { it.provider.key == key }?.provider

    /**
     * The first problem of [sections], the `exporters:` sections of `WORKFLOW.md` as `WorkflowFile.exporters` keeps them, in the order written, or null: a key no plugin provides,
     * a section that is not a map, a problem of the provider's profile, or what the provider's own check refuses, such
     * as a bad endpoint. Part of the preflight, so a bad section fails startup and every tick.
     */
    fun problem(
        sections: Map<String, Any?>,
        env: Map<String, String>,
    ): String? =
        sections.entries.firstNotNullOfOrNull { (key, written) ->
            val provider = provider(key) ?: return@firstNotNullOfOrNull unsupported(key)
            val config = sectionOf(written) ?: return@firstNotNullOfOrNull "$SECTION.$key must be a map"
            provider.profile.check("$SECTION.$key", config, env).problem?.let { return@firstNotNullOfOrNull it.message }
            try {
                provider.validate(config, env)
                null
            } catch (e: ExporterConfigException) {
                e.message ?: "$SECTION.$key is invalid"
            }
        }

    /** One warning per key of an `exporters:` section its provider's profile does not list, as for runners and trackers. */
    fun warnings(
        sections: Map<String, Any?>,
        env: Map<String, String>,
    ): List<String> =
        sections.entries.flatMap { (key, written) ->
            val provider = provider(key) ?: return@flatMap emptyList()
            val config = sectionOf(written) ?: return@flatMap emptyList()
            provider.profile.check("$SECTION.$key", config, env).unknownKeys.map { "$it is not a key of the $key exporter, Loop ignores it" }
        }

    /** The env names whose values the enabled exporters send, such as a header's `$NAME`: the run's redactor learns them. */
    fun secretEnvNames(sections: Map<String, Any?>): Set<String> =
        sections.entries.flatMapTo(mutableSetOf()) { (key, written) ->
            val config = sectionOf(written) ?: return@flatMapTo emptySet()
            provider(key)?.secretEnvNames(config).orEmpty()
        }

    /**
     * Builds the exporters [sections] turn on, which the preflight checked, each wrapped in [redactor] so it never sees
     * a secret the store would not keep. None when `exporters:` is absent.
     */
    fun create(
        sections: Map<String, Any?>,
        env: Map<String, String>,
        redactor: Redactor,
    ): List<Enabled> =
        sections.entries.map { (key, written) ->
            val provider = checkNotNull(provider(key)) { unsupported(key) }
            val config = checkNotNull(sectionOf(written)) { "$SECTION.$key must be a map" }
            val exporter = provider.create(config, env)
            Enabled(key, RedactingEventSink(redactor, exporter), exporter)
        }

    private fun unsupported(key: String): String {
        if (keys.isEmpty()) return "$SECTION.$key has no exporter plugin, none was found"
        return "$SECTION.$key has no exporter plugin, use ${keys.joinToString(" or ")}"
    }

    companion object {
        /** The key of `WORKFLOW.md` whose sections turn exporters on. */
        const val SECTION = "exporters"

        /** The registry of [found], or a failure naming both providers when two share a key. */
        fun of(found: List<Found>): Result<ExporterPlugins> {
            val duplicate =
                found.groupBy { it.provider.key }.values.firstOrNull { it.size > 1 }
                    ?: return Result.success(ExporterPlugins(found))
            val (first, second) = duplicate
            return Result.failure(
                IllegalStateException(
                    "two exporter plugins provide $SECTION.${first.provider.key}: ${first.describe()} and ${second.describe()}",
                ),
            )
        }

        /** The providers of the classpath, then those of each JAR in [pluginsDir], an absent folder holding none. */
        fun discover(pluginsDir: Path): Result<ExporterPlugins> =
            PluginFolder.open(pluginsDir).fold(onSuccess = ::discover, onFailure = { Result.failure(it) })

        /** The providers of the classpath, then those of each JAR of [folder]. */
        fun discover(folder: PluginFolder): Result<ExporterPlugins> =
            runCatching { folder.providers(EventExporterProvider::class.java, "exporter plugins").map { Found(it.provider, it.source) } }
                .fold(onSuccess = ::of, onFailure = { Result.failure(it) })

        /** The classpath's providers only. */
        val classpath: ExporterPlugins by lazy { discover(PluginFolder.CLASSPATH_ONLY).getOrThrow() }

        /** A section as written, a map with string keys, or null when it is not a map. An empty section is an empty map. */
        private fun sectionOf(written: Any?): Map<String, Any?>? =
            when (written) {
                null -> emptyMap()
                is Map<*, *> -> written.entries.associate { (k, v) -> k.toString() to v }
                else -> null
            }

        private fun Found.describe(): String = "${provider.javaClass.name} from ${PluginFolder.describe(source)}"
    }
}

/**
 * The event sink of a run with exporters (m4-42): each event goes to [store] first, whose result is the only one
 * [record] returns, then to each exporter of [exporters] through a bounded queue of its own, read by its own coroutine.
 * A slow, failing or throwing exporter never delays, fails or stops the run: a full queue drops the event, and
 * [onFailure] hears of an exporter's failures at most once per [reportEvery], with its key, the latest error and how
 * many failed since the last report, so a collector that is down does not flood the log. [close] lets each exporter
 * take what its queue holds for up to [drainTimeout], then closes it.
 */
class ExportingSink(
    private val store: EventSink,
    exporters: List<ExporterPlugins.Enabled>,
    private val onFailure: (key: String, error: Throwable, failures: Int) -> Unit,
    capacity: Int = DEFAULT_CAPACITY,
    private val drainTimeout: Duration = 10.seconds,
    private val reportEvery: Duration = 1.minutes,
    private val nanoTime: () -> Long = System::nanoTime,
) : EventSink,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private inner class Lane(
        val enabled: ExporterPlugins.Enabled,
        capacity: Int,
    ) {
        val queue = Channel<RecordedEvent>(capacity)
        private var unreported = 0
        private var reportedAt: Long? = null

        val job: Job =
            scope.launch {
                for (event in queue) {
                    val result =
                        try {
                            enabled.sink.record(event)
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    result.onFailure { failed(it) }
                }
            }

        fun offer(event: RecordedEvent) {
            if (queue.trySend(event).isFailure) failed(IllegalStateException("the export queue is full, an event was dropped"))
        }

        @Synchronized
        fun failed(error: Throwable) {
            unreported++
            val now = nanoTime()
            val last = reportedAt
            if (last != null && now - last < reportEvery.inWholeNanoseconds) return
            reportedAt = now
            onFailure(enabled.key, error, unreported)
            unreported = 0
        }
    }

    private val lanes = exporters.map { Lane(it, capacity) }

    override suspend fun record(event: RecordedEvent): Result<Unit> {
        val stored = store.record(event)
        lanes.forEach { it.offer(event) }
        return stored
    }

    override fun close() {
        lanes.forEach { it.queue.close() }
        runBlocking { withTimeoutOrNull(drainTimeout) { lanes.forEach { it.job.join() } } }
        scope.cancel()
        lanes.forEach { lane ->
            try {
                lane.enabled.close()
            } catch (e: Exception) {
                lane.failed(e)
            }
        }
    }

    companion object {
        /** How many events an exporter's queue holds before it drops the next one. */
        const val DEFAULT_CAPACITY = 10_000
    }
}
