package loop.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * m2-11: the label write is optional, a tracker that does not implement it fails it cleanly. bl-06: completing an
 * issue before its prompt is optional too, a tracker that does not implement it returns the issue unchanged. m4-14:
 * the move and the comment by the current issue fall back to the id versions, and an adapter that overrides them
 * reads the issue's `native_ref`.
 */
class TrackerAdapterDefaultsTest {
    /** The writes the adapters below were asked for. */
    private val writes = mutableListOf<String>()

    private val readOnly =
        object : TrackerAdapter {
            override val definedStates: List<String> = listOf("todo")

            override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = Result.success(emptyList())

            override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> = Result.success(emptyList())

            override suspend fun moveIssue(
                issueId: String,
                toState: String,
            ): Result<Unit> = Result.success(Unit).also { writes += "move $issueId $toState" }

            override suspend fun addComment(
                issueId: String,
                comment: IssueComment,
            ): Result<Unit> = Result.success(Unit).also { writes += "comment $issueId ${comment.text}" }
        }

    /** An adapter whose writes need a provider id beyond the issue id, which it reads from `native_ref`. */
    private val byNativeRef =
        object : TrackerAdapter by readOnly {
            override suspend fun moveIssue(
                issue: Issue,
                toState: String,
            ): Result<Unit> = Result.success(Unit).also { writes += "move ${issue.nativeRef?.get("item_id")} $toState" }

            override suspend fun addComment(
                issue: Issue,
                comment: IssueComment,
            ): Result<Unit> = Result.success(Unit).also { writes += "comment ${issue.nativeRef?.get("item_id")} ${comment.text}" }
        }

    private val issue =
        Issue(
            id = "a-1",
            identifier = "A-1",
            title = "Export",
            state = "todo",
            priority = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = "## What\n\nExport.",
            createdAt = null,
            nativeRef = mapOf("item_id" to "PVTI_1"),
        )

    private val comment = IssueComment("loop", Instant.fromEpochSeconds(0), "hello")

    @Test
    fun `a tracker without a label write fails it as unsupported`() {
        val result = runBlocking { readOnly.updateLabels("a-1", add = listOf("needs-info")) }

        val error = assertIs<TrackerError.Unsupported>(result.exceptionOrNull(), result.toString())
        assertContains(error.message.orEmpty(), "labels")
    }

    @Test
    fun `m4-33 a tracker without an issue create fails it as unsupported`() {
        val result = runBlocking { readOnly.createIssue("Follow up", "Body.", listOf("needs-triage")) }

        val error = assertIs<TrackerError.Unsupported>(result.exceptionOrNull(), result.toString())
        assertContains(error.message.orEmpty(), "issues")
        assertEquals(emptyList(), writes)
    }

    @Test
    fun `bl-06 a tracker without a prompt completion returns the issue unchanged`() {
        val result = runBlocking { readOnly.completeIssue(issue) }

        assertSame(issue, result.getOrThrow())
    }

    @Test
    fun `m4-14 a tracker that writes by id only gets the moves and comments by issue through its id versions`() {
        runBlocking {
            readOnly.moveIssue(issue, "review").getOrThrow()
            readOnly.addComment(issue, comment).getOrThrow()
        }

        assertEquals(listOf("move a-1 review", "comment a-1 hello"), writes)
    }

    @Test
    fun `m4-14 a tracker that writes by issue reads the current issue's native_ref`() {
        runBlocking {
            byNativeRef.moveIssue(issue, "review").getOrThrow()
            byNativeRef.addComment(issue, comment).getOrThrow()
        }

        assertEquals(listOf("move PVTI_1 review", "comment PVTI_1 hello"), writes)
    }
}
