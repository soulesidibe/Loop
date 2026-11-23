package loop.resource.android

import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.RealIntegration
import loop.runner.process.ProcessGroupLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** m4-01: proves the module's classpath before any agent needs it, so a missing dependency fails here. */
class AndroidResourceModuleSmokeTest {
    @Test
    fun `plugin-api, the coroutines test library and the plugin-api test fixtures are on the classpath`() =
        runTest {
            assertEquals("p-1", ProjectId("p-1").value)
            assertTrue(RealIntegration.targets(emptyMap()).isEmpty())
        }

    @Test
    fun `runner-process is on the classpath`() {
        assertEquals("ProcessGroupLauncher", ProcessGroupLauncher::class.simpleName)
    }
}
