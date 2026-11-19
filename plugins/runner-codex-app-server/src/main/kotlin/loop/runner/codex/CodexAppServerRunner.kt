package loop.runner.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
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
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.v2.TurnCompletedNotification
import loop.runner.codex.protocol.v2.TurnStatus
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import loop.runner.process.agentEnvironment
import loop.runner.process.resolveAddedEnv
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The settings of the Codex runner, from `runners.codex` ([of], m3-17): [command] is split into [argv], its words, and
 * run with no shell (m3-20). [readTimeout] (`read_timeout_ms`) is how long the runner waits for the answer to each
 * request it writes, [turnTimeout] (`turn_timeout_ms`) how long a started turn may go without a protocol message
 * (m3-19, spec 10.6). [strictMcp] (`strict_mcp`, m3-20 after bl-72) starts every thread with each MCP server of the
 * Codex config disabled and the `apps` feature off, so the agent gets no MCP server. [holdoutDir] is Loop's holdout
 * folder (m2-14), given by the host through plugin loading, whose reads and edits the approval rules refuse (m3-20);
 * null refuses none.
 */
data class CodexAppServerSettings(
    val command: String = DEFAULT_COMMAND,
    val turnTimeout: Duration = 1.hours,
    val readTimeout: Duration = 5.seconds,
    val strictMcp: Boolean = true,
    val holdoutDir: Path? = null,
) {
    /** [command] split into the words the runner launches, see [CommandWords]; throws [RunnerConfigException]. */
    val argv: List<String> get() = CommandWords.of(command, "runners.$SECTION.command")

    companion object {
        /** The `runner.kind` of the Codex runner. */
        const val KIND = "codex-app-server"

        /** The key of its section under `runners`, the spec's `codex` object (5.3.6). */
        const val SECTION = "codex"

        /** The spec's default `codex.command` (5.3.6). */
        const val DEFAULT_COMMAND = "codex app-server"

        /** The longest `turn_timeout_ms` and `read_timeout_ms`, one day, as for the claude-code runner. */
        const val MAX_TIMEOUT_MS: Long = 86_400_000

        /**
         * The profile of `runners.codex`: `command` (m3-17), the two timeouts with the spec's defaults (5.3.6) and
         * their limits (m3-19), and `strict_mcp`, true by default as for the claude-code runner (bl-72, m3-20). No
         * secret.
         */
        val PROFILE: PluginProfile =
            CodexAppServerSettings().let { defaults ->
                PluginProfile(
                    keys =
                        listOf(
                            ProfileKey("command", ProfileType.STRING, default = DEFAULT_COMMAND),
                            ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "${defaults.turnTimeout.inWholeMilliseconds}"),
                            ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "${defaults.readTimeout.inWholeMilliseconds}"),
                            ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "${defaults.strictMcp}"),
                        ),
                    limits = mapOf("turn_timeout_ms" to 1L..MAX_TIMEOUT_MS, "read_timeout_ms" to 1L..MAX_TIMEOUT_MS),
                )
            }

        /**
         * Reads the section `runners.codex` as written: `command`, a string, [DEFAULT_COMMAND] when absent, null or
         * blank, as the profile check treats a blank value, refused when [CommandWords] cannot split it;
         * `turn_timeout_ms` and `read_timeout_ms`, positive integers, quoted or not, their defaults when absent, null or
         * blank; `strict_mcp`, `true` or `false` in any case, bare or quoted, true when absent, null or blank. Throws [RunnerConfigException] naming the key. [holdoutDir] is the
         * host's holdout folder.
         */
        fun of(
            config: Map<String, Any?>,
            holdoutDir: Path? = null,
        ): CodexAppServerSettings {
            val defaults = CodexAppServerSettings()
            val command =
                when (val value = config["command"]) {
                    null -> DEFAULT_COMMAND
                    is String -> value.takeIf { it.isNotBlank() } ?: DEFAULT_COMMAND
                    else -> throw RunnerConfigException("runners.$SECTION.command must be a string")
                }
            CommandWords.of(command, "runners.$SECTION.command")
            return CodexAppServerSettings(
                command = command,
                turnTimeout = config.millis("turn_timeout_ms") ?: defaults.turnTimeout,
                readTimeout = config.millis("read_timeout_ms") ?: defaults.readTimeout,
                strictMcp = config.boolean("strict_mcp") ?: defaults.strictMcp,
                holdoutDir = holdoutDir,
            )
        }

        private fun Map<String, Any?>.boolean(key: String): Boolean? {
            val value = get(key)?.takeUnless { it is String && it.isBlank() } ?: return null
            return ProfileType.booleanOf(value) ?: throw RunnerConfigException("runners.$SECTION.$key must be a boolean")
        }

        private fun Map<String, Any?>.millis(key: String): Duration? {
            val text =
                when (val value = get(key)) {
                    null -> return null
                    is String -> value.takeIf { it.isNotBlank() }?.trim() ?: return null
                    is Int, is Long -> value.toString()
                    else -> null
                }
            return text?.toLongOrNull()?.takeIf { it > 0 }?.milliseconds
                ?: throw RunnerConfigException("runners.$SECTION.$key must be a positive integer")
        }
    }
}

