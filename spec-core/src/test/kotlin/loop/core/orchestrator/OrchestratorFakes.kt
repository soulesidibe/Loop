package loop.core.orchestrator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.Claim
import loop.api.Claims
import loop.api.EventSink
import loop.api.Freeze
import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.Issue
import loop.api.IssueComment
import loop.api.LedgerRow
import loop.api.ParkedIssue
import loop.api.ParkedIssues
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RetryState
import loop.api.RetryStates
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TrackerAdapter
import loop.api.TrackerError
import loop.api.TurnId
import loop.api.WaitingIssue
import loop.api.WaitingIssues
import loop.core.machine.Machine
import loop.core.machine.MachineLimits
import loop.core.workspace.HookName
import loop.core.workspace.HookResult
import loop.core.workspace.HookRunner
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

val FIXED_NOW: Instant = Instant.parse("2026-11-06T19:02:11Z")

class FixedClock : Clock {
    override fun now(): Instant = FIXED_NOW
}

/** [FIXED_NOW] plus the virtual time of [scheduler], so `advanceTimeBy` moves the clock. */
class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun now(): Instant = FIXED_NOW + scheduler.currentTime.milliseconds
}

/**
 * In-memory [ParkedIssues]; [failure] makes every call fail. [parkFailure] fails only
 * [park] with a failed Result, [parkThrows] makes [park] throw instead.
 */
class FakeParkedIssues(
    vararg initial: ParkedIssue,
) : ParkedIssues {
    val records: MutableMap<Pair<String, String>, ParkedIssue> =
        initial.associateBy { it.projectId.value to it.issueId }.toMutableMap()
    var failure: Throwable? = null
    var parkFailure: Throwable? = null
    var parkThrows: Throwable? = null

    override suspend fun park(issue: ParkedIssue): Result<Unit> {
        parkThrows?.let { throw it }
        parkFailure?.let { return Result.failure(it) }
        failure?.let { return Result.failure(it) }
        records[issue.projectId.value to issue.issueId] = issue
        return Result.success(Unit)
    }

    override suspend fun list(projectId: ProjectId): Result<List<ParkedIssue>> {
        failure?.let { return Result.failure(it) }
        return Result.success(records.values.filter { it.projectId == projectId })
    }

    override suspend fun unpark(
        projectId: ProjectId,
        issueId: String,
    ): Result<ParkedIssue?> {
        failure?.let { return Result.failure(it) }
        return Result.success(records.remove(projectId.value to issueId))
    }
}

/** In-memory [Claims]; [failure] makes every call fail. [history] holds every claim written, in order. */
class FakeClaims(
    vararg initial: Claim,
) : Claims {
    val records: MutableMap<Pair<String, String>, Claim> =
        initial.associateBy { it.projectId.value to it.issueId }.toMutableMap()
    val history = mutableListOf<Claim>()
    var failure: Throwable? = null

    override suspend fun claim(claim: Claim): Result<Unit> {
        failure?.let { return Result.failure(it) }
        records[claim.projectId.value to claim.issueId] = claim
        history += claim
        return Result.success(Unit)
    }

    override suspend fun claims(projectId: ProjectId): Result<List<Claim>> {
        failure?.let { return Result.failure(it) }
        return Result.success(records.values.filter { it.projectId == projectId })
    }

    override suspend fun release(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> {
        failure?.let { return Result.failure(it) }
        records.remove(projectId.value to issueId)
        return Result.success(Unit)
    }
}

/** In-memory [RetryStates]; [readFailure] fails the reads, [writeFailure] the writes and deletes. */
class FakeRetryStates(
    vararg initial: RetryState,
) : RetryStates {
    val records: MutableMap<Pair<String, String>, RetryState> =
        initial.associateBy { it.projectId.value to it.issueId }.toMutableMap()
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    override suspend fun saveRetryState(state: RetryState): Result<Unit> {
        writeFailure?.let { return Result.failure(it) }
        records[state.projectId.value to state.issueId] = state
        return Result.success(Unit)
    }

    override suspend fun deleteRetryState(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it) }
        records.remove(projectId.value to issueId)
        return Result.success(Unit)
    }

    override suspend fun retryStates(projectId: ProjectId): Result<List<RetryState>> {
        readFailure?.let { return Result.failure(it) }
        return Result.success(records.values.filter { it.projectId == projectId })
    }
}

