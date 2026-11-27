package loop.core.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.HumanChannel
import loop.api.HumanEvent
import loop.api.HumanNotice
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.events.EventDefinition
import loop.core.events.HookFailed
import loop.core.events.HumanNotified
import loop.core.trackers.IssueIdDisplay
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.workspace.HookListener
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m4-30: the notices of a park and of an approval wait, through the channel and `hooks.on_human_needed`. One notice per
 * wait or park: a wait keeps the key of its notice in its data, so the same wait saved again on a later tick or read
 * back after a restart never notifies twice, and the wait a refused delivery or a fallen back auto merge leaves
 * notifies again.
 */
class HumanNoticesTest {
    private val folder: Path = createTempDirectory("loop-notices").toRealPath()
    private val since = Instant.parse("2026-11-25T10:00:00Z")
    private val project = ProjectId("p1")

    private val shown = mutableListOf<HumanNotice>()
    private val channel =
        object : HumanChannel {
            override fun notify(notice: HumanNotice): Result<Unit> {
                shown += notice
                return Result.success(Unit)
            }
        }

    private data class Recorded(
        val issueId: String,
        val identifier: String,
        val definition: EventDefinition,
        val data: Map<String, String>,
    )

    private val recorded = mutableListOf<Recorded>()
    private val warnings = mutableListOf<String>()
    private val hookRuns = mutableListOf<Pair<String, HookResult>>()

    private val recorder =
        object : NoticeRecorder {
            override suspend fun record(
                issueId: String,
                identifier: String,
                definition: EventDefinition,
                data: Map<String, String>,
            ) {
                recorded += Recorded(issueId, identifier, definition, data)
            }

            override fun hookListener(
                issueId: String,
                identifier: String,
            ): HookListener =
                object : HookListener {
                    override suspend fun finished(
                        script: String,
                        result: HookResult,
                    ) {
                        hookRuns += script to result
                    }
                }

            override suspend fun warn(
                issueId: String,
                identifier: String,
                reason: String,
            ) {
                warnings += reason
            }
        }

    private fun notices(
        hook: String? = null,
        timeoutMs: Long = 10_000,
        channel: HumanChannel = this.channel,
        display: IssueIdDisplay = IssueIdDisplay("default", listOf("default"), namespaced = false),
        redact: (String) -> String = { it },
    ) = HumanNotices(
        channel = channel,
        settings = { NoticeSettings(hook = hook, timeoutMs = timeoutMs, folder = folder, display = display, channel = "terminal") },
        env = mapOf("LOOP_PROJECT_ID" to project.value),
        recorder = recorder,
        redact = redact,
    )

    private fun approval(
        approvedBy: String? = null,
        pullRequest: String? = null,
    ): Wait.Approval =
        Wait.approval(
            projectId = project,
            issueId = "default:m4-30",
            identifier = "m4-30",
            attemptId = AttemptId("a1"),
            runId = RunId("r1"),
            since = since,
            to = "review",
            fingerprint = "git:abc",
            approvedBy = approvedBy,
            approvedAt = approvedBy?.let { since },
            pullRequest = pullRequest,
        )

    /** Saves through [notices] like a call site does, and returns what it saved. */
    private fun save(
        notices: HumanNotices,
        wait: Wait.Approval,
        event: HumanEvent = HumanEvent.ApprovalWaiting,
        reason: String = "a verified result waits for approval",
    ): Wait.Approval {
        var saved: Wait.Approval? = null
        runBlocking { notices.waiting(wait, event, reason) { saved = it } }
        return checkNotNull(saved) { "the call site never saved the wait" }
    }

    private fun park(at: Instant = since) =
        ParkedIssue(
            projectId = project,
            issueId = "default:m4-30",
            identifier = "m4-30",
            reason = ParkReason.MaxAttempts,
            state = "todo",
            labels = emptySet(),
            parkedAt = at,
        )

    @Test
    fun `a fresh approval wait notifies once, with the URL, and the saved wait keeps the key of its notice`() {
        val notices = notices()

        val saved = save(notices, approval(pullRequest = "https://github.com/o/r/pull/7"))

        val notice = shown.single()
        assertEquals(
            HumanNotice(
                "default:m4-30",
                "m4-30",
                HumanEvent.ApprovalWaiting,
                "a verified result waits for approval",
                "https://github.com/o/r/pull/7",
                since,
            ),
            notice,
        )
        assertEquals("loop approve m4-30", notice.nextCommand)
        assertEquals("default:m4-30|approval|$since", saved.toRecord().data[WaitKeys.HUMAN_NOTICE])
        val event = recorded.single { it.definition == HumanNotified }
        assertEquals("approval_waiting", event.data[HumanNotified.EVENT])
        assertEquals("terminal", event.data[HumanNotified.CHANNEL])
        assertEquals("loop approve m4-30", event.data[HumanNotified.COMMAND])
        assertEquals("m4-30", event.identifier)
    }

