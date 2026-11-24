package loop.core.delivery

import kotlinx.coroutines.test.runTest
import loop.api.DeliveryError
import loop.api.DeliveryExecutor
import loop.api.PullRequest
import loop.api.PullRequestState
import loop.api.TrackerError
import loop.core.orchestrator.FakeTracker
import loop.core.orchestrator.FixedClock
import loop.core.orchestrator.issue
import loop.core.verify.WorkspaceFingerprint
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m2-25: the pull request delivery of a verified result, in its order of separate steps: the fingerprint check, the
 * lookup of the pull request in every state, the push, the pull request opened or updated, the comment with its URL,
 * then, last and only when asked, the move to the handoff state. Every failure is either retried at the next tick or
 * recorded as `delivery_failed`.
 */
class PullRequestDeliveryTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val empty = WorkspaceFingerprint.EMPTY_HASH
    private val clean = "git:$sha:$empty:$empty"
    private val attempt = "0f3c9a2e-4b7d-4e1a-9c2b-1d2e3f4a5b6c"

    private val tracker = FakeTracker(listOf(issue("t-1", state = "in_progress")))
    private val executor = FakeExecutor()
    private val events = mutableListOf<Pair<String, Map<String, String>>>()

    private fun delivery(redact: (String) -> String = { it }) =
        PullRequestDelivery(executor, tracker, { redact(it) }, FixedClock()) { definition, data -> events += definition.name to data }

    private fun facts(
        fingerprint: String = clean,
        nativeRef: Map<String, String> = emptyMap(),
        commented: String? = null,
        judgmentCalls: Int = 2,
    ) = DeliveryFacts(
        issue = issue("t-1", state = "in_progress").copy(nativeRef = nativeRef),
        identifier = "t-1",
        attemptId = attempt,
        fingerprint = fingerprint,
        checks =
            listOf(
                "- check 0, ./gradlew build: passed with exit code 0 in 42.1s, evidence $attempt.evidence-1.log",
                "- hidden check 1: passed",
            ),
        judgmentCalls = judgmentCalls,
        workspaceKey = "t-1",
        commented = commented,
    )

    private fun kinds() = events.map { it.first }

    private val handoff = PullRequestDelivery.Handoff(from = "in_progress", to = "review", event = mapOf("approved" to "true"))

    private val url7 = "https://github.com/acme/widgets/pull/7"

    @Test
    fun `a verified commit is pushed to its loop branch, the pull request opened and its URL commented, in that order`() =
        runTest {
            val outcome = delivery().deliver(facts())

            val delivered = assertIs<DeliveryOutcome.Delivered>(outcome)
            assertEquals(PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true), delivered.pullRequest)
            assertEquals(listOf("find loop/t-1", "push $sha loop/t-1", "open loop/t-1 t-1: Title of t-1"), executor.calls)
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented"), kinds())
            assertEquals(mapOf("sha" to sha, "branch" to "loop/t-1"), events[0].second)
            assertEquals(mapOf("number" to "7", "url" to "https://github.com/acme/widgets/pull/7"), events[1].second)
            val (issueId, comment) = tracker.comments.single()
            assertEquals("id-t-1", issueId)
            assertEquals("loop", comment.author)
            assertEquals("Loop opened pull request #7 for this issue: https://github.com/acme/widgets/pull/7", comment.text)
        }

    @Test
    fun `m4-14 the comment and the move are made with the current issue, its native_ref included`() =
        runTest {
            val ref = mapOf("repo" to "acme/widgets", "number" to "12", "node_id" to "I_12")

            assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts(nativeRef = ref), handoff))

            assertEquals(listOf("id-t-1" to ref, "id-t-1" to ref), tracker.writtenIssues.map { it.id to it.nativeRef })
        }

    @Test
    fun `the body holds the verdict, the fingerprint, each check, the judgment calls and the issue link, built from facts only`() =
        runTest {
            delivery().deliver(facts())

            assertEquals(
                """
                |Loop verified the result of attempt 0f3c9a2e for t-1: every check passed.
                |
                |Verdict: verified
                |Fingerprint: $clean
                |Checks:
                |- check 0, ./gradlew build: passed with exit code 0 in 42.1s, evidence $attempt.evidence-1.log
                |- hidden check 1: passed
                |Judgment calls left to a human: 2
                |
                |Refs t-1
                """.trimMargin(),
                executor.bodies.single(),
            )
        }

    @Test
    fun `an issue of the same github repository is closed by the pull request, another one is only referenced`() =
        runTest {
            delivery().deliver(facts(nativeRef = mapOf("repo" to "Acme/Widgets", "number" to "26")))
            delivery().deliver(facts(nativeRef = mapOf("repo" to "acme/other", "number" to "26")))

            assertTrue(executor.bodies[0].endsWith("\n\nCloses #26"), executor.bodies[0])
            assertTrue(executor.bodies[1].endsWith("\n\nRefs t-1"), executor.bodies[1])
        }

    @Test
    fun `the title and the body are redacted`() =
        runTest {
            delivery { it.replace("Title", "[redacted]").replace("gradlew", "[redacted]") }.deliver(facts())

            assertTrue(executor.calls[2].endsWith("t-1: [redacted] of t-1"))
            assertTrue("gradlew" !in executor.bodies.single())
        }

    @Test
    fun `uncommitted verified changes deliver nothing and record delivery_failed`() =
        runTest {
            for (fingerprint in listOf("git:$sha:0000000000000000:$empty", "git:$sha:$empty:1111111111111111", "tree:abc", "unknown")) {
                events.clear()
                val outcome = assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts(fingerprint = fingerprint)))

                assertEquals("fingerprint", outcome.step)
                assertEquals(listOf("delivery_failed"), kinds())
                assertEquals("fingerprint", events.single().second["step"])
            }
            assertEquals(emptyList(), executor.calls)
            assertEquals(emptyList(), tracker.comments)
        }

    @Test
    fun `without a handoff the delivery stops after the comment and the issue stays where it is`() =
        runTest {
            val delivered = assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts(), handoff = null))

            assertFalse(delivered.moved)
            assertEquals(url7, delivered.commented)
            assertEquals(emptyList(), tracker.moves)
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented"), kinds())
        }

    @Test
    fun `with a handoff the issue is moved last, after the comment, and issue_moved is recorded`() =
        runTest {
            val delivered = assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts(), handoff))

            assertTrue(delivered.moved)
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented", "issue_moved"), kinds())
            assertEquals(
                mapOf("from" to "in_progress", "to" to "review", "by" to "host", "verified" to "true", "approved" to "true"),
                events.last().second,
            )
        }

    @Test
    fun `a failed move keeps what was done, retried on a server error, permanent on a refusal, and never opens a second pull request`() =
        runTest {
            tracker.moveFailure = TrackerError.Status(503, "unavailable")
            val retry = assertIs<DeliveryOutcome.Retry>(delivery().deliver(facts(), handoff))
            assertEquals("move", retry.step)
            assertEquals(url7, retry.commented)
            assertEquals(listOf("branch_pushed", "pull_request_opened", "issue_commented"), kinds())

            events.clear()
            tracker.moveFailure = TrackerError.Status(403, "forbidden")
            val failed = assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts(commented = retry.commented), handoff))
            assertEquals("move", failed.step)
            assertEquals(url7, failed.commented)
            assertEquals(listOf("branch_pushed", "pull_request_updated", "delivery_failed"), kinds())
            assertEquals("move", events.last().second["step"])
            assertEquals(1, executor.opened, "a second pull request was opened")
            assertEquals(1, tracker.comments.size, "the URL was commented twice")
        }

    @Test
    fun `a merged pull request at the verified commit counts as delivered, nothing is pushed or opened, the rest runs`() =
        runTest {
            executor.found = PullRequest(5, "https://github.com/acme/widgets/pull/5", false, PullRequestState.Merged, sha.uppercase())

            val delivered = assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts(), handoff))

            assertEquals(5, delivered.pullRequest.number)
            assertEquals(listOf("find loop/t-1"), executor.calls)
            assertEquals(listOf("pull_request_found", "issue_commented", "issue_moved"), kinds())
            assertEquals(
                mapOf("number" to "5", "url" to "https://github.com/acme/widgets/pull/5", "state" to "merged"),
                events.first().second,
            )
            assertEquals(
                "Loop found pull request #5 for this issue, already merged: https://github.com/acme/widgets/pull/5",
                tracker.comments
                    .single()
                    .second.text,
            )
            assertEquals(listOf("id-t-1" to "review"), tracker.moves)
        }

    @Test
    fun `an open pull request is updated, a closed one or one merged at another commit is history and a new one is opened`() =
        runTest {
            executor.found = PullRequest(4, "https://github.com/acme/widgets/pull/4", false, PullRequestState.Open, "abc")
            assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts()))
            assertEquals(listOf("find loop/t-1", "push $sha loop/t-1", "update 4 t-1: Title of t-1"), executor.calls)
            assertEquals("pull_request_updated", kinds()[1])

            for (found in listOf(
                PullRequest(3, "https://github.com/acme/widgets/pull/3", false, PullRequestState.Closed, sha),
                PullRequest(2, "https://github.com/acme/widgets/pull/2", false, PullRequestState.Merged, "f".repeat(40)),
                PullRequest(1, "https://github.com/acme/widgets/pull/1", false, PullRequestState.Merged, null),
            )) {
                executor.calls.clear()
                events.clear()
                executor.found = found
                assertIs<DeliveryOutcome.Delivered>(delivery().deliver(facts()))
                assertEquals(listOf("find loop/t-1", "push $sha loop/t-1", "open loop/t-1 t-1: Title of t-1"), executor.calls, "$found")
                assertEquals("pull_request_opened", kinds()[1])
            }
        }

    @Test
    fun `a lookup that fails is classified like the pull request step and pushes nothing`() =
        runTest {
            executor.findFailure = DeliveryError.Unavailable("GitHub did not answer")
            assertEquals("pull_request", assertIs<DeliveryOutcome.Retry>(delivery().deliver(facts())).step)
            executor.findFailure = DeliveryError.Rejected("GitHub answered 404")
            assertEquals("pull_request", assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts())).step)
            assertEquals(listOf("find loop/t-1", "find loop/t-1"), executor.calls)
        }

    @Test
    fun `a rejected push is permanent, a push that cannot reach the remote is retried, neither opens a pull request`() =
        runTest {
            executor.pushFailure = DeliveryError.Rejected("the branch loop/t-1 is at 999, not the verified commit $sha")
            val rejected = assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts()))
            assertEquals("push", rejected.step)
            assertEquals(listOf("delivery_failed"), kinds())
            assertEquals(
                mapOf("step" to "push", "reason" to "the branch loop/t-1 is at 999, not the verified commit $sha"),
                events[0].second,
            )

            events.clear()
            executor.pushFailure = DeliveryError.Unavailable("could not reach the remote")
            val retry = assertIs<DeliveryOutcome.Retry>(delivery().deliver(facts()))
            assertEquals("push", retry.step)
            assertEquals(emptyList(), kinds())
            assertTrue(executor.calls.none { it.startsWith("open") || it.startsWith("update") })
        }

    @Test
    fun `a 403 on the pull request is permanent, a network error is retried`() =
        runTest {
            executor.pullRequestFailure = DeliveryError.Rejected("GitHub answered 403: Resource not accessible")
            assertEquals("pull_request", assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts())).step)
            assertEquals(listOf("branch_pushed", "delivery_failed"), kinds())

            events.clear()
            executor.pullRequestFailure = DeliveryError.Unavailable("GitHub did not answer")
            assertIs<DeliveryOutcome.Retry>(delivery().deliver(facts()))
            assertEquals(listOf("branch_pushed"), kinds())
            assertEquals(emptyList(), tracker.comments)
        }

    @Test
    fun `an updated pull request whose URL was already commented is not commented again`() =
        runTest {
            executor.found = PullRequest(7, url7, false, PullRequestState.Open, sha)

            val outcome = delivery().deliver(facts(commented = "https://github.com/acme/widgets/pull/7"))

            assertIs<DeliveryOutcome.Delivered>(outcome)
            assertEquals(listOf("branch_pushed", "pull_request_updated"), kinds())
            assertEquals(emptyList(), tracker.comments)
        }

    @Test
    fun `a failed comment is retried on a server error and permanent on a refusal`() =
        runTest {
            tracker.commentFailure = TrackerError.Status(503, "unavailable")
            assertEquals("comment", assertIs<DeliveryOutcome.Retry>(delivery().deliver(facts())).step)
            tracker.commentFailure = TrackerError.Status(403, "forbidden")
            assertEquals("comment", assertIs<DeliveryOutcome.Failed>(delivery().deliver(facts())).step)
        }

    @Test
    fun `a body over the limit is refused before anything is pushed`() =
        runTest {
            val long = facts().copy(checks = listOf("x".repeat(PullRequestDelivery.MAX_BODY_CHARS)))

            assertEquals("body", assertIs<DeliveryOutcome.Failed>(delivery().deliver(long)).step)
            assertEquals(emptyList(), executor.calls)
        }

    @Test
    fun `failures are classified, only network, rate limit and server errors are retried`() {
        assertTrue(PullRequestDelivery.permanent(DeliveryError.Rejected("x")))
        assertTrue(!PullRequestDelivery.permanent(DeliveryError.Unavailable("x")))
        assertTrue(!PullRequestDelivery.permanent(TrackerError.Unavailable(java.io.IOException("x"))))
        assertTrue(!PullRequestDelivery.permanent(TrackerError.RateLimited(null)))
        assertTrue(!PullRequestDelivery.permanent(TrackerError.Status(502, "x")))
        assertTrue(PullRequestDelivery.permanent(TrackerError.Status(404, "x")))
        assertTrue(PullRequestDelivery.permanent(TrackerError.Rejected("x")))
        assertTrue(!PullRequestDelivery.permanent(java.io.IOException("x")))
    }

    @Test
    fun `the fingerprint of a clean git work tree names its commit, any uncommitted change does not`() {
        val repo = createTempDirectory("loop-pr-fingerprint")
        git(repo, "init", "-q")
        repo.resolve("a.txt").writeText("a\n")
        git(repo, "add", "a.txt")
        git(repo, "commit", "-q", "-m", "first")
        val head = git(repo, "rev-parse", "HEAD").trim()

        assertEquals(head, WorkspaceFingerprint.committedHead(WorkspaceFingerprint.of(repo).getOrThrow()))
        repo.resolve("a.txt").writeText("b\n")
        assertNull(WorkspaceFingerprint.committedHead(WorkspaceFingerprint.of(repo).getOrThrow()))
        git(repo, "checkout", "-q", "a.txt")
        repo.resolve("new.txt").writeText("new\n")
        assertNull(WorkspaceFingerprint.committedHead(WorkspaceFingerprint.of(repo).getOrThrow()))
    }

    /** Runs git in [dir] with the user's and the system's config left out, so the host never matters. */
    private fun git(
        dir: java.nio.file.Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment() +=
                        mapOf(
                            "GIT_CONFIG_GLOBAL" to "/dev/null",
                            "GIT_CONFIG_SYSTEM" to "/dev/null",
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_AUTHOR_NAME" to "Loop test",
                            "GIT_AUTHOR_EMAIL" to "loop@example.com",
                            "GIT_COMMITTER_NAME" to "Loop test",
                            "GIT_COMMITTER_EMAIL" to "loop@example.com",
                        )
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private class FakeExecutor : DeliveryExecutor {
        override val repository: String = "acme/widgets"
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var pushFailure: DeliveryError? = null
        var findFailure: DeliveryError? = null
        var pullRequestFailure: DeliveryError? = null
        var found: PullRequest? = null
        var opened = 0

        override suspend fun push(
            sha: String,
            branch: String,
        ): Result<Unit> {
            calls += "push $sha $branch"
            return pushFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun findPullRequest(branch: String): Result<PullRequest?> {
            calls += "find $branch"
            return findFailure?.let { Result.failure(it) } ?: Result.success(found)
        }

        override suspend fun openPullRequest(
            branch: String,
            title: String,
            body: String,
        ): Result<PullRequest> {
            calls += "open $branch $title"
            bodies += body
            pullRequestFailure?.let { return Result.failure(it) }
            opened++
            val pullRequest = PullRequest(7, "https://github.com/acme/widgets/pull/7", created = true, headSha = null)
            found = pullRequest.copy(created = false)
            return Result.success(pullRequest)
        }

        override suspend fun updatePullRequest(
            number: Long,
            title: String,
            body: String,
        ): Result<PullRequest> {
            calls += "update $number $title"
            bodies += body
            pullRequestFailure?.let { return Result.failure(it) }
            return Result.success(PullRequest(number, "https://github.com/acme/widgets/pull/$number", created = false))
        }
    }
}
