package loop.api

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * m2-11: the label write is optional, a tracker that does not implement it fails it cleanly. bl-06: completing an
 * issue before its prompt is optional too, a tracker that does not implement it returns the issue unchanged.
 */
class TrackerAdapterDefaultsTest {
    private val readOnly =
        object : TrackerAdapter {
            override val definedStates: List<String> = listOf("todo")

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

    @Test
    fun `a tracker without a label write fails it as unsupported`() {
        val result = runBlocking { readOnly.updateLabels("a-1", add = listOf("needs-info")) }

        val error = assertIs<TrackerError.Unsupported>(result.exceptionOrNull(), result.toString())
        assertContains(error.message.orEmpty(), "labels")
    }

    @Test
    fun `bl-06 a tracker without a prompt completion returns the issue unchanged`() {
        val issue =
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
            )

        val result = runBlocking { readOnly.completeIssue(issue) }

        assertSame(issue, result.getOrThrow())
    }
}
