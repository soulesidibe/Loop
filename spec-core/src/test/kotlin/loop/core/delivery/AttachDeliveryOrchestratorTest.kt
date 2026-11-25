package loop.core.delivery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerError
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.kinds.DeliverableFile
import loop.core.log.KeyValueLog
import loop.core.orchestrator.FakeHookRunner
import loop.core.orchestrator.FakeParkedIssues
import loop.core.orchestrator.FakeRunner
import loop.core.orchestrator.FakeSink
import loop.core.orchestrator.FakeTracker
import loop.core.orchestrator.FakeWaitingIssues
import loop.core.orchestrator.Orchestrator
import loop.core.orchestrator.SchedulerClock
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.core.orchestrator.issue
import loop.core.template.TemplateEngine
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.EvidenceFiles
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.KindConfig
import loop.core.workflow.KindDeliverable
import loop.core.workflow.KindMatch
import loop.core.workflow.KindsConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * m4-22, the attach delivery in the orchestrator: under `human` a verified content result is posted on the issue as one
 * comment, before the evidence summary, and waits as `Waiting(approval)` for the move to `on_delivered.move_to`; under
 * `auto` with `delivery.attach_auto.enabled` the comment and the move happen at once. Preflight refuses `auto` without the
 * opt in, `attach` without a deliverable and `attach_auto` without `attach`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachDeliveryOrchestratorTest {
    private val root: Path = createTempDirectory("loop-attach").toRealPath()

    private val code = KindConfig(listOf(CheckConfig.Command("code-check")), delivery = DeliveryConfig(DeliveryPolicy.Human))

    private fun content(delivery: DeliveryConfig) =
        KindConfig(
            listOf(CheckConfig.Command("content-check")),
            match = KindMatch(label = "posts"),
            deliverable = KindDeliverable(listOf("out/**/*.md")),
            delivery = delivery,
        )

    private val attachHuman = DeliveryConfig(DeliveryPolicy.Human, onDeliveredMoveTo = "done", attach = true)

    private fun workflow(delivery: DeliveryConfig) =
        WorkflowFile(
            config =
                WorkflowConfig(
                    agent = AgentConfig(maxTurns = 1),
                    kinds = KindsConfig(linkedMapOf("code" to code, "posts" to content(delivery))),
                ),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work on {{ issue.identifier }}",
        )

    private val tracker =
        FakeTracker(listOf(issue("p-1", labels = listOf("ready-for-agent", "posts")))).apply {
            definedStatesOverride = listOf("todo", "review", "done", "canceled")
        }

    /** Every check passes by default. */
    private var checks: CheckRunner = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1, output = "ok") }

    private val loopHome: Path = root.resolve("home")

    private val post = "# Post\n\n```kotlin\nval x = 1\n```\n"

    /** The files the agent writes in its workspace, by path. */
    private var writes: Map<String, String> = mapOf("out/post.md" to post, "notes.txt" to "not a deliverable\n")

    private val runner =
        FakeRunner().apply {
            onCall = { ctx ->
                writes.forEach { (path, text) ->
                    ctx.workspace
                        .resolve(path)
                        .also { it.parent.createDirectories() }
                        .writeText(text)
                }
                ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
            }
        }

    private fun TestScope.orchestrator(
        workflow: WorkflowFile,
        waiting: FakeWaitingIssues,
        sink: FakeSink = FakeSink(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = runner,
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, FakeHookRunner()),
            newId = { "a${++next}" },
            runId = RunId("r1"),
            projectId = PROJECT,
            launcher = root.resolve("install/bin/loop"),
            loopHome = loopHome,
            hostPath = "/usr/bin:/bin",
            parked = FakeParkedIssues(),
            redactor = SecretRedactor(),
            secretsOf = { emptyMap() },
            checkRunnerFor = { checks },
            waiting = waiting,
            evidence = EvidenceFiles(loopHome, Redactor.NONE),
            deliveryFor = { null },
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun FakeSink.commented(): List<Map<String, String>> = events.filter { it.kind == "issue_commented" }.map { it.data }

    @Test
    fun `under human the deliverable is posted as one comment before the evidence summary, and the issue waits for approval`() =
        runTest {
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()

            orchestrator(workflow(attachHuman), waiting, sink).tick()

            val comments = tracker.comments.map { it.second }
            assertEquals(2, comments.size, comments.toString())
            val deliverable = comments.first()
            assertEquals("loop", deliverable.author)
            assertEquals(
                "Loop attached the deliverable of p-1, verified in attempt a1: 1 file.\n\n" +
                    "`out/post.md`, ${post.length} bytes:\n\n````\n$post````",
                deliverable.text,
            )
            assertFalse(comments.last().text.contains("Loop attached"), "the evidence summary comes last")
            assertEquals(listOf("deliverable", "evidence_summary"), sink.commented().map { it["reason"] })
            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Approval, wait.reason)
            assertEquals("done", wait.data["to"], "the approval moves the issue to on_delivered.move_to")
            assertEquals(emptyList(), tracker.moves)
        }

    @Test
    fun `an approved attach wait moves the issue to on_delivered move_to, with no second comment`() =
        runTest {
            val waiting = FakeWaitingIssues()
            val orchestrator = orchestrator(workflow(attachHuman), waiting)
            orchestrator.tick()
            val wait = waiting.records.values.single()
            waiting.records[PROJECT.value to wait.issueId] =
                wait.copy(data = wait.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to "2026-11-25T10:00:00Z"))

            orchestrator.tick()

            assertEquals(listOf("id-p-1" to "done"), tracker.moves)
            assertTrue(waiting.records.isEmpty(), waiting.records.toString())
            assertEquals(2, tracker.comments.size, "the deliverable is posted once, before the approval")
        }

    @Test
    fun `without on_delivered the approval wait keeps the handoff state the agent asked for`() =
        runTest {
            val waiting = FakeWaitingIssues()

            orchestrator(workflow(DeliveryConfig(DeliveryPolicy.Human, attach = true)), waiting).tick()

            assertEquals(
                "review",
                waiting.records.values
                    .single()
                    .data["to"],
            )
            assertContains(
                tracker.comments
                    .first()
                    .second.text,
                "Loop attached the deliverable of p-1",
            )
        }

    @Test
    fun `under auto with attach_auto enabled the comment and the move happen at once, with no approval`() =
        runTest {
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val auto = DeliveryConfig(DeliveryPolicy.Auto, onDeliveredMoveTo = "done", attach = true, attachAutoEnabled = true)

            orchestrator(workflow(auto), waiting, sink).tick()

            assertEquals(listOf("deliverable"), sink.commented().map { it["reason"] })
            assertContains(
                tracker.comments
                    .single()
                    .second.text,
                "````\n$post````",
            )
            assertEquals(listOf("id-p-1" to "done"), tracker.moves)
            assertTrue(waiting.records.isEmpty(), waiting.records.toString())
        }

    @Test
    fun `a file a check changed after the listing is listed by its verified digest, never inlined`() =
        runTest {
            checks =
                CheckRunner { _, cwd, _ ->
                    cwd.resolve("out/post.md").writeText("# Rewritten by a check\n")
                    CheckResult(0, timedOut = false, durationMs = 1, output = "ok")
                }

            orchestrator(workflow(attachHuman), FakeWaitingIssues()).tick()

            val body =
                tracker.comments
                    .first()
                    .second.text
            assertFalse(body.contains("Rewritten"), body)
            assertContains(body, "- `out/post.md`, ${post.length} bytes, sha256 ${sha256(post)}, changed since its verification")
        }

    @Test
    fun `the deliverable comment is redacted like every Loop comment`() =
        runTest {
            val token = "ghp_m422AttachToken0123456789abcdefghijklmn"
            writes = mapOf("out/post.md" to "# Post\n\ntoken $token\n")

            orchestrator(workflow(attachHuman), FakeWaitingIssues()).tick()

            val body =
                tracker.comments
                    .first()
                    .second.text
            assertContains(body, "Loop attached the deliverable of p-1")
            assertFalse(body.contains(token), body)
        }

    // The send-back of pull request #56: under auto, the issue moves only once its whole deliverable is posted.

    private val attachAuto = DeliveryConfig(DeliveryPolicy.Auto, onDeliveredMoveTo = "done", attach = true, attachAutoEnabled = true)

    /** The agent queues a move and writes a `done` result file, so one drain submits two results. */
    private fun submitTwice() {
        runner.onCall = { ctx ->
            writes.forEach { (path, text) ->
                ctx.workspace
                    .resolve(path)
                    .also { it.parent.createDirectories() }
                    .writeText(text)
            }
            ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
            ctx.workspace
                .resolve(".loop/result.json")
                .also { it.parent.createDirectories() }
                .writeText("{\"status\": \"done\"}\n")
        }
    }

    private fun summary(): String =
        tracker.comments
            .map { it.second.text }
            .single { it.startsWith("Loop verified the result") }

    @Test
    fun `under auto a second result in the same drain never moves the issue when the deliverable comment failed`() =
        runTest {
            submitTwice()
            tracker.commentFailure = TrackerError.Unavailable(IOException("comments are closed"))
            val sink = FakeSink()

            orchestrator(workflow(attachAuto), FakeWaitingIssues(), sink).tick()

            assertEquals(2, sink.events.count { it.kind == "result_submitted" }, "both results were submitted")
            assertEquals(emptyList(), tracker.moves)
            assertEquals(emptyList(), sink.commented())
        }

    @Test
    fun `under auto a file over the cap falls back to the approval wait, so the workspace stays`() =
        runTest {
            writes = mapOf("out/post.md" to post, "out/big.md" to "a".repeat(70_000))
            val waiting = FakeWaitingIssues()

            orchestrator(workflow(attachAuto), waiting).tick()

            assertEquals(emptyList(), tracker.moves)
            val wait = waiting.records.values.single()
            assertEquals(WaitReason.Approval, wait.reason)
            assertEquals("done", wait.data["to"])
            val deliverable =
                tracker.comments
                    .first()
                    .second.text
            assertContains(deliverable, "- `out/big.md`, 70000 bytes")
            assertContains(summary(), "1 deliverable file is only listed, not inlined, so Loop did not move it on its own.")
            assertContains(summary(), "The delivery policy is auto: p-1 moves to done once an operator approves it.")
        }

    @Test
    fun `under auto a file a check changed falls back to the approval wait`() =
        runTest {
            checks =
                CheckRunner { _, cwd, _ ->
                    cwd.resolve("out/post.md").writeText("# Rewritten by a check\n")
                    CheckResult(0, timedOut = false, durationMs = 1, output = "ok")
                }
            val waiting = FakeWaitingIssues()

            orchestrator(workflow(attachAuto), waiting).tick()

            assertEquals(emptyList(), tracker.moves)
            assertEquals(
                WaitReason.Approval,
                waiting.records.values
                    .single()
                    .reason,
            )
            assertContains(
                tracker.comments
                    .first()
                    .second.text,
                "changed since its verification",
            )
        }

    @Test
    fun `under human a failed deliverable comment is named in the evidence summary, and the wait starts`() =
        runTest {
            tracker.commentFailureFor = { comment ->
                if (comment.text.startsWith("Loop attached")) TrackerError.Unavailable(IOException("comments are closed")) else null
            }
            val waiting = FakeWaitingIssues()

            orchestrator(workflow(attachHuman), waiting).tick()

            assertEquals(
                WaitReason.Approval,
                waiting.records.values
                    .single()
                    .reason,
            )
            assertContains(summary(), "The deliverable could not be posted: cannot post the deliverable: ")
            assertContains(summary(), "comments are closed")
        }

    @Test
    fun `attach under auto without the opt in waits for approval, even past the loader`() =
        runTest {
            val waiting = FakeWaitingIssues()

            orchestrator(workflow(attachAuto.copy(attachAutoEnabled = false)), waiting).tick()

            assertEquals(emptyList(), tracker.moves)
            assertEquals(
                WaitReason.Approval,
                waiting.records.values
                    .single()
                    .reason,
            )
            assertContains(summary(), "The delivery policy is human")
        }

    @Test
    fun `under auto a move that failed after the post never posts the same deliverable twice`() =
        runTest {
            submitTwice()
            tracker.moveFailure = TrackerError.Status(503, "unavailable")
            val sink = FakeSink()

            orchestrator(workflow(attachAuto), FakeWaitingIssues(), sink).tick()

            assertEquals(2, sink.events.count { it.kind == "result_submitted" }, "both results were submitted")
            assertEquals(listOf("deliverable"), sink.commented().map { it["reason"] })
            assertEquals(1, tracker.comments.size)
        }

    @Test
    fun `the cap applies to the redacted comment, a path that redaction makes longer included`() =
        runTest {
            val workspace = root.resolve("prepare").createDirectories()
            val text = "x".repeat(AttachComment.MAX_CHARS - 200)
            workspace.resolve("out").createDirectories()
            workspace.resolve("out/KEY.md").writeText(text)
            val file = DeliverableFile("out/KEY.md", text.length.toLong(), sha256(text))
            val marker = "[redacted:" + "N".repeat(500) + "]"
            val delivery =
                AttachDelivery(tracker, { it.replace("KEY", marker) }, SchedulerClock(testScheduler)) { _, _ -> }

            val prepared = delivery.prepare("p-1", "a1", workspace, listOf(file), evidence = true)

            assertIs<AttachDelivery.Prepared.Comment>(prepared)
            assertTrue(prepared.text.length <= AttachComment.MAX_CHARS, "${prepared.text.length}")
            assertEquals(1, prepared.listed)
            assertFalse(prepared.text.contains("KEY"), prepared.text.take(300))
        }

    // The loader and preflight.

    private fun front(kinds: String): Path {
        val folder = root.resolve("preflight").createDirectories()
        return folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |$kinds
                |---
                |Work on {{ issue.identifier }}
                """.trimMargin(),
            )
        }
    }

    private fun preflight(kinds: String): PreflightResult =
        runBlocking { Preflight(WorkflowLoader(env = emptyMap()), front(kinds), TemplateEngine(), trackerCheck = { null }).check() }

    private fun posts(
        delivery: String,
        deliverable: String = "    deliverable: { files: [out/**/*.md] }\n",
    ) = "  posts:\n    match: { label: posts }\n$deliverable    delivery: $delivery\n" +
        "    verify:\n      checks:\n        - { type: command, run: \"true\" }"

    private fun refused(
        kinds: String,
        expected: String,
    ) {
        val result = preflight(kinds)
        assertIs<PreflightResult.Failed>(result, kinds)
        assertEquals("invalid_config", result.code)
        assertEquals(expected, result.message)
    }

    @Test
    fun `preflight refuses policy auto on an attach kind without attach_auto enabled`() {
        refused(
            posts("{ policy: auto, attach: true }"),
            "kinds.posts.delivery.policy is auto, which needs the opt in kinds.posts.delivery.attach_auto.enabled: true",
        )
        refused(
            posts("{ policy: auto, attach: true, attach_auto: { enabled: false }, auto_merge: { enabled: true } }"),
            "kinds.posts.delivery.policy is auto, which needs the opt in kinds.posts.delivery.attach_auto.enabled: true",
        )
    }

    @Test
    fun `preflight accepts policy auto with the opt in, and the loader reads the attach keys with human by default`() {
        assertIs<PreflightResult.Ok>(preflight(posts("{ policy: auto, attach: true, attach_auto: { enabled: true } }")))

        val workflow =
            WorkflowLoader(env = emptyMap())
                .load(front(posts("{ attach: true, on_delivered: { move_to: done } }")))
                .getOrThrow()

        val delivery =
            checkNotNull(workflow.config.kinds)
                .named
                .getValue("posts")
                .delivery
        assertEquals(DeliveryConfig(DeliveryPolicy.Human, onDeliveredMoveTo = "done", attach = true), delivery)
    }

    @Test
    fun `preflight refuses attach without a deliverable, attach_auto without attach and a value that is not a boolean`() {
        refused(
            posts("{ attach: true }", deliverable = ""),
            "kinds.posts.delivery.attach needs kinds.posts.deliverable.files, the files it posts",
        )
        refused(
            posts("{ attach_auto: { enabled: true } }"),
            "kinds.posts.delivery.attach_auto.enabled needs kinds.posts.delivery.attach: true",
        )
        refused(posts("{ attach: yes please }"), "kinds.posts.delivery.attach must be true or false, got \"yes please\"")
    }

    @Test
    fun `attach and a pull request cannot both deliver a kind`() {
        assertEquals(
            "kinds.code.delivery.attach and kinds.code.delivery.pull_request cannot both deliver a kind, use one of them",
            AttachDelivery.refusal("kinds.code", listOf("out/*.md"), attach = true, attachAuto = false, pullRequest = true, auto = false),
        )
    }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
