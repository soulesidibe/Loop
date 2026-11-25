package loop.tracker.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import loop.api.ActionsJob
import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.ActionsStep
import loop.api.DeliveryError
import loop.api.MergeMethod
import loop.api.Mergeability
import loop.api.PullRequest
import loop.api.PullRequestState
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m2-25: the pull request half of delivery against a fake GitHub. The pull request whose head is the branch and whose
 * base is the configured base is looked up in every state; an open one is updated, with none one is created against
 * the base. A 422 that says one already exists looks once more. Failures are classed as [DeliveryError.Rejected]
 * (permanent) or [DeliveryError.Unavailable].
 */
class GitHubPullRequestsTest {
    private val token = "ghp_m225PullToken0123456789abcdefghijklm"
    private val github = FakeGitHub()
    private val clock = TestClock(Instant.parse("2026-11-12T15:00:00Z"))

    @TempDir
    lateinit var projectDir: Path

    private val pullsUrl = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/pulls"
    private val lookupUrl = "$pullsUrl?head=octocat%3Aloop%2Fk1&base=main&state=all&per_page=100"

    private fun pullUrl(number: Long) = "$pullsUrl/$number"

    private fun delivery() =
        GitHubDelivery(
            owner = "octocat",
            repo = "Hello-World",
            token = token,
            projectDir = projectDir,
            apiUrl = URI(FakeGitHub.API),
            base = "main",
            env = emptyMap(),
            transport = github,
            clock = clock,
        )

    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private fun item(
        number: Long,
        state: String = "open",
        merged: Boolean = false,
        head: String = "loop/k1",
        base: String = "main",
    ) = JsonObject(
        mapOf(
            "number" to JsonPrimitive(number),
            "html_url" to JsonPrimitive("https://github.com/${FakeGitHub.REPO}/pull/$number"),
            "state" to JsonPrimitive(state),
            "merged_at" to if (merged) JsonPrimitive("2026-11-13T10:00:00Z") else kotlinx.serialization.json.JsonNull,
            "head" to JsonObject(mapOf("ref" to JsonPrimitive(head), "sha" to JsonPrimitive(sha))),
            "base" to JsonObject(mapOf("ref" to JsonPrimitive(base))),
        ),
    )

    private fun pull(
        number: Long,
        status: Int = 200,
    ) = FakeGitHub.response(status, item(number).toString())

    private fun pulls(vararg numbers: Long) = FakeGitHub.response(200, numbers.joinToString(",", "[", "]") { item(it).toString() })

    private fun items(vararg items: JsonObject) = FakeGitHub.response(200, items.joinToString(",", "[", "]"))

    /** The pull request step as the delivery runs it: the lookup, then an update of the open one or a new one. */
    private suspend fun GitHubDelivery.pullRequest(
        branch: String,
        title: String,
        body: String,
    ): Result<PullRequest> {
        val found = findPullRequest(branch).getOrElse { return Result.failure(it) }
        return if (found != null && found.state == PullRequestState.Open) {
            updatePullRequest(found.number, title, body)
        } else {
            openPullRequest(branch, title, body)
        }
    }

    private fun alreadyExists() =
        FakeGitHub.response(
            422,
            """{"message":"Validation Failed","errors":[{"resource":"PullRequest","code":"custom",""" +
                """"message":"A pull request already exists for octocat:loop/k1."}],"status":"422"}""",
        )

    private fun bodyOf(request: FakeGitHub.Request) = Json.parseToJsonElement(request.body!!) as JsonObject

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content

    @Test
    fun `with no open pull request for the branch, one is created against the base`() =
        runTest {
            github.on(lookupUrl, pulls())
            github.on("POST", pullsUrl, pull(7, status = 201))

            val result = delivery().pullRequest("loop/k1", "m2-25: deliver", "The evidence.")

            assertEquals(PullRequest(7, "https://github.com/${FakeGitHub.REPO}/pull/7", created = true, headSha = sha), result.getOrThrow())
            assertEquals(listOf("GET" to lookupUrl, "POST" to pullsUrl), github.requests.map { it.method to it.url })
            val post = github.requests[1]
            val body = bodyOf(post)
            assertEquals("m2-25: deliver", body.text("title"))
            assertEquals("loop/k1", body.text("head"))
            assertEquals("main", body.text("base"))
            assertEquals("The evidence.", body.text("body"))
            for (request in github.requests) {
                assertEquals("Bearer $token", request.headers["Authorization"])
                assertEquals(GitHubClient.API_VERSION, request.headers["X-GitHub-Api-Version"])
            }
            assertEquals("application/json", post.headers["Content-Type"])
        }