/** In-memory [WaitingIssues]; [readFailure] fails the reads, [writeFailure] the writes and ends; [reads] counts the reads. */
class FakeWaitingIssues(
    vararg initial: WaitingIssue,
) : WaitingIssues {
    val records: MutableMap<Pair<String, String>, WaitingIssue> =
        initial.associateBy { it.projectId.value to it.issueId }.toMutableMap()
    val history = mutableListOf<WaitingIssue>()
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var reads = 0

    override suspend fun startWaiting(issue: WaitingIssue): Result<Unit> {
        writeFailure?.let { return Result.failure(it) }
        records[issue.projectId.value to issue.issueId] = issue
        history += issue
        return Result.success(Unit)
    }

    override suspend fun waitingIssues(projectId: ProjectId): Result<List<WaitingIssue>> {
        reads++
        readFailure?.let { return Result.failure(it) }
        return Result.success(records.values.filter { it.projectId == projectId })
    }

    override suspend fun endWaiting(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> {
        writeFailure?.let { return Result.failure(it) }
        records.remove(projectId.value to issueId)
        return Result.success(Unit)
    }
}

fun issue(
    identifier: String,
    state: String = "todo",
    labels: List<String> = listOf("ready-for-agent"),
    priority: Int? = 2,
    createdAt: Instant? = null,
    blockedReason: String? = null,
    description: String? = null,
    area: List<String> = emptyList(),
): Issue =
    Issue(
        id = "id-$identifier",
        identifier = identifier,
        title = "Title of $identifier",
        state = state,
        priority = priority,
        labels = labels,
        blockedBy = emptyList(),
        description = description,
        createdAt = createdAt,
        blockedReason = blockedReason,
        area = area,
    )

/**
 * Returns every issue it holds whatever the states asked, so the tests cover
 * the orchestrator's own filter rather than the adapter's.
 */
class FakeTracker(
    issues: List<Issue>,
) : TrackerAdapter {
    val issues: MutableList<Issue> = issues.toMutableList()
    val requestedStates = mutableListOf<List<String>>()
    val moves = mutableListOf<Pair<String, String>>()
    val comments = mutableListOf<Pair<String, IssueComment>>()
    var fetchFailure: TrackerError? = null

    /** When set, the states this tracker defines instead of the files tracker's (m4-14). */
    var definedStatesOverride: List<String>? = null

    override val definedStates: List<String>
        get() = definedStatesOverride ?: listOf("todo", "in_progress", "review", "needs_info", "done", "canceled")

    /** When true, [fetchIssuesByStates] returns only the issues in the states asked, like a real adapter. */
    var filterByStates: Boolean = false

    override suspend fun fetchIssuesByStates(states: List<String>): Result<List<Issue>> {
        requestedStates += states
        fetchFailure?.let { return Result.failure(it) }
        if (!filterByStates) return Result.success(issues.toList())
        val wanted = states.map { it.trim().lowercase() }.toSet()
        return Result.success(issues.filter { it.state.trim().lowercase() in wanted })
    }

    /** Runs at the start of every [fetchIssuesByIds] call; may throw or suspend. */
    var onFetchByIds: suspend () -> Unit = {}

    /** Every id list asked of [fetchIssuesByIds]. */
    val requestedIds = mutableListOf<List<String>>()

    /** When set, [fetchIssuesByIds] returns it as a failure. */
    var fetchByIdsFailure: TrackerError? = null

    override suspend fun fetchIssuesByIds(ids: List<String>): Result<List<Issue>> {
        requestedIds += ids
        onFetchByIds()
        fetchByIdsFailure?.let { return Result.failure(it) }
        return Result.success(issues.filter { it.id in ids })
    }

    /** When set, [moveIssue] records nothing and returns it as a failure. */
    var moveFailure: TrackerError? = null

    override suspend fun moveIssue(
        issueId: String,
        toState: String,
    ): Result<Unit> {
        moveFailure?.let { return Result.failure(it) }
        moves += issueId to toState
        val index = issues.indexOfFirst { it.id == issueId }
        if (index < 0) return Result.failure(TrackerError.NotFound(issueId))
        issues[index] = onMove(issues[index], toState)
        return Result.success(Unit)
    }

    /**
     * How [moveIssue] changes the issue; by default its state becomes the target. A label tracker like GitHub's
     * keeps the state `open` and changes labels instead (m2-23).
     */
    var onMove: (Issue, String) -> Issue = { issue, toState -> issue.copy(state = toState) }

    /** When set, [addComment] records nothing and returns it as a failure. */
    var commentFailure: TrackerError? = null

    /** When it returns an error for a comment, [addComment] fails with it for that comment only (m4-22). */
    var commentFailureFor: (IssueComment) -> TrackerError? = { null }

    override suspend fun addComment(
        issueId: String,
        comment: IssueComment,
    ): Result<Unit> {
        commentFailure?.let { return Result.failure(it) }
        commentFailureFor(comment)?.let { return Result.failure(it) }
        if (issues.none { it.id == issueId }) return Result.failure(TrackerError.NotFound(issueId))
        comments += issueId to comment
        return Result.success(Unit)
    }

    /** Every issue given to the move or the comment by issue (m4-14), in order, as the core passed it. */
    val writtenIssues = mutableListOf<Issue>()

    override suspend fun moveIssue(
        issue: Issue,
        toState: String,
    ): Result<Unit> = moveIssue(issue.id, toState).also { writtenIssues += issue }

    override suspend fun addComment(
        issue: Issue,
        comment: IssueComment,
    ): Result<Unit> = addComment(issue.id, comment).also { writtenIssues += issue }

    /** Every issue given to [completeIssue], in order (bl-06). */
    val completions = mutableListOf<Issue>()

    /** When set, [completeIssue] returns it as a failure. */
    var completeFailure: TrackerError? = null

    /** How [completeIssue] completes an issue; unchanged by default, like the interface default. */
    var completer: (Issue) -> Issue = { it }

    override suspend fun completeIssue(issue: Issue): Result<Issue> {
        completions += issue
        completeFailure?.let { return Result.failure(it) }
        return Result.success(completer(issue))
    }

    /** Every label write, as issue id, added and removed. */
    val labelWrites = mutableListOf<Triple<String, List<String>, List<String>>>()

    /** When set, [updateLabels] records nothing and returns it as a failure. */
    var labelFailure: TrackerError? = null

    override suspend fun updateLabels(
        issueId: String,
        add: List<String>,
        remove: List<String>,
    ): Result<Unit> {
        labelFailure?.let { return Result.failure(it) }
        val index = issues.indexOfFirst { it.id == issueId }
        if (index < 0) return Result.failure(TrackerError.NotFound(issueId))
        labelWrites += Triple(issueId, add, remove)
        val removed = remove.map { it.lowercase() }.toSet()
        val labels = issues[index].labels.filterNot { it in removed }
        issues[index] = issues[index].copy(labels = labels + add.map { it.lowercase() }.filterNot { it in labels })
        return Result.success(Unit)
    }
}

class FakeSink : EventSink {
    val events = mutableListOf<RecordedEvent>()
    var failure: Throwable? = null
    var throwOnRecord: Throwable? = null

    override suspend fun record(event: RecordedEvent): Result<Unit> {
        throwOnRecord?.let { throw it }
        failure?.let { return Result.failure(it) }
        events += event
        return Result.success(Unit)
    }
}

val SESSION = SessionId("th1-tu1")
val TURN = TurnId("tu1")

/** Starts a session, writes one line and completes the turn with exit code 0. */
fun succeedingTurn(): Flow<RunnerEvent> =
    flow {
        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
        emit(RunnerEvent.Output(RunnerEvent.Output.Stream.Stdout, "hello"))
        emit(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0))
    }

