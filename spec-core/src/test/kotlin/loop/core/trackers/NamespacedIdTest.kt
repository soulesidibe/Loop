package loop.core.trackers

import loop.core.delivery.PullRequestDelivery
import loop.core.workspace.WorkspaceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NamespacedIdTest {
    @Test
    fun `the namespaced id is the tracker id, a colon and the native id`() {
        assertEquals("gh:454", NamespacedId("gh", "454").toString())
        assertEquals("local:research-03", NamespacedId("local", "research-03").value)
    }

    @Test
    fun `a tracker id off the pattern or a blank native id is refused`() {
        assertFailsWith<IllegalArgumentException> { NamespacedId("Gh", "454") }
        assertFailsWith<IllegalArgumentException> { NamespacedId("gh", "") }
    }

    @Test
    fun `parse splits at the first colon, so a native id may hold a colon`() {
        assertEquals(NamespacedId("gh", "454"), NamespacedId.parse("gh:454"))
        assertEquals(NamespacedId("lin", "a:b"), NamespacedId.parse("lin:a:b"))
    }

    @Test
    fun `parse gives null for a bare id`() {
        assertNull(NamespacedId.parse("m4-12"))
        assertNull(NamespacedId.parse("454"))
        assertNull(NamespacedId.parse(":454"))
        assertNull(NamespacedId.parse("Gh:454"))
        assertNull(NamespacedId.parse("gh:"))
    }

    @Test
    fun `the reading rule reads a bare id as the primary tracker's and keeps a namespaced one`() {
        assertEquals(NamespacedId("default", "m4-12"), NamespacedId.read("m4-12", primary = "default"))
        assertEquals(NamespacedId("gh", "454"), NamespacedId.read("454", primary = "gh"))
        assertEquals(NamespacedId("local", "research-03"), NamespacedId.read("local:research-03", primary = "gh"))
        assertEquals(NamespacedId("gh", "454"), NamespacedId.read("gh:454", primary = "gh"))
    }

    @Test
    fun `the bare form is the native id of a primary tracker's issue, none for an extra tracker's`() {
        assertEquals("m4-12", NamespacedId("default", "m4-12").bareFor(primary = "default"))
        assertNull(NamespacedId("local", "m4-12").bareFor(primary = "default"))
    }

    @Test
    fun `the primary tracker's issue keeps the workspace key of its identifier and its branch`() {
        val key = NamespacedId.workspaceKey("gh:454", "example-app-454", primary = "gh")
        assertEquals(WorkspaceKey.of("example-app-454"), key)
        assertEquals("loop/example-app-454", PullRequestDelivery.branch(key.value))
    }

    @Test
    fun `a bare id is the primary tracker's, so it keeps the key of its identifier too`() {
        assertEquals(WorkspaceKey.of("m4-12"), NamespacedId.workspaceKey("m4-12", "m4-12", primary = "default"))
    }

    @Test
    fun `an extra tracker's issue gets the key of its tracker id and identifier and the branch of that key`() {
        val key = NamespacedId.workspaceKey("local:research-03", "research-03", primary = "gh")
        assertEquals(WorkspaceKey.of("local:research-03"), key)
        assertEquals("local_research-03-" + key.value.substringAfterLast("-"), key.value)
        assertEquals("loop/${key.value}", PullRequestDelivery.branch(key.value))
    }

    @Test
    fun `two trackers with the same identifier never share a workspace`() {
        val primary = NamespacedId.workspaceKey("gh:12", "t-12", primary = "gh")
        val extra = NamespacedId.workspaceKey("local:12", "t-12", primary = "gh")
        assertEquals(WorkspaceKey.of("t-12"), primary)
        assertEquals(WorkspaceKey.of("local:t-12"), extra)
        assert(primary != extra)
    }

    @Test
    fun `the native id and the tracker id of a core id, null tracker for a bare one`() {
        assertEquals("454", NamespacedId.nativeOf("gh:454"))
        assertEquals("m4-12", NamespacedId.nativeOf("m4-12"))
        assertEquals("gh", NamespacedId.trackerOf("gh:454"))
        assertNull(NamespacedId.trackerOf("m4-12"))
    }
}
