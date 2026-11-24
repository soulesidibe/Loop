package loop.core.trackers

import kotlinx.coroutines.test.runTest
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerAdapter
import loop.api.TrackerError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-16, story 116: the `Blocked by <tracker>:<id>` line of an issue's description and the check that keeps the issue
 * blocked while a named blocker is not in a terminal state of its own tracker, fail closed for an unknown tracker and
 * an unreadable blocker, each blocker read once per check.
 */
class CrossTrackerBlockersTest {
    private fun refs(vararg texts: String): List<BlockerRef> =
        texts.map { BlockerRef(it.substringBefore(':'), it.substringAfter(':')) }

    @Test
    fun `the parser reads blocker lines and ignores prose and fenced lines`() {
        val table: List<Pair<String, List<BlockerRef>>> =
            listOf(
                "Blocked by gh:12" to refs("gh:12"),
                "Blocked by gh:12, local:m4-03" to refs("gh:12", "local:m4-03"),
                "Blocked by gh:12,local:m4-03 , lin:MKT-12" to refs("gh:12", "local:m4-03", "lin:MKT-12"),
                "## What\n\nSome text.\nBlocked by lin:MKT-12\nMore text." to refs("lin:MKT-12"),
                "Blocked by gh:12\r\n" to refs("gh:12"),
                "Blocked by gh:12  " to refs("gh:12"),
                // An unknown or misspelled tracker is still read, so the check can keep the issue blocked.
                "Blocked by Lin:MKT-12" to refs("Lin:MKT-12"),
                "blocked by gh:12" to refs("gh:12"),
                // Two lines add up, a blocker named twice is read once.
                "Blocked by gh:12\nBlocked by gh:12, gh:13" to refs("gh:12", "gh:13"),
                // A sentence's last period is not part of the id.
                "Blocked by gh:12." to refs("gh:12"),
                // Prose: a word without a tracker, other words after the items, another separator, not at column 0.
                "Blocked by bl-54 so the two never run side by side: both edit pull request delivery." to emptyList(),
                "Blocked by bl-54" to emptyList(),
                "Blocked by gh:12 until the release" to emptyList(),
                "Blocked by gh:12 and lin:MKT-12" to emptyList(),
                "Blocked by gh:12; lin:MKT-12" to emptyList(),
                "Blocked by" to emptyList(),
                "Blocked by " to emptyList(),
                " Blocked by gh:12" to emptyList(),
                "- Blocked by gh:12" to emptyList(),
                "> Blocked by gh:12" to emptyList(),
                "Blocked by: gh:12" to emptyList(),
                "Blocked by gh:" to emptyList(),
                "Blocked by :12" to emptyList(),
                "Blocked by gh:12,, lin:3" to emptyList(),
                "Blocked bygh:12" to emptyList(),
                "Not blocked by gh:12" to emptyList(),
                // Fenced code blocks, backticks and tildes, closed or not.
                "```\nBlocked by gh:12\n```" to emptyList(),
                "~~~markdown\nBlocked by gh:12\n~~~\nBlocked by gh:13" to refs("gh:13"),
                "````\n```\nBlocked by gh:12\n```\n````" to emptyList(),
                "```\nBlocked by gh:12" to emptyList(),
                "  ```\nBlocked by gh:12\n  ```\nBlocked by gh:14" to refs("gh:14"),
                "" to emptyList(),
            )
        for ((description, expected) in table) {
            assertEquals(expected, BlockerLines.parse(description), "description: \"$description\"")
        }
        assertEquals(emptyList(), BlockerLines.parse(null))
    }

    @Test
    fun `the prose Blocked by lines of Loop's own issues hold no blocker`() {
        // The lines of bl-61 and bl-48 as written in `.loop/issues/`.
        val bl61 =
            "## What\n\nMerge check lows.\n\n" +
                "Blocked by bl-54 so the two never run side by side: both edit pull request delivery and the merge check. " +
                "Done blocker m2-26 removed.\n\n## Comments\n\n" +
                "2026-11-18 soule: kept deferred in m3-29: merge check lows, blocked by bl-54, low.\n"
        val bl48 =
            "Blocked by bl-45 so the two never run side by side: both edit the tracker comment path, and bl-45 carries " +
                "the comment cap this issue relies on. Done blocker m2-23 removed.\n"
        assertEquals(emptyList(), BlockerLines.parse(bl61))
        assertEquals(emptyList(), BlockerLines.parse(bl48))
    }