/** Starts a session and fails the turn with exit code 1. */
fun failingTurn(): Flow<RunnerEvent> =
    flow {
        emit(RunnerEvent.SessionStarted(SESSION, threadId = "th1", turnId = TURN))
        emit(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 1, reason = "exit_code=1"))
    }

/**
 * Turn [turn] of the scripted session: turn 1 starts the thread `th1`, later turns announce
 * their own turn id `tu<turn>` on it, and the turn ends with [exitCode]. With [tokens] set it reports a `Usage`
 * of that many input tokens (plus [cacheRead] cache read tokens) before the turn ends; like a real runner the
 * values are absolute totals for the thread so far.
 */
fun sessionTurn(
    turn: Int,
    exitCode: Int = 0,
    tokens: Long? = null,
    cacheRead: Long = 0,
): Flow<RunnerEvent> =
    flow {
        val turnId = TurnId("tu$turn")
        val sessionId = SessionId("th1-tu$turn")
        if (turn == 1) {
            emit(RunnerEvent.SessionStarted(sessionId, threadId = "th1", turnId = turnId))
        } else {
            emit(RunnerEvent.TurnStarted(sessionId, threadId = "th1", turnId = turnId, turn = turn))
        }
        if (tokens != null) {
            emit(RunnerEvent.Usage(sessionId, turnId, tokens, 0, cacheRead, 0, totalCostUsd = null))
        }
        if (exitCode == 0) {
            emit(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
        } else {
            emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = exitCode, reason = "exit_code=$exitCode"))
        }
    }

