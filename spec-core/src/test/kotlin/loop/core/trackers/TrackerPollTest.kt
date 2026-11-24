package loop.core.trackers

import kotlinx.coroutines.runBlocking
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-13, story 113: each tracker is polled with its own active states and read with its own terminal states, a
 * failing tracker is reported and skipped while the others go on, by id reads and writes reach each issue's own
 * tracker, and each issue is judged by its own tracker's states and `required_labels`.
 */
class TrackerPollTest {
    /** A native adapter holding [issues] under their native ids, which records what it is asked. */
    private class Native(
        vararg issues: Issue,
    ) : TrackerAdapter {
        val issues = issues.toMutableList()
        val askedStates = mutableListOf<List<String>>()
        val askedIds = mutableListOf<List<String>>()
        val writes = mutableListOf<String>()
        var failure: Throwable? = null
        var thrown: Exception? = null

        override val definedStates = listOf("todo", "review", "done")

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
            askedStates += states
            thrown?.let { throw it }
            failure?.let { return Result.failure(it) }
            return Result.success(issues.filter { it.state in states })
        }

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
            askedIds += ids
            failure?.let { return Result.failure(it) }
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

    private val files = Native(issue("l-1", "todo"), issue("l-2", "done"), issue("l-3", "open"))
    private val github = Native(issue("26", "open"), issue("27", "closed"), issue("28", "todo"))

    private val config =
        TrackersConfig(
            primary = entry("local", active = listOf("todo"), terminal = listOf("done"), labels = listOf("ready-for-agent")),
            extras = listOf(entry("gh", active = listOf("open"), terminal = listOf("closed"), labels = listOf("gh-ready"))),
        )

    private val poll = TrackerPoll.of(TrackerRegistry.build(config) { if (it.id == "local") files else github })

    private val comment = IssueComment("loop", Instant.fromEpochSeconds(0), "hello")

    @Test
    fun `each tracker is polled with its own active states and their issues are merged, namespaced`() {
        val read = runBlocking { poll.poll(config) }

        assertEquals(listOf(listOf("todo")), files.askedStates)
        assertEquals(listOf(listOf("open")), github.askedStates)
        assertEquals(listOf("local:l-1", "gh:26"), read.issues.map { it.id })
        assertEquals(emptyMap(), read.failures)
    }

    @Test
    fun `a tracker that fails its poll is reported by its id and the other tracker's issues still come`() {
        github.failure = TrackerError.Status(503, "unavailable")

        val read = runBlocking { poll.poll(config) }

        assertEquals(listOf("local:l-1"), read.issues.map { it.id })
        assertEquals(listOf("gh"), read.failures.keys.toList())
        assertIs<TrackerError.Status>(read.failures.getValue("gh"))
        assertTrue(read.answered("local:l-9"), "the files tracker answered")
        assertFalse(read.answered("gh:26"), "the github tracker did not")
        assertFalse(read.nothingAnswered)

        files.failure = TrackerError.Status(500, "down")
        assertTrue(runBlocking { poll.poll(config) }.nothingAnswered, "with both failing the tick has nothing to go on")
    }

    @Test
    fun `an adapter that throws fails its own tracker only`() {
        files.thrown = IllegalStateException("disk gone")

        val read = runBlocking { poll.poll(config) }

        assertEquals(listOf("gh:26"), read.issues.map { it.id })
        assertEquals("disk gone", read.failures.getValue("local").message)
    }

    @Test
    fun `the terminal read asks each tracker for its own terminal states`() {
        val read = runBlocking { poll.terminal(config) }

        assertEquals(listOf(listOf("done")), files.askedStates)
        assertEquals(listOf(listOf("closed")), github.askedStates)
        assertEquals(listOf("local:l-2", "gh:27"), read.issues.map { it.id })
    }

    @Test
    fun `an adapter whose tracker is no longer in the workflow is not polled`() {
        val read = runBlocking { poll.poll(TrackersConfig(primary = config.primary)) }

        assertEquals(emptyList(), github.askedStates)
        assertEquals(listOf("local:l-1"), read.issues.map { it.id })
        assertEquals(emptyMap(), read.failures)
    }