    @Test
    fun `m4-31 an ask wait notifies once with the question event, and saved again or restored it does not notify twice`() {
        val notices = notices()
        val ask = Wait.ask(project, "default:m4-30", "m4-30", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "product")
        var saved: Wait.Ask? = null

        runBlocking { notices.asking(ask, "the agent asks a product question") { saved = it } }
        val noticed = checkNotNull(saved)
        runBlocking { notices.asking(noticed) { saved = it } }
        runBlocking { notices().asking(Wait.of(noticed.toRecord()) as Wait.Ask) { saved = it } }

        val notice = shown.single()
        assertEquals(HumanEvent.Question, notice.event)
        assertEquals("the agent asks a product question", notice.reason)
        assertEquals("loop answer m4-30 \"<text>\"", notice.nextCommand)
        assertEquals("default:m4-30|ask|$since", noticed.toRecord().data[WaitKeys.HUMAN_NOTICE])
        assertEquals("question", recorded.single { it.definition == HumanNotified }.data[HumanNotified.EVENT])
    }

    @Test
    fun `the same wait saved again on a later tick does not notify twice`() {
        val notices = notices()
        val saved = save(notices, approval())

        save(notices, saved)
        save(notices, saved.commented("https://github.com/o/r/pull/7"))

        assertEquals(1, shown.size, shown.toString())
    }

    @Test
    fun `the same wait read back after a restart does not notify again`() {
        val saved = save(notices(), approval())
        val restored = Wait.of(saved.toRecord()) as Wait.Approval

        save(notices(), restored)

        assertEquals(1, shown.size, shown.toString())
    }

    @Test
    fun `a new wait of the same issue, with a new since, notifies again`() {
        val notices = notices()
        save(notices, approval())
        val later =
            WaitingIssue(project, "default:m4-30", "m4-30", WaitReason.Approval, null, null, since.plus(kotlin.time.Duration.parse("1h")))

        save(notices, Wait.of(later) as Wait.Approval)

        assertEquals(2, shown.size, shown.toString())
    }

    @Test
    fun `the unapproved wait after a delivery failure notifies again, though its since is the same`() {
        val notices = notices()
        val saved = save(notices, approval())
        val approved =
            Wait.of(
                saved.toRecord().copy(
                    data =
                        saved.toRecord().data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to since.toString()),
                ),
            ) as Wait.Approval

        save(notices, approved.unapproved(), HumanEvent.DeliveryFailed, "push: refused")