/**
 * A runner whose [run] plays [script]. With [sessionScript] set it also opens a session, which plays
 * that script once per turn and records each turn's input in [turnInputs]; without it
 * [openSession] is null, like a runner with no continuation.
 */
class FakeRunner(
    private val script: (AttemptContext, String) -> Flow<RunnerEvent> = { _, _ -> succeedingTurn() },
) : AgentRunner {
    /** The model it reports for the ledger (m3-04). */
    override var model: String? = null

    /** Plays one turn of the session; null makes [openSession] return null. */
    var sessionScript: ((turn: Int, input: String) -> Flow<RunnerEvent>)? = null

    /** The declared occupancy capability (m3-09); the scripts may report occupancy whatever it says. */
    var occupancy = false

    /** Continuation follows [sessionScript]; the scripts may report usage with a cost. */
    override val capabilities: RunnerCapabilities
        get() =
            RunnerCapabilities(
                continuation = sessionScript != null,
                loopTools = RunnerCapabilities.LoopTools.Files,
                usage = true,
                cost = true,
                occupancy = occupancy,
                sandbox = false,
            )

    val calls = mutableListOf<Pair<AttemptContext, String>>()
    var onCall: (AttemptContext) -> Unit = {}

    /** The turn number and input of every [AgentSession.runTurn] call, across sessions. */
    val turnInputs = mutableListOf<Pair<Int, String>>()
    var sessionsOpened = 0
    var stops = 0

    /** Runs at the start of [AgentSession.stop]. */
    var onStop: suspend () -> Unit = {}

    /**
     * The reports the session numbered from 1 gives at its stop (bl-132), as a wake read at the stop would: its usage,
     * and a [RunnerEvent.StopGraceExpired] when the stop grace ended with the wake still running (m4-04).
     */
    var stopUsage: (Int) -> List<RunnerEvent> = { emptyList() }

    /** The number of the session, counted from 1, of every stop called with a report callback (m4-04). */
    val reportedStops = mutableListOf<Int>()

    /** The number of the session, counted from 1, of every [AgentSession.interrupt] call (m3-12). */
    val interrupts = mutableListOf<Int>()

    /** Runs on [AgentSession.interrupt] with the number of the session. */
    var onInterrupt: (Int) -> Unit = {}

    /**
     * m4-03: scripted wakes. True for turn `t` of session `s` (numbered from 1) makes it a wake turn: a
     * [RunnerEvent.WakeTurn] goes right before its terminal event, as a runner sends it once the turn read the
     * notification of a background task run an earlier turn started.
     */
    var wakes: (session: Int, turn: Int) -> Boolean = { _, _ -> false }

    override fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent> {
        calls += ctx to prompt
        onCall(ctx)
        return script(ctx, prompt)
    }

    /** m4-46: true makes [resumeSession] resume, as Claude Code does; false keeps the default, as Codex does. */
    var resumes = false

    /** The runtime session ids [resumeSession] resumed, in order. */
    val resumedSessions = mutableListOf<String>()

    /** The number, counted from 1 like [sessionsOpened], of each session [resumeSession] opened. */
    val resumedNumbers = mutableListOf<Int>()

    override fun resumeSession(
        ctx: AttemptContext,
        runtimeSessionId: String,
    ): AgentSession? {
        if (!resumes) return super.resumeSession(ctx, runtimeSessionId)
        val session = openSession(ctx) ?: return null
        resumedSessions += runtimeSessionId
        resumedNumbers += sessionsOpened
        return session
    }

    override fun openSession(ctx: AttemptContext): AgentSession? {
        val turns = sessionScript ?: return null
        sessionsOpened++
        val number = sessionsOpened
        onCall(ctx)
        return object : AgentSession {
            override fun runTurn(
                input: String,
                turn: Int,
            ): Flow<RunnerEvent> {
                turnInputs += turn to input
                if (!wakes(number, turn)) return turns(turn, input)
                return flow {
                    var woken = false
                    turns(turn, input).collect { event ->
                        val ids = event.terminalIds()
                        if (!woken && ids != null) {
                            woken = true
                            emit(RunnerEvent.WakeTurn(ids.first, ids.second))
                        }
                        emit(event)
                    }
                }
            }

            override suspend fun stop() {
                stops++
                onStop()
            }

            override suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
                reportedStops += number
                stopUsage(number).forEach { report(it) }
                stop()
            }

            override fun interrupt() {
                interrupts += number
                onInterrupt(number)
            }
        }
    }
}

