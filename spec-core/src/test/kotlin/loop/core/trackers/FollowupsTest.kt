package loop.core.trackers

import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-33, story 130: a follow up goes to the tracker of its parent issue unless the parent's kind names another, its
 * body ends with a line naming the parent and the attempt, it carries `needs-triage`, and the core gets its namespaced
 * id. At most [Followups.MAX_PER_ATTEMPT] are filed per attempt.
 */
class FollowupsTest {
    /** A native adapter that creates issues numbered from 1, with the labels it was given. */
    private class Creating(
        private val prefix: String,
    ) : TrackerAdapter {
        val created = mutableListOf<Issue>()

        override val definedStates = listOf("todo", "review")

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = Result.success(emptyList())

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> = Result.success(emptyList())

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun createIssue(
            title: String,
            body: String,
            labels: List<String>,
        ): Result<Issue> {
            val id = "$prefix-${created.size + 1}"
            val issue = Issue(id, id, title, "todo", null, labels, emptyList(), body, null)
            created += issue
            return Result.success(issue)
        }
    }

    private val files = Creating("bl")
    private val github = Creating("27")

    private val poll =
        TrackerPoll(
            "local",
            mapOf("local" to NamespacedTracker("local", "local", files), "gh" to NamespacedTracker("gh", "local", github)),
        )

    @Test
    fun `the follow up goes to the parent's tracker, a bare id being the primary's, unless the kind names one`() {
        assertEquals("local", Followups.trackerOf("local:m4-33", null, "local"))
        assertEquals("local", Followups.trackerOf("m4-33", null, "local"))
        assertEquals("gh", Followups.trackerOf("gh:454", null, "local"))
        assertEquals("gh", Followups.trackerOf("local:m4-33", "gh", "local"))
    }

    @Test
    fun `the body ends with a line naming the parent and the attempt`() {
        val body = Followups.body("The export drops archived items.\n\n", "m4-33", "local:m4-33", "a1b2c3d4")

        assertEquals(
            "The export drops archived items.\n\nFollow up of m4-33 (local:m4-33), filed by Loop from attempt a1b2c3d4.",
            body,
        )
        assertEquals("Follow up of m4-33 (local:m4-33), filed by Loop from attempt a1b2c3d4.", Followups.body("  ", "m4-33", "local:m4-33", "a1b2c3d4"))
    }

    @Test
    fun `filing creates a needs-triage issue in the tracker and gives its namespaced id`() =
        runBlocking {
            val filed = Followups.file(poll, "gh", "Archived items", "body").getOrThrow()

            assertEquals(Followups.Filed("gh:27-1", "27-1", "gh"), filed)
            assertEquals(listOf(listOf("needs-triage")), github.created.map { it.labels })
            assertTrue(files.created.isEmpty())
        }

    @Test
    fun `a lone adapter that is not namespaced files under the tracker id it was asked for`() =
        runBlocking {
            val lone = TrackerPoll("local", mapOf("local" to files))

            assertEquals(Followups.Filed("local:bl-1", "bl-1", "local"), Followups.file(lone, "local", "t", "b").getOrThrow())
        }

    @Test
    fun `a tracker the registry does not have, or one that cannot create issues, fails with nothing written`() =
        runBlocking {
            val unknown = Followups.file(poll, "linear", "t", "b").exceptionOrNull()
            assertIs<IllegalStateException>(unknown)
            assertEquals("No tracker \"linear\" in this project, so no follow up was filed", unknown.message)

            val plain = TrackerPoll("local", mapOf("local" to NamespacedTracker("local", "local", NoCreate)))
            assertIs<TrackerError.Unsupported>(Followups.file(plain, "local", "t", "b").exceptionOrNull())
        }

    @Test
    fun `past the limit per attempt the follow up is refused`() {
        assertEquals(3, Followups.MAX_PER_ATTEMPT)
        assertNull(Followups.refusal(2))
        assertEquals(
            "this attempt already filed 3 follow ups, the most Loop files per attempt",
            Followups.refusal(3),
        )
    }

    @Test
    fun `the comment on the parent names the follow up and its tracker`() {
        assertEquals(
            "Filed the follow up gh:27-1 in the tracker gh: Archived items",
            Followups.parentComment(Followups.Filed("gh:27-1", "27-1", "gh"), "Archived items"),
        )
    }

    private object NoCreate : TrackerAdapter {
        override val definedStates = listOf("todo")

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = Result.success(emptyList())

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> = Result.success(emptyList())

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ): Result<Unit> = Result.success(Unit)
    }
}
