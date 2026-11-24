package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.EventExporter
import loop.api.EventExporterProvider
import loop.api.EventSink
import loop.api.ExporterConfigException
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** m4-42: exporter plugins are found by `ServiceLoader`, opt in, checked in the preflight and wrapped in the redactor. */
class ExporterPluginsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** A provider written in the test: it keeps what its exporter sees, and fails or throws when told to. */
    private class Capturing(
        val failWith: Exception? = null,
        val throwOn: Boolean = false,
    ) : EventExporterProvider {
        val seen: MutableList<RecordedEvent> = Collections.synchronizedList(mutableListOf())
        var closed = false

        override val key = "capture"
        override val profile = PluginProfile(listOf(ProfileKey("target", ProfileType.STRING, required = true)))

        override fun validate(
            config: Map<String, Any?>,
            env: Map<String, String>,
        ) {
            if (config["target"] == "bad") throw ExporterConfigException("exporters.capture.target is bad")
        }

        override fun secretEnvNames(config: Map<String, Any?>): Set<String> = setOf("CAPTURE_TOKEN")

        override fun create(
            config: Map<String, Any?>,
            env: Map<String, String>,
        ): EventExporter =
            object : EventExporter {
                override suspend fun record(event: RecordedEvent): Result<Unit> {
                    seen += event
                    if (throwOn) throw IllegalStateException("exporter bug")
                    return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
                }

                override fun close() {
                    closed = true
                }
            }
    }

    private class Store : EventSink {
        val events: MutableList<RecordedEvent> = Collections.synchronizedList(mutableListOf())

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            events += event
            return Result.success(Unit)
        }
    }

    private fun plugins(provider: EventExporterProvider) = ExporterPlugins.of(listOf(ExporterPlugins.Found(provider, PluginFolder.CLASSPATH))).getOrThrow()

    private fun event(
        kind: String,
        data: Map<String, String> = emptyMap(),
    ) = RecordedEvent(ts = Instant.parse("2026-11-24T10:00:00Z"), projectId = ProjectId("p-1"), kind = kind, data = data)

    @Test
    fun `m4-42 the otel exporter is found on the classpath through its services file`() {
        val plugins = ExporterPlugins.classpath

        assertEquals(listOf("otel"), plugins.keys)
        assertEquals("loop.export.otel.OtelExporterProvider", plugins.provider("otel")!!.javaClass.name)
    }

    @Test
    fun `m4-42 with the exporters key absent, no exporter is built and nothing is exported`() {
        val plugins = ExporterPlugins.classpath

        assertNull(plugins.problem(emptyMap(), emptyMap()))
        assertEquals(emptyList(), plugins.create(emptyMap(), emptyMap(), SecretRedactor()))
    }

    @Test
    fun `m4-42 the preflight check refuses a bad endpoint, an unknown exporter and a section that is not a map`() {
        val plugins = ExporterPlugins.classpath
        fun problem(sections: Map<String, Any?>) = plugins.problem(sections, emptyMap())

        assertEquals("exporters.otel.endpoint must start with http:// or https://", problem(mapOf("otel" to mapOf("endpoint" to "localhost:4318"))))
        assertEquals("exporters.otel.endpoint is required", problem(mapOf("otel" to null)))
        assertEquals("exporters.datadog has no exporter plugin, use otel", problem(mapOf("datadog" to mapOf("x" to "y"))))
        assertEquals("exporters.otel must be a map", problem(mapOf("otel" to "http://localhost:4318")))
        assertNull(problem(mapOf("otel" to mapOf("endpoint" to "http://localhost:4318"))))
        assertEquals(
            listOf("exporters.otel.color is not a key of the otel exporter, Loop ignores it"),
            plugins.warnings(mapOf("otel" to mapOf("endpoint" to "http://localhost:4318", "color" to "blue")), emptyMap()),
        )
    }

    @Test
    fun `m4-42 a provider's own check and its profile run in the preflight`() {
        val plugins = plugins(Capturing())

        assertEquals("exporters.capture.target is required", plugins.problem(mapOf("capture" to emptyMap<String, Any?>()), emptyMap()))
        assertEquals("exporters.capture.target is bad", plugins.problem(mapOf("capture" to mapOf("target" to "bad")), emptyMap()))
        assertEquals(setOf("CAPTURE_TOKEN"), plugins.secretEnvNames(mapOf("capture" to mapOf("target" to "x"))))
    }

    @Test
    fun `m4-42 two providers of one key fail, naming both`() {
        val found = listOf(ExporterPlugins.Found(Capturing(), PluginFolder.CLASSPATH), ExporterPlugins.Found(Capturing(), "/plugins/x.jar"))

        val message = ExporterPlugins.of(found).exceptionOrNull()?.message.orEmpty()

        assertContains(message, "two exporter plugins provide exporters.capture")
        assertContains(message, "/plugins/x.jar")
    }

    @Test
    fun `m4-42 an exporter is wrapped in the run's redactor, so it never sees a secret`() {
        val provider = Capturing()
        val redactor = SecretRedactor().apply { learn(mapOf("CAPTURE_TOKEN" to "s3cr3t-capture-value")) }
        val enabled = plugins(provider).create(mapOf("capture" to mapOf("target" to "x")), emptyMap(), redactor)

        val store = Store()
        ExportingSink(store, enabled, onFailure = { _, _, _ -> }).use { sink ->
            runBlocking { sink.record(event("runner_output", mapOf("line" to "token s3cr3t-capture-value"))) }
        }

        assertEquals("token s3cr3t-capture-value", store.events.single().data["line"], "the store redacts on its own")
        assertEquals("token [redacted:CAPTURE_TOKEN]", provider.seen.single().data["line"])
        assertTrue(provider.closed)
    }

    @Test
    fun `m4-42 an exporter that fails or throws never fails the record, and its failures are reported once a minute`() {
        for (provider in listOf(Capturing(failWith = IllegalStateException("endpoint down")), Capturing(throwOn = true))) {
            val failures = Collections.synchronizedList(mutableListOf<String>())
            val enabled = plugins(provider).create(mapOf("capture" to mapOf("target" to "x")), emptyMap(), SecretRedactor())
            val store = Store()

            val results =
                ExportingSink(store, enabled, onFailure = { key, error, count -> failures += "$key: ${error.message}, $count" }).use { sink ->
                    runBlocking { (1..3).map { sink.record(event("poll")) } }
                }

            assertTrue(results.all { it.isSuccess }, results.toString())
            assertEquals(3, store.events.size)
            assertEquals(3, provider.seen.size)
            assertEquals(1, failures.size, failures.toString())
            assertTrue(failures.single().startsWith("capture: "), failures.toString())
            assertTrue(failures.single().endsWith(", 1"), failures.toString())
        }
    }

    @Test
    fun `m4-42 a failure after the report interval is reported again, with the count of failures since the last report`() {
        var now = 0L
        val reports = Collections.synchronizedList(mutableListOf<Int>())
        val provider = Capturing(failWith = IllegalStateException("down"))
        val enabled = plugins(provider).create(mapOf("capture" to mapOf("target" to "x")), emptyMap(), SecretRedactor())
        ExportingSink(Store(), enabled, onFailure = { _, _, count -> reports += count }, reportEvery = 1.minutes, nanoTime = { now })
            .use { sink ->
                runBlocking { repeat(3) { sink.record(event("poll")) } }
                // The exporter reads its queue on a coroutine of its own: the clock moves once it took the three events.
                while (provider.seen.size < 3) Thread.sleep(10)
                now = 61.seconds.inWholeNanoseconds
                runBlocking { sink.record(event("poll")) }
            }

        assertEquals(listOf(1, 3), reports)
    }

    @Test
    fun `m4-42 loop run lists the plugins it found and the exporters the workflow turns on`() {
        fixture.issue("t-1")

        val off = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, off.exitCode, off.toString())
        val plugins = off.stderr.lines().single { "event=plugins" in it }
        assertContains(plugins, "runners=claude-code,codex-app-server,command")
        assertContains(plugins, "trackers=files,github")
        assertContains(plugins, "exporters=otel")
        assertContains(plugins, "enabled_exporters=none")
    }

    @Test
    fun `m4-42 an exporter whose endpoint cannot be reached never stops the run`() {
        fixture.issue("t-1")
        // Port 9 on the loopback interface: nothing listens there.
        fixture.writeWorkflow(extra = "exporters:\n  otel:\n    endpoint: http://127.0.0.1:9\n    timeout_ms: 1000")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertContains(result.stderr.lines().single { "event=plugins" in it }, "enabled_exporters=otel")
        val failed = result.stderr.lines().filter { "event=export" in it && "outcome=failed" in it }
        assertEquals(1, failed.size, result.toString())
        assertContains(failed.single(), "exporter=otel")
    }

    @Test
    fun `m4-42 a bad endpoint fails the startup preflight`() {
        fixture.issue("t-1")
        fixture.writeWorkflow(extra = "exporters:\n  otel:\n    endpoint: localhost:4318")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "exporters.otel.endpoint must start with http:// or https://")
        assertFalse("event=plugins" in result.stderr, result.toString())
        assertEquals("todo", fixture.stateOf("t-1"))
    }
}
