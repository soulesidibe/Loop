package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.api.EventExporter
import loop.api.EventSink
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RedactingEventSink
import loop.api.SecretRedactor
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** m4-42, after the review of pull request #44: a hanging exporter never holds the run's shutdown past its budget. */
class ExportingSinkTest {
    /** Released after each test, so the threads a hanging exporter holds end. */
    private val hang = CountDownLatch(1)

    @AfterTest
    fun release() = hang.countDown()

    private class Store : EventSink {
        val events: MutableList<RecordedEvent> = Collections.synchronizedList(mutableListOf())

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            events += event
            return Result.success(Unit)
        }
    }

    /** An exporter that blocks in [record] and in [close] until the test ends, as one whose collector never answers. */
    private inner class Hanging(
        private val hangOnClose: Boolean = true,
    ) : EventExporter {
        val taken = CountDownLatch(1)

        override suspend fun record(event: RecordedEvent): Result<Unit> {
            taken.countDown()
            hang.await()
            return Result.success(Unit)
        }

        override fun close() {
            if (hangOnClose) hang.await()
        }
    }

    private data class Report(
        val key: String,
        val message: String,
        val failures: Int,
    )

    private fun enabled(exporter: EventExporter) = ExporterPlugins.Enabled("hang", exporter.redacted(), exporter)

    private fun EventExporter.redacted(): EventSink = RedactingEventSink(SecretRedactor(), this)

    private fun event(n: Int) =
        RecordedEvent(
            ts = Instant.parse("2026-11-24T10:00:00Z"),
            projectId = ProjectId("p-1"),
            kind = "poll",
            data = mapOf("n" to n.toString()),
        )

    @Test
    fun `m4-42 a full queue drops events without blocking record`() {
        val exporter = Hanging()
        val reports = Collections.synchronizedList(mutableListOf<Report>())
        val store = Store()
        val sink =
            ExportingSink(
                store,
                listOf(enabled(exporter)),
                onFailure = { key, error, failures -> reports += Report(key, error.message.orEmpty(), failures) },
                capacity = 2,
                drainTimeout = 100.milliseconds,
                closeTimeout = 100.milliseconds,
            )
        try {
            runBlocking { sink.record(event(0)) }
            assertTrue(exporter.taken.await(5, TimeUnit.SECONDS), "the exporter took the first event")
            val results = runBlocking { withTimeout(2.seconds) { (1..20).map { sink.record(event(it)) } } }

            assertTrue(results.all { it.isSuccess }, results.toString())
            assertEquals(21, store.events.size)
            assertTrue(reports.isNotEmpty(), "a dropped event is reported")
            assertContains(reports.first().message, "the export queue is full")
        } finally {
            sink.close()
        }
    }

    @Test
    fun `m4-42 close ends within its budget when the exporter hangs, and reports what it dropped`() {
        val exporter = Hanging(hangOnClose = false)
        val reports = Collections.synchronizedList(mutableListOf<Report>())
        val sink =
            ExportingSink(
                Store(),
                listOf(enabled(exporter)),
                onFailure = { key, error, failures -> reports += Report(key, error.message.orEmpty(), failures) },
                drainTimeout = 300.milliseconds,
                closeTimeout = 300.milliseconds,
            )
        runBlocking { (1..5).forEach { sink.record(event(it)) } }
        assertTrue(exporter.taken.await(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        sink.close()
        val took = (System.nanoTime() - started).let { it / 1_000_000 }

        assertTrue(took < 1_500, "close took $took ms")
        // The five events never went out, one in flight and four queued, and one report at close gives them.
        assertEquals(listOf(5), reports.map { it.failures }, reports.toString())
        assertEquals("hang", reports.single().key)
        assertContains(reports.single().message, "5 events were not exported")
    }

    @Test
    fun `m4-42 the default shutdown budget fits the run's stop grace`() {
        val budget = ExportingSink.DRAIN_TIMEOUT + ExportingSink.CLOSE_TIMEOUT

        assertTrue(budget <= 3.seconds, "budget $budget")
        // The shutdown hook of loop run waits 10 s (RunCommand.SHUTDOWN_WAIT_SECONDS) for the attempts, the sink and the store.
        assertTrue(budget < 5.seconds, "budget $budget")
    }

    @Test
    fun `m4-42 an exporter that drains in time but hangs in close is left behind and reported`() {
        val exporter = Hanging(hangOnClose = true)
        val quick =
            object : EventExporter {
                override suspend fun record(event: RecordedEvent): Result<Unit> = Result.success(Unit)

                override fun close() = exporter.close()
            }
        val reports = Collections.synchronizedList(mutableListOf<Report>())
        val sink =
            ExportingSink(
                Store(),
                listOf(enabled(quick)),
                onFailure = { key, error, failures -> reports += Report(key, error.message.orEmpty(), failures) },
                drainTimeout = 300.milliseconds,
                closeTimeout = 300.milliseconds,
            )
        runBlocking { sink.record(event(1)) }

        val started = System.nanoTime()
        sink.close()
        val took = (System.nanoTime() - started) / 1_000_000

        assertTrue(took < 1_500, "close took $took ms")
        assertEquals(1, reports.size, reports.toString())
        assertContains(reports.single().message, "did not close within")
    }
}