    @Test
    fun `an open pull request for the branch gets the new title and body, and none is created`() =
        runTest {
            github.on(lookupUrl, pulls(12))
            github.on("PATCH", pullUrl(12), pull(12))

            val result = delivery().pullRequest("loop/k1", "New title", "New body.")

            assertEquals(
                PullRequest(12, "https://github.com/${FakeGitHub.REPO}/pull/12", created = false, headSha = sha),
                result.getOrThrow(),
            )
            assertEquals(listOf("GET" to lookupUrl, "PATCH" to pullUrl(12)), github.requests.map { it.method to it.url })
            val body = bodyOf(github.requests[1])
            assertEquals(mapOf("title" to "New title", "body" to "New body."), body.mapValues { (it.value as JsonPrimitive).content })
            assertTrue(github.requests.none { it.method == "POST" })
        }

    @Test
    fun `a 422 saying the pull request already exists looks it up once more, then updates it`() =
        runTest {
            github.on(lookupUrl, pulls(), pulls(15))
            github.on("POST", pullsUrl, alreadyExists())
            github.on("PATCH", pullUrl(15), pull(15))

            val result = delivery().pullRequest("loop/k1", "Title", "Body.")

            assertEquals(
                PullRequest(15, "https://github.com/${FakeGitHub.REPO}/pull/15", created = false, headSha = sha),
                result.getOrThrow(),
            )
            assertEquals(
                listOf("GET" to lookupUrl, "POST" to pullsUrl, "GET" to lookupUrl, "PATCH" to pullUrl(15)),
                github.requests.map { it.method to it.url },
            )
        }

    @Test
    fun `a 422 saying it already exists while the lookup still finds none is permanent`() =
        runTest {
            github.on(lookupUrl, pulls())
            github.on("POST", pullsUrl, alreadyExists())

            val error = delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("already exists" in error.message!!, error.message)
            assertEquals(listOf("GET", "POST", "GET"), github.requests.map { it.method })
        }

    @Test
    fun `any other 422 is permanent and looks nothing up again`() =
        runTest {
            github.on(lookupUrl, pulls())
            github.on(
                "POST",
                pullsUrl,
                FakeGitHub.response(
                    422,
                    """{"message":"Validation Failed","errors":[{"resource":"PullRequest","code":"custom",""" +
                        """"message":"No commits between main and loop/k1"}]}""",
                ),
            )

            val error = delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue(error.permanent)
            assertTrue("No commits between main and loop/k1" in error.message!!, error.message)
            assertEquals(listOf("GET", "POST"), github.requests.map { it.method })
        }

    @Test
    fun `401, 403 and 404 answers are permanent`() =
        runTest {
            for (status in listOf(401, 403, 404)) {
                github.requests.clear()
                github.on(lookupUrl, FakeGitHub.response(status, """{"message":"Answer $status"}"""))

                val error = delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull()

                assertIs<DeliveryError.Rejected>(error, "status $status")
                assertTrue("Answer $status" in error.message!!, error.message)
                assertEquals(1, github.requests.size)
            }
            github.on(lookupUrl, pulls(3))
            github.on("PATCH", pullUrl(3), FakeGitHub.recorded("write-forbidden"))
            assertIs<DeliveryError.Rejected>(delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())
        }

    @Test
    fun `a 502 answer and an unreachable GitHub are unavailable, so the delivery is tried again`() =
        runTest {
            github.on(lookupUrl, FakeGitHub.response(502, "Bad Gateway"))
            val bad = delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(bad)
            assertFalse(bad.permanent)

            github.unreachable = true
            val unreachable = delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(unreachable)
            assertIs<IOException>(unreachable.cause?.cause)
        }

