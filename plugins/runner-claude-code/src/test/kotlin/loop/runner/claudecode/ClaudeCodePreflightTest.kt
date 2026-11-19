package loop.runner.claudecode

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The startup checks of `loop run` with `runner.kind: claude-code`. */
class ClaudeCodePreflightTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `running as root is a startup error`() {
        val error = ClaudeCodePreflight(uid = { 0 }, managedSettings = dir.resolve("absent.json")).check().error

        assertContains(assertNotNull(error), "root")
    }

    @Test
    fun `bypass mode disabled by managed settings is a startup error that says how to fix it`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("""{"permissions":{"disableBypassPermissionsMode":"disable"}}""")

        val error = ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check().error

        assertEquals(
            "the claude-code runner needs bypassPermissions mode, but $managed sets " +
                "permissions.disableBypassPermissionsMode to \"disable\". Remove that setting or use runner.kind: command",
            error,
        )
    }

    @Test
    fun `a regular user with managed settings that keep bypass mode passes without warning`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("""{"permissions":{"deny":["WebFetch"]}}""")

        assertEquals(PreflightResult(), ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check())
        assertEquals(PreflightResult(), ClaudeCodePreflight(uid = { 501 }, managedSettings = dir.resolve("absent.json")).check())
    }

    @Test
    fun `an unparseable managed settings file passes with a warning naming the file and the error`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("{not json")

        val result = ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check()

        assertNull(result.error)
        val warning = result.warnings.single()
        assertContains(warning, managed.toString())
        assertContains(warning, "Unexpected JSON token")
    }

    @Test
    fun `an unknown uid passes with a warning`() {
        val result = ClaudeCodePreflight(uid = { null }, managedSettings = dir.resolve("absent.json")).check()

        assertNull(result.error)
        assertContains(result.warnings.single(), "root")
    }
}
