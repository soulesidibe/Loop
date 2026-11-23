package loop.export.otel

import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RealIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** m4-01: proves the module's classpath before any agent needs it, so a missing dependency fails here. */
class OtelExportModuleSmokeTest {
    @Test
    fun `plugin-api, the coroutines test library and the plugin-api test fixtures are on the classpath`() =
        runTest {
            assertEquals("p-1", ProjectId("p-1").value)
            assertTrue(RealIntegration.targets(emptyMap()).isEmpty())
        }

    @Test
    fun `the OpenTelemetry SDK is on the classpath`() {
        val provider = SdkTracerProvider.builder().build()
        try {
            assertNotNull(provider.get("loop"))
        } finally {
            provider.close()
        }
    }
}