    @Test
    fun `an issue with a prose Blocked by line keeps its eligibility and reads nothing`() =
        runTest {
            val issue = issue("local:bl-61", "Blocked by bl-54 so the two never run side by side: both edit pull request delivery.")
            val checked = CrossTrackerBlockers.check(listOf(issue), config, poll)
            assertEquals(listOf(issue), checked)
            assertTrue(checked.single().dispatchable)
            assertEquals(0, github.byIdCalls.size + local.byIdCalls.size)
        }

    @Test
    fun `an open blocker of another tracker blocks the issue, a closed one does not`() =
        runTest {
            github.issues["12"] = issue("12", state = "open")
            github.issues["13"] = issue("13", state = "closed")
            val blocked = issue("local:l-1", "Blocked by gh:12")
            val free = issue("local:l-2", "Blocked by gh:13")

            val checked = CrossTrackerBlockers.check(listOf(blocked, free), config, poll).associateBy { it.id }

            assertEquals("blocked_cross_tracker gh:12 (open)", checked.getValue("local:l-1").blockedReason)
            assertNull(checked.getValue("local:l-2").blockedReason)
        }

    @Test
    fun `a blocker of the issue's own tracker is judged by that tracker's terminal states`() =
        runTest {
            local.issues["m4-03"] = issue("m4-03", state = "canceled")
            local.issues["m4-04"] = issue("m4-04", state = "review")
            val free = issue("local:l-1", "Blocked by local:m4-03")
            val blocked = issue("local:l-2", "Blocked by local:m4-04")

            val checked = CrossTrackerBlockers.check(listOf(free, blocked), config, poll).associateBy { it.id }

            assertNull(checked.getValue("local:l-1").blockedReason)
            assertEquals("blocked_cross_tracker local:m4-04 (review)", checked.getValue("local:l-2").blockedReason)
        }

    @Test
    fun `an id whose tracker is not in the registry keeps the issue blocked, naming the tracker`() =
        runTest {
            github.issues["12"] = issue("12", state = "closed")
            val checked =
                CrossTrackerBlockers.check(
                    listOf(issue("local:l-1", "Blocked by lin:MKT-12"), issue("local:l-2", "Blocked by gh:12, Gh:12")),
                    config,
                    poll,
                ).associateBy { it.id }

            assertEquals("blocked_cross_tracker lin:MKT-12 (unknown tracker lin)", checked.getValue("local:l-1").blockedReason)
            assertEquals("blocked_cross_tracker Gh:12 (unknown tracker Gh)", checked.getValue("local:l-2").blockedReason)
            assertEquals(emptyList(), local.byIdCalls, "an unknown tracker is never read from another one")
        }

    @Test
    fun `a tracker with an adapter that the workflow in effect no longer has is unknown`() =
        runTest {
            github.issues["12"] = issue("12", state = "closed")
            val withoutGh = config.copy(extras = emptyList())
            val checked = CrossTrackerBlockers.check(listOf(issue("local:l-1", "Blocked by gh:12")), withoutGh, poll)
            assertEquals("blocked_cross_tracker gh:12 (unknown tracker gh)", checked.single().blockedReason)
            assertEquals(0, github.byIdCalls.size)
        }

    @Test
    fun `a single tracker project keeps an issue blocked by another tracker's id`() =
        runTest {
            val single = TrackersConfig(primary = localEntry)
            val lone = TrackerPoll("local", mapOf("local" to NamespacedTracker("local", "local", local)))
            local.issues["12"] = issue("12", state = "done")
            val checked = CrossTrackerBlockers.check(listOf(issue("local:l-1", "Blocked by gh:12")), single, lone)
            assertEquals("blocked_cross_tracker gh:12 (unknown tracker gh)", checked.single().blockedReason)
            assertEquals(0, local.byIdCalls.size, "the primary tracker never reads another tracker's id")
        }

    @Test
    fun `a blocker that cannot be read keeps the issue blocked with the reason`() =
        runTest {
            github.failure = TrackerError.Status(502, "Bad gateway")
            local.issues["m4-03"] = issue("m4-03", state = "done")
            val checked =
                CrossTrackerBlockers.check(
                    listOf(issue("local:l-1", "Blocked by gh:12"), issue("local:l-2", "Blocked by local:m4-03")),
                    config,
                    poll,
                ).associateBy { it.id }

            assertEquals(
                "blocked_cross_tracker gh:12 (unreadable: The tracker answered 502: Bad gateway)",
                checked.getValue("local:l-1").blockedReason,
            )
            assertNull(checked.getValue("local:l-2").blockedReason, "the other tracker's blockers are still read")
        }

    @Test
    fun `a blocker that is not found keeps the issue blocked`() =
        runTest {
            val checked = CrossTrackerBlockers.check(listOf(issue("local:l-1", "Blocked by gh:404")), config, poll)
            assertEquals("blocked_cross_tracker gh:404 (not found)", checked.single().blockedReason)
        }

