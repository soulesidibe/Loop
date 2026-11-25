package loop.core.kinds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.core.events.Verdict
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
import loop.core.verify.CoreCheckRunner
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
import loop.core.workflow.WorkflowError
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-21, a kind whose deliverable is files: verification lists the files matching `deliverable.files` and records them
 * with the verdict, no match refutes the result with "no deliverable file", the workflow's own `command` checks do the
 * rest, such as the banned dash example of the docs, and preflight refuses `policy: auto` on such a kind. Behavior
 * follows the key, so a kind of another name behaves the same as one named `content`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentKindVerifyTest {
    private val root: Path = createTempDirectory("loop-content").toRealPath()

    /** The banned dash check of the docs, which runs on BSD and GNU grep. */
    private val dashCheck = CheckConfig.Command("! grep -rnE \"\$(printf '\\342\\200\\224|\\342\\200\\223')\" out/")

    private val code = KindConfig(listOf(CheckConfig.Command("code-check")), delivery = DeliveryConfig(DeliveryPolicy.Human))

    /** A kind for the `posts` label whose deliverable is the markdown files under `out`, with the default `human` policy. */
    private fun content(checks: List<CheckConfig> = listOf(CheckConfig.Command("content-check"))) =
        KindConfig(checks, match = KindMatch(label = "posts"), deliverable = KindDeliverable(listOf("out/**/*.md")))

    private fun workflow(kinds: Map<String, KindConfig>) =
        WorkflowFile(
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 1), kinds = KindsConfig(LinkedHashMap(kinds))),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done", "canceled"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work on {{ issue.identifier }}{% if loop.verify_findings %}\n{{ loop.verify_findings }}{% endif %}",
        )

    private val postIssue = issue("p-1", labels = listOf("ready-for-agent", "posts"))

    private fun tracker() = FakeTracker(listOf(postIssue)).apply { definedStatesOverride = listOf("todo", "review", "done", "canceled") }

    /** Every check passes by default. */
    private var checks: CheckRunner = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1, output = "ok") }

    /**
     * The real `command` check runner, on real time: under the test's virtual time the timeout of a real process would
     * end it at once.
     */
    private val realChecks = CheckRunner { check, cwd, env -> withContext(Dispatchers.IO) { CoreCheckRunner().run(check, cwd, env) } }

    private val loopHome: Path = root.resolve("home")

    /** The files the agent writes in its workspace on each turn, by path; none by default. */
    private var writes: Map<String, String> = emptyMap()

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
        waiting: FakeWaitingIssues = FakeWaitingIssues(),
        sink: FakeSink = FakeSink(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker(),
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

    private fun FakeSink.verdicts(): List<Map<String, String>> = events.filter { it.kind == "verdict" }.map { it.data }

    private fun evidence(name: String): String =
        Files
            .walk(loopHome)
            .use { files -> files.filter { it.name == name }.toList() }
            .single()
            .readText()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // Verification.

    @Test
    fun `the files matching deliverable files are recorded with the verdict, which waits for approval under the default policy`() =
        runTest {
            for (name in listOf("content", "posts")) {
                writes = mapOf("out/post.md" to "# Post\n", "out/2026/second.md" to "# Second\n", "notes.txt" to "not a deliverable\n")
                val waiting = FakeWaitingIssues()
                val sink = FakeSink()

                orchestrator(workflow(mapOf("code" to code, name to content())), waiting, sink).tick()

                val verdict = sink.verdicts().single()
                assertEquals("verified", verdict["verdict"], name)
                assertEquals("2", verdict["deliverable"], name)
                val listed = evidence(verdict.getValue("deliverable_evidence"))
                assertContains(listed, "deliverable: 2")
                assertContains(listed, "fingerprint: ${verdict["fingerprint"]}")
                assertContains(listed, "- out/2026/second.md, 9 bytes, sha256 ${sha256("# Second\n")}")
                assertContains(listed, "- out/post.md, 7 bytes, sha256 ${sha256("# Post\n")}")
                assertFalse(listed.contains("notes.txt"), listed)
                assertEquals(
                    WaitReason.Approval,
                    waiting.records.values
                        .single()
                        .reason,
                    name,
                )
                Files.walk(loopHome).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                Files.walk(root.resolve("workspaces")).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

    @Test
    fun `a submission with no file matching deliverable files is refuted with no deliverable file, whatever the kind is called`() =
        runTest {
            for (name in listOf("content", "posts")) {
                writes = mapOf("out/post.txt" to "not markdown\n")
                runner.calls.clear()
                val waiting = FakeWaitingIssues()
                val sink = FakeSink()
                val orchestrator = orchestrator(workflow(mapOf("code" to code, name to content())), waiting, sink)

                val first = orchestrator.tick().attempts.single()
                advanceTimeBy(10_000)
                orchestrator.tick()

                assertEquals("refuted", first.reason, name)
                val verdict = sink.verdicts().first()
                assertEquals("refuted", verdict["verdict"], name)
                assertEquals("0", verdict["deliverable"], name)
                assertEquals(emptyMap(), waiting.records, name)
                val next = runner.calls[1].second
                assertContains(next, "Your last result was refuted: no deliverable file")
                assertContains(next, "out/**/*.md")
                Files.walk(root.resolve("workspaces")).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }

    @Test
    fun `a kind named content without deliverable files lists nothing and is verified as before`() =
        runTest {
            val plain = KindConfig(listOf(CheckConfig.Command("content-check")), match = KindMatch(label = "posts"))
            val sink = FakeSink()

            orchestrator(workflow(mapOf("code" to code, "content" to plain)), sink = sink).tick()

            val verdict = sink.verdicts().single()
            assertEquals("verified", verdict["verdict"])
            assertNull(verdict["deliverable"])
            assertNull(verdict["deliverable_evidence"])
        }

    @Test
    fun `a failed check refutes with its own findings, not the missing deliverable`() =
        runTest {
            checks = CheckRunner { _, _, _ -> CheckResult(1, timedOut = false, durationMs = 1, output = "too long") }
            runner.calls.clear()
            val sink = FakeSink()
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "content" to content())), sink = sink)

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            assertEquals("refuted", sink.verdicts().first()["verdict"])
            val next = runner.calls[1].second
            assertContains(next, "Check: content-check")
            assertFalse(next.contains("no deliverable file"), next)
        }

    // The banned dash example, with the real command check runner.

    @Test
    fun `a banned dash in a deliverable fails the example check, and the finding names the file and the line`() =
        runTest {
            checks = realChecks
            writes = mapOf("out/post.md" to "# Post\n\nA line with an em dash \u2014 here.\n", "out/clean.md" to "# Clean\n")
            val sink = FakeSink()
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "content" to content(listOf(dashCheck)))), sink = sink)

            orchestrator.tick()
            advanceTimeBy(10_000)
            orchestrator.tick()

            val verdict = sink.verdicts().first()
            assertEquals("refuted", verdict["verdict"])
            assertEquals("2", verdict["deliverable"])
            val next = runner.calls[1].second
            assertContains(next, "Exit code: 1")
            assertContains(next, "out/post.md:3:A line with an em dash")
            assertFalse(next.contains("out/clean.md"), next)
        }

    @Test
    fun `an en dash fails the example check too, and a deliverable with no dash passes it`() =
        runTest {
            checks = realChecks
            writes = mapOf("out/post.md" to "From 2026 \u2013 2027.\n")
            val dashed = FakeSink()
            orchestrator(workflow(mapOf("code" to code, "content" to content(listOf(dashCheck)))), sink = dashed).tick()
            assertEquals("refuted", dashed.verdicts().single()["verdict"])

            Files.walk(root.resolve("workspaces")).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            writes = mapOf("out/post.md" to "# Post\n\nA plain line, with commas, and periods.\n")
            val clean = FakeSink()
            orchestrator(workflow(mapOf("code" to code, "content" to content(listOf(dashCheck)))), sink = clean).tick()

            val verdict = clean.verdicts().single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("1", verdict["deliverable"])
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

    private fun refused(
        kinds: String,
        expected: String,
    ) {
        val result = preflight(kinds)
        assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", result.code)
        assertEquals(expected, result.message)
    }

    private fun kind(
        name: String,
        deliverable: String,
        delivery: String = "",
    ) = """
        |  $name:
        |    match: { label: $name }
        |    deliverable: $deliverable
        |$delivery
        |    verify:
        |      checks:
        |        - type: command
        |          run: |
        |            ! grep -rnE "${'$'}(printf '\342\200\224|\342\200\223')" out/
        """.trimMargin()

    @Test
    fun `preflight refuses policy auto on a kind with deliverable files, whatever the kind is called`() {
        for (name in listOf("content", "posts")) {
            refused(
                kind(name, "{ files: [out/**/*.md] }", "    delivery: { policy: auto, auto_merge: { enabled: true } }"),
                "kinds.$name.delivery.policy is auto, which a kind with deliverable.files does not support yet, use policy: human",
            )
            refused(
                kind(name, "{ files: [out/**/*.md] }", "    delivery: { policy: auto }"),
                "kinds.$name.delivery.policy is auto, which a kind with deliverable.files does not support yet, use policy: human",
            )
        }
    }

    @Test
    fun `the loader reads deliverable files, keeps the default human policy and the example check as written`() {
        val workflow = WorkflowLoader(env = emptyMap()).load(front(kind("posts", "{ files: [out/**/*.md, \" README.md \"] }"))).getOrThrow()

        val kinds = checkNotNull(workflow.config.kinds)
        val posts = kinds.named.getValue("posts")
        assertEquals(KindDeliverable(listOf("out/**/*.md", "README.md")), posts.deliverable)
        assertEquals(DeliveryPolicy.Human, posts.delivery.policy)
        assertEquals(
            "! grep -rnE \"\$(printf '\\342\\200\\224|\\342\\200\\223')\" out/",
            (posts.checks.single() as CheckConfig.Command).run,
        )
        assertEquals(KindDeliverable(), kinds.code.deliverable)
        assertTrue(preflight(kind("posts", "{ files: [out/**/*.md] }", "    delivery: { policy: human }")) is PreflightResult.Ok)
    }

    @Test
    fun `the loader refuses a deliverable that is not a map of globs relative to the workspace`() {
        val cases =
            listOf(
                "out/**/*.md" to "kinds.posts.deliverable must be a map",
                "{ files: [] }" to "kinds.posts.deliverable.files must hold at least one glob",
                "{ files: [\" \"] }" to "kinds.posts.deliverable.files must not hold a blank glob",
                "{ files: [/etc/*.md] }" to "kinds.posts.deliverable.files[0] is /etc/*.md, use a glob relative to the workspace",
                "{ files: [out/../../*.md] }" to "kinds.posts.deliverable.files[0] is out/../../*.md, use a glob relative to the workspace",
                "{ report: report.md }" to "kinds.posts.deliverable.report is not supported yet, use files",
                "{ files: [out/*.md], fiels: [x] }" to "kinds.posts.deliverable.fiels is not supported, use files",
                "{}" to "kinds.posts.deliverable.files must hold at least one glob",
            )
        for ((deliverable, expected) in cases) {
            val error =
                assertFailsWith<WorkflowError.InvalidConfig>(deliverable) {
                    WorkflowLoader(env = emptyMap()).load(front(kind("posts", deliverable))).getOrThrow()
                }
            assertEquals(expected, error.message, deliverable)
        }
    }

    private object Facts : loop.core.events.HumanFacts {
        override val attemptName = "attempt"
        override val issueState: String? = null
        override val spend = ""
        override val preflightFailing = false
        override val nextAttempt: String? = null
    }

    @Test
    fun `the verdict sentence says when no deliverable file was listed, and how many were`() {
        val data = mapOf("verdict" to "refuted", "checks" to "1", "evidence" to "a.log", "deliverable" to "0")

        assertEquals("verdict refuted (no deliverable file), evidence of 1 check saved", Verdict.humanSentence(data, Facts))
        assertEquals(
            "verdict verified, evidence of 1 check saved, 2 deliverable files listed",
            Verdict.humanSentence(data + mapOf("verdict" to "verified", "deliverable" to "2"), Facts),
        )
        assertEquals(
            "verdict verified, evidence of 1 check saved, 1 deliverable file listed",
            Verdict.humanSentence(data + mapOf("verdict" to "verified", "deliverable" to "1"), Facts),
        )
    }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
