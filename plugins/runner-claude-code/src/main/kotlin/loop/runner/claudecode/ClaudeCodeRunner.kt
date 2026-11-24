package loop.runner.claudecode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.RunnerCapabilities
import loop.api.RunnerConfigException
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.process.GroupSignalResult
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import loop.runner.process.ShellGroupSignaller
import loop.runner.process.agentEnvironment
import loop.runner.process.resolveAddedEnv
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The settings of the Claude Code runner, from `runners.claude-code` ([of], m3-04): [command] is the
 * executable, [model] is passed as `--model` when set. [holdoutDir] is Loop's holdout folder
 * (m2-14), whose reads and edits the generated settings deny; null denies none. [strictMcp]
 * (`strict_mcp`, bl-72) passes `--strict-mcp-config`, so the agent gets Loop's MCP server and no
 * other: none of the operator's, the project's or the claude.ai connectors.
 */
data class ClaudeCodeSettings(
    val command: String,
    val model: String? = null,
    val turnTimeout: Duration = 1.hours,
    val readTimeout: Duration = 120.seconds,
    val holdoutDir: Path? = null,
    val strictMcp: Boolean = true,
) {
    companion object {
        /** The `runner.kind` of the Claude Code runner, and the key of its section under `runners`. */
        const val KIND = "claude-code"

        /** The longest `turn_timeout_ms` and `read_timeout_ms`, one day (m3-05). */
        const val MAX_TIMEOUT_MS: Long = 86_400_000

        /** The profile of `runners.claude-code` (m3-05, bl-72): its keys, no secret, and the limits of both timeouts. */
        val PROFILE: PluginProfile =
            ClaudeCodeSettings(command = "").let { defaults ->
                PluginProfile(
                    keys =
                        listOf(
                            ProfileKey("command", ProfileType.STRING, required = true),
                            ProfileKey("model", ProfileType.STRING),
                            ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "${defaults.turnTimeout.inWholeMilliseconds}"),
                            ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "${defaults.readTimeout.inWholeMilliseconds}"),
                            ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "${defaults.strictMcp}"),
                        ),
                    limits = mapOf("turn_timeout_ms" to 1L..MAX_TIMEOUT_MS, "read_timeout_ms" to 1L..MAX_TIMEOUT_MS),
                )
            }

        /**
         * Reads the section `runners.claude-code` as written: `command` (required), `model`, `turn_timeout_ms` and
         * `read_timeout_ms` (positive integers, quoted or not), `strict_mcp` (`true` or `false`, true when absent or
         * blank). Throws [RunnerConfigException] naming the key.
         */
        fun of(
            config: Map<String, Any?>,
            holdoutDir: Path? = null,
        ): ClaudeCodeSettings {
            val defaults = ClaudeCodeSettings(command = "")
            return ClaudeCodeSettings(
                command =
                    config.string("command")?.takeIf { it.isNotBlank() }
                        ?: throw RunnerConfigException("runners.$KIND.command is required"),
                model = config.string("model")?.takeIf { it.isNotBlank() },
                turnTimeout = config.millis("turn_timeout_ms") ?: defaults.turnTimeout,
                readTimeout = config.millis("read_timeout_ms") ?: defaults.readTimeout,
                holdoutDir = holdoutDir,
                strictMcp = config.boolean("strict_mcp") ?: defaults.strictMcp,
            )
        }

        private fun Map<String, Any?>.boolean(key: String): Boolean? {
            val value = get(key)?.takeUnless { it is String && it.isBlank() } ?: return null
            return ProfileType.booleanOf(value) ?: throw RunnerConfigException("runners.$KIND.$key must be a boolean")
        }

        private fun Map<String, Any?>.string(key: String): String? =
            when (val value = get(key)) {
                null -> null
                is String -> value
                else -> throw RunnerConfigException("runners.$KIND.$key must be a string")
            }

        private fun Map<String, Any?>.millis(key: String): Duration? {
            val text = string(key) ?: return null
            return text.toLongOrNull()?.takeIf { it > 0 }?.milliseconds
                ?: throw RunnerConfigException("runners.$KIND.$key must be a positive integer")
        }
    }
}

