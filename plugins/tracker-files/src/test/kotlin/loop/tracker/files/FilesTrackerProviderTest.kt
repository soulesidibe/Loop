package loop.tracker.files

import kotlinx.coroutines.runBlocking
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerContext
import loop.api.TrackerProvider
import java.util.ServiceLoader
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(listOf(ProfileKey("dir", ProfileType.STRING, default = ".loop/issues")), provider.profile.keys)
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
}
