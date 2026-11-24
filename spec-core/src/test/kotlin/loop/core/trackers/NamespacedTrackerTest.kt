package loop.core.trackers

import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

class NamespacedTrackerTest {
    private class NativeTracker : TrackerAdapter {
        val issues = mutableListOf(issue("454", "example-app-454"), issue("455", "example-app-455"))
        val asked = mutableListOf<List<String>>()
        val writes = mutableListOf<String>()

        override val definedStates = listOf("todo", "review")

        override suspend fun fetchIssuesByStates(states: List<String>) = Result.success(issues.toList())

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
            asked += ids
            return Result.success(issues.filter { it.id in ids })
        }

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ): Result<Unit> = Result.success(Unit).also { writes += "move $issueId $toState" }

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ): Result<Unit> = Result.success(Unit).also { writes += "comment $issueId ${comment.text}" }

        override suspend fun updateLabels(
            issueId: String,
            add: List<String>,
            remove: List<String>,
        ): Result<Unit> = Result.success(Unit).also { writes += "labels $issueId $add $remove" }

        override suspend fun completeIssue(issue: Issue): Result<Issue> {
            writes += "complete ${issue.id}"
            return Result.success(issue.copy(description = "completed"))
        }
    }

    private val native = NativeTracker()
    private val comment = IssueComment("loop", Instant.fromEpochSeconds(0), "hello")

    @Test
    fun `issues reach the core with namespaced ids and native identifiers`() {
        val tracker = NamespacedTracker("gh", primary = "gh", native)

        val issues = runBlocking { tracker.fetchIssuesByStates(listOf("todo")) }.getOrThrow()

        assertEquals(listOf("gh:454", "gh:455"), issues.map { it.id })
        assertEquals(listOf("example-app-454", "example-app-455"), issues.map { it.identifier })
        assertEquals(native.definedStates, tracker.definedStates)
        assertSame(native, tracker.native)
    }

    @Test
    fun `a read by id asks the adapter for native ids and drops the ids of another tracker`() {
        val tracker = NamespacedTracker("gh", primary = "gh", native)

        val issues = runBlocking { tracker.fetchIssuesByIds(listOf("gh:454", "local:454", "455")) }.getOrThrow()

        assertEquals(listOf(listOf("454", "455")), native.asked)
        assertEquals(listOf("gh:454", "gh:455"), issues.map { it.id })
    }

    @Test
    fun `an extra tracker never takes a bare id, which is the primary's`() {
        val tracker = NamespacedTracker("local", primary = "gh", native)

        val issues = runBlocking { tracker.fetchIssuesByIds(listOf("454", "local:455")) }.getOrThrow()

        assertEquals(listOf(listOf("455")), native.asked)
        assertEquals(listOf("local:455"), issues.map { it.id })
        val refused = runBlocking { tracker.moveIssue("454", "review") }.exceptionOrNull()
        assertIs<TrackerError.NotFound>(refused)
        assertEquals("454", refused.issueId)
    }

    @Test
    fun `writes reach the adapter with the native id`() {
        val tracker = NamespacedTracker("gh", primary = "gh", native)

        runBlocking {
            tracker.moveIssue("gh:454", "review").getOrThrow()
            tracker.addComment("gh:454", comment).getOrThrow()
            tracker.updateLabels("gh:454", add = listOf("a"), remove = listOf("b")).getOrThrow()
        }

        assertEquals(listOf("move 454 review", "comment 454 hello", "labels 454 [a] [b]"), native.writes)
    }

    @Test
    fun `a write for another tracker's issue is refused and never reaches the adapter`() {
        val tracker = NamespacedTracker("gh", primary = "gh", native)

        val refused = runBlocking { tracker.addComment("local:454", comment) }.exceptionOrNull()

        assertIs<TrackerError.NotFound>(refused)
        assertEquals("local:454", refused.issueId)
        assertEquals(emptyList(), native.writes)
    }

    @Test
    fun `the completed issue keeps its namespaced id`() {
        val tracker = NamespacedTracker("gh", primary = "gh", native)
        val issue = runBlocking { tracker.fetchIssuesByIds(listOf("gh:454")) }.getOrThrow().single()

        val completed = runBlocking { tracker.completeIssue(issue) }.getOrThrow()

        assertEquals("gh:454", completed.id)
        assertEquals("completed", completed.description)
        assertEquals(listOf("complete 454"), native.writes)
    }

    private companion object {
        fun issue(
            id: String,
            identifier: String,
        ) = Issue(
            id = id,
            identifier = identifier,
            title = "t",
            state = "todo",
            priority = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = null,
            createdAt = null,
        )
    }
}