    @Test
    fun `a rate limit is unavailable and holds the next request`() =
        runTest {
            github.on(lookupUrl, FakeGitHub.recorded("rate-limited"))
            val delivery = delivery()

            assertIs<DeliveryError.Unavailable>(delivery.pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())
            assertIs<DeliveryError.Unavailable>(delivery.pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())
            assertEquals(1, github.requests.size, "the hold sends nothing")

            github.requests.clear()
            github.on(lookupUrl, FakeGitHub.recorded("secondary-rate-limited"))
            assertIs<DeliveryError.Unavailable>(delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())
        }

    @Test
    fun `a body over GitHub's limit, counted in code points, sends nothing`() =
        runTest {
            github.on(lookupUrl, pulls())
            github.on("POST", pullsUrl, pull(7, status = 201))
            val emoji = "😀"
            val atLimit = emoji.repeat(GitHubPullRequests.MAX_BODY_CHARS)

            val error = delivery().openPullRequest("loop/k1", "Title", atLimit + "x").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("65537" in error.message!!, error.message)
            assertIs<DeliveryError.Rejected>(delivery().updatePullRequest(7, "Title", atLimit + "x").exceptionOrNull())
            assertTrue(github.requests.isEmpty())
            assertTrue(delivery().openPullRequest("loop/k1", "Title", atLimit).isSuccess, "the limit itself is allowed")
        }

    @Test
    fun `a success that cannot be read as a pull request is permanent`() =
        runTest {
            github.on(lookupUrl, pulls())
            github.on("POST", pullsUrl, FakeGitHub.response(201, """{"number":"seven"}"""))
            assertIs<DeliveryError.Rejected>(delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())

            github.on(lookupUrl, FakeGitHub.response(200, "not json"))
            assertIs<DeliveryError.Rejected>(delivery().pullRequest("loop/k1", "Title", "Body.").exceptionOrNull())
        }

    @Test
    fun `the head in the lookup is encoded, a branch with a plus or a space included`() =
        runTest {
            val url = "$pullsUrl?head=octocat%3Aloop%2Fa%2Bb%20c&base=main&state=all&per_page=100"
            github.on(url, items(item(4, head = "loop/a+b c")))
            github.on("PATCH", pullUrl(4), pull(4))

            assertTrue(delivery().pullRequest("loop/a+b c", "Title", "Body.").isSuccess)
            assertEquals(url, github.requests.first().url)
        }

    @Test
    fun `the lookup finds the pull request of the branch in every state, the open one first, then the latest merged`() =
        runTest {
            github.on(
                lookupUrl,
                items(item(3, state = "closed"), item(5, state = "closed", merged = true), item(4, state = "closed", merged = true)),
            )
            assertEquals(
                PullRequest(5, "https://github.com/${FakeGitHub.REPO}/pull/5", false, PullRequestState.Merged, sha),
                delivery().findPullRequest("loop/k1").getOrThrow(),
            )

            github.on(lookupUrl, items(item(5, state = "closed", merged = true), item(8), item(3, state = "closed")))
            assertEquals(PullRequestState.Open, delivery().findPullRequest("loop/k1").getOrThrow()?.state)
            assertEquals(8L, delivery().findPullRequest("loop/k1").getOrThrow()?.number)

            github.on(lookupUrl, items(item(2, state = "closed"), item(3, state = "closed")))
            assertEquals(
                PullRequest(3, "https://github.com/${FakeGitHub.REPO}/pull/3", false, PullRequestState.Closed, sha),
                delivery().findPullRequest("loop/k1").getOrThrow(),
            )
            assertTrue(github.requests.all { it.url == lookupUrl })
        }

    @Test
    fun `the lookup keeps only the pull requests whose head is the branch and whose base is the configured base`() =
        runTest {
            github.on(lookupUrl, items(item(6, base = "release"), item(7, head = "loop/k10"), item(8, head = "other")))

            assertEquals(null, delivery().findPullRequest("loop/k1").getOrThrow())
        }

    @Test
    fun `a 422 saying it already exists while only a merged one is found is permanent`() =
        runTest {
            github.on(lookupUrl, items(), items(item(5, state = "closed", merged = true)))
            github.on("POST", pullsUrl, alreadyExists())

            val error = delivery().openPullRequest("loop/k1", "Title", "Body.").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertEquals(listOf("POST", "GET"), github.requests.map { it.method })
        }