/** The session and turn ids of a terminal event, null for any other event. */
private fun RunnerEvent.terminalIds(): Pair<SessionId, TurnId>? =
    when (this) {
        is RunnerEvent.TurnCompleted -> sessionId to turnId
        is RunnerEvent.TurnFailed -> sessionId to turnId
        is RunnerEvent.TurnInputRequired -> sessionId to turnId
        else -> null
    }

/** One call of [FakeHookRunner]. */
data class HookCall(
    val hook: HookName,
    val script: String,
    val cwd: Path,
    val env: Map<String, String>,
    val timeoutMs: Long,
)

/**
 * Records every hook call and returns a canned result: exit code 0 unless set in
 * [exitCodes], timed out for the hooks in [timedOut], with [output], cut for the hooks in
 * [truncated]. [onRun] runs
 * first and may suspend or throw.
 */
class FakeHookRunner : HookRunner {
    val calls = mutableListOf<HookCall>()
    val exitCodes = mutableMapOf<HookName, Int>()
    val timedOut = mutableSetOf<HookName>()
    val output = mutableMapOf<HookName, String>()

    /** Hooks whose [output] is reported as a cut tail. */
    val truncated = mutableSetOf<HookName>()
    var onRun: suspend (HookName) -> Unit = {}

    fun hooks(): List<HookName> = calls.map { it.hook }

    override suspend fun run(
        hook: HookName,
        script: String,
        cwd: Path,
        env: Map<String, String>,
        timeoutMs: Long,
    ): HookResult {
        calls += HookCall(hook, script, cwd, env, timeoutMs)
        onRun(hook)
        val late = hook in timedOut
        return HookResult(
            hook = hook,
            exitCode = if (late) null else exitCodes[hook] ?: 0,
            timedOut = late,
            timeoutMs = timeoutMs,
            durationMs = 12,
            output = output[hook] ?: "",
            outputTruncated = hook in truncated,
        )
    }
}

