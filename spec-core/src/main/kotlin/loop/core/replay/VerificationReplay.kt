package loop.core.replay

import kotlinx.coroutines.Dispatchers
import loop.api.AttemptId
import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.Issue
import loop.api.IssueComment
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerAdapter
import loop.api.TrackerError
import loop.core.delivery.PullRequestDelivery
import loop.core.events.CheckEvent
import loop.core.events.CheckFinished
import loop.core.events.EventDefinition
import loop.core.events.ReplayVerdict
import loop.core.events.Verdict
import loop.core.kinds.IssueKind
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.verify.CheckRunner
import loop.core.verify.EvidenceWriter
import loop.core.verify.Fingerprinter
import loop.core.verify.Verification
import loop.core.verify.VerificationHost
import loop.core.verify.WorkspaceFingerprint
import loop.core.waiting.Wait
import loop.core.workflow.WorkflowFile
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** The attempt `loop replay --verify` replays (m4-41): its issue, as the tracker holds it now, and the current workflow. */
data class ReplayTarget(
    val attemptId: AttemptId,
    val issueId: String,
    val identifier: String,
    val description: String?,
    val workflow: WorkflowFile,
    /** The issue's kind in the current workflow, whose checks run; null in Symphony mode. */
    val kind: IssueKind?,
)

/**
 * One check run of a replay, as its `check_finished` event holds it. A hidden check (m2-14) holds only its [result],
 * `passed`, `failed` or `could_not_run`, and its [line] names only its number.
 */
data class ReplayedCheck(
    val index: Int,
    val command: String,
    val holdout: Boolean,
    val rerun: Boolean,
    val exitCode: Int?,
    val durationMs: Long?,
    val timedOut: Boolean,
    val result: String?,
    val reason: String?,
) {
    val passed: Boolean get() = if (holdout) result == PASSED else exitCode == 0 && !timedOut && reason == null

    /** The run on one line, such as `check 0 rerun failed with exit code 1 in 12 ms: ./gradlew test`. */
    fun line(): String {
        val name = (if (holdout) "hidden check $index" else "check $index") + if (rerun) " rerun" else ""
        if (holdout) return "$name ${if (result == CheckEvent.COULD_NOT_RUN) "could not run" else result ?: "failed"}"
        val took = durationMs?.let { " in $it ms" }.orEmpty()
        val outcome =
            when {
                passed -> "passed$took"
                reason != null -> "could not run: $reason"
                timedOut -> "timed out$took"
                exitCode == null -> "ended without an exit code$took"
                else -> "failed with exit code $exitCode$took"
            }
        return "$name $outcome: $command"
    }

    private companion object {
        const val PASSED = "passed"
    }
}

/** What a replay found: the new [verdict] on the state at [fingerprint], why it is `unverified`, and each check run. */
data class ReplayedVerdict(
    val verdict: String,
    val fingerprint: String,
    val reason: String?,
    val checks: List<ReplayedCheck>,
)

/**
 * Runs the verification of `loop.core.verify` again on a [ReplaySite] (m4-41), outside any run: the checks of the current
 * workflow for the issue's kind, through the same check runner as `loop run`. It writes nothing anywhere: the events the
 * verification records are kept in memory and only read for the result, no evidence is saved, no base is fetched, the
 * frozen acceptance command list is only read through [frozen] (null standing for the list the issue holds now), and the
 * result is never delivered, so the tracker, the issue and the attempt's records stay as they were.
 */
