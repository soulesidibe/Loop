package loop.core.replay

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.events.ReplayVerdict
import loop.core.kinds.IssueKind
import loop.core.orchestrator.FIXED_NOW
import loop.core.verify.CheckResult
import loop.core.verify.CheckRunner
import loop.core.verify.CoreCheckRunner
import loop.core.verify.WorkspaceFingerprint
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PullRequestConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-41: the verification of `loop.core.verify` run again on a replay site, outside any run: its checks, its verdict
 * and the `replay_verdict` event, with nothing written anywhere else.
 */
class VerificationReplayTest {
    private val root: Path = createTempDirectory("loop-verification-replay").toRealPath()
    private val workspace: Path = root.resolve("workspaces/t-1").createDirectories()
    private val temp: Path = root.resolve("tmp").createDirectories()
    private val sites = ReplaySites(tempParent = { Files.createTempDirectory(temp, "replay-") })
    private val attempt = AttemptId("a-1")
    private val frozenReads = mutableListOf<String>()

    private fun workflow(kind: KindConfig) =
        WorkflowFile(
            config = WorkflowConfig(agent = AgentConfig(maxTurns = 1), kinds = KindsConfig(linkedMapOf(KindsConfig.CODE to kind))),
            path = root.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = root.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = listOf("ready-for-agent"),
            workspaceRoot = root.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Work on {{ issue.identifier }}",
        )

    private fun target(
        kind: KindConfig,
        description: String? = "Some work",
    ) = ReplayTarget(attempt, "t-1", "t-1", description, workflow(kind), IssueKind(KindsConfig.CODE, kind))

    private fun replay(checkRunner: CheckRunner = CoreCheckRunner()) =
        VerificationReplay(
            projectId = ProjectId("p"),
            loopHome = root.resolve("home"),
            redactor = SecretRedactor(),
            checkRunner = checkRunner,
            frozen = { issueId ->
                frozenReads += issueId
                Result.success(null)
            },
        )

    private fun recorded(fingerprint: String) = RecordedVerdict("verified", fingerprint, diffBase = null, runId = RunId("r-1"))

    private fun site(fingerprint: String): ReplaySite =
        assertIs<Placement.Ready>(sites.place(fingerprint, Result.success(workspace), root)).site