    @Test
    fun `the repository is owner and name`() {
        assertEquals("octocat/Hello-World", delivery().repository)
    }

    private val mergeSha = "fedcba9876543210fedcba9876543210fedcba98"

    private fun mergeUrl(number: Long) = "${pullUrl(number)}/merge"

    private val baseRefUrl = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/git/ref/heads/main"

    private fun merged() = FakeGitHub.response(200, """{"sha":"$mergeSha","merged":true,"message":"Pull Request successfully merged"}""")

    @Test
    fun `a merge sends the head sha as a guard and the method, and returns the merge commit`() =
        runTest {
            github.on("PUT", mergeUrl(12), merged())

            val squashed = delivery().mergePullRequest(12, sha, MergeMethod.Squash)

            assertEquals(mergeSha, squashed.getOrThrow())
            assertEquals(listOf("PUT" to mergeUrl(12)), github.requests.map { it.method to it.url })
            val request = github.requests.single()
            assertEquals(mapOf("sha" to sha, "merge_method" to "squash"), bodyOf(request).mapValues { (it.value as JsonPrimitive).content })
            assertEquals("Bearer $token", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Content-Type"])

            github.requests.clear()
            assertEquals(mergeSha, delivery().mergePullRequest(12, sha, MergeMethod.Merge).getOrThrow())
            assertEquals("merge", bodyOf(github.requests.single()).text("merge_method"))
        }

    @Test
    fun `a merge refused with 403 is permanent and names the permissions the token needs`() =
        runTest {
            github.on("PUT", mergeUrl(12), FakeGitHub.response(403, """{"message":"Resource not accessible by personal access token"}"""))

            val error = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue(error.permanent)
            for (part in listOf("#12", "contents write", "pull requests write", "Resource not accessible by personal access token")) {
                assertTrue(part in error.message!!, "$part is missing: ${error.message}")
            }
            assertFalse(token in error.message!!)
        }

    @Test
    fun `a merge refused with 405 says the pull request is not mergeable, with 409 that the head moved`() =
        runTest {
            github.on("PUT", mergeUrl(12), FakeGitHub.response(405, """{"message":"Pull Request is not mergeable"}"""))
            val notMergeable = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()
            assertIs<DeliveryError.Rejected>(notMergeable)
            assertTrue("not mergeable" in notMergeable.message!!, notMergeable.message)

            github.on(
                "PUT",
                mergeUrl(12),
                FakeGitHub.response(409, """{"message":"Head branch was modified. Review and try the merge again."}"""),
            )
            val moved = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()
            assertIs<DeliveryError.Rejected>(moved)
            for (part in listOf("head moved", "sha guard", sha, "Head branch was modified")) {
                assertTrue(part in moved.message!!, "$part is missing: ${moved.message}")
            }
        }

    @Test
    fun `send back 5, a merge refused because a branch was modified is worth one more try, not mergeable is not`() =
        runTest {
            for ((status, message) in listOf(
                405 to "Base branch was modified. Review and try the merge again.",
                409 to "Head branch was modified. Review and try the merge again.",
            )) {
                github.on("PUT", mergeUrl(12), FakeGitHub.response(status, """{"message":"$message"}"""))
                val error = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()
                assertIs<DeliveryError.Rejected>(error)
                assertTrue(error.retryOnce, "$status")
                assertTrue(error.permanent)
                assertTrue(message.substringBefore(".") in error.message!!, error.message)
            }
            github.on("PUT", mergeUrl(12), FakeGitHub.response(405, """{"message":"Pull Request is not mergeable"}"""))
            val notMergeable = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()
            assertIs<DeliveryError.Rejected>(notMergeable)
            assertFalse(notMergeable.retryOnce)
            github.on("PUT", mergeUrl(12), FakeGitHub.response(403, """{"message":"Base branch was modified"}"""))
            val forbidden = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()
            assertIs<DeliveryError.Rejected>(forbidden)
            assertFalse(forbidden.retryOnce, "only a 405 or a 409 says a branch moved")
        }

    @Test
    fun `a merge refused with 401, 404 or 422 is permanent`() =
        runTest {
            for (status in listOf(401, 404, 422)) {
                github.on("PUT", mergeUrl(12), FakeGitHub.response(status, """{"message":"Answer $status"}"""))

                val error = delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull()

                assertIs<DeliveryError.Rejected>(error, "status $status")
                assertTrue("Answer $status" in error.message!!, error.message)
            }
        }

    @Test
    fun `a merge answered 502, rate limited or unreachable is unavailable`() =
        runTest {
            github.on("PUT", mergeUrl(12), FakeGitHub.response(502, "Bad Gateway"))
            assertIs<DeliveryError.Unavailable>(delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull())

            for (limit in listOf("rate-limited", "secondary-rate-limited")) {
                github.on("PUT", mergeUrl(12), FakeGitHub.recorded(limit))
                assertIs<DeliveryError.Unavailable>(delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull(), limit)
            }
            github.on(
                "PUT",
                mergeUrl(12),
                FakeGitHub.response(403, """{"message":"You have exceeded a secondary rate limit"}""", "retry-after" to "30"),
            )
            assertIs<DeliveryError.Unavailable>(delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull())

            github.unreachable = true
            assertIs<DeliveryError.Unavailable>(delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull())
        }

    @Test
    fun `a merge success without a merge commit, or a sha that is not a full commit id, is permanent`() =
        runTest {
            github.on("PUT", mergeUrl(12), FakeGitHub.response(200, """{"merged":true}"""))
            assertIs<DeliveryError.Rejected>(delivery().mergePullRequest(12, sha, MergeMethod.Merge).exceptionOrNull())

            github.requests.clear()
            assertIs<DeliveryError.Rejected>(delivery().mergePullRequest(12, "HEAD", MergeMethod.Merge).exceptionOrNull())
            assertTrue(github.requests.isEmpty(), "a merge without a full sha guard was sent")
        }

    @Test
    fun `the base tip is the sha of the base ref`() =
        runTest {
            github.on(
                baseRefUrl,
                FakeGitHub.response(200, """{"ref":"refs/heads/main","object":{"sha":"$mergeSha","type":"commit"}}"""),
            )

            assertEquals(mergeSha, delivery().baseTip().getOrThrow())
            assertEquals(listOf("GET" to baseRefUrl), github.requests.map { it.method to it.url })
        }

    @Test
    fun `a base tip that cannot be read, or another ref, is permanent`() =
        runTest {
            for (body in listOf(
                """{"ref":"refs/heads/main"}""",
                """{"ref":"refs/heads/main","object":{"sha":"short"}}""",
                """{"ref":"refs/heads/main-old","object":{"sha":"$mergeSha"}}""",
                "not json",
            )) {
                github.on(baseRefUrl, FakeGitHub.response(200, body))
                assertIs<DeliveryError.Rejected>(delivery().baseTip().exceptionOrNull(), body)
            }
        }

    @Test
    fun `a base tip answered 401, 403 or 404 is permanent, 502, a rate limit or unreachable is unavailable`() =
        runTest {
            for (status in listOf(401, 403, 404)) {
                github.on(baseRefUrl, FakeGitHub.response(status, """{"message":"Answer $status"}"""))
                val error = delivery().baseTip().exceptionOrNull()
                assertIs<DeliveryError.Rejected>(error, "status $status")
                assertTrue("Answer $status" in error.message!!, error.message)
            }
            github.on(baseRefUrl, FakeGitHub.response(502, "Bad Gateway"))
            assertIs<DeliveryError.Unavailable>(delivery().baseTip().exceptionOrNull())
            github.on(baseRefUrl, FakeGitHub.recorded("rate-limited"))
            assertIs<DeliveryError.Unavailable>(delivery().baseTip().exceptionOrNull())
            github.unreachable = true
            assertIs<DeliveryError.Unavailable>(delivery().baseTip().exceptionOrNull())
        }

    @Test
    fun `the base tip of a base with a slash keeps the slash in the ref path`() =
        runTest {
            val url = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/git/ref/heads/release/2026.11"
            github.on(url, FakeGitHub.response(200, """{"ref":"refs/heads/release/2026.11","object":{"sha":"$mergeSha"}}"""))
            val release =
                GitHubDelivery(
                    owner = "octocat",
                    repo = "Hello-World",
                    token = token,
                    projectDir = projectDir,
                    apiUrl = URI(FakeGitHub.API),
                    base = "release/2026.11",
                    env = emptyMap(),
                    transport = github,
                    clock = clock,
                )

            assertEquals(mergeSha, release.baseTip().getOrThrow())
            assertEquals(url, github.requests.single().url)
        }

    @Test
    fun `m2-28, mergeability reads mergeable and mergeable_state of the pull request`() =
        runTest {
            github.on(pullUrl(7), FakeGitHub.response(200, """{"number":7,"mergeable":true,"mergeable_state":"clean"}"""))
            assertEquals(Mergeability(true, "clean"), delivery().mergeability(7).getOrThrow())
            assertEquals(listOf("GET" to pullUrl(7)), github.requests.map { it.method to it.url })

            github.on(pullUrl(7), FakeGitHub.response(200, """{"number":7,"mergeable":null,"mergeable_state":"unknown"}"""))
            assertEquals(Mergeability(null, "unknown"), delivery().mergeability(7).getOrThrow())
            github.on(pullUrl(7), FakeGitHub.response(200, """{"number":7,"mergeable":false,"mergeable_state":"dirty"}"""))
            assertEquals(Mergeability(false, "dirty"), delivery().mergeability(7).getOrThrow())
        }

    @Test
    fun `m2-28, mergeability answered 404 or unreadable is permanent, 502 unavailable`() =
        runTest {
            github.on(pullUrl(7), FakeGitHub.response(404, """{"message":"Not Found"}"""))
            assertIs<DeliveryError.Rejected>(delivery().mergeability(7).exceptionOrNull())
            github.on(pullUrl(7), FakeGitHub.response(200, "not json"))
            assertIs<DeliveryError.Rejected>(delivery().mergeability(7).exceptionOrNull())
            github.on(pullUrl(7), FakeGitHub.response(502, "Bad Gateway"))
            assertIs<DeliveryError.Unavailable>(delivery().mergeability(7).exceptionOrNull())
        }

    private val runsUrl = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/actions/runs?head_sha=$sha&per_page=100"

    @Test
    fun `m3-15, the Actions runs listing reads each run of a commit with the fields the verdict and the comment need`() =
        runTest {
            github.on(runsUrl, FakeGitHub.recorded("actions-runs"))

            val listed = delivery().actionsRuns(sha).getOrThrow()

            assertEquals(listOf("GET" to runsUrl), github.requests.map { it.method to it.url })
            assertEquals(3, listed.totalCount)
            assertEquals(
                ActionsRun(
                    id = 30433642,
                    name = "build",
                    workflowId = 161335,
                    event = "push",
                    runNumber = 41,
                    runAttempt = 2,
                    status = "completed",
                    conclusion = "success",
                    url = "https://github.com/octocat/Hello-World/actions/runs/30433642",
                ),
                listed.runs.first(),
            )
            assertEquals(listOf("success", "failure", null), listed.runs.map { it.conclusion })
            assertEquals("in_progress", listed.runs.single { it.name == "lint" }.status)
        }

    @Test
    fun `m3-15, a listing with no run is empty, and a total above the page is kept for the verdict`() =
        runTest {
            github.on(runsUrl, FakeGitHub.response(200, """{"total_count":0,"workflow_runs":[]}"""))
            assertEquals(ActionsRuns(0, emptyList()), delivery().actionsRuns(sha).getOrThrow())
            github.on(runsUrl, FakeGitHub.response(200, """{"total_count":150,"workflow_runs":[]}"""))
            assertEquals(150, delivery().actionsRuns(sha).getOrThrow().totalCount)
        }

    @Test
    fun `m3-15, a 403 or a 404 on the runs listing is permanent and names Actions read`() =
        runTest {
            github.on(runsUrl, FakeGitHub.recorded("actions-forbidden"))
            val forbidden = assertIs<DeliveryError.Rejected>(delivery().actionsRuns(sha).exceptionOrNull())
            assertContains(forbidden.message.orEmpty(), "Actions: read")
            assertContains(forbidden.message.orEmpty(), "Resource not accessible by personal access token")
            github.on(runsUrl, FakeGitHub.response(404, """{"message":"Not Found"}"""))
            assertContains(
                assertIs<DeliveryError.Rejected>(delivery().actionsRuns(sha).exceptionOrNull()).message.orEmpty(),
                "Actions: read",
            )
        }

    @Test
    fun `m3-15, a runs listing keeps a total above the page, unreadable or refused fails, held or unreachable is unavailable`() =
        runTest {
            // Not all on the page: the total is kept, so the verdict reads the listing as pending, never a part as green.
            github.on(runsUrl, FakeGitHub.response(200, """{"total_count":101,"workflow_runs":[]}"""))
            val partial = delivery().actionsRuns(sha).getOrThrow()
            assertEquals(101, partial.totalCount)
            assertTrue(partial.totalCount > partial.runs.size)
            github.on(runsUrl, FakeGitHub.response(403, """{"message":"Resource not accessible by personal access token"}"""))
            assertIs<DeliveryError.Rejected>(delivery().actionsRuns(sha).exceptionOrNull())
            github.on(runsUrl, FakeGitHub.response(200, "not json"))
            assertIs<DeliveryError.Rejected>(delivery().actionsRuns(sha).exceptionOrNull())
            github.on(runsUrl, FakeGitHub.response(200, """{"total_count":1,"workflow_runs":[{"id":1}]}"""))
            assertIs<DeliveryError.Rejected>(delivery().actionsRuns(sha).exceptionOrNull())
            github.on(runsUrl, FakeGitHub.response(502, "Bad Gateway"))
            assertIs<DeliveryError.Unavailable>(delivery().actionsRuns(sha).exceptionOrNull())
            github.on(runsUrl, FakeGitHub.recorded("rate-limited"))
            assertIs<DeliveryError.Unavailable>(delivery().actionsRuns(sha).exceptionOrNull())
            github.unreachable = true
            assertIs<DeliveryError.Unavailable>(delivery().actionsRuns(sha).exceptionOrNull())
        }

    @Test
    fun `m3-15, one delivery keeps a rate limit hold across calls, so a held listing sends nothing until the reset`() =
        runTest {
            val reset = Instant.parse("2026-11-12T15:30:00Z")
            val spent =
                FakeGitHub.recorded("actions-runs").let {
                    it.copy(
                        headers =
                            it.headers +
                                mapOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("${reset.epochSeconds}")),
                    )
                }
            github.on(runsUrl, spent)
            val delivery = delivery()

            assertTrue(delivery.actionsRuns(sha).isSuccess)
            assertIs<DeliveryError.Unavailable>(delivery.actionsRuns(sha).exceptionOrNull())
            assertEquals(1, github.requests.size, "the held call sent nothing")
            clock.now = reset
            assertTrue(delivery.actionsRuns(sha).isSuccess)
            assertEquals(2, github.requests.size)
        }

