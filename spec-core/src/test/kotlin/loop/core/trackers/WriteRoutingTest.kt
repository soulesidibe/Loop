package loop.core.trackers

import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * m4-14, story 114, bl-153: every tracker write the core makes goes to the tracker of the issue's namespaced id, never
 * to the primary by default, and the native adapter gets the current issue with its own id and its `native_ref`. A
 * write for an issue whose tracker is not in the registry fails naming the tracker id, and writes nothing anywhere.
 */
class WriteRoutingTest {
    /** A native adapter that records every write, the writes by issue with the `native_ref` they read. */
    private class Native : TrackerAdapter {
        val writes = mutableListOf<String>()

        override val definedStates = listOf("todo", "review", "done")

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

        override suspend fun updateLabels(
            issueId: String,
            add: List<String>,
            remove: List<String>,
        ): Result<Unit> = Result.success(Unit).also { writes += "labels $issueId $add" }

        override suspend fun moveIssue(
            issue: Issue,
            toState: String,
        ): Result<Unit> = Result.success(Unit).also { writes += "move ${issue.id} ${issue.nativeRef} $toState" }

        override suspend fun addComment(
            issue: Issue,
            comment: IssueComment,
        ): Result<Unit> = Result.success(Unit).also { writes += "comment ${issue.id} ${issue.nativeRef} ${comment.text}" }
    }

    /** A native adapter that writes by id only, as every M3 tracker does. */
    private class ById : TrackerAdapter {
        val writes = mutableListOf<String>()

        override val definedStates = listOf("todo", "review")

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

    private val files = ById()
    private val github = Native()

    private val config = TrackersConfig(primary = entry("local"), extras = listOf(entry("gh")))

    private val poll = TrackerPoll.of(TrackerRegistry.build(config) { if (it.id == "local") files else github })

    private val comment = IssueComment("agent", Instant.fromEpochSeconds(0), "hello")

    private val ghIssue = issue("gh:26", nativeRef = mapOf("number" to "26", "node_id" to "I_26"))
    private val localIssue = issue("local:l-1")

    @Test
    fun `a move and a comment by issue reach the issue's own tracker with its native id and native_ref`() {
        runBlocking {
            poll.moveIssue(ghIssue, "review").getOrThrow()
            poll.addComment(ghIssue, comment).getOrThrow()
            poll.moveIssue(localIssue, "review").getOrThrow()
            poll.addComment(localIssue, comment).getOrThrow()
        }

        assertEquals(listOf("move 26 {number=26, node_id=I_26} review", "comment 26 {number=26, node_id=I_26} hello"), github.writes)
        // A tracker that writes by id only gets them through its id versions.
        assertEquals(listOf("move l-1 review", "comment l-1 hello"), files.writes)
    }

    @Test
    fun `the writes by id are routed the same way, a bare id being the primary's`() {
        runBlocking {
            poll.moveIssue("gh:26", "review").getOrThrow()
            poll.addComment("l-1", comment).getOrThrow()
            poll.updateLabels("gh:26", add = listOf("needs-info"), remove = emptyList()).getOrThrow()
        }

        assertEquals(listOf("move 26 review", "labels 26 [needs-info]"), github.writes)
        assertEquals(listOf("comment l-1 hello"), files.writes)
    }

    @Test
    fun `a write for an issue whose tracker is not in the registry fails naming the tracker id and writes nothing`() {
        val unknown = issue("lin:MKT-12")
        val results =
            runBlocking {
                listOf(
                    poll.moveIssue(unknown, "review"),
                    poll.addComment(unknown, comment),
                    poll.moveIssue(unknown.id, "review"),
                    poll.addComment(unknown.id, comment),
                    poll.updateLabels(unknown.id, add = listOf("needs-info"), remove = emptyList()),
                )
            }

        results.forEach { result ->
            val error = assertIs<UnroutedWrite>(result.exceptionOrNull(), result.toString())
            assertEquals("lin", error.trackerId)
            assertContains(error.message.orEmpty(), "\"lin\"")
            assertContains(error.message.orEmpty(), "lin:MKT-12")
        }
        assertEquals(emptyList(), files.writes, "nothing is written on the primary")
        assertEquals(emptyList(), github.writes, "nothing is written on another tracker")
    }

    @Test
    fun `with a registry of one tracker, another tracker's issue fails naming its tracker`() {
        val native = Native()
        val single = TrackerPoll.of(TrackerRegistry.build(TrackersConfig(primary = entry("local"))) { native })

        val result = runBlocking { single.moveIssue(ghIssue, "review") }

        assertEquals("gh", assertIs<UnroutedWrite>(result.exceptionOrNull(), result.toString()).trackerId)
        assertEquals(emptyList(), native.writes)
    }

    @Test
    fun `a lone adapter that is not namespaced takes every id, as the orchestrator's tests hand ids over`() {
        val native = Native()
        val single = TrackerPoll.of(native, primary = "default")

        runBlocking {
            single.moveIssue(ghIssue, "review").getOrThrow()
            single.addComment("x:MT-1", comment).getOrThrow()
        }

        assertEquals(listOf("move gh:26 {number=26, node_id=I_26} review", "comment x:MT-1 hello"), native.writes)
    }

    @Test
    fun `the states of an issue's tracker are its own tracker's, the primary's for a tracker not in the registry`() {
        assertEquals(github.definedStates, poll.definedStatesOf("gh:26"))
        assertEquals(files.definedStates, poll.definedStatesOf("local:l-1"))
        assertEquals(files.definedStates, poll.definedStatesOf("l-1"))
        assertEquals(files.definedStates, poll.definedStatesOf("lin:MKT-12"))
    }

    private fun entry(id: String): TrackerEntry = TrackerEntry(id, "test", Path.of("."), null, listOf("todo"), listOf("done"), emptyList())

    private fun issue(
        id: String,
        nativeRef: Map<String, String>? = null,
    ): Issue = Issue(id, id, "Title of $id", "todo", null, emptyList(), emptyList(), null, null, nativeRef = nativeRef)
}
