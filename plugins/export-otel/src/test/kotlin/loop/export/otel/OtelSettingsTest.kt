package loop.export.otel

import loop.api.ExporterConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** m4-42: the section `exporters.otel`, checked before any attempt. */
class OtelSettingsTest {
    private val provider = OtelExporterProvider()
    private val env = mapOf("OTEL_TOKEN" to "Bearer s3cr3t", "BLANK" to " ")

    private fun refused(
        config: Map<String, Any?>,
        env: Map<String, String> = this.env,
    ): String = assertFailsWith<ExporterConfigException> { provider.validate(config, env) }.message.orEmpty()

    @Test
    fun `m4-42 an endpoint alone takes the defaults`() {
        val settings = OtelSettings.parse(mapOf("endpoint" to "http://localhost:4318"), env)

        assertEquals("http://localhost:4318", settings.endpoint)
        assertEquals(OtelSettings.Protocol.HTTP_PROTOBUF, settings.protocol)
        assertEquals("http://localhost:4318/v1/traces", settings.tracesUrl)
        assertEquals(emptyMap(), settings.headers)
        assertEquals(10.seconds, settings.timeout)
    }

    @Test
    fun `m4-42 grpc, a header from the env and a timeout are read`() {
        val config =
            mapOf(
                "endpoint" to "https://otel.example.com:4317/",
                "protocol" to "grpc",
                "headers" to mapOf("authorization" to "\$OTEL_TOKEN"),
                "timeout_ms" to "2500",
            )

        val settings = OtelSettings.parse(config, env)

        assertEquals(OtelSettings.Protocol.GRPC, settings.protocol)
        assertEquals("https://otel.example.com:4317", settings.tracesUrl)
        assertEquals(mapOf("authorization" to "Bearer s3cr3t"), settings.headers)
        assertEquals(2500.milliseconds, settings.timeout)
        assertEquals(setOf("OTEL_TOKEN"), provider.secretEnvNames(config))
    }

    @Test
    fun `m4-42 a bad endpoint is refused, naming the key and never the value`() {
        listOf(
            "localhost:4318",
            "ftp://localhost:4318",
            "http://",
            "http://user:s3cr3t@localhost:4318",
            "http://localhost:4318?token=s3cr3t",
            "http://localhost:4318#s3cr3t",
            "http://local host:4318",
        ).forEach { endpoint ->
            val message = refused(mapOf("endpoint" to endpoint))
            assertTrue(message.startsWith("exporters.otel.endpoint "), message)
            assertFalse("s3cr3t" in message, message)
        }
        assertEquals("exporters.otel.endpoint is required", refused(emptyMap()))
        assertEquals("exporters.otel.endpoint must be a string", refused(mapOf("endpoint" to listOf("x"))))
    }

    @Test
    fun `m4-42 a bad protocol, timeout or header is refused`() {
        val endpoint = "endpoint" to "http://localhost:4318"
        assertEquals(
            "exporters.otel.protocol must be http/protobuf or grpc",
            refused(mapOf(endpoint, "protocol" to "http/json")),
        )
        assertEquals(
            "exporters.otel.timeout_ms must be between 100 and 60000, got 50",
            refused(mapOf(endpoint, "timeout_ms" to "50")),
        )
        assertEquals(
            "exporters.otel.headers.authorization must be a \$NAME reference to the env, never the secret itself",
            refused(mapOf(endpoint, "headers" to mapOf("authorization" to "Bearer s3cr3t"))),
        )
        assertEquals(
            "exporters.otel.headers.authorization uses \$MISSING, which is not set",
            refused(mapOf(endpoint, "headers" to mapOf("authorization" to "\$MISSING"))),
        )
        assertEquals(
            "exporters.otel.headers.authorization uses \$BLANK, which is empty",
            refused(mapOf(endpoint, "headers" to mapOf("authorization" to "\$BLANK"))),
        )
        assertEquals("exporters.otel.headers must be a map", refused(mapOf(endpoint, "headers" to "x")))
    }

    @Test
    fun `m4-42 the profile lists every key of the section`() {
        assertEquals("otel", provider.key)
        assertEquals(listOf("endpoint", "protocol", "headers", "timeout_ms"), provider.profile.keys.map { it.name })
    }
}
