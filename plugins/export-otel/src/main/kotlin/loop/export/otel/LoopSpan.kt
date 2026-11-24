package loop.export.otel

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import java.security.MessageDigest
import kotlin.time.Instant

/**
 * A span Loop builds from its own events, ended, ready to export. It is built by hand, not by an SDK tracer, so its
 * ids come from Loop's ids ([LoopIds]) and its times from the events' timestamps.
 */
internal class LoopSpan(
    private val name: String,
    private val context: SpanContext,
    private val parent: SpanContext,
    private val status: StatusData,
    private val startNanos: Long,
    private val endNanos: Long,
    private val attributes: Attributes,
    private val events: List<EventData>,
    private val resource: Resource,
) : SpanData {
    override fun getName(): String = name

    override fun getKind(): SpanKind = SpanKind.INTERNAL

    override fun getSpanContext(): SpanContext = context

    override fun getParentSpanContext(): SpanContext = parent

    override fun getStatus(): StatusData = status

    override fun getStartEpochNanos(): Long = startNanos

    override fun getAttributes(): Attributes = attributes

    override fun getEvents(): List<EventData> = events

    override fun getLinks(): List<LinkData> = emptyList()

    override fun getEndEpochNanos(): Long = endNanos

    override fun hasEnded(): Boolean = true

    override fun getTotalRecordedEvents(): Int = events.size

    override fun getTotalRecordedLinks(): Int = 0

    override fun getTotalAttributeCount(): Int = attributes.size()

    @Deprecated("Deprecated in OpenTelemetry, use getInstrumentationScopeInfo")
    @Suppress("DEPRECATION")
    override fun getInstrumentationLibraryInfo(): io.opentelemetry.sdk.common.InstrumentationLibraryInfo =
        io.opentelemetry.sdk.common.InstrumentationLibraryInfo
            .create(SCOPE, null)

    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.create(SCOPE)

    override fun getResource(): Resource = resource

    companion object {
        /** The instrumentation scope of every span Loop exports. */
        const val SCOPE = "loop"
    }
}

/**
 * The OpenTelemetry ids of Loop's spans, derived from Loop's own ids so the same attempt always gives the same trace,
 * whichever process exports it: the first bytes of the SHA-256 of a typed key, as lowercase hex.
 */
internal object LoopIds {
    /** The trace of an attempt: 16 bytes of `loop:attempt:<attempt id>`. */
    fun traceId(attemptId: String): String = hex("loop:attempt:$attemptId", 16)

    /** The span of an attempt, the root of its trace. */
    fun attemptSpanId(attemptId: String): String = hex("loop:attempt-span:$attemptId", 8)

    fun sessionSpanId(
        attemptId: String,
        sessionId: String,
    ): String = hex("loop:session:$attemptId:$sessionId", 8)

    fun turnSpanId(
        attemptId: String,
        turnId: String,
    ): String = hex("loop:turn:$attemptId:$turnId", 8)

    /** A verification of an attempt, by the time of the event that opened it: an attempt can have several. */
    fun verificationSpanId(
        attemptId: String,
        startNanos: Long,
    ): String = hex("loop:verification:$attemptId:$startNanos", 8)

    /** A span of one event of the attempt that no other span holds, by its kind and time. */
    fun eventSpanId(
        attemptId: String,
        kind: String,
        nanos: Long,
    ): String = hex("loop:event:$attemptId:$kind:$nanos", 8)

    fun context(
        traceId: String,
        spanId: String,
    ): SpanContext = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())

    private fun hex(
        key: String,
        bytes: Int,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(bytes)
            .joinToString("") { "%02x".format(it) }
}

/** [this] instant in nanoseconds since the epoch, as OpenTelemetry counts time. */
internal fun Instant.epochNanos(): Long = epochSeconds * 1_000_000_000 + nanosecondsOfSecond
