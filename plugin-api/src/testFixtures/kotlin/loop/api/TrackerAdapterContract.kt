package loop.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The behavior every [TrackerAdapter] must show, whatever tracker it reads: the adapter
 * neutral items of spec 17.3. A tracker's test extends this class and supplies trackers
 * seeded through its own provider. Provider specific items (scope, malformed records,
 * blockers, pagination) stay in each tracker's own tests.
 */
public abstract class TrackerAdapterContract {
    /** One issue to seed, keyed by its [identifier]: the provider assigns the opaque id. */
    public data class Seed(
        val identifier: String,
        val title: String,
        val state: String,
        val priority: Int?,
        val labels: List<String>,
        val description: String,
    )

    /** An active state of the tracker, one [otherState] is not. */
    protected abstract val activeState: String

    /** A second state of the tracker, different from [activeState]. */
    protected abstract val otherState: String

    /**
     * False for a tracker that does not write labels yet, which skips the label write tests
     * (reported as skipped, not passed). The GitHub tracker reads first (m2-22) and writes in m2-23.
     */
    protected open val writesLabels: Boolean = true

    private fun requireLabelWrites() = assumeTrue(writesLabels, "this tracker does not write labels yet")

    /** A tracker whose provider holds exactly [seeds]. */
    protected abstract fun trackerWith(seeds: List<Seed>): TrackerAdapter

    /** A tracker whose provider cannot be reached, so every provider call fails. */
    protected abstract fun unreachableTracker(): TrackerAdapter

    /**
     * An id of the shape the tracker gives its issues, for a refresh sent to [unreachableTracker]. A tracker
     * may omit an id that cannot be one of its own without asking the provider, as the GitHub tracker does
     * for an id that is not an issue number.
     */
    protected open val wellFormedId: String = "c-1"

    private fun seed(
        identifier: String,
        state: String = activeState,
        labels: List<String> = listOf("ready-for-agent"),
    ): Seed = Seed(identifier, "Title of $identifier", state, 2, labels, "Do $identifier.")

    private fun TrackerAdapter.byStates(vararg states: String): List<Issue> =
        runBlocking { fetchIssuesByStates(states.toList()) }.getOrThrow()

    @Test
    public fun `17_3 candidate fetch returns only the issues in the requested states`() {
        val tracker = trackerWith(listOf(seed("c-1"), seed("c-2", state = otherState), seed("c-3")))

        assertEquals(listOf("c-1", "c-3"), tracker.byStates(activeState).map { it.identifier }.sorted())
        assertEquals(listOf("c-2"), tracker.byStates(otherState).map { it.identifier })
    }

    @Test
    public fun `17_3 an empty state list returns empty without a provider call`() {
        val result = runBlocking { unreachableTracker().fetchIssuesByStates(emptyList()) }

        assertEquals(emptyList(), result.getOrThrow())
    }

    @Test
    public fun `17_3 an empty id list returns empty without a provider call`() {
        val result = runBlocking { unreachableTracker().fetchIssuesByIds(emptyList()) }

        assertEquals(emptyList(), result.getOrThrow())
    }

    @Test
    public fun `17_3 labels are normalized to lowercase`() {
        val tracker = trackerWith(listOf(seed("c-1", labels = listOf("Ready-For-Agent", "AFK"))))

        assertEquals(listOf("ready-for-agent", "afk"), tracker.byStates(activeState).single().labels)
    }

    @Test
    public fun `17_3 refresh by id returns the full normalized issue snapshot`() {
        val wanted = Seed("c-2", "Second issue", otherState, 1, listOf("Ready-For-Agent"), "The body of c-2.")
        val tracker = trackerWith(listOf(seed("c-1"), wanted))
        val listed = tracker.byStates(activeState, otherState).single { it.identifier == "c-2" }

        val refreshed = runBlocking { tracker.fetchIssuesByIds(listOf(listed.id)) }.getOrThrow()

        assertEquals(listOf(listed), refreshed)
        val issue = refreshed.single()
        assertEquals(wanted.title, issue.title)
        assertEquals(wanted.state, issue.state)
        assertEquals(wanted.priority, issue.priority)
        assertEquals(listOf("ready-for-agent"), issue.labels)
        assertEquals(wanted.description, issue.description)
    }

    private fun TrackerAdapter.labelsOf(id: String): List<String> =
        runBlocking { fetchIssuesByIds(listOf(id)) }.getOrThrow().single().labels

    @Test
    public fun `a label write adds and removes labels, seen by a refresh`() {
        requireLabelWrites()
        val tracker = trackerWith(listOf(seed("c-1", labels = listOf("ready-for-agent", "afk")), seed("c-2")))
        val id = tracker.byStates(activeState).single { it.identifier == "c-1" }.id

        val result = runBlocking { tracker.updateLabels(id, add = listOf("Needs-Info"), remove = listOf("AFK")) }

        assertTrue(result.isSuccess, result.toString())
        assertEquals(setOf("ready-for-agent", "needs-info"), tracker.labelsOf(id).toSet())
        val other = tracker.byStates(activeState).single { it.identifier == "c-2" }
        assertEquals(listOf("ready-for-agent"), other.labels)
    }

    @Test
    public fun `adding a present label or removing an absent one changes nothing`() {
        requireLabelWrites()
        val tracker = trackerWith(listOf(seed("c-1", labels = listOf("ready-for-agent", "needs-info"))))
        val id = tracker.byStates(activeState).single().id

        val result = runBlocking { tracker.updateLabels(id, add = listOf("NEEDS-INFO"), remove = listOf("afk")) }

        assertTrue(result.isSuccess, result.toString())
        assertEquals(listOf("ready-for-agent", "needs-info"), tracker.labelsOf(id))
    }

    @Test
    public fun `a label write on an unknown issue fails with not found`() {
        requireLabelWrites()
        val tracker = trackerWith(listOf(seed("c-1")))

        val result = runBlocking { tracker.updateLabels("no-such-issue", add = listOf("needs-info")) }

        assertIs<TrackerError.NotFound>(result.exceptionOrNull(), result.toString())
    }

    @Test
    public fun `17_3 an unreachable provider fails both reads with a request error`() {
        val tracker = unreachableTracker()

        val byStates = runBlocking { tracker.fetchIssuesByStates(listOf(activeState)) }
        val byIds = runBlocking { tracker.fetchIssuesByIds(listOf(wellFormedId)) }

        assertIs<TrackerError.Unavailable>(byStates.exceptionOrNull(), byStates.toString())
        assertIs<TrackerError.Unavailable>(byIds.exceptionOrNull(), byIds.toString())
        assertTrue(byStates.exceptionOrNull()!!.message!!.isNotBlank())
    }
}
