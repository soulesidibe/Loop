package loop.export.otel

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import loop.api.EventExporter
import loop.api.EventExporterProvider
import loop.api.PluginProfile
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * The OpenTelemetry exporter plugin (m4-42), key `otel`: it sends the spans of [OtelEventExporter] to an OTLP endpoint,
 * over HTTP with protobuf or over gRPC, as `exporters.otel` sets it. Found by `ServiceLoader` through its
 * `META-INF/services` resource.
 */
public class OtelExporterProvider : EventExporterProvider {
    override val key: String = "otel"
    override val profile: PluginProfile = OtelSettings.PROFILE

    override fun validate(
        config: Map<String, Any?>,
        env: Map<String, String>,
    ) {
        OtelSettings.parse(config, env)
    }

    override fun secretEnvNames(config: Map<String, Any?>): Set<String> = OtelSettings.headerEnvNames(config)

    override fun create(
        config: Map<String, Any?>,
        env: Map<String, String>,
    ): EventExporter {
        val settings = OtelSettings.parse(config, env)
        return OtelEventExporter(spanExporter(settings), settings.timeout, clock = { Clock.System.now() })
    }

    private fun spanExporter(settings: OtelSettings): SpanExporter {
        val timeoutMs = settings.timeout.inWholeMilliseconds
        return when (settings.protocol) {
            OtelSettings.Protocol.HTTP_PROTOBUF ->
                OtlpHttpSpanExporter
                    .builder()
                    .setEndpoint(settings.tracesUrl)
                    .setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .apply { settings.headers.forEach { (name, value) -> addHeader(name, value) } }
                    .build()
            OtelSettings.Protocol.GRPC ->
                OtlpGrpcSpanExporter
                    .builder()
                    .setEndpoint(settings.tracesUrl)
                    .setTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .apply { settings.headers.forEach { (name, value) -> addHeader(name, value) } }
                    .build()
        }
    }
}
