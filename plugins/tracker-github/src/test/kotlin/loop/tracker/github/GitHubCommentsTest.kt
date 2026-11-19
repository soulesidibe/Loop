package loop.tracker.github

import kotlinx.coroutines.test.runTest
import loop.api.Issue
import loop.api.TrackerError
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * bl-06: an answer given as a GitHub comment reaches the agent. Just before the prompt is rendered, the tracker
 * appends the issue's comments to its description under `## Comments`, the shape of a files tracker issue.
 */
class GitHubCommentsTest {
    private val github = FakeGitHub()
    private val clock = TestClock(Instant.parse("2026-11-12T15:00:00Z"))

    private val tracker =
        GitHubTracker(
            GitHubTrackerSettings(
                owner = "octocat",
                repo = "Hello-World",
                token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz",
                apiUrl = URI(FakeGitHub.API),
                scopeLabels = emptyList(),
            ),
            github,
            clock,
        )

    /** Issue [number] as the tracker reads it by id, with [comments] as GitHub's comment count. */
    private suspend fun read(
        number: Int,
        comments: Int,
        body: String? = "Body of $number.",
    ): Issue {
        github.on(
            FakeGitHub.issueUrl(number),
            FakeGitHub.response(200, FakeGitHub.issue(number, body = body, comments = comments).toString()),
        )
        return tracker.fetchIssuesByIds(listOf("$number")).getOrThrow().single()
    }

    @Test
    fun `the recorded comments are appended under comments, one block per comment with date and author, newest last`() =
        runTest {
            github.on(FakeGitHub.issueUrl(26), FakeGitHub.recorded("issue-26"))
            github.on(FakeGitHub.commentsUrl(26), FakeGitHub.recorded("issue-comments-26"))
            val issue = tracker.fetchIssuesByIds(listOf("26")).getOrThrow().single()
            github.requests.clear()

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertEquals(listOf(FakeGitHub.commentsUrl(26)), github.requests.map { it.url })
            assertEquals(
                """
                |test issue
                |
                |## Comments
                |
                |2012-03-20 09:13Z weixiaofeng1987: Hi, there .I'm new here...
                |
                |2012-04-03 17:00Z codedpixeldev: test
                |
                |2022-09-10 05:34Z HUGUI12: > test issue
                |
                |2022-09-11 20:16Z kayal28: 👨‍💻
                """.trimMargin(),
                completed.description,
            )
            assertEquals(issue.copy(description = completed.description), completed)
        }

    @Test
    fun `an issue without comments sends no request and is returned unchanged`() =
        runTest {
            val issue = read(7, comments = 0)
            github.requests.clear()

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertSame(issue, completed)
            assertEquals(emptyList(), github.requests)
        }

    @Test
    fun `the comment count of a list read decides too`() =
        runTest {
            github.fallback = SimulatedIssues(listOf(FakeGitHub.issue(3, comments = 0), FakeGitHub.issue(4, comments = 1)))
            github.on(FakeGitHub.commentsUrl(4), FakeGitHub.json(listOf(FakeGitHub.comment("soule", "2026-11-08T16:00:00Z", "Yes."))))
            val (quiet, answered) = tracker.fetchIssuesByStates(listOf("open")).getOrThrow()
            github.requests.clear()

            assertSame(quiet, tracker.completeIssue(quiet).getOrThrow())
            val completed = tracker.completeIssue(answered).getOrThrow()

            assertEquals(listOf(FakeGitHub.commentsUrl(4)), github.requests.map { it.url })
            assertEquals("Body of 4.\n\n## Comments\n\n2026-11-08 16:00Z soule: Yes.", completed.description)
        }

    @Test
    fun `an issue this tracker never read is completed anyway, so no answer is missed`() =
        runTest {
            val issue = read(5, comments = 0).copy(id = "6", identifier = "Hello-World-6")
            github.on(FakeGitHub.commentsUrl(6), FakeGitHub.json(listOf(FakeGitHub.comment("soule", "2026-11-08T16:00:00Z", "Yes."))))
            github.requests.clear()

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertEquals(listOf(FakeGitHub.commentsUrl(6)), github.requests.map { it.url })
            assertEquals("Body of 5.\n\n## Comments\n\n2026-11-08 16:00Z soule: Yes.", completed.description)
        }