/**
 * Runs the Codex app server (spec 10.1, m3-17): [CodexAppServerSettings.argv], the words of the command, in the
 * attempt's workspace, in its own process group through [ProcessGroupLauncher], with the agent env of the shared
 * policy, starts the session on it (m3-18) and runs its turns on one thread (m3-19). No shell runs it (m3-20): a login
 * shell, the spec's `bash -lc`, would read the user's profile after the env policy, and a profile export would bring
 * back a name the policy removed.
 *
 * Sessions (m3-19, spec 10.2 and 10.3): [openSession] gives a session that runs several turns on one long lived app
 * server and one thread; [run] is a session of one turn, stopped once the turn ended. Turn 1 starts the app server
 * and the session; each later turn writes one more `turn/start` on the thread of turn 1, with its input as the only
 * text, and keeps the thread id. Stdin stays open across turns. Between turns the app server is idle and no timer runs
 * on it: what it writes then is read by the next turn, right after that turn's start event. A turn whose app server is
 * gone (it exited, or an earlier turn killed it) fails at once with `port_exit`, after its `TurnStarted`: no new app
 * server is started for it.
 *
 * Streams: stdout is the protocol stream. Each stdout line is kept as raw [RunnerEvent.Output] and read with
 * [ProtocolFraming], one JSON-RPC message per line; a line split across reads is whole once its line break arrives,
 * since the launcher reads lines. A line that is not a protocol message is logged as a `loop:` line on stderr naming
 * the framing's fixed reason, never the line, and reading goes on: a bad line never ends the turn. Stderr is kept
 * apart, as diagnostics: each stderr line is a raw `Output` on stderr and never reaches the framing. The client's
 * messages go to stdin, one per line.
 *
 * Startup ([StartupHandshake], spec 10.2): `initialize` with Loop's identity, `initialized`, `thread/start` and
 * `turn/start` with the prompt, each request written once the answer before it arrived, with the approval and sandbox
 * settings of `docs/spec-decisions.md`. An error answer, an answer to another request or an answer that is not its
 * method's response type fails the start with the [StartupFailure]'s `response_error` reason, and the process group
 * is killed at once. A continuation turn's `turn/start` is read the same way.
 *
 * Server requests (m3-20, spec 10.5): every request the app server sends, an approval, a user input request or a
 * dynamic tool call, is answered at once by [ServerRequestPolicy], which declines `git push`, `gh`, history rewrites
 * and the [CodexAppServerSettings.holdoutDir] folder; each answer adds a `loop:` line on stderr. A user input request
 * is not answered: the turn ends with [RunnerEvent.TurnInputRequired] and the process group is killed.
 *
 * Loop's tools (m3-22, 17.5 items 15 and 16): when the attempt env has `LOOP_LAUNCHER` and `LOOP_TOOL_CONFIG`, the
 * thread declares `submit_result`, `move` and `comment` as client tools, built by [LoopClientTools] from the tool
 * config snapshot, so they follow the workflow's tracker. A call of one is run by [LoopToolCall] through
 * `loop tool mcp`, the same queue and drain as the Claude Code runner's MCP tools, and its reply, or a structured
 * failure, answers the call; the turn goes on either way. Reading waits for the call, so its time does not count
 * against the turn timeout.
 *
 * Telemetry (m3-21, 17.5 items 13 and 14): [Telemetry] reads `thread/tokenUsage/updated` into
 * [RunnerEvent.Usage], absolute for the session and with no cost, and `account/rateLimits/updated` into
 * [RunnerEvent.RateLimit], each emitted only when it changed.
 *
 * Occupancy (m3-23, plan 8.3): the same `thread/tokenUsage/updated` of the session's thread also gives
 * [RunnerEvent.ContextUsage], the input of its `last` request, never its cumulative `total`, with the model key
 * `<modelProvider>/<model>` of the `thread/start` answer, which Loop looks up in `context.windows`. [AgentSession.interrupt]
 * writes one `turn/interrupt` for the turn that runs, or at its start when it is asked before Codex named the turn;
 * Codex then reports the turn's last usage and ends it with `turn/completed` `interrupted`. A rotation (m3-11, m3-12) is
 * the orchestrator's: it stops the session, which ends its app server, and opens a new one, which starts a new app
 * server and a new thread.
 *
 * Turn end (spec 10.3): the `turn/completed` notification of the current turn ends it: `completed` with
 * [RunnerEvent.TurnCompleted] and exit code 0, since the app server still runs, `failed` with `turn_failed` and
 * `interrupted` with `turn_cancelled`, each followed by the turn error's message when there is one. A `turn/completed`
 * that does not match the pinned schema fails the turn with `response_error` and kills the group.
 *
 * Timeouts (m3-19, spec 10.6): each request the client writes gets [CodexAppServerSettings.readTimeout] for its answer,
 * else `response_timeout: no <method> response within <n> ms`; a started turn gets
 * [CodexAppServerSettings.turnTimeout] between two protocol messages, else `turn_timeout: no protocol message for <n>
 * ms`. Stderr lines and lines that are not protocol messages do not count. Both kill the process group, so the exit
 * code is null.
 *
 * Ids (spec 10.2): [RunnerEvent.SessionStarted] carries the thread id of the `thread/start` answer and the turn id of
 * the `turn/start` answer, and the session id `<thread_id>-<turn_id>`. It comes first: what was read before it is
 * held and emitted right after it, in order. A run that ends before the turn started emits it with ids Loop minted,
 * two UUIDs, right before the terminal event. Turns 2..n emit [RunnerEvent.TurnStarted] the same way, with the thread
 * id of turn 1 and the turn id of their own answer, or one Loop minted. Exactly one terminal event comes last.
 *
 * Strict MCP (m3-20, bl-72): with [CodexAppServerSettings.strictMcp] the startup reads the Codex config with
 * `config/read` and starts the thread with each MCP server it lists disabled and the `apps` feature off.
 *
 * Failures: `invalid_workspace_cwd` (the workspace is not a directory, nothing is started), `agent_not_found` (the
 * launcher could not spawn, or it exited 127, the command could not be run, before any stdout line), `response_error`,
 * `response_timeout`, `turn_timeout`, `turn_failed`, `turn_cancelled`, `port_exit: exit code N, before the <method>
 * response` (the server exited while a request waited for its answer), `port_exit: exit code N, no turn result` (it
 * exited during the turn) and `port_exit: the app server of this session is gone, ...`. Cancelling the collection
 * kills the process group.
 *
 * Env: [agentEnvironment], [hostEnv] minus [removedEnvNames], plus [addedEnv] (`agent.env`, each `{workspace}`
 * replaced by the workspace), plus the context env, always minus `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN`.
 *
 * Stop: stdin is closed, the app server gets [stopGrace] to exit, then its group is killed.
 */
