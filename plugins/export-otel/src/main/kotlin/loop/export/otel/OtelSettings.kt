package loop.export.otel

import loop.api.ExporterConfigException
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The section `exporters.otel` of `WORKFLOW.md` (m4-42), read: the OTLP [endpoint], its [protocol], the [headers] sent
 * with each export, their values read from the env, and the [timeout] of one export.
 */
public class OtelSettings(
    /** The collector's base URL, without a final slash, as `OTEL_EXPORTER_OTLP_ENDPOINT` takes it. */
    public val endpoint: String,
    public val protocol: Protocol,
    public val headers: Map<String, String>,
    public val timeout: Duration,
) {
    /** Where the spans go: `<endpoint>/v1/traces` over HTTP, the endpoint itself over gRPC. */
    public val tracesUrl: String
        get() =
            when (protocol) {
                Protocol.HTTP_PROTOBUF -> "$endpoint/v1/traces"
                Protocol.GRPC -> endpoint
            }

    /** The OTLP transports, by the name `exporters.otel.protocol` gives them. */
    public enum class Protocol(
        public val written: String,
    ) {
        HTTP_PROTOBUF("http/protobuf"),
        GRPC("grpc"),
    }

    public companion object {
        internal const val SECTION = "exporters.otel"

        /** The keys of `exporters.otel`, checked before [parse] reads them. */
        public val PROFILE: PluginProfile =
            PluginProfile(
                keys =
                    listOf(
                        ProfileKey("endpoint", ProfileType.STRING, required = true),
                        ProfileKey("protocol", ProfileType.STRING, default = Protocol.HTTP_PROTOBUF.written),
                        ProfileKey("headers", ProfileType.MAP),
                        ProfileKey("timeout_ms", ProfileType.INTEGER, default = "10000"),
                    ),
                limits = mapOf("timeout_ms" to 100L..60_000L),
            )

        private val ENV_REF = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")

        /**
         * Reads [config], the section as written, with the host [env], or throws [ExporterConfigException] naming the
         * first bad key. A message never holds a header value, and holds no part of a refused endpoint.
         */
        public fun parse(
            config: Map<String, Any?>,
            env: Map<String, String>,
        ): OtelSettings {
            PROFILE.check(SECTION, config, env).problem?.let { throw ExporterConfigException(it.message) }
            val protocol =
                (config["protocol"] as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let { written ->
                    Protocol.entries.firstOrNull { it.written == written }
                        ?: throw ExporterConfigException(
                            "$SECTION.protocol must be ${Protocol.entries.joinToString(" or ") { it.written }}",
                        )
                } ?: Protocol.HTTP_PROTOBUF
            return OtelSettings(
                endpoint = endpointOf((config["endpoint"] as String).trim()),
                protocol = protocol,
                headers = headersOf(config["headers"] as Map<*, *>?, env),
                timeout = timeoutOf(config["timeout_ms"]),
            )
        }

        /** The timeout in milliseconds, an integral number or a string of one, which the profile checked. */
        private fun timeoutOf(written: Any?): Duration =
            when (written) {
                null -> 10_000L
                is Number -> written.toLong()
                else -> written.toString().trim().toLong()
            }.milliseconds

        /** The names of the env variables the headers of [config] read. */
        internal fun headerEnvNames(config: Map<String, Any?>): Set<String> =
            (config["headers"] as? Map<*, *>)
                ?.values
                ?.mapNotNull { (it as? String)?.trim()?.let(ENV_REF::matchEntire)?.groupValues?.get(1) }
                ?.toSet()
                .orEmpty()

        /** An http or https URL with a host, and no user info, query or fragment, which could hold a secret. */
        private fun endpointOf(written: String): String {
            val key = "$SECTION.endpoint"
            val uri =
                try {
                    URI(written)
                } catch (_: URISyntaxException) {
                    throw ExporterConfigException("$key is not a valid URL")
                }
            val problem =
                when {
                    uri.scheme?.lowercase() !in setOf("http", "https") -> "must start with http:// or https://"
                    uri.host.isNullOrEmpty() -> "must name a host"
                    uri.rawUserInfo != null -> "must not hold a user or a password, send them as headers from the env"
                    uri.rawQuery != null -> "must not hold a query"
                    uri.rawFragment != null -> "must not hold a fragment"
                    else -> null
                }
            if (problem != null) throw ExporterConfigException("$key $problem")
            return written.trimEnd('/')
        }

        /** Each header value is a whole `$NAME`, set and not blank in [env]: a secret never sits in the file. */
        private fun headersOf(
            written: Map<*, *>?,
            env: Map<String, String>,
        ): Map<String, String> =
            written.orEmpty().entries.associate { (name, value) ->
                val key = "$SECTION.headers.$name"
                val envName =
                    (value as? String)?.trim()?.let(ENV_REF::matchEntire)?.groupValues?.get(1)
                        ?: throw ExporterConfigException("$key must be a \$NAME reference to the env, never the secret itself")
                val resolved = env[envName]
                if (resolved == null || resolved.isBlank()) {
                    throw ExporterConfigException("$key uses \$$envName, which is ${if (resolved == null) "not set" else "empty"}")
                }
                name.toString() to resolved
            }
    }
}