    @Test
    fun `a by id read asks each tracker for its own issues only`() {
        val read = runBlocking { poll.readByIds(listOf("local:l-2", "gh:27", "gh:99", "l-3")) }

        // A bare id is the primary tracker's (m4-12).
        assertEquals(listOf(listOf("l-2", "l-3")), files.askedIds)
        assertEquals(listOf(listOf("27", "99")), github.askedIds)
        assertEquals(setOf("local:l-2", "local:l-3", "gh:27"), read.issues.map { it.id }.toSet())
    }

    @Test
    fun `a by id read whose tracker fails leaves only that tracker's ids unread`() {
        github.failure = TrackerError.Status(503, "unavailable")

        val read = runBlocking { poll.readByIds(listOf("local:l-1", "gh:26")) }

        assertEquals(listOf("local:l-1"), read.issues.map { it.id })
        assertTrue(read.answered("local:l-1"))
        assertFalse(read.answered("gh:26"))
        // As a TrackerAdapter the read is all or nothing, so a caller that cannot tell the trackers apart fails closed.
        val whole = runBlocking { poll.fetchIssuesByIds(listOf("local:l-1", "gh:26")) }
        assertIs<TrackerError.Status>(whole.exceptionOrNull())
        assertEquals(emptyList(), runBlocking { poll.fetchIssuesByIds(emptyList()) }.getOrThrow())
    }

    @Test
    fun `writes reach the issue's own tracker with its native id`() {
        runBlocking {
            poll.moveIssue("gh:26", "review").getOrThrow()
            poll.addComment("gh:26", comment).getOrThrow()
            poll.updateLabels("local:l-1", add = listOf("needs-info"), remove = emptyList()).getOrThrow()
            assertEquals("completed", poll.completeIssue(issue("26", "open").copy(id = "gh:26")).getOrThrow().description)
        }

        assertEquals(listOf("move 26 review", "comment 26 hello", "complete 26"), github.writes)
        assertEquals(listOf("labels l-1 [needs-info] []"), files.writes)
    }

    @Test
    fun `with one tracker every id goes to it unchanged, as the orchestrator's tests hand ids over`() {
        val native = Native(issue("MT-1", "todo"))
        val single = TrackerPoll.of(native, primary = "default")

        runBlocking { single.moveIssue("x:MT-1", "review").getOrThrow() }

        assertEquals(listOf("move x:MT-1 review"), native.writes)
        assertSame(single, TrackerPoll.of(single, primary = "other"))
        assertEquals(native.definedStates, single.definedStates)
    }

    @Test
    fun `each issue is judged by its own tracker's states and labels`() {
        val local = config.trackerOf("local:l-1")!!
        val gh = config.trackerOf("gh:26")!!

        assertEquals("local", config.trackerOf("l-1")!!.id, "a bare id is the primary's")
        assertNull(config.trackerOf("lin:MKT-12"), "no tracker of the workflow has it")
        assertTrue(local.isActive(" Todo ") && !local.isActive("open"))
        assertTrue(gh.isActive("open") && gh.isTerminal("Closed") && !gh.isTerminal("done"))
        assertTrue(gh.hasRequiredLabels(listOf("GH-ready", "bug")))
        assertFalse(gh.hasRequiredLabels(listOf("ready-for-agent")), "the primary's label is not the extra's")
        assertTrue(local.hasRequiredLabels(listOf("ready-for-agent")))
        // With no extra tracker every id is the primary's, whatever it holds.
        assertEquals("local", TrackersConfig(primary = config.primary).trackerOf("lin:MKT-12")!!.id)
    }

    private fun entry(
        id: String,
        active: List<String>,
        terminal: List<String>,
        labels: List<String>,
    ): TrackerEntry = TrackerEntry(id, "test", Path.of("."), null, active, terminal, labels)

    private fun issue(
        id: String,
        state: String,
    ): Issue = Issue(id, id, "Title of $id", state, null, emptyList(), emptyList(), null, null)
}
