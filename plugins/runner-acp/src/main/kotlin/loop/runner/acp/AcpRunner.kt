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
import kotlinx.serialization.json.JsonArray
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
 *
 * With [AcpSettings.readOnly] (bl-205, the Gemini merge risk reviewer), the permission policy is read only, no
 * `mcpServers` are passed, a session never resumes and its isolated folder is removed when it stops, never kept.
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
    /** Receives cleanup warnings that cannot be emitted on a turn flow (for example during `stop()`). */
    private val onWarn: (String) -> Unit = { line -> System.err.println(line) },
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

    /**
     * Opens a session whose turn 1 loads [runtimeSessionId] with `session/load`. Under `preset: agy` (bl-213), the
     * session store lives in the isolated `--gemini-dir` folder, so this finds the folder an earlier attempt of the
     * same issue kept for that id and claims it, or returns null when none is kept and Loop opens a fresh session.
     */
    override fun resumeSession(
        ctx: AttemptContext,
        runtimeSessionId: String,
    ): AgentSession? {
        if (settings.readOnly) return null
        if (!settings.isAgyPreset) return AcpSession(ctx, resumeFrom = runtimeSessionId)
        val roots = isolationRoots(agentEnv(ctx))
        AgyIsolationDir.sweepStale(roots.home, roots.loopHome).forEach { warn(ctx, it) }
        val kept =
            AgyIsolationDir.findKept(roots.home, roots.loopHome, ctx.projectId.value, ctx.issue.id, runtimeSessionId)
                ?: return null
        return AcpSession(ctx, resumeFrom = runtimeSessionId, keptDir = kept)
    }

    /** Where isolated `--gemini-dir` folders live for an agent environment [env]. */
    private data class IsolationRoots(
        val home: Path,
        val loopHome: Path?,
    )

    private fun isolationRoots(env: Map<String, String>): IsolationRoots =
        IsolationRoots(
            home =
                env["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: hostEnv["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home")),
            loopHome =
                env["LOOP_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: hostEnv["LOOP_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of),
        )

    private fun agentEnv(ctx: AttemptContext): Map<String, String> {
        val removed = if (settings.isAgyPreset) removedEnvNames + AGY_REMOVED_ENV_NAMES else removedEnvNames
        return agentEnvironment(ctx.env, removed, hostEnv, resolveAddedEnv(addedEnv, ctx.workspace))
    }

    private fun warn(
        ctx: AttemptContext,
        error: String,
    ) {
        val clean = error.replace('"', '\'').replace('\n', ' ')
        onWarn("level=warn event=acp_cleanup project=${ctx.projectId.value} attempt=${ctx.attemptId.value} reason=\"$clean\"")
    }

    private class LiveProcess(
        val events: Channel<ProcessEvent>,
        val stdin: Channel<String>,
    ) {
        lateinit var job: Job
        var stdoutLines: Int = 0

        /** True once the process exited or failed to spawn, even while its last events wait in [events]. */
        @Volatile var gone: Boolean = false
    }

    private data class TurnIds(
        val sessionId: SessionId,
        val turnId: TurnId,
    )

    /**
     * One ACP session on one long lived process, running turns 1..n sequentially. Under `preset: agy`, a turn whose
     * process is gone respawns it once and loads the session from the kept [isolationDir] (bl-213), and a session that
     * resumes an earlier attempt's [resumeFrom] from [keptDir] falls back to a fresh session when it cannot load it.
     */
    private inner class AcpSession(
        private val ctx: AttemptContext,
        private val resumeFrom: String? = null,
        keptDir: AgyIsolationDir? = null,
    ) : AgentSession {
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val threadId = UUID.randomUUID().toString()

        @Volatile private var handshake = newHandshake(resumeFrom)

        /** The earlier attempt's runtime session turn 1 resumes under `preset: agy`, until it loads or is given up. */
        private var pendingResume: String? = resumeFrom?.takeIf { settings.isAgyPreset }

        /** The `mcpServers` of the turn that opened the session, passed again when a respawn loads it. */
        private var sessionMcpServers: JsonArray = JsonArray(emptyList())
        private val owner = AgyIsolationDir.Owner(ctx.projectId.value, ctx.issue.id, ctx.workspace)
        private val homePath: Path? =
            (ctx.env["HOME"] ?: hostEnv["HOME"])?.takeIf { it.isNotBlank() }?.let(Path::of)
        private val permissionPolicy =
            AcpPermissionPolicy(
                workspace = ctx.workspace,
                holdout = settings.holdoutDir?.let { HoldoutFolder(it, homePath) },
                protectedRoots = ProtectedWorkspaceRoots(ctx.workspace, homePath),
                strictMcp = settings.strictMcp,
                launcher = ctx.env["LOOP_LAUNCHER"],
                protectedWriteDirs = ::internalProtectedDirs,
                readOnly = settings.readOnly,
            )
        private val telemetry = AcpTelemetry(isAgyPreset = settings.isAgyPreset)

        @Volatile private var live: LiveProcess? = null

        @Volatile private var isolationDir: AgyIsolationDir? = keptDir

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
                    releaseIsolationDir()
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

        private fun newHandshake(resumeFrom: String?): AcpHandshake =
            AcpHandshake(
                workspace = ctx.workspace,
                loopVersion = loopVersion,
                preset = settings.preset,
                configuredModel = settings.model,
                resumeFrom = resumeFrom,
            )

        private fun spawn(warnings: MutableList<String>): LiveProcess {
            val process = LiveProcess(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
            val env = agentEnv(ctx)
            val argv = buildLaunchArgv(env, warnings)
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
                        launchProcess(spec).collect {
                            if (it is ProcessEvent.Exited || it is ProcessEvent.SpawnFailed) process.gone = true
                            process.events.send(it)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        process.events.close(e)
                    } finally {
                        process.gone = true
                        process.events.close()
                    }
                }
            live = process
            return process
        }

        private fun buildLaunchArgv(
            env: Map<String, String>,
            warnings: MutableList<String>,
        ): List<String> {
            val base = settings.argv
            if (!settings.isAgyPreset) return base
            val roots = isolationRoots(env)
            AgyIsolationDir.sweepStale(roots.home, roots.loopHome).forEach { warnings += it }
            // A new session of the issue supersedes the folders its earlier sessions kept; its own folder is live.
            AgyIsolationDir.removeKept(roots.home, roots.loopHome, ctx.projectId.value, ctx.issue.id).forEach { warnings += it }
            val isolated =
                isolationDir
                    ?: AgyIsolationDir
                        .create(homeDir = roots.home, loopHome = roots.loopHome, attemptId = ctx.attemptId.value)
                        .also { isolationDir = it }
            return base + listOf(FLAG_DISABLE_TELEMETRY, FLAG_SANDBOX, FLAG_GEMINI_DIR, isolated.relativePath)
        }

        private fun internalProtectedDirs(): List<HoldoutFolder> {
            val dirs = mutableListOf<Path>()
            isolationDir?.path?.let { dirs.add(it) }
            ctx.env["LOOP_TOOL_CONFIG"]
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it).parent }.getOrNull() }
                ?.let { dirs.add(it) }
            val loopHome =
                (ctx.env["LOOP_HOME"] ?: hostEnv["LOOP_HOME"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Path.of(it) }.getOrNull() }
            if (loopHome != null) {
                dirs.add(loopHome.resolve("attempts").resolve(ctx.projectId.value).resolve(ctx.attemptId.value))
            }
            return dirs.distinct().map { HoldoutFolder(it, homePath) }
        }

        private fun deleteIsolationDir() {
            val dir = isolationDir ?: return
            isolationDir = null
            dir.delete()?.let { warn(ctx, it) }
        }

        /**
         * At the stop, keeps the folder of a session that reported its runtime session id, so a respawn or a later
         * attempt can load it (bl-213), and deletes any other, and every folder of a read only session.
         */
        private fun releaseIsolationDir() {
            val dir = isolationDir ?: return
            if (!settings.readOnly && dir.keep()) {
                isolationDir = null
            } else {
                deleteIsolationDir()
            }
        }

        private suspend fun kill(process: LiveProcess) =
            withContext(NonCancellable) {
                if (live === process) live = null
                process.stdin.close()
                process.job.cancelAndJoin()
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

            /** Why the agent could not load [pendingResume], set when [read] stops to fall back to a fresh session. */
            private var resumeRefusal: String? = null

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
                            ProtectedRootsCheck.refusal(ctx.workspace, ctx.baseRef)?.let { return fail(null, it) }
                        }
                        val mcpEnv = ctx.env + mapOf(ENV_SESSION_ID to sessionId.value, ENV_TURN_ID to turnId.value)
                        val mcpServers = if (settings.readOnly) JsonArray(emptyList()) else AcpMcpConfig.mcpServers(mcpEnv)
                        sessionMcpServers = mcpServers
                        pendingResume?.let { resumed ->
                            val refusal = isolationDir?.checkBeforeLoad()
                            if (refusal != null) {
                                giveUpResume(resumed, refusal)
                            } else {
                                out(loopNote("turn 1 resumes agy session $resumed of an earlier attempt"))
                            }
                        }
                        val process = startProcess(input, mcpServers)
                        read(process)
                        val refusal = resumeRefusal ?: return
                        killAndDrain(process)
                        giveUpResume(checkNotNull(pendingResume), refusal)
                        read(startProcess(input, mcpServers))
                    } else {
                        val process = live
                        val established = handshake.acpSessionId
                        if (settings.isAgyPreset && (process == null || process.gone) && established != null && isolationDir != null) {
                            return respawn(process, established, input)
                        }
                        if (process == null || !handshake.isReadyForContinuation) {
                            return fail(
                                null,
                                "port_exit: the ACP server of this session is gone, turn $turn cannot start on thread $threadId",
                            )
                        }
                        if (settings.isAgyPreset) {
                            ProtectedRootsCheck.refusal(ctx.workspace, ctx.baseRef)?.let { return killAndFail(process, it) }
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

            private suspend fun startProcess(
                input: String,
                mcpServers: JsonArray,
            ): LiveProcess {
                val spawnWarnings = mutableListOf<String>()
                val process = spawn(spawnWarnings)
                spawnWarnings.forEach { out(loopNote(it)) }
                process.stdin.send(handshake.start(input, mcpServers))
                return process
            }

            /**
             * Turn 1 cannot load the earlier attempt's session [resumed] for [reason]: it says why and opens a fresh
             * session in a fresh folder, never a half resumed one.
             */
            private suspend fun giveUpResume(
                resumed: String,
                reason: String,
            ) {
                out(loopNote("cannot resume agy session $resumed of an earlier attempt ($reason), turn 1 opens a fresh session"))
                pendingResume = null
                resumeRefusal = null
                runtimeSessionId = null
                deleteIsolationDir()
                handshake = newHandshake(null)
            }

            /**
             * The `agy` process of session [established] is gone ([previous] exited, or a failed turn killed it):
             * starts a new `agy acp` on the same `--gemini-dir`, loads the session with the `mcpServers` of the turn
             * that opened it, and runs this turn on it. At most once per turn: a failure fails the turn.
             */
            private suspend fun respawn(
                previous: LiveProcess?,
                established: String,
                input: String,
            ) {
                previous?.let { kill(it) }
                ProtectedRootsCheck.refusal(ctx.workspace, ctx.baseRef)?.let { return fail(null, it) }
                checkNotNull(isolationDir).checkBeforeLoad()?.let { refusal ->
                    return fail(null, "port_exit: the agy process of session $established is gone and turn $turn cannot load it: $refusal")
                }
                out(loopNote("the agy process of session $established is gone, turn $turn starts a new one and loads the session"))
                handshake = newHandshake(established)
                read(startProcess(input, sessionMcpServers))
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
                                handshake.observeNotification(frame)?.let { mismatch ->
                                    return killAndFail(process, mismatch.reason)
                                }
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
                                        pendingResume = null
                                        isolationDir?.let { dir ->
                                            runCatching { dir.recordSession(owner, step.acpSessionId) }.onFailure { e ->
                                                out(
                                                    loopNote(
                                                        "the isolated agy folder cannot record session ${step.acpSessionId}, it is not kept: ${e.message}",
                                                    ),
                                                )
                                            }
                                        }
                                        if (settings.isAgyPreset) {
                                            ProtectedRootsCheck.refusal(ctx.workspace, ctx.baseRef)?.let {
                                                return killAndFail(process, it)
                                            }
                                        }
                                        process.stdin.send(step.promptLine)
                                        allowInterrupt(process, step.acpSessionId)
                                        start()
                                        since = timeSource.markNow()
                                    }
                                    is AcpHandshake.Step.Failed -> {
                                        if (pendingResume != null && step.failure.refusesLoad()) {
                                            resumeRefusal = step.failure.reason
                                            return
                                        }
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
                killAndDrain(process)
                end(event)
            }

            private suspend fun killAndDrain(process: LiveProcess) {
                kill(process)
                while (true) {
                    val queued = process.events.tryReceive().getOrNull() ?: break
                    if (queued is ProcessEvent.Line) out(RunnerEvent.Output(queued.stream, queued.text))
                }
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