/**
 * Runs Claude Code headless, one long lived process per Attempt (m3-07): `<command> -p [--resume <id>]
 * --input-format stream-json --output-format stream-json --verbose --settings <file> --permission-mode
 * bypassPermissions [--strict-mcp-config] [--mcp-config <file>] [--model m]` in the workspace, in its own process
 * group through [ProcessGroupLauncher]. The first turn of a session ([openSession]) spawns it. Each turn writes one
 * stream-json user message line on its stdin, which stays open, and the turn ends at the next `result` line, which
 * decides it ([StreamJsonMapper.turnEvents]): the process is not expected to exit then. Between turns the process is
 * idle by design, no timer runs on it, and it lives until the next turn or [AgentSession.stop]. [run] is the first
 * turn of a session that is stopped before the terminal event, so what the stop could not release is reported first.
 *
 * Ids: the thread and turn ids are minted as UUIDs before the turn, so the session id is `<thread_id>-<turn_id>`.
 * Turn 1 emits [RunnerEvent.SessionStarted] when the `system` `init` line arrives, carrying Claude Code's session id
 * as `runtimeSessionId`, or, when the turn ends before any init, right before the terminal event without it. Output
 * lines read before init are held back and emitted right after SessionStarted, in order. Turns 2..n emit
 * [RunnerEvent.TurnStarted] at once. Exactly one terminal event ends a turn's flow, nothing follows it.
 *
 * Every stdout line is emitted as raw [RunnerEvent.Output], a malformed one included. Lines the process writes while
 * it is idle, such as an agent woken by a background task's notification, are read by the next turn: emitted right
 * after its start event, and their `result` lines add their usage without ending that turn. A turn that finds such a
 * wake still running (an `init` or `assistant` line read while idle, no `result` line since) reads it to its `result`
 * line before it writes its input, so the wake cannot end the turn; the wait follows the turn timeout. Usage is
 * absolute for the session: each `result` line carries its own segment's usage, which the session sums (its
 * `model_usage_since_last_result`, subagents included, else its `usage`, bl-71), and the latest `total_cost_usd`,
 * which is already the session's (bl-25). Each `result` line with usage is followed at once by a
 * [RunnerEvent.Usage], the session total so far, so a turn that times out, stalls or is cancelled after one still
 * reports that spend (bl-37, bl-108); the end of a turn reports no usage of its own.
 * Claude Code reports usage only in the `result` line, so during a turn each main session `assistant` line with
 * usage is followed by a [RunnerEvent.UsageEstimate] (m1-38): the session's real totals plus this turn's per message
 * usage, one entry per message id (a later line of a message replaces its earlier one), subagent lines and lines
 * without a message id left out.
 * Occupancy (m3-08): each main session `assistant` message with usage is followed by a [RunnerEvent.ContextUsage],
 * its `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`, the input size of that request. Claude
 * Code writes one line per content block with the same message id and usage, so only the first line of a message id
 * counts, for the whole session; a line without a message id counts on its own. Subagent lines (with a
 * `parent_tool_use_id`) never count: a subagent's window is its own. Lines read while idle count in the turn that
 * reads them. The model is `anthropic/` and the newest `init` line's `model`, null while no `init` line named one.
 *
 * Failures: `invalid_workspace_cwd` (the workspace is not a directory, nothing is spawned), `agent_not_found` (spawn
 * or exec failed), `response_timeout` (no stdout line within [ClaudeCodeSettings.readTimeout] after the input was
 * written), `turn_timeout` (no stdout line for [ClaudeCodeSettings.turnTimeout]), both killing the process group and
 * keeping the lines still queued, `port_exit` (exit without a result line) and `missing_runtime_session_id` (a new
 * process is needed for turn 2..n but no Claude Code session id is known). A tool denied outside the deny rules ends
 * the turn with [RunnerEvent.TurnInputRequired] naming the tool instead.
 *
 * Interrupt (m3-12): [AgentSession.interrupt] writes a stream-json interrupt request to the process; Claude Code then
 * ends the turn with a `result` line of subtype `error_during_execution`, whose usage and cost are reported as for any
 * result line, so the turn fails with that reason and its spend counts. A wake being read ends the same way, and the
 * turn's input is then never written: the turn fails as `interrupted`. A turn cancelled while it reads a wake kills
 * the process group, as any cancelled turn does.
 *
 * A process that exited between turns, or that a timed out, failed or cancelled turn killed, is not a failure by
 * itself: the next turn spawns a new one with `--resume <runtime session id>`, after a `loop:` warning line on
 * stderr. The newest init session id wins and the `result` line's `session_id` is the fallback; a process that
 * reports no id drops the stored one, so a later respawn fails instead of resuming a stale session.
 *
 * Across attempts (m4-46): [resumeSession] opens a session whose turn 1 spawns the process with `--resume` and the
 * session id of an earlier attempt, after a `loop:` warning line, so the conversation goes on in a new attempt. Loop
 * asks for it only after a network hold, and a rotation always opens a fresh session with [openSession].
 *
 * Env: the shared [agentEnvironment] policy, [hostEnv] minus [removedEnvNames], plus [addedEnv] (`agent.env`, each
 * `{workspace}` replaced by the attempt's workspace), plus the context env and `LOOP_SESSION_ID` and `LOOP_TURN_ID`
 * (Loop's own names survive removal), always minus `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN`. The env and the
 * MCP config are fixed when the process spawns, so they carry the ids of the turn that spawned it.
 * MCP: when the context env has the launcher and the tool config snapshot, the process gets `--mcp-config` with
 * Loop's MCP server, `loop tool mcp` ([ClaudeMcpConfig], m2-16), so the agent can call `submit_result`, `move` and
 * `comment` (m2-17). With [ClaudeCodeSettings.strictMcp], the default, it also gets `--strict-mcp-config`, so that
 * server is the only one (bl-72). The generated settings file and MCP config live in a temp folder outside the
 * workspace, deleted once the process group is gone. A folder that cannot be deleted is reported as a `loop:` line
 * on stderr before the terminal event, or by [AgentSession.stop].
 *
 * Stop: stdin is closed, the process gets [stopGrace] to exit, then its group is killed. Stop throws when the group
 * cannot be stopped (the launcher reported members it could not kill, or a probe after the kill still finds some),
 * when the group of a process an earlier turn killed still has members, or when the settings folder cannot be deleted.
 * A `result` line read while it stops, from a wake still running or one that ended while the process was idle, adds
 * its usage to the session's, and the session total goes to the usage callback of [AgentSession.stop] (bl-132).
 */