class CodexAppServerRunner internal constructor(
    internal val settings: CodexAppServerSettings,
    private val hostEnv: Map<String, String>,
    private val removedEnvNames: Set<String>,
    private val addedEnv: Map<String, String>,
    private val launchProcess: (LaunchSpec) -> Flow<ProcessEvent>,
    /** Receives every frame read from stdout, in order; a seam for tests. */
    private val onFrame: (Frame) -> Unit = {},
    /** Sent as `clientInfo.version`. */
    private val loopVersion: String = LoopVersion.current(),
    /** Runs the reading of the app server, which outlives a turn; a seam for tests on virtual time. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Measures the timeouts; a seam for tests on virtual time. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val stopGrace: Duration = STOP_GRACE,
) : AgentRunner {
    constructor(
        settings: CodexAppServerSettings,
        hostEnv: Map<String, String> = System.getenv(),
        removedEnvNames: Set<String> = emptySet(),
        addedEnv: Map<String, String> = emptyMap(),
    ) : this(settings, hostEnv, removedEnvNames, addedEnv, ProcessGroupLauncher()::launch)

    /**
     * Continuation on one thread (m3-19); Loop's tools as client tools the runner declares to the server, the Codex
     * way (plan 6.3, m3-22); usage from `thread/tokenUsage/updated` (m3-21); no cost, which Codex does not report, so dollar
     * budgets do not hold for this runner; occupancy from the `last` usage of the session's thread (m3-23); no sandbox,
     * since the thread runs with `danger-full-access`.
     */
    override val capabilities: RunnerCapabilities = CAPABILITIES

    override fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent> =
        flow {
            val session = CodexSession(ctx)
            try {
                emitAll(session.runTurn(prompt, 1))
            } finally {
                session.stop()
            }
        }

    override fun openSession(ctx: AttemptContext): AgentSession = CodexSession(ctx)

    /** The app server of a session while it runs: what it wrote, not read yet, and its stdin. */
    private class LiveProcess(
        val events: Channel<ProcessEvent>,
        val stdin: Channel<String>,
    ) {
        lateinit var job: Job
        var stdoutLines = 0
    }

    /** One thread on one long lived app server, its turns run one after the other. */
    private inner class CodexSession(
        private val ctx: AttemptContext,
    ) : AgentSession {
        // The reading of the app server lives here, not in a turn's flow, so the app server outlives the turn.
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        // Loop's tools, declared to the thread when the attempt env gives the launcher and the tool config (m3-22).
        private val loopTools = LoopClientTools.load(ctx.env)
        private val ready = loopTools as? LoopClientTools.Loaded.Ready
        private val toolCall = ready?.let { LoopToolCall(it.launcher, loopVersion) }
        private val handshake = StartupHandshake(ctx.workspace, loopVersion, settings.strictMcp, ready?.tools?.specs)
        private val requests =
            ServerRequestPolicy(
                ctx.workspace,
                settings.holdoutDir?.let { HoldoutFolder(it, hostEnv["HOME"]?.let(Path::of)) },
                if (ready != null) LoopClientTools.NAMES else emptyList(),
            )

        // Usage and rate limits of the whole session, kept across turns, so a later turn's usage replaces the earlier (m3-21).
        private val telemetry = Telemetry()

        // Loop's own thread id, kept only while Codex has not named its thread.
        private var threadId = UUID.randomUUID().toString()
        private var threadKnown = false

        @Volatile private var live: LiveProcess? = null

        // The turn being collected, the one an interrupt ends (m3-23).
        @Volatile private var running: Turn? = null

        override fun runTurn(
            input: String,
            turn: Int,
        ): Flow<RunnerEvent> = flow { Turn(this, turn).run(input) }

        override fun interrupt() {
            running?.interrupt()
        }

        override suspend fun stop() {
            withContext(NonCancellable) {
                val process = live
                live = null
                if (process != null) {
                    process.stdin.close()
                    // A real app server ends once its stdin closes; the launcher then cleans its group up.
                    withTimeoutOrNull(stopGrace) { while (!process.events.receiveCatching().isClosed) continue }
                    process.job.cancelAndJoin()
                }
                scope.cancel()
            }
        }

        /** Starts the app server; its events are read by the turns. */
        private fun spawn(): LiveProcess {
            val process = LiveProcess(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
            val spec =
                LaunchSpec(
                    argv = settings.argv,
                    cwd = ctx.workspace,
                    environment = agentEnv(),
                    stdinLines = process.stdin,
                )
            process.job =
                scope.launch {
                    try {
                        launchProcess(spec).collect { process.events.send(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        process.events.close(e)
                    } finally {
                        process.events.close()
                    }
                }
            live = process
            return process
        }

        /** The env of the app server, and of `loop tool mcp` for a client tool call, see [CodexAppServerRunner]. */
        private fun agentEnv(): Map<String, String> =
            agentEnvironment(ctx.env, removedEnvNames, hostEnv, resolveAddedEnv(addedEnv, ctx.workspace))

        /** Kills the group of [process] at once; nothing more is read from it. */
        private suspend fun kill(process: LiveProcess) {
            withContext(NonCancellable) {
                if (live === process) live = null
                process.stdin.close()
                process.job.cancelAndJoin()
            }
        }

        /** One turn, from its input to its terminal event, reported to [out]. */
        private inner class Turn(
            private val out: FlowCollector<RunnerEvent>,
            private val turn: Int,
        ) {
            // Loop's own turn id, kept only when the turn ends before Codex named it.
            private var turnId = TurnId(UUID.randomUUID().toString())
            private var started = false
            private var ended = false

            // What was read before the start event, built at the start, so a telemetry event gets the turn's real ids.
            private val held = mutableListOf<() -> RunnerEvent>()

            private val sessionId get() = SessionId("$threadId-${turnId.value}")

            // m3-23: whether an interrupt was asked, and the app server it goes to once Codex named the turn. The lock
            // keeps the request to one, whichever of the two comes first.
            private val interruptLock = Any()
            private var interruptAsked = false
            private var interruptible: LiveProcess? = null

            /** Asks Codex to end this turn with `turn/interrupt`, once, at once when the turn started, else at its start. */
            fun interrupt() {
                synchronized(interruptLock) {
                    if (interruptAsked) return
                    interruptAsked = true
                    interruptible?.let(::sendInterrupt)
                }
            }

            /** The turn started on [process]: an interrupt asked before goes now, a later one at once. */
            private fun allowInterrupt(process: LiveProcess) {
                synchronized(interruptLock) {
                    interruptible = process
                    if (interruptAsked) sendInterrupt(process)
                }
            }

            private fun sendInterrupt(process: LiveProcess) {
                process.stdin.trySend(handshake.interrupt(threadId, turnId.value))
            }

            suspend fun run(input: String) {
                running = this
                try {
                    if (turn == 1) {
                        if (!ctx.workspace.isDirectory()) return fail(null, "invalid_workspace_cwd: ${ctx.workspace}")
                        if (loopTools is LoopClientTools.Loaded.Unreadable) {
                            out(
                                RunnerEvent.Output(
                                    Stream.Stderr,
                                    "${LOOP_PREFIX}the thread declares no Loop tool: the attempt's tool config snapshot " +
                                        "cannot be read (${loopTools.reason})",
                                ),
                            )
                        }
                        val process = spawn()
                        process.stdin.send(handshake.start(input))
                        read(process)
                    } else {
                        val process = live
                        if (process == null || !threadKnown || handshake.pendingMethod != null) {
                            return fail(
                                null,
                                "port_exit: the app server of this session is gone, turn $turn cannot start on thread $threadId",
                            )
                        }
                        process.stdin.send(handshake.continueOn(threadId, input))
                        read(process)
                    }
                } finally {
                    if (running === this) running = null
                    // A cancelled turn, or one that threw: nothing it started outlives it.
                    if (!ended) live?.let { kill(it) }
                }
            }

            /** Reads the app server until the turn ends: its end, a failure, an exit or a timeout. */
            private suspend fun read(process: LiveProcess) {
                // Since the latest request was written, or, once the turn started, since the latest protocol message.
                var since = timeSource.markNow()
                while (true) {
                    val awaited = handshake.pendingMethod
                    val limit = if (awaited != null) settings.readTimeout else settings.turnTimeout
                    val next =
                        withTimeoutOrNull((limit - since.elapsedNow()).coerceAtLeast(Duration.ZERO)) {
                            process.events.receiveCatching()
                        }
                    if (next == null) {
                        val reason =
                            if (awaited != null) {
                                "response_timeout: no $awaited response within ${limit.inWholeMilliseconds} ms"
                            } else {
                                "turn_timeout: no protocol message for ${limit.inWholeMilliseconds} ms"
                            }
                        return killAndFail(process, reason)
                    }
                    if (next.isClosed) {
                        if (live === process) live = null
                        next.exceptionOrNull()?.let { throw it }
                        return fail(null, "port_exit: no exit code, no turn result")
                    }
                    when (val event = next.getOrThrow()) {
                        is ProcessEvent.Started -> Unit
                        is ProcessEvent.SpawnFailed -> {
                            if (live === process) live = null
                            return fail(null, agentNotFound(event.message))
                        }
                        is ProcessEvent.Exited -> {
                            if (live === process) live = null
                            val reason =
                                when {
                                    event.exitCode == EXIT_COMMAND_NOT_FOUND && process.stdoutLines == 0 ->
                                        agentNotFound("the launcher exited $EXIT_COMMAND_NOT_FOUND, the command could not be run")
                                    handshake.pendingMethod != null ->
                                        "port_exit: exit code ${event.exitCode}, before the ${handshake.pendingMethod} response"
                                    else -> "port_exit: exit code ${event.exitCode}, no turn result"
                                }
                            return fail(event.exitCode, reason)
                        }
                        is ProcessEvent.Line -> {
                            out(RunnerEvent.Output(event.stream, event.text))
                            if (event.stream != Stream.Stdout) continue
                            process.stdoutLines++
                            val frame = frame(event.text)
                            if (frame is Frame.Malformed) {
                                out(malformed(frame))
                                continue
                            }
                            if (handshake.pendingMethod == null) since = timeSource.markNow()
                            if (frame is Frame.Notification) {
                                requests.observe(frame)
                                telemetry.read(frame).forEach { signal -> out { signal.event(sessionId, turnId) } }
                            }
                            if (frame is Frame.Request) {
                                when (val answer = requests.answer(frame)) {
                                    is ServerRequestPolicy.Answer.Write -> {
                                        process.stdin.send(answer.line)
                                        out(RunnerEvent.Output(Stream.Stderr, answer.note))
                                    }
                                    is ServerRequestPolicy.Answer.InputRequired ->
                                        return killAndEnd(process, RunnerEvent.TurnInputRequired(sessionId, turnId, answer.reason))
                                    is ServerRequestPolicy.Answer.CallTool -> {
                                        callTool(process, answer)
                                        // Loop's own call is not the app server's silence.
                                        since = timeSource.markNow()
                                    }
                                }
                            }
                            when (val step = handshake.read(frame)) {
                                is StartupHandshake.Step.Write ->
                                    if (step.lines.isNotEmpty()) {
                                        step.lines.forEach { process.stdin.send(it) }
                                        since = timeSource.markNow()
                                    }
                                is StartupHandshake.Step.Started -> {
                                    threadId = step.threadId
                                    threadKnown = true
                                    turnId = TurnId(step.turnId)
                                    telemetry.startTurn(step.threadId, handshake.modelProvider, handshake.model)
                                    allowInterrupt(process)
                                    start()
                                    since = timeSource.markNow()
                                }
                                is StartupHandshake.Step.Failed -> return killAndFail(process, step.failure.reason)
                            }
                            if (started && handshake.pendingMethod == null && turnEnded(process, frame)) return
                        }
                    }
                }
            }

            /**
             * Runs the client tool call [call] through `loop tool mcp` (m3-22), with the agent env and the turn's
             * `LOOP_SESSION_ID` and `LOOP_TURN_ID`, as the Claude Code runner passes them to its MCP server, and writes
             * its reply as the answer. The note never quotes the arguments or the reply.
             */
            private suspend fun callTool(
                process: LiveProcess,
                call: ServerRequestPolicy.Answer.CallTool,
            ) {
                val runner = checkNotNull(toolCall) { "a tool call is answered only when the thread declared Loop's tools" }
                val env = agentEnv() + mapOf(ENV_SESSION_ID to sessionId.value, ENV_TURN_ID to turnId.value)
                val reply = runner.call(call.tool, call.arguments, ctx.workspace, env)
                process.stdin.send(requests.toolResult(call.id, reply.success, reply.text))
                val outcome = if (reply.success) "" else ", refused"
                out(
                    RunnerEvent.Output(
                        Stream.Stderr,
                        "${LOOP_PREFIX}ran the Loop tool ${call.tool} for the tool call request ${call.id}$outcome",
                    ),
                )
            }

            /**
             * Ends the turn when [frame] is the `turn/completed` notification of this turn; true when it did. Another
             * turn's completion is passed over.
             */
            private suspend fun turnEnded(
                process: LiveProcess,
                frame: Frame,
            ): Boolean {
                if (frame !is Frame.Notification || frame.message.method != TURN_COMPLETED) return false
                val completed = frame.message.params?.let(::turnCompleted)
                if (completed == null) {
                    killAndFail(process, "response_error: the $TURN_COMPLETED notification does not match the pinned schema")
                    return true
                }
                if (completed.turn.id != turnId.value) return false
                val detail =
                    completed.turn.error
                        ?.message
                        ?.let { ": $it" } ?: ""
                when (completed.turn.status) {
                    TurnStatus.COMPLETED -> end(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                    TurnStatus.FAILED -> fail(null, "turn_failed$detail")
                    TurnStatus.INTERRUPTED -> fail(null, "turn_cancelled$detail")
                    TurnStatus.IN_PROGRESS ->
                        killAndFail(process, "response_error: the $TURN_COMPLETED notification says the turn is still in progress")
                }
                return true
            }

            /** Emits the turn's start event, then what was held before it. */
            private suspend fun start() {
                started = true
                val event =
                    if (turn == 1) {
                        RunnerEvent.SessionStarted(sessionId, threadId, turnId)
                    } else {
                        RunnerEvent.TurnStarted(sessionId, threadId, turnId, turn)
                    }
                out.emit(event)
                held.forEach { out.emit(it()) }
                held.clear()
            }

            private suspend fun out(event: RunnerEvent) = out { event }

            private suspend fun out(event: () -> RunnerEvent) = if (started) out.emit(event()) else held += event

            private suspend fun end(event: RunnerEvent) {
                if (!started) start()
                ended = true
                out.emit(event)
            }

            private suspend fun fail(
                exitCode: Int?,
                reason: String,
            ) = end(RunnerEvent.TurnFailed(sessionId, turnId, exitCode, reason))

            /** Kills the group, keeps the lines read before the kill, and fails the turn with [reason]. */
            private suspend fun killAndFail(
                process: LiveProcess,
                reason: String,
            ) = killAndEnd(process, RunnerEvent.TurnFailed(sessionId, turnId, null, reason))

            /** Kills the group, keeps the lines read before the kill, and ends the turn with [event]. */
            private suspend fun killAndEnd(
                process: LiveProcess,
                event: RunnerEvent,
            ) {
                kill(process)
                while (true) {
                    val queued = process.events.tryReceive().getOrNull() ?: break
                    if (queued is ProcessEvent.Line) out(RunnerEvent.Output(queued.stream, queued.text))
                }
                end(event)
            }
        }
    }

    /** The params of a `turn/completed` notification, null when they are not its type in the pinned schema. */
    private fun turnCompleted(params: JsonElement): TurnCompletedNotification? =
        try {
            CodexJson.format.decodeFromJsonElement(TurnCompletedNotification.serializer(), params)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is a wrong JSON type for a member.
            null
        }

    /** Frames one stdout [line] and hands the frame to [onFrame]. */
    private fun frame(line: String): Frame = ProtocolFraming.decode(line).also(onFrame)

    /** The log line of a stdout line that is not a protocol message: its reason, never the line. */
    private fun malformed(frame: Frame.Malformed): RunnerEvent.Output =
        RunnerEvent.Output(Stream.Stderr, "${LOOP_PREFIX}an app server stdout line is not a protocol message (${frame.reason}), skipped")

    private fun agentNotFound(message: String): String = "agent_not_found: ${settings.command}: $message"

    private companion object {
        const val LOOP_PREFIX = "loop: "

        /** The env names of the turn's ids, which `loop tool` records with a request, as for the Claude Code runner. */
        const val ENV_SESSION_ID = "LOOP_SESSION_ID"
        const val ENV_TURN_ID = "LOOP_TURN_ID"

        /** The notification that ends a turn. */
        const val TURN_COMPLETED = "turn/completed"

        /** What the launcher exits with when it cannot exec the command, see ProcessGroupLauncher. */
        const val EXIT_COMMAND_NOT_FOUND = 127

        /** How long a stopped app server has to exit once its stdin closed, before its group is killed. */
        val STOP_GRACE = 5.seconds

        val CAPABILITIES =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.ClientTools,
                usage = true,
                cost = false,
                occupancy = true,
                sandbox = false,
            )
    }
}