        assertEquals(listOf(HumanEvent.ApprovalWaiting, HumanEvent.DeliveryFailed), shown.map { it.event })
        assertEquals("push: refused", shown.last().reason)
    }

    @Test
    fun `bl-250 a save that loses to a human decision notifies nobody`() {
        val notices = notices(hook = "exit 0")
        val tried = mutableListOf<Wait.Approval>()

        runBlocking {
            notices.waitingIfSaved(approval(), HumanEvent.DeliveryFailed, "push: refused") { wait ->
                tried += wait
                false
            }
        }

        assertEquals(1, tried.size)
        assertEquals(emptyList(), shown)
        assertEquals(emptyList(), hookRuns)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `the approval wait a refused merge falls back to notifies again`() {
        val notices = notices()
        val saved = save(notices, approval())
        val merge = Wait.of(saved.toRecord().copy(reason = WaitReason.Merge)) as Wait.Merge

        val refused = merge.refused(since.plus(kotlin.time.Duration.parse("2h")))
        assertNull(refused.toRecord().data[WaitKeys.HUMAN_NOTICE])
        save(notices, refused, HumanEvent.DeliveryFailed, "merge: refused")

        assertEquals(2, shown.size, shown.toString())
    }

    @Test
    fun `an approved wait, the auto policy's, notifies nobody`() {
        save(notices(hook = "exit 0"), approval(approvedBy = "auto"))

        assertEquals(emptyList(), shown)
        assertEquals(emptyList(), hookRuns)
    }

    @Test
    fun `a park notifies once with the resume command`() {
        runBlocking { notices().parked(park(), "max_attempts: exit code 1") }

        val notice = shown.single()
        assertEquals(HumanEvent.Parked, notice.event)
        assertEquals("max_attempts: exit code 1", notice.reason)
        assertEquals(since, notice.at)
        assertEquals("loop resume m4-30", notice.nextCommand)
    }

    @Test
    fun `the notice names the issue as Loop shows it, so the next command works with several trackers`() {
        val display = IssueIdDisplay("default", listOf("default", "gh"), namespaced = true)

        runBlocking { notices(display = display).parked(park(), "max_attempts") }

        assertEquals("loop resume default:m4-30", shown.single().nextCommand)
    }

    @Test
    fun `the hook runs once in the project folder with the notice as JSON on stdin and as LOOP_NOTICE variables`() {
        val stdin = folder.resolve("stdin.json")
        val env = folder.resolve("env.txt")
        val hook = "cat > '$stdin'; pwd > '$env'; env | grep '^LOOP_' | sort >> '$env'"

        save(notices(hook = hook), approval(pullRequest = "https://github.com/o/r/pull/7"))

        val json = Json.parseToJsonElement(stdin.readText()).jsonObject
        assertEquals("default:m4-30", json["issue_id"]?.jsonPrimitive?.content)
        assertEquals("m4-30", json["identifier"]?.jsonPrimitive?.content)
        assertEquals("approval_waiting", json["event"]?.jsonPrimitive?.content)
        assertEquals("a verified result waits for approval", json["reason"]?.jsonPrimitive?.content)
        assertEquals("https://github.com/o/r/pull/7", json["url"]?.jsonPrimitive?.content)
        assertEquals(since.toString(), json["at"]?.jsonPrimitive?.content)
        assertEquals("loop approve m4-30", json["command"]?.jsonPrimitive?.content)
        val lines = env.readText().lines()
        assertEquals(folder.toString(), lines.first())
        listOf(
            "LOOP_NOTICE_ISSUE_ID=default:m4-30",
            "LOOP_NOTICE_IDENTIFIER=m4-30",
            "LOOP_NOTICE_EVENT=approval_waiting",
            "LOOP_NOTICE_REASON=a verified result waits for approval",
            "LOOP_NOTICE_URL=https://github.com/o/r/pull/7",
            "LOOP_NOTICE_AT=$since",
            "LOOP_NOTICE_COMMAND=loop approve m4-30",
            "LOOP_PROJECT_ID=p1",
        ).forEach { assertTrue(it in lines, "$it missing from $lines") }
        assertEquals(1, hookRuns.size)
        assertEquals(hook, hookRuns.single().first, "hook_finished names the operator's command, not Loop's wrapper")
        assertEquals(HookName.ON_HUMAN_NEEDED, hookRuns.single().second.hook)
        assertTrue(recorded.none { it.definition == HookFailed })
    }

    @Test
    fun `a failing hook records hook_failed and the call returns`() {
        save(notices(hook = "echo nope; exit 3"), approval())

        val failed = recorded.single { it.definition == HookFailed }
        assertEquals("on_human_needed", failed.data[HookFailed.HOOK])
        assertEquals("3", failed.data[HookFailed.EXIT_CODE])
        assertEquals("false", failed.data[HookFailed.TIMED_OUT])
        assertEquals("hook on_human_needed failed with exit code 3", failed.data[HookFailed.REASON])
        assertEquals(1, shown.size, "the channel was told before the hook ran")
    }

    @Test
    fun `a hanging hook is stopped at the hooks timeout, records hook_failed and the call returns`() {
        val started = System.nanoTime()

        save(notices(hook = "sleep 30", timeoutMs = 300), approval())

        assertTrue(System.nanoTime() - started < 20_000_000_000L, "the hook was not stopped at its timeout")
        val failed = recorded.single { it.definition == HookFailed }
        assertEquals("true", failed.data[HookFailed.TIMED_OUT])
        assertNull(failed.data[HookFailed.EXIT_CODE])
        assertEquals("hook on_human_needed timed out after 300 ms", failed.data[HookFailed.REASON])
    }

    @Test
    fun `a channel that fails is a warning, and the hook still runs`() {
        val broken =
            object : HumanChannel {
                override fun notify(notice: HumanNotice): Result<Unit> = Result.failure(IllegalStateException("stderr is closed"))
            }

        save(notices(hook = "exit 0", channel = broken), approval())

        assertEquals(listOf("cannot tell the human through the terminal channel: stderr is closed"), warnings)
        assertEquals(1, hookRuns.size)
        assertEquals("stderr is closed", recorded.single { it.definition == HumanNotified }.data[HumanNotified.CHANNEL_ERROR])
    }

    @Test
    fun `the reason and the URL are redacted before the channel, the event and the hook see them`() {
        val stdin = folder.resolve("stdin.json")

        save(
            notices(hook = "cat > '$stdin'", redact = { it.replace("ghp_secret", "[REDACTED]") }),
            approval(pullRequest = "https://x/ghp_secret"),
            reason = "push refused for ghp_secret",
        )

        assertEquals("push refused for [REDACTED]", shown.single().reason)
        assertEquals("https://x/[REDACTED]", shown.single().url)
        assertTrue("ghp_secret" !in stdin.readText(), stdin.readText())
        assertTrue(recorded.none { record -> record.data.values.any { "ghp_secret" in it } })
    }

    @Test
    fun `with no hook nothing runs, and a blank hook counts as none`() {
        save(notices(hook = null), approval())
        save(notices(hook = "  "), Wait.of(approval().toRecord().copy(issueId = "default:m4-31", identifier = "m4-31")) as Wait.Approval)

        assertEquals(2, shown.size)
        assertEquals(emptyList(), hookRuns)
        assertNotEquals(shown[0].issueId, shown[1].issueId)
    }
}
