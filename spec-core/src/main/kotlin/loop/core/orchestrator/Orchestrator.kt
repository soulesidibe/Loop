package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.AttemptRecord
import loop.api.AttemptRecordWriter
import loop.api.Claim
import loop.api.Claims
import loop.api.DeliveryExecutor
import loop.api.EventSink
import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.HumanChannel
import loop.api.HumanEvent
import loop.api.HumanTouch
import loop.api.Issue
import loop.api.IssueComment
import loop.api.LedgerAttempt
import loop.api.LedgerTurn
import loop.api.ParkReason
import loop.api.ParkedIssues
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.ResourceLockStore
import loop.api.RetryStates
import loop.api.RiskReviewer
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.SecretRedactor
import loop.api.SessionId
import loop.api.TrackerAdapter
import loop.api.TurnId
import loop.api.TurnLedger
import loop.api.WaitReason
import loop.api.WaitingIssues
import loop.core.channel.HumanNotices
import loop.core.channel.NoticeSettings
import loop.core.delivery.AutoMerge
import loop.core.delivery.CloneDelivery
import loop.core.delivery.DeliveryFacts
import loop.core.delivery.DeliveryOutcome
import loop.core.delivery.MergeCheck
import loop.core.delivery.PullRequestDelivery
import loop.core.events.AcceptanceCommands
import loop.core.events.AcceptanceCommandsFrozen
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStalled
import loop.core.events.AttemptStarted
import loop.core.events.BaseFetchFailed
import loop.core.events.BaseFetched
import loop.core.events.BudgetReached
import loop.core.events.BudgetWarning
import loop.core.events.CandidateSkipped
import loop.core.events.ConfigReloadFailed
import loop.core.events.ConfigReloaded
import loop.core.events.ContextSourceMissing
import loop.core.events.ContextUsage
import loop.core.events.ContextWindowUnknown
import loop.core.events.Delivery
import loop.core.events.DeliveryFailed
import loop.core.events.DeliveryQueuePaused
import loop.core.events.DeliveryQueueResumed
import loop.core.events.EventDefinition
import loop.core.events.EvidenceRecordFailed
import loop.core.events.FollowupFiled
import loop.core.events.HookFinished
import loop.core.events.HumanTouched
import loop.core.events.InterruptGraceExpired
import loop.core.events.IssueCommented
import loop.core.events.IssueLabeled
import loop.core.events.IssueMoved
import loop.core.events.IssueParked
import loop.core.events.MergeRequeued
import loop.core.events.Phase
import loop.core.events.PromptRecordFailed
import loop.core.events.QuestionAsked
import loop.core.events.RateLimit
import loop.core.events.ResourceReleased
import loop.core.events.ResultDiscarded
import loop.core.events.ResultInvalid
import loop.core.events.ResultSubmitted
import loop.core.events.Results
import loop.core.events.RunnerHoldLifted
import loop.core.events.RunnerHoldStarted
import loop.core.events.RunnerOutput
import loop.core.events.RunnerOutputRecordFailed
import loop.core.events.SessionRotated
import loop.core.events.SessionStopFailed
import loop.core.events.StopGraceExpired
import loop.core.events.TokenThreshold
import loop.core.events.ToolRequestDropped
import loop.core.events.ToolRequestEvent
import loop.core.events.ToolRequestFailed
import loop.core.events.Tracker
import loop.core.events.TurnCompleted
import loop.core.events.TurnEvent
import loop.core.events.TurnFailed
import loop.core.events.TurnInputRequired
import loop.core.events.TurnStarted
import loop.core.events.Usage
import loop.core.events.Verdict
import loop.core.kinds.IssueKind
import loop.core.kinds.KindLookup
import loop.core.kinds.KindPrompt
import loop.core.kinds.KindWorkspaces
import loop.core.kinds.PinnedKind
import loop.core.kinds.Readiness
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.machine.Machine
import loop.core.merge.AutoMergeGate
import loop.core.merge.Merges
import loop.core.merge.Recheck
import loop.core.merge.RecheckOutcome
import loop.core.merge.WorkspaceMergeGit
import loop.core.reactions.CiFailedReaction
import loop.core.reactions.ReactionMarks
import loop.core.reactions.Reactions
import loop.core.reactions.SendBackOutcome
import loop.core.resources.ResourceGrants
import loop.core.resources.ResourceLocks
import loop.core.template.Template
import loop.core.template.TemplateEngine
import loop.core.trackers.AreaLock
import loop.core.trackers.Followups
import loop.core.trackers.IssueIdDisplay
import loop.core.trackers.NamespacedId
import loop.core.trackers.TrackerPoll
import loop.core.trackers.TrackerSlots
import loop.core.verify.AcceptanceCriteria
import loop.core.verify.CheckRunner
import loop.core.verify.EvidenceWriter
import loop.core.verify.Fingerprinter
import loop.core.verify.PlainWorkspace
import loop.core.verify.ResultFile
import loop.core.verify.SubmittedResult
import loop.core.verify.Verification
import loop.core.verify.VerificationHost
import loop.core.verify.VerifyFindings
import loop.core.verify.WorkspaceFingerprint
import loop.core.verify.WorkspaceGit
import loop.core.verify.commands
import loop.core.verify.judgmentCalls
import loop.core.waiting.Answers
import loop.core.waiting.Asks
import loop.core.waiting.MergePhase
import loop.core.waiting.MergeQueue
import loop.core.waiting.SendBacks
import loop.core.waiting.Wait
import loop.core.waiting.WaitKeys
import loop.core.waiting.Waits
import loop.core.workflow.CheckConfig
import loop.core.workflow.ContextConfig
import loop.core.workflow.FilledPrompt
import loop.core.workflow.GuardsConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindsConfig
import loop.core.workflow.PreflightCheck
import loop.core.workflow.PreflightResult
import loop.core.workflow.PromptBudget
import loop.core.workflow.PromptContent
import loop.core.workflow.PromptFill
import loop.core.workflow.PromptSlots
import loop.core.workflow.PromptVariables
import loop.core.workflow.SessionBrief
import loop.core.workflow.TokenCounters
import loop.core.workflow.ToolOutputTrim
import loop.core.workflow.WorkflowDiff
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkspaceGitMode
import loop.core.workflow.promptLimitFor
import loop.core.workspace.CloneBase
import loop.core.workspace.CloneStart
import loop.core.workspace.CloneState
import loop.core.workspace.HookListener
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import loop.core.workspace.Workspace
import loop.core.workspace.WorkspaceClones
import loop.core.workspace.WorkspaceKey
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.hookEnv
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import loop.core.events.Preflight as PreflightEvent

/** The attempt phases of spec 7.2, recorded in this order (plan D19). */
enum class AttemptPhase {
    PreparingWorkspace,
    BuildingPrompt,
    LaunchingAgentProcess,
    InitializingSession,
    StreamingTurn,
    Finishing,
    Succeeded,
    Failed,
    TimedOut,
    Stalled,
    CanceledByReconciliation,
}

enum class AttemptOutcome { Succeeded, Failed }

/** One attempt of a tick, for the `--once` summary. */
data class AttemptSummary(
    val issueIdentifier: String,
    /** The issue as dispatched, before any turn; with [refreshedIssue] it shows whether the attempt changed it. */
    val dispatchedIssue: Issue,
    val outcome: AttemptOutcome,
    /** Tracker state of the issue after the attempt, null when it could not be read. */
    val issueState: String?,
    val duration: Duration,
    /** The attempt's id, for the retry bookkeeping events. */
    val attemptId: AttemptId? = null,
    /** Why the attempt failed, null when it succeeded. */
    val reason: String? = null,
    /** The issue as last read, after the last turn or after the attempt; null when the refresh failed or found nothing. */
    val refreshedIssue: Issue? = null,
    /** Why a successful attempt ended (an [ExitReason] value), null when it failed or was cut off. */
    val exitReason: String? = null,
    /** How many turns the attempt ran. */
    val turns: Int = 0,
    /** Set when the attempt ends in a park of its own, not a countable failure (a spend budget, m2-20). */
    val parkReason: ParkReason? = null,
    /** What the `issue_parked` event says about [parkReason]; empty without one. */
    val parkData: Map<String, String> = emptyMap(),
    /**
     * Set when reconciliation stopped the attempt (m1-16): `terminal`, `not_active`, `not_eligible` or
     * `not_found`. Such an attempt is neither a failure nor a normal exit, so it gets no retry.
     */
    val reconciled: String? = null,
    /**
     * True when the attempt moved its issue to a handoff state, a defined state neither active nor terminal (m2-23).
     * On a label tracker like GitHub's the issue is still `open` then, so the state alone would not show it.
     */
    val handedOff: Boolean = false,
    /**
     * Set when the attempt's last turn failed with the category `runner_unavailable` (bl-39): the failure is about the
     * runner, not the issue, so it takes no `max_attempts` slot and holds the run instead.
     */
    val runnerUnavailable: RunnerUnavailable? = null,
)

/** A turn failure the runner marked `runner_unavailable` (bl-39): its redacted [reason] and the wait the runtime asked for. */
data class RunnerUnavailable(
    val reason: String,
    val retryAfter: Duration?,
)

/** What one poll did: how many candidates the tracker returned, and the attempts run. */
data class TickResult(
    val candidates: Int,
    val attempts: List<AttemptSummary>,
    /** Attempts that ended in an unexpected exception (logged, not in [attempts]); the caller decides the exit code. */
    val brokenAttempts: Int = 0,
)

/**
 * Polls the tracker and runs eligible issues concurrently, up to `agent.max_concurrent_agents`
 * in all and `agent.max_concurrent_agents_by_state` per state (spec 8, 8.3, 16.2 to 16.5).
 * Claims are decided in memory (plan D16) and written to [claims] for the next process (m2-02).
 * Events go to [sink], operator lines to [log].
 *
 * Single threaded contract: [tick] and [runUntilCancelled] must run on a single threaded
 * dispatcher (`runBlocking` in production, `runTest` in tests). The claim and running maps are
 * plain collections with no lock; nothing suspends between the eligibility check, the claim and
 * the launch of an attempt, so two attempts can never claim the same issue.
 *
 * A sink failure is logged and never stops an attempt. The turn id is taken from
 * the runner's turn result, so phases before [AttemptPhase.Finishing] carry no turn id.
 */
