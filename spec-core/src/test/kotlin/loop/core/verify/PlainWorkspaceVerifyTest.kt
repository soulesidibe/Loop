package loop.core.verify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.core.log.KeyValueLog
import loop.core.orchestrator.FIXED_NOW
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
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.HooksConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindMatch
import loop.core.workflow.KindPopulate
import loop.core.workflow.KindWorkspace
import loop.core.workflow.KindsConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import loop.core.workspace.HookName
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-20, verification and the fingerprint of a `workspace: plain` kind, on workspaces with and without git: a plain kind
 * is fingerprinted `tree:` and never read with git, preflight refuses its git only settings, `populate: empty` runs no
 * workspace hook, and a `git` kind behaves as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlainWorkspaceVerifyTest {
    private val root: Path = createTempDirectory("loop-plain").toRealPath()

    /** The default `code` kind, a git kind with one passing command check. */
    private val code = KindConfig(listOf(CheckConfig.Command("code-check")), delivery = DeliveryConfig(DeliveryPolicy.Human))

    /** A `notes` kind for the `notes` label: plain, populated empty, delivered after approval. */
    private fun notes(
        checks: List<CheckConfig> = listOf(CheckConfig.Command("notes-check")),
        holdout: List<CheckConfig> = emptyList(),
        delivery: DeliveryConfig = DeliveryConfig(DeliveryPolicy.Human),
    ) = KindConfig(
        checks,
        holdout = holdout,
        delivery = delivery,
        match = KindMatch(label = "notes"),
        workspace = KindWorkspace.Plain,
        populate = KindPopulate.Empty,
    )

    private fun workflow(
        kinds: Map<String, KindConfig>,
        hooks: HooksConfig = HooksConfig(),
    ) = WorkflowFile(
        config = WorkflowConfig(hooks = hooks, agent = AgentConfig(maxTurns = 1), kinds = KindsConfig(LinkedHashMap(kinds))),
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

    private val notesIssue = issue("n-1", labels = listOf("ready-for-agent", "notes"))

    private fun tracker(vararg issues: loop.api.Issue) =
        FakeTracker(issues.toList()).apply { definedStatesOverride = listOf("todo", "review", "needs_info", "done", "canceled") }

    /** Every check passes, by default, and the commands run are kept. */
    private val ran = mutableListOf<String>()
    private var checks: CheckRunner =
        CheckRunner { check, _, _ ->
            ran += check.label
            CheckResult(0, timedOut = false, durationMs = 1, output = "ok")
        }

    /** The real fingerprint, counting the calls a plain kind must never make. */
    private class SpyFingerprinter : WorkspaceFingerprinter() {
        var uncommittedCalls = 0
        var restoreCalls = 0

        override fun uncommitted(workspace: Path): Result<UncommittedFiles> {
            uncommittedCalls++
            return super.uncommitted(workspace)
        }

        override fun restore(workspace: Path): Result<Unit> {
            restoreCalls++
            return super.restore(workspace)
        }
    }

    private val fingerprinter = SpyFingerprinter()
    private val hookRunner = FakeHookRunner()
    private val loopHome: Path = root.resolve("home")

    /** The workspace of the agent's one turn, and what it held when the turn started. */
    private var turnWorkspace: Path? = null
    private var atTurnStart: List<String>? = null

    /** An agent that writes `notes.md` in its workspace and moves its issue to review. */
    private fun agent() =
        FakeRunner().apply {
            onCall = { ctx ->
                val workspace = ctx.workspace
                turnWorkspace = workspace
                atTurnStart = workspace.listDirectoryEntries().map { it.name }.sorted()
                workspace.resolve("notes.md").writeText("# Notes\n")
                ToolRequestQueue(Path.of(checkNotNull(ctx.env["LOOP_TOOL_CONFIG"])).parent).enqueue(ToolRequest.Move("review"))
            }
        }

    private fun TestScope.orchestrator(
        workflow: WorkflowFile,
        tracker: FakeTracker,
        waiting: FakeWaitingIssues = FakeWaitingIssues(),
        sink: FakeSink = FakeSink(),
    ): Orchestrator {
        var next = 0
        val clock = SchedulerClock(testScheduler)
        return Orchestrator(
            workflow = workflow,
            tracker = tracker,
            runner = agent(),
            sink = sink,
            log = KeyValueLog(out = {}, clock = clock, redactor = Redactor.NONE),
            clock = clock,
            workspaces = WorkspaceManager(Workspaces(workflow.workspaceRoot), workflow.config.hooks, hookRunner),
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
            fingerprinter = fingerprinter,
            evidence = EvidenceFiles(loopHome, Redactor.NONE),
            deliveryFor = { null },
        )
    }

    private fun FakeSink.verdict(): Map<String, String> = events.single { it.kind == "verdict" }.data

    private fun FakeWaitingIssues.approve() {
        val key = PROJECT.value to notesIssue.id
        val record = records.getValue(key)
        records[key] =
            record.copy(data = record.data + mapOf(WaitingIssue.APPROVED_BY to "soule", WaitingIssue.APPROVED_AT to FIXED_NOW.toString()))
    }

    // Verification of a plain workspace.

    @Test
    fun `a command check verifies a plain workspace, and the verdict and the evidence carry a tree fingerprint`() =
        runTest {
            val sink = FakeSink()
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "notes" to notes())), tracker(notesIssue), sink = sink)

            orchestrator.tick()

            assertEquals(listOf("notes-check"), ran.map { it.removePrefix("command ") })
            val verdict = sink.verdict()
            assertEquals("verified", verdict["verdict"])
            val fingerprint = assertNotNull(verdict["fingerprint"])
            assertTrue(fingerprint.startsWith("tree:"), fingerprint)
            assertFalse(checkNotNull(turnWorkspace).resolve(".git").exists(), "the plain workspace is not a git repository")
            val evidence =
                Files.walk(loopHome).use { files -> files.filter { it.name.contains(".evidence-") }.toList() }.single()
            assertContains(evidence.readText(), "fingerprint: $fingerprint")
        }

    @Test
    fun `a file changed after verification changes the tree fingerprint, and delivery refuses the stale verdict`() =
        runTest {
            val tracker = tracker(notesIssue)
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "notes" to notes())), tracker, waiting, sink)

            orchestrator.tick()

            val record = assertNotNull(waiting.records.values.singleOrNull())
            assertEquals(WaitReason.Approval, record.reason)
            val verified = sink.verdict().getValue("fingerprint")
            val workspace = checkNotNull(turnWorkspace)
            assertEquals(verified, fingerprinter.tree(workspace).getOrThrow())

            workspace.resolve("notes.md").writeText("# Notes, edited after the verdict\n")
            assertNotEquals(verified, fingerprinter.tree(workspace).getOrThrow())
            waiting.approve()
            orchestrator.tick()

            val failed = sink.events.single { it.kind == "delivery_failed" }.data
            assertEquals("fingerprint", failed["step"])
            assertContains(failed.getValue("reason"), verified)
            assertEquals(emptyList(), tracker.moves)
            // The approval is cleared, so only a new loop approve tries again.
            assertNull(waiting.records.values.single().data[WaitingIssue.APPROVED_BY])
        }

    @Test
    fun `an unchanged plain workspace is delivered after approval`() =
        runTest {
            val tracker = tracker(notesIssue)
            val waiting = FakeWaitingIssues()
            val sink = FakeSink()
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "notes" to notes())), tracker, waiting, sink)

            orchestrator.tick()
            waiting.approve()
            orchestrator.tick()

            assertEquals(listOf(notesIssue.id to "review"), tracker.moves)
            assertTrue(sink.events.none { it.kind == "delivery_failed" })
        }

    // Preflight.

    private fun preflight(kinds: String): PreflightResult {
        val folder = root.resolve("preflight").createDirectories()
        val path =
            folder.resolve("WORKFLOW.md").also {
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
        return runBlocking {
            Preflight(WorkflowLoader(env = emptyMap()), path, TemplateEngine(), trackerCheck = { null }).check()
        }
    }

    private fun refused(
        kinds: String,
        expected: String,
    ) {
        val result = preflight(kinds)
        assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", result.code)
        assertEquals(expected, result.message)
    }

    @Test
    fun `preflight refuses diff_policy on a plain kind`() =
        refused(
            """
            |  notes:
            |    match: { label: notes }
            |    workspace: plain
            |    verify:
            |      checks:
            |        - { type: command, run: "true" }
            |        - { type: diff_policy }
            |      guards:
            |        protect: [scripts/]
            """.trimMargin(),
            "kinds.notes.verify.checks[1] is diff_policy, which reads a git diff, and kinds.notes is workspace: plain",
        )

    @Test
    fun `preflight refuses the git deny guards on a plain kind`() =
        refused(
            """
            |  notes:
            |    match: { label: notes }
            |    workspace: plain
            |    verify:
            |      checks:
            |        - { type: command, run: "true" }
            |      guards:
            |        protect: [scripts/]
            |        deny: [deleted_tests]
            """.trimMargin(),
            "kinds.notes.verify.guards.deny reads a git diff, and kinds.notes is workspace: plain",
        )

    @Test
    fun `preflight refuses delivery pull_request on a plain kind`() =
        refused(
            """
            |  notes:
            |    match: { label: notes }
            |    workspace: plain
            |    delivery:
            |      pull_request: { repo: acme/widgets, token: ${'$'}LOOP_PR_TOKEN }
            |    verify:
            |      checks:
            |        - { type: command, run: "true" }
            """.trimMargin(),
            "kinds.notes.delivery.pull_request pushes a git commit, and kinds.notes is workspace: plain",
        )

    @Test
    fun `preflight refuses populate empty on a git kind`() =
        refused(
            """
            |  notes:
            |    match: { label: notes }
            |    populate: empty
            |    verify:
            |      checks:
            |        - { type: command, run: "true" }
            """.trimMargin(),
            "kinds.notes.populate is empty, which needs workspace: plain, a git kind is populated by the workspace hooks",
        )

    @Test
    fun `preflight accepts a plain kind populated empty, and reads both keys`() {
        val result =
            preflight(
                """
                |  notes:
                |    match: { label: notes }
                |    workspace: plain
                |    populate: empty
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                """.trimMargin(),
            )
        val ok = assertIs<PreflightResult.Ok>(result)
        val kinds = assertNotNull(ok.workflow.config.kinds)
        val notes = kinds.named.getValue("notes")
        assertEquals(KindWorkspace.Plain, notes.workspace)
        assertEquals(KindPopulate.Empty, notes.populate)
        assertEquals(KindWorkspace.Git, kinds.code.workspace)
    }

    // The pinned base.

    @Test
    fun `the pinned base reads the issue's own kind, and a plain kind fetches no base`() =
        runTest {
            val pullRequest = DeliveryConfig(DeliveryPolicy.Human, PullRequestConfig("acme/widgets", "LOOP_PR_TOKEN"))
            val diffPolicy = CheckConfig.DiffPolicy(listOf("scripts/"))
            val other = KindConfig(listOf(diffPolicy), delivery = pullRequest, match = KindMatch(label = "other"))
            // Built past the loader, which refuses it: a plain kind that delivers a pull request with a diff_policy.
            val plain = notes(checks = listOf(diffPolicy), delivery = pullRequest)
            val kinds = mapOf("code" to code, "other" to other, "notes" to plain)

            val otherSink = FakeSink()
            orchestrator(workflow(kinds), tracker(issue("o-1", labels = listOf("ready-for-agent", "other"))), sink = otherSink).tick()
            // The other kind's base, which this host cannot fetch: the code kind delivers no pull request.
            assertEquals(1, otherSink.events.count { it.kind == "diff_base_unpinned" })

            val notesSink = FakeSink()
            orchestrator(workflow(kinds), tracker(notesIssue), sink = notesSink).tick()
            assertTrue(notesSink.events.none { it.kind == "diff_base_unpinned" || it.kind == "base_fetched" }, "${notesSink.events}")
            assertTrue(notesSink.verdict().getValue("fingerprint").startsWith("tree:"))
            assertEquals(0, fingerprinter.uncommittedCalls)
            assertEquals(0, fingerprinter.restoreCalls)
        }

    // With and without git.

    private fun git(
        dir: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed" }
    }

    @Test
    fun `a plain workspace inside a git work tree is fingerprinted tree, without git's own folder`() {
        val repo = root.resolve("repo").createDirectories()
        git(repo, "init", "-q")
        repo.resolve("notes.md").writeText("# Notes\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "first")
        val copy = root.resolve("copy").createDirectories()
        copy.resolve("notes.md").writeText("# Notes\n")

        val tree = WorkspaceFingerprint.tree(repo).getOrThrow()

        assertTrue(WorkspaceFingerprint.of(repo).getOrThrow().startsWith("git:"))
        assertTrue(tree.startsWith("tree:"), tree)
        assertEquals(WorkspaceFingerprint.of(copy).getOrThrow(), tree)
    }

    @Test
    fun `a git kind in a folder that is not a git work tree stays unverified`() =
        runTest {
            checks = CoreCheckRunner()
            val gitKind = KindConfig(listOf(CheckConfig.DiffPolicy(listOf("scripts/"))), delivery = DeliveryConfig(DeliveryPolicy.Human))
            val sink = FakeSink()

            orchestrator(workflow(mapOf("code" to gitKind)), tracker(issue("c-1")), sink = sink).tick()

            val verdict = sink.verdict()
            assertEquals("unverified", verdict["verdict"])
            assertContains(verdict.getValue("reason"), "not the top of a git work tree")
            assertTrue(verdict.getValue("fingerprint").startsWith("tree:"))
        }

    // populate: empty.

    private val allHooks =
        HooksConfig(afterCreate = "after-create", beforeRun = "before-run", afterRun = "after-run", beforeRemove = "before-remove")

    @Test
    fun `a populate empty kind gets an empty folder and runs none of the four workspace hooks`() =
        runTest {
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "notes" to notes()), allHooks), tracker(notesIssue))

            orchestrator.tick()
            assertEquals(emptyList(), atTurnStart, "the folder Loop created is empty")
            orchestrator.removeWorkspace(notesIssue).getOrThrow()

            assertEquals(emptyList(), hookRunner.hooks())
            assertFalse(checkNotNull(turnWorkspace).exists(), "the workspace is removed")
        }

    @Test
    fun `a git kind still runs the four workspace hooks`() =
        runTest {
            val codeIssue = issue("c-1")
            val orchestrator = orchestrator(workflow(mapOf("code" to code, "notes" to notes()), allHooks), tracker(codeIssue))

            orchestrator.tick()
            orchestrator.removeWorkspace(codeIssue).getOrThrow()

            assertEquals(
                listOf(HookName.AFTER_CREATE, HookName.BEFORE_RUN, HookName.AFTER_RUN, HookName.BEFORE_REMOVE),
                hookRunner.hooks(),
            )
        }

    // ChangedLineCoverage, Holdouts and the verify findings.

    @Test
    fun `changed_line_coverage outside a git work tree says it could not run, and never throws`() {
        val folder = root.resolve("plain").createDirectories()
        folder.resolve("report.xml").writeText("<report name=\"r\"/>")

        val result = CoreChecks.changedLineCoverage(CheckConfig.ChangedLineCoverage(listOf("report.xml"), 80.0), folder, mapOf("PATH" to "/usr/bin:/bin"))

        assertContains(assertNotNull(result.couldNotRun), "not the top of a git work tree")
    }

    @Test
    fun `holdouts are checked for a project that is not a git repository, and never throw`() {
        val holdoutDir = createTempDirectory("loop-plain-holdout").toRealPath()
        val holdout = CheckConfig.Command("sh \"\$LOOP_HOLDOUT/check.sh\"")
        val workflow = workflow(mapOf("code" to code, "notes" to notes(holdout = listOf(holdout))))

        assertNull(Holdouts.problem(workflow, holdoutDir))
    }

    @Test
    fun `a failed check and a failed holdout on a plain workspace give findings, with no uncommitted listing or restore`() =
        runTest {
            checks =
                CheckRunner { check, _, _ ->
                    ran += check.label
                    CheckResult(1, timedOut = false, durationMs = 1, output = "missing section")
                }
            val sink = FakeSink()
            orchestrator(workflow(mapOf("code" to code, "notes" to notes())), tracker(notesIssue), sink = sink).tick()
            assertEquals("refuted", sink.verdict()["verdict"])

            checks =
                CheckRunner { check, _, _ ->
                    ran += check.label
                    CheckResult(if (check.label.contains("LOOP_HOLDOUT")) 1 else 0, timedOut = false, durationMs = 1)
                }
            val holdout = CheckConfig.Command("sh \"\$LOOP_HOLDOUT/check.sh\"")
            val hiddenSink = FakeSink()
            orchestrator(workflow(mapOf("code" to code, "notes" to notes(holdout = listOf(holdout)))), tracker(notesIssue), sink = hiddenSink)
                .tick()
            assertEquals("refuted", hiddenSink.verdict()["verdict"])
            assertTrue(hiddenSink.verdict().getValue("fingerprint").startsWith("tree:"))

            assertEquals(0, fingerprinter.uncommittedCalls)
            assertEquals(0, fingerprinter.restoreCalls)
        }

    private companion object {
        val PROJECT = ProjectId("p1")
    }
}