    private val refusedSha = "1c495c0359f1fb41aef31782e7221ff699f6cb8c"
    private val refusedRunsUrl = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/actions/runs?head_sha=$refusedSha&per_page=100"

    private fun jobsUrl(run: Long) = "${FakeGitHub.API}/repos/${FakeGitHub.REPO}/actions/runs/$run/jobs?per_page=100"

    @Test
    fun `m4-07, the recorded run 43779352010 GitHub refused for billing lists as failed and its only job has zero steps`() =
        runTest {
            github.on(refusedRunsUrl, FakeGitHub.recorded("actions-runs-43779352010"))
            github.on(jobsUrl(43779352010), FakeGitHub.recorded("actions-run-43779352010-jobs"))

            val listed = delivery().actionsRuns(refusedSha).getOrThrow()
            val jobs = delivery().actionsJobs(43779352010).getOrThrow()

            val single = FakeGitHub.recordedJson("actions-run-43779352010").jsonObject
            val run = listed.runs.single()
            assertEquals(1, listed.totalCount)
            assertEquals(single.getValue("id").jsonPrimitive.long, run.id)
            assertEquals(single.getValue("conclusion").jsonPrimitive.content, run.conclusion)
            assertEquals(single.getValue("status").jsonPrimitive.content, run.status)
            assertEquals("failure", run.conclusion)
            assertEquals(ActionsJobs(1, listOf(ActionsJob(133012782555, "build", "completed", "failure", 0))), jobs)
            assertEquals(
                listOf("GET" to refusedRunsUrl, "GET" to jobsUrl(43779352010)),
                github.requests.map { it.method to it.url },
            )
        }

