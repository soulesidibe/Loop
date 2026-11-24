package loop.export.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import loop.api.EventExporter
import loop.api.EventExporterContract
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** m4-42: the OpenTelemetry exporter keeps the contract of every exporter, over a throwing and an unreachable transport. */
class OtelEventExporterContractTest : EventExporterContract() {
    private class Throwing : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode = throw IllegalStateException("transport down")

        override fun flush(): CompletableResultCode = throw IllegalStateException("transport down")

        override fun shutdown(): CompletableResultCode = throw IllegalStateException("transport down")
    }

    override fun throwingExporter(): EventExporter = OtelEventExporter(Throwing(), timeout = 1.seconds, clock = { Clock.System.now() })

    // Port 9 on the loopback interface: nothing listens there, so the connection is refused at once.
    override fun unreachableExporter(): EventExporter =
        OtelExporterProvider().create(mapOf("endpoint" to "http://127.0.0.1:9", "timeout_ms" to "1000"), emptyMap())
}
