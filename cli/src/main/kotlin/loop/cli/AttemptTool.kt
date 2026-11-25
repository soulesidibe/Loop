package loop.cli

import com.github.ajalt.clikt.core.ProgramResult
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.SessionId
import loop.api.TurnId
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.EventDefinition
import loop.core.orchestrator.ToolRequest
import loop.core.orchestrator.ToolRequestQueue
import loop.core.trackers.NamespacedId
import loop.store.SqliteEventStore
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Clock

/**
 * What the `loop tool` commands share (plan D11, U2, m1-23): the attempt environment, the tool
 * config snapshot, the store, the liveness check and the request queue. It never reads
 * `WORKFLOW.md` and builds no tracker: a tool only validates and queues, and Loop applies the
 * request host side when the turn ends. [name] prefixes every refusal line, and every refusal that
 * queued nothing ends with `Nothing queued.`. A refusal prints one redacted line to stderr and exits
 * with its code. The redactor stays the fallback one (the Anthropic keys and the token patterns): no
 * credential is reachable inside an agent.
 */
internal class AttemptTool(
    private val name: String,
    private val host: Host,
    private val scope: RedactorScope,
    private val echoErr: (String) -> Unit,
) {
    /** The attempt of the environment, as the snapshot at [configPath] describes its project. */
    class Env(
        val attemptId: AttemptId,
        /** `LOOP_ISSUE_ID`, the native id of the issue (m4-12). */
        val issueId: String,
        val issueName: String,
        val configPath: Path,
        val config: ToolConfig,
        /** `LOOP_TRACKER_ID`, the tracker of the issue (m4-12), null when the attempt was started without it. */
        val trackerId: String? = null,
        /**
         * The issue as the core names it (m4-47), read from [trackerId] and [issueId] by the snapshot's trackers, null
         * with a snapshot written before m4-47.
         */
        val issue: NamespacedId? = null,
    ) {
        val projectId: ProjectId get() = config.projectId

        /**
         * True when the core id [issueId] of an `attempt_started` event is this issue. Since m4-47 the core reads it
         * with [issue], a bare id being the primary tracker's. With a snapshot written before m4-47: its namespaced id
         * `<LOOP_TRACKER_ID>:<LOOP_ISSUE_ID>` (m4-12), or the bare id an attempt started before m4-12 carries.
         */
        fun owns(issueId: String): Boolean {
            val ids = config.ids
            if (ids != null && issue != null) return ids.isIssue(issueId, issue)
            return issueId == this.issueId || (trackerId != null && issueId == "$trackerId:${this.issueId}")
        }

        /** The attempt directory, the folder of the snapshot, which holds the request queue. */
        val attemptDir: Path get() = configPath.parent
    }

    fun refuse(
        message: String,
        code: Int,
    ): Nothing {
        echoErr(scope.redactor.redact("loop tool $name: $message"))
        throw ProgramResult(code)
    }

    fun redact(text: String): String = scope.redactor.redact(text)

    /** The Loop home of the machine, where the store and the run files are (m4-25). */
    val loopHome: Path get() = host.loopHome

    /** Prints one redacted warning line to stderr, which fails nothing (m4-25). */
    fun warn(message: String) {
        echoErr(redact("loop tool $name: $message"))
    }

    /** Reads the attempt environment and the tool config snapshot at `LOOP_TOOL_CONFIG`. */
    fun env(): Env {
        val attemptId = host.env(ENV_ATTEMPT_ID) ?: refuse(NOT_IN_ATTEMPT, EXIT_NOT_IN_ATTEMPT)
        val issueId = host.env(ENV_ISSUE_ID) ?: refuse(NOT_IN_ATTEMPT, EXIT_NOT_IN_ATTEMPT)
        val configPath = host.env(ENV_TOOL_CONFIG)?.let { Path(it) } ?: refuse(NOT_IN_ATTEMPT, EXIT_NOT_IN_ATTEMPT)
        val config =
            readToolConfig(configPath).getOrElse { error ->
                when (error) {
                    is ToolConfigError.UnknownVersion ->
                        refuse(
                            "the attempt config at $configPath has version ${error.version}, " +
                                "this loop reads version $TOOL_CONFIG_VERSION. Update the loop launcher on PATH. $NOTHING_QUEUED",
                            EXIT_NOT_IN_ATTEMPT,
                        )
                    else ->
                        refuse(
                            "cannot read the attempt config at $configPath: ${error.sentence()}. " +
                                "The attempt may have ended; Loop removes this file when an attempt finishes. $NOTHING_QUEUED",
                            EXIT_NOT_IN_ATTEMPT,
                        )
                }
            }
        val trackerId = host.env(ENV_TRACKER_ID)
        // m4-47: no LOOP_TRACKER_ID means the primary tracker, and a tracker the project does not have is refused.
        val issue = config.ids?.ofAgent(trackerId, issueId)?.getOrElse { refuse("${it.message}. $NOTHING_QUEUED", EXIT_NOT_LIVE) }
        return Env(AttemptId(attemptId), issueId, host.env(ENV_ISSUE_IDENTIFIER) ?: issueId, configPath, config, trackerId, issue)
    }

    fun openStore(env: Env): SqliteEventStore {
        val storeFile = host.loopHome.resolve(SqliteEventStore.FILE_NAME)
        return SqliteEventStore
            .open(host.loopHome, env.config.workflowDir, scope.redactor)
            .getOrElse { refuse("cannot open the store at $storeFile: ${it.sentence()}. $NOTHING_QUEUED", EXIT_STORE) }
    }

    /** The `attempt_started` event of the attempt, when it is live and belongs to the issue in the project. */
    suspend fun liveAttempt(
        store: SqliteEventStore,
        env: Env,
    ): RecordedEvent {
        val short = shortId(env.attemptId)
        val events =
            store
                .eventsForAttempt(env.attemptId)
                .getOrElse { refuse("cannot read the store: ${it.sentence()}. $NOTHING_QUEUED", EXIT_STORE) }
                .map { it.event }
        val started =
            events.firstOrNull { it.kind == AttemptStarted.name }
                ?: refuse("attempt $short is not known to the store. $NOTHING_QUEUED", EXIT_NOT_LIVE)
        if (started.projectId != env.projectId) {
            refuse(
                "attempt $short belongs to project ${started.projectId.value}, not ${env.projectId.value}. $NOTHING_QUEUED",
                EXIT_NOT_LIVE,
            )
        }
        if (started.issueId?.let(env::owns) != true) {
            val owner = started.issueIdentifier ?: started.issueId
            refuse("attempt $short belongs to issue $owner, not ${env.issueName}. $NOTHING_QUEUED", EXIT_NOT_LIVE)
        }
        events.firstOrNull { it.kind == AttemptFinished.name }?.let { finished ->
            refuse("attempt $short is not live (finished at ${finished.ts}). $NOTHING_QUEUED", EXIT_NOT_LIVE)
        }
        return started
    }

    /**
     * Queues [request] in the attempt directory. [what] names it in a failure, for example
     * `the move of t-1`. The directory gone (the attempt ended) exits 5; another failure exits 1.
     */
    fun enqueue(
        env: Env,
        request: ToolRequest,
        what: String,
    ) {
        try {
            ToolRequestQueue(env.attemptDir).enqueue(request)
        } catch (e: NoSuchFileException) {
            refuse("attempt ${shortId(env.attemptId)} is not live (its attempt directory is gone). $NOTHING_QUEUED", EXIT_NOT_LIVE)
        } catch (e: IOException) {
            refuse("cannot queue $what: ${e.sentence()}. $NOTHING_QUEUED", EXIT_FAILURE)
        }
    }

    /** Records an event of the attempt's issue; a store failure warns ("[done], but the store...") and does not fail the tool. */
    suspend fun record(
        store: SqliteEventStore,
        env: Env,
        started: RecordedEvent,
        definition: EventDefinition,
        data: Map<String, String>,
        done: String,
    ) {
        val event =
            definition.event(
                ts = Clock.System.now(),
                projectId = env.projectId,
                data = data,
                runId = started.runId,
                // m4-12: the core id of the attempt, namespaced, so the event is named with its tracker like the others.
                issueId = started.issueId ?: env.issueId,
                issueIdentifier = started.issueIdentifier ?: env.issueName,
                attemptId = env.attemptId,
                sessionId = host.env(ENV_SESSION_ID)?.let(::SessionId),
                turnId = host.env(ENV_TURN_ID)?.let(::TurnId),
            )
        store.record(event).onFailure {
            echoErr(redact("loop tool $name: $done, but the store did not record it: ${it.sentence()}."))
        }
    }

    companion object {
        const val EXIT_FAILURE = 1
        const val EXIT_REJECTED_ARGUMENT = 2
        const val EXIT_STORE = 3
        const val EXIT_NOT_IN_ATTEMPT = 4
        const val EXIT_NOT_LIVE = 5

        /** What every tool prints after a successful queue. */
        const val APPLIED_WHEN_TURN_ENDS = "Loop applies this in the tracker when this turn ends. Do not run it again."

        /** How every refusal ends: a tool only ever queues, so nothing else could have happened. */
        const val NOTHING_QUEUED = "Nothing queued."
        private const val SHORT_ID = 8
        private const val ENV_ATTEMPT_ID = "LOOP_ATTEMPT_ID"
        private const val ENV_ISSUE_ID = "LOOP_ISSUE_ID"
        private const val ENV_ISSUE_IDENTIFIER = "LOOP_ISSUE_IDENTIFIER"
        private const val ENV_TRACKER_ID = "LOOP_TRACKER_ID"
        private const val ENV_SESSION_ID = "LOOP_SESSION_ID"
        private const val ENV_TURN_ID = "LOOP_TURN_ID"
        private const val ENV_TOOL_CONFIG = "LOOP_TOOL_CONFIG"
        private const val NOT_IN_ATTEMPT =
            "not inside an attempt. Run it from the agent process Loop started, " +
                "where LOOP_ATTEMPT_ID, LOOP_ISSUE_ID and LOOP_TOOL_CONFIG are set. $NOTHING_QUEUED"

        /** The first 8 characters of the attempt id, as every tool line shows it. */
        fun shortId(attemptId: AttemptId): String = attemptId.value.take(SHORT_ID)
    }
}