    @Test
    fun `m4-07, the jobs read counts the steps of each job and keeps the total`() =
        runTest {
            github.on(
                jobsUrl(5),
                FakeGitHub.response(
                    200,
                    """{"total_count":120,"jobs":[{"id":1,"name":"build","status":"completed","conclusion":"failure",""" +
                        """"steps":[{"name":"a"},{"name":"b"}]},{"id":2,"name":"lint","status":"completed","conclusion":null,"steps":[]}]}""",
                ),
            )

            assertEquals(
                ActionsJobs(
                    120,
                    listOf(
                        ActionsJob(1, "build", "completed", "failure", 2, listOf(ActionsStep("a", null), ActionsStep("b", null))),
                        ActionsJob(2, "lint", "completed", null, 0),
                    ),
                ),
                delivery().actionsJobs(5).getOrThrow(),
            )
        }

    @Test
    fun `m4-35, the jobs read keeps the name and conclusion of each step, in order, from the hand written red run`() =
        runTest {
            github.on(jobsUrl(30433700), FakeGitHub.recorded("actions-run-30433700-jobs"))

            val jobs = delivery().actionsJobs(30433700).getOrThrow()

            assertEquals(2, jobs.totalCount)
            val test = jobs.jobs.first()
            assertEquals("test" to "failure", test.name to test.conclusion)
            assertEquals(6, test.steps)
            assertEquals(
                listOf(
                    ActionsStep("Set up job", "success"),
                    ActionsStep("Run actions/checkout@v4", "success"),
                    ActionsStep("Run ./gradlew test", "failure"),
                    ActionsStep("Run ./gradlew ktlintCheck", "skipped"),
                    ActionsStep("Post Run actions/checkout@v4", "success"),
                    ActionsStep("Complete job", "success"),
                ),
                test.stepList,
            )
            assertEquals(listOf("success"), jobs.jobs[1].stepList.map { it.conclusion }.distinct())
        }