class ClaudeCodeRunner internal constructor(
    internal val settings: ClaudeCodeSettings,
    private val hostEnv: Map<String, String>,
    private val removedEnvNames: Set<String>,
    private val launchProcess: (LaunchSpec) -> Flow<ProcessEvent>,
    private val addedEnv: Map<String, String> = emptyMap(),
    private val stopGrace: Duration = STOP_GRACE,
    /** True while the process group still has a member; a probe that fails counts as alive. */
    private val groupAlive: (Long) -> Boolean = { pgid -> ShellGroupSignaller.signal(pgid, SIGNAL_PROBE) != GroupSignalResult.Empty },
    /** How many lines no turn needs the process may queue while idle, see [LiveProcess.keeps]. */
    private val idleLineBound: Int = IDLE_LINE_BOUND,
    private val newSettingsDir: () -> Path = { createTempDirectory(SETTINGS_DIR_PREFIX) },
) : AgentRunner {
    constructor(
        settings: ClaudeCodeSettings,
        hostEnv: Map<String, String> = System.getenv(),
        removedEnvNames: Set<String> = emptySet(),
        addedEnv: Map<String, String> = emptyMap(),
    ) : this(settings, hostEnv, removedEnvNames, ProcessGroupLauncher()::launch, addedEnv)

    /**
     * Continuation (one long lived process), Loop's tools through its MCP server, usage and cost from the `result`
     * lines, occupancy from the per message usage of the main session (m3-08), and no sandbox: the generated deny
     * rules are a guardrail.
     */
    override val capabilities: RunnerCapabilities = CAPABILITIES

    override fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent> =
        flow {
            val session = ClaudeSession(ctx)
            try {
                session.runTurn(prompt, 1).collect { event ->
                    if (event.isTerminal()) session.shutdown().forEach { emit(RunnerEvent.Output(Stream.Stderr, it)) }
                    emit(event)
                }
            } finally {
                // After a cancel or a failure the collector is gone, so nothing can be reported any more.
                session.shutdown()
            }
        }

    override fun openSession(ctx: AttemptContext): AgentSession = ClaudeSession(ctx)

    /**
     * m4-46: a session of the attempt [ctx] on a new thread whose turn 1 spawns Claude Code with `--resume
     * <runtimeSessionId>`, after a `loop:` warning line on stderr, so the agent continues the conversation of an earlier
     * attempt. Later turns and respawns behave as in any session.
     */
    override fun resumeSession(
        ctx: AttemptContext,
        runtimeSessionId: String,
    ): AgentSession = ClaudeSession(ctx, resumeFrom = runtimeSessionId)

    override val model: String? get() = settings.model

    /**
     * One agent thread and its long lived process. The session keeps the newest runtime session id and the summed
     * usage, so every Usage event is the absolute total of the session so far, across turns and respawns alike.
     */
    private inner class ClaudeSession(
        private val ctx: AttemptContext,
        /** The Claude Code session of an earlier attempt that turn 1 resumes (m4-46), null for a fresh session. */
        private val resumeFrom: String? = null,
    ) : AgentSession {
        private val threadId = UUID.randomUUID().toString()
        private val continuity = Continuity().apply { runtimeSessionId = resumeFrom }

        // The process collectors live here, not in a turn's flow, so the process outlives the turn that spawned it.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var live: LiveProcess? = null

        // The groups of retired processes that still had members after the kill, probed again by [shutdown].
        private val leftGroups = mutableSetOf<Long>()

        // The turn that runs now, the one [interrupt] ends.
        @Volatile private var running: Turn? = null

        // The ids of the latest turn, which name a usage report read at the stop (bl-132).
        @Volatile private var latest: Ids? = null

        override fun runTurn(
            input: String,
            turn: Int,
        ): Flow<RunnerEvent> =
            channelFlow {
                val turnId = TurnId(UUID.randomUUID().toString())
                val ids = Ids(SessionId("$threadId-${turnId.value}"), threadId, turnId, turn)
                val current = Turn(this, ids, hiddenFolders(ctx.workspace))
                running = current
                latest = ids
                try {
                    current.run(input)
                } finally {
                    if (running === current) running = null
                }
            }

        override fun interrupt() {
            running?.interrupt()
        }

        override suspend fun stop() = stop {}

        /**
         * bl-132: a wake still running when the session stops, or one that ended while the process was idle, wrote
         * `result` lines no turn read. Their usage is added to the session's and given to [report] once the process is
         * released, before a failed stop throws, so it counts in the attempt's spend. A wake still running when the stop
         * grace ends is reported from its lines, then [RunnerEvent.StopGraceExpired] (m4-04).
         */
        override suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
            val reports = mutableListOf<RunnerEvent>()
            val problems = shutdown { reports += it }
            reports.forEach { report(it) }
            if (problems.isNotEmpty()) throw IllegalStateException(problems.joinToString("; ") { it.removePrefix(LOOP_PREFIX) })
        }

        /**
         * Closes stdin, gives the process [stopGrace] to exit, then kills its group and deletes the settings folder.
         * The groups an earlier turn's kill left with members ([retire]) are probed again. Each stdout `result` line
         * read meanwhile adds its usage to the session's, and the session total is given to [report] (bl-132). When
         * the grace ends with the process still there, the usage of the wake it was running, read from its lines, is
         * added and given the same way, then a [RunnerEvent.StopGraceExpired] (m4-04). Returns the problems met, as
         * `loop:` lines; empty when everything was released. Safe to call twice.
         */
        suspend fun shutdown(report: (RunnerEvent) -> Unit = {}): List<String> =
            withContext(NonCancellable) {
                val process = live
                live = null
                val problems = mutableListOf<String>()
                try {
                    if (process != null) {
                        process.stdin.close()
                        val wake = StopWake()
                        // Once the process exits the launcher kills what is left of its group and reports what it could not.
                        var graceExpired = false
                        val exited =
                            try {
                                val read =
                                    withTimeoutOrNull(stopGrace) {
                                        for (event in process.events) {
                                            if (event !is ProcessEvent.Line) continue
                                            if (GROUP_PROBLEMS.any { event.text.startsWith(it) }) {
                                                problems += event.text
                                            } else if (event.stream == Stream.Stdout) {
                                                stopped(event.text, wake)?.let(report)
                                            }
                                        }
                                    }
                                graceExpired = read == null
                                read != null
                            } catch (e: Exception) {
                                // The launcher failed while the process was idle: whether the group is gone is unknown, the probe decides.
                                false
                            }
                        if (graceExpired) graceExpired(wake).forEach(report)
                        process.job?.cancelAndJoin()
                        val pid = process.pid
                        if (!exited && pid != null && probe(pid)) leftGroups += pid
                    }
                    leftGroups.filter { probe(it) }.forEach { problems += "${LOOP_PREFIX}could not stop the claude-code process group $it" }
                    leftGroups.clear()
                } finally {
                    process?.deleteSettings()?.let { problems += it }
                    scope.cancel()
                }
                problems
            }

        /**
         * A stdout [line] read while the session stops: a `result` line's usage is added to the session's, and the
         * session total is returned as a report of the latest turn. Null for any other line, or before any turn. A
         * main session `init` or `assistant` line marks a running [wake] and keeps its message's usage, which a `result`
         * line covers and clears (m4-04).
         */
        private fun stopped(
            line: String,
            wake: StopWake,
        ): RunnerEvent.Usage? {
            val ids = latest ?: return null
            when (val parsed = StreamJsonMapper.parse(line)) {
                is StreamLine.Init -> wake.running = true
                is StreamLine.Assistant -> if (parsed.parentToolUseId == null) wake.read(parsed)
                is StreamLine.Result -> {
                    wake.clear()
                    val segment = parsed.usage ?: return null
                    val total = continuity.usage.plus(segment)
                    continuity.usage = total
                    return StreamJsonMapper.usageEvent(total, ids.sessionId, ids.turnId)
                }
                else -> Unit
            }
            return null
        }

        /**
         * m4-04: the stop grace ended with the process still there. The usage the running [wake]'s lines reported is
         * added to the session's and reported, then [RunnerEvent.StopGraceExpired]; nothing before any turn.
         */
        private fun graceExpired(wake: StopWake): List<RunnerEvent> {
            val ids = latest ?: return emptyList()
            val messages = wake.messages()
            val usage =
                messages.takeIf { it.isNotEmpty() }?.let {
                    val total = it.fold(continuity.usage) { sum, part -> sum.plus(part) }
                    continuity.usage = total
                    total?.let { totals -> StreamJsonMapper.usageEvent(totals, ids.sessionId, ids.turnId) }
                }
            val expired =
                RunnerEvent.StopGraceExpired(
                    sessionId = ids.sessionId,
                    turnId = ids.turnId,
                    graceMs = stopGrace.inWholeMilliseconds,
                    wake = wake.running,
                    wakeMessages = messages.size,
                )
            return listOfNotNull(usage, expired)
        }

        private suspend fun probe(pid: Long): Boolean = withContext(Dispatchers.IO) { groupAlive(pid) }

        /**
         * Kills what is left of [process] and deletes its settings folder; returns the line to report when the folder
         * could not be deleted. A group that still has members after the kill is kept for [shutdown], which reports it
         * when it is still there, so the stop of the session fails.
         */
        private suspend fun retire(process: LiveProcess): String? =
            withContext(NonCancellable) {
                if (live === process) live = null
                process.stdin.close()
                process.job?.cancelAndJoin()
                process.pid?.let { pid -> if (probe(pid)) leftGroups += pid }
                process.deleteSettings()
            }

        /** One turn, from its input to its terminal event, reporting to [out]. */
        private inner class Turn(
            private val out: ProducerScope<RunnerEvent>,
            private val ids: Ids,
            private val hidden: HiddenFolders,
        ) {
            private val sessionId = ids.sessionId
            private val turnId = ids.turnId
            private var started = false
            private var ended = false
            private val held = mutableListOf<RunnerEvent>()

            // m4-03: whether this turn read the notification of a background task run an earlier turn started.
            private var wake = false

            // This turn's per message usage of the main session, by message id, for the estimate.
            private val messageUsage = mutableMapOf<String, StreamLine.UsageTotals>()

            // The launcher's own line when it could not exec the command, see ProcessGroupLauncher.
            private var execFailure: String? = null

            // The process this turn talks to, killed when the turn ends without its terminal event.
            private var current: LiveProcess? = null

            // m3-12: whether the turn was interrupted, and the process an interrupt request goes to. The lock keeps the
            // request from reaching a process before the turn's input, which would then run unchecked.
            private val inputLock = Any()
            private var interrupted = false
            private var listening: LiveProcess? = null

            suspend fun run(input: String) {
                try {
                    // Turns 2..n need nothing from the process to start.
                    if (ids.turn > 1) start(null)
                    val idle =
                        live?.let {
                            // m3-12: a turn cancelled while a wake is read stops the process group, as on any cancel.
                            current = it
                            hear(it)
                            val dropped = it.markBusy()
                            if (dropped > 0) {
                                emit(
                                    RunnerEvent.Output(
                                        Stream.Stderr,
                                        "${LOOP_PREFIX}warning: the claude-code process wrote more than $idleLineBound lines while idle, " +
                                            "$dropped of them dropped, its usage and result lines kept",
                                    ),
                                )
                            }
                            readIdle(it)
                        }
                    // A wake the process ran while idle went silent past the turn timeout.
                    if (ended) return
                    var process = idle ?: spawn() ?: return
                    current = process
                    if (!write(process, input)) return
                    if (read(process, reused = idle != null)) {
                        // It died between turns after all, its exit not read yet when the input was written.
                        process = spawn() ?: return
                        current = process
                        if (!write(process, input)) return
                        read(process, reused = false)
                    }
                } finally {
                    // m4-03: between turns nothing reads the process, so its queue is bounded until the next turn.
                    if (ended) live?.markIdle()
                    if (!ended) {
                        withContext(NonCancellable) {
                            // After a cancel the collector is gone, so the line cannot be emitted any more.
                            current?.let { retire(it) }?.let { out.trySend(RunnerEvent.Output(Stream.Stderr, it)) }
                        }
                    }
                }
            }

            /**
             * Asks the process to end this turn at once (m3-12): a stream-json interrupt request, after which Claude Code
             * writes its `result` line. A wake being read ends the same way, and the turn's input is then never written.
             * Only the first call counts.
             */
            fun interrupt() {
                synchronized(inputLock) {
                    if (interrupted) return
                    interrupted = true
                    listening?.stdin?.trySend(StreamJsonMapper.interruptRequest() + "\n")
                }
            }

            /** Makes [process] the one an interrupt request goes to; sends it at once when the turn was interrupted already. */
            private fun hear(process: LiveProcess) {
                synchronized(inputLock) {
                    listening = process
                    if (interrupted) process.stdin.trySend(StreamJsonMapper.interruptRequest() + "\n")
                }
            }

            /**
             * Writes [input] to [process] and returns true, or, when the turn was interrupted before, writes nothing and
             * ends the turn: false.
             */
            private suspend fun write(
                process: LiveProcess,
                input: String,
            ): Boolean {
                val written =
                    synchronized(inputLock) {
                        if (!interrupted) {
                            listening = process
                            process.stdin.trySend(StreamJsonMapper.userMessage(input) + "\n").getOrThrow()
                        }
                        !interrupted
                    }
                if (!written) end(listOf(RunnerEvent.TurnFailed(sessionId, turnId, null, INTERRUPTED_BEFORE_INPUT)))
                return written
            }

            /**
             * Reads what the live [process] wrote since the previous turn ended. While it runs a wake, a turn it
             * started on its own after a background task's notification, the reading goes on until the wake's `result`
             * line, so this turn's input cannot be answered by the wake's result. The wait follows the turn's silence
             * rule: [ClaudeCodeSettings.turnTimeout] without a stdout line kills the group and fails the turn. Returns
             * the process when it still runs and is idle, null when it ended, once its group is cleaned up, or when
             * the turn failed.
             */
            private suspend fun readIdle(process: LiveProcess): LiveProcess? {
                var lastStdout = TimeSource.Monotonic.markNow()
                while (true) {
                    val next =
                        if (process.waking) {
                            withTimeoutOrNull((settings.turnTimeout - lastStdout.elapsedNow()).coerceAtLeast(Duration.ZERO)) {
                                process.events.receiveCatching()
                            } ?: return timedOut(process, "turn_timeout")
                        } else {
                            process.events.tryReceive()
                        }
                    if (next.isClosed) return gone(process)
                    when (val event = next.getOrNull() ?: return process) {
                        is ProcessEvent.Line -> {
                            if (event.stream == Stream.Stdout) lastStdout = TimeSource.Monotonic.markNow()
                            line(event, process, idle = true)
                        }
                        is ProcessEvent.Exited, is ProcessEvent.SpawnFailed -> return gone(process)
                        is ProcessEvent.Started -> Unit
                    }
                }
            }

            /** Kills [process] after a silence, keeps the lines still queued, and fails the turn with [reason]. */
            private suspend fun timedOut(
                process: LiveProcess,
                reason: String,
            ): LiveProcess? {
                // Killing the process group before the turn is reported failed.
                process.job?.cancelAndJoin()
                // Lines read before the kill but not received yet still belong to the turn.
                while (true) {
                    val queued = process.events.tryReceive().getOrNull() ?: break
                    if (queued is ProcessEvent.Line) line(queued, process, idle = true)
                }
                failed(retire(process), RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = reason))
                return null
            }

            private suspend fun gone(process: LiveProcess): LiveProcess? {
                retire(process)?.let { emit(RunnerEvent.Output(Stream.Stderr, it)) }
                if (current === process) current = null
                return null
            }

            /**
             * Spawns the session's process, resuming the session after turn 1, and at turn 1 the earlier attempt's
             * session of a resumed one (m4-46); null when the turn ended instead.
             */
            private suspend fun spawn(): LiveProcess? {
                val resume = if (ids.turn > 1 || resumeFrom != null) continuity.runtimeSessionId else null
                if (ids.turn > 1 && resume == null) {
                    end(listOf(RunnerEvent.TurnFailed(sessionId, turnId, null, "missing_runtime_session_id: no session to resume")))
                    return null
                }
                if (!ctx.workspace.isDirectory()) {
                    end(listOf(RunnerEvent.TurnFailed(sessionId, turnId, null, "invalid_workspace_cwd: ${ctx.workspace}")))
                    return null
                }
                if (resume != null) {
                    val warning =
                        if (ids.turn == 1) {
                            "turn 1 resumes session $resume of an earlier attempt"
                        } else {
                            "the claude-code process of this session is gone, turn ${ids.turn} resumes session $resume in a new process"
                        }
                    emit(RunnerEvent.Output(Stream.Stderr, "${LOOP_PREFIX}warning: $warning"))
                }
                val stdin = Channel<String>(Channel.UNLIMITED)
                val events = Channel<ProcessEvent>(Channel.UNLIMITED)
                // Outside the workspace, so the agent cannot edit its own guardrail; kept until the process is gone.
                val process = LiveProcess(events, stdin, newSettingsDir(), idleLineBound).also { current = it }
                val dir = checkNotNull(process.settingsDir)
                val settingsFile =
                    dir.resolve(SETTINGS_FILE).apply { writeText(ClaudeSettingsFile.json(hiddenDirs(), ctx.env[ENV_LAUNCHER])) }
                val turnEnv = mapOf(ENV_SESSION_ID to sessionId.value, ENV_TURN_ID to turnId.value)
                // m2-16: Loop's MCP server, beside the settings, with the ids of the turn that spawns the process.
                val mcpConfig = ClaudeMcpConfig.json(ctx.env + turnEnv)?.let { dir.resolve(MCP_CONFIG_FILE).apply { writeText(it) } }
                val spec =
                    LaunchSpec(
                        argv = argv(settingsFile, mcpConfig, resume),
                        cwd = ctx.workspace,
                        environment =
                            agentEnvironment(ctx.env + turnEnv, removedEnvNames, hostEnv, resolveAddedEnv(addedEnv, ctx.workspace)),
                        stdinLines = stdin,
                    )
                process.job =
                    scope.launch {
                        try {
                            launchProcess(spec).collect { event ->
                                if (event is ProcessEvent.Started) process.pid = event.pid
                                if (process.keeps(event)) events.send(event)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            events.close(e)
                        } finally {
                            events.close()
                        }
                    }
                live = process
                return process
            }

            /**
             * Reads the process events until the turn ends: a result line, an exit, or a silence past a timeout.
             * Returns true, the turn still open, when the [reused] process of an earlier turn exited before it wrote
             * any line of this one: it died between turns, so the turn goes on in a new process.
             */
            private suspend fun read(
                process: LiveProcess,
                reused: Boolean,
            ): Boolean {
                var answered = false
                val written = TimeSource.Monotonic.markNow()
                var lastStdout = written
                var responded = false
                while (true) {
                    val untilSilence = settings.turnTimeout - lastStdout.elapsedNow()
                    val untilResponse = if (responded) Duration.INFINITE else settings.readTimeout - written.elapsedNow()
                    val next =
                        withTimeoutOrNull(minOf(untilSilence, untilResponse).coerceAtLeast(Duration.ZERO)) {
                            process.events.receiveCatching()
                        }
                    if (next == null) {
                        timedOut(process, if (untilResponse <= untilSilence) "response_timeout" else "turn_timeout")
                        return false
                    }
                    if (next.isClosed) {
                        next.exceptionOrNull()?.let { throw it }
                        if (reused && !answered) {
                            gone(process)
                            return true
                        }
                        failed(retire(process), RunnerEvent.TurnFailed(sessionId, turnId, null, "port_exit: no exit code, no result line"))
                        return false
                    }
                    when (val event = next.getOrThrow()) {
                        is ProcessEvent.Started -> Unit
                        is ProcessEvent.SpawnFailed -> {
                            failed(retire(process), RunnerEvent.TurnFailed(sessionId, turnId, null, agentNotFound(event.message)))
                            return false
                        }
                        is ProcessEvent.Line -> {
                            answered = true
                            if (event.stream == Stream.Stdout) {
                                lastStdout = TimeSource.Monotonic.markNow()
                                responded = true
                            }
                            val result = line(event, process, idle = false) ?: continue
                            // The turn is over, the process stays for the next one.
                            if (!process.idKnown) continuity.runtimeSessionId = null
                            // The usage was reported as each result line was read (bl-108); the result line decides the turn.
                            end(StreamJsonMapper.turnEvents(result.copy(usage = null), sessionId, turnId, exitCode = null, hidden))
                            return false
                        }
                        is ProcessEvent.Exited -> {
                            if (reused && !answered) {
                                gone(process)
                                return true
                            }
                            val cleanup = retire(process)
                            val notFound = execFailure?.takeIf { event.exitCode == EXIT_EXEC_FAILED }
                            val reason = notFound?.let(::agentNotFound) ?: "port_exit: exit code ${event.exitCode}, no result line"
                            failed(cleanup, RunnerEvent.TurnFailed(sessionId, turnId, event.exitCode, reason))
                            return false
                        }
                    }
                }
            }

            /**
             * Handles one line of [process]. Returns the `result` line that ends the turn; an [idle] line never ends it,
             * nor does a wake's result (`origin.kind` `task-notification`) unless the turn was interrupted (m4-03).
             * A `result` line's usage is added to the session's and reported at once, the session total, after the line.
             * An [idle] `init` or main session `assistant` line means the process runs a wake, until a `result` line; a
             * subagent's line does not (bl-122). The first `task_notification` line of a run an earlier turn started
             * makes this turn a wake turn, reported once, after the line (m4-03).
             */
            private suspend fun line(
                event: ProcessEvent.Line,
                process: LiveProcess,
                idle: Boolean,
            ): StreamLine.Result? {
                var estimate: RunnerEvent.UsageEstimate? = null
                var occupancy: RunnerEvent.ContextUsage? = null
                var usage: RunnerEvent.Usage? = null
                var result: StreamLine.Result? = null
                var woken: RunnerEvent.WakeTurn? = null
                if (event.stream == Stream.Stdout) {
                    when (val line = StreamJsonMapper.parse(event.text)) {
                        is StreamLine.Init -> {
                            if (idle) process.waking = true
                            line.model?.let { continuity.model = it }
                            line.runtimeSessionId?.let {
                                continuity.runtimeSessionId = it
                                process.idFromInit = true
                                process.idKnown = true
                            }
                            start(line.runtimeSessionId)
                        }
                        is StreamLine.Result -> {
                            process.waking = false
                            // Only when this process's init gave no id, the newest one wins.
                            if (!process.idFromInit) {
                                line.sessionId?.let {
                                    continuity.runtimeSessionId = it
                                    process.idKnown = true
                                }
                            }
                            line.usage?.let {
                                val total = continuity.usage.plus(it)
                                continuity.usage = total
                                // bl-108: reported at once, so a stall or a stop that cancels the turn later keeps this spend.
                                usage = StreamJsonMapper.usageEvent(total, sessionId, turnId)
                            }
                            if (!idle && (!line.wake || synchronized(inputLock) { interrupted })) result = line
                        }
                        is StreamLine.TaskStarted -> continuity.runs.putIfAbsent(line.run, ids.turn)
                        is StreamLine.TaskNotification -> {
                            val startedIn = continuity.runs[line.run]
                            if (!wake && startedIn != null && startedIn < ids.turn) {
                                wake = true
                                woken = RunnerEvent.WakeTurn(sessionId, turnId)
                            }
                        }
                        is StreamLine.Assistant -> {
                            if (idle && line.parentToolUseId == null) process.waking = true
                            val id = line.messageId
                            val usage = line.usage
                            if (!idle && id != null && usage != null && line.parentToolUseId == null) {
                                messageUsage[id] = usage
                                val total = messageUsage.values.fold(continuity.usage) { sum, part -> sum.plus(part) }
                                estimate = total?.let { ids.estimate(it) }
                            }
                            // m3-08: a main session message counts once, at its first line, idle or not.
                            if (usage != null && line.parentToolUseId == null && (id == null || continuity.countedMessages.add(id))) {
                                occupancy = ids.occupancy(usage, continuity.model?.let { "$PROVIDER/$it" })
                            }
                        }
                        else -> Unit
                    }
                } else if (event.text.startsWith(EXEC_FAILED)) {
                    execFailure = event.text.removePrefix(LOOP_PREFIX)
                }
                // The estimate, the occupancy or the usage follows the line it came from.
                emit(RunnerEvent.Output(event.stream, event.text))
                woken?.let { emit(it) }
                estimate?.let { emit(it) }
                occupancy?.let { emit(it) }
                usage?.let { emit(it) }
                return result
            }

            private suspend fun emit(event: RunnerEvent) {
                if (started) out.send(event) else held.add(event)
            }

            private suspend fun start(runtimeSessionId: String?) {
                if (started) return
                started = true
                out.send(ids.started(runtimeSessionId))
                held.forEach { out.send(it) }
                held.clear()
            }

            /** Ends a failed turn: the cleanup line, then [failure]. Its usage was reported as its result lines were read (bl-37, bl-108). */
            private suspend fun failed(
                cleanup: String?,
                failure: RunnerEvent.TurnFailed,
            ) {
                cleanup?.let { emit(RunnerEvent.Output(Stream.Stderr, it)) }
                end(listOf(failure))
            }

            // The only way out: the start event if not yet sent, then the terminal events. Nothing is emitted after them.
            private suspend fun end(terminal: List<RunnerEvent>) {
                start(null)
                terminal.forEach { out.send(it) }
                ended = true
            }
        }
    }

    /** The folders the settings deny reads and edits under: the holdout folder, by path and by real path. */
    private fun hiddenDirs(): List<Path> = settings.holdoutDir?.let { ClaudeSettingsFile.pathsOf(it) }.orEmpty()

    /**
     * The hidden folders as the turn's denials are matched against them: relative paths resolve against the
     * [workspace], the agent's cwd, and `~` against the agent's `HOME`.
     */
    private fun hiddenFolders(workspace: Path): HiddenFolders {
        val home = (hostEnv["HOME"] ?: System.getProperty("user.home"))?.let { Path.of(it) }
        return HiddenFolders(hiddenDirs(), cwd = workspace, home = home)
    }

    private fun argv(
        settingsFile: Path,
        mcpConfig: Path?,
        resume: String?,
    ): List<String> =
        listOf(settings.command, "-p") +
            (resume?.let { listOf("--resume", it) } ?: emptyList()) +
            listOf("--input-format", "stream-json", "--output-format", "stream-json", "--verbose") +
            listOf("--settings", settingsFile.toString(), "--permission-mode", "bypassPermissions") +
            // bl-72: alone, without a config, it loads no MCP server at all (Claude Code 2.1.319).
            (if (settings.strictMcp) listOf("--strict-mcp-config") else emptyList()) +
            (mcpConfig?.let { listOf("--mcp-config", it.toString()) } ?: emptyList()) +
            (settings.model?.let { listOf("--model", it) } ?: emptyList())

    /**
     * What a session carries across its turns and processes: Claude Code's newest session id, the usage so far, the
     * newest `init` line's model, the main session message ids whose occupancy was reported and the turn that read
     * each background task run's `task_started` line (m4-03).
     */
    private class Continuity {
        var runtimeSessionId: String? = null
        var usage: StreamLine.UsageTotals? = null
        var model: String? = null
        val countedMessages = mutableSetOf<String>()
        val runs = mutableMapOf<StreamLine.TaskRun, Int>()
    }

    /**
     * What the stop of a session read of a wake (m4-04): whether one is [running], a main session `init` or `assistant`
     * line read with no `result` line since, and the usage of its main session messages, one per message id as for the
     * turn's estimate, a line without an id counting on its own.
     */
    private class StopWake {
        var running = false
        private val byId = linkedMapOf<String, StreamLine.UsageTotals>()
        private val withoutId = mutableListOf<StreamLine.UsageTotals>()

        fun read(line: StreamLine.Assistant) {
            running = true
            val usage = line.usage ?: return
            val id = line.messageId
            if (id == null) withoutId += usage else byId[id] = usage
        }

        fun clear() {
            running = false
            byId.clear()
            withoutId.clear()
        }

        fun messages(): List<StreamLine.UsageTotals> = byId.values + withoutId
    }

    /**
     * One running process of a session: its launch collector [job] (cancelling it kills the group), its [events],
     * its open [stdin] and the [settingsDir] it reads, deleted once it is gone. While it is idle, between turns, its
     * [events] queue holds at most [idleLineBound] lines no turn needs (m4-03, bl-122), see [keeps].
     */
    private class LiveProcess(
        val events: Channel<ProcessEvent>,
        val stdin: Channel<String>,
        var settingsDir: Path?,
        private val idleLineBound: Int,
    ) {
        // Whether no turn reads it, the lines no turn needs queued since, and those dropped over the bound.
        private var idle = false
        private var idleLines = 0
        private var dropped = 0

        /**
         * Whether [event] goes to the queue. Always while a turn reads the process. While it is idle, every line a
         * later turn needs is kept: `result` lines (their usage), `init` lines, the `task_started` and
         * `task_notification` lines of background runs, main session `assistant` lines and Loop's own `loop:` lines.
         * Any other line, a subagent's message or a tool result, is kept up to [idleLineBound], then dropped.
         */
        @Synchronized
        fun keeps(event: ProcessEvent): Boolean {
            if (!idle || event !is ProcessEvent.Line || needed(event)) return true
            if (idleLines < idleLineBound) {
                idleLines++
                return true
            }
            dropped++
            return false
        }

        /** No turn reads the process from now on, until [markBusy]. */
        @Synchronized
        fun markIdle() {
            idle = true
            idleLines = 0
        }

        /** A turn reads the process from now on; returns how many lines were dropped while it was idle. */
        @Synchronized
        fun markBusy(): Int {
            idle = false
            idleLines = 0
            return dropped.also { dropped = 0 }
        }

        private fun needed(event: ProcessEvent.Line): Boolean =
            if (event.stream == Stream.Stdout) {
                when (val line = StreamJsonMapper.parse(event.text)) {
                    is StreamLine.Result, is StreamLine.Init, is StreamLine.TaskStarted, is StreamLine.TaskNotification -> true
                    is StreamLine.Assistant -> line.parentToolUseId == null
                    else -> false
                }
            } else {
                event.text.startsWith(LOOP_PREFIX)
            }

        var job: Job? = null

        @Volatile var pid: Long? = null

        /** True once this process's init gave a session id. */
        var idFromInit = false

        /** True once this process gave a session id, by its init or a result line. */
        var idKnown = false

        /** True while it runs a wake: an `init` or `assistant` line came while it was idle, no `result` line since. */
        var waking = false

        /** Deletes the settings folder, once; returns the line to report when it could not be deleted. */
        fun deleteSettings(): String? {
            val dir = settingsDir ?: return null
            settingsDir = null
            return if (dir.toFile().deleteRecursively()) null else "${LOOP_PREFIX}could not delete the claude-code settings folder $dir"
        }
    }

    private data class Ids(
        val sessionId: SessionId,
        val threadId: String,
        val turnId: TurnId,
        val turn: Int,
    ) {
        /** The session's estimated [totals] during this turn. */
        fun estimate(totals: StreamLine.UsageTotals): RunnerEvent.UsageEstimate =
            RunnerEvent.UsageEstimate(
                sessionId,
                turnId,
                totals.inputTokens,
                totals.outputTokens,
                totals.cacheReadInputTokens,
                totals.cacheCreationInputTokens,
            )

        /** The occupancy of one main session message with [usage]: the input size of its request, cached tokens included. */
        fun occupancy(
            usage: StreamLine.UsageTotals,
            model: String?,
        ): RunnerEvent.ContextUsage =
            RunnerEvent.ContextUsage(
                sessionId,
                turnId,
                occupancyTokens = usage.inputTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens,
                model = model,
            )

        /** Turn 1 starts the thread, later turns continue it. */
        fun started(runtimeSessionId: String?): RunnerEvent =
            if (turn == 1) {
                RunnerEvent.SessionStarted(sessionId, threadId, turnId, runtimeSessionId)
            } else {
                RunnerEvent.TurnStarted(sessionId, threadId, turnId, turn)
            }
    }

    private fun RunnerEvent.isTerminal(): Boolean =
        this is RunnerEvent.TurnCompleted || this is RunnerEvent.TurnFailed || this is RunnerEvent.TurnInputRequired

    /**
     * The tokens of [other] added to these. The cost is not added: Claude Code's `total_cost_usd` is already the
     * session's so far, across the result lines of one process and across `--resume` processes alike, so the latest
     * one wins and an earlier one stays only when [other] has none (bl-25).
     */
    private fun StreamLine.UsageTotals?.plus(other: StreamLine.UsageTotals): StreamLine.UsageTotals =
        if (this == null) {
            other
        } else {
            StreamLine.UsageTotals(
                inputTokens = inputTokens + other.inputTokens,
                outputTokens = outputTokens + other.outputTokens,
                cacheReadInputTokens = cacheReadInputTokens + other.cacheReadInputTokens,
                cacheCreationInputTokens = cacheCreationInputTokens + other.cacheCreationInputTokens,
                totalCostUsd = other.totalCostUsd ?: totalCostUsd,
            )
        }

    private fun agentNotFound(message: String): String = "agent_not_found: ${settings.command}: ${message.trimEnd('.')}"

    private companion object {
        const val SETTINGS_DIR_PREFIX = "loop-claude-settings"
        const val SETTINGS_FILE = "settings.json"
        const val MCP_CONFIG_FILE = "mcp.json"
        const val LOOP_PREFIX = "loop: "
        const val EXEC_FAILED = "${LOOP_PREFIX}exec failed"
        const val EXIT_EXEC_FAILED = 127
        const val ENV_SESSION_ID = "LOOP_SESSION_ID"
        const val ENV_TURN_ID = "LOOP_TURN_ID"
        const val ENV_LAUNCHER = "LOOP_LAUNCHER"
        const val SIGNAL_PROBE = "0"

        /** The provider of the models Claude Code names, the first part of a `context.windows` key (m3-08). */
        const val PROVIDER = "anthropic"

        /** m3-12: why a turn interrupted before its input was written ends; Loop does not count it as a failure. */
        const val INTERRUPTED_BEFORE_INPUT = "interrupted: the turn was interrupted before its input was written"

        /** How many lines no turn needs an idle process may queue before the next ones are dropped (m4-03, bl-122). */
        const val IDLE_LINE_BOUND = 1_000

        /** How long [AgentSession.stop] waits for the process to exit once its stdin is closed. */
        val STOP_GRACE = 5.seconds

        /** The launcher's lines that say a process group could not be killed, see ProcessGroupLauncher. */
        val GROUP_PROBLEMS = listOf("${LOOP_PREFIX}could not signal process group", "${LOOP_PREFIX}processes still alive after SIGKILL")

        val CAPABILITIES =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Mcp,
                usage = true,
                cost = true,
                occupancy = true,
                sandbox = false,
            )
    }
}
