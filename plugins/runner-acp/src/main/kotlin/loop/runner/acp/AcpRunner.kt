package loop.runner.acp

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.process.HoldoutFolder
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import loop.runner.process.ProtectedWorkspaceRoots
import loop.runner.process.agentEnvironment
import loop.runner.process.resolveAddedEnv
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs an Agent Client Protocol (ACP) agent over JSON-RPC 2.0 on stdio, one long lived process per
 * session, launched in its own process group through [ProcessGroupLauncher] with no shell.
 *
 * Under `preset: agy`, strips Google API keys from the inherited host environment by default (an
 * operator can still opt in through `agent.env`), enforces the pinned `agy` major version `1` on
 * `initialize`, pins `runners.acp.model` with `session/set_model`, and escapes leading `/` prompts.
 */
class AcpRunner internal constructor(
    internal val settings: AcpSettings,
    private val hostEnv: Map<String, String>,
    private val removedEnvNames: Set<String>,
    private val addedEnv: Map<String, String>,
    private val launchProcess: (LaunchSpec) -> Flow<ProcessEvent>,
    /** Receives every decoded stdout frame, in order; a seam for tests. */
    private val onFrame: (AcpFrame) -> Unit = {},
    /** Sent as `clientInfo.version` in `initialize`. */
    private val loopVersion: String = LoopVersion.current(),
    /** Runs background process collection across turns; a seam for virtual time tests. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Measures read and turn timeouts; a seam for virtual time tests. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val stopGrace: Duration = STOP_GRACE,
) : AgentRunner {
    constructor(
        settings: AcpSettings,
        hostEnv: Map<String, String> = System.getenv(),
        removedEnvNames: Set<String> = emptySet(),
        addedEnv: Map<String, String> = emptyMap(),
    ) : this(
        settings = settings,
        hostEnv = hostEnv,
        removedEnvNames = removedEnvNames,
        addedEnv = addedEnv,
        launchProcess = ProcessGroupLauncher(maxLineBytes = AcpFraming.MAX_LINE_BYTES)::launch,
    )

    override val capabilities: RunnerCapabilities = CAPABILITIES

    override val model: String? get() = settings.model

    override fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent> =
        flow {
            val session = AcpSession(ctx)
            try {
                emitAll(session.runTurn(prompt, 1))
            } finally {
                session.stop()
            }
        }

    override fun openSession(ctx: AttemptContext): AgentSession = AcpSession(ctx)

    override fun resumeSession(
        ctx: AttemptContext,
        runtimeSessionId: String,
    ): AgentSession = AcpSession(ctx, resumeFrom = runtimeSessionId)

    private class LiveProcess(
        val events: Channel<ProcessEvent>,
        val stdin: Channel<String>,
    ) {
        lateinit var job: Job
        var stdoutLines: Int = 0
    }

    private data class TurnIds(
        val sessionId: SessionId,
        val turnId: TurnId,
    )

    /** One ACP session on one long lived process, running turns 1..n sequentially. */
    private inner class AcpSession(
        private val ctx: AttemptContext,
        private val resumeFrom: String? = null,
    ) : AgentSession {
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val threadId = UUID.randomUUID().toString()
        private val handshake =
            AcpHandshake(
                workspace = ctx.workspace,
                loopVersion = loopVersion,
                preset = settings.preset,
                configuredModel = settings.model,
                resumeFrom = resumeFrom,
            )
        private val homePath: Path? =
            (ctx.env["HOME"] ?: hostEnv["HOME"])?.takeIf { it.isNotBlank() }?.let(Path::of)
        private val permissionPolicy =
            AcpPermissionPolicy(
                workspace = ctx.workspace,
                holdout = settings.holdoutDir?.let { HoldoutFolder(it, homePath) },
                protectedRoots = ProtectedWorkspaceRoots(ctx.workspace, homePath),
                strictMcp = settings.strictMcp,
                launcher = ctx.env["LOOP_LAUNCHER"],
            )
        private val telemetry = AcpTelemetry(isAgyPreset = settings.isAgyPreset)

        @Volatile private var live: LiveProcess? = null

        @Volatile private var isolationDir: AgyIsolationDir? = null

        @Volatile private var latest: TurnIds? = null

        @Volatile private var running: Turn? = null

        override fun runTurn(
            input: String,
            turn: Int,
        ): Flow<RunnerEvent> = flow { Turn(this, turn).run(input) }

        override fun interrupt() {
            running?.interrupt()
        }

        override suspend fun stop() = stop {}

        override suspend fun stop(report: suspend (RunnerEvent) -> Unit) {
            val reports = mutableListOf<RunnerEvent>()
            withContext(NonCancellable) {
                val process = live
                live = null
                try {
                    if (process != null) {
                        process.stdin.close()
                        val drained =
                            withTimeoutOrNull(stopGrace) {
                                while (true) {
                                    val next = process.events.receiveCatching()
                                    if (next.isClosed) break
                                    val event = next.getOrNull()
                                    if (event is ProcessEvent.Line && event.stream == Stream.Stdout) {
                                        drainStoppedUsage(event.text)?.let { reports += it }
                                    }
                                }
                            }
                        process.job.cancelAndJoin()
                        if (drained == null) {
                            latest?.let { ids ->
                                reports +=
                                    RunnerEvent.StopGraceExpired(
                                        sessionId = ids.sessionId,
                                        turnId = ids.turnId,
                                        graceMs = stopGrace.inWholeMilliseconds,
                                        wake = false,
                                        wakeMessages = 0,
                                    )
                            }
                        }
                    }
                } finally {
                    deleteIsolationDir()
                    scope.cancel()
                }
            }
            reports.forEach { report(it) }
        }

        private fun drainStoppedUsage(line: String): RunnerEvent.Usage? {
            val ids = latest ?: return null
            val frame = AcpFraming.decode(line)
            if (frame !is AcpFrame.Response) return null
            val result = frame.result as? JsonObject ?: return null
            return telemetry.readPromptUsage(result, ids.sessionId, ids.turnId)
        }

        private fun spawn(): LiveProcess {
            val process = LiveProcess(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
            val env = agentEnv()
            val argv = buildLaunchArgv(env)
            val spec =
                LaunchSpec(
                    argv = argv,
                    cwd = ctx.workspace,
                    environment = env,
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

        private fun buildLaunchArgv(env: Map<String, String>): List<String> {
            val base = settings.argv
            if (!settings.isAgyPreset) return base
            val args = mutableListOf<String>()
            args += base
            args += FLAG_DISABLE_TELEMETRY
            args += FLAG_SANDBOX
            if (settings.strictMcp) {
                val homePath =
                    env["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: hostEnv["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: Path.of(System.getProperty("user.home"))
                val loopHomePath =
                    env["LOOP_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: hostEnv["LOOP_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                val isolated =
                    AgyIsolationDir.create(
                        homeDir = homePath,
                        loopHome = loopHomePath,
                        projectId = ctx.projectId.value,
                        attemptId = ctx.attemptId.value,
                    )
                isolationDir = isolated
                args += FLAG_GEMINI_DIR
                args += isolated.relativePath
            }
            return args
        }

        private fun deleteIsolationDir() {
            val dir = isolationDir ?: return
            isolationDir = null
            dir.delete()
        }

        private fun agentEnv(): Map<String, String> {
            val removed = if (settings.isAgyPreset) removedEnvNames + AGY_REMOVED_ENV_NAMES else removedEnvNames
            return agentEnvironment(ctx.env, removed, hostEnv, resolveAddedEnv(addedEnv, ctx.workspace))
        }

        private suspend fun kill(process: LiveProcess) {
            withContext(NonCancellable) {
                if (live === process) live = null
                process.stdin.close()
                process.job.cancelAndJoin()
                deleteIsolationDir()
            }
        }

        private inner class Turn(
            private val out: FlowCollector<RunnerEvent>,
            private val turn: Int,
        ) {
            private val turnId = TurnId(UUID.randomUUID().toString())
            private val sessionId = SessionId("$threadId-${turnId.value}")
            private var started = false
            private var ended = false
            private var runtimeSessionId: String? = resumeFrom
            private val held = mutableListOf<RunnerEvent>()

            private val interruptLock = Any()
            private var interruptAsked = false
            private var interruptible: LiveProcess? = null
            private var cancelSessionId: String? = null

            fun interrupt() {
                synchronized(interruptLock) {
                    if (interruptAsked) return
                    interruptAsked = true
                    val process = interruptible
                    val sid = cancelSessionId
                    if (process != null && sid != null) {
                        sendCancel(process, sid)
                    }
                }
            }

            private fun allowInterrupt(
                process: LiveProcess,
                sid: String,
            ) {
                synchronized(interruptLock) {
                    interruptible = process
                    cancelSessionId = sid
                    if (interruptAsked) sendCancel(process, sid)
                }
            }

            private fun isInterrupted(): Boolean = synchronized(interruptLock) { interruptAsked }

            private fun sendCancel(
                process: LiveProcess,
                sid: String,
            ) {
                process.stdin.trySend(handshake.cancel(sid))
            }

            suspend fun run(input: String) {
                running = this
                latest = TurnIds(sessionId, turnId)
                try {
                    if (turn == 1) {
                        if (!ctx.workspace.isDirectory()) return fail(null, "invalid_workspace_cwd: ${ctx.workspace}")
                        if (settings.isAgyPreset) {
                            ProtectedRootsCheck.refusal(ctx.workspace, agentEnv())?.let { return fail(null, it) }
                        }
                        val mcpEnv = ctx.env + mapOf(ENV_SESSION_ID to sessionId.value, ENV_TURN_ID to turnId.value)
                        val mcpServers = AcpMcpConfig.mcpServers(mcpEnv)
                        val process = spawn()
                        process.stdin.send(handshake.start(input, mcpServers))
                        read(process)
                    } else {
                        val process = live
                        if (process == null || !handshake.isReadyForContinuation) {
                            return fail(
                                null,
                                "port_exit: the ACP server of this session is gone, turn $turn cannot start on thread $threadId",
                            )
                        }
                        if (settings.isAgyPreset) {
                            ProtectedRootsCheck.refusal(ctx.workspace, agentEnv())?.let { return killAndFail(process, it) }
                        }
                        process.stdin.send(handshake.continuePrompt(input))
                        allowInterrupt(process, checkNotNull(handshake.acpSessionId))
                        start()
                        read(process)
                    }
                } finally {
                    if (running === this) running = null
                    if (!ended) live?.let { kill(it) }
                }
            }

            private suspend fun read(process: LiveProcess) {
                var since = timeSource.markNow()
                while (true) {
                    val awaited = handshake.pendingHandshakeMethod
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
                                    handshake.pendingHandshakeMethod != null ->
                                        "port_exit: exit code ${event.exitCode}, before the ${handshake.pendingHandshakeMethod} response"
                                    else -> "port_exit: exit code ${event.exitCode}, no turn result"
                                }
                            return fail(event.exitCode, reason)
                        }
                        is ProcessEvent.Line -> {
                            out(RunnerEvent.Output(event.stream, event.text))
                            if (event.stream != Stream.Stdout) continue
                            process.stdoutLines++
                            val frame = decodeFrame(event.text)
                            if (frame is AcpFrame.Malformed) {
                                out(malformed(frame))
                                continue
                            }
                            if (handshake.pendingHandshakeMethod == null) since = timeSource.markNow()
                            if (frame is AcpFrame.Notification) {
                                handshake.observeNotification(frame)
                                telemetry
                                    .readNotification(frame, sessionId, turnId, handshake.activeModelId)
                                    ?.let { out(it) }
                            }
                            if (frame is AcpFrame.Request) {
                                if (isInterrupted() && frame.method == AcpHandshake.METHOD_REQUEST_PERMISSION) {
                                    process.stdin.send(cancelledPermissionResponse(frame.id))
                                    out(loopNote("cancelled permission request ${frame.id} after interrupt"))
                                } else {
                                    when (val answer = permissionPolicy.answer(frame)) {
                                        is AcpPermissionPolicy.Answer.Write -> {
                                            process.stdin.send(answer.line)
                                            out(loopNote(answer.note))
                                        }
                                        is AcpPermissionPolicy.Answer.InputRequired ->
                                            return killAndEnd(process, RunnerEvent.TurnInputRequired(sessionId, turnId, answer.reason))
                                    }
                                }
                            }
                            if (handshake.pendingHandshakeMethod != null) {
                                when (val step = handshake.readStartup(frame)) {
                                    is AcpHandshake.Step.Continue -> {
                                        step.notes.forEach { out(loopNote(it)) }
                                        if (step.lines.isNotEmpty()) {
                                            step.lines.forEach { process.stdin.send(it) }
                                            since = timeSource.markNow()
                                        }
                                    }
                                    is AcpHandshake.Step.ReadyForPrompt -> {
                                        step.notes.forEach { out(loopNote(it)) }
                                        runtimeSessionId = step.acpSessionId
                                        if (settings.isAgyPreset) {
                                            ProtectedRootsCheck.refusal(ctx.workspace, agentEnv())?.let {
                                                return killAndFail(process, it)
                                            }
                                        }
                                        process.stdin.send(step.promptLine)
                                        allowInterrupt(process, step.acpSessionId)
                                        start()
                                        since = timeSource.markNow()
                                    }
                                    is AcpHandshake.Step.Failed -> {
                                        val classified =
                                            (step.failure as? AcpHandshakeFailure.ErrorAnswer)?.let { err ->
                                                AcpErrors.classify(err.method, err.code, err.message, err.data)
                                            }
                                        return killAndEnd(
                                            process,
                                            RunnerEvent.TurnFailed(
                                                sessionId = sessionId,
                                                turnId = turnId,
                                                exitCode = null,
                                                reason = step.failure.reason,
                                                category = classified?.category,
                                                retryAfter = classified?.retryAfter,
                                            ),
                                        )
                                    }
                                }
                                continue
                            }
                            if (handlePromptCompletion(process, frame)) return
                        }
                    }
                }
            }

            /** Checks whether [frame] is the response to the active `session/prompt`; returns true if the turn ended. */
            private suspend fun handlePromptCompletion(
                process: LiveProcess,
                frame: AcpFrame,
            ): Boolean {
                val promptId = handshake.activePromptId ?: return false
                when (frame) {
                    is AcpFrame.Response -> {
                        if (!AcpHandshake.matchesId(frame.id, promptId)) return false
                        val obj = frame.result as? JsonObject
                        if (obj == null) {
                            killAndFail(process, "response_error: the session/prompt response is not a JSON object")
                            return true
                        }
                        handshake.completePrompt()
                        telemetry.readPromptUsage(obj, sessionId, turnId)?.let { out(it) }
                        val stopReason = (obj["stopReason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        when (stopReason) {
                            null, "end_turn", "max_tokens", "max_turn_requests" ->
                                end(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                            "cancelled" ->
                                if (isInterrupted()) {
                                    end(RunnerEvent.TurnCompleted(sessionId, turnId, exitCode = 0))
                                } else {
                                    fail(null, "turn_cancelled")
                                }
                            else -> fail(null, "turn_failed: $stopReason")
                        }
                        return true
                    }
                    is AcpFrame.Error -> {
                        val id = frame.id ?: return false
                        if (!AcpHandshake.matchesId(id, promptId)) return false
                        handshake.completePrompt()
                        val classified =
                            AcpErrors.classify(
                                AcpHandshake.METHOD_SESSION_PROMPT,
                                frame.code,
                                frame.message,
                                frame.data,
                            )
                        fail(
                            exitCode = null,
                            reason = "turn_failed: ${frame.message} (code ${frame.code})",
                            category = classified.category,
                            retryAfter = classified.retryAfter,
                        )
                        return true
                    }
                    else -> return false
                }
            }

            private suspend fun start() {
                started = true
                val event =
                    if (turn == 1) {
                        RunnerEvent.SessionStarted(sessionId, threadId, turnId, runtimeSessionId = runtimeSessionId)
                    } else {
                        RunnerEvent.TurnStarted(sessionId, threadId, turnId, turn)
                    }
                out.emit(event)
                held.forEach { out.emit(it) }
                held.clear()
            }

            private suspend fun out(event: RunnerEvent) {
                if (started) out.emit(event) else held += event
            }

            private suspend fun end(event: RunnerEvent) {
                if (!started) start()
                ended = true
                out.emit(event)
            }

            private suspend fun fail(
                exitCode: Int?,
                reason: String,
                category: RunnerEvent.TurnFailed.Category? = null,
                retryAfter: Duration? = null,
            ) = end(RunnerEvent.TurnFailed(sessionId, turnId, exitCode, reason, category = category, retryAfter = retryAfter))

            private fun cancelledPermissionResponse(id: kotlinx.serialization.json.JsonElement): String =
                AcpFraming.response(
                    id = id,
                    result =
                        buildJsonObject {
                            put(
                                "outcome",
                                buildJsonObject {
                                    put("outcome", "cancelled")
                                },
                            )
                        },
                )

            private suspend fun killAndFail(
                process: LiveProcess,
                reason: String,
            ) = killAndEnd(process, RunnerEvent.TurnFailed(sessionId, turnId, null, reason))

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

    private fun decodeFrame(line: String): AcpFrame = AcpFraming.decode(line).also(onFrame)

    private fun malformed(frame: AcpFrame.Malformed): RunnerEvent.Output =
        loopNote("an ACP server stdout line is not a protocol message (${frame.reason}), skipped")

    private fun loopNote(message: String): RunnerEvent.Output = RunnerEvent.Output(Stream.Stderr, "$LOOP_PREFIX$message")

    private fun agentNotFound(message: String): String = "agent_not_found: ${settings.command}: $message"

    companion object {
        private const val LOOP_PREFIX = "loop: "
        private const val EXIT_COMMAND_NOT_FOUND = 127
        private const val ENV_SESSION_ID = "LOOP_SESSION_ID"
        private const val ENV_TURN_ID = "LOOP_TURN_ID"
        private const val FLAG_DISABLE_TELEMETRY = "--disable-telemetry"
        private const val FLAG_SANDBOX = "--sandbox"
        private const val FLAG_GEMINI_DIR = "--gemini-dir"

        /** Google API env variables removed from the inherited host env by default under `preset: agy`. */
        val AGY_REMOVED_ENV_NAMES: Set<String> =
            setOf(
                "GEMINI_API_KEY",
                "GOOGLE_API_KEY",
                "GOOGLE_APPLICATION_CREDENTIALS",
                "GOOGLE_GENAI_USE_VERTEXAI",
            )

        val STOP_GRACE: Duration = 5.seconds

        val CAPABILITIES: RunnerCapabilities =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Mcp,
                usage = true,
                cost = false,
                occupancy = true,
                sandbox = false,
            )
    }
}