    @Test
    fun `m4-07, a 403 or a 404 on the jobs read is permanent and names Actions read`() =
        runTest {
            github.on(jobsUrl(5), FakeGitHub.recorded("actions-forbidden"))
            val forbidden = assertIs<DeliveryError.Rejected>(delivery().actionsJobs(5).exceptionOrNull())
            assertContains(forbidden.message.orEmpty(), "Actions: read")
            assertContains(forbidden.message.orEmpty(), "Resource not accessible by personal access token")
            github.on(jobsUrl(5), FakeGitHub.response(404, """{"message":"Not Found"}"""))
            assertContains(assertIs<DeliveryError.Rejected>(delivery().actionsJobs(5).exceptionOrNull()).message.orEmpty(), "Actions: read")
        }

    @Test
    fun `m4-07, a jobs read unreadable fails, held or unreachable is unavailable`() =
        runTest {
            github.on(jobsUrl(5), FakeGitHub.response(200, "not json"))
            assertIs<DeliveryError.Rejected>(delivery().actionsJobs(5).exceptionOrNull())
            github.on(jobsUrl(5), FakeGitHub.response(200, """{"total_count":1,"jobs":[{"id":1,"name":"build","status":"completed"}]}"""))
            assertIs<DeliveryError.Rejected>(delivery().actionsJobs(5).exceptionOrNull(), "a job without its steps")
            github.on(jobsUrl(5), FakeGitHub.response(502, "Bad Gateway"))
            assertIs<DeliveryError.Unavailable>(delivery().actionsJobs(5).exceptionOrNull())
            github.on(jobsUrl(5), FakeGitHub.recorded("rate-limited"))
            assertIs<DeliveryError.Unavailable>(delivery().actionsJobs(5).exceptionOrNull())
            github.unreachable = true
            assertIs<DeliveryError.Unavailable>(delivery().actionsJobs(5).exceptionOrNull())
        }
}