class Orchestrator(
    workflow: WorkflowFile,
    private val tracker: TrackerAdapter,
    private val runner: AgentRunner,
    sink: EventSink,
    private val log: KeyValueLog,
    private val clock: Clock,
    private val workspaces: WorkspaceManager,
    private val newId: () -> String,
    private val runId: RunId,
    private val projectId: ProjectId,
    private val launcher: Path,
    private val loopHome: Path,
    /** The host `PATH`, which the runner env extends with the launcher's folder. */
    private val hostPath: String?,
    /** Name printed as `sink=` when [sink] fails. */
    private val sinkName: String = "sqlite",
    private val templates: TemplateEngine = TemplateEngine(),
    /** Runs at the start of every tick; a failure skips dispatch for that tick (spec 6.3). */
    private val preflight: PreflightCheck = PreflightCheck { PreflightResult.Ok(workflow) },
    /** Parked issues, read on every tick so a `loop resume` from another process is seen (m1-07). */
    private val parked: ParkedIssues,
    /**
     * The run's redactor (m1-11), shared with [log] and the store. Text that is cut before it is
     * written (runner lines, hook commands) is redacted here first, so no fragment survives the cut.
     */
    private val redactor: SecretRedactor,
    /** The secrets of a workflow, env name to value, learned on every Ok preflight. */
    private val secretsOf: (WorkflowFile) -> Map<String, String>,
    /**
     * The `reason` of `attempt_finished` for a canceled attempt, read when the attempt unwinds, so the
     * caller can say why it canceled the run (`stop_requested` for `loop stop`, `signal` otherwise).
     */
    private val cancelReason: () -> String = { CANCELED },
    /** Writes each Attempt's tool config snapshot, read by `loop tool` instead of `WORKFLOW.md` (m1-23). */
    private val toolConfigWriter: ToolConfigWriter = ToolConfigWriter.NONE,
    /**
     * Where the attempt directory's file work and the host side tracker writes run (m1-23). The cli
     * passes `Dispatchers.IO`; tests keep the caller's dispatcher, so virtual time stays deterministic.
     */
    private val io: CoroutineContext = EmptyCoroutineContext,
    /** The running Loop build, an opaque label recorded on every `attempt_started` (m1-12). */
    private val loopVersion: String = "unknown",
    /**
     * Writes each Attempt's turn 1 rendered prompt (m1-13) and every runner line in full (m2-18) to its
     * Attempt file, kept after the Attempt. Null, for tests that do not look at the file, writes nothing.
     */
    private val attemptRecords: AttemptRecordWriter? = null,
    /**
     * The highest token threshold already recorded for an attempt, read when the attempt starts, so a
     * step is never recorded twice for one attempt, also after a restart (m1-38). The cli reads the store.
     */
    private val recordedThreshold: suspend (AttemptId) -> Long = { 0L },
    /**
     * Follows the run for the human readable run log (m1-39): every recorded event, each runner line in
     * full and each poll. It sees an event before the store does, so a failed store write still shows.
     */
    private val observer: RunObserver = RunObserver.NONE,
    /** The runner of an Attempt, built from the workflow it starts with, so a reload reaches the next Attempt (m1-15). */
    private val runnerFor: (WorkflowFile) -> AgentRunner = { runner },
    /** The workspaces and hooks of an Attempt or a removal, from the workflow in effect then (m1-15). */
    private val workspacesFor: (WorkflowFile) -> WorkspaceManager = { workspaces },
    /** What a reload from the first workflow to the second cannot apply live, one sentence each, logged at warn (m1-15). */
    private val reloadWarnings: (WorkflowFile, WorkflowFile) -> List<String> = { _, _ -> emptyList() },
    /** The retry queue and its counters in the store, read back on the first tick after a restart (m2-02). */
    retryStates: RetryStates = RetryStates.NONE,
    /** The claims in the store, with the session of each claimed attempt (m2-02). */
    private val claims: Claims = Claims.NONE,
    /**
     * The check runner of an Attempt in Loop mode (m2-03), built from the workflow it starts with. The cli
     * gives it the agent env policy; the default refuses every check, so no move is ever let through unchecked.
     */
    private val checkRunnerFor: (WorkflowFile) -> CheckRunner = { CheckRunner.NONE },
    /** The waiting issues in the store (m2-04): never dispatched while they wait, read back on the first tick after a restart. */
    private val waiting: WaitingIssues = WaitingIssues.NONE,
    /** Saves each check's output as evidence (m2-05); the cli writes the files beside the Attempt file. */
    private val evidence: EvidenceWriter = EvidenceWriter.NONE,
    /** The workspace fingerprint taken before a verification's checks (m2-05). */
    private val fingerprinter: Fingerprinter = WorkspaceFingerprint,
    /** The ledger line of each turn (m2-19); the cli appends it to `<LOOP_HOME>/ledger/<project_id>.jsonl`. */
    private val turnLedger: TurnLedger = TurnLedger.NONE,
    /** The ledger row of each finished attempt (m2-19), and the last row of an issue at dispatch; the cli gives the store. */
    private val attemptLedger: AttemptLedger = AttemptLedger.NONE,
    /**
     * The acceptance command list of each issue, frozen at its first dispatch (m2-09): later attempts and restored
     * waits verify against it, so a list rewritten in the issue between attempts never runs.
     */
    private val frozenCommands: FrozenAcceptanceCommands = FrozenAcceptanceCommands.NONE,
    /**
     * The pull request delivery of a workflow whose kind sets `delivery.pull_request` (m2-25), built from the workflow
     * in effect when it delivers; null when this host cannot deliver one, which fails such a delivery for good.
     */
    private val deliveryFor: (WorkflowFile) -> DeliveryExecutor? = { null },
    /** The git of the merge check in an issue's workspace (m2-26): hooks off, minimal env, no token, a timeout. */
    private val workspaceGit: WorkspaceGit = WorkspaceGit(),
    /**
     * Every event of an attempt (m2-28), read by the auto merge gate for the verdict and `diff_base_unpinned`. The
     * default cannot read them, which fails those conditions: no auto merge without the proof.
     */
    private val attemptEvents: suspend (AttemptId) -> Result<List<RecordedEvent>> = {
        Result.failure(IllegalStateException("this Loop cannot read the events of an attempt"))
    },
    /** The read only reviewer of the merge risk assessment (m2-28); null when this host has none, which falls back. */
    private val riskReviewerFor: (WorkflowFile) -> RiskReviewer? = { null },
    /** m3-16: the machine limits, read every tick, and the machine slots of the shared store. */
    private val machine: Machine = Machine.NONE,
    /**
     * bl-175: the `delivery_queue_paused` an earlier run left with no `delivery_queue_resumed` after it, read once at the
     * first tick, which records the resume by the restart; null when none, or when this host cannot read it.
     */
    private val queuePausedBefore: suspend () -> RecordedEvent? = { null },
    /**
     * m4-35: the reaction events of the project in the run store, every run, which count the `ci_failed` reactions of
     * an issue across a restart and, since m4-36, hold the reviews `changes_requested` sent and escalated; the default
     * has none, so the count is this process's.
     */
    private val reactionsBefore: suspend () -> Result<List<RecordedEvent>> = { Result.success(emptyList()) },
    /**
     * bl-224: the reaction events and the counter reset traces of the project in the run store, every run, in recording
     * order, read once at the restore of the retry states to mark the issues a reaction sent back; the default has none.
     */
    private val retryEventsBefore: suspend () -> Result<List<RecordedEvent>> = { Result.success(emptyList()) },
    /**
     * m4-37: the `merge_check_finished` events of the project in the run store, every run, which say whether the merge
     * check passed on a pull request's head, green under `ci: none` for `approved_green`; the default has none.
     */
    private val mergeChecksBefore: suspend () -> Result<List<RecordedEvent>> = { Result.success(emptyList()) },
    /** m4-10: true once `loop drain` asked this run to drain, read at each dispatch. */
    private val drainRequested: () -> Boolean = { false },
    /** m4-50: the resource locks of the shared store, freed at the end of each attempt. */
    private val resourceLockStore: ResourceLockStore = ResourceLockStore.NONE,
    /** m4-25: the resource grants of `loop run`, which give back what an attempt prepared before its locks are freed. */
    private val resourceGrants: ResourceGrants? = null,
    /** m4-30: the channel through which a human is told that a run needs them; the cli gives the terminal channel. */
    private val channel: HumanChannel = HumanChannel.NONE,
) {
    /** The HEAD and working tree reads for the progress between rotations (bl-136), bounded and cancellable (bl-149). */
    private val workspaceFacts = WorkspaceFacts(workspaceGit)

    /**
     * The merge waits (m2-26, m2-28, m3-15, bl-85): the merge check, the CI wait, the merge and the auto merge gate, in
     * their own module. This class keeps the slot decision and the background launch, see [syncMerges].
     */
    private val merges: Merges by lazy {
        val git = WorkspaceMergeGit(workspaceGit)
        Merges(
            waits = waits,
            tracker = tracker,
            executorFor = ::executorFor,
            locate = { workflow, key -> workspacesFor(workflow).locate(key) },
            git = git,
            fingerprinter = fingerprinter,
            recheck = Recheck(::recheckMerge),
            gate =
                AutoMergeGate(
                    git = git,
                    attemptEvents = attemptEvents,
                    reviewerFor = riskReviewerFor,
                    attemptLedger = attemptLedger,
                    budgetPassed = ReviewerBudget(spendReading)::passed,
                    setupOf = LedgerMeter::setupOf,
                    attemptsFolder = attemptsFolder(loopHome, projectId),
                    redactor = redactor,
                    clock = clock,
                    io = io,
                    newId = newId,
                    projectId = projectId,
                    runId = runId,
                ),
            workflow = { effective },
            rejected = { rejected },
            onFindings = { issueId, text -> verifyFindings[issueId] = text },
            removeWorkspace = { removeWorkspace(it) },
            cloneBase = ::writeCloneBase,
            wake = { attemptEnded.trySend(Unit) },
            onCiTimeout = { pauseQueue(it) },
            notices = notices,
            ciFailed = ciFailed,
            sink = this.sink,
            sinkName = sinkName,
            log = log,
            redactor = redactor,
            clock = clock,
            io = io,
            projectId = projectId,
            runId = runId,
        )
    }

    /**
     * m4-34: the reactions of `reactions:` to the waiting pull requests, in their own module. A reaction that sends the
     * issue back ends its wait and takes a `max_attempts` slot through the retry coordinator.
     */
    private val reactions: Reactions by lazy {
        Reactions(
            executorFor = ::executorFor,
            record = ::recordWaitEvent,
            sendBack = ::reactionSendBack,
            contextOf = ::waitContext,
            log = log,
            redactor = redactor,
            clock = clock,
            io = io,
            reactionsBefore = reactionsBefore,
            escalate = notices::changesRequested,
            mergeChecksBefore = mergeChecksBefore,
            approvedGreen = notices::approvedGreen,
        )
    }

    /** m4-35: the `ci_failed` reaction to a red verdict of the CI wait; its send back goes through the retry coordinator. */
    private val ciFailed: CiFailedReaction by lazy {
        CiFailedReaction(
            reactionsBefore = reactionsBefore,
            record = ::recordWaitEvent,
            sendBack = { wait, issue, findings ->
                endWaiting(wait)
                retries.onReaction(issue, wait.attemptId, effective).also { if (it) verifyFindings[wait.issueId] = findings }
            },
            contextOf = ::waitContext,
            log = log,
            redactor = redactor,
        )
    }

    /**
     * The send back of a reaction to an approval wait (m4-34): ends the wait only when it is still neither approved nor
     * sent back, in the same store change (bl-224), so a `loop approve` or a `loop send-back` committed while the
     * reaction read GitHub is kept, then schedules the retry on a `max_attempts` slot with the findings.
     */
    private suspend fun reactionSendBack(
        wait: Wait.Approval,
        issue: Issue,
        findings: String,
    ): SendBackOutcome {
        val ended =
            waits.endUndecided(wait).getOrElse { error ->
                log.log(LogLevel.WARN, WAITING, LogOutcome.FAILED, waitContext(wait), reason = error.describe())
                return SendBackOutcome.Kept
            }
        attemptEnded.trySend(Unit)
        if (!ended) return SendBackOutcome.Decided
        if (!retries.onReaction(issue, wait.attemptId, effective)) return SendBackOutcome.Parked
        verifyFindings[wait.issueId] = findings
        return SendBackOutcome.Scheduled
    }

    /** The sink every event goes through, the retry coordinator's too; the observer sees each one first. */
    private val sink: EventSink = ObservedSink(sink, observer)

    /** m3-16: the machine limits of each tick, the machine slots of this process and the machine day budget. */
    private val machineGate = MachineGate(machine, log, clock, newId)

    /** m3-16: the machine slot each running attempt holds, by issue id; none without a machine cap. */
    private val machineSlots = mutableMapOf<String, String>()

    /** m4-50: the resource lock calls, over the shared store. */
    private val resourceLocks =
        ResourceLocks(resourceLockStore, this.sink, clock, runId) { event, error ->
            val context = LogContext(runId, event.projectId, event.issueId, event.issueIdentifier, event.attemptId)
            log.log(LogLevel.WARN, "sink", LogOutcome.FAILED, context, fields = mapOf("sink" to sinkName), reason = error.describe())
        }

    /** How many attempts this orchestrator saw canceled, for the run level `run_stopped` event. */
    var attemptsCanceled: Int = 0
        private set

    // Plan D5: the one place the workflow is read from. An Ok preflight of a changed file swaps it (m1-15);
    // an attempt reads it once when it starts.
    private var effective: WorkflowFile = workflow

    /** The version of the last rejected file, [UNREADABLE] for a file that could not be read, so each is recorded once. */
    private var rejectedVersion: String? = null

    /** Why the file of [rejectedVersion] was rejected, null since the last passing preflight. */
    private var rejected: PreflightResult.Failed? = null

    private val claimed = mutableSetOf<String>()

    /** The area lock of the dispatch (m4-17): two issues whose areas overlap are never worked at once. */
    private val areas = AreaLock()

    /**
     * The waits of the project (m2-04, m2-24, m2-26, bl-79): a waiting issue is never dispatched and its retries never
     * fire, whatever its tracker state. The store is read once per tick. A merge check holds an agent slot like an
     * attempt, and at most one runs per project, so two issues never invalidate each other's check.
     */
    private val waits = Waits(waiting, projectId)

    /**
     * bl-188: every verification build and merge check build passes here first, an attempt's, a merge check's and a
     * restored wait's rerun, so at most `agent.max_concurrent_verifications` (else `agent.max_concurrent_agents`) run at
     * once. Read from [effective] on every admission, so a reload applies to the builds that have not started yet.
     */
    private val buildGate =
        BuildGate({ effective.config.agent.buildCapacity }) { context, ahead ->
            log.log(
                LogLevel.INFO,
                BUILD_GATE,
                LogOutcome.SCHEDULED,
                context,
                mapOf("ahead" to ahead),
                "verification waits for a build slot ($ahead ahead)",
            )
        }

    /** True once [restoreWaiting] resumed the waits a previous process left. */
    private var waitingRestored = false

    /**
     * Running issue id to its normalized state, for the per state slots: the state at dispatch, then
     * the state reconciliation read on each tick (m1-16).
     */
    private val running = mutableMapOf<String, String>()

    /** The attempt of each running issue id, so reconciliation can stop it (m1-16). */
    private val executions = mutableMapOf<String, Execution>()

    /** Ticks skipped by a failed preflight since the last Ok one. */
    private var skippedTicks = 0

    private val pollContext = LogContext(runId = runId, projectId = projectId)

    /** m4-13: every tracker of the run, each polled and reconciled on its own; [tracker] routes each write. */
    private val trackers = TrackerPoll.of(tracker, workflow.trackerId)

    private val retries =
        RetryCoordinator(
            trackers,
            parked,
            this.sink,
            sinkName,
            log,
            clock,
            runId,
            projectId,
            ::onTerminalRetryDropped,
            retryStates,
            retryEventsBefore,
        ) { park, reason ->
            notices.parked(park, reason)
        }

    /**
     * m4-30: the notices to a human of a park and of an approval wait, through [channel] and `hooks.on_human_needed`, as
     * the workflow in effect says at each notice. See [HumanNotices] for the never twice rule.
     */
    private val notices: HumanNotices by lazy {
        HumanNotices(
            channel = channel,
            settings = {
                val config = effective.config
                NoticeSettings(
                    hook = config.hooks.onHumanNeeded,
                    timeoutMs = config.hooks.timeoutMs,
                    folder = effective.path.toAbsolutePath().parent,
                    display = IssueIdDisplay.of(effective.trackers),
                    channel = config.channel.kind,
                )
            },
            env = mapOf("LOOP_PROJECT_ID" to projectId.value, "LOOP_WORKFLOW" to effective.path.toString()),
            recorder = NoticeRecording(this.sink, sinkName, log, clock, projectId, runId, ::HookRecorder),
            redact = redactor::redact,
        )
    }

    /** bl-39: the run level hold of a runner that cannot reach its model; it gates every dispatch. */
    private val hold = RunnerHold(clock)

    /** m4-46: whether the next attempt of an issue the hold took resumes its last session. */
    private val networkResume = NetworkResume()

    /** bl-175: the pause of the delivery queue after the head's CI timed out; it stops every merge check from starting. */
    private val queuePause = DeliveryQueuePause()

    /** m4-10: the drain of the run, once `loop drain` asked for it: nothing new starts, the run stops once nothing runs. */
    private val drain = RunDrain(drainRequested, this.sink, sinkName, log, clock, runId, projectId)

    /** The spec attempt of each issue dispatched by a retry, for the `attempt` template variable. */
    private val dispatchAttempts = mutableMapOf<String, Int>()

    /** The `loop.retry_kind` of each issue dispatched by a retry (m3-11), absent on a first dispatch. */
    private val dispatchRetryKinds = mutableMapOf<String, String>()

    /** The answered ask wait of each issue dispatched after `loop answer` (m4-52), for `loop.answer`. */
    private val dispatchAnswers = mutableMapOf<String, Wait.Ask>()

    /** The sent back approval wait of each issue dispatched after `loop send-back` (m4-32), for `loop.verify_findings`. */
    private val dispatchSentBacks = mutableMapOf<String, Wait.Approval>()

    /**
     * The findings of the refutation that ended an issue's last attempt or restored check, by issue id, for the
     * `loop.verify_findings` of its next attempt (m2-06). Taken by the next render; kept in memory only.
     */
    private val verifyFindings = mutableMapOf<String, String>()

    /**
     * The deliverable comments posted and not yet followed by their move or wait, by issue, attempt and fingerprint
     * (m4-22), so a retry after a failed move never posts the same deliverable twice. Kept in memory only.
     */
    private val postedDeliverables: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What every [Verification] of this run reads from the orchestrator (m4-54). */
    private val verificationHost: VerificationHost =
        object : VerificationHost {
            override val projectId get() = this@Orchestrator.projectId
            override val runId get() = this@Orchestrator.runId
            override val loopHome get() = this@Orchestrator.loopHome
            override val tracker get() = this@Orchestrator.tracker
            override val log get() = this@Orchestrator.log
            override val clock get() = this@Orchestrator.clock
            override val redactor get() = this@Orchestrator.redactor
            override val io get() = this@Orchestrator.io
            override val fingerprinter get() = this@Orchestrator.fingerprinter
            override val evidence get() = this@Orchestrator.evidence
            override val frozenCommands get() = this@Orchestrator.frozenCommands
            override val model get() = runner.model
            override val postedDeliverables get() = this@Orchestrator.postedDeliverables

            override suspend fun freezeAcceptanceCommands(
                issueId: String,
                identifier: String,
                commands: List<String>,
                digest: String,
                record: suspend (EventDefinition, Map<String, String>) -> Unit,
            ): Result<FrozenCommands> = this@Orchestrator.freezeAcceptanceCommands(issueId, identifier, commands, digest, record)

            override suspend fun fetchBase(workflow: WorkflowFile): Result<String> = deliveryStep { executorFor(workflow).fetchBase() }

            override suspend fun projectCommit(
                workflow: WorkflowFile,
                ref: String,
            ): String? =
                try {
                    withContext(io) {
                        workspaceGit
                            .run(workflow.path.parent, "rev-parse", "--verify", "-q", "$ref^{commit}")
                            .takeIf { it.code == 0 }
                            ?.stdout
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    }
                } catch (e: IOException) {
                    null
                }

            override suspend fun writeCloneBase(
                workflow: WorkflowFile,
                workspace: Path,
                base: String,
                sha: String,
            ): String? = this@Orchestrator.writeCloneBase(workflow, workspace, base, sha)

            override suspend fun importVerified(
                workflow: WorkflowFile,
                workspace: Path,
                sha: String,
            ): String? {
                val clones = workspacesFor(workflow).clones ?: return null
                return try {
                    withContext(io) {
                        if (clones.state(workspace) == CloneState.Clone) {
                            clones.import(workspace, sha, WorkspaceClones.branch(WorkspaceKey(workspace.fileName.toString())))
                        }
                    }
                    null
                } catch (e: IOException) {
                    "cannot import the verified $sha into the project: ${e.message}"
                }
            }

            override fun pullRequestDelivery(
                workflow: WorkflowFile,
                record: suspend (EventDefinition, Map<String, String>) -> Unit,
            ): PullRequestDelivery = this@Orchestrator.pullRequestDelivery(workflow, record)

            override fun awaitsApproval(issueId: String): Boolean = waits.awaitsApproval(issueId)

            override suspend fun startApprovalWait(
                wait: Wait.Approval,
                reason: String,
            ) {
                notices.waiting(wait, HumanEvent.ApprovalWaiting, reason) { noticed ->
                    waits.hold(noticed)
                    storeWaiting(noticed) { waits.save(noticed) }
                }
            }
        }

    /**
     * What each attempt running in this process spent so far, by attempt id, for the day budgets (m2-20): an
     * attempt's ledger row is written only when it ends. Removed once the row is written.
     */
    private val liveSpend = ConcurrentHashMap<AttemptId, LiveSpend>()

    /** What was spent against the issue and day budgets, the ledger rows and [liveSpend] (bl-84). */
    private val spendReading = SpendReading(attemptLedger, projectId, clock) { liveSpend.toMap() }

    // False when the last tick stopped before firing the due retries (failed preflight or poll): an overdue
    // entry would then make runUntilCancelled sleep 1 ms in a loop, so it sleeps the polling interval instead.
    private var retriesFired = false

    /** Signalled when an attempt ends, so the poll sleep of [runUntilCancelled] can see a new retry. */
    private val attemptEnded = Channel<Unit>(Channel.CONFLATED)

    /** The last `candidate_skipped` reason recorded per issue id, so an unchanged skip is recorded once. */
    private val lastSkipReasons = mutableMapOf<String, String>()

    /** Issue ids bounced by the readiness check in this process and still not ready (m2-11). */
    private val bounced = mutableSetOf<String>()

    /**
     * Issue ids whose merge wait the early conflict check of this tick ended as a merged delivery (bl-182), or the CI
     * poll before a `ci_failed` send back (m4-35): the tick read them active before, so they are not dispatched in it.
     */
    private val settledEarly = mutableSetOf<String>()

    /**
     * True when the merge waits still hold in memory a CI poll or an early conflict check of the issue [issueId]
     * (bl-182), for tests: a wait that ended holds neither.
     */
    internal fun mergeMemoryHeld(issueId: String): Boolean = merges.remembers(issueId)

    /**
     * One poll: dispatches what the free slots allow, then waits for those attempts.
     * With `--once` this runs at most `agent.max_concurrent_agents` attempts.
     */
    suspend fun tick(): TickResult =
        supervisorScope {
            val dispatched = dispatch(this)
            // A restored wait's checks run in this tick too, so `--once` sees them through (m2-04).
            dispatched.verifications.awaitAll()
            val results = dispatched.attempts.awaitAll()
            val attempts = results.filterNotNull()
            TickResult(candidates = dispatched.candidates, attempts = attempts, brokenAttempts = results.size - attempts.size)
        }

    /** What [dispatch] did: the candidates the tracker returned and the attempts it launched (null for a broken one). */
    private class Dispatched(
        val candidates: Int,
        val attempts: List<Deferred<AttemptSummary?>>,
        /** The checks of waits a previous process left, run again on the first tick (m2-04). */
        val verifications: List<Deferred<Unit>> = emptyList(),
    )

    /**
     * Preflight, fetch, unpark changed parked issues, fire due retries, then filter, sort and
     * fill the free slots: each issue is claimed before its attempt is launched in [scope].
     */
    private suspend fun dispatch(scope: CoroutineScope): Dispatched {
        retriesFired = false
        // Spec 8.1 and 16.2: before the preflight, so a broken WORKFLOW.md still stops what the tracker ended.
        reconcile()
        // m4-10: from the first dispatch after `loop drain`, no candidate and no retry of another issue starts.
        drain.notice(drainFacts())
        // bl-101: before the preflight, so a slot whose release failed is not held while config.yaml stays malformed.
        machineGate.retryReleases(pollContext)
        if (!passesPreflight()) return Dispatched(candidates = 0, attempts = emptyList())
        // bl-188: a reload that raised the build capacity starts the queued builds it now has room for.
        buildGate.admit()
        // m4-13: each tracker polled with its own active states; one that fails is skipped this tick, the others go on.
        val polled = trackers.poll(effective.trackers)
        polled.failures.forEach { (id, error) ->
            log.log(LogLevel.ERROR, "poll", LogOutcome.FAILED, pollContext, fields = mapOf("tracker" to id), reason = error.describe())
        }
        if (polled.nothingAnswered) return Dispatched(candidates = 0, attempts = emptyList())
        val issues = polled.issues
        // m2-02: a restart dispatches nothing before the stored retry queue is back.
        if (!retries.restore(effective)) return Dispatched(candidates = issues.size, attempts = emptyList())
        // m2-04: nor before the stored waits are back, so a waiting issue is never dispatched. One read per tick (bl-79).
        if (!readWaits()) return Dispatched(candidates = issues.size, attempts = emptyList())
        val restored = if (polled.failures.isEmpty()) restoreWaiting(scope, issues) else emptyList()
        // m2-24: approvals given from another terminal, and the approval waits a human ended by moving the issue.
        val delivered = syncApprovals(issues)
        // m4-31: the ask waits a human ended by moving the issue.
        syncAsks(issues)
        // m4-34: the reactions to the waiting pull requests, before the due retries fire.
        reactions.poll(effective, waits.current(WaitReason.Approval).filterIsInstance<Wait.Approval>(), issues, claimed + running.keys)
        // m2-26, bl-172: the merge waits, one delivery at a time, the head of the queue on a free agent slot.
        val verifications = restored + syncMerges(scope, issues)
        val parkedIds =
            retries.stillParked(issues) ?: return Dispatched(candidates = issues.size, attempts = emptyList(), verifications)
        // bl-39: while the runner hold waits for its probe, no attempt or retry is dispatched; at the probe, only one.
        var gate = hold.gate()
        if (gate == RunnerHold.Gate.Closed) return Dispatched(candidates = issues.size, attempts = emptyList(), verifications)
        val held = drain.heldBack(retries.queued().keys)
        val fire =
            retries.fireDue(
                issues,
                effective,
                busy = claimed + running.keys + waits.busy + held,
                parked = parkedIds,
                polled = polled::answered,
            )
        retriesFired = true
        val now = clock.now()
        val retryAttempts = fire.dispatch.associate { it.issue.id to it.attempt }
        val retryKinds = fire.dispatch.mapNotNull { due -> due.retryKind?.let { due.issue.id to it } }.toMap()
        val retryDueAt = fire.dispatch.associate { it.issue.id to it.firstDueAt }
        // Due retries go through their by-id refresh instead of the candidate list.
        val candidates =
            drain.candidates(
                issues.filter {
                    it.id !in parkedIds &&
                        it.id !in fire.handled &&
                        !waits.isBusy(it.id) &&
                        it.id !in delivered &&
                        it.id !in settledEarly &&
                        !retries.isWaiting(it.id, now)
                },
            )
        val routable =
            (candidates + fire.dispatch.map { it.issue })
                .filter(::isEligible)
                .distinctBy { it.id }
                .let { trackers.withCrossTrackerBlockers(it, effective.trackers) }
                // The plan's order, so the skips below are recorded in the order the slots fill.
                .sortedWith(SlotPlan.slotOrder(retryDueAt))
        val skips = mutableMapOf<Issue, String>()
        // The tracker adapter decides blockers; the orchestrator only reads its verdict.
        routable.filterNot { it.dispatchable }.forEach { skips[it] = checkNotNull(it.blockedReason) }
        val dispatchable = routable.filter { it.dispatchable }
        // m2-11: a due retry already passed the readiness check at its first dispatch.
        val notReady =
            dispatchable
                .filter { it.id !in retryAttempts }
                .associate { it.id to missingSections(it) }
                .filterValues { it.isNotEmpty() }
        for (issue in dispatchable.filter { it.id in notReady }) {
            skips[issue] = "$NOT_READY (missing ${notReady.getValue(issue.id).joinToString(", ")})"
        }
        // m4-17: a candidate whose area overlaps a claimed issue's is skipped in its place.
        val eligible =
            areas.admit(
                dispatchable.filter { it.id !in notReady },
                holders = claimed + waits.verifying + retries.queued().keys,
                seen = executions.values.map { it.issue } + issues,
                skips = skips,
            )
        // m4-17: a due retry held by its area or a cross tracker blocker waits a poll interval, like one with no slot.
        for ((issue, reason) in skips.filterKeys { it.id in retryAttempts }) {
            retries.requeueNoSlot(issue, effective, reason.substringBefore(' '))
        }
        if (eligible.isEmpty()) {
            bounceNotReady(dispatchable.filter { it.id in notReady }, notReady)
            recordSkips(skips)
            // Idle only when nothing runs either; attempts in flight are not an idle poll.
            if (running.isEmpty()) {
                log.log(LogLevel.INFO, "poll", LogOutcome.IDLE, pollContext, fields = mapOf("candidates" to 0))
            }
            observer.polled(emptyList(), running.size, effective.config.agent.maxConcurrentAgents)
            return Dispatched(candidates = issues.size, attempts = emptyList(), verifications)
        }
        log.log(
            LogLevel.INFO,
            "poll",
            LogOutcome.COMPLETED,
            pollContext,
            fields = mapOf("candidates" to eligible.size),
        )
        observer.polled(eligible.map { it.identifier }, running.size, effective.config.agent.maxConcurrentAgents)
        val eligibleIds = eligible.map { it.id }.toSet()
        // bl-86: step two of the slot plan; a due retry with no slot is requeued, its `retry_scheduled` says why.
        val slots =
            SlotPlan.attempts(
                effective.config.agent,
                slotUse(),
                due = fire.dispatch.filter { it.issue.id in eligibleIds },
                candidates = eligible.filter { it.id !in retryAttempts },
                trackerOf = { effective.trackers.trackerOf(it)?.id },
                held = areas::heldBy,
            )
        val attempts = mutableListOf<Deferred<AttemptSummary?>>()
        // Due retries with no slot, each with the error its `retry_scheduled` gives, null for the orchestrator's own.
        val noSlotRetries = mutableListOf<Pair<Issue, String?>>()
        // A due retry is requeued, its `retry_scheduled` says why; a plain candidate is a skip.
        val skip = { issue: Issue, reason: String, retryError: String? ->
            if (issue.id in retryAttempts) noSlotRetries += issue to retryError else skips[issue] = reason
        }
        // m3-16: a crossed machine day budget holds every new dispatch of this tick, a due retry included.
        val launches =
            if (machineGate.budgetHolds(liveSpend.toMap(), pollContext)) {
                (slots.launches + slots.requeues + slots.skips + slots.held.keys).forEach {
                    skip(it, MachineGate.MACHINE_BUDGET, MachineGate.MACHINE_BUDGET)
                }
                emptyList()
            } else {
                slots.held.forEach { (issue, reason) -> skip(issue, reason, AreaLock.REASON) }
                slots.skips.forEach { skips[it] = NO_AVAILABLE_SLOTS }
                slots.requeues.forEach { noSlotRetries += it to null }
                slots.launches
            }
        var probing = false
        // The machine slot claim is the one suspension from the plan to the last launch (m3-16). Meanwhile attempts can
        // only end, which frees slots, so the plan still holds; the claim in memory and the launch follow with none.
        for (issue in launches) {
            // A launch is a proposal: an issue claimed or running since the plan read its inputs is left out.
            if (issue.id in claimed || issue.id in running) continue
            // bl-39: the probe of the runner hold is the first launch in slot order, the next due retry; the rest wait for it.
            if (probing || gate == RunnerHold.Gate.Closed) {
                skip(issue, RUNNER_UNAVAILABLE, RUNNER_UNAVAILABLE)
                continue
            }
            val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier)
            // m3-16: the plan's launch then claims a machine slot in the shared store; none free turns it into a skip.
            val slot = machineGate.claim(context)
            if (slot == SlotClaim.Refused) {
                skip(issue, MachineGate.NO_MACHINE_SLOT, MachineGate.NO_MACHINE_SLOTS)
                continue
            }
            // bl-101: an attempt that ended during the claim may have started the runner hold, so the gate is read again.
            gate = hold.gate()
            if (gate == RunnerHold.Gate.Closed) {
                if (slot is SlotClaim.Held) machineGate.release(slot.holder, context)
                skip(issue, RUNNER_UNAVAILABLE, RUNNER_UNAVAILABLE)
                continue
            }
            if (slot is SlotClaim.Held) machineSlots[issue.id] = slot.holder
            claimed += issue.id
            running[issue.id] = issue.state.normalized()
            executions[issue.id] = Execution(issue)
            retryAttempts[issue.id]?.let { dispatchAttempts[issue.id] = it }
            retryKinds[issue.id]?.let { dispatchRetryKinds[issue.id] = it }
            waits.answered(issue.id)?.let { dispatchAnswers[issue.id] = it }
            waits.sentBack(issue.id)?.let { dispatchSentBacks[issue.id] = it }
            if (gate == RunnerHold.Gate.Probe) {
                hold.probeStarted(issue.id)
                probing = true
            }
            // bl-101: started atomically, like a merge check's job (bl-180), so a scope cancelled since the claim still
            // runs its finally, which releases the machine slot and the claim; it runs nothing else.
            @OptIn(DelicateCoroutinesApi::class)
            val attempt = scope.async(start = CoroutineStart.ATOMIC) { attemptAndRelease(issue) }
            attempts += attempt
        }
        noSlotRetries.forEach { (issue, error) ->
            if (error == null) retries.requeueNoSlot(issue, effective) else retries.requeueNoSlot(issue, effective, error)
        }
        bounceNotReady(dispatchable.filter { it.id in notReady }, notReady)
        // bl-101: a skip of the launch loop was added after the slot plan's own, so they are recorded in slot order.
        val slotOrder = routable.withIndex().associate { (index, issue) -> issue.id to index }
        recordSkips(skips.entries.sortedBy { slotOrder[it.key.id] }.associate { it.toPair() })
        return Dispatched(candidates = issues.size, attempts = attempts, verifications)
    }

    /** What the [drain] reads to know what still runs (m4-10). */
    private fun drainFacts(): DrainFacts =
        DrainFacts(
            running = executions.values.map { it.issue },
            waits = waits.current(),
            queued = retries.queued(),
            queuePaused = queuePause.paused != null,
            mergeJobs = waits.busy.filter(waits::isChecking).toSet(),
        )

    /**
     * What holds an agent slot now, for the [SlotPlan]: the running attempts by state and by tracker (m4-15), and the
     * running merge checks.
     */
    private fun slotUse(): SlotUse =
        SlotUse(running.values.toList(), waits.checks, TrackerSlots.runningTrackers(running.keys, effective.trackers))

    /**
     * The sections the kind's readiness check finds missing in [issue] (m2-11); empty when it is ready, in
     * Symphony mode and for a kind without `ready`.
     */
    private fun missingSections(issue: Issue): List<String> {
        val ready =
            KindLookup
                .of(effective, issue)
                ?.config
                ?.ready ?: return emptyList()
        return Readiness.missingSections(issue.description, ready)
    }

    /**
     * Bounces each issue that failed the readiness check (m2-11, plan 6.2): one comment saying why, then the
     * `needs-info` label. An issue that already has the label gets nothing, so a restart does not repeat it,
     * and an issue bounced once in this process is not bounced again while it stays not ready, also when its
     * label write failed. A failed write is logged at warn and never stops the tick.
     */
    private suspend fun bounceNotReady(
        issues: List<Issue>,
        missing: Map<String, List<String>>,
    ) {
        bounced.retainAll(missing.keys)
        for (issue in issues) {
            if (issue.labels.any { it.normalized() == NEEDS_INFO_LABEL } || !bounced.add(issue.id)) continue
            val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier)
            val text = notReadyComment(missing.getValue(issue.id))
            withContext(io) { tracker.addComment(issue, IssueComment(LOOP_AUTHOR, clock.now(), text)) }.fold(
                onSuccess = {
                    recordIssueEvent(
                        issue,
                        IssueCommented,
                        mapOf(
                            Tracker.CHARS to text.length.toString(),
                            Tracker.REASON to NOT_READY,
                        ),
                    )
                },
                onFailure = { log.log(LogLevel.WARN, READINESS, LogOutcome.FAILED, context, reason = "comment: ${it.describe()}") },
            )
            withContext(io) { tracker.updateLabels(issue.id, add = listOf(NEEDS_INFO_LABEL)) }.fold(
                onSuccess = {
                    recordIssueEvent(
                        issue,
                        IssueLabeled,
                        mapOf(IssueLabeled.ADD to NEEDS_INFO_LABEL, Tracker.REASON to NOT_READY),
                    )
                },
                onFailure = {
                    log.log(LogLevel.WARN, READINESS, LogOutcome.FAILED, context, reason = "labels: ${it.describe()}")
                },
            )
        }
    }

    /** Records an issue level event outside any attempt; a sink failure is logged. */
    private suspend fun recordIssueEvent(
        issue: Issue,
        definition: EventDefinition,
        data: Map<String, String>,
    ) {
        val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier)
        val event =
            definition.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = issue.id,
                issueIdentifier = issue.identifier,
                data = data,
            )
        log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
    }

    /**
     * Records an issue level `candidate_skipped` event for each skipped candidate whose reason
     * differs from the last one recorded for it in this process (PLAN.md: the trace says why a
     * candidate was skipped). A candidate not skipped this poll is forgotten, so a later skip is
     * recorded again.
     */
    private suspend fun recordSkips(skips: Map<Issue, String>) {
        val byId = skips.mapKeys { it.key.id }
        lastSkipReasons.keys.retainAll(byId.keys)
        for ((issue, reason) in skips) {
            if (lastSkipReasons[issue.id] == reason) continue
            lastSkipReasons[issue.id] = reason
            recordIssueEvent(issue, CandidateSkipped, mapOf(CandidateSkipped.REASON to redactor.redact(reason)))
        }
    }

    /**
     * Runs one claimed attempt and its retry bookkeeping, then releases the claim whatever the end.
     * An unexpected exception is logged and gives null, so it never cancels the sibling attempts.
     */
    private suspend fun attemptAndRelease(issue: Issue): AttemptSummary? {
        var unavailable = false
        try {
            // bl-101: a launch on a scope cancelled since its claim runs nothing, only the releases of the finally.
            currentCoroutineContext().ensureActive()
            val summary = runAttempt(issue)
            val reconciled = summary.reconciled
            if (reconciled == null) {
                unavailable = summary.runnerUnavailable != null
                // A cancel throws out of runAttempt, so it never counts as a failure.
                val held = holdOn(issue, summary)
                networkResume.attemptEnded(issue.id, summary.attemptId, held)
                if (!held) retries.afterAttempt(issue, summary, effective)
            } else {
                retries.onReconciled(issue.id, reconciled)
                // Spec 8.5: after the attempt closed, so the agent and after_run are done with the workspace.
                if (reconciled == ExitReason.TERMINAL) removeWorkspace(summary.refreshedIssue ?: issue)
            }
            return summary
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier)
            log.log(LogLevel.ERROR, "attempt", LogOutcome.FAILED, context, reason = e.describe())
            return null
        } finally {
            hold.onAttemptEnded(issue.id, unavailable)
            try {
                withContext(NonCancellable) {
                    // m3-16: the machine slot goes at the attempt end, whatever the end.
                    machineSlots.remove(issue.id)?.let { holder ->
                        machineGate.release(holder, pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier))
                    }
                    storeClaim(issue.id, issue.identifier) { claims.release(projectId, issue.id) }
                }
            } finally {
                releaseInMemory(issue)
            }
        }
    }

    /**
     * bl-39: an attempt whose last turn failed `runner_unavailable` goes to the run level hold instead of the failure
     * count. True when the hold took it: its issue is requeued for the hold's probe without a `max_attempts` slot, and
     * a failure that began a hold period records it. False for any other attempt, and for the third hit in a row of
     * an issue while others succeed, which counts as an ordinary failure.
     */
    private suspend fun holdOn(
        issue: Issue,
        summary: AttemptSummary,
    ): Boolean {
        val unavailable = summary.runnerUnavailable ?: return false
        val outcome = hold.onUnavailable(issue.id, unavailable.reason, unavailable.retryAfter)
        if (outcome !is RunnerHold.Outcome.Held) return false
        retries.requeueUnavailable(issue, summary, outcome.dueAt)
        val period = outcome.started ?: return true
        val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier, attemptId = summary.attemptId)
        val data =
            mapOfNotNull(
                RunnerHoldStarted.REASON to period.reason,
                RunnerHoldStarted.HOLD to period.number.toString(),
                RunnerHoldStarted.DELAY_MS to period.delay.inWholeMilliseconds.toString(),
                RunnerHoldStarted.PROBE_AT to period.probeAt.toString(),
                RunnerHoldStarted.RETRY_AFTER_MS to period.retryAfter?.inWholeMilliseconds?.toString(),
            )
        val event =
            RunnerHoldStarted.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = issue.id,
                issueIdentifier = issue.identifier,
                attemptId = summary.attemptId,
                data = data,
            )
        log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
        log.log(
            LogLevel.WARN,
            RUNNER_HOLD,
            LogOutcome.STARTED,
            context,
            fields = mapOf("hold" to period.number, "delay_ms" to period.delay.inWholeMilliseconds, "probe_at" to period.probeAt),
            reason = period.reason,
        )
        return true
    }

    /** Releases the in-memory claim of [issue] and wakes the poll sleep. */
    private fun releaseInMemory(issue: Issue) {
        dispatchAttempts -= issue.id
        dispatchRetryKinds -= issue.id
        dispatchAnswers -= issue.id
        dispatchSentBacks -= issue.id
        executions -= issue.id
        running -= issue.id
        claimed -= issue.id
        attemptEnded.trySend(Unit)
    }

    /**
     * Runs one write of [claims] for [issueId] (m2-02). A failure is logged at warn and never stops the
     * attempt: the claim in memory decides, the stored one only tells the next process what this one held.
     */
    private suspend fun storeClaim(
        issueId: String,
        identifier: String,
        attemptId: AttemptId? = null,
        write: suspend () -> Result<Unit>,
    ) {
        val error =
            try {
                write().exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } ?: return
        val context = pollContext.copy(issueId = issueId, issueIdentifier = identifier, attemptId = attemptId)
        log.log(LogLevel.WARN, "claim", LogOutcome.FAILED, context, reason = error.describe())
    }

    /**
     * An immediate dispatch, then the next one after `polling.interval_ms`, or sooner
     * when a retry falls due before that (plan review item 9). Attempts run in this
     * call's scope while later polls go on, so a freed slot is filled on the next poll.
     */
    suspend fun runUntilCancelled(): Unit =
        supervisorScope {
            while (true) {
                try {
                    dispatch(this)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log(LogLevel.ERROR, "tick", LogOutcome.FAILED, pollContext, reason = e.describe())
                }
                // m4-10: a drained run stops by itself.
                if (drain.drained(drainFacts())) return@supervisorScope
                sleepUntilNextPoll()
            }
        }

    /**
     * Sleeps the polling interval, or less when a retry falls due sooner. Attempts end while
     * it sleeps and may schedule a retry, so each end wakes it to look at the next due time again.
     */
    private suspend fun sleepUntilNextPoll() {
        val pollAt = clock.now() + effective.pollingIntervalMs.coerceAtLeast(1).milliseconds
        while (true) {
            val untilPoll = (pollAt - clock.now()).inWholeMilliseconds
            if (untilPoll <= 0) return
            // A running issue's entry does not fire, so it must not shorten the sleep either, nor one a drain holds back
            // (m4-10). bl-39: while the runner hold waits, its probe time is the next due time; an overdue probe that
            // found nothing to run waits a poll.
            val now = clock.now()
            val probeAt = hold.probeAt
            val nextDue =
                if (probeAt != null) {
                    probeAt.takeIf { it > now }
                } else {
                    retries
                        .nextDueAt(busy = claimed + running.keys + waits.busy + drain.heldBack(retries.queued().keys))
                        ?.takeIf { retriesFired }
                }
            val sleep =
                if (nextDue == null) {
                    untilPoll
                } else {
                    (nextDue - clock.now()).inWholeMilliseconds.coerceIn(1, untilPoll)
                }
            withTimeoutOrNull(sleep) { attemptEnded.receive() } ?: return
        }
    }

    /**
     * Removes the workspace of [issue] (plan R16): `before_remove` runs in it, its
     * failure or timeout is recorded and ignored, then the directory is deleted.
     * Events carry the issue and no attempt id. Called by the terminal retry cleanup
     * (m1-07), reconciliation and the startup cleanup (m1-16).
     */
    suspend fun removeWorkspace(issue: Issue): Result<Unit> {
        val key = NamespacedId.workspaceKey(issue.id, issue.identifier, effective.trackerId)
        val context = pollContext.copy(issueId = issue.id, issueIdentifier = issue.identifier)
        val kind = KindLookup.of(effective, issue)
        val env =
            hookEnv(effective.path, issue.id, issue.identifier, key, kind?.name) +
                mapOf(
                    "LOOP_HOME" to loopHome.toString(),
                    "LOOP_PROJECT_ID" to projectId.value,
                    "LOOP_RUN_ID" to runId.value,
                )
        val hooks =
            HookRecorder(
                record = { definition, data ->
                    val event =
                        definition.event(
                            ts = clock.now(),
                            projectId = projectId,
                            runId = runId,
                            issueId = issue.id,
                            issueIdentifier = issue.identifier,
                            data = data,
                        )
                    log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
                },
                context = { context },
            )
        // m4-20: a kind populated empty runs no before_remove.
        return KindWorkspaces.manager(workspacesFor(effective), kind).remove(key, env, hooks).fold(
            onSuccess = { removed ->
                if (removed) {
                    log.log(LogLevel.INFO, "workspace", LogOutcome.COMPLETED, context, mapOf("workspace_key" to key.value))
                }
                Result.success(Unit)
            },
            onFailure = { error ->
                log.log(LogLevel.WARN, "workspace", LogOutcome.FAILED, context, mapOf("workspace_key" to key.value), error.describe())
                Result.failure(error)
            },
        )
    }

    /**
     * Startup terminal workspace cleanup (spec 8.6, m1-16): fetches the issues in the terminal states
     * and removes the workspace of each one that has a workspace, through [removeWorkspace], so
     * `before_remove` runs first. A failed fetch is logged as a warning and startup goes on (spec 11.4).
     * Called once by `loop run`, before the first tick.
     */
    suspend fun removeTerminalWorkspaces() {
        // m4-13: each tracker with its own terminal states; one that fails is logged and the others go on.
        val read = trackers.terminal(effective.trackers)
        read.failures.forEach { (id, error) ->
            log.log(
                LogLevel.WARN,
                STARTUP_CLEANUP,
                LogOutcome.FAILED,
                pollContext,
                fields = mapOf("tracker" to id),
                reason = error.describe(),
            )
        }
        for (issue in read.issues) removeWorkspace(issue)
    }

    /**
     * Reconciliation of the running issues (spec 8.5 part B, 16.3, m1-16): one by-id read of every
     * running issue not already being stopped. A terminal issue's attempt is stopped and its workspace
     * removed once the attempt closed; an issue that is not active, not eligible or gone is stopped and
     * its workspace kept; an eligible one keeps running with its state updated for the per state slots.
     * A failed read keeps every attempt running until the next tick. Nothing running means no read.
     */
    private suspend fun reconcile() {
        val ids = executions.values.filter { it.stop == null }.map { it.issue.id }
        if (ids.isEmpty()) return
        // m4-13: each tracker read on its own; the attempts of a tracker whose read failed keep running this tick.
        val refreshed = trackers.readByIds(ids)
        refreshed.failures.forEach { (id, error) ->
            log.log(LogLevel.WARN, RECONCILE, LogOutcome.FAILED, pollContext, fields = mapOf("tracker" to id), reason = error.describe())
        }
        val byId = refreshed.issues.associateBy { it.id }
        for (id in ids.filter(refreshed::answered)) {
            // An attempt can end while the read is in flight.
            val execution = executions[id] ?: continue
            val issue = byId[id]
            val reason =
                if (issue == null) {
                    ExitReason.NOT_FOUND
                } else {
                    when (RetryTarget.of(issue, effective)) {
                        RetryTarget.Eligible -> null
                        RetryTarget.Terminal -> ExitReason.TERMINAL
                        RetryTarget.NotActive -> ExitReason.NOT_ACTIVE
                        RetryTarget.NotEligible -> ExitReason.NOT_ELIGIBLE
                    }
                }
            if (reason == null) {
                running[id] = checkNotNull(issue).state.normalized()
                continue
            }
            val known = issue ?: execution.issue
            val context = pollContext.copy(issueId = id, issueIdentifier = known.identifier)
            val fields =
                mapOfNotNull(
                    "issue_state" to issue?.state,
                    "workspace" to if (reason == ExitReason.TERMINAL) "remove" else "keep",
                )
            log.log(LogLevel.INFO, RECONCILE, LogOutcome.COMPLETED, context, fields = fields, reason = reason)
            execution.stop = Stop(reason, issue)
            execution.job?.cancel()
        }
    }

    /** A tracker read with a thrown exception turned into a failed Result; a cancel still propagates. */
    private suspend fun <T> fetch(read: suspend () -> Result<T>): Result<T> =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Plan D8 revised: runs where a retry is dropped because its issue is terminal and
     * removes its workspace. A removal failure is logged by [removeWorkspace] and ignored.
     */
    private suspend fun onTerminalRetryDropped(issue: Issue) {
        removeWorkspace(issue)
    }

    /**
     * Runs the preflight, records a run level `preflight` event and logs per plan
     * D8: every failure at error, an Ok only when it ends a run of failures.
     */
    private suspend fun passesPreflight(): Boolean {
        val checked = preflight.check()
        // Learned before anything of this tick is recorded, so a new credential ref is covered at once.
        if (checked is PreflightResult.Ok) redactor.learn(secretsOf(checked.workflow))
        followWorkflow(checked)
        // m3-16: the machine config is read on every tick too. A malformed one fails the tick, never WORKFLOW.md's reload.
        val result =
            if (checked is PreflightResult.Ok) {
                machineGate.readLimits()?.let { PreflightResult.Failed(INVALID_CONFIG, it) } ?: checked
            } else {
                checked
            }
        val data =
            when (result) {
                is PreflightResult.Ok -> mapOf(PreflightEvent.RESULT to PreflightEvent.OK)
                is PreflightResult.Failed ->
                    mapOf(
                        PreflightEvent.RESULT to PreflightEvent.FAILED,
                        PreflightEvent.ERROR to result.code,
                        PreflightEvent.MESSAGE to redactor.redact(result.message),
                    )
            }
        val event = PreflightEvent.event(ts = clock.now(), projectId = projectId, runId = runId, data = data)
        log.guardSink(sinkName, pollContext) { sink.record(event).getOrThrow() }
        return when (result) {
            is PreflightResult.Ok -> {
                if (skippedTicks > 0) {
                    log.log(
                        LogLevel.INFO,
                        "preflight",
                        LogOutcome.COMPLETED,
                        pollContext,
                        fields = mapOf("skipped_ticks" to skippedTicks),
                    )
                    skippedTicks = 0
                }
                true
            }
            is PreflightResult.Failed -> {
                skippedTicks++
                log.log(
                    LogLevel.ERROR,
                    "preflight",
                    LogOutcome.FAILED,
                    pollContext,
                    fields = mapOf("error" to result.code),
                    reason = result.message,
                )
                false
            }
        }
    }

    /**
     * Spec 6.2 (m1-15): an Ok preflight of a file whose hash differs from the effective workflow's makes it the
     * effective workflow and records `config_reloaded`. A failed preflight of a changed file keeps the effective
     * workflow and records `config_reload_failed`, once per rejected text (once per stretch for an unreadable file).
     */
    private suspend fun followWorkflow(result: PreflightResult) {
        when (result) {
            is PreflightResult.Ok -> {
                rejectedVersion = null
                rejected = null
                val reloaded = result.workflow
                if (reloaded.sourceHash == effective.sourceHash) return
                val previous = effective
                effective = reloaded
                recordReload(previous, reloaded)
                // bl-221: what the reloaded workflow runs with but should fix, such as a template that never reads loop.answer.
                result.warnings.forEach { warning ->
                    log.log(LogLevel.WARN, WORKFLOW_RELOAD, LogOutcome.COMPLETED, pollContext, reason = redactor.redact(warning))
                }
            }
            is PreflightResult.Failed -> {
                val version = result.source?.hash ?: UNREADABLE
                if (version == effective.sourceHash || version == rejectedVersion) return
                rejectedVersion = version
                rejected = result
                recordRejected(result)
            }
        }
    }

    private suspend fun recordReload(
        previous: WorkflowFile,
        reloaded: WorkflowFile,
    ) {
        val data =
            mapOf(ConfigReloaded.CONFIG_VERSION to reloaded.sourceHash, ConfigReloaded.PREVIOUS_VERSION to previous.sourceHash) +
                diffData(previous, reloaded.sourceText, reloaded.sourceHash)
        val event = ConfigReloaded.event(ts = clock.now(), projectId = projectId, runId = runId, data = data)
        log.guardSink(sinkName, pollContext) { sink.record(event).getOrThrow() }
        log.log(
            LogLevel.INFO,
            WORKFLOW_RELOAD,
            LogOutcome.COMPLETED,
            pollContext,
            fields = mapOf(CONFIG_VERSION to reloaded.sourceHash.take(SHORT_ID), "previous_version" to previous.sourceHash.take(SHORT_ID)),
        )
        reloadWarnings(previous, reloaded).forEach { warning ->
            log.log(LogLevel.WARN, WORKFLOW_RELOAD, LogOutcome.COMPLETED, pollContext, reason = warning)
        }
    }

    private suspend fun recordRejected(result: PreflightResult.Failed) {
        val source = result.source
        val message = redactor.redact(result.message)
        val data =
            mapOfNotNull(
                ConfigReloadFailed.CONFIG_VERSION to source?.hash,
                ConfigReloadFailed.ACTIVE_VERSION to effective.sourceHash,
                ConfigReloadFailed.ERROR to result.code,
                ConfigReloadFailed.MESSAGE to message,
            ) + (source?.let { diffData(effective, it.text, it.hash) } ?: emptyMap())
        val event = ConfigReloadFailed.event(ts = clock.now(), projectId = projectId, runId = runId, data = data)
        log.guardSink(sinkName, pollContext) { sink.record(event).getOrThrow() }
        log.log(
            LogLevel.ERROR,
            WORKFLOW_RELOAD,
            LogOutcome.FAILED,
            pollContext,
            fields =
                mapOf(
                    CONFIG_VERSION to source?.hash?.take(SHORT_ID),
                    "active_version" to effective.sourceHash.take(SHORT_ID),
                    "error" to result.code,
                ),
            reason = message,
        )
    }

    /**
     * The `diff` of a reload record from [from] to [text], redacted before it is cut to [DIFF_MAX] characters at a
     * line end, so no fragment of a secret survives the cut; a cut diff also gets `diff_truncated`.
     */
    private fun diffData(
        from: WorkflowFile,
        text: String,
        hash: String,
    ): Map<String, String> {
        val name = from.path.name
        val diff =
            redactor.redact(
                WorkflowDiff.unified(from.sourceText, text, "$name ${from.sourceHash.take(SHORT_ID)}", "$name ${hash.take(SHORT_ID)}"),
            )
        if (diff.length <= DIFF_MAX) return mapOf(ConfigReloaded.DIFF to diff)
        val cut = diff.take(DIFF_MAX)
        val lineEnd = cut.lastIndexOf('\n')
        return mapOf(ConfigReloaded.DIFF to if (lineEnd >= 0) cut.take(lineEnd + 1) else cut, ConfigReloaded.DIFF_TRUNCATED to "true")
    }

    private fun isEligible(issue: Issue): Boolean {
        // m4-13: the states and required labels of the issue's own tracker.
        val own = effective.trackers.trackerOf(issue.id) ?: return false
        return own.isActive(issue.state) &&
            !own.isTerminal(issue.state) &&
            own.hasRequiredLabels(issue.labels) &&
            issue.id !in claimed &&
            issue.id !in running &&
            !waits.isBusy(issue.id)
    }

    private suspend fun runAttempt(issue: Issue): AttemptSummary {
        val attempt = Attempt(issue, AttemptId(newId()))
        val startedAt = clock.now()
        // m1-15: the config version the attempt runs with; a workflow not read from a file has none.
        attempt.record(
            AttemptStarted,
            data =
                mapOfNotNull(
                    AttemptStarted.LOOP_VERSION to loopVersion,
                    AttemptStarted.CONFIG_VERSION to attempt.workflow.sourceHash.ifEmpty { null },
                    AttemptStarted.OCCUPANCY to
                        attempt.runner.capabilities.occupancy
                            .toString(),
                ) + attempt.setupData(),
        )
        log.log(LogLevel.INFO, "attempt", LogOutcome.STARTED, attempt.logContext())
        attempt.writeClaim()
        attempt.recordReturn()
        // Null until the attempt ran to its end; a cancel anywhere before leaves it null.
        var closing: Closing? = null
        var cancelled = false
        // Set when reconciliation stopped the attempt (m1-16): it closes like an attempt that ended.
        var stop: Stop? = null
        try {
            val executed =
                try {
                    execute(attempt)
                } catch (e: CancellationException) {
                    // Only the inner job was canceled when this coroutine is still active: a reconciliation stop.
                    stop = executions[issue.id]?.stop?.takeIf { currentCoroutineContext().isActive }?.let(attempt::ownStop)
                    if (stop == null) {
                        cancelled = true
                        throw e
                    }
                    null
                } catch (e: Exception) {
                    Executed(AttemptOutcome.Failed, "internal error: ${e.describe()}")
                }
            closing = if (executed == null) checkNotNull(stop).closing() else closingOf(attempt, executed)
        } finally {
            withContext(NonCancellable) {
                val end =
                    closing ?: Closing(AttemptOutcome.Failed, cancelReason(), issueState = null, canceled = true).also {
                        attemptsCanceled++
                    }
                try {
                    attempt.afterRun(canceled = cancelled || stop != null)
                } finally {
                    try {
                        // m4-50: every lock and queue row of the attempt goes at its end, whatever the end.
                        // m4-25: through the grants of loop run when it has them, so each provider releases first.
                        // bl-208: in a try of its own, so whatever it throws, an Error of a plugin too, the requests
                        // are still dropped and the directory deleted.
                        try {
                            (
                                resourceGrants?.endAttempt(attempt.attemptId, ResourceReleased.ATTEMPT_ENDED)
                                    ?: resourceLocks.freeAttempt(attempt.attemptId, ResourceReleased.ATTEMPT_ENDED)
                            ).onFailure {
                                log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, attempt.logContext(), reason = it.describe())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            log.log(LogLevel.WARN, "resource", LogOutcome.FAILED, attempt.logContext(), reason = e.describe())
                        }
                        // Whatever a canceled or failed turn queued is never applied (m1-23).
                        attempt.dropRequests()
                        attempt.deleteDirectory()
                    } finally {
                        attempt.close(end, startedAt)
                    }
                }
            }
        }
        val closed = checkNotNull(closing)
        return AttemptSummary(
            issue.identifier,
            issue,
            closed.outcome,
            closed.issueState,
            clock.now() - startedAt,
            attemptId = attempt.attemptId,
            reason = closed.reason,
            refreshedIssue = closed.refreshed,
            exitReason = closed.exitReason,
            turns = closed.turns,
            parkReason = closed.parkReason,
            parkData = closed.parkData,
            reconciled = closed.reconciled,
            handedOff = attempt.handedOff(),
            runnerUnavailable = closed.unavailable,
        )
    }

    /**
     * Runs [attempt]'s turns in an inner job registered on the issue's [Execution], so reconciliation can
     * cancel the turns alone: the attempt then still closes (m1-16). A stop that came before the job
     * existed cancels it at once.
     */
    private suspend fun execute(attempt: Attempt): Executed =
        coroutineScope {
            val job = async { attempt.execute() }
            val execution = executions[attempt.issue.id]
            execution?.job = job
            if (execution?.stop != null) job.cancel()
            try {
                job.await()
            } finally {
                execution?.job = null
            }
        }

    /** The running attempt of one issue: the issue as dispatched, its turns' job, and why reconciliation stopped it. */
    private class Execution(
        val issue: Issue,
    ) {
        var job: Job? = null
        var stop: Stop? = null
    }

    /** Why reconciliation stopped an attempt (an [ExitReason] value), and the issue as read, null when gone. */
    private class Stop(
        val reason: String,
        val issue: Issue?,
    ) {
        /** A stopped attempt closes as canceled, never counted as a failure or in [attemptsCanceled]. */
        fun closing(): Closing =
            Closing(
                AttemptOutcome.Failed,
                reason,
                issue?.state,
                canceled = true,
                refreshed = issue,
                failedPhase = AttemptPhase.CanceledByReconciliation,
                reconciled = reason,
            )
    }

    /**
     * How an attempt whose turns ran to their end closes. The turn loop's last refresh is the closing one; an
     * attempt that never refreshed reads the issue now. A successful attempt's last turn completed normally,
     * so what it queued is applied first (m1-23); after a non empty drain the issue is always re-read, so the
     * retry decision sees the moved state.
     */
    private suspend fun closingOf(
        attempt: Attempt,
        executed: Executed,
    ): Closing {
        val last = executed.refresh
        val drained = executed.outcome == AttemptOutcome.Succeeded && attempt.drainRequests()
        val refreshed = if (last != null && !drained) last.issue else refreshIssue(attempt).getOrNull()
        return Closing(
            executed.outcome,
            executed.reason,
            refreshed?.state,
            exitReason = executed.exitReason,
            turns = executed.turns,
            refreshed = refreshed,
            error = executed.error,
            parkReason = executed.parkReason,
            parkData = executed.parkData,
            failedPhase = if (executed.reason == STALLED) AttemptPhase.Stalled else AttemptPhase.Failed,
            unavailable = executed.unavailable,
        )
    }

    /**
     * The issue as the tracker has it by id: null inside a success when it is gone, a failure when the read failed,
     * logged at warn as `event=tracker`.
     */
    private suspend fun refreshIssue(attempt: Attempt): Result<Issue?> =
        readIssue(attempt).onFailure { error ->
            log.log(LogLevel.WARN, "tracker", LogOutcome.FAILED, attempt.logContext(), reason = error.describe())
        }

    /** [refreshIssue] without its warning, for a caller that logs the failure its own way (bl-152). */
    private suspend fun readIssue(attempt: Attempt): Result<Issue?> {
        val result =
            try {
                tracker.fetchIssuesByIds(listOf(attempt.issue.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return result.map { issues -> issues.firstOrNull() }
    }

    /** What [Attempt.execute] returns; [refresh] is set when the turn loop ended on a by-id refresh. */
    private class Executed(
        val outcome: AttemptOutcome,
        val reason: String? = null,
        val exitReason: String? = null,
        val turns: Int = 0,
        val refresh: Refresh? = null,
        /** The tracker error behind a refresh failure, kept off the countable [reason]. */
        val error: String? = null,
        /** Park the issue for this reason instead of counting a failure. */
        val parkReason: ParkReason? = null,
        val parkData: Map<String, String> = emptyMap(),
        /** Set when the failing turn was `runner_unavailable` (bl-39). */
        val unavailable: RunnerUnavailable? = null,
    )

    /**
     * How a result file ends an attempt (m2-15): the countable [reason] (`result_blocked`, `result_failed` or
     * `invalid_result`) and the [error], the agent's summary or the validation error.
     */
    private class ResultFailure(
        val reason: String,
        val error: String,
    ) {
        fun executed(turns: Int): Executed = Executed(AttemptOutcome.Failed, reason, turns = turns, error = error.ifEmpty { null })
    }

    /** What a rotation led to (m3-11): a new session and its first [Started.prompt], or the end of the attempt. */
    private sealed interface Rotation {
        class Started(
            val prompt: String,
        ) : Rotation

        class Ended(
            val executed: Executed,
        ) : Rotation
    }

    /**
     * What the attempt's first prompt rendered with, so a rotation renders the template again the same way (m3-11). The
     * issue is not kept: a rotation renders the one it reads, else the one the turn end read (bl-152).
     */
    private data class PromptInputs(
        val attempt: Int?,
        val findings: String?,
        val workspaceReused: Boolean,
        val workspace: Path,
        val fetchedBase: String?,
    )

    /** One by-id read of the issue; [issue] is null when the tracker no longer has it or the read failed. */
    private class Refresh(
        val issue: Issue?,
    )

    /** How an attempt ends: the terminal phase, `attempt_finished` and the log line derive from it. */
    private data class Closing(
        val outcome: AttemptOutcome,
        val reason: String?,
        val issueState: String?,
        val canceled: Boolean = false,
        /** Why a successful attempt ended, an [ExitReason] value. */
        val exitReason: String? = null,
        val turns: Int = 0,
        /** The issue read after the attempt, for the park snapshot (m1-07). */
        val refreshed: Issue? = null,
        /** Detail of the failure behind [reason], when the reason is a fixed countable token. */
        val error: String? = null,
        val parkReason: ParkReason? = null,
        val parkData: Map<String, String> = emptyMap(),
        /** The terminal phase of a failed attempt: [AttemptPhase.Stalled], [AttemptPhase.CanceledByReconciliation] or [AttemptPhase.Failed]. */
        val failedPhase: AttemptPhase = AttemptPhase.Failed,
        /** Why reconciliation stopped the attempt, null otherwise. */
        val reconciled: String? = null,
        val unavailable: RunnerUnavailable? = null,
    ) {
        fun turnsOrNull(): String? = turns.takeIf { it > 0 }?.toString()
    }

    private inner class Attempt(
        val issue: Issue,
        val attemptId: AttemptId,
    ) {
        /**
         * The workflow in effect when the attempt starts, used for its whole life: a reload reaches the
         * next attempt, never this one (m1-15). Its hash is the config version the attempt records.
         */
        val workflow: WorkflowFile = effective

        /** The kind of the issue as dispatched (m4-18), for the whole attempt like [workflow]; null in Symphony mode. */
        val kind: IssueKind? = KindLookup.of(workflow, issue)

        val runner: AgentRunner = runnerFor(workflow)
        private val workspaces: WorkspaceManager = KindWorkspaces.manager(workspacesFor(workflow), kind)

        /** The windows of this attempt's models, and the ones with none (m4-02). */
        private val contextWindows =
            ContextWindows(
                workflow.config.context.windows,
                workflow.runner.kind,
                records = workflow.config.kinds != null && runner.capabilities.occupancy,
            )

        var sessionId: SessionId? = null
        var turnId: TurnId? = null

        /** The runtime's own session id, when the runner reports one, for the stored claim (m2-02). */
        private var runtimeSessionId: String? = null

        /** The thread of the current agent session, from its latest start event (m3-11). */
        private var threadId: String? = null

        /** When the attempt claimed its issue, for the stored claim. */
        private val claimedAt: Instant = clock.now()

        /** Writes the claim of this attempt with its current session (m2-02). */
        suspend fun writeClaim() {
            val claim = Claim(projectId, issue.id, issue.identifier, attemptId, runId, claimedAt, sessionId, runtimeSessionId)
            storeClaim(issue.id, issue.identifier, attemptId) { claims.claim(claim) }
        }

        /** The attempt's tokens, the latest absolute total (m1-08). */
        private val tokens = TokenMeter()

        /** The context occupancy of the current agent session (m3-08), a new meter for each session. */
        private var occupancy = OccupancyMeter()

        /** The agent session the next turn runs on, null for a runner without continuation or once it was stopped. */
        private var session: AgentSession? = null

        /** The rotations of this attempt so far (m3-11). */
        private var rotations = 0

        /** The closed sessions' spend, added to every usage report so it stays absolute for the attempt (m3-11). */
        private val spend = SessionSpend()

        /**
         * The data of the `session_rotated` event of a rotation whose new session has not started a turn yet (m3-11):
         * recorded with the new ids at that start, or without them at the end of a turn that never started.
         */
        private var pendingRotation: Map<String, String>? = null

        /** What renders the first prompt of a new session after a rotation, set once the first prompt rendered. */
        private var promptInputs: PromptInputs? = null

        /**
         * bl-221: the answered ask wait (m4-52) or the sent back approval wait (m4-32) whose answer or reason the first
         * prompt carries, ended once a turn of this attempt completed. Until then it stays in the store, so an attempt
         * that ends before, at a budget, a failed open, a stop, a crash or a failed first turn, leaves it for the next
         * dispatch.
         */
        private var carried: List<Wait> = emptyList()

        /** The progress between this attempt's rotations (m3-12), from the workspace's HEAD at its first prompt. */
        private var progress = RotationProgress(startHead = null, startTree = null)

        /** The facts whose failed read this attempt already logged (bl-149), so each is logged once per attempt. */
        private val loggedFactFailures = mutableSetOf<String>()

        /** The attempt's spend budgets, read once when the attempt starts (m2-20). */
        private val budgets = BudgetMeter(workflow.config.budgets)

        /** The first budget the attempt went past, null while it is within every one. */
        private var breach: BudgetLevel? = null

        /** The spend of the issue's finished attempts and of the project's last 24 hours, as last read from the ledger. */
        private var issueSpend = Spend()
        private var daySpend = Spend()

        /** `budgets.token_threshold_every`, read once when the attempt starts (m1-38). */
        private val thresholdEvery = workflow.config.budgets.tokenThresholdEvery

        /** The attempt's token thresholds; [execute] seeds them with the steps already recorded. */
        private var thresholds = TokenThresholds(thresholdEvery)

        /** The `total_cost_usd` of the latest real usage report that had one, null before any. */
        private var costUsd: Double? = null

        /**
         * What the ledger counts of this attempt (m2-19), and the setup each of its lines and its row name, with the
         * runner's configured model. A line names its session's reported model instead, the row the attempt's (bl-147).
         */
        private val ledger = LedgerMeter()
        private val ledgerSetup = LedgerMeter.setupOf(workflow, runner.model, issue.id, kind?.name)

        /** m4-42: the ledger setup `attempt_started` carries, so an exporter names it on every span of the attempt. */
        fun setupData(): Map<String, String> =
            mapOfNotNull(
                AttemptStarted.TRACKER to ledgerSetup.tracker,
                AttemptStarted.KIND to ledgerSetup.kind,
                AttemptStarted.RUNNER to ledgerSetup.runner,
                AttemptStarted.MODEL to ledgerSetup.model,
                AttemptStarted.TEMPLATE_VERSION to ledgerSetup.templateVersion,
            )

        /** The latest turn line of this attempt, which a `stop` line copies (m4-04). */
        private var lastTurnLine: LedgerTurn? = null

        /**
         * Every stop of the session passes the usage callback (m4-04): what a stop reads counts in the spend, the budgets
         * and the ledger, and a failed stop is logged and recorded as `session_stop_failed` (m3-07).
         */
        private val stopUsage =
            StopUsage(
                usage = { recordUsage(it, budgets = true, atStop = true) },
                graceExpired = { event ->
                    record(StopGraceExpired, data = StopUsage.graceData(event, ledger.tokens?.let { tokens.total }, costUsd))
                    val reason = "the session did not exit within ${describeMs(event.graceMs)} of its stop, its unreported usage is lost"
                    log.log(
                        LogLevel.WARN,
                        "session",
                        LogOutcome.FAILED,
                        logContext(),
                        fields = mapOf("grace_ms" to event.graceMs),
                        reason = reason,
                    )
                },
                failed = { e ->
                    log.log(LogLevel.WARN, "session", LogOutcome.FAILED, logContext(), reason = e.describe())
                    record(SessionStopFailed, data = mapOf(SessionStopFailed.REASON to e.describe()))
                },
            )

        /** The latest model a `context_usage` report of this attempt named, null before any (bl-147). */
        private var reportedModel: String? = null

        /** The state this attempt last moved its issue to, so its own move is never taken for a human's. */
        private var movedTo: String? = null

        /** True when [state] is the one this attempt last moved its issue to. */
        fun movedItTo(state: String?): Boolean = state != null && movedTo?.normalized() == state.normalized()

        /**
         * True when this attempt moved its issue to a handoff state, one that is neither active nor terminal (m2-23).
         * The move counts as a handoff whatever the issue reads now: on a label tracker like GitHub's the issue stays
         * `open` and only its labels change, and a read right after the write may not show them yet.
         */
        fun handedOff(): Boolean {
            val to = movedTo?.normalized() ?: return false
            val own = workflow.trackers.entryOf(issue.id)
            return !own.isActive(to) && !own.isTerminal(to)
        }

        /**
         * A reconciliation [stop] as this attempt sees it (m2-23): after its own handoff, an issue that still reads
         * active, eligible or not, left the active set by that move, so the stop is `not_active`, as on a tracker
         * whose handoff is a state.
         */
        fun ownStop(stop: Stop): Stop =
            if (handedOff() && stop.reason == ExitReason.NOT_ELIGIBLE) Stop(ExitReason.NOT_ACTIVE, stop.issue) else stop

        /**
         * Records a `human_touch` when the last attempt of the issue left it outside the active states (m2-19): it is
         * dispatched again, so a human moved it back, for example from the handoff state to rework, or after an
         * answer. A store that cannot be read is logged and counts nothing.
         */
        suspend fun recordReturn() {
            val last =
                try {
                    attemptLedger.attemptRows(projectId, issue.id).getOrThrow().lastOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log(LogLevel.WARN, LEDGER, LogOutcome.FAILED, logContext(), reason = e.describe())
                    null
                }
            val from = last?.attempt?.issueState ?: return
            if (workflow.trackers.entryOf(issue.id).isActive(from)) return
            record(
                HumanTouched,
                data = mapOf(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.FROM_STATE to from, HumanTouched.TO_STATE to issue.state),
            )
        }

        /** Appends the ledger line of [turn], which started at [startedAt]; a failure is logged, never fatal. */
        private suspend fun appendTurnLine(
            turn: TurnResult,
            startedAt: Instant,
        ) {
            val now = clock.now()
            val line =
                LedgerTurn(
                    ts = now,
                    projectId = projectId,
                    runId = runId,
                    issueId = issue.id,
                    identifier = issue.identifier,
                    attemptId = attemptId,
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    turn = turn.number,
                    outcome = if (turn.failure == null) "completed" else "failed",
                    // bl-147: the model the session's latest report named, else the configured one.
                    setup = ledgerSetup.withModel(occupancy.model),
                    tokens = ledger.turnTokens(),
                    attemptTokens = ledger.tokens,
                    costUsd = ledger.turnCostUsd(),
                    attemptCostUsd = ledger.costUsd,
                    wallMs = (now - startedAt).inWholeMilliseconds,
                    threadId = turn.threadId,
                    peak = occupancy.peak(workflow.config.context.windows),
                    rotations = rotations,
                    interrupted = turn.interrupted?.let { SessionRotated.HARD_LIMIT },
                    wake = turn.wake,
                )
            lastTurnLine = line
            ledgerWrite { withContext(io) { turnLedger.append(line) } }
        }

        /** Writes the ledger row of this attempt, which ended as [outcome]; a failure is logged, never fatal. */
        private suspend fun recordRow(
            end: Closing,
            outcome: String,
            startedAt: Instant,
        ) {
            val row =
                LedgerAttempt(
                    projectId = projectId,
                    runId = runId,
                    issueId = issue.id,
                    identifier = issue.identifier,
                    attemptId = attemptId,
                    // bl-147: the latest model a report of the attempt named, else the configured one.
                    setup = ledgerSetup.withModel(reportedModel),
                    startedAt = startedAt,
                    finishedAt = clock.now(),
                    outcome = outcome,
                    reason = end.reason,
                    turns = end.turns,
                    issueState = end.issueState,
                    tokens = ledger.tokens,
                    costUsd = ledger.costUsd,
                    checks = ledger.checks,
                    // The guards run inside diff_policy (m2-12, m2-13) and count among the checks; no verdict reports
                    // them apart, so null rather than zero.
                    guards = null,
                    verdict = ledger.verdict,
                )
            ledgerWrite { attemptLedger.recordAttempt(row) }
        }

        private suspend fun ledgerWrite(write: suspend () -> Result<Unit>) {
            val error =
                try {
                    write().exceptionOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
            error?.let { log.log(LogLevel.WARN, LEDGER, LogOutcome.FAILED, logContext(), reason = it.describe()) }
        }

        /** True once a runner line could not be written to the Attempt file: the failure is recorded once (m2-18). */
        private var runnerRecordFailed = false

        /** `codex.stall_timeout_ms`, read once when the attempt starts; zero or less disables stall detection. */
        private val stallTimeoutMs = workflow.config.codex.stallTimeoutMs
        val hooks = HookRecorder({ definition, data -> record(definition, data = data) }, ::logContext)

        /** Set once the workspace exists, for `after_run`. */
        private var workspace: Workspace? = null
        private var env: Map<String, String> = emptyMap()

        /** The attempt directory (m1-23): the tool config snapshot and the request queue, outside the workspace. */
        val directory: Path = attemptDirectory(loopHome, projectId, attemptId)
        private val toolConfig: Path = directory.resolve(TOOL_CONFIG_FILE)

        /** True once this attempt created [directory], so it only ever deletes its own. */
        private var directoryCreated = false

        /**
         * Creates [directory] and its requests folder with mode 0700, then writes the tool config
         * snapshot from the attempt's workflow. A failure gives its reason, null when all is in place.
         */
        private suspend fun createDirectory(): String? {
            val states = trackers.definedStatesOf(issue.id)
            return try {
                withContext(io) {
                    Files.createDirectories(directory.parent)
                    Files.createDirectory(directory, ownerOnly())
                    directoryCreated = true
                    Files.createDirectory(directory.resolve(REQUESTS_DIR), ownerOnly())
                    toolConfigWriter.write(toolConfig, projectId, workflow, workflow.trackers.entryOf(issue.id), states)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "cannot prepare the attempt directory: ${e.describe()}"
            }
        }

        private val requests = ToolRequestQueue(directory)

        /**
         * Applies the queued `loop tool` requests host side, in name order, through the orchestrator's
         * own tracker (m1-23). Called only after a turn that completed normally, once its process group
         * is dead. Each request is independent: a failure records `tool_request_failed` and the rest
         * still apply. Each file is deleted right after its request is applied. In Loop mode the result file
         * comes last (m2-15). True when the queue held anything or a result file was taken, so the caller
         * re-reads the issue.
         */
        suspend fun drainRequests(): Boolean {
            if (!directoryCreated) return false
            keepToolOutputs()
            submitted = null
            submittedVerification = null
            drainFindings = null
            drainFailures.clear()
            resultFailure = null
            val pending =
                try {
                    withContext(io) { requests.pending() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An unreadable queue fails its requests, never the attempt; the caller still re-reads the issue.
                    failed(
                        mapOf(
                            ToolRequestEvent.REQUEST to UNKNOWN_REQUEST,
                            Tracker.REASON to "cannot read the request queue: ${e.describe().trimEnd('.')}",
                        ),
                    )
                    takeResultFile()
                    return true
                }
            for (entry in pending) {
                // Applied already, only its delete failed: never apply it twice in this attempt.
                if (entry.file.name !in appliedRequests) {
                    when (entry) {
                        is QueuedRequest.Valid -> apply(entry.request)
                        is QueuedRequest.Unreadable ->
                            failed(
                                mapOf(
                                    ToolRequestEvent.REQUEST to UNKNOWN_REQUEST,
                                    Tracker.REASON to entry.reason,
                                ),
                            )
                    }
                    appliedRequests += entry.file.name
                }
                removeRequest(entry)
            }
            // m4-31: a result file left beside an ask stays for the next attempt's stale discard.
            val took = !asked && takeResultFile()
            return pending.isNotEmpty() || took
        }

        /** The request file names this attempt already handled, so a file whose delete failed is not applied again. */
        private val appliedRequests = mutableSetOf<String>()

        /**
         * Records `tool_request_dropped` for every request still queued when the attempt ends: only a
         * turn that completed normally drains, so after a cancel (`loop stop`, reconciliation) or a
         * failed turn an agent's request never overrides a human's change. The files go with the
         * directory. Never throws: a read failure is logged.
         */
        suspend fun dropRequests() {
            if (!directoryCreated) return
            keepToolOutputs()
            val pending =
                try {
                    withContext(io) { requests.pending() }
                } catch (e: Exception) {
                    log.log(LogLevel.WARN, "tool_request", LogOutcome.FAILED, logContext(), reason = e.describe())
                    return
                }
            for (entry in pending.filter { it.file.name !in appliedRequests }) {
                val data =
                    when (entry) {
                        is QueuedRequest.Valid -> requestData(entry.request)
                        is QueuedRequest.Unreadable -> mapOf(ToolRequestEvent.REQUEST to UNKNOWN_REQUEST)
                    }
                record(ToolRequestDropped, data = data)
            }
        }

        private suspend fun apply(request: ToolRequest) {
            val failure =
                try {
                    when (request) {
                        // m4-31: after an ask the issue waits for its answer, so no move and no result of the drain applies.
                        is ToolRequest.Move ->
                            if (asked) {
                                WAITS_FOR_ANSWER
                            } else if (intercepts(request.to)) {
                                applySubmitted(request.to)
                            } else {
                                applyMove(request)
                            }
                        is ToolRequest.Comment -> applyComment(request)
                        is ToolRequest.Result -> if (asked) WAITS_FOR_ANSWER else applyQueuedResult(request)
                        is ToolRequest.Ask -> applyAsk(request)
                        is ToolRequest.Followup -> applyFollowup(request)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.describe()
                }
            failure?.let { reason -> failed(requestData(request) + (Tracker.REASON to reason)) }
        }

        /**
         * Records `tool_request_failed` with [data] and keeps its notice for the guidance of the next turn (m2-07).
         * Its `to` and `reason` can hold text the agent chose (a state, a file name), so both are redacted here, the
         * reason before its cut to [REASON_MAX]. A second failed move to the same state in one drain, a queued move
         * and a `done` result file, records its event but adds no second notice.
         */
        private suspend fun failed(data: Map<String, String>) {
            val safe =
                data.mapValues { (key, value) ->
                    when (key) {
                        Tracker.TO -> redactor.redact(value)
                        Tracker.REASON -> redactor.redact(value).take(REASON_MAX)
                        else -> value
                    }
                }
            record(ToolRequestFailed, data = safe)
            val notice = failedRequestNotice(safe)
            drainFailures.putIfAbsent(if (safe[Tracker.TO] != null) failedRequestName(safe) else notice, notice)
        }

        /**
         * Moves the issue and records `issue_moved` by host; the failure's reason, null when it moved. A move that would
         * put the issue under another kind is refused (m4-18), so an agent never picks a kind with laxer checks for its
         * next attempt.
         */
        private suspend fun applyMove(request: ToolRequest.Move): String? {
            val current =
                withContext(io) { tracker.fetchIssuesByIds(listOf(issue.id)) }
                    .getOrElse { return it.describe() }
                    .firstOrNull()
                    ?: return "no issue ${issue.identifier} in the tracker"
            val before = KindLookup.of(workflow, current)?.name
            val after = KindLookup.afterMove(workflow, current, request.to)?.name
            if (before != after) return "the move to ${request.to} would change the kind of the issue from $before to $after"
            val from = current.state
            withContext(io) { tracker.moveIssue(current, request.to) }.getOrElse { return it.describe() }
            record(IssueMoved, data = mapOf(Tracker.FROM to from, Tracker.TO to request.to, Tracker.BY to BY_HOST))
            return null
        }

        /** The Loop mode contract of this attempt (m2-03), from the workflow it started with; null is Symphony mode. */
        private val kinds = workflow.config.kinds

        private val checkRunner: CheckRunner by lazy { checkRunnerFor(workflow) }

        /** Whether the checks passed for a result submitted earlier in this drain, null before one; reset per drain. */
        private var submitted: Boolean? = null

        /** The verification of that result (m4-22), whose verified state a second result of the drain delivers. */
        private var submittedVerification: Verification? = null

        /** The findings of a refutation in this drain, for the guidance of the next turn (m2-06); reset per drain. */
        private var drainFindings: VerifyFindings? = null

        /**
         * The notices of the requests this drain could not apply, for the guidance of the next turn (m2-07), one per
         * move target or per distinct notice; reset per drain.
         */
        private val drainFailures = linkedMapOf<String, String>()

        /**
         * The findings of this attempt's latest verdict when it is `refuted`, null before any verdict or after a
         * verdict that is not (m2-06). An attempt that runs out of turns with it set ends as a `refuted` failure.
         */
        private var refuted: VerifyFindings? = null

        /** True once a verification of this attempt gave a verdict, so a rotation reads [refuted] alone (bl-132). */
        private var verdicts = false

        /**
         * Why this attempt's latest verdict is `unverified`, null otherwise (m2-08). The attempt stops after the
         * drain that set it and parks the issue with the reason `unverified`: nothing proves the work.
         */
        private var unverified: String? = null

        /**
         * True once a drain started a `Waiting(approval)` for this attempt's verified result (m2-24). The attempt
         * stops after that drain: the issue stays active until `loop approve`, and no further turn is needed.
         */
        private var waitingApproval = false

        /**
         * True once a drain of this attempt started a `Waiting(ask)` (m4-31). The attempt stops after that drain with no
         * verification, and a second `ask` fails.
         */
        private var asked = false

        /**
         * A queued `ask` (m4-31): its question posted on the issue as it stands now, then `Waiting(ask)`, see [Asks]. The
         * failure's reason, null once the issue waits.
         */
        private suspend fun applyAsk(request: ToolRequest.Ask): String? {
            Asks.refusal(loopMode = kinds != null, asked = asked, submitted = submitted != null)?.let { return it }
            val current =
                withContext(io) { tracker.fetchIssuesByIds(listOf(issue.id)) }
                    .getOrElse { return it.describe() }
                    .firstOrNull()
                    ?: return "no issue ${issue.identifier} in the tracker"
            val wait =
                Wait.ask(
                    projectId,
                    issue.id,
                    issue.identifier,
                    attemptId,
                    runId,
                    clock.now(),
                    current.state,
                    request.questionKind,
                    kind?.name,
                )
            return Asks
                .start(
                    Asks.Question(request.questionKind, request.question, request.options, request.recommendation),
                    wait,
                    redactor::redact,
                    post = { text -> withContext(io) { tracker.addComment(current, IssueComment(agentAuthor(), clock.now(), text)) } },
                    record = { definition, data -> record(definition, data = data) },
                    save = { asking ->
                        notices.asking(
                            asking,
                            Asks.noticeReason(request.questionKind),
                        ) { noticed -> storeWaiting(noticed) { waits.save(noticed) } }
                    },
                ).also { failure -> if (failure == null) asked = true }
        }

        /**
         * In Loop mode every move out of the active states is a submitted result, except `needs_info`: a move
         * to the handoff state or a terminal one is verified first, so no unverified way out is left (m2-03).
         */
        private fun intercepts(to: String): Boolean {
            if (kinds == null) return false
            val target = to.normalized()
            return target != NEEDS_INFO && !workflow.trackers.entryOf(issue.id).isActive(target)
        }

        /**
         * A move the agent queued as its result (m2-03): `result_submitted`, then the kind's checks in the
         * workspace, then the move by Loop only when every check passed and the issue is still active, so a
         * human change during the checks wins. A failed check is not a failed request: the check events are
         * the record. A second move in the same drain reuses the first result. While the checks run and until
         * the move is applied, the issue is `Waiting(verify)` (m2-04): a reconciliation stop ends the wait,
         * any other cancel (`loop stop`, a signal) keeps it, so the next start runs the checks again. A `done`
         * result file (m2-15) comes here too, with its own [data] on `result_submitted`.
         */
        private suspend fun applySubmitted(
            to: String,
            data: Map<String, String> = emptyMap(),
        ): String? {
            record(ResultSubmitted, data = mapOf(Results.TO to to) + data)
            submitted?.let { passed -> return if (passed) submittedVerification?.deliver(to) else null }
            val verification = verification().also { submittedVerification = it }
            if (verification == null) {
                submitted = false
                return null
            }
            verification.prepare()
            if (!verification.hasChecks) {
                // Nothing to wait for: the verdict is `unverified` at once (m2-08).
                submitted = verification.verify()
                unverified = verification.unverified
                return null
            }
            val wait = Wait.verify(projectId, issue.id, issue.identifier, attemptId, runId, clock.now(), to, kind?.name)
            return waitingFor(wait, write = true, endOnCancel = { executions[issue.id]?.stop != null }) {
                val passed = buildGate.withBuild(logContext()) { verification.verify() }.also { submitted = it }
                progress.verified(verification.failingChecks)
                verdicts = true
                refuted = verification.findings
                drainFindings = verification.findings
                unverified = verification.unverified
                if (passed) verification.deliver(to) else null
            }.also { if (waits.becameApproval(wait)) waitingApproval = true }
        }

        /** How the result file taken in the last drain ends the attempt, null when it does not (m2-15); reset per drain. */
        private var resultFailure: ResultFailure? = null

        /**
         * Takes `.loop/result.json` from the workspace in Loop mode (m2-15) and submits it ([submitResult]). True when a
         * file was taken.
         */
        private suspend fun takeResultFile(): Boolean {
            if (kinds == null) return false
            val path = workspace?.path ?: return false
            val taken =
                try {
                    withContext(io) { ResultFile.take(path) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                } ?: return false
            submitResult(taken, RESULT_FILE_SOURCE)
            return true
        }

        /**
         * A result the agent submitted with the `submit_result` MCP tool (m2-16), queued as a request: the same path as
         * the result file, with `source` = `mcp`. In Symphony mode there is no verdict to give, so it is a failed
         * request, never applied.
         */
        private suspend fun applyQueuedResult(request: ToolRequest.Result): String? {
            if (kinds == null) return "submit_result needs Loop mode, a kinds: key in WORKFLOW.md"
            submitResult(Result.success(request.text), RESULT_MCP_SOURCE)
            return null
        }

        /**
         * Acts on a submitted result, the text of the result file (m2-15) or of a `submit_result` call (m2-16), as
         * [source] names it: saves it beside the evidence, then acts on its status. `done` is a submitted result moving
         * to [HANDOFF_STATE], verified like an intercepted move; `needs_info` comments the summary, the agent's question,
         * and moves the issue to `needs_info`; `blocked` and `failed` end the attempt as a failure with the summary as
         * the error. A text that cannot be read or is not a valid result records `result_invalid` and fails the attempt.
         */
        private suspend fun submitResult(
            taken: Result<String>,
            source: String,
        ) {
            val saved = taken.getOrNull()?.let { saveResult(it, source) }
            // The result file's events keep their m2-15 data; only another source is named.
            val named = source.takeIf { it != RESULT_FILE_SOURCE }
            val result =
                taken.mapCatching { SubmittedResult.parse(it).getOrThrow() }.getOrElse { error ->
                    val reason = redactor.redact(error.message ?: error.describe()).take(REASON_MAX)
                    record(ResultInvalid, data = mapOfNotNull(Results.REASON to reason, Results.RESULT to saved, Results.SOURCE to named))
                    log.log(LogLevel.WARN, "result", LogOutcome.FAILED, logContext(), reason = reason)
                    resultFailure = ResultFailure(INVALID_RESULT, reason)
                    return
                }
            val data =
                mapOfNotNull(
                    Results.STATUS to result.status.value,
                    Results.SOURCE to source,
                    Results.ACCEPTANCE to result.acceptance.size.toString(),
                    Results.RESULT to saved,
                )
            val summary = result.summary?.let { redactor.redact(it).trim().take(REASON_MAX) }
            when (result.status) {
                SubmittedResult.Status.Done -> applyResult(HANDOFF_STATE, named) { applySubmitted(HANDOFF_STATE, data) }
                SubmittedResult.Status.NeedsInfo -> {
                    record(ResultSubmitted, data = data)
                    apply(ToolRequest.Comment(checkNotNull(result.summary)))
                    apply(ToolRequest.Move(NEEDS_INFO))
                }
                SubmittedResult.Status.Blocked, SubmittedResult.Status.Failed -> {
                    record(ResultSubmitted, data = data + mapOfNotNull(Results.REASON to summary))
                    val reason = if (result.status == SubmittedResult.Status.Blocked) RESULT_BLOCKED else RESULT_FAILED
                    resultFailure = ResultFailure(reason, summary.orEmpty())
                }
            }
        }

        /**
         * Removes a `.loop/result.json` left in the workspace before the first turn, in Loop mode (m2-15): a turn
         * that wrote it and then failed or timed out never drained, so its result is never submitted by a later
         * attempt. The removal records `result_discarded`; a file that cannot be removed fails the attempt, its
         * reason returned, since the next drain would take it.
         */
        private suspend fun discardStaleResult(path: Path): String? {
            if (kinds == null) return null
            val removed =
                try {
                    withContext(io) { ResultFile.discard(path) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return redactor.redact("cannot remove a stale ${ResultFile.PATH}: ${e.describe()}").take(REASON_MAX)
                }
            if (removed) record(ResultDiscarded, data = mapOf(Results.REASON to STALE_RESULT))
            return null
        }

        /**
         * Runs the move of a `done` result; a failure records `tool_request_failed` with `request` = `result`, and the
         * [source] of a result that is not the file (m2-16), and is named in the next turn's guidance, like a queued
         * move (m2-07).
         */
        private suspend fun applyResult(
            to: String,
            source: String?,
            move: suspend () -> String?,
        ) {
            val failure =
                try {
                    move()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.describe()
                }
            failure?.let { reason ->
                failed(
                    mapOfNotNull(
                        ToolRequestEvent.REQUEST to RESULT_REQUEST,
                        Tracker.TO to to,
                        Tracker.REASON to reason,
                        ToolRequestEvent.SOURCE to source,
                    ),
                )
            }
        }

        /**
         * Saves the text of a submitted result beside the evidence (m2-15, m2-16); its reference, null when none is kept
         * or the write failed. A failed write names the result by its [source]: the file's path, or `submit_result`.
         */
        private suspend fun saveResult(
            text: String,
            source: String,
        ): String? =
            try {
                withContext(io) { evidence.writeResult(projectId, attemptId, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = redactor.redact("cannot save the result: ${e.describe()}").take(REASON_MAX)
                val name = if (source == RESULT_FILE_SOURCE) ResultFile.PATH else SUBMIT_RESULT_TOOL
                record(EvidenceRecordFailed, data = mapOf(Results.RESULT to name, Results.REASON to reason))
                log.log(LogLevel.WARN, "evidence_record", LogOutcome.FAILED, logContext(), reason = reason)
                null
            }

        /** The verification of this attempt's results, null before its workspace exists. */
        private fun verification(): Verification? {
            val path = workspace?.path ?: return null
            return Verification(
                issue.id,
                issue.identifier,
                issue.description,
                attemptId,
                workflow,
                kind,
                path,
                checkRunner,
                { definition, data -> record(definition, data = data) },
                ::logContext,
                ::movedItTo,
                host = verificationHost,
            )
        }

        /** Adds the comment, redacted with the run's redactor, and records `issue_commented` by host. */
        private suspend fun applyComment(request: ToolRequest.Comment): String? {
            val text = redactor.redact(request.text).trim()
            val comment = IssueComment(agentAuthor(), clock.now(), text)
            withContext(io) { tracker.addComment(issue, comment) }.getOrElse { return it.describe() }
            record(IssueCommented, data = mapOf(Tracker.CHARS to text.length.toString(), Tracker.BY to BY_HOST))
            return null
        }

        /** How many follow ups this attempt filed (m4-33), for [Followups.MAX_PER_ATTEMPT]. */
        private var followupsFiled = 0

        /**
         * A queued follow up (m4-33), filed and named on the parent by [Followups], in the tracker of the issue or the one
         * its kind names. The failure's reason, null once it is filed.
         */
        private suspend fun applyFollowup(request: ToolRequest.Followup): String? {
            Followups.refusal(followupsFiled)?.let { return it }
            val title = redactor.redact(request.title).trim()
            val body = Followups.body(redactor.redact(request.body), issue.identifier, issue.id, attemptId.value)
            val target = Followups.trackerOf(issue.id, kind?.config?.followupTracker, workflow.trackerId)
            val filed = withContext(io) { Followups.file(trackers, target, title, body) }.getOrElse { return it.describe() }
            followupsFiled++
            record(
                FollowupFiled,
                data = mapOf(FollowupFiled.PARENT to issue.id, FollowupFiled.FOLLOWUP to filed.id, FollowupFiled.TRACKER to filed.tracker),
            )
            return applyComment(ToolRequest.Comment(Followups.parentComment(filed, title)))
        }

        /** The author of the agent's comments, `agent <short attempt id>`. */
        private fun agentAuthor(): String = "agent ${attemptId.value.take(SHORT_ID)}"

        /** The `request` and `to` or `chars` data of the tool request events; the length is of the redacted text. */
        private fun requestData(request: ToolRequest): Map<String, String> =
            when (request) {
                is ToolRequest.Move -> mapOf(ToolRequestEvent.REQUEST to request.kind, Tracker.TO to request.to)
                is ToolRequest.Comment -> {
                    val chars = redactor.redact(request.text).trim().length
                    mapOf(ToolRequestEvent.REQUEST to request.kind, Tracker.CHARS to chars.toString())
                }
                is ToolRequest.Result -> mapOf(ToolRequestEvent.REQUEST to request.kind, ToolRequestEvent.SOURCE to RESULT_MCP_SOURCE)
                is ToolRequest.Ask -> mapOf(ToolRequestEvent.REQUEST to request.kind, QuestionAsked.QUESTION_KIND to request.questionKind)
                is ToolRequest.Followup -> mapOf(ToolRequestEvent.REQUEST to request.kind)
            }

        private val toolOutputs = ToolOutputQueue(directory)

        /**
         * Moves the full texts of the tool replies `loop tool mcp` trimmed (m3-14) into the Attempt file as `tool_output`
         * records, redacted, and deletes their files, at every drain and when the attempt ends, whatever its turn did.
         * A file that cannot be read is deleted; a failure is logged and never fails the attempt.
         */
        private suspend fun keepToolOutputs() {
            val pending =
                try {
                    withContext(io) { toolOutputs.pending() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log(LogLevel.WARN, TOOL_OUTPUT, LogOutcome.FAILED, logContext(), reason = e.describe())
                    return
                }
            for ((file, output) in pending) {
                output?.let { appendRecord(AttemptRecord.ToolOutput(clock.now(), it.tool, redactor.redact(it.text))) }
                try {
                    withContext(io) { Files.deleteIfExists(file) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log(LogLevel.WARN, TOOL_OUTPUT, LogOutcome.FAILED, logContext(), reason = e.describe())
                }
            }
        }

        /** Deletes one request file; a failure is logged, the file may then apply again on the next drain. */
        private suspend fun removeRequest(entry: QueuedRequest) {
            val failure =
                try {
                    withContext(io) { Files.deleteIfExists(entry.file) }
                    null
                } catch (e: Exception) {
                    e
                }
            failure?.let {
                log.log(LogLevel.WARN, "tool_request", LogOutcome.FAILED, logContext(), reason = it.describe())
            }
        }

        /**
         * Deletes [directory] when this attempt created it and it is still a real directory, never
         * following a symlink. A failure is logged and never stops the attempt from closing.
         */
        @OptIn(ExperimentalPathApi::class)
        suspend fun deleteDirectory() {
            if (!directoryCreated) return
            val failure =
                try {
                    withContext(io) {
                        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) directory.deleteRecursively()
                    }
                    null
                } catch (e: Exception) {
                    e
                }
            failure?.let {
                log.log(LogLevel.WARN, "attempt_directory", LogOutcome.FAILED, logContext(), reason = it.describe())
            }
        }

        fun logContext(): LogContext =
            LogContext(
                runId = runId,
                projectId = projectId,
                issueId = issue.id,
                issueIdentifier = issue.identifier,
                attemptId = attemptId,
                sessionId = sessionId,
                turnId = turnId,
            )

        suspend fun record(
            definition: EventDefinition,
            phase: AttemptPhase? = null,
            data: Map<String, String> = emptyMap(),
        ) {
            when (definition) {
                Verdict -> ledger.verdict(data)
                IssueMoved -> movedTo = data[Tracker.TO]
            }
            val event = event(definition, phase, data)
            log.guardSink(sinkName, logContext()) { sink.record(event).getOrThrow() }
        }

        /** An event of this attempt with its current ids, not recorded yet. */
        fun event(
            definition: EventDefinition,
            phase: AttemptPhase? = null,
            data: Map<String, String> = emptyMap(),
        ): RecordedEvent =
            definition.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = issue.id,
                issueIdentifier = issue.identifier,
                attemptId = attemptId,
                sessionId = sessionId,
                turnId = turnId,
                phase = phase?.name,
                data = data,
            )

        suspend fun recordPhase(
            phase: AttemptPhase,
            data: Map<String, String> = emptyMap(),
        ) = record(Phase, phase, data)

        /** Records the terminal phase and `attempt_finished`, then logs the attempt's end. */
        suspend fun close(
            end: Closing,
            startedAt: Instant,
        ) {
            if (end.outcome == AttemptOutcome.Succeeded) {
                recordPhase(
                    AttemptPhase.Succeeded,
                    mapOfNotNull(
                        Phase.ISSUE_STATE to end.issueState,
                        Phase.EXIT_REASON to end.exitReason,
                        Phase.TURNS to end.turnsOrNull(),
                    ),
                )
            } else {
                val state = if (end.reconciled != null) end.issueState else null
                recordPhase(end.failedPhase, mapOfNotNull(Phase.REASON to end.reason, Phase.ISSUE_STATE to state))
            }
            // A reconciliation stop is a human's change of the issue (m2-19), unless it is this attempt's own move.
            if (end.reconciled != null && !movedItTo(end.issueState) && !(handedOff() && end.reconciled == ExitReason.NOT_ACTIVE)) {
                record(HumanTouched, data = mapOfNotNull(HumanTouched.TOUCH to HumanTouch.MOVE, HumanTouched.TO_STATE to end.issueState))
            }
            val outcomeName = if (end.canceled) CANCELED else end.outcome.name.lowercase()
            record(
                AttemptFinished,
                data =
                    mapOfNotNull(
                        AttemptFinished.OUTCOME to outcomeName,
                        AttemptFinished.REASON to end.reason,
                        AttemptFinished.ERROR to end.error,
                        AttemptFinished.EXIT_REASON to end.exitReason,
                        AttemptFinished.TURNS to end.turnsOrNull(),
                    ),
            )
            recordRow(end, outcomeName, startedAt)
            // Its row is in the ledger now, so the day budgets of other attempts read it there (m2-20).
            liveSpend.remove(attemptId)
            val succeeded = end.outcome == AttemptOutcome.Succeeded
            log.log(
                when {
                    succeeded -> LogLevel.INFO
                    end.canceled -> LogLevel.WARN
                    else -> LogLevel.ERROR
                },
                "attempt",
                if (succeeded) LogOutcome.COMPLETED else LogOutcome.FAILED,
                logContext(),
                fields =
                    mapOf(
                        "issue_state" to end.issueState,
                        "exit_reason" to end.exitReason,
                        "turns" to end.turnsOrNull(),
                        "duration_ms" to (clock.now() - startedAt).inWholeMilliseconds,
                    ),
                reason = end.reason,
            )
        }

        /**
         * Runs `after_run` once the workspace exists, whatever the attempt's end (spec 9.4,
         * spec-decisions answer 2). Its failure is recorded and ignored; it never throws,
         * so the attempt always closes. A cancelled attempt gives it at most
         * [CANCELED_AFTER_RUN_MAX_MS] (plan R5).
         */
        suspend fun afterRun(canceled: Boolean) {
            val workspace = workspace ?: return
            val max = if (canceled) CANCELED_AFTER_RUN_MAX_MS else Long.MAX_VALUE
            try {
                workspaces.afterRun(workspace, env, hooks, max)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(LogLevel.WARN, "hook", LogOutcome.FAILED, logContext(), mapOf("hook" to "after_run"), e.describe())
            }
        }

        /** Runs the attempt up to its terminal phase: its turns, then the outcome and why it ended. */
        suspend fun execute(): Executed {
            freezeAtDispatch()
            val key = NamespacedId.workspaceKey(issue.id, issue.identifier, workflow.trackerId)
            // Before env and every hook, so hooks and the agent see a valid LOOP_TOOL_CONFIG (m1-23).
            createDirectory()?.let { reason ->
                recordPhase(AttemptPhase.PreparingWorkspace, mapOf(Phase.WORKSPACE_KEY to key.value))
                return Executed(AttemptOutcome.Failed, reason)
            }
            val env = env(key)
            // bl-59: every hook of the attempt, not the agent, gets the base fetched right before them.
            val fetchedBase = attemptBase()
            val hookEnv = env + fetchedBase?.let { mapOf(LOOP_BASE_REF to it) }.orEmpty()
            val codeKind = workflow.config.kinds?.code
            val configuredBase = codeKind?.guards?.base ?: codeKind?.delivery?.pullRequest?.base
            val start = cloneStart(fetchedBase, configuredBase)
            // bl-04: the phase is recorded once the folder exists, before after_create and before_run, as plan 10.1 shows it.
            var phaseRecorded = false
            val workspace =
                workspaces
                    .prepare(key, hookEnv, hooks, start) { created ->
                        recordPhase(AttemptPhase.PreparingWorkspace, mapOf(Phase.WORKSPACE to created.path.toString()))
                        phaseRecorded = true
                    }.getOrElse { error ->
                        if (!phaseRecorded) recordPhase(AttemptPhase.PreparingWorkspace, mapOf(Phase.WORKSPACE_KEY to key.value))
                        return Executed(AttemptOutcome.Failed, error.describe())
                    }
            if (workspaces.clones != null && workspace.createdNow && start.base == null) {
                // bl-28: said, not silent, since the operator's checked out branch is then the start of the work.
                val reason =
                    "no base resolves in the project, so the new clone started at its ${WorkspaceClones.branch(key)} branch " +
                        "when it has one, else at its HEAD; set kinds.code.guards.base or the pull request base"
                log.log(LogLevel.WARN, "clone_start", LogOutcome.COMPLETED, logContext(), reason = reason)
            }
            if (workspace.createdNow) {
                // bl-246: said at once, since the import of a verified commit then refuses to move that branch.
                workspaces.clones?.let { clones -> withContext(io) { clones.checkedOutWarning(key) } }?.let { reason ->
                    log.log(LogLevel.WARN, "clone_branch_checked_out", LogOutcome.COMPLETED, logContext(), reason = reason)
                }
            }
            this.workspace = workspace
            this.env = hookEnv
            val beforeRun = workspaces.beforeRun(workspace, hookEnv, hooks)
            // Spec 9.4 and spec-decisions answer 2: fatal to this attempt only.
            beforeRun?.reason?.let { return Executed(AttemptOutcome.Failed, it) }
            discardStaleResult(workspace.path)?.let { return Executed(AttemptOutcome.Failed, it) }

            // Null on a first dispatch, including the first after a resume or a restart; the spec attempt on a retry.
            val attempt = dispatchAttempts[issue.id]
            // m4-32: after `loop send-back` the human's reason, the path a refuted merge check takes, with no slot taken.
            val sentBack = dispatchSentBacks[issue.id]
            val sentBackFindings = sentBack?.let(SendBacks::findings)
            // The findings of a refutation that ended the last attempt, null otherwise (m2-06). bl-224: a reaction's from
            // the run store when a restart lost them.
            val findings = sentBackFindings ?: verifyFindings[issue.id] ?: storedReactionFindings()
            // bl-19: the folder held earlier work before this attempt, whatever attempt says (a restart, a resume).
            val workspaceReused = !workspace.createdNow
            // bl-06: once per dispatch, so an answer the tracker keeps outside the body (a GitHub comment) is in the
            // description the prompt renders. A failure starts no agent: the attempt fails and retries with the backoff.
            val issue =
                completeIssue().getOrElse { error ->
                    recordPhase(AttemptPhase.BuildingPrompt)
                    return Executed(AttemptOutcome.Failed, "tracker error: ${error.describe()}")
                }
            // m4-52: the dispatch after `loop answer` shows the question and the answer, with the retry kind `answer`.
            val answered = dispatchAnswers[issue.id]
            val answer = answered?.let(Answers::prompt)
            // m3-11: continuation, error or refuted on a retry, null on a first dispatch.
            val retryKind =
                when {
                    answer != null -> PromptVariables.RETRY_ANSWER
                    sentBackFindings != null -> PromptVariables.RETRY_SENT_BACK
                    else -> dispatchRetryKinds[issue.id]
                }
            // m3-10: a retry or a reused workspace gets a session brief built from facts, a fresh first dispatch none.
            val brief = if (attempt != null || workspaceReused) sessionBrief(issue, workspace.path, fetchedBase, findings) else null
            // m4-19: the kind's own template or the body, and its context sources as the workspace holds them now.
            val sources = contextSources(workspace.path)
            val (template, rendered) =
                templates
                    .parse(KindPrompt.template(workflow, kind))
                    .mapCatching {
                        it to
                            it
                                .render(
                                    PromptVariables.of(issue, attempt, findings, workspaceReused, brief, retryKind, sources, answer),
                                ).getOrThrow()
                    }.getOrElse { error ->
                        recordPhase(AttemptPhase.BuildingPrompt)
                        return Executed(AttemptOutcome.Failed, "template error: ${error.describe()}")
                    }
            verifyFindings -= issue.id
            // bl-221: the answer or the reason is in the prompt, and its wait ends once a turn completed, see endCarried.
            carried = listOfNotNull(answered, sentBack)
            promptInputs = PromptInputs(attempt, findings, workspaceReused, workspace.path, fetchedBase)
            // m3-12: only a session that can rotate needs the commit, and the working tree (bl-136), its progress starts from.
            if (runner.capabilities.occupancy) {
                val (head, tree) = workspaceFacts(workspace.path)
                progress = RotationProgress(head, tree)
            }
            // m3-14: over its limit the prompt is filled slot by slot, each slot cut by its rule.
            val filled =
                fillPrompt(template, rendered, PromptContent(issue, findings, brief, sources, answer), attempt, workspaceReused, retryKind)
            val prompt = filled.prompt
            val promptRecordError = recordPrompt(prompt)
            val promptData =
                mapOf(Phase.PROMPT_CHARS to prompt.length.toString()) +
                    promptSlots(template, attempt, filled, workspaceReused, retryKind)
            recordPhase(AttemptPhase.BuildingPrompt, promptData)
            promptRecordError?.let { reason ->
                record(PromptRecordFailed, data = mapOf(PromptRecordFailed.REASON to reason))
                log.log(LogLevel.WARN, "prompt_record", LogOutcome.FAILED, logContext(), reason = reason)
            }

            val guardBase =
                resolveAttemptBaseRef(workspace.path, fetchedBase, configuredBase)
                    ?: cloneGuardBase(key)
                    ?: if (workflow.workspaceGit == WorkspaceGitMode.Clone) {
                        return Executed(
                            AttemptOutcome.Failed,
                            "no base commit resolves in the project for the clone workspace ${key.value}, and its keep ref " +
                                "${WorkspaceClones.keepRef(key)} is gone; a clone never takes its base from its own refs",
                        )
                    } else {
                        null
                    }
            val ctx =
                AttemptContext(
                    runId = runId,
                    projectId = projectId,
                    issue = issue,
                    attemptId = attemptId,
                    workspace = workspace.path,
                    env = env,
                    baseRef = guardBase,
                )
            thresholds = TokenThresholds(thresholdEvery, recordedThreshold())
            // An issue or a day already past its budget parks before a turn spends more (m2-20).
            checkBudgets()
            if (breach != null) return budgetEnd(turns = 0)
            val resume = networkResume.choose(issue.id, workflow.runner.kind, workflow.config.context.softLimit)
            session = NetworkResume.open(runner, ctx, resume)
            val executed =
                try {
                    runTurns(ctx, prompt)
                } finally {
                    endSession()
                }
            val parks = executed.parkReason != null
            val waits = executed.exitReason == ExitReason.WAITING_APPROVAL || executed.exitReason == ExitReason.WAITING_ASK
            return if (StopUsage.budgetWins(breach != null, parks, waits)) budgetEnd(executed.turns) else executed
        }

        /**
         * Stops the session at the attempt's end, with the usage callback like every stop (m4-04), then writes the
         * `stop` ledger line of what a stop read that no turn line holds. Its failure is recorded and ignored. A rotation
         * still pending, its old session stopped but no turn of the new one started, is recorded first, without a new
         * session's ids (bl-152): a `loop stop`, a reconciliation stop or an error that ends the attempt in the middle of
         * the rotation then keeps `session_rotated` in the trace.
         */
        private suspend fun endSession() =
            withContext(NonCancellable) {
                recordRotation(newThreadId = null, runtimeSessionId = null)
                session?.let { stopUsage.stop(it) }
                session = null
                val fraction = occupancy.fraction(workflow.config.context.windows)
                networkResume.sessionEnded(issue.id, attemptId, workflow.runner.kind, runtimeSessionId, fraction)
                StopUsage.line(lastTurnLine, ledger, clock.now())?.let { line ->
                    ledgerWrite { withContext(io) { turnLedger.append(line) } }
                }
            }

        /**
         * The session brief of [issue] in [workspace] (m3-10, plan 9.2), diffed against [fetchedBase], the base fetched
         * for this attempt (bl-59), else the code kind's `guards.base`. It never fails: a part it cannot read is one line.
         */
        private suspend fun sessionBrief(
            issue: Issue,
            workspace: Path,
            fetchedBase: String?,
            findings: String?,
            rotations: Int = 0,
        ): String {
            val base =
                fetchedBase ?: kind
                    ?.config
                    ?.guards
                    ?.base ?: GuardsConfig.DEFAULT_BASE
            val brief = SessionBrief(workspaceGit, attemptLedger, attemptEvents, SessionBrief.savedResults(loopHome, projectId), redactor)
            return withContext(io) { brief.build(projectId, issue, workspace, base, findings, rotations) }
        }

        /** The issue as the tracker completes it for the prompt (bl-06); a thrown error is a failure too. */
        private suspend fun completeIssue(of: Issue = issue): Result<Issue> =
            try {
                withContext(io) { tracker.completeIssue(of) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

        /**
         * Freezes the acceptance command list of the issue as it was dispatched, before the agent runs, when the kind
         * declares `acceptance_commands` and the issue has no list yet (m2-09). A store failure is logged and never
         * fails the attempt: its verification freezes the same dispatched list, or parks the issue when it cannot.
         */
        private suspend fun freezeAtDispatch() {
            if (!declaresAcceptanceCommands(kind?.config) || workflow.trackers.entryOf(issue.id).kind != FILES_TRACKER) return
            val commands = AcceptanceCriteria.of(issue.description).commands
            freezeAcceptanceCommands(issue.id, issue.identifier, commands) { definition, data -> record(definition, data = data) }
                .onFailure { error ->
                    val reason = redactor.redact("cannot freeze the acceptance commands: ${error.describe()}").take(REASON_MAX)
                    log.log(LogLevel.WARN, ACCEPTANCE_COMMANDS, LogOutcome.FAILED, logContext(), reason = reason)
                }
        }

        /**
         * The findings of the issue's last reaction that scheduled a retry, from the run store (bl-224), for a retry of
         * kind `reaction` whose findings this process does not hold, as after a restart. Null for any other retry, and
         * when the store cannot be read, logged: the retry then renders without them, as before.
         */
        private suspend fun storedReactionFindings(): String? {
            if (dispatchRetryKinds[issue.id] != PromptVariables.RETRY_REACTION) return null
            val events =
                reactionsBefore().getOrElse { error ->
                    val reason = redactor.redact("cannot read the reaction findings: ${error.describe()}").take(REASON_MAX)
                    log.log(LogLevel.WARN, REACTIONS, LogOutcome.FAILED, logContext(), reason = reason)
                    return null
                }
            return ReactionMarks.findings(events, issue.id)
        }

        /** The highest threshold the store already holds for this attempt; 0, logged, when it cannot be read. */
        private suspend fun recordedThreshold(): Long =
            try {
                recordedThreshold(attemptId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(LogLevel.WARN, TokenThreshold.name, LogOutcome.FAILED, logContext(), reason = e.describe())
                0L
            }

        /**
         * Records a `token_threshold` event for each step [total] crossed, logs it and runs
         * `hooks.on_token_threshold` (m1-38). [estimated] is true when [total] is the runner's mid turn
         * estimate. The attempt always goes on: nothing here stops it.
         */
        private suspend fun reportThresholds(
            total: Long,
            estimated: Boolean,
        ) {
            for (step in thresholds.crossed(total)) {
                val cost = costUsd?.toString()
                val data =
                    mapOfNotNull(
                        TokenThreshold.TOKENS to total.toString(),
                        TokenThreshold.THRESHOLD to step.toString(),
                        TokenThreshold.COST_USD to cost,
                        TokenThreshold.ESTIMATED to if (estimated) "true" else null,
                    )
                record(TokenThreshold, data = data)
                log.log(LogLevel.INFO, TokenThreshold.name, LogOutcome.REACHED, logContext(), fields = data)
                runThresholdHook(total, step, cost)
            }
        }

        /** Runs `hooks.on_token_threshold` in the workspace; recorded as `hook_finished`, never fatal. */
        private suspend fun runThresholdHook(
            total: Long,
            step: Long,
            cost: String?,
        ) {
            val workspace = workspace ?: return
            val vars =
                mapOf(
                    "LOOP_ISSUE" to issue.identifier,
                    "LOOP_ATTEMPT" to attemptId.value,
                    "LOOP_TOKENS" to total.toString(),
                    "LOOP_THRESHOLD" to step.toString(),
                    "LOOP_COST_USD" to cost.orEmpty(),
                )
            try {
                workspaces.onTokenThreshold(workspace, env + vars, hooks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hook = HookName.ON_TOKEN_THRESHOLD.id
                log.log(LogLevel.WARN, "hook", LogOutcome.FAILED, logContext(), mapOf("hook" to hook), e.describe())
            }
        }

        /** What this attempt spent so far: its tokens and the latest cost the runner reported, none before one. */
        private fun spent(): Spend = Spend(tokens.total, costUsd ?: 0.0)

        /** How an attempt past a budget ends: a failure that parks the issue with `budget` and the budget's level. */
        private fun budgetEnd(turns: Int): Executed {
            val level = checkNotNull(breach)
            return Executed(AttemptOutcome.Failed, BUDGET, turns = turns, parkReason = ParkReason.Budget, parkData = level.data())
        }

        /**
         * Checks the spend budgets (m2-20) on a real usage report and before the first turn: records a
         * `budget_warning` for each budget newly at 80 percent, and on the first budget past its limit records
         * `budget_reached` and sets [breach], which stops the attempt. The issue's earlier attempts and the
         * project's last 24 hours are read through [spendReading]: the ledger rows, and the attempts running in this
         * process, this one included, from memory, their rows being written only when they end.
         */
        private suspend fun checkBudgets() {
            if (!budgets.any) return
            val attempt = spent()
            val read =
                spendReading.read(
                    issue.id.takeIf { budgets.readsIssue },
                    budgets.readsDay,
                    own = attemptId to LiveSpend(issue.id, attempt),
                )
            read.issue?.let { issueSpend = ledgerOf(it, issueSpend) }
            read.day?.let { daySpend = ledgerOf(it, daySpend) }
            val check =
                budgets.check(
                    attempt,
                    issue = read.issue?.total(issueSpend) ?: (issueSpend + attempt),
                    day = read.day?.total(daySpend) ?: daySpend,
                )
            check.warnings.forEach { warnBudget(it) }
            val level = check.breach ?: return
            if (breach != null) return
            breach = level
            record(BudgetReached, data = level.data())
            log.log(LogLevel.WARN, BudgetReached.name, LogOutcome.REACHED, logContext(), fields = level.data())
        }

        /** The ledger part of [scope]; when its read failed, logged, the spend read [last], so the budgets keep it. */
        private fun ledgerOf(
            scope: ScopeSpend,
            last: Spend,
        ): Spend {
            scope.ledger?.let { return it }
            log.log(LogLevel.WARN, BUDGET, LogOutcome.FAILED, logContext(), reason = scope.failure?.describe())
            return last
        }

        /** Records a `budget_warning`, logs it and runs `hooks.on_budget_warning`; the attempt goes on. */
        private suspend fun warnBudget(level: BudgetLevel) {
            record(BudgetWarning, data = level.data())
            log.log(LogLevel.WARN, BudgetWarning.name, LogOutcome.REACHED, logContext(), fields = level.data())
            val workspace = workspace ?: return
            val vars =
                mapOf(
                    "LOOP_ISSUE" to issue.identifier,
                    "LOOP_ATTEMPT" to attemptId.value,
                    "LOOP_BUDGET" to level.budget.key,
                    "LOOP_BUDGET_USED" to level.used,
                    "LOOP_BUDGET_LIMIT" to level.limit,
                )
            try {
                workspaces.onBudgetWarning(workspace, env + vars, hooks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hook = HookName.ON_BUDGET_WARNING.id
                log.log(LogLevel.WARN, "hook", LogOutcome.FAILED, logContext(), mapOf("hook" to hook), e.describe())
            }
        }

        /**
         * The `slot_<name>_chars` data of the [filled] prompt as it is sent (m1-17), then its prompt budget (m3-13): its
         * tokens, the `slot_<name>_tokens`, the prompt limit of the window of the runner's model and whether the prompt is
         * still over it, the counter's method, and the `cut_<name>_tokens` when the fill cut it (m3-14). The slots are
         * left out with a warning when they cannot be measured: a missing measure never fails the Attempt and never
         * changes its prompt.
         */
        private fun promptSlots(
            template: Template,
            attempt: Int?,
            filled: FilledPrompt,
            workspaceReused: Boolean,
            retryKind: String?,
        ): Map<String, String> {
            val counter = TokenCounters.forModel(runner.model)
            val content = filled.content
            val slots =
                PromptSlots.measure(
                    template,
                    content.issue,
                    attempt,
                    filled.prompt,
                    content.verifyFindings,
                    workspaceReused,
                    content.sessionBrief,
                    retryKind,
                    counter,
                    content.contextSources,
                    content.answer,
                )
            if (slots == null) {
                val reason = "the template does not render without the issue"
                log.log(LogLevel.WARN, "prompt_slots", LogOutcome.FAILED, logContext(), reason = reason)
            }
            val limit = workflow.config.context.promptLimitFor(runner.model)
            return slots?.chars?.data.orEmpty() + PromptBudget.data(filled.prompt, slots?.tokens, limit, counter, filled.cuts)
        }

        /**
         * The prompt [template] rendered as [rendered] from [content], filled to the prompt limit of the runner's model
         * (m3-14, plan 8.5): as rendered when it fits, when no limit is known or when a render of the fill fails.
         */
        private suspend fun fillPrompt(
            template: Template,
            rendered: String,
            content: PromptContent,
            attempt: Int?,
            workspaceReused: Boolean,
            retryKind: String?,
        ): FilledPrompt {
            val whole = FilledPrompt(rendered, content, null)
            val limit = workflow.config.context.promptLimitFor(runner.model) ?: return whole
            val counter = TokenCounters.forModel(runner.model)
            if (counter.count(rendered) <= limit.tokens) return whole
            val pointer = issuePointer(content.issue)
            return PromptFill.fill(template, content, attempt, workspaceReused, retryKind, counter, limit.tokens, pointer) ?: whole
        }

        /** The context sources of the attempt's kind as [workspace] holds them now (m4-19), each missing one recorded. */
        private suspend fun contextSources(workspace: Path): String? {
            val sources = KindPrompt.sources(kind, workspace)
            sources.missing.forEach { path ->
                record(
                    ContextSourceMissing,
                    data =
                        mapOf(
                            ContextSourceMissing.PATH to path,
                            ContextSourceMissing.KIND to kind?.name.orEmpty(),
                        ),
                )
            }
            return sources.text
        }

        /**
         * Where the agent reads the full issue when its description is cut (m3-14): the issue's URL when the tracker
         * gives one, else its file of the `files` tracker, else its folder, else the issue in its tracker.
         */
        private suspend fun issuePointer(issue: Issue): String {
            issue.nativeRef?.get(ISSUE_URL)?.let { return "at $it" }
            // m4-13: the issue's own tracker.
            val own = workflow.trackers.entryOf(issue.id)
            if (own.kind != FILES_TRACKER) return "in issue ${issue.identifier} of the ${own.kind} tracker"
            val folder = own.dir
            val names = setOf(issue.id, issue.identifier)
            val file =
                withContext(io) {
                    runCatching {
                        folder
                            .listDirectoryEntries("*.md")
                            .filter { entry -> names.any { entry.name == "$it.md" || entry.name.startsWith("$it-") } }
                            .singleOrNull()
                    }.getOrNull()
                }
            return file?.let { "in the issue file $it" } ?: "in the issue file of ${issue.identifier} under $folder"
        }

        /**
         * Appends the turn 1 [prompt] to the Attempt file (m1-13). A failure gives its redacted reason, null
         * when the record is written. It never throws: a lost prompt record never fails the Attempt.
         */
        private suspend fun recordPrompt(prompt: String): String? =
            appendRecord(AttemptRecord.Prompt(clock.now(), turn = 1, text = prompt))
                ?.exceptionOrNull()
                ?.let { redactor.redact("cannot record the prompt: ${it.describe()}").take(REASON_MAX) }

        /**
         * Appends one redacted runner [line] in full to the Attempt file (m2-18) and gives its sequence number,
         * null when it is not written. The first failure of the Attempt records `runner_output_record_failed`
         * and logs a warning; later ones only lose their line, so a full disk does not flood the store.
         */
        private suspend fun recordRunnerLine(
            stream: RunnerEvent.Output.Stream,
            line: String,
        ): Long? {
            val written = appendRecord(AttemptRecord.RunnerOutput(clock.now(), stream, line)) ?: return null
            written.exceptionOrNull()?.let { error ->
                if (!runnerRecordFailed) {
                    runnerRecordFailed = true
                    val reason = redactor.redact("cannot record the runner output: ${error.describe()}").take(REASON_MAX)
                    record(RunnerOutputRecordFailed, data = mapOf(RunnerOutputRecordFailed.REASON to reason))
                    log.log(LogLevel.WARN, "runner_output_record", LogOutcome.FAILED, logContext(), reason = reason)
                }
            }
            return written.getOrNull()
        }

        /**
         * Appends [record] to the Attempt file and gives its sequence number, or the failure, or null when
         * no file is written. It never throws but for a cancellation.
         */
        private suspend fun appendRecord(record: AttemptRecord): Result<Long>? {
            val writer = attemptRecords ?: return null
            return try {
                withContext(io) { writer.append(projectId, attemptId, record) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Spec 16.5: turn 1 gets the full [prompt]; after each completed turn the issue is read by id and,
         * while it stays routable and below `agent.max_turns`, the next turn gets continuation guidance on
         * the same [session]. A runner without a session runs the one turn. A session past the soft limit is
         * rotated before the next turn (m3-11): that turn opens a new session with a newly rendered prompt, and
         * keeps its number, since turns count across the sessions of an attempt. A turn interrupted at the hard limit
         * (m3-12) ends like a completed one, then rotates with that reason. A wake turn counts against
         * `agent.max_wake_turns`, not `agent.max_turns` ([TurnCount], m4-03).
         */
        private suspend fun runTurns(
            ctx: AttemptContext,
            prompt: String,
        ): Executed {
            val maxTurns = workflow.config.agent.maxTurns
            val count = TurnCount(maxTurns, workflow.config.agent.maxWakeTurns)
            var number = 1
            // The turn's number within its session: a new session starts at 1, so a runtime starts it empty.
            var sessionTurn = 1
            var input = prompt
            while (true) {
                val turn = TurnResult(number, maxTurns)
                val current = session
                val runs = sessionTurn
                runTurn(turn) { current?.runTurn(input, runs) ?: runner.run(ctx, input) }
                val limit = count.end(turn.wake)
                // Before the end reason, max turns and the refresh: an attempt past a budget runs no further turn.
                // The budget deliberately wins over the turn's own failure: the issue is parked, not retried.
                if (turn.capped) {
                    recordFinishing(turn)
                    return budgetEnd(turns = number)
                }
                val failure = turn.failure
                if (failure != null) {
                    recordFinishing(turn)
                    return Executed(AttemptOutcome.Failed, failure, turns = number, unavailable = turn.unavailable)
                }
                if (current == null) {
                    // Loop mode verifies inside the attempt's job, so reconciliation and `loop stop` can stop a
                    // check; the close path drain then finds the queue empty (m2-03).
                    if (workflow.config.kinds != null) drainRequests()
                    recordFinishing(turn)
                    unverifiedEnd(number)?.let { return it }
                    approvalEnd(number)?.let { return it }
                    askEnd(number)?.let { return it }
                    resultFailure?.let { return it.executed(number) }
                    refutedEnd(number, refresh = null)?.let { return it }
                    return Executed(AttemptOutcome.Succeeded, exitReason = ExitReason.NO_CONTINUATION, turns = number)
                }
                // The turn completed normally and its process group is dead: apply its tool requests, then
                // the refresh below sees the moved state (m1-23).
                drainRequests()
                unverifiedEnd(number)?.let {
                    recordFinishing(turn)
                    return it
                }
                approvalEnd(number)?.let {
                    recordFinishing(turn)
                    return it
                }
                askEnd(number)?.let {
                    recordFinishing(turn)
                    return it
                }
                // A blocked, failed or invalid result file ends the attempt as a failure (m2-15).
                resultFailure?.let { failure ->
                    recordFinishing(turn)
                    return failure.executed(number)
                }
                val refresh =
                    refreshIssue(this).getOrElse { error ->
                        recordFinishing(turn)
                        return Executed(
                            AttemptOutcome.Failed,
                            ExitReason.REFRESH_ERROR,
                            turns = number,
                            refresh = Refresh(null),
                            error = error.describe(),
                        )
                    }
                val ended = endReason(refresh, limit)
                if (ended != null) {
                    recordFinishing(turn)
                    if (TurnCount.isLimit(ended)) refutedEnd(number, Refresh(refresh))?.let { return it }
                    return Executed(AttemptOutcome.Succeeded, exitReason = ended, turns = number, refresh = Refresh(refresh))
                }
                // m3-12: a turn interrupted at the hard limit rotates with that reason, else the soft limit is checked.
                val hard = turn.interrupted
                val crossing = hard ?: occupancy.crossing(workflow.config.context.windows, workflow.config.context.softLimit)
                if (crossing != null && runner.capabilities.occupancy) {
                    val limit = if (hard != null) SessionRotated.HARD_LIMIT else SessionRotated.SOFT_LIMIT
                    when (val rotation = rotate(ctx, crossing, limit, number, checkNotNull(refresh))) {
                        is Rotation.Ended -> {
                            recordFinishing(turn)
                            return rotation.executed
                        }
                        is Rotation.Started -> {
                            number++
                            sessionTurn = 1
                            input = rotation.prompt
                            continue
                        }
                    }
                }
                number++
                sessionTurn++
                input =
                    continuationGuidance(
                        checkNotNull(refresh),
                        count.next,
                        maxTurns,
                        drainFindings,
                        drainFailures.values.toList(),
                        workflow.config.context.toolOutputTokens,
                    )
            }
        }

        /**
         * Rotates the session that crossed the soft limit at the end of turn [number] (m3-11, plan 8.4), or the hard limit
         * during it (m3-12), [limit] naming which: stops it, then opens a new one in the same workspace whose first prompt
         * is the template rendered with `loop.retry_kind` = `rotation` and a session brief. Not a retry: no `max_attempts`
         * slot, no backoff. The second rotation in a row without progress parks the issue with `split_issue` instead
         * (m3-12), a rotation past `context.max_rotations` with `max_rotations`, and a failed stop ends the attempt as an
         * error retry rather than start a second process beside one that may still live. [turnEnd] is the issue the turn
         * end's refresh read, the one the rotated prompt shows when the read at the rotation fails (bl-152).
         */
        private suspend fun rotate(
            ctx: AttemptContext,
            crossing: OccupancyMeter.Crossing,
            limit: String,
            number: Int,
            turnEnd: Issue,
        ): Rotation {
            val maxRotations = workflow.config.context.maxRotations
            val occupancyData =
                mapOf(
                    SessionRotated.OCCUPANCY_TOKENS to crossing.tokens.toString(),
                    SessionRotated.WINDOW to crossing.window.toString(),
                    SessionRotated.FRACTION to crossing.fractionText(),
                )
            val inputs = checkNotNull(promptInputs)
            val (head, tree) = workspaceFacts(inputs.workspace)
            val withoutProgress = progress.rotation(head, tree)
            if (withoutProgress >= RotationProgress.SPLIT_AFTER) {
                val parkData =
                    mapOf(
                        IssueParked.WITHOUT_PROGRESS to withoutProgress.toString(),
                        IssueParked.ROTATIONS to rotations.toString(),
                        IssueParked.LIMIT to limit,
                    ) + occupancyData
                return Rotation.Ended(
                    Executed(
                        AttemptOutcome.Failed,
                        SPLIT_ISSUE,
                        turns = number,
                        parkReason = ParkReason.SplitIssue,
                        parkData = parkData,
                    ),
                )
            }
            if (rotations >= maxRotations) {
                val parkData =
                    mapOf(IssueParked.ROTATIONS to rotations.toString(), IssueParked.MAX_ROTATIONS to maxRotations.toString()) +
                        occupancyData
                return Rotation.Ended(
                    Executed(
                        AttemptOutcome.Failed,
                        MAX_ROTATIONS,
                        turns = number,
                        parkReason = ParkReason.MaxRotations,
                        parkData = parkData,
                    ),
                )
            }
            val oldThread = threadId
            val oldRuntime = runtimeSessionId
            val old = session
            session = null
            // bl-132: a wake the old session read at its stop counts in the attempt's spend, as any of its usage does.
            val stopFailure = old?.let { stopUsage.stop(it) }
            // A budget that usage went past starts no new session and wins over a failed stop (m4-04).
            when (StopUsage.rotationEnd(breach != null, stopFailure)) {
                StopUsage.End.Budget -> return Rotation.Ended(budgetEnd(turns = number))
                StopUsage.End.StopFailed ->
                    return Rotation.Ended(Executed(AttemptOutcome.Failed, SessionStopFailed.name, turns = number, error = stopFailure))
                StopUsage.End.Next -> Unit
            }
            rotations++
            spend.closeSession()
            occupancy = OccupancyMeter()
            pendingRotation =
                mapOfNotNull(
                    SessionRotated.REASON to limit,
                    SessionRotated.ROTATION to rotations.toString(),
                    SessionRotated.MAX_ROTATIONS to maxRotations.toString(),
                    SessionRotated.OLD_THREAD_ID to oldThread,
                    SessionRotated.OLD_RUNTIME_SESSION_ID to oldRuntime,
                ) + occupancyData
            log.log(
                LogLevel.INFO,
                "session",
                LogOutcome.ROTATED,
                logContext(),
                fields = mapOf("rotation" to rotations, "max_rotations" to maxRotations, "fraction" to crossing.fractionText()),
                reason = limit,
            )
            // bl-132: the issue as the tracker has it now, the one the turn end read when that read fails (bl-152). A
            // cancel during the read leaves the rotation pending, and the session's end records it (bl-152).
            val issue = rotationIssue(turnEnd)
            // The new session knows nothing of the turn that just ended: it gets the findings of this attempt's latest
            // verdict, none when that verdict is not a refutation (bl-132), the dispatch findings only before any
            // verdict of the attempt, and the notices of the requests the last drain could not apply, the way the
            // next turn of the old session would have.
            val findings = if (verdicts) refuted?.text else inputs.findings
            val notices = drainFailures.values.toList()
            val brief = sessionBrief(issue, inputs.workspace, inputs.fetchedBase, findings, rotations)
            val sources = contextSources(inputs.workspace)
            val (template, rendered) =
                templates
                    .parse(KindPrompt.template(workflow, kind))
                    .mapCatching {
                        val variables =
                            PromptVariables.of(
                                issue,
                                inputs.attempt,
                                findings,
                                inputs.workspaceReused,
                                brief,
                                PromptVariables.RETRY_ROTATION,
                                sources,
                            )
                        it to it.render(variables).getOrThrow()
                    }.getOrElse { error ->
                        // bl-132: the old session is stopped, so the rotation happened: it stays in the trace, without
                        // a new session's ids, before the template error ends the attempt.
                        recordRotation(newThreadId = null, runtimeSessionId = null)
                        return Rotation.Ended(Executed(AttemptOutcome.Failed, "template error: ${error.describe()}", turns = number))
                    }
            // m3-14: filled to the prompt limit like the attempt's first prompt, the notices trimmed after it.
            val content = PromptContent(issue, findings, brief, sources)
            val filled = fillPrompt(template, rendered, content, inputs.attempt, inputs.workspaceReused, PromptVariables.RETRY_ROTATION)
            val prompt = filled.prompt + noticesText(notices, workflow.config.context.toolOutputTokens)
            // A cut rotated prompt records its budget and its cuts on the session_rotated event, as the first prompt does
            // on BuildingPrompt, so loop trace shows what the new session was not given.
            filled.cuts?.let { cuts ->
                val limit = workflow.config.context.promptLimitFor(runner.model)
                val budget = PromptBudget.data(prompt, null, limit, TokenCounters.forModel(runner.model), cuts)
                pendingRotation = pendingRotation.orEmpty() + budget
            }
            appendRecord(AttemptRecord.Prompt(clock.now(), turn = number + 1, text = prompt))
            session = runner.openSession(ctx)
            return Rotation.Started(prompt)
        }

        /**
         * The issue a rotated prompt renders (bl-132): read by id and completed as at dispatch (bl-06), so the new
         * session sees what changed since. A failed read or completion, or an issue the tracker no longer has, falls
         * back to [turnEnd], the issue the turn end's refresh read moments before (bl-152), with one warning on the
         * operator log, `event=rotation_issue`.
         */
        private suspend fun rotationIssue(turnEnd: Issue): Issue {
            val problem =
                readIssue(this).fold(
                    onSuccess = { fresh ->
                        if (fresh == null) {
                            "the tracker no longer has it"
                        } else {
                            completeIssue(fresh).fold(onSuccess = { return it }, onFailure = { it.describe() })
                        }
                    },
                    onFailure = { it.describe() },
                )
            val reason = "the rotated prompt shows the issue as the turn end read it, the read before it failed: $problem"
            log.log(LogLevel.WARN, "rotation_issue", LogOutcome.FAILED, logContext(), reason = reason)
            return turnEnd
        }

        /**
         * How an attempt whose issue is still eligible ends after its last turn [number] when its latest verdict is
         * `refuted` (m2-06): a counted failure with the reason `refuted`, so refutations count against
         * `retries.max_attempts` and an agent that never passes parks. Its findings go to the next attempt's
         * `loop.verify_findings`. Null when the latest verdict is not a refutation.
         */
        private fun refutedEnd(
            number: Int,
            refresh: Refresh?,
        ): Executed? {
            val findings = refuted ?: return null
            verifyFindings[issue.id] = findings.text
            return Executed(AttemptOutcome.Failed, REFUTED, turns = number, refresh = refresh)
        }

        /**
         * How an attempt ends after the turn [number] whose drain gave an `unverified` verdict (m2-08): it runs no
         * further turn and parks the issue with the reason `unverified`, like a budget park, without taking one of
         * the `retries.max_attempts`. Null when the latest verdict is not `unverified`.
         */
        private fun unverifiedEnd(number: Int): Executed? {
            val reason = unverified ?: return null
            return Executed(
                AttemptOutcome.Failed,
                UNVERIFIED,
                turns = number,
                parkReason = ParkReason.Unverified,
                parkData = mapOf(IssueParked.ERROR to reason),
            )
        }

        /**
         * How an attempt ends after the turn [number] whose drain started a `Waiting(approval)` (m2-24): normally,
         * with the exit reason `waiting_approval` and no continuation, since the verified result only waits for a
         * human now. Null when no approval wait started.
         */
        private fun approvalEnd(number: Int): Executed? =
            if (waitingApproval) Executed(AttemptOutcome.Succeeded, exitReason = ExitReason.WAITING_APPROVAL, turns = number) else null

        /**
         * How an attempt ends after the turn [number] whose drain started a `Waiting(ask)` (m4-31): normally, with the exit
         * reason `waiting_ask`, no verification and no continuation, its workspace kept. Null when no ask wait started.
         */
        private fun askEnd(number: Int): Executed? =
            if (asked) Executed(AttemptOutcome.Succeeded, exitReason = ExitReason.WAITING_ASK, turns = number) else null

        /** Why no further turn follows a completed turn, [limit] the turn limit it reached, null when the next one should run. */
        private fun endReason(
            refreshed: Issue?,
            limit: String?,
        ): String? {
            if (refreshed == null) return ExitReason.NOT_FOUND
            val target = RetryTarget.of(refreshed, workflow)
            // m2-23: the attempt's own move to a handoff state ends it even when the issue still reads active.
            if (handedOff() && (target == RetryTarget.Eligible || target == RetryTarget.NotEligible)) return ExitReason.NOT_ACTIVE
            return when (target) {
                RetryTarget.Terminal -> ExitReason.TERMINAL
                RetryTarget.NotActive -> ExitReason.NOT_ACTIVE
                RetryTarget.NotEligible -> ExitReason.NOT_ELIGIBLE
                RetryTarget.Eligible -> limit
            }
        }

        /**
         * bl-221: ends the [carried] wait once, after the first turn that completed and before its drain, so a wait the
         * turn's own `ask` starts is never the one ended. Its failure is logged and the wait stays: the next dispatch
         * carries the same answer or reason again.
         */
        private suspend fun endCarried() {
            val waits = carried
            carried = emptyList()
            waits.forEach { endWaiting(it) }
        }

        /** Collects one turn from [start] into [turn], then records `turn_completed` or `turn_failed`. */
        private suspend fun runTurn(
            turn: TurnResult,
            start: () -> Flow<RunnerEvent>,
        ) {
            ledger.startTurn()
            val startedAt = clock.now()
            try {
                collectTurn(turn, start())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                turn.finished = true
                turn.failure = "runner error: ${e.describe()}"
            }
            // A turn cut by a budget or at the hard limit (m3-12) before any terminal event has none of its own.
            if (turn.capped || turn.interrupted != null) turn.finished = true
            // Without a session the launch phase is still recorded, before Finishing, to keep 7.2 order.
            if (turn.number == 1 && !turn.launched) recordPhase(AttemptPhase.LaunchingAgentProcess)
            if (!turn.finished) turn.failure = "runner ended without a turn result"
            // m3-11: a new session whose first turn never started still records its rotation, without the new ids.
            recordRotation(newThreadId = null, runtimeSessionId = null)
            occupancy.data(workflow.config.context.windows)?.let { record(ContextUsage, data = it) }
            val failure = turn.failure
            if (failure == null) {
                record(TurnCompleted, data = turn.data())
                liftHold()
                endCarried()
            } else {
                val category =
                    mapOfNotNull(
                        TurnEvent.CATEGORY to turn.unavailable?.let { RunnerEvent.TurnFailed.Category.RunnerUnavailable.wire },
                        TurnEvent.RETRY_AFTER_MS to
                            turn.unavailable
                                ?.retryAfter
                                ?.inWholeMilliseconds
                                ?.toString(),
                    )
                record(TurnFailed, data = turn.data() + (TurnEvent.REASON to failure) + category)
            }
            appendTurnLine(turn, startedAt)
        }

        /**
         * Feeds [events] to [onRunnerEvent]. Once a budget is breached the turn is cut: the runner's real terminal
         * event, which follows the breaching usage, is still recorded, but a flow with nothing after it is given
         * only [CAP_GRACE_MS] before it is cancelled (the agent process is stopped as on any cancel). This
         * coroutine goes on, so the turn is neither failed nor CANCELED.
         *
         * m3-12: at the hard limit the session is asked to interrupt the turn at once. Its events go on being recorded,
         * so the usage the runtime reports when it stops counts, until its terminal event, which is not recorded as a
         * failure, or until [INTERRUPT_GRACE_MS] passes, when the flow is cancelled. bl-136: that grace is a deadline,
         * counted from the interrupt request, never restarted by an event, and its expiry records
         * `interrupt_grace_expired`.
         */
        private suspend fun collectTurn(
            turn: TurnResult,
            events: Flow<RunnerEvent>,
        ) = coroutineScope {
            val channel = events.produceIn(this)
            var lastEventAt = clock.now()
            // Completed INTERRUPT_GRACE_MS after the interrupt request, null before one.
            var graceOver: CompletableDeferred<Unit>? = null
            var graceTimer: Job? = null
            try {
                while (true) {
                    val grace = graceOver
                    val received =
                        if (grace != null) {
                            // The deadline is the first clause, so a runtime that keeps writing cannot starve it.
                            select<ChannelResult<RunnerEvent>?> {
                                grace.onAwait { null }
                                channel.onReceiveCatching { it }
                            }
                        } else {
                            // The stall timer restarts on every runner event; the cap grace replaces it once capped.
                            val timeoutMs = if (turn.capped) CAP_GRACE_MS else stallTimeoutMs.takeIf { it > 0 }
                            if (timeoutMs == null) {
                                channel.receiveCatching()
                            } else {
                                withTimeoutOrNull(timeoutMs) { channel.receiveCatching() }
                            }
                        }
                    if (received == null) {
                        when {
                            grace != null -> interruptGraceExpired()
                            !turn.capped -> stalled(turn, lastEventAt)
                        }
                        break
                    }
                    received.exceptionOrNull()?.let { throw it }
                    val event = received.getOrNull() ?: break
                    lastEventAt = clock.now()
                    // The runtime's own end of the interrupted turn: the turn is cut, not failed.
                    if (grace != null && event.isTerminal()) break
                    onRunnerEvent(event, turn)
                    if (turn.interrupted != null && graceOver == null) {
                        val deadline = CompletableDeferred<Unit>()
                        graceOver = deadline
                        graceTimer =
                            launch {
                                delay(INTERRUPT_GRACE_MS)
                                deadline.complete(Unit)
                            }
                        session?.interrupt()
                        continue
                    }
                    if (turn.capped && graceOver == null && event !is RunnerEvent.Usage) break
                }
            } finally {
                graceTimer?.cancel()
                // Cancels the runner flow, which stops the agent process group.
                channel.cancel()
            }
        }

        /**
         * bl-136: the interrupted turn did not end within [INTERRUPT_GRACE_MS] of the interrupt request, so its flow is
         * about to be cancelled and the usage the runtime had not reported is lost. Records `interrupt_grace_expired` with
         * the session's latest occupancy and the latest usage known, and logs a warning.
         */
        private suspend fun interruptGraceExpired() {
            val occupancyData = occupancy.data(workflow.config.context.windows).orEmpty()
            val data =
                mapOfNotNull(
                    InterruptGraceExpired.GRACE_MS to INTERRUPT_GRACE_MS.toString(),
                    InterruptGraceExpired.OCCUPANCY_TOKENS to occupancyData[ContextUsage.LATEST_TOKENS],
                    InterruptGraceExpired.WINDOW to occupancyData[ContextUsage.WINDOW],
                    InterruptGraceExpired.FRACTION to occupancyData[ContextUsage.FRACTION],
                    InterruptGraceExpired.ATTEMPT_TOKENS to ledger.tokens?.let { tokens.total.toString() },
                    InterruptGraceExpired.TOTAL_COST_USD to costUsd?.toString(),
                )
            record(InterruptGraceExpired, data = data)
            log.log(
                LogLevel.WARN,
                "interrupt",
                LogOutcome.FAILED,
                logContext(),
                fields = mapOf("grace_ms" to INTERRUPT_GRACE_MS),
                reason = "the interrupted turn did not end within ${describeMs(INTERRUPT_GRACE_MS)}, its unreported usage is lost",
            )
        }

        /**
         * The runner said nothing for [stallTimeoutMs] (spec 8.5): the turn fails as `stalled`. This coroutine
         * goes on, so the attempt is not CANCELED: it fails and the usual retry or park follows.
         */
        private suspend fun stalled(
            turn: TurnResult,
            lastEventAt: Instant,
        ) {
            turn.finished = true
            turn.failure = STALLED
            val silentMs = (clock.now() - lastEventAt).inWholeMilliseconds
            record(
                AttemptStalled,
                data = mapOf(AttemptStalled.SILENT_MS to silentMs.toString(), AttemptStalled.STALL_TIMEOUT_MS to stallTimeoutMs.toString()),
            )
            log.log(
                LogLevel.WARN,
                "stall",
                LogOutcome.FAILED,
                logContext(),
                fields = mapOf("silent_ms" to silentMs, "stall_timeout_ms" to stallTimeoutMs),
                reason = "no runner event for ${describeMs(stallTimeoutMs)}",
            )
        }

        /**
         * m3-08, m4-02: the first report of a [model] with no window in `context.windows` in this attempt, or of a runner
         * that names no model, logs a warning naming it and, in Loop mode with a runner that declares occupancy, records
         * `context_window_unknown` ([ContextWindows]). Its occupancy is still recorded, in tokens, without a fraction.
         */
        private suspend fun warnUnknownWindow(model: String?) {
            val unknown = contextWindows.firstUnknown(model) ?: return
            if (unknown.recorded) record(ContextWindowUnknown, data = unknown.data)
            val fields = mapOfNotNull(ContextUsage.MODEL to model)
            log.log(LogLevel.WARN, CONTEXT_WINDOW, LogOutcome.FAILED, logContext(), fields, unknown.reason)
        }

        /** bl-39: a successful turn lifts the runner hold, if one holds the run, and records it in this attempt. */
        private suspend fun liftHold() {
            val lifted = hold.onTurnSucceeded(issue.id) ?: return
            val heldMs = lifted.held.inWholeMilliseconds
            record(
                RunnerHoldLifted,
                data =
                    mapOf(
                        RunnerHoldLifted.HOLDS to lifted.holds.toString(),
                        RunnerHoldLifted.HELD_MS to heldMs.toString(),
                    ),
            )
            log.log(
                LogLevel.INFO,
                RUNNER_HOLD,
                LogOutcome.RELEASED,
                logContext(),
                fields = mapOf("holds" to lifted.holds, "held_ms" to heldMs),
            )
        }

        /**
         * Records the pending `session_rotated` of a rotation (m3-11) with the new session's [newThreadId] and
         * [runtimeSessionId], when known. True when it recorded one, false when no rotation was pending.
         */
        private suspend fun recordRotation(
            newThreadId: String?,
            runtimeSessionId: String?,
        ): Boolean {
            val data = pendingRotation ?: return false
            pendingRotation = null
            val ids = mapOfNotNull(SessionRotated.NEW_THREAD_ID to newThreadId, SessionRotated.NEW_RUNTIME_SESSION_ID to runtimeSessionId)
            record(SessionRotated, data = data + ids)
            return true
        }

        private suspend fun recordFinishing(turn: TurnResult) =
            recordPhase(AttemptPhase.Finishing, mapOfNotNull(Phase.EXIT_CODE to turn.exitCode?.toString()))

        /**
         * Records a real usage report of the current session, a turn's or one its stop read [atStop] (bl-132): absolute
         * totals for the session so far, so a later report replaces this one. m3-11: plus the closed sessions of the
         * attempt, so the totals stay absolute for the attempt. A report read at the stop counts in the next turn's
         * ledger share. With [budgets] it checks the spend budgets too.
         */
        private suspend fun recordUsage(
            report: RunnerEvent.Usage,
            budgets: Boolean,
            atStop: Boolean = false,
        ) {
            val event = spend.usage(report)
            tokens.record(event)
            val warning = if (atStop) ledger.usageBetweenTurns(event) else ledger.usage(event)
            warning?.let { log.log(LogLevel.WARN, LEDGER, LogOutcome.COMPLETED, logContext(), reason = it) }
            costUsd = event.totalCostUsd ?: costUsd
            liveSpend[attemptId] = LiveSpend(issue.id, spent())
            val data =
                mapOfNotNull(
                    Usage.ATTEMPT_TOKENS to tokens.total.toString(),
                    Usage.MAX_TOKENS to
                        workflow.config.budgets.perAttemptTokens
                            ?.toString(),
                    Usage.INPUT_TOKENS to event.inputTokens.toString(),
                    Usage.OUTPUT_TOKENS to event.outputTokens.toString(),
                    Usage.CACHE_READ_INPUT_TOKENS to event.cacheReadInputTokens.toString(),
                    Usage.CACHE_CREATION_INPUT_TOKENS to event.cacheCreationInputTokens.toString(),
                    Usage.TOTAL_COST_USD to event.totalCostUsd?.toString(),
                )
            record(Usage, data = data)
            reportThresholds(tokens.total, estimated = false)
            if (budgets) checkBudgets()
        }

        private suspend fun onRunnerEvent(
            event: RunnerEvent,
            turn: TurnResult,
        ) {
            when (event) {
                is RunnerEvent.SessionStarted -> {
                    // The runner mints the session and turn ids before it spawns the
                    // process (plan D4), so the launch phase is recorded here with them.
                    sessionId = event.sessionId
                    turnId = event.turnId
                    turn.threadId = event.threadId
                    turn.turnId = event.turnId
                    turn.launched = true
                    // A new thread: occupancy is measured per session.
                    occupancy = OccupancyMeter()
                    threadId = event.threadId
                    runtimeSessionId = event.runtimeSessionId
                    // m3-11: a rotated session records its rotation, not the once per attempt phases again.
                    val rotated = recordRotation(event.threadId, event.runtimeSessionId)
                    if (!rotated) recordPhase(AttemptPhase.LaunchingAgentProcess)
                    record(TurnStarted, data = turn.data())
                    if (!rotated) {
                        recordPhase(AttemptPhase.InitializingSession, mapOfNotNull(Phase.RUNTIME_SESSION_ID to event.runtimeSessionId))
                    }
                    log.log(LogLevel.INFO, "session", LogOutcome.STARTED, logContext())
                    writeClaim()
                    if (!rotated) recordPhase(AttemptPhase.StreamingTurn)
                }
                is RunnerEvent.TurnStarted -> {
                    sessionId = event.sessionId
                    turnId = event.turnId
                    turn.threadId = event.threadId
                    turn.turnId = event.turnId
                    turn.launched = true
                    threadId = event.threadId
                    recordRotation(event.threadId, runtimeSessionId = null)
                    record(TurnStarted, data = turn.data())
                    writeClaim()
                }
                is RunnerEvent.Output -> {
                    val line = redactor.redact(event.line)
                    // m2-18: the line in full goes to the Attempt file; the event keeps a preview that points to it.
                    val seq = recordRunnerLine(event.stream, line)
                    val data =
                        mapOfNotNull(
                            RunnerOutput.STREAM to event.stream.name.lowercase(),
                            RunnerOutput.LINE to line.take(RUNNER_OUTPUT_MAX),
                            RunnerOutput.CHARS to line.length.toString(),
                            RunnerOutput.TRUNCATED to if (line.length > RUNNER_OUTPUT_MAX) "true" else null,
                            RunnerOutput.RECORD_SEQ to seq?.toString(),
                        )
                    // The observer gets the line in full: a tool result cut at the store's limit loses its end.
                    observer.runnerLine(event(RunnerOutput, data = data), line)
                    record(RunnerOutput, data = data)
                }
                is RunnerEvent.Usage -> {
                    recordUsage(event, budgets = !turn.capped)
                    if (!turn.capped && breach != null) turn.capped = true
                }
                is RunnerEvent.UsageEstimate -> {
                    // Thresholds only (m1-38): an estimate is not recorded as usage and never meets a budget.
                    val event = spend.estimate(event)
                    val total =
                        TokenMeter.totalOf(
                            event.inputTokens,
                            event.outputTokens,
                            event.cacheReadInputTokens,
                            event.cacheCreationInputTokens,
                        )
                    reportThresholds(total, estimated = true)
                }
                is RunnerEvent.ContextUsage -> {
                    // Kept, not recorded: the turn end records the session's latest and peak (m3-08), and checks the soft
                    // limit (m3-11).
                    occupancy.record(event)
                    event.model?.let { reportedModel = it }
                    warnUnknownWindow(event.model)
                    // m3-12: a session that can be rotated is interrupted at once at the hard limit.
                    if (turn.interrupted == null && session != null && runner.capabilities.occupancy) {
                        turn.interrupted = occupancy.crossing(workflow.config.context.windows, workflow.config.context.hardLimit)
                    }
                }
                is RunnerEvent.RateLimit -> {
                    // m3-21: recorded as it comes, a report that never stops the turn; the runner sends only changes.
                    record(RateLimit, data = rateLimitData(event))
                }
                // m4-03: counted at the turn's end, against agent.max_wake_turns instead of agent.max_turns.
                is RunnerEvent.WakeTurn -> turn.wake = true
                // m4-04: reported only by a stop, which StopUsage reads.
                is RunnerEvent.StopGraceExpired -> Unit
                is RunnerEvent.TurnCompleted -> {
                    sessionId = event.sessionId
                    turnId = event.turnId
                    turn.turnId = event.turnId
                    turn.finished = true
                    turn.exitCode = event.exitCode
                }
                is RunnerEvent.TurnFailed -> {
                    sessionId = event.sessionId
                    turnId = event.turnId
                    turn.turnId = event.turnId
                    turn.finished = true
                    turn.exitCode = event.exitCode
                    // The runner's reason carries its error category; the exit code is the fallback.
                    val reason = redactor.redact(event.reason)
                    val failure = reason.ifBlank { event.exitCode?.let { "exit_code=$it" } ?: "turn_failed" }
                    turn.failure = failure
                    // bl-39: only the runner's typed category marks a failure as the runner's, never its text.
                    if (event.category == RunnerEvent.TurnFailed.Category.RunnerUnavailable) {
                        turn.unavailable = RunnerUnavailable(failure, event.retryAfter)
                    }
                }
                is RunnerEvent.TurnInputRequired -> {
                    sessionId = event.sessionId
                    turnId = event.turnId
                    turn.turnId = event.turnId
                    // Finished, so the reason is not replaced by "runner ended without a turn result".
                    turn.finished = true
                    val reason = redactor.redact(event.reason)
                    turn.failure = "${TurnInputRequired.name}: $reason"
                    record(TurnInputRequired, data = mapOf(TurnInputRequired.REASON to reason))
                }
            }
        }

        /**
         * The base commit the hooks of this attempt get as `LOOP_BASE_REF` (bl-59). For a kind with
         * `delivery.pull_request`, the base fetched host side now through the delivery executor's `fetchBase`, recorded
         * as `base_fetched`: a retry reuses its branch and merges the base again, so every attempt fetches, not only the
         * one that creates the workspace. A failed fetch never holds the attempt: it records `base_fetch_failed`, logs a
         * warning and falls back to the last fetched `refs/loop/base/<base>` of the project repository, else null.
         * Null for a kind without pull request delivery, which fetches nothing. No local branch is read or moved.
         */
        private suspend fun attemptBase(): String? {
            val base =
                kind
                    ?.config
                    ?.delivery
                    ?.pullRequest
                    ?.base ?: return null
            return deliveryStep { executorFor(workflow).fetchBase() }.fold(
                onSuccess = { sha ->
                    record(BaseFetched, data = mapOf(Delivery.SHA to sha, Delivery.BASE to base))
                    sha
                },
                onFailure = { error ->
                    val reason = redactor.redact("cannot fetch $base: ${error.describe()}").take(REASON_MAX)
                    val earlier = lastFetchedBase(base)
                    record(BaseFetchFailed, data = mapOfNotNull(Delivery.BASE to base, Delivery.SHA to earlier, Delivery.REASON to reason))
                    log.log(
                        LogLevel.WARN,
                        BaseFetchFailed.name,
                        LogOutcome.FAILED,
                        logContext(),
                        mapOf(Delivery.BASE to base, Delivery.SHA to earlier),
                        reason,
                    )
                    earlier
                },
            )
        }

        /**
         * The commit HEAD of [workspace] is at and the fingerprint of its working tree (bl-136) when the workspace is the
         * top level of a git work tree, for the progress between rotations (m3-12); each null otherwise and when its git
         * read fails, which never counts against the agent. bl-149: the read is bounded and cancellable, and a failed read
         * is logged on the operator log once per fact and attempt, so a workspace that never parks says why.
         */
        private suspend fun workspaceFacts(workspace: Path): Pair<String?, String?> {
            val facts = workspaceFacts.of(workspace)
            facts.failures.forEach { (fact, reason) ->
                if (loggedFactFailures.add(fact)) {
                    log.log(
                        LogLevel.WARN,
                        "progress",
                        LogOutcome.FAILED,
                        logContext(),
                        fields = mapOf("fact" to fact),
                        reason = redactor.redact("$reason, counted as progress").take(REASON_MAX),
                    )
                }
            }
            return facts.head to facts.tree
        }

        /**
         * bl-28: what a clone workspace starts from, when the workflow clones: the fetched base, else the base
         * [resolveAttemptBaseRef] resolves in the project, and `refs/loop/base/<base>` at the fetched base for the agent.
         */
        private suspend fun cloneStart(
            fetchedBase: String?,
            configuredBase: String?,
        ): CloneStart {
            if (workspaces.clones == null) return CloneStart()
            val base = resolveAttemptBaseRef(null, fetchedBase, configuredBase)
            val name =
                kind
                    ?.config
                    ?.delivery
                    ?.pullRequest
                    ?.base
            val baseRef = if (name != null && fetchedBase != null) CloneBase(name, fetchedBase) else null
            return CloneStart(base, baseRef)
        }

        /** The commit `refs/loop/base/[base]` of the project repository is at, null when it has none or git fails (bl-59). */
        private suspend fun lastFetchedBase(base: String): String? = resolveGitCommit(workflow.path.parent, MergeCheck.baseRef(base))

        /**
         * Resolves the attempt base ref to an immutable commit SHA on the host side before the agent
         * starts, so the agent cannot move a branch ref inside the workspace to defeat pre-turn checks.
         */
        private suspend fun resolveAttemptBaseRef(
            workspace: Path?,
            fetchedBase: String?,
            configuredBase: String?,
        ): String? {
            if (fetchedBase != null) return fetchedBase
            val candidates =
                if (configuredBase != null) {
                    listOf(configuredBase)
                } else {
                    listOf("main", "master", "origin/HEAD")
                }
            val cloned = workflow.workspaceGit == WorkspaceGitMode.Clone
            for (candidate in candidates) {
                resolveGitCommit(workflow.path.parent, candidate)?.let { return it }
                // bl-28: a clone's refs are the agent's, so in clone mode the base is resolved in the project only.
                if (workspace != null && !cloned) {
                    resolveGitCommit(workspace, candidate)?.let { return it }
                }
            }
            // bl-28: a name would be resolved in the clone, whose refs the agent owns, so clone mode gives none.
            return if (cloned) null else configuredBase
        }

        /**
         * bl-28: the base of the runner's guards in clone mode when [resolveAttemptBaseRef] gives none: the start
         * commit of the clone, read from its keep ref in the project, never a ref of the clone. Null outside clone
         * mode, and when even the keep ref is gone.
         */
        private suspend fun cloneGuardBase(key: WorkspaceKey): String? {
            if (workflow.workspaceGit != WorkspaceGitMode.Clone) return null
            return resolveGitCommit(workflow.path.parent, WorkspaceClones.keepRef(key))
        }

        private suspend fun resolveGitCommit(
            dir: Path,
            ref: String,
        ): String? =
            try {
                withContext(io) {
                    workspaceGit
                        .run(dir, "rev-parse", "--verify", "-q", "$ref^{commit}")
                        .takeIf { it.code == 0 }
                        ?.stdout
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        private fun env(key: WorkspaceKey): Map<String, String> {
            val launcherDir = launcher.parent.toString()
            return hookEnv(workflow.path, issue.id, issue.identifier, key, kind?.name) +
                mapOf(
                    "LOOP_HOME" to loopHome.toString(),
                    "LOOP_PROJECT_ID" to projectId.value,
                    "LOOP_RUN_ID" to runId.value,
                    "LOOP_ATTEMPT_ID" to attemptId.value,
                    "LOOP_LAUNCHER" to launcher.toString(),
                    "LOOP_TOOL_CONFIG" to toolConfig.toString(),
                    "PATH" to if (hostPath.isNullOrEmpty()) launcherDir else "$launcherDir${File.pathSeparator}$hostPath",
                )
        }
    }

    /** Whether [kind] declares `acceptance_commands` (m2-09). */
    private fun declaresAcceptanceCommands(kind: KindConfig?): Boolean =
        kind
            ?.checks
            .orEmpty()
            .any { it is CheckConfig.AcceptanceCommands }

    /**
     * Freezes [commands], the acceptance command list of [issueId], when the issue has none yet (m2-09), recording
     * `acceptance_commands_frozen` with their count when this call wrote it. Returns the list in the store, the
     * one frozen at the first dispatch, or why the store could not give it.
     */
    private suspend fun freezeAcceptanceCommands(
        issueId: String,
        identifier: String,
        commands: List<String>,
        digest: String = AcceptanceCriteria.digest(commands),
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
    ): Result<FrozenCommands> {
        val freeze =
            try {
                frozenCommands.freeze(FrozenCommands(projectId, issueId, identifier, digest, commands.size, clock.now()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        return freeze.map { found ->
            if (found.written) record(AcceptanceCommandsFrozen, mapOf(AcceptanceCommands.COUNT to commands.size.toString()))
            found.stored
        }
    }

    /**
     * Runs [block] while the issue of [wait] is waiting (m2-04): in memory, so dispatch, retries and the poll
     * sleep leave it alone, and in the store when [write] is true (a restored wait is there already). The wait
     * ends when [block] returns or throws, and on a cancel only when [endOnCancel] says so: a wait kept on a
     * cancel is what the next start runs again.
     */
    private suspend fun <T> waitingFor(
        wait: Wait.Verify,
        write: Boolean,
        endOnCancel: () -> Boolean,
        block: suspend () -> T,
    ): T {
        waits.hold(wait)
        if (write) storeWaiting(wait) { waits.save(wait) }
        var end = true
        try {
            return block()
        } catch (e: CancellationException) {
            end = endOnCancel()
            throw e
        } finally {
            // m2-24: a verify wait that became an approval wait goes on waiting, in memory and in the store. bl-257: one a
            // human sent back during the notice, read by a poll since, is no longer held but is still never deleted here.
            when {
                waits.becameApproval(wait) -> Unit
                end -> withContext(NonCancellable) { endWaiting(wait) }
                else -> waits.forget(wait.issueId)
            }
        }
    }

    /** Ends the wait of [wait]'s issue, in the store and in memory, and wakes the poll sleep. */
    private suspend fun endWaiting(wait: Wait) {
        storeWaiting(wait) { waits.end(wait) }
        attemptEnded.trySend(Unit)
    }

    /**
     * Runs one write of the waits for [wait] (m2-04). A failure is logged at warn and never stops anything:
     * the wait in memory decides for this process. A row left behind is checked again by the next start.
     */
    private suspend fun storeWaiting(
        wait: Wait,
        write: suspend () -> Result<Unit>,
    ) {
        val error = write().exceptionOrNull() ?: return
        log.log(LogLevel.WARN, WAITING, LogOutcome.FAILED, waitContext(wait), reason = error.describe())
    }

    private fun waitContext(wait: Wait): LogContext =
        pollContext.copy(issueId = wait.issueId, issueIdentifier = wait.identifier, attemptId = wait.attemptId)

    /**
     * Reads the waits of the store, once per tick (bl-79), for [restoreWaiting], [syncApprovals] and [syncMerges]. False
     * when the store cannot be read before the waits a previous process left are back: the caller dispatches nothing
     * this tick. A store that cannot be read later is logged and changes nothing: the waits in memory decide for this
     * tick.
     */
    private suspend fun readWaits(): Boolean {
        val error = waits.read().exceptionOrNull() ?: return true
        val level = if (waitingRestored) LogLevel.WARN else LogLevel.ERROR
        log.log(level, WAITING, LogOutcome.FAILED, pollContext, reason = error.describe())
        return waitingRestored
    }

    /**
     * Resumes, once, the waits a previous process left (m2-04), from this tick's read. Each `verify` wait whose issue is
     * still among the active [candidates], in a workflow with `kinds:`, and whose workspace is still there, runs its
     * checks again in [scope] and then applies its move like an attempt's drain; its issue waits meanwhile. Any other
     * `verify` wait ends at once and its issue is dispatched by the usual paths. A wait for another reason keeps its
     * issue waiting, and the merge waits behind the head of the queue go back to phase `check` ([requeueBehindHead]).
     */
    private suspend fun restoreWaiting(
        scope: CoroutineScope,
        candidates: List<Issue>,
    ): List<Deferred<Unit>> {
        if (waitingRestored) return emptyList()
        waitingRestored = true
        requeueBehindHead()
        resumeAfterRestart()
        val byId = candidates.associateBy { it.id }
        return waits.current().mapNotNull { record -> resumeWait(scope, record, byId[record.issueId]) }
    }

    /**
     * bl-172: at startup, the merge waits behind the head of the queue found in phase `ci` or `merge`, which a Loop that
     * delivered several pull requests at once may have left, go back to phase `check` at their round, as a new round
     * does, so only the head holds the queue. Each records `merge_requeued` naming the head. A wait that cannot be
     * written is logged and stays as it is: [syncMerges] never advances it while it is behind the head.
     */
    private suspend fun requeueBehindHead() {
        val queue = MergeQueue.of(waits.current())
        val head = queue.firstOrNull() ?: return
        for (wait in queue.drop(1).filter { it.phase != MergePhase.Check }) {
            if (!storeWait(wait.again(wait.round), waitContext(wait))) continue
            val phase = if (wait.phase == MergePhase.Ci) WaitKeys.PHASE_CI else WaitKeys.PHASE_MERGE
            recordWaitEvent(
                wait,
                MergeRequeued,
                mapOf(MergeRequeued.PHASE to phase, Delivery.ROUND to wait.round.toString(), MergeRequeued.HEAD_ISSUE to head.identifier),
            )
            log.log(
                LogLevel.INFO,
                MergeCheck.MARK,
                LogOutcome.SCHEDULED,
                waitContext(wait),
                mapOf("round" to wait.round, "phase" to phase),
                "behind ${head.identifier} in the delivery queue",
            )
        }
    }

    /**
     * bl-175: the pause an earlier run left on the delivery queue ends with this restart, since the pause is held in
     * memory only. Records `delivery_queue_resumed` with `by` = `restart` on the issue that paused it.
     */
    private suspend fun resumeAfterRestart() {
        val paused = queuePausedBefore() ?: return
        val context = pollContext.copy(issueId = paused.issueId, issueIdentifier = paused.issueIdentifier, attemptId = paused.attemptId)
        recordQueueResumed(context, DeliveryQueueResumed.RESTART)
    }

    /**
     * bl-175: the CI wait of [wait], the head of the delivery queue, timed out and fell back to approval: the queue
     * pauses, records `delivery_queue_paused` and starts no merge check until [resumeOnApproval] or a restart.
     */
    private suspend fun pauseQueue(wait: Wait.Merge) {
        if (!queuePause.pause(wait, clock.now())) return
        recordWaitEvent(
            wait,
            DeliveryQueuePaused,
            mapOf(DeliveryQueuePaused.ISSUE to wait.identifier, Delivery.REASON to DeliveryQueuePaused.CI_TIMEOUT),
        )
        log.log(
            LogLevel.WARN,
            DELIVERY_QUEUE,
            LogOutcome.STARTED,
            waitContext(wait),
            reason = "the CI wait timed out, no merge check starts until loop approve or a restart",
        )
    }

    /**
     * bl-175: a paused delivery queue resumes when one of [approvals] was approved with `loop approve` since the pause,
     * whatever its issue. Records `delivery_queue_resumed` with `by` = `approve` on the issue that paused it.
     */
    private suspend fun resumeOnApproval(approvals: List<Wait.Approval>) {
        val paused = queuePause.resumeOnApproval(approvals) ?: return
        val context = pollContext.copy(issueId = paused.issueId, issueIdentifier = paused.identifier, attemptId = paused.attemptId)
        recordQueueResumed(context, DeliveryQueueResumed.APPROVE)
    }

    private suspend fun recordQueueResumed(
        context: LogContext,
        by: String,
    ) {
        val event =
            DeliveryQueueResumed.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = context.issueId,
                issueIdentifier = context.issueIdentifier,
                attemptId = context.attemptId,
                data = mapOf(Delivery.BY to by),
            )
        log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
        log.log(LogLevel.INFO, DELIVERY_QUEUE, LogOutcome.RESUMED, context, fields = mapOf("by" to by))
    }

    /** Where the issue of a wait stands this tick, see [standings]. */
    private sealed interface Standing {
        /** The issue is active: among the candidates, or read by id in an active state. */
        class Active(
            val issue: Issue,
        ) : Standing

        /** The by-id read shows the issue out of the active states, [issue], or gone, null. */
        class Gone(
            val issue: Issue?,
        ) : Standing

        /** The by-id read failed: the wait keeps its issue waiting. */
        data object Unread : Standing
    }

    /**
     * The standing of the issue of each of [records] this tick (m2-24, m2-26). An issue not among the active
     * [candidates] is read by id, except those [skip] names, since the by-state read skips a file it cannot parse: it
     * is gone only when that read succeeds and shows it gone or out of the active states. A failed read, a malformed
     * file among them, keeps each of those waits, logged.
     */
    private suspend fun standings(
        records: List<Wait>,
        candidates: List<Issue>,
        skip: (String) -> Boolean = { false },
    ): (String) -> Standing {
        val byId = candidates.associateBy { it.id }
        val missing = records.map { it.issueId }.filter { it !in byId && !skip(it) }
        // m4-13: each tracker read on its own; a failed read fails closed on a malformed file, so no wait of that
        // tracker can end on it this tick.
        val read = withContext(io) { trackers.readByIds(missing) }
        val refreshed = read.issues.associateBy { it.id }
        for ((tracker, error) in read.failures) {
            val reason = "cannot read the issue, it keeps waiting: ${error.describe()}"
            records.filter { it.issueId in missing && trackers.trackerOf(it.issueId) == tracker }.forEach {
                log.log(
                    LogLevel.WARN,
                    WAITING,
                    LogOutcome.FAILED,
                    waitContext(it),
                    reason = reason,
                )
            }
        }
        return { issueId ->
            val candidate = byId[issueId]
            when {
                candidate != null -> Standing.Active(candidate)
                !read.answered(issueId) -> Standing.Unread
                else -> {
                    val issue = refreshed[issueId]
                    val active = issue != null && effective.trackers.entryOf(issueId).isActive(issue.state)
                    if (active) Standing.Active(issue) else Standing.Gone(issue)
                }
            }
        }
    }

    /**
     * Goes through the approval waits of this tick's read (m2-24), so a `loop approve` from another process is seen
     * like a `loop resume` (m1-07). An approved wait is delivered: its issue moves to the wait's target state, recorded
     * as `issue_moved` with `approved`, and the wait ends. A wait whose issue is gone, see [standings], ends at once,
     * the human change wins and is a human touch. Any other keeps its issue waiting. The ids of the issues delivered,
     * which this tick must not dispatch: [candidates] still shows them active.
     */
    private suspend fun syncApprovals(candidates: List<Issue>): Set<String> {
        val approvals = waits.current(WaitReason.Approval).filterIsInstance<Wait.Approval>()
        // bl-175: a `loop approve` since the pause resumes the delivery queue, before this tick's merge waits run.
        resumeOnApproval(approvals)
        val standing = standings(approvals, candidates)
        val delivered = mutableSetOf<String>()
        for (wait in approvals) {
            val candidate =
                when (val found = standing(wait.issueId)) {
                    Standing.Unread -> continue
                    is Standing.Active -> found.issue
                    is Standing.Gone -> {
                        endWaiting(wait)
                        recordWaitEvent(wait, HumanTouched, mapOf(HumanTouched.TOUCH to HumanTouch.MOVE))
                        log.log(
                            LogLevel.INFO,
                            WAITING,
                            LogOutcome.RELEASED,
                            waitContext(wait),
                            mapOf("wait_reason" to wait.reason.wire),
                            "the issue is no longer active",
                        )
                        continue
                    }
                }
            val to = wait.to
            if (to.isNullOrBlank()) {
                endWaiting(wait)
                log.log(
                    LogLevel.INFO,
                    WAITING,
                    LogOutcome.RELEASED,
                    waitContext(wait),
                    mapOf("wait_reason" to wait.reason.wire),
                    "the wait has no target state",
                )
                continue
            }
            // The attempt that asked for the approval may still be closing: it delivers on a later tick.
            if (wait.approved && wait.issueId !in running && deliverApproved(wait, candidate, to)) delivered += wait.issueId
        }
        return delivered
    }

    /**
     * Goes through the ask waits of this tick's read (m4-31): a wait whose issue a human moved, see [Wait.Ask.humanMoved],
     * ends, recorded as a human touch, and the issue is dispatched again like any other. An issue that still runs, the
     * attempt that asked closing, or that cannot be read keeps waiting.
     */
    private suspend fun syncAsks(candidates: List<Issue>) {
        val asks = waits.current(WaitReason.Ask).filterIsInstance<Wait.Ask>().filter { it.issueId !in running }
        if (asks.isEmpty()) return
        val standing = standings(asks, candidates)
        for (wait in asks) {
            val found = standing(wait.issueId)
            val issue =
                when (found) {
                    Standing.Unread -> continue
                    is Standing.Active -> found.issue
                    is Standing.Gone -> found.issue
                }
            if (!wait.humanMoved(issue?.state, active = found is Standing.Active)) continue
            endWaiting(wait)
            val touch =
                mapOfNotNull(
                    HumanTouched.TOUCH to HumanTouch.MOVE,
                    HumanTouched.FROM_STATE to wait.state,
                    HumanTouched.TO_STATE to issue?.state,
                )
            recordWaitEvent(wait, HumanTouched, touch)
            log.log(
                LogLevel.INFO,
                WAITING,
                LogOutcome.RELEASED,
                waitContext(wait),
                mapOf("wait_reason" to wait.reason.wire),
                "a human moved the issue",
            )
        }
    }

    /**
     * Delivers an approved wait (m2-24): on the files tracker, the move of [issue] to [to], its handoff state, with
     * the wait's attempt id. A failed move is logged and the issue keeps waiting, so the next tick tries again.
     * With `delivery.pull_request` the pull request delivery runs instead, its move last (m2-25). True when it was
     * delivered. The delivery is that of the kind the issue was dispatched under, which the wait holds (m4-18), not the
     * one it matches now; a wait whose kind left the workflow keeps waiting, with a warning.
     */
    private suspend fun deliverApproved(
        wait: Wait.Approval,
        issue: Issue,
        to: String,
    ): Boolean {
        val context = waitContext(wait)
        val kind =
            when (val pinned = KindLookup.pinned(effective, wait.kind, issue)) {
                is PinnedKind.Found -> pinned.kind
                is PinnedKind.Gone -> {
                    warnKindGone(wait, pinned.name)
                    return false
                }
            }
        if (kind
                ?.config
                ?.delivery
                ?.pullRequest != null
        ) {
            return deliverPullRequest(wait, issue, to)
        }
        // m4-20: a plain workspace is delivered only while it is still at its verified fingerprint.
        if (KindWorkspaces.isPlain(kind)) {
            val key = NamespacedId.workspaceKey(wait.issueId, wait.identifier, effective.trackerId)
            val stale = withContext(io) { PlainWorkspace.stale(fingerprinter, workspacesFor(effective).locate(key), wait.fingerprint) }
            if (stale != null) {
                val reason = redactor.redact(stale).take(REASON_MAX)
                recordWaitEvent(wait, DeliveryFailed, mapOf(Delivery.STEP to PlainWorkspace.STEP_FINGERPRINT, Delivery.REASON to reason))
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = "cannot deliver the approved result: $reason")
                clearApproval(wait, "${PlainWorkspace.STEP_FINGERPRINT}: $reason")
                return false
            }
        }
        val failure =
            try {
                withContext(io) { tracker.moveIssue(issue, to) }.exceptionOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        if (failure != null) {
            val reason = redactor.redact("cannot deliver the approved result: ${failure.describe()}").take(REASON_MAX)
            log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
            return false
        }
        val data =
            mapOfNotNull(
                Tracker.FROM to issue.state,
                Tracker.TO to to,
                Tracker.BY to BY_HOST,
                Tracker.VERIFIED to "true",
                Tracker.APPROVED to "true",
                Tracker.APPROVED_BY to wait.approvedBy,
            )
        recordWaitEvent(wait, IssueMoved, data)
        log.log(LogLevel.INFO, DELIVERY, LogOutcome.COMPLETED, context, mapOf("to" to to))
        endWaiting(wait)
        return true
    }

    /**
     * The pull request delivery of an approved [wait] on [issue] (m2-25), from the facts the wait holds, so a delivery
     * after a restart builds the full body; its last step moves the issue to [to], and the wait ends. True when it was
     * delivered. Otherwise the pull request URL, once commented, is written to the wait, so a replay comments it once:
     * a step to try again keeps the wait approved for the next tick, a permanent failure clears the approval, so only a
     * new `loop approve` tries again.
     */
    private suspend fun deliverPullRequest(
        wait: Wait.Approval,
        issue: Issue,
        to: String,
    ): Boolean {
        val context = waitContext(wait)
        val facts =
            DeliveryFacts(
                issue = issue,
                identifier = wait.identifier,
                attemptId = wait.attemptId?.value,
                fingerprint = wait.fingerprint.orEmpty(),
                checks = wait.checks,
                judgmentCalls = wait.judgmentCalls,
                workspaceKey = NamespacedId.workspaceKey(wait.issueId, wait.identifier, effective.trackerId).value,
                commented = wait.pullRequest,
            )
        // m2-26: with `merge`, the delivery stops after the comment and the merge wait takes over, the issue unmoved.
        // m4-18: the code kind's, since only a code issue reaches a pull request delivery: no other kind may declare
        // one, and the wait pins the kind the issue was dispatched under.
        val merges =
            effective.config.kinds
                ?.code
                ?.delivery
                ?.pullRequest
                ?.merge != null
        val handoff =
            PullRequestDelivery
                .Handoff(
                    from = issue.state,
                    to = to,
                    event = mapOfNotNull(Tracker.APPROVED to "true", Tracker.APPROVED_BY to wait.approvedBy),
                ).takeUnless { merges }
        val record: suspend (EventDefinition, Map<String, String>) -> Unit = { definition, data -> recordWaitEvent(wait, definition, data) }
        val outcome = pullRequestDelivery(effective, record).deliver(facts, handoff)
        if (outcome is DeliveryOutcome.Delivered && merges) {
            startMergeWait(wait, outcome.pullRequest)
            return true
        }
        if (outcome is DeliveryOutcome.Delivered) {
            log.log(LogLevel.INFO, DELIVERY, LogOutcome.COMPLETED, context, mapOf("to" to to))
            endWaiting(wait)
            return true
        }
        val kept = outcome.commented?.let { wait.commented(it) } ?: wait
        when (outcome) {
            is DeliveryOutcome.Retry -> {
                val reason = redactor.redact("cannot deliver yet, ${outcome.step}: ${outcome.reason}").take(REASON_MAX)
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
                if (kept != wait) storeWait(kept, context)
            }
            is DeliveryOutcome.Failed -> {
                val reason = "cannot deliver the approved result, ${outcome.step}: ${outcome.reason}"
                log.log(LogLevel.WARN, DELIVERY, LogOutcome.FAILED, context, reason = reason)
                clearApproval(kept, "${outcome.step}: ${outcome.reason}")
            }
            is DeliveryOutcome.Delivered -> Unit
        }
        return false
    }

    /**
     * Replaces the approved [wait] by the `Waiting(merge)` of [pullRequest] (m2-26), in memory and in the store: every
     * key of the approval wait, its approval included, plus the pull request number, phase `check` and round 1, so a
     * restart resumes the merge without a second pull request or a lost approval. The issue is not moved.
     */
    private suspend fun startMergeWait(
        wait: Wait.Approval,
        pullRequest: loop.api.PullRequest,
    ) {
        storeWait(wait.merging(pullRequest.number, pullRequest.url, clock.now()), waitContext(wait))
        log.log(
            LogLevel.INFO,
            DELIVERY,
            LogOutcome.STARTED,
            waitContext(wait),
            mapOf("wait_reason" to WaitReason.Merge.wire, "pull_request" to pullRequest.number),
        )
    }

    /**
     * Goes through the merge waits of this tick's read in queue order ([MergeQueue]), the merge waits [syncApprovals]
     * just started included, at the back (m2-26, bl-172). A wait whose issue left the active states is settled at once
     * through [Merges.settleInactive], wherever it stands: a pull request merged at the stored head is delivered (the
     * move to `on_delivered.move_to`, a human touch when a human merged it), anything else ends the wait as a human
     * change, and the wait leaves the queue. The first other wait is the head, and only the head advances: whose check
     * runs, whose attempt runs or whose issue cannot be read holds the queue and does nothing this tick. An active head
     * first runs the CI poll (m3-15), which needs no slot, through [Merges.advance]; then its merge check and merge run
     * in one background job in [scope] through [Merges.run]. A job in phase `check` waits for a free agent slot and a
     * machine slot; a job in phase `merge` takes no agent slot, and a machine slot only for the auto merge gate's
     * reviewer (bl-173). The waits behind the head stay in phase `check` and do nothing, but for the early conflict check
     * of [Merges.checkEarly] against the base tip last fetched (bl-178). The running jobs are returned so `--once` waits
     * for them.
     */
    private suspend fun syncMerges(
        scope: CoroutineScope,
        candidates: List<Issue>,
    ): List<Deferred<Unit>> {
        val mergeWaits = MergeQueue.of(waits.current(WaitReason.Merge))
        settledEarly.clear()
        // bl-182: a merge wait that ended since the last tick, by any path, leaves nothing in memory.
        merges.retainOnly(mergeWaits.mapTo(mutableSetOf()) { it.issueId })
        if (mergeWaits.isEmpty()) return emptyList()
        val standing = standings(mergeWaits, candidates, skip = waits::isChecking)
        val launched = mutableListOf<Deferred<Unit>>()
        var headFound = false
        for (stored in mergeWaits) {
            if (waits.isChecking(stored.issueId) || stored.issueId in running) {
                headFound = true
                continue
            }
            val candidate =
                when (val found = standing(stored.issueId)) {
                    Standing.Unread -> {
                        headFound = true
                        continue
                    }
                    is Standing.Active -> found.issue
                    is Standing.Gone -> {
                        merges.settleInactive(stored, found.issue)
                        continue
                    }
                }
            if (headFound) {
                // bl-178: a wait behind the head only gets the early conflict check, against the base tip fetched last.
                if (merges.checkEarly(stored, candidate)) settledEarly += stored.issueId
                continue
            }
            headFound = true
            // m3-15: a CI wait is polled here, with no slot; once CI allows the merge, the merge phase starts below.
            val wait = merges.advance(stored, candidate) { settledEarly += stored.issueId } ?: continue
            // bl-173: only phase check builds in the workspace and holds an agent slot; phase merge holds none.
            val checks = wait.phase == MergePhase.Check
            // bl-175: while the queue is paused after a CI timeout, no merge check starts.
            if (checks && queuePause.paused != null) continue
            // bl-86: step one of the slot plan, for the merge check work that holds a slot, before the parked read and
            // the due retries of [dispatch].
            if (checks && !SlotPlan.mergeCheckFits(effective.config.agent, slotUse())) continue
            // m3-16: then a machine slot, like an attempt, for the check and for a merge phase that runs the merge risk
            // reviewer, the auto merge gate's; with none free the work waits for a later tick.
            val slot = if (checks || wait.approvedBy == AutoMerge.AUTO) machineGate.claim(waitContext(wait)) else null
            if (slot == SlotClaim.Refused) continue
            waits.checkStarted(wait.issueId, holdsSlot = checks)
            // bl-180: the slot and the running check are taken before the job, so the job starts atomically: its body
            // runs even on a scope cancelled meanwhile, stops at once there, and its finally releases both.
            @OptIn(DelicateCoroutinesApi::class)
            val job =
                scope.async(start = CoroutineStart.ATOMIC) {
                    try {
                        ensureActive()
                        // The wait itself says what follows: the tick reads it again, so what the run left is not needed here.
                        merges.run(wait, candidate)
                        Unit
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.log(LogLevel.ERROR, DELIVERY, LogOutcome.FAILED, waitContext(wait), reason = redactor.redact(e.describe()))
                    } finally {
                        if (slot is SlotClaim.Held) withContext(NonCancellable) { machineGate.release(slot.holder, waitContext(wait)) }
                        waits.checkEnded(wait.issueId)
                        attemptEnded.trySend(Unit)
                    }
                }
            launched += job
        }
        return launched
    }

    /**
     * The rerun of the checks of a merge check (m2-26), the [Recheck] of [merges]: every check and holdout of [workflow]
     * in [workspace], outside any attempt, with the same env policy and rerun rule as a verification, `diff_policy`
     * based on the fetched [base] and every event marked [MergeCheck.MARK]. With [diffPolicyOnly], only the
     * `diff_policy` checks run, and nothing when the kind has none (bl-32).
     */
    private suspend fun recheckMerge(
        wait: Wait.Merge,
        issue: Issue,
        workflow: WorkflowFile,
        workspace: Path,
        base: String,
        diffPolicyOnly: Boolean,
    ): RecheckOutcome {
        val context = waitContext(wait)
        val verification =
            Verification(
                wait.issueId,
                wait.identifier,
                issue.description,
                wait.attemptId,
                workflow,
                // m4-18: the merge check is code only until each kind takes it up. Only a code issue reaches it: no
                // other kind may deliver a pull request, and the approval wait pins the kind it was dispatched under.
                workflow.config.kinds?.let { IssueKind(KindsConfig.CODE, it.code) },
                workspace,
                checkRunnerFor(workflow),
                { definition, data -> recordWaitEvent(wait, definition, data + (MergeCheck.MARK to "true")) },
                { context },
                diffBase = base,
                diffPolicyOnly = diffPolicyOnly,
                host = verificationHost,
            )
        if (diffPolicyOnly) {
            verification.prepare()
            if (!verification.hasChecks) return RecheckOutcome.Skipped
        }
        // bl-188: the full rerun is a build and waits for the build gate; the diff policy pass alone builds nothing.
        val passed = if (diffPolicyOnly) verification.verify() else buildGate.withBuild(context) { verification.verify() }
        if (passed) return RecheckOutcome.Passed
        return RecheckOutcome.Failed(verification.unverified, verification.findings?.text)
    }

    /**
     * bl-28: writes `refs/loop/base/[base]` at [sha], a base Loop fetched, in [workspace] when [workflow] clones and it
     * is a clone of the project, with the base keep ref in the project; why it failed, null otherwise.
     */
    private suspend fun writeCloneBase(
        workflow: WorkflowFile,
        workspace: Path,
        base: String,
        sha: String,
    ): String? {
        val clones = workspacesFor(workflow).clones ?: return null
        return try {
            withContext(io) {
                if (clones.state(workspace) == CloneState.Clone) {
                    clones.writeBase(workspace, WorkspaceKey(workspace.fileName.toString()), base, sha)
                }
            }
            null
        } catch (e: IOException) {
            "cannot write the base $base at $sha in the clone $workspace: ${e.message}"
        }
    }

    /** The executor of [workflow]'s pull request delivery, [NoDelivery] when this host has none. */
    private fun executorFor(workflow: WorkflowFile): DeliveryExecutor {
        val executor = deliveryFor(workflow) ?: NoDelivery
        // bl-28: a clone's commit is imported into the project before every push of it.
        val clones = workspacesFor(workflow).clones ?: return executor
        return CloneDelivery(executor, clones) { key -> workspacesFor(workflow).locate(key) }
    }

    /** The result of a delivery call on [io], an exception it throws turned into its failure. */
    private suspend fun <T> deliveryStep(block: suspend () -> Result<T>): Result<T> =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Writes [wait] back without its approval (m2-25): only a new `loop approve` delivers it now. The human is told,
     * `delivery_failed` for [reason] (m4-30).
     */
    private suspend fun clearApproval(
        wait: Wait.Approval,
        reason: String,
    ) {
        notices.waiting(wait.unapproved(), HumanEvent.DeliveryFailed, reason) { storeWait(it, waitContext(wait)) }
    }

    /**
     * Writes [wait] to the store, its issue then waiting in memory for the wait's reason, written or not; a failure is
     * logged. True when it was written.
     */
    private suspend fun storeWait(
        wait: Wait,
        context: LogContext,
    ): Boolean {
        val failure = waits.save(wait).exceptionOrNull()
        failure?.let {
            log.log(
                LogLevel.WARN,
                WAITING,
                LogOutcome.FAILED,
                context,
                reason = redactor.redact(it.describe()).take(REASON_MAX),
            )
        }
        return failure == null
    }

    /**
     * The pull request delivery of [workflow] (m2-25), recording through [record]. A host with no executor for it
     * gives a delivery whose every push fails for good, so the result waits for a human and nothing is lost. bl-28:
     * through [executorFor], so with clone workspaces every push, the delivery retry's too, imports its commit first.
     */
    private fun pullRequestDelivery(
        workflow: WorkflowFile,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
    ): PullRequestDelivery = PullRequestDelivery(executorFor(workflow), tracker, redactor, clock, io, record)

    /** Records a [definition] event for the issue and attempt of [wait], outside any attempt; a sink failure is logged. */
    private suspend fun recordWaitEvent(
        wait: Wait,
        definition: EventDefinition,
        data: Map<String, String>,
    ) {
        val event =
            definition.event(
                ts = clock.now(),
                projectId = projectId,
                runId = runId,
                issueId = wait.issueId,
                issueIdentifier = wait.identifier,
                attemptId = wait.attemptId,
                data = data,
            )
        log.guardSink(sinkName, waitContext(wait)) { sink.record(event).getOrThrow() }
    }

    /** The issues whose wait named a kind the workflow no longer holds, warned once each for the life of this run (m4-18). */
    private val kindGoneWarned = mutableSetOf<String>()

    /** Logs at warn, once per issue, that [wait] keeps waiting because its kind [name] left the workflow (m4-18). */
    private fun warnKindGone(
        wait: Wait,
        name: String,
    ) {
        if (!kindGoneWarned.add(wait.issueId)) return
        val reason = "the kind $name the issue was dispatched under is no longer in the workflow, it keeps waiting until it is back"
        log.log(LogLevel.WARN, WAITING, LogOutcome.FAILED, waitContext(wait), mapOf("wait_reason" to wait.reason.wire), reason)
    }

    /** Resumes one stored wait, see [restoreWaiting]; the rerun of its checks, null when nothing runs. */
    private suspend fun resumeWait(
        scope: CoroutineScope,
        wait: Wait,
        candidate: Issue?,
    ): Deferred<Unit>? {
        if (wait !is Wait.Verify) {
            waits.hold(wait)
            return null
        }
        val to = wait.to
        val workflow = effective
        val target = candidate?.let { RetryTarget.of(it, workflow) }
        val located = workspacesFor(workflow).locate(NamespacedId.workspaceKey(wait.issueId, wait.identifier, workflow.trackerId))
        val dropped =
            when {
                target == null || target == RetryTarget.Terminal || target == RetryTarget.NotActive -> "the issue is no longer active"
                workflow.config.kinds == null -> "the workflow has no kinds"
                to.isNullOrBlank() -> "the wait has no target state"
                located.isFailure -> "cannot locate the workspace: ${located.exceptionOrNull()?.describe()}"
                located.getOrNull() == null -> "the workspace is gone"
                else -> null
            }
        if (dropped != null) {
            endWaiting(wait)
            log.log(LogLevel.INFO, WAITING, LogOutcome.RELEASED, waitContext(wait), mapOf("wait_reason" to wait.reason.wire), dropped)
            return null
        }
        // m4-18: the checks of the kind the issue was dispatched under; a wait whose kind left the workflow keeps waiting.
        val kind =
            when (val pinned = KindLookup.pinned(workflow, wait.kind, candidate)) {
                is PinnedKind.Found -> pinned.kind
                is PinnedKind.Gone -> {
                    waits.hold(wait)
                    warnKindGone(wait, pinned.name)
                    return null
                }
            }
        val context = waitContext(wait)
        val record: suspend (EventDefinition, Map<String, String>) -> Unit = { definition, data ->
            val event =
                definition.event(
                    ts = clock.now(),
                    projectId = projectId,
                    runId = runId,
                    issueId = wait.issueId,
                    issueIdentifier = wait.identifier,
                    attemptId = wait.attemptId,
                    data = data,
                )
            log.guardSink(sinkName, context) { sink.record(event).getOrThrow() }
        }
        val verification =
            Verification(
                wait.issueId,
                wait.identifier,
                candidate?.description,
                wait.attemptId,
                workflow,
                kind,
                checkNotNull(located.getOrNull()),
                checkRunnerFor(workflow),
                record,
                { context },
                host = verificationHost,
            )
        waits.hold(wait)
        log.log(LogLevel.INFO, WAITING, LogOutcome.RESUMED, context, mapOf("wait_reason" to wait.reason.wire))
        return scope.async {
            try {
                waitingFor(wait, write = false, endOnCancel = { false }) {
                    record(ResultSubmitted, mapOf(Results.TO to checkNotNull(to), Results.RESTORED to "true"))
                    val passed = buildGate.withBuild(context) { verification.verify() }
                    val failure = if (passed) verification.deliver(to) else null
                    // A refuted rerun hands its findings to the issue's next attempt, like an attempt's (m2-06).
                    verification.findings?.let { verifyFindings[wait.issueId] = it.text }
                    // An unverified rerun parks the issue before its wait ends, like an attempt's (m2-08).
                    verification.unverified?.let { reason ->
                        retries.parkNow(checkNotNull(candidate), wait.attemptId, ParkReason.Unverified, mapOf(IssueParked.ERROR to reason))
                    }
                    failure?.let { reason ->
                        val data =
                            mapOf(
                                ToolRequestEvent.REQUEST to ToolRequestEvent.MOVE,
                                Tracker.TO to to,
                                Tracker.REASON to redactor.redact(reason).take(REASON_MAX),
                            )
                        record(ToolRequestFailed, data)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(LogLevel.ERROR, WAITING, LogOutcome.FAILED, context, reason = e.describe())
            }
        }
    }

    /** Records a `hook_finished` event for each hook run (plan R3) and logs it (spec 9.4, plan R2). */
    private inner class HookRecorder(
        private val record: suspend (EventDefinition, Map<String, String>) -> Unit,
        private val context: () -> LogContext,
    ) : HookListener {
        override suspend fun started(
            hook: HookName,
            script: String,
        ) {
            log.log(LogLevel.INFO, "hook", LogOutcome.STARTED, context(), mapOf("hook" to hook.id))
        }

        override suspend fun finished(
            script: String,
            result: HookResult,
        ) {
            log(recordRun(script, result))
        }

        /** One `hook_finished` for a run that ended or was stopped (bl-04), redacted; returns the redacted result. */
        private suspend fun recordRun(
            script: String,
            result: HookResult,
        ): HookResult {
            val (command, redacted) = redact(script, result)
            val data =
                mapOfNotNull(
                    HookFinished.HOOK to redacted.hook.id,
                    HookFinished.COMMAND to command.take(HOOK_COMMAND_MAX),
                    HookFinished.EXIT_CODE to result.exitCode?.toString(),
                    HookFinished.TIMED_OUT to result.timedOut.toString(),
                    HookFinished.STOPPED to if (result.stopped) "true" else null,
                    HookFinished.DURATION_MS to result.durationMs.toString(),
                    HookFinished.OUTPUT to redacted.output,
                    HookFinished.OUTPUT_TRUNCATED to if (redacted.outputTruncated) "true" else null,
                )
            record(HookFinished, data)
            return redacted
        }

        /**
         * The one redaction of a hook run (m1-11): its command, before the cut to
         * [HOOK_COMMAND_MAX], and its output, so the event and the log line both use it.
         */
        private fun redact(
            script: String,
            result: HookResult,
        ): Pair<String, HookResult> = redactor.redact(script) to result.copy(output = redactor.redact(result.output))

        override suspend fun stopped(
            script: String,
            result: HookResult,
        ) {
            recordRun(script, result)
            val hook = result.hook
            log.log(LogLevel.WARN, "hook", LogOutcome.FAILED, context(), mapOf("hook" to hook.id), "hook ${hook.id} was stopped")
        }

        private fun log(result: HookResult) {
            val hook = result.hook.id
            val duration = result.durationMs
            if (result.stopFailed) {
                log.log(LogLevel.WARN, "hook", LogOutcome.FAILED, context(), mapOf("hook" to hook), "cannot stop the hook process group")
            }
            if (result.ok) {
                log.log(LogLevel.INFO, "hook", LogOutcome.COMPLETED, context(), mapOf("hook" to hook, "duration_ms" to duration))
                return
            }
            // after_create and before_run fail the attempt; after_run and before_remove are only reported.
            val fatal = result.hook == HookName.AFTER_CREATE || result.hook == HookName.BEFORE_RUN
            val level = if (fatal) LogLevel.ERROR else LogLevel.WARN
            if (result.timedOut) {
                val fields = mapOf("hook" to hook, "timed_out" to true, "duration_ms" to duration)
                log.log(level, "hook", LogOutcome.FAILED, context(), fields, "timed out after ${result.timeoutMs} ms")
            } else {
                val fields = mapOf("hook" to hook, "exit_code" to result.exitCode, "duration_ms" to duration)
                val lastLine =
                    result.output
                        .lines()
                        .lastOrNull { it.isNotBlank() }
                        ?.trim()
                log.log(level, "hook", LogOutcome.FAILED, context(), fields, lastLine ?: result.reason)
            }
        }
    }

    /** What the runner said about turn [number] of at most [maxTurns]; [finished] is false until a turn result arrives. */
    private class TurnResult(
        val number: Int,
        val maxTurns: Int,
    ) {
        var threadId: String? = null
        var turnId: TurnId? = null

        /** The data of the turn events: its number, the limit and the ids the runner reported so far. */
        fun data(): Map<String, String> =
            mapOfNotNull(
                TurnEvent.TURN to number.toString(),
                TurnEvent.MAX_TURNS to maxTurns.toString(),
                TurnEvent.THREAD_ID to threadId,
                TurnEvent.TURN_ID to turnId?.value,
                TurnEvent.CAPPED to if (capped) "true" else null,
                TurnEvent.INTERRUPTED to interrupted?.let { SessionRotated.HARD_LIMIT },
                TurnEvent.WAKE to if (wake) "true" else null,
            )

        var launched = false
        var finished = false

        /** True once the runner said this is a wake turn (m4-03): it counts against `agent.max_wake_turns` only. */
        var wake = false

        /** True once the attempt went past a spend budget during this turn (m2-20); the turn is then cut. */
        var capped = false

        /**
         * The occupancy that reached `context.hard_limit` of the session's window during this turn (m3-12), null below
         * it. The turn is then cut at once and the session rotated; the turn is not a failed one.
         */
        var interrupted: OccupancyMeter.Crossing? = null
        var exitCode: Int? = null
        var failure: String? = null

        /** Set when the runner failed the turn with the category `runner_unavailable` (bl-39). */
        var unavailable: RunnerUnavailable? = null
    }

    private companion object {
        const val CONFIG_VERSION = ConfigReloaded.CONFIG_VERSION
        const val WORKFLOW_RELOAD = "workflow_reload"

        /** The rejected version of a file that could not be read; never a hash. */
        const val UNREADABLE = "unreadable"

        /** The longest `diff` a reload record keeps, in characters. */
        const val DIFF_MAX = 16_000
        const val RUNNER_OUTPUT_MAX = 2000
        const val CANCELED = "canceled"
        const val LEDGER = "ledger"
        const val CONTEXT_WINDOW = "context_window"
        const val NO_AVAILABLE_SLOTS = "no_available_slots"

        /** The preflight error of a malformed machine config (m3-16), like a malformed `WORKFLOW.md` key. */
        const val INVALID_CONFIG = "invalid_config"

        /** bl-39: why a candidate or a due retry waits for the probe of the runner hold, and the operator log event. */
        const val RUNNER_UNAVAILABLE = "runner_unavailable"
        const val RUNNER_HOLD = "runner_hold"
        const val BUDGET = "budget"
        const val STALLED = "stalled"
        const val RECONCILE = "reconcile"
        const val STARTUP_CLEANUP = "startup_cleanup"
        const val CAP_GRACE_MS = 2_000L

        /** How long an interrupted turn (m3-12) has to report its usage and end, counted from the interrupt request (bl-136). */
        const val INTERRUPT_GRACE_MS = 10_000L

        const val HOOK_COMMAND_MAX = 200
        const val CANCELED_AFTER_RUN_MAX_MS = 5_000L

        /** The result file reasons (m2-15). */
        const val STALE_RESULT = "left by an earlier turn"
        const val INVALID_RESULT = "invalid_result"
        const val RESULT_BLOCKED = "result_blocked"
        const val RESULT_FAILED = "result_failed"
        const val RESULT_FILE_SOURCE = Results.FILE

        /** The `source` of a result submitted with the `submit_result` MCP tool (m2-16). */
        const val RESULT_MCP_SOURCE = Results.MCP
        const val SUBMIT_RESULT_TOOL = "submit_result"
        const val RESULT_REQUEST = ToolRequestEvent.RESULT

        /** Where a `done` result file moves the issue once verified: the handoff state of story 14. */
        const val HANDOFF_STATE = "review"

        /** The operator log event of a wait (m2-04). */
        const val WAITING = "waiting"

        /** bl-224: the operator log event of a reaction read at dispatch, as the reactions log theirs. */
        private const val REACTIONS = "reactions"

        /** The operator log event of a delivery: an approval wait started, an approved result delivered (m2-24). */
        const val DELIVERY = "delivery"

        /** The operator log event of a pause of the delivery queue and its resume (bl-175). */
        const val DELIVERY_QUEUE = "delivery_queue"

        /** bl-188: a build that waits for room in the build gate. */
        const val BUILD_GATE = "build_gate"

        /** The values of the verdict that ends each verification (m2-05). */
        const val VERIFIED = Verification.VERIFIED
        const val REFUTED = Verification.REFUTED
        const val UNVERIFIED = Verification.UNVERIFIED

        /** The reason of an attempt that parks after a rotation past `context.max_rotations` (m3-11). */
        const val MAX_ROTATIONS = "max_rotations"

        /** The reason of an attempt that parks after two rotations in a row without progress (m3-12). */
        const val SPLIT_ISSUE = "split_issue"

        /** The log event of the frozen acceptance command lists (m2-09). */
        const val ACCEPTANCE_COMMANDS = "acceptance_commands"

        /** The one tracker whose issue text Loop trusts to hold commands, until a trust check exists (m2-09). */
        const val FILES_TRACKER = "files"

        /** The operator log event of the trimmed tool replies kept in the Attempt file (m3-14). */
        const val TOOL_OUTPUT = "tool_output"

        /** The `native_ref` key of an issue's web address, set by the GitHub tracker (m3-14 reads it for a cut description). */
        const val ISSUE_URL = "url"

        const val NEEDS_INFO = "needs_info"
        const val NOT_READY = IssueCommented.NOT_READY
        const val NEEDS_INFO_LABEL = "needs-info"
        const val READINESS = "readiness"

        /** The author of the comments Loop writes itself, not on an agent's request. */
        const val LOOP_AUTHOR = "loop"

        /** The comment on an issue the readiness check bounced (m2-11), naming the [missing] sections. */
        fun notReadyComment(missing: List<String>): String {
            val names = missing.map { "`## $it`" }
            val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
            val what = if (names.size == 1) "a non empty $list section" else "non empty $list sections"
            return "Loop did not dispatch this issue because it is not ready: its description needs $what. " +
                "Add ${if (names.size == 1) "it" else "them"} and Loop picks the issue up on a later poll."
        }

        const val BY_HOST = Tracker.BY_HOST
        const val UNKNOWN_REQUEST = ToolRequestEvent.UNKNOWN
        const val REASON_MAX = 200

        /** The hook variable of the base commit fetched before an attempt (bl-59). */
        const val LOOP_BASE_REF = "LOOP_BASE_REF"

        /** The longest move target a failed request notice names (m2-07). */
        const val NOTICE_TARGET_MAX = 64
        const val SHORT_ID = 8

        /** Why a move or a result queued after an `ask` of the same drain fails (m4-31). */
        const val WAITS_FOR_ANSWER = "the issue waits for the answer to the agent's question"

        /** The guidance of turns 2..n, the same for every runner (m1-06 decision P1); the tags are filled per turn. */
        const val CONTINUATION_GUIDANCE =
            "Continue working on <identifier>. This is turn <n> of <max>. The issue is still in <state>. " +
                "Build on your previous turns, do not start over. When the work is done, move the issue with " +
                "loop tool move review. If you are blocked, comment and move it to needs_info."

        /**
         * The guidance of turn [turn]; after a refutation in the turn before, its [findings] follow in a paragraph
         * (m2-06), then the notices of the requests the turn before queued and Loop could not apply, one per line (m2-07),
         * trimmed to [toolOutputTokens] (m3-14, see [noticesText]).
         */
        fun continuationGuidance(
            issue: Issue,
            turn: Int,
            maxTurns: Int,
            findings: VerifyFindings? = null,
            failures: List<String> = emptyList(),
            toolOutputTokens: Int = ContextConfig().toolOutputTokens,
        ): String =
            CONTINUATION_GUIDANCE
                .replace("<identifier>", issue.identifier)
                .replace("<n>", turn.toString())
                .replace("<max>", maxTurns.toString())
                .replace("<state>", issue.state) +
                findings?.let { "\n\n${it.text}" }.orEmpty() +
                noticesText(failures, toolOutputTokens)

        /** Where the full text of the notices trimmed by [noticesText] is (m3-14). */
        const val NOTICES_FULL_AT = "Loop records each request it could not apply, with its reason, as a tool_request_failed event."

        /**
         * The paragraph of the failed request [notices] (m2-07), one per line after a blank line, empty without one.
         * Since m3-14 it is trimmed to [toolOutputTokens] of the estimate like any tool output: the first notices that
         * fit stay whole, and a note says how much is left out; each notice stays whole in its `tool_request_failed` event.
         */
        fun noticesText(
            notices: List<String>,
            toolOutputTokens: Int = ContextConfig().toolOutputTokens,
        ): String {
            if (notices.isEmpty()) return ""
            val text = ToolOutputTrim.trim(notices.joinToString("\n"), toolOutputTokens, TokenCounters.estimate, NOTICES_FULL_AT)
            return "\n\n$text"
        }

        /**
         * What the agent reads about a request Loop could not apply (m2-07), from the redacted data of its
         * `tool_request_failed` event: the [failedRequestName] and the reason on one line, cut to [REASON_MAX]. The
         * reason ends with a period unless it already ends with a period, a question mark or an exclamation mark.
         */
        fun failedRequestNotice(data: Map<String, String>): String {
            val reason =
                data[Tracker.REASON]
                    .orEmpty()
                    .oneLine()
                    .take(REASON_MAX)
                    .trimEnd()
            val ended = if (reason.endsWith('.') || reason.endsWith('?') || reason.endsWith('!')) reason else "$reason."
            return "Loop could not apply your last request (${failedRequestName(data)}): $ended Run it again if it is still needed."
        }

        /**
         * The request of a failed request notice (m2-07): `move to <state>` for a queued move or a `done` result, with
         * the state on one line and cut to [NOTICE_TARGET_MAX], since the agent chose it, `submit_result` for a submitted
         * result that never got to a move (m2-16), else `comment` or `unknown request`.
         */
        fun failedRequestName(data: Map<String, String>): String =
            when (data[ToolRequestEvent.REQUEST]) {
                RESULT_REQUEST if data[Tracker.TO] == null -> SUBMIT_RESULT_TOOL
                ToolRequestEvent.MOVE, RESULT_REQUEST -> "move to ${data[Tracker.TO].orEmpty().oneLine().take(NOTICE_TARGET_MAX).trimEnd()}"
                ToolRequestEvent.COMMENT -> "comment"
                ToolRequestEvent.FOLLOWUP -> "file_followup"
                else -> "unknown request"
            }

        /** The text with every run of whitespace, line breaks and control characters replaced by one space, trimmed. */
        private fun String.oneLine(): String = replace(LINE_BREAKS, " ").trim()

        private val LINE_BREAKS = Regex("[\\s\\p{Cntrl}\\u0085\\u2028\\u2029]+")

        fun String.normalized(): String = trim().lowercase()

        /** `300 s` for whole seconds, else `1500 ms`. */
        fun describeMs(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"

        fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"

        /** Mode 0700 for the attempt directory and its requests folder (m1-23). */
        fun ownerOnly() = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    }
}

/** The data of a `rate_limit` event (m3-21): each key of [event] the runner gave, none for what it did not say. */
private fun rateLimitData(event: RunnerEvent.RateLimit): Map<String, String> =
    mapOfNotNull(
        RateLimit.LIMIT_ID to event.limitId,
        RateLimit.LIMIT_NAME to event.limitName,
        RateLimit.PRIMARY_USED_PERCENT to event.primary?.usedPercent?.toString(),
        RateLimit.PRIMARY_WINDOW_MINUTES to event.primary?.windowMinutes?.toString(),
        RateLimit.PRIMARY_RESETS_AT to event.primary?.resetsAt?.toString(),
        RateLimit.SECONDARY_USED_PERCENT to event.secondary?.usedPercent?.toString(),
        RateLimit.SECONDARY_WINDOW_MINUTES to event.secondary?.windowMinutes?.toString(),
        RateLimit.SECONDARY_RESETS_AT to event.secondary?.resetsAt?.toString(),
        RateLimit.REACHED to event.reached,
    )

/** The executor of a host that cannot deliver a pull request (m2-25): every step is refused for good. */
private object NoDelivery : DeliveryExecutor {
    override val repository: String = ""

    private fun <T> refused(): Result<T> = Result.failure(loop.api.DeliveryError.Rejected("this Loop cannot deliver a pull request"))

    override suspend fun push(
        sha: String,
        branch: String,
    ): Result<Unit> = refused()

    override suspend fun findPullRequest(branch: String): Result<loop.api.PullRequest?> = refused()

    override suspend fun openPullRequest(
        branch: String,
        title: String,
        body: String,
    ): Result<loop.api.PullRequest> = refused()

    override suspend fun updatePullRequest(
        number: Long,
        title: String,
        body: String,
    ): Result<loop.api.PullRequest> = refused()
}

/** True for the events that end a turn's flow. */
private fun RunnerEvent.isTerminal(): Boolean =
    this is RunnerEvent.TurnCompleted || this is RunnerEvent.TurnFailed || this is RunnerEvent.TurnInputRequired