/**
 * In-memory [FrozenAcceptanceCommands], one record per project and issue; [failure] fails every call,
 * [clearFailure] only [clearChanged].
 */
class FakeFrozenCommands(
    vararg initial: FrozenCommands,
) : FrozenAcceptanceCommands {
    val records: MutableMap<Pair<String, String>, FrozenCommands> =
        initial.associateBy { it.projectId.value to it.issueId }.toMutableMap()
    var failure: Throwable? = null
    var clearFailure: Throwable? = null

    override suspend fun freeze(commands: FrozenCommands): Result<Freeze> {
        failure?.let { return Result.failure(it) }
        val key = commands.projectId.value to commands.issueId
        records[key]?.let { return Result.success(Freeze(it, written = false)) }
        records[key] = commands
        return Result.success(Freeze(commands, written = true))
    }

    override suspend fun markChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
        count: Int,
    ): Result<Unit> {
        failure?.let { return Result.failure(it) }
        val key = projectId.value to issueId
        records[key]?.let { records[key] = it.copy(changedDigest = digest, changedCount = count) }
        return Result.success(Unit)
    }

    override suspend fun clearChanged(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> {
        (clearFailure ?: failure)?.let { return Result.failure(it) }
        val key = projectId.value to issueId
        records[key]?.let { records[key] = it.copy(changedDigest = null, changedCount = null) }
        return Result.success(Unit)
    }

    override suspend fun acceptChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
    ): Result<FrozenCommands?> {
        failure?.let { return Result.failure(it) }
        val key = projectId.value to issueId
        val record = records[key]?.takeIf { it.changedDigest == digest } ?: return Result.success(null)
        val accepted =
            record.copy(
                digest = checkNotNull(record.changedDigest),
                count = checkNotNull(record.changedCount),
                changedDigest = null,
                changedCount = null,
            )
        records[key] = accepted
        return Result.success(accepted)
    }
}

/**
 * The machine of m3-16 for orchestrator tests, shared by every orchestrator a test builds, like the machine level
 * store: [limits] as the config would give them, the slots held on the machine (by any process) and the ledger rows of
 * every project. [log] keeps each claim, refusal and release in order.
 */
class FakeMachine(
    var limits: MachineLimits? = null,
) : Machine {
    var limitsFailure: Throwable? = null
    var claimFailure: Throwable? = null
    var releaseFailure: Throwable? = null
    var rowsFailure: Throwable? = null

    /** Called before each claim with its holder (bl-101: a claim that suspends while an attempt ends). */
    var beforeClaim: suspend (String) -> Unit = {}

    /** Called after each claim that took a slot, with its holder (bl-180: cancels the tick right after the claim). */
    var onClaimed: (String) -> Unit = {}
    val slots = mutableListOf<String>()
    val rows = mutableListOf<LedgerRow>()
    val log = mutableListOf<String>()

    override fun limits(): Result<MachineLimits?> = limitsFailure?.let { Result.failure(it) } ?: Result.success(limits)

    override suspend fun claimSlot(
        holder: String,
        cap: Int,
    ): Result<Boolean> {
        beforeClaim(holder)
        claimFailure?.let { return Result.failure(it) }
        if (slots.size >= cap) {
            log += "refused $holder"
            return Result.success(false)
        }
        slots += holder
        log += "claimed $holder"
        onClaimed(holder)
        return Result.success(true)
    }

    override suspend fun releaseSlot(holder: String): Result<Unit> {
        releaseFailure?.let { return Result.failure(it) }
        slots -= holder
        log += "released $holder"
        return Result.success(Unit)
    }

    override suspend fun attemptRowsSince(since: Instant): Result<List<LedgerRow>> =
        rowsFailure?.let { Result.failure(it) } ?: Result.success(rows.filter { it.attempt.finishedAt > since })
}
