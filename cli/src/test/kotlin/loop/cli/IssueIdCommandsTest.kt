package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.ToolRequest
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * m4-47: the commands and the agent tools take a bare id, the primary tracker's, or a namespaced id, which names its
 * tracker, and refuse one whose tracker is not in the registry. A project with no `trackers:` shows bare ids, a project
 * with `trackers:` shows namespaced ids, the primary's included. `loop trace` has its cases in [TraceCommandTest].
 */
class IssueIdCommandsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val operator = mapOf("USER" to "soule")

    /** Adds the extra `files` tracker `local` to the workflow, so ids are shown namespaced. */
    private fun withExtraTracker() {
        fixture.projectDir.resolve("other").createDirectories()
        fixture.writeWorkflow(extra = "trackers:\n  - id: local\n    kind: files\n    provider:\n      dir: ./other\n")
    }

    private fun project(): ProjectId = ProjectId(fixture.projectId())

    private fun store(block: suspend (SqliteEventStore) -> Unit) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { runBlocking { block(it) } }
    }

    /** t-1 waits for approval to move to review, as the store holds it since m4-12. */
    private fun waitForApproval(issueId: String = "default:t-1") =
        store {
            val wait =
                WaitingIssue(
                    project(),
                    issueId,
                    "t-1",
                    WaitReason.Approval,
                    AttemptId(ATTEMPT),
                    null,
                    Clock.System.now(),
                    mapOf("to" to "review"),
                )
            it.startWaiting(wait).getOrThrow()
        }

    private fun park(
        issueId: String = "default:t-1",
        identifier: String = "t-1",
    ) = store {
        it
            .park(ParkedIssue(project(), issueId, identifier, ParkReason("stalled"), "todo", setOf("ready-for-agent"), Clock.System.now()))
            .getOrThrow()
    }

    private fun command(vararg args: String): LoopResult =
        fixture.inProcess(*args, "--workflow", fixture.workflow.toString(), env = operator)

    @Test
    fun `m4-47 loop approve takes a bare id as the primary tracker's`() {
        fixture.issue("t-1")
        waitForApproval()

        val result = command("approve", "t-1")

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(result.stdout.startsWith("Approved t-1, waiting since "), result.toString())
    }

    @Test
    fun `m4-47 loop approve takes a namespaced id of the primary tracker`() {
        fixture.issue("t-1")
        waitForApproval()

        val result = command("approve", "default:t-1")

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(result.stdout.startsWith("Approved t-1, waiting since "), result.toString())
    }

    @Test
    fun `m4-47 with trackers, loop approve takes either form and shows the namespaced id`() {
        withExtraTracker()
        fixture.issue("t-1")
        waitForApproval()

        val bare = command("approve", "t-1")

        assertEquals(0, bare.exitCode, bare.toString())
        assertTrue(bare.stdout.startsWith("Approved default:t-1, waiting since "), bare.toString())
        val again = command("approve", "default:t-1")
        assertEquals(0, again.exitCode, again.toString())
        assertEquals("default:t-1 is already approved, Loop moves it to review on its next tick. Nothing changed.", again.stdout.trim())
    }

    @Test
    fun `m4-47 loop approve finds a waiting issue of an extra tracker by its namespaced id`() {
        withExtraTracker()
        waitForApproval("local:t-1")

        val other = command("approve", "t-1")
        assertEquals(2, other.exitCode, other.toString())

        val result = command("approve", "local:t-1")

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(result.stdout.startsWith("Approved local:t-1, waiting since "), result.toString())
    }

    @Test
    fun `m4-47 loop approve and loop resume refuse a namespaced id whose tracker is not in the registry, naming it`() {
        fixture.issue("t-1")
        waitForApproval()
        park()

        val approve = command("approve", "gh:t-1")
        val resume = command("resume", "gh:t-1")

        assertEquals(2, approve.exitCode, approve.toString())
        assertEquals("loop approve: no tracker \"gh\" in this project, its tracker is default. Nothing changed.", approve.stderr.trim())
        assertEquals(2, resume.exitCode, resume.toString())
        assertEquals("loop resume: no tracker \"gh\" in this project, its tracker is default. Nothing changed.", resume.stderr.trim())
        assertTrue(fixture.query("SELECT 1 FROM events WHERE kind IN ('approval_given', 'issue_unparked')").isEmpty())
    }

    @Test
    fun `m4-47 loop resume takes a bare or a namespaced id`() {
        fixture.issue("t-1")
        park()

        val bare = command("resume", "t-1")

        assertEquals(0, bare.exitCode, bare.toString())
        assertTrue(bare.stdout.startsWith("Resumed t-1, parked since "), bare.toString())
        park()
        val namespaced = command("resume", "default:t-1")
        assertEquals(0, namespaced.exitCode, namespaced.toString())
        assertTrue(namespaced.stdout.startsWith("Resumed t-1, parked since "), namespaced.toString())
    }

    @Test
    fun `m4-47 with trackers, loop resume shows the namespaced id and resumes an extra tracker's issue`() {
        withExtraTracker()
        fixture.issue("t-1")
        park()
        park("local:t-1")

        val primary = command("resume", "t-1")
        val extra = command("resume", "local:t-1")

        assertEquals(0, primary.exitCode, primary.toString())
        assertTrue(primary.stdout.startsWith("Resumed default:t-1, parked since "), primary.toString())
        assertEquals(0, extra.exitCode, extra.toString())
        assertTrue(extra.stdout.startsWith("Resumed local:t-1, parked since "), extra.toString())
    }

    @Test
    fun `m4-47 loop status shows bare ids with no trackers, namespaced ids with trackers`() {
        park()

        val bare = fixture.inProcess("status", fixture.workflow.toString())

        assertEquals(0, bare.exitCode, bare.toString())
        assertTrue(bare.stdout.lines().any { it.startsWith("parked    t-1  ") }, bare.toString())
        withExtraTracker()
        park("local:t-2", "t-2")
        val namespaced = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, namespaced.exitCode, namespaced.toString())
        assertTrue(namespaced.stdout.lines().any { it.startsWith("parked    default:t-1  ") }, namespaced.toString())
        assertTrue(namespaced.stdout.lines().any { it.startsWith("parked    local:t-2  ") }, namespaced.toString())
    }

    @Test
    fun `m4-47 loop show shows bare ids with no trackers, namespaced ids with trackers`() {
        val started = fixture.attemptStarted(ATTEMPT, "default:t-1").copy(issueIdentifier = "t-1")
        fixture.seed(started, started.copy(kind = "attempt_finished"))

        val bareCost = fixture.inProcess("show", ATTEMPT, "--cost")
        val bareEvents = fixture.inProcess("show", ATTEMPT, "--events")

        assertEquals("Attempt $ATTEMPT  issue t-1", bareCost.stdout.lines().first(), bareCost.toString())
        assertEquals(listOf("t-1", "t-1"), issueIds(bareEvents))
        withExtraTracker()
        val cost = fixture.inProcess("show", ATTEMPT, "--cost")
        val events = fixture.inProcess("show", ATTEMPT, "--events")
        assertEquals("Attempt $ATTEMPT  issue default:t-1", cost.stdout.lines().first(), cost.toString())
        assertEquals(listOf("default:t-1", "default:t-1"), issueIds(events))
    }

    @Test
    fun `m4-47 an agent tool without LOOP_TRACKER_ID takes its bare LOOP_ISSUE_ID as the primary tracker's`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "default:t-1").copy(issueIdentifier = "t-1"))

        val result = fixture.inProcess("tool", "move", "review", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals(listOf(ToolRequest.Move("review")), fixture.queuedRequests(ATTEMPT).map { assertIs<QueuedRequest.Valid>(it).request })
    }

    @Test
    fun `m4-47 an agent tool of an extra tracker's issue takes LOOP_TRACKER_ID as its tracker`() {
        withExtraTracker()
        fixture.seed(fixture.attemptStarted(ATTEMPT, "local:t-1").copy(issueIdentifier = "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1") + ("LOOP_TRACKER_ID" to "local")

        val result = fixture.inProcess("tool", "comment", "a question", cwd = root, env = env)

        assertEquals(0, result.exitCode, result.toString())
        val requested = fixture.query("SELECT issue_id FROM events WHERE kind = 'issue_comment_requested'").single()
        assertEquals("local:t-1", requested["issue_id"])
        // The same native id as the primary's is another issue.
        val primary = fixture.inProcess("tool", "comment", "a question", cwd = root, env = fixture.toolEnv(ATTEMPT, "t-1"))
        assertEquals(5, primary.exitCode, primary.toString())
    }

    @Test
    fun `m4-47 an agent tool whose LOOP_TRACKER_ID is not in the registry is refused, naming the tracker id`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "default:t-1").copy(issueIdentifier = "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1") + ("LOOP_TRACKER_ID" to "gh")

        val move = fixture.inProcess("tool", "move", "review", cwd = root, env = env)
        val comment = fixture.inProcess("tool", "comment", "a question", cwd = root, env = env)

        assertEquals(5, move.exitCode, move.toString())
        assertContains(move.stderr, "loop tool move: no tracker \"gh\" in this project, its tracker is default. Nothing queued.")
        assertEquals(5, comment.exitCode, comment.toString())
        assertContains(comment.stderr, "loop tool comment: no tracker \"gh\" in this project, its tracker is default. Nothing queued.")
        assertEquals(emptyList(), fixture.queuedRequests(ATTEMPT))
    }

    private fun issueIds(result: LoopResult): List<String> {
        assertEquals(0, result.exitCode, result.toString())
        return result.stdout
            .lines()
            .filter { it.isNotBlank() }
            .map {
                Json
                    .parseToJsonElement(it)
                    .jsonObject
                    .getValue("issue_id")
                    .jsonPrimitive.content
            }
    }

    private companion object {
        const val ATTEMPT = "7c1e9d2a-0000-4000-8000-000000000047"
    }
}