class VerificationReplay(
    private val projectId: ProjectId,
    private val loopHome: Path,
    private val redactor: SecretRedactor,
    private val checkRunner: CheckRunner,
    private val frozen: suspend (String) -> Result<FrozenCommands?>,
    private val fingerprinter: Fingerprinter = WorkspaceFingerprint,
    private val clock: Clock = Clock.System,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** Verifies [site] again for [target], the base of a `diff_policy` check pinned to the one of [recorded]. */
    suspend fun run(
        target: ReplayTarget,
        recorded: RecordedVerdict,
        site: ReplaySite,
    ): ReplayedVerdict {
        val recordedEvents = mutableListOf<Pair<EventDefinition, Map<String, String>>>()
        val host = ReplayHost(recorded.runId ?: REPLAY_RUN)
        val verification =
            Verification(
                issueId = target.issueId,
                identifier = target.identifier,
                description = target.description,
                // No attempt: the attempt's evidence folder gets nothing.
                attemptId = null,
                workflow = target.workflow,
                kind = target.kind,
                workspace = site.dir,
                checkRunner = checkRunner,
                record = { definition, data -> recordedEvents += definition to data },
                logContext = { LogContext(projectId = projectId, issueId = target.issueId, issueIdentifier = target.identifier) },
                diffBase = recorded.diffBase,
                host = host,
            )
        verification.verify()
        val verdict = recordedEvents.last { it.first == Verdict }.second
        val checks =
            recordedEvents
                .filter { (definition, data) -> definition == CheckFinished && data[CheckEvent.CANCELED] == null }
                .map { (_, data) ->
                    ReplayedCheck(
                        index = data[CheckEvent.CHECK]?.toIntOrNull() ?: 0,
                        command = data[CheckEvent.COMMAND].orEmpty(),
                        holdout = data[CheckEvent.HOLDOUT] == "true",
                        rerun = data[CheckEvent.RERUN] == "true",
                        exitCode = data[CheckEvent.EXIT_CODE]?.toIntOrNull(),
                        durationMs = data[CheckEvent.DURATION_MS]?.toLongOrNull(),
                        timedOut = data[CheckEvent.TIMED_OUT] == "true",
                        result = data[CheckEvent.RESULT],
                        reason = data[CheckEvent.REASON],
                    )
                }
        return ReplayedVerdict(
            verdict = verdict[Verdict.VERDICT].orEmpty(),
            fingerprint = verdict[Verdict.FINGERPRINT] ?: Verification.UNKNOWN_FINGERPRINT,
            reason = verdict[Verdict.REASON],
            checks = checks,
        )
    }

    /** A host that reads and never writes: no tracker write, no evidence, no base fetch, no delivery, no wait. */
    private inner class ReplayHost(
        override val runId: RunId,
    ) : VerificationHost {
        override val projectId = this@VerificationReplay.projectId
        override val loopHome = this@VerificationReplay.loopHome
        override val tracker: TrackerAdapter = NoTracker
        override val log = KeyValueLog(out = {}, clock = this@VerificationReplay.clock, redactor = this@VerificationReplay.redactor)
        override val clock = this@VerificationReplay.clock
        override val redactor = this@VerificationReplay.redactor
        override val io = this@VerificationReplay.io
        override val fingerprinter = this@VerificationReplay.fingerprinter
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
        ): Result<FrozenCommands> =
            frozen(issueId).map { stored -> stored ?: FrozenCommands(projectId, issueId, identifier, digest, commands.size, clock.now()) }

        override suspend fun fetchBase(workflow: WorkflowFile): Result<String> = Result.failure(IllegalStateException(NO_WRITE))

        override fun pullRequestDelivery(
            workflow: WorkflowFile,
            record: suspend (EventDefinition, Map<String, String>) -> Unit,
        ): PullRequestDelivery = throw IllegalStateException(NO_WRITE)

        override fun awaitsApproval(issueId: String): Boolean = false

        override suspend fun startApprovalWait(
            wait: Wait.Approval,
            reason: String,
        ) = Unit
    }

    /** The tracker of a replay: every call fails, a replay reads and writes nothing there. */
    private object NoTracker : TrackerAdapter {
        override val definedStates: List<String> = emptyList()

        override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> = refused()

        override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> = refused()

        override suspend fun moveIssue(
            issueId: String,
            toState: String,
        ): Result<Unit> = refused()

        override suspend fun addComment(
            issueId: String,
            comment: IssueComment,
        ): Result<Unit> = refused()

        private fun <T> refused(): Result<T> = Result.failure(TrackerError.Unsupported("from a replay"))
    }

    companion object {
        /** The run id a check of a replay sees when the recorded verdict names no run. */
        private val REPLAY_RUN = RunId("replay")

        private const val NO_WRITE = "a replay fetches and delivers nothing"

        /**
         * The `replay_verdict` event of [replayed]: issue level, with no attempt id or run id, so the events of the
         * replayed attempt stay as they were; the attempt is named in its data.
         */
        fun event(
            ts: Instant,
            projectId: ProjectId,
            target: ReplayTarget,
            recorded: RecordedVerdict,
            site: ReplaySite,
            replayed: ReplayedVerdict,
        ): RecordedEvent =
            RecordedEvent(
                ts = ts,
                projectId = projectId,
                kind = ReplayVerdict.name,
                issueId = target.issueId,
                issueIdentifier = target.identifier,
                data =
                    buildMap {
                        put(ReplayVerdict.ATTEMPT, target.attemptId.value)
                        put(ReplayVerdict.VERDICT, replayed.verdict)
                        put(ReplayVerdict.RECORDED_VERDICT, recorded.verdict)
                        put(ReplayVerdict.FINGERPRINT, replayed.fingerprint)
                        put(ReplayVerdict.RECORDED_FINGERPRINT, recorded.fingerprint)
                        put(
                            ReplayVerdict.SITE,
                            when (site) {
                                is ReplaySite.WorkspaceCopy -> ReplayVerdict.WORKSPACE_COPY
                                is ReplaySite.Worktree -> ReplayVerdict.WORKTREE
                            },
                        )
                        put(ReplayVerdict.CHECKS, replayed.checks.count { !it.rerun }.toString())
                        replayed.reason?.let { put(ReplayVerdict.REASON, it) }
                    },
            )
    }
}
