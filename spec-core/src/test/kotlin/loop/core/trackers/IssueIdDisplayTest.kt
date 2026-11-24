package loop.core.trackers

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** m4-47: how commands and agent tools read issue ids, and how ids are shown. */
class IssueIdDisplayTest {
    private val single = IssueIdDisplay.of(TrackersConfig(entry("default")))
    private val several = IssueIdDisplay.of(TrackersConfig(entry("gh"), listOf(entry("local"))))

    @Test
    fun `a bare id typed in a command means the primary tracker`() {
        assertEquals(NamespacedId("default", "m4-12"), single.typed("m4-12").getOrThrow())
        assertEquals(NamespacedId("gh", "454"), several.typed("454").getOrThrow())
    }

    @Test
    fun `a namespaced id names its tracker, the primary or an extra one`() {
        assertEquals(NamespacedId("default", "m4-12"), single.typed("default:m4-12").getOrThrow())
        assertEquals(NamespacedId("gh", "454"), several.typed("gh:454").getOrThrow())
        assertEquals(NamespacedId("local", "research-03"), several.typed("local:research-03").getOrThrow())
        assertEquals(NamespacedId("local", "a:b"), several.typed("local:a:b").getOrThrow())
    }

    @Test
    fun `a namespaced id whose tracker is not in the registry is refused, naming the tracker id`() {
        val refused = single.typed("gh:454").exceptionOrNull()
        assertIs<UnknownTrackerId>(refused)
        assertEquals("gh", refused.tracker)
        assertEquals("no tracker \"gh\" in this project, its tracker is default", refused.message)
        val other = several.typed("jira:7").exceptionOrNull()
        assertIs<UnknownTrackerId>(other)
        assertEquals("no tracker \"jira\" in this project, its trackers are gh and local", other.message)
    }

    @Test
    fun `a project with no extra tracker shows bare ids`() {
        assertEquals("m4-12", single.id("default:m4-12"))
        assertEquals("m4-12", single.id("m4-12"))
        assertEquals("m4-12", single.name("default:m4-12", "m4-12"))
        assertEquals("example-app-454", single.name("default:454", "example-app-454"))
        assertEquals("m4-12", single.name(null, "m4-12"))
    }

    @Test
    fun `a project with extra trackers shows namespaced ids, the primary's included`() {
        assertEquals("gh:454", several.id("gh:454"))
        assertEquals("gh:454", several.id("454"))
        assertEquals("local:research-03", several.id("local:research-03"))
        assertEquals("gh:example-app-454", several.name("gh:454", "example-app-454"))
        assertEquals("gh:example-app-454", several.name("454", "example-app-454"))
        assertEquals("local:research-03", several.name("local:research-03", "research-03"))
        assertEquals("research-03", several.name(null, "research-03"))
    }

    @Test
    fun `an id of a tracker that is not the primary stays namespaced in a project with no extra tracker`() {
        assertEquals("local:t-1", single.id("local:t-1"))
        assertEquals("local:t-1", single.name("local:t-1", "t-1"))
    }

    @Test
    fun `an empty trackers list is no extra tracker`() {
        assertFalse(IssueIdDisplay.of(TrackersConfig(entry("default"), emptyList())).namespaced)
        assertTrue(several.namespaced)
    }

    @Test
    fun `a typed id matches a record by its id or its identifier, within its tracker`() {
        val typed = several.typed("454").getOrThrow()
        assertTrue(several.matches(typed, "gh:454", "example-app-454"))
        assertTrue(several.matches(typed, "454", "example-app-454"))
        assertTrue(several.matches(several.typed("example-app-454").getOrThrow(), "gh:454", "example-app-454"))
        assertTrue(several.matches(several.typed("gh:example-app-454").getOrThrow(), "gh:454", "example-app-454"))
        assertFalse(several.matches(several.typed("local:454").getOrThrow(), "gh:454", "454"))
        assertFalse(several.matches(typed, "local:454", "454"))
    }

    @Test
    fun `an agent tool's issue is LOOP_TRACKER_ID and LOOP_ISSUE_ID, a missing tracker id meaning the primary`() {
        assertEquals(NamespacedId("local", "t-1"), several.ofAgent("local", "t-1").getOrThrow())
        assertEquals(NamespacedId("local", "a:b"), several.ofAgent("local", "a:b").getOrThrow())
        assertEquals(NamespacedId("gh", "454"), several.ofAgent(null, "454").getOrThrow())
        assertEquals(NamespacedId("local", "t-1"), several.ofAgent(null, "local:t-1").getOrThrow())
        val refused = single.ofAgent("local", "t-1").exceptionOrNull()
        assertIs<UnknownTrackerId>(refused)
        assertEquals("local", refused.tracker)
    }

    @Test
    fun `a core id is the issue when it reads as the same namespaced id, a bare id being the primary's`() {
        assertTrue(several.isIssue("gh:454", NamespacedId("gh", "454")))
        assertTrue(several.isIssue("454", NamespacedId("gh", "454")))
        assertFalse(several.isIssue("local:454", NamespacedId("gh", "454")))
        assertFalse(several.isIssue("454", NamespacedId("local", "454")))
    }

    private fun entry(id: String) = TrackerEntry(id, "files", Path(".loop/issues"), null, listOf("todo"), listOf("done"), emptyList())
}