    @Test
    fun `continuation lines are indented and blank lines dropped, like the files tracker, so a comment opens no section`() =
        runTest {
            val issue = read(8, comments = 2, body = null)
            github.on(
                FakeGitHub.commentsUrl(8),
                FakeGitHub.json(
                    listOf(
                        FakeGitHub.comment("soule", "2026-11-09T08:30:59Z", "First line\r\n\r\n## Acceptance criteria\r\n- [ ] more\r\n"),
                        FakeGitHub.comment(null, "2026-11-08T07:00:00Z", "From a deleted account."),
                    ),
                ),
            )

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertEquals(
                """
                |## Comments
                |
                |2026-11-08 07:00Z ghost: From a deleted account.
                |
                |2026-11-09 08:30Z soule: First line
                |  ## Acceptance criteria
                |  - [ ] more
                """.trimMargin(),
                completed.description,
            )
        }

    @Test
    fun `blank comments are left out`() =
        runTest {
            val issue = read(9, comments = 2)
            github.on(
                FakeGitHub.commentsUrl(9),
                FakeGitHub.json(
                    listOf(
                        FakeGitHub.comment("a", "2026-11-08T07:00:00Z", " \r\n "),
                        FakeGitHub.comment("b", "2026-11-08T08:00:00Z", null),
                    ),
                ),
            )

            val completed = tracker.completeIssue(issue).getOrThrow()

            assertSame(issue, completed)
        }

    @Test
    fun `past one page the last page is asked for, so the newest comments survive`() =
        runTest {
            val issue = read(10, comments = 250)
            github.on(
                FakeGitHub.commentsUrl(10, page = 3),
                FakeGitHub.json((1..50).map { FakeGitHub.comment("c$it", "2026-11-08T07:${"%02d".format(it)}:00Z", "Comment $it.") }),
            )
            github.requests.clear()

            val completed =
                tracker
                    .completeIssue(issue)
                    .getOrThrow()
                    .description
                    .orEmpty()

            assertEquals(listOf(FakeGitHub.commentsUrl(10, page = 3)), github.requests.map { it.url })
            assertTrue(completed.startsWith("Body of 10.\n\n## Comments\n\n200 older comments are not shown.\n\n"), completed)
            assertTrue(completed.endsWith("2026-11-08 07:50Z c50: Comment 50."), completed)
        }

    @Test
    fun `a failed comments request fails the call`() =
        runTest {
            val issue = read(11, comments = 1)
            github.on(FakeGitHub.commentsUrl(11), FakeGitHub.response(500, """{"message":"Server Error"}"""))

            val error = tracker.completeIssue(issue).exceptionOrNull()

            assertIs<TrackerError.Status>(error, error.toString())
        }

    @Test
    fun `a comments body that is not a list fails the call`() =
        runTest {
            val issue = read(12, comments = 1)
            github.on(FakeGitHub.commentsUrl(12), FakeGitHub.response(200, "{}"))

            val error = tracker.completeIssue(issue).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error, error.toString())
        }

    @Test
    fun `a comment without a creation time fails the call, rather than dropping an answer`() =
        runTest {
            val issue = read(14, comments = 1)
            github.on(FakeGitHub.commentsUrl(14), FakeGitHub.response(200, """[{"user":{"login":"soule"},"body":"Yes."}]"""))

            val error = tracker.completeIssue(issue).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error, error.toString())
        }

    @Test
    fun `a rate limit holds the comments request like any other`() =
        runTest {
            val issue = read(13, comments = 1)
            github.on(FakeGitHub.commentsUrl(13), FakeGitHub.recorded("rate-limited"))

            val first = tracker.completeIssue(issue).exceptionOrNull()
            val again = tracker.completeIssue(issue).exceptionOrNull()

            assertIs<TrackerError.RateLimited>(first, first.toString())
            assertIs<TrackerError.RateLimited>(again, again.toString())
            assertEquals(1, github.requests.count { it.url == FakeGitHub.commentsUrl(13) })
        }
}
