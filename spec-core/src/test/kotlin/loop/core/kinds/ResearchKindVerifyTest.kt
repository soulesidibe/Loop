package loop.core.kinds

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
import loop.core.workflow.KindPopulate
import loop.core.workflow.KindWorkspace
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowError
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * m4-23, a kind whose deliverable is one report: verification lists `deliverable.report` like a deliverable file and
 * reads it, a missing or empty report refutes the result, and so does a report missing one of the sections of
 * `verify.require_sections`, the findings naming the report or each missing section. Behavior follows the keys, so a kind
 * of another name behaves the same as one named `research`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResearchKindVerifyTest {
    private val root: Path = createTempDirectory("loop-research").toRealPath()

    private val code = KindConfig(listOf(CheckConfig.Command("code-check")), delivery = DeliveryConfig(DeliveryPolicy.Human))

    /** A kind for the `research` label whose deliverable is `report.md`, on a plain empty workspace, `human` by default. */
    private fun research(sections: List<String> = listOf("Summary", "Sources")) =
        KindConfig(
            listOf(CheckConfig.Command("sources-check")),
            match = KindMatch(label = "research"),
            workspace = KindWorkspace.Plain,
            populate = KindPopulate.Empty,
            deliverable = KindDeliverable(report = "report.md"),
            requireSections = sections,
        )

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

    private fun tracker() =
        FakeTracker(listOf(issue("r-1", labels = listOf("ready-for-agent", "research")))).apply {
            definedStatesOverride = listOf("todo", "review", "done", "canceled")
        }

    private val checks = CheckRunner { _, _, _ -> CheckResult(0, timedOut = false, durationMs = 1, output = "ok") }

    private val loopHome: Path = root.resolve("home")

    /** The files the agent writes in its workspace on each turn, by path; none by default. */
    private var writes: Map<String, ByteArray> = emptyMap()

    private val runner =
        FakeRunner().apply {
            onCall = { ctx ->
                writes.forEach { (path, bytes) ->
                    ctx.workspace
                        .resolve(path)
                        .also { it.parent.createDirectories() }
                        .writeBytes(bytes)
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

    private val fullReport = "# Queues\n\n## Summary\n\nFine.\n\n## Sources\n\n- https://example.com/queues\n"

    /** Runs one refuted attempt and the next one, and returns the verdict of the first and the prompt of the second. */
    private suspend fun TestScope.refutedTwice(name: String): Pair<Map<String, String>, String> {
        runner.calls.clear()
        val waiting = FakeWaitingIssues()
        val sink = FakeSink()
        val orchestrator = orchestrator(workflow(mapOf("code" to code, name to research())), waiting, sink)

        val first = orchestrator.tick().attempts.single()
        advanceTimeBy(10_000)
        orchestrator.tick()

        assertEquals("refuted", first.reason, name)
        assertEquals(emptyMap(), waiting.records, name)
        Files.walk(root.resolve("workspaces")).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return sink.verdicts().first() to runner.calls[1].second
    }

    @Test
    fun `a verified report is listed with the verdict as the one deliverable file, and waits for approval by default`() =
        runTest {
            writes = mapOf("report.md" to fullReport.toByteArray(), "notes.md" to "# Notes\n".toByteArray())
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()

            orchestrator(workflow(mapOf("code" to code, "research" to research())), waiting, sink).tick()

            val verdict = sink.verdicts().single()
            assertEquals("verified", verdict["verdict"])
            assertEquals("1", verdict["deliverable"])
            assertNull(verdict["report_findings"])
            assertContains(verdict.getValue("fingerprint"), "tree:")
            val listed = evidence(verdict.getValue("deliverable_evidence"))
            assertContains(listed, "- report.md, ${fullReport.length} bytes, sha256 ")
            assertFalse(listed.contains("notes.md"), listed)
            assertEquals(WaitReason.Approval, waiting.records.values.single().reason)
        }

    @Test
    fun `a missing report is refuted and the finding names the report, whatever the kind is called`() =
        runTest {
            for (name in listOf("research", "study")) {
                writes = mapOf("Report.md" to fullReport.toByteArray(), "out/report.md" to fullReport.toByteArray())

                val (verdict, next) = refutedTwice(name)

                assertEquals("refuted", verdict["verdict"], name)
                assertEquals("0", verdict["deliverable"], name)
                assertContains(next, "Your last result was refuted: the report is not ready")
                assertContains(next, "- report.md is missing")
            }
        }

    @Test
    fun `a report missing a required section is refuted and the finding names each missing section`() =
        runTest {
            writes = mapOf("report.md" to "# Queues\n\n## Summary\n\nFine.\n\n## Method\n\n".toByteArray())

            val (verdict, next) = refutedTwice("research")

            assertEquals("refuted", verdict["verdict"])
            assertEquals("1", verdict["deliverable"])
            assertEquals("1", verdict["report_findings"])
            assertContains(next, "Your last result was refuted: the report is not ready")
            assertContains(next, "- report.md misses the section Sources")
            assertFalse(next.contains("misses the section Summary"), next)
        }

    @Test
    fun `an empty report and one that is not UTF-8 text are refuted, each with its finding`() =
        runTest {
            writes = mapOf("report.md" to " \n\n".toByteArray())
            val (empty, emptyNext) = refutedTwice("research")
            assertEquals("refuted", empty["verdict"])
            assertContains(emptyNext, "- report.md is empty")
            assertFalse(emptyNext.contains("misses the section"), emptyNext)

            writes = mapOf("report.md" to byteArrayOf(0xC3.toByte(), 0x28, 0x0A))
            val (binary, binaryNext) = refutedTwice("research")
            assertEquals("refuted", binary["verdict"])
            assertContains(binaryNext, "- report.md is not UTF-8 text")
        }

    @Test
    fun `a report kind with no required section is verified with any non empty report`() =
        runTest {
            writes = mapOf("report.md" to "Just a line.\n".toByteArray())
            val sink = FakeSink()

            orchestrator(workflow(mapOf("code" to code, "research" to research(sections = emptyList()))), sink = sink).tick()

            assertEquals("verified", sink.verdicts().single()["verdict"])
        }

    // The loader.

    private fun front(kinds: String): Path {
        val folder = root.resolve("loader").createDirectories()
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

    private fun kind(
        deliverable: String?,
        verify: String = "",
        delivery: String = "",
    ) = """
        |  research:
        |    match: { label: research }
        |    workspace: plain
        |    populate: empty
        |${deliverable?.let { "    deliverable: $it" }.orEmpty()}
        |$delivery
        |    verify:
        |$verify
        |      checks:
        |        - { type: command, run: "sh check-sources.sh report.md" }
        """.trimMargin()

    private fun load(kinds: String) = WorkflowLoader(env = emptyMap()).load(front(kinds))

    @Test
    fun `the loader reads deliverable report, verify require_sections and delivery attach under the default human policy`() {
        val workflow =
            load(
                kind(
                    "{ report: \" report.md \" }",
                    "      require_sections: [Summary, \" Sources \"]",
                    "    delivery: { attach: true }",
                ),
            ).getOrThrow()

        val research = checkNotNull(workflow.config.kinds).named.getValue("research")
        assertEquals(KindDeliverable(report = "report.md"), research.deliverable)
        assertEquals(listOf("report.md"), research.deliverable.globs)
        assertEquals(listOf("Summary", "Sources"), research.requireSections)
        assertEquals(DeliveryPolicy.Human, research.delivery.policy)
        assertEquals(true, research.delivery.attach)
        assertEquals(emptyList(), checkNotNull(workflow.config.kinds).code.requireSections)
    }

    @Test
    fun `the loader refuses a report that is not one plain path relative to the workspace, and misplaced required sections`() {
        val cases =
            listOf(
                kind("{ report: [report.md] }") to "kinds.research.deliverable.report must be a string",
                kind("{ report: \" \" }") to "kinds.research.deliverable.report must not be blank",
                kind("{ report: /tmp/report.md }") to
                    "kinds.research.deliverable.report is /tmp/report.md, use a path relative to the workspace",
                kind("{ report: ../report.md }") to
                    "kinds.research.deliverable.report is ../report.md, use a path relative to the workspace",
                kind("{ report: \"out/*.md\" }") to
                    "kinds.research.deliverable.report is out/*.md, name one file, without * or ?",
                kind("{ report: out/ }") to "kinds.research.deliverable.report is out/, name one file, not a folder",
                kind("{ report: report.md, files: [out/*.md] }") to
                    "kinds.research.deliverable holds files and report, use one of them",
                kind(null, "      require_sections: [Summary]") to
                    "kinds.research.verify.require_sections needs kinds.research.deliverable.report, the report it reads",
                kind("{ report: report.md }", "      require_sections: []") to
                    "kinds.research.verify.require_sections must list at least one section",
                kind("{ report: report.md }", "      require_sections: [Summary, \" \"]") to
                    "kinds.research.verify.require_sections must not hold a blank name",
                kind("{ report: report.md }", delivery = "    delivery: { policy: auto, auto_merge: { enabled: true } }") to
                    "kinds.research.delivery.policy is auto, which a kind with deliverable.report supports only with " +
                    "kinds.research.delivery.attach: true and the opt in kinds.research.delivery.attach_auto.enabled: true, " +
                    "or use policy: human",
            )
        for ((kinds, expected) in cases) {
            val error = assertFailsWith<WorkflowError.InvalidConfig>(expected) { load(kinds).getOrThrow() }
            assertEquals(expected, error.message)
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
    fun `the verdict sentence says how many report findings refuted it`() {
        val data = mapOf("verdict" to "refuted", "checks" to "1", "evidence" to "a.log", "deliverable" to "1")

        assertEquals(
            "verdict refuted (1 report finding), evidence of 1 check saved, 1 deliverable file listed",
            Verdict.humanSentence(data + ("report_findings" to "1"), Facts),
        )
        assertEquals(
            "verdict refuted (2 report findings), evidence of 1 check saved, 1 deliverable file listed",
            Verdict.humanSentence(data + ("report_findings" to "2"), Facts),
        )
    }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