    @Test
    fun `a throwing tracker keeps the issue blocked`() =
        runTest {
            github.thrown = IllegalStateException("socket closed")
            val checked = CrossTrackerBlockers.check(listOf(issue("local:l-1", "Blocked by gh:12")), config, poll)
            assertEquals("blocked_cross_tracker gh:12 (unreadable: socket closed)", checked.single().blockedReason)
        }

    @Test
    fun `every blocker that holds the issue is named`() =
        runTest {
            github.issues["12"] = issue("12", state = "open")
            github.issues["13"] = issue("13", state = "closed")
            val checked = CrossTrackerBlockers.check(listOf(issue("local:l-1", "Blocked by gh:12, gh:13, lin:7")), config, poll)
            assertEquals("blocked_cross_tracker gh:12 (open), lin:7 (unknown tracker lin)", checked.single().blockedReason)
        }

    @Test
    fun `each blocker is read once, whatever the number of issues naming it`() =
        runTest {
            github.issues["12"] = issue("12", state = "open")
            github.issues["13"] = issue("13", state = "closed")
            local.issues["m4-03"] = issue("m4-03", state = "done")
            val issues =
                (1..5).map { issue("local:l-$it", "Blocked by gh:12, gh:13\nBlocked by local:m4-03") } +
                    issue("gh:20", "Blocked by gh:12")

            val checked = CrossTrackerBlockers.check(issues, config, poll)

            assertTrue(checked.all { it.blockedReason == "blocked_cross_tracker gh:12 (open)" }, "$checked")
            assertEquals(listOf(listOf("12", "13")), github.byIdCalls, "one call for the tracker, each id once")
            assertEquals(listOf(listOf("m4-03")), local.byIdCalls)
        }

    @Test
    fun `an issue its own tracker already blocks keeps its reason and reads nothing`() =
        runTest {
            val native = issue("local:l-1", "Blocked by gh:12").copy(blockedReason = "blocked_by m1-02 (todo)")
            val checked = CrossTrackerBlockers.check(listOf(native), config, poll)
            assertEquals(listOf(native), checked)
            assertEquals(0, github.byIdCalls.size)
        }

    @Test
    fun `an issue with no blocker line is returned as is and nothing is read`() =
        runTest {
            val plain = issue("local:l-1", "## What\n\nWrite hello.txt.")
            val noDescription = issue("local:l-2", null)
            assertEquals(listOf(plain, noDescription), CrossTrackerBlockers.check(listOf(plain, noDescription), config, poll))
            assertEquals(0, github.byIdCalls.size + local.byIdCalls.size)
        }

    @Test
    fun `the poll applies the check with the workflow in effect`() =
        runTest {
            github.issues["12"] = issue("12", state = "open")
            val checked = poll.withCrossTrackerBlockers(listOf(issue("local:l-1", "Blocked by gh:12")), config)
            assertEquals("blocked_cross_tracker gh:12 (open)", checked.single().blockedReason)
        }

    private val localEntry =
        TrackerEntry(
            id = "local",
            kind = "files",
            dir = Path.of("."),
            settings = null,
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = emptyList(),
        )

    private val ghEntry =
        TrackerEntry(
            id = "gh",
            kind = "github",
            dir = Path.of("."),
            settings = null,
            activeStates = listOf("open"),
            terminalStates = listOf("closed"),
            requiredLabels = emptyList(),
        )

    private val config = TrackersConfig(primary = localEntry, extras = listOf(ghEntry))

    private val local = CountingTracker()
    private val github = CountingTracker()

    private val poll =
        TrackerPoll.of(TrackerRegistry.build(config) { entry -> if (entry.id == "local") local else github })

    private fun issue(
        id: String,
        description: String? = null,
        state: String = "todo",
    ): Issue =
        Issue(
            id = id,
            identifier = id.substringAfter(':'),
            title = "Issue $id",
            state = state,
            priority = null,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = description,
            createdAt = null,
        )

    /** A tracker that answers by id reads from [issues], native ids, and records each call's ids. */
    private class CountingTracker : TrackerAdapter {
        val issues = mutableMapOf<String, Issue>()
        val byIdCalls = mutableListOf<List<String>>()
        var failure: TrackerError? = null
        var thrown: Exception? = null

        override val definedStates: List<String> = listOf("todo", "done")

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = Result.success(emptyList())

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
            byIdCalls += ids
            thrown?.let { throw it }
            failure?.let { return Result.failure(it) }
            return Result.success(ids.mapNotNull { issues[it] })
        }

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
