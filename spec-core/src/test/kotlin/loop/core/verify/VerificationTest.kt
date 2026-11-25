package loop.core.verify

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.delivery.PullRequestDelivery
import loop.core.events.CheckFinished
import loop.core.events.CheckStarted
import loop.core.events.EventDefinition
import loop.core.events.IssueMoved
import loop.core.events.Verdict
import loop.core.kinds.IssueKind
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.orchestrator.FIXED_NOW
import loop.core.orchestrator.FakeTracker
import loop.core.orchestrator.FixedClock
import loop.core.orchestrator.issue
import loop.core.waiting.Wait
import loop.core.workflow.AgentConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.DeliveryConfig
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-54: a [Verification] runs outside the orchestrator, on what its [VerificationHost] gives, so `loop replay` can call
 * it. The checks, the verdict and the delivery are covered by the orchestrator tests; these prove the seam.
 */
class VerificationTest {
    private val root: Path = createTempDirectory("loop-verification").toRealPath()
    private val todo = issue("v-1", description = "Some work")
    private val tracker = FakeTracker(listOf(todo)).apply { definedStatesOverride = listOf("todo", "review", "done") }
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()
    private val envs = mutableListOf<Map<String, String>>()
    private val passing =
        CheckRunner { _, _, env ->
            envs += env
            CheckResult(0, timedOut = false, durationMs = 1, output = "ok")
        }

    private class FakeHost(
        override val tracker: FakeTracker,
        override val loopHome: Path,
    ) : VerificationHost {
        val approvalWaits = mutableListOf<Pair<Wait.Approval, String>>()

        override val projectId = ProjectId("p")
        override val runId = RunId("r-1")
        override val log = KeyValueLog(out = {}, clock = FixedClock(), redactor = Redactor.NONE)
        override val clock = FixedClock()
        override val redactor = SecretRedactor()
        override val io = kotlin.coroutines.EmptyCoroutineContext
        override val fingerprinter = Fingerprinter { Result.success("git:abc123") }
        override val evidence = EvidenceWriter.NONE
        override val frozenCommands = FrozenAcceptanceCommands.NONE
        override val model: String? = null
        override val postedDeliverables: MutableSet<String> = mutableSetOf()

        override suspend fun freezeAcceptanceCommands(
            issueId: String,
            identifier: String,
            commands: List<String>,
            digest: String,
            record: suspend (EventDefinition, Map<String, String>) -> Unit,
        ): Result<FrozenCommands> = Result.success(FrozenCommands(projectId, issueId, identifier, digest, commands.size, FIXED_NOW))

        override suspend fun fetchBase(workflow: WorkflowFile): Result<String> = Result.failure(IllegalStateException("no base"))

        override fun pullRequestDelivery(
            workflow: WorkflowFile,
            record: suspend (EventDefinition, Map<String, String>) -> Unit,
        ): PullRequestDelivery = error("no pull request delivery in this test")

        override fun awaitsApproval(issueId: String): Boolean = approvalWaits.any { it.first.issueId == issueId }

        override suspend fun startApprovalWait(
            wait: Wait.Approval,
            reason: String,
        ) {
            approvalWaits += wait to reason
        }
    }

    private val host = FakeHost(tracker, root.resolve("home"))

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

    private fun verification(kind: KindConfig) =
        Verification(
            todo.id,
            todo.identifier,
            todo.description,
            AttemptId("a-1"),
            workflow(kind),
            IssueKind(KindsConfig.CODE, kind),
            root,
            passing,
            { definition, data -> recorded += definition.name to data },
            { LogContext() },
            host = host,
        )

    private fun names() = recorded.map { it.first }

    @Test
    fun `a passing check is verified and an auto delivery moves the issue through the host's tracker`() =
        runTest {
            val kind = KindConfig(listOf(CheckConfig.Command("true")), delivery = DeliveryConfig(DeliveryPolicy.Auto))
            val verification = verification(kind)

            assertTrue(verification.verify())
            assertNull(verification.deliver("review"))

            assertEquals(listOf(CheckStarted.name, CheckFinished.name, Verdict.name, IssueMoved.name), names())
            assertEquals(Verification.VERIFIED, recorded.single { it.first == Verdict.name }.second[Verdict.VERDICT])
            assertEquals("git:abc123", recorded.single { it.first == Verdict.name }.second[Verdict.FINGERPRINT])
            assertEquals(listOf(todo.id to "review"), tracker.moves)
            // The check runs with the run and attempt of the host and the verification, never more.
            assertEquals("r-1", envs.single()["LOOP_RUN_ID"])
            assertEquals("a-1", envs.single()["LOOP_ATTEMPT_ID"])
            assertEquals("p", envs.single()["LOOP_PROJECT_ID"])
        }

    @Test
    fun `a human delivery starts its approval wait through the host`() =
        runTest {
            val kind = KindConfig(listOf(CheckConfig.Command("true")), delivery = DeliveryConfig(DeliveryPolicy.Human))
            val verification = verification(kind)

            assertTrue(verification.verify())
            assertNull(verification.deliver("review"))

            val (wait, reason) = host.approvalWaits.single()
            assertEquals(todo.id, wait.issueId)
            assertEquals(RunId("r-1"), wait.runId)
            assertEquals("a verified result waits for approval to move to review", reason)
            assertTrue(tracker.moves.isEmpty())
        }

    @Test
    fun `a kind with no check is unverified with its reason`() =
        runTest {
            val verification = verification(KindConfig(emptyList()))

            verification.prepare()
            assertFalse(verification.hasChecks)
            assertFalse(verification.verify())

            assertEquals(Verification.NO_CHECK, verification.unverified)
            assertEquals(listOf(Verdict.name), names())
            assertEquals(Verification.UNVERIFIED, recorded.single().second[Verdict.VERDICT])
        }
}