    @Test
    fun `the checks of the kind run on the copy, and the workspace is left as it was`() =
        runTest {
            workspace.resolve("hello.txt").writeText("hello\n")
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.Command("test -f hello.txt"), CheckConfig.Command("echo written > written.txt")))

            val replayed = site(fingerprint).use { replay().run(target(kind), recorded(fingerprint), it) }

            assertEquals("verified", replayed.verdict)
            assertEquals(fingerprint, replayed.fingerprint)
            assertNull(replayed.reason)
            assertEquals(listOf("test -f hello.txt", "echo written > written.txt"), replayed.checks.map { it.command })
            assertTrue(replayed.checks.all { it.passed }, replayed.checks.toString())
            assertFalse(workspace.resolve("written.txt").exists(), "the check wrote in the copy, never in the workspace")
            assertEquals(fingerprint, WorkspaceFingerprint.of(workspace).getOrThrow())
        }

    @Test
    fun `a failing check refutes the replay, its rerun shown`() =
        runTest {
            workspace.resolve("hello.txt").writeText("hello\n")
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.Command("test -f goodbye.txt")))

            val replayed = site(fingerprint).use { replay().run(target(kind), recorded(fingerprint), it) }

            assertEquals("refuted", replayed.verdict)
            assertEquals(listOf(false, true), replayed.checks.map { it.rerun })
            assertEquals(listOf(1, 1), replayed.checks.map { it.exitCode })
            assertContains(replayed.checks.last().line(), "check 0 rerun failed with exit code 1")
            val findings = replayed.findings ?: error("no findings")
            assertContains(findings, "Check: test -f goodbye.txt\nExit code: 1")
            assertFalse("Your last result" in findings, "the lead speaks to the agent: $findings")
            assertFalse(replayed.baseUnpinned)
        }

    @Test
    fun `a check sees the run id replay, never the one of the attempt`() =
        runTest {
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.Command("true")))
            val seen = mutableListOf<String?>()
            val runner =
                CheckRunner { _, _, env ->
                    seen += env["LOOP_RUN_ID"]
                    CheckResult(0, false, 1)
                }

            site(fingerprint).use { replay(runner).run(target(kind), recorded(fingerprint), it) }

            assertEquals(listOf<String?>("replay"), seen)
        }

    @Test
    fun `a diff policy with no recorded base says the base is unpinned`() =
        runTest {
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind =
                KindConfig(
                    listOf(CheckConfig.DiffPolicy(protect = listOf("WORKFLOW.md"))),
                    delivery = DeliveryConfig(pullRequest = PullRequestConfig()),
                )

            val replayed = site(fingerprint).use { replay().run(target(kind), recorded(fingerprint), it) }

            assertTrue(replayed.baseUnpinned, replayed.toString())
        }

    @Test
    fun `a hidden check says only whether it passed`() =
        runTest {
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.Command("true")), holdout = listOf(CheckConfig.Command("secret-check.sh --flag")))
            val runner = CheckRunner { check, _, _ -> CheckResult(if (check.label == "true") 0 else 3, false, 5, output = "secret") }

            val replayed = site(fingerprint).use { replay(runner).run(target(kind), recorded(fingerprint), it) }

            val hidden = replayed.checks.filter { it.holdout }
            assertEquals(listOf("hidden check 1 failed", "hidden check 1 rerun failed"), hidden.map { it.line() })
            assertFalse(replayed.checks.any { "secret" in it.line() })
            assertNull(replayed.findings, "a hidden check shows no findings")
        }

    @Test
    fun `acceptance commands are checked against the frozen list, read and never written`() =
        runTest {
            workspace.resolve("hello.txt").writeText("hello\n")
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.AcceptanceCommands()))
            val description = "## Acceptance criteria\n\n- [ ] `test -f hello.txt` passes.\n"

            val replayed = site(fingerprint).use { replay().run(target(kind, description), recorded(fingerprint), it) }

            assertEquals("verified", replayed.verdict)
            assertEquals(listOf("test -f hello.txt"), replayed.checks.map { it.command })
            assertEquals(listOf("t-1"), frozenReads)

            val changed =
                VerificationReplay(
                    projectId = ProjectId("p"),
                    loopHome = root.resolve("home"),
                    redactor = SecretRedactor(),
                    checkRunner = CoreCheckRunner(),
                    frozen = { Result.success(FrozenCommands(ProjectId("p"), it, "t-1", "another digest", 2, FIXED_NOW)) },
                )
            val refused = site(fingerprint).use { changed.run(target(kind, description), recorded(fingerprint), it) }
            assertEquals("unverified", refused.verdict)
            assertEquals("acceptance commands changed", refused.reason)
            assertTrue(refused.checks.isEmpty())
        }

    @Test
    fun `the replay verdict event holds both fingerprints and both verdicts, and names the attempt only in its data`() =
        runTest {
            val fingerprint = WorkspaceFingerprint.of(workspace).getOrThrow()
            val kind = KindConfig(listOf(CheckConfig.Command("false")))
            val site = site(fingerprint)
            val replayed = site.use { replay().run(target(kind), recorded(fingerprint), it) }

            val event = VerificationReplay.event(FIXED_NOW, ProjectId("p"), target(kind), recorded(fingerprint), site, replayed)

            assertEquals(ReplayVerdict.name, event.kind)
            assertNull(event.attemptId, "the attempt's own events are left as they were")
            assertNull(event.runId)
            assertEquals("t-1", event.issueId)
            assertEquals(
                mapOf(
                    ReplayVerdict.ATTEMPT to "a-1",
                    ReplayVerdict.VERDICT to "refuted",
                    ReplayVerdict.RECORDED_VERDICT to "verified",
                    ReplayVerdict.FINGERPRINT to fingerprint,
                    ReplayVerdict.RECORDED_FINGERPRINT to fingerprint,
                    ReplayVerdict.SITE to ReplayVerdict.WORKSPACE_COPY,
                    ReplayVerdict.CHECKS to "1",
                ),
                event.data,
            )
        }
}
