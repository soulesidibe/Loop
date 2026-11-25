package loop.tracker.files

import kotlinx.coroutines.runBlocking
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerConfigException
import loop.api.TrackerContext
import loop.api.TrackerProvider
import java.util.ServiceLoader
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** m3-05: the `files` tracker plugin, registered in `META-INF/services/loop.api.TrackerProvider`. */
class FilesTrackerProviderTest {
    private val provider = FilesTrackerProvider()

    @Test
    fun `it is found with ServiceLoader under the files kind`() {
        val kinds = ServiceLoader.load(TrackerProvider::class.java).map { it.kind }

        assertEquals(listOf("files"), kinds)
    }

    @Test
    fun `its profile declares dir with its default, no secret and no limit`() {
        assertEquals(
            listOf(
                ProfileKey("dir", ProfileType.STRING, default = ".loop/issues"),
                ProfileKey("prefix", ProfileType.STRING, default = "bl"),
            ),
            provider.profile.keys,
        )
        assertEquals(emptySet(), provider.profile.secrets)
        assertEquals(emptyMap(), provider.profile.limits)
    }

    @Test
    fun `its default and defined states are the files states`() {
        assertEquals(listOf("todo", "in_progress"), provider.defaultActiveStates)
        assertEquals(listOf("done", "canceled"), provider.defaultTerminalStates)
        assertEquals(FilesTracker.DEFINED_STATES, provider.definedStates)
    }

    @Test
    fun `it needs no settings and builds a files tracker over the resolved dir`() {
        val dir = createTempDirectory("loop-files-provider")
        dir.resolve("a-1.md").writeText("---\nid: a-1\ntitle: One\nstate: todo\n---\nBody\n")

        assertNull(provider.parse(mapOf("dir" to "issues"), emptyMap()))
        val tracker = assertIs<FilesTracker>(provider.create(null, TrackerContext(dir, createTempDirectory("loop-locks"))))

        assertEquals(listOf("a-1"), runBlocking { tracker.fetchIssuesByStates(listOf("todo")).getOrThrow() }.map { it.id })
    }

    @Test
    fun `m4-33 a prefix setting names the files of the issues it creates`() {
        val dir = createTempDirectory("loop-files-provider")
        val settings = provider.parse(mapOf("prefix" to "ops"), emptyMap())
        val tracker = provider.create(settings, TrackerContext(dir, createTempDirectory("loop-locks"), activeStates = listOf("todo")))

        val created = runBlocking { tracker.createIssue("Follow up", "Body.", listOf("needs-triage")) }.getOrThrow()

        assertEquals("ops-01", created.id)
        assertEquals("todo", created.state)
    }

    @Test
    fun `m4-33 without a prefix setting the tracker creates bl issues`() {
        val dir = createTempDirectory("loop-files-provider")
        val tracker = provider.create(provider.parse(emptyMap(), emptyMap()), TrackerContext(dir, createTempDirectory("loop-locks")))

        val created = runBlocking { tracker.createIssue("Follow up", "Body.", listOf("needs-triage")) }.getOrThrow()

        assertEquals("bl-01", created.id)
    }

    @Test
    fun `m4-33 a prefix that cannot start an issue id is refused`() {
        for (bad in listOf("BL", "1x", "a-b", "", "a b")) {
            val error = assertFailsWith<TrackerConfigException>(bad) { provider.parse(mapOf("prefix" to bad), emptyMap()) }
            assertEquals("invalid_tracker_config", error.category)
            assertContains(error.message.orEmpty(), "tracker.provider.prefix")
        }
    }
}
