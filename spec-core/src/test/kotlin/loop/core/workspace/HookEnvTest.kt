package loop.core.workspace

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HookEnvTest {
    @Test
    fun `m4-12 the issue variables stay native and LOOP_TRACKER_ID holds the tracker id`() {
        val env = hookEnv(Path("/p/WORKFLOW.md"), "gh:454", "example-app-454", WorkspaceKey.of("example-app-454"))

        assertEquals("454", env["LOOP_ISSUE_ID"])
        assertEquals("example-app-454", env["LOOP_ISSUE_IDENTIFIER"])
        assertEquals("example-app-454", env["LOOP_WORKSPACE_KEY"])
        assertEquals("gh", env["LOOP_TRACKER_ID"])
        assertEquals("/p/WORKFLOW.md", env["LOOP_WORKFLOW"])
    }

    @Test
    fun `an extra tracker's issue gets the key of its tracker id and identifier`() {
        val key = WorkspaceKey.of("local:research-03")
        val env = hookEnv(Path("/p/WORKFLOW.md"), "local:research-03", "research-03", key)

        assertEquals("research-03", env["LOOP_ISSUE_ID"])
        assertEquals("research-03", env["LOOP_ISSUE_IDENTIFIER"])
        assertEquals(key.value, env["LOOP_WORKSPACE_KEY"])
        assertEquals("local", env["LOOP_TRACKER_ID"])
    }

    @Test
    fun `a bare id, which only a test hands the core, has no tracker variable`() {
        val env = hookEnv(Path("/p/WORKFLOW.md"), "m1-04", "m1-04", WorkspaceKey.of("m1-04"))

        assertEquals("m1-04", env["LOOP_ISSUE_ID"])
        assertFalse("LOOP_TRACKER_ID" in env)
    }
}
