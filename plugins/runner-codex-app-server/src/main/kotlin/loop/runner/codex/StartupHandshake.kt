package loop.runner.codex

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import loop.runner.codex.protocol.ClientInfo
import loop.runner.codex.protocol.ClientNotification
import loop.runner.codex.protocol.ClientRequest
import loop.runner.codex.protocol.CodexCliVersion
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.InitializeCapabilities
import loop.runner.codex.protocol.InitializeParams
import loop.runner.codex.protocol.InitializeResponse
import loop.runner.codex.protocol.v2.AskForApproval
import loop.runner.codex.protocol.v2.ConfigReadParams
import loop.runner.codex.protocol.v2.ConfigReadResponse
import loop.runner.codex.protocol.v2.SandboxMode
import loop.runner.codex.protocol.v2.SandboxPolicy
import loop.runner.codex.protocol.v2.ThreadStartParams
import loop.runner.codex.protocol.v2.ThreadStartResponse
import loop.runner.codex.protocol.v2.TurnInterruptParams
import loop.runner.codex.protocol.v2.TurnStartParams
import loop.runner.codex.protocol.v2.TurnStartResponse
import loop.runner.codex.protocol.v2.UserInput
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Why a session start, or the `turn/start` of a continuation turn (m3-19), failed (m3-18, 17.5 item 2). [reason] is the
 * turn's failure reason, `response_error: <detail>`, the 10.6 category for an answer the client cannot go on with.
 */
sealed interface StartupFailure {
    val reason: String

    /** The server answered the startup request [method] with a JSON-RPC error. */
    data class ErrorAnswer(
        val method: String,
        val code: Long,
        val message: String,
    ) : StartupFailure {
        override val reason: String get() = "response_error: $method failed with code $code: $message"
    }

    /** The server answered request [id] while the client waited for the answer to [method]. */
    data class OutOfOrder(
        val method: String,
        val id: JsonElement,
    ) : StartupFailure {
        override val reason: String get() = "response_error: an answer to request $id arrived while waiting for the $method response"
    }

    /** The `initialize` answer's `userAgent` names no codex version (bl-128). */
    data object UnknownVersion : StartupFailure {
        override val reason: String get() = "response_error: the initialize response names no codex version in its userAgent"
    }

    /** The app server runs codex [found], whose major version is not the one of the [pinned] codex (bl-128, m3-02). */
    data class OtherMajorVersion(
        val found: String,
        val pinned: String,
    ) : StartupFailure {
        override val reason: String
            get() = "response_error: codex $found is not of the pinned major version ${pinned.substringBefore('.')} (codex-cli $pinned)"
    }

    /** The answer to [method] is not that method's response type in the pinned schema. */
    data class InvalidAnswer(
        val method: String,
    ) : StartupFailure {
        override val reason: String get() = "response_error: the $method response does not match the pinned schema"
    }
}

/**
 * The client side of a session start on the pinned app server (m3-18, spec 10.2), one per session. The order is the
 * protocol's: `initialize`, its answer, the `initialized` notification and `thread/start`, its answer, then
 * `turn/start` with the prompt, and its answer. A request is written only once the answer before it arrived, so
 * the server never sees a thread or turn request on a connection it has not initialized. Request ids count from 1.
 *
 * With [strictMcp] (m3-20, bl-72), `config/read` with the workspace as `cwd` comes between `initialized` and
 * `thread/start`, and `thread/start` carries a `config` that disables each MCP server its answer lists, by name, and
 * turns the `apps` feature off, the mechanism m3-02 found on the pinned app server: no single override empties the
 * server table, but a server disabled by name never starts. The answer lists the servers of the user's config and of
 * a trusted project's `.codex/config.toml`. An error answer or one that does not decode fails the start, so a strict
 * session never runs with the operator's servers.
 *
 * A continuation turn (m3-19, spec 10.2 and 10.3) is one more `turn/start` on the same thread, with the next request
 * id and the same policy and `cwd`: [continueOn] gives its line, and its answer is read as the first turn's is.
 *
 * An interrupt (m3-23, the hard limit) is a `turn/interrupt` request on the thread and turn that run: [interrupt] gives
 * its line, with the next request id, from any thread. Its answer, whenever it comes, is passed over: the turn's end is
 * its `turn/completed`, and an answer that comes late never fails the next turn's start.
 *
 * The `thread/start` answer names the thread's [modelProvider] and [model], the `context.windows` key of its
 * occupancy (m3-23). With [configuredModel], `runners.codex.model` (bl-141), `thread/start` asks for that model;
 * without it the thread takes the model of the Codex config.
 *
 * [start] gives the first line to write, [read] takes each frame read from stdout and says what to do next. Every
 * line is one JSON object written with [CodexJson.format] through the base type of its union, so its `method` is
 * written, with its line break. Notifications are not part of the start and are passed over, as are server requests:
 * the runner answers those with [ServerRequestPolicy] (m3-20).
 *
 * With [tools] (m3-22, 17.5 item 15), Loop's client tools, `initialize` asks for the experimental API and `thread/start`
 * carries them as `dynamicTools`: the pinned app server refuses that member otherwise. Without them both stay on the
 * stable protocol.
 *
 * Version (bl-128, the m3-02 decision): the `initialize` answer's `userAgent`, `<originator>/<version> (...)`, names
 * the codex version of the app server. A major version other than the one of [pinnedVersion], `codex-cli` in
 * `gradle/libs.versions.toml`, or a `userAgent` with no version fails the start before `initialized` is written: the
 * client speaks the pinned protocol only. Another minor or patch version of the same major starts.
 *
 * Every `thread/start` carries a `config` with `allow_login_shell: false`, so Codex runs each command of the agent in a
 * plain shell, not a login shell that would source the user's profile after the agent env policy built the env.
 *
 * Payloads:
 * - identity: `clientInfo` with the name `loop` and [loopVersion];
 * - capabilities: `experimentalApi: false`, since Loop uses the stable protocol, the one the committed schema holds,
 *   except `dynamicTools` when it declares its tools, which turns it to true;
 * - policy: [APPROVAL_POLICY] with [SANDBOX] on `thread/start`, [APPROVAL_POLICY] with [SANDBOX_POLICY], the same
 *   posture in the form `turn/start` takes, on `turn/start`, and the workspace's absolute path as `cwd` on both, see
 *   `docs/spec-decisions.md`, "Codex runner session startup" and "Codex runner approvals, user input, tool calls and
 *   guards". With [readOnly], the merge risk reviewer's thread (bl-214), [READ_ONLY_SANDBOX] and
 *   [READ_ONLY_SANDBOX_POLICY] instead.
 */
internal class StartupHandshake(
    private val workspace: Path,
    private val loopVersion: String,
    private val strictMcp: Boolean = false,
    private val tools: JsonArray? = null,
    private val configuredModel: String? = null,
    /** bl-214: the merge risk reviewer's thread, on [READ_ONLY_SANDBOX] and [READ_ONLY_SANDBOX_POLICY]. */
    private val readOnly: Boolean = false,
    private val pinnedVersion: String = CodexCliVersion.PINNED,
) {
    private val sandbox: SandboxMode get() = if (readOnly) READ_ONLY_SANDBOX else SANDBOX
    private val sandboxPolicy: SandboxPolicy get() = if (readOnly) READ_ONLY_SANDBOX_POLICY else SANDBOX_POLICY

    /** What the client does after reading one frame. */
    sealed interface Step {
        /** Write [lines] (none when the frame changed nothing) and go on reading. */
        data class Write(
            val lines: List<String>,
        ) : Step

        /** The turn started on Codex's thread [threadId] with Codex's turn [turnId]: the start is over. */
        data class Started(
            val threadId: String,
            val turnId: String,
        ) : Step

        /** The start failed; nothing more is written. */
        data class Failed(
            val failure: StartupFailure,
        ) : Step
    }

    private var awaiting: Awaiting = Awaiting.Done

    // The input of the first turn, written once the thread started.
    private var prompt: String = ""

    // The id of the next request, taken by the reading coroutine and by an interrupt from another thread.
    private val ids = AtomicLong(1)

    // The ids of the interrupt requests written, whose answers are passed over.
    private val interrupts: MutableSet<JsonPrimitive> = ConcurrentHashMap.newKeySet()

    /** The provider of the thread's model, from the `thread/start` answer; null until it arrived. */
    @Volatile var modelProvider: String? = null
        private set

    /** The thread's model, from the `thread/start` answer; null until it arrived. */
    @Volatile var model: String? = null
        private set

    /** The request whose answer is awaited, null once the turn started; for the reason of an exit or a timeout. */
    val pendingMethod: String? get() = awaiting.method

    /** The first line to write, the `initialize` request; [prompt] is the input of the first turn. */
    fun start(prompt: String): String {
        this.prompt = prompt
        val initialize = Awaiting.Initialize(ids.getAndIncrement())
        awaiting = initialize
        return request(
            ClientRequest.Initialize(
                id = id(initialize),
                params =
                    InitializeParams(
                        clientInfo = ClientInfo(name = CLIENT_NAME, version = loopVersion),
                        // thread/start.dynamicTools is experimental: the pinned app server refuses it without this (m3-22).
                        capabilities = InitializeCapabilities(experimentalApi = tools != null),
                    ),
            ),
        )
    }

    /**
     * The line of a continuation turn's `turn/start` on the thread [threadId] with [input], once the turn before it
     * started. Its answer is read by [read] and ends in [Step.Started] with the new turn's id.
     */
    fun continueOn(
        threadId: String,
        input: String,
    ): String {
        check(awaiting == Awaiting.Done) { "a turn/start is written only once the turn before it started" }
        val turn = Awaiting.Turn(threadId, ids.getAndIncrement())
        awaiting = turn
        return turnStart(turn, input)
    }

    /** The line of a `turn/interrupt` request for the turn [turnId] on the thread [threadId] (m3-23); its answer is passed over. */
    fun interrupt(
        threadId: String,
        turnId: String,
    ): String {
        val id = JsonPrimitive(ids.getAndIncrement())
        interrupts += id
        return request(ClientRequest.TurnInterrupt(id = id, params = TurnInterruptParams(threadId = threadId, turnId = turnId)))
    }

    /** Reads [frame], one stdout line, and says what to do next. */
    fun read(frame: Frame): Step {
        val answered =
            when (frame) {
                is Frame.Response -> frame.message.id
                is Frame.Error -> frame.message.id
                else -> null
            }
        if (answered != null && interrupts.remove(answered)) return NOTHING
        val method = awaiting.method ?: return NOTHING
        return when (frame) {
            is Frame.Response ->
                if (frame.message.id != id(awaiting)) {
                    Step.Failed(StartupFailure.OutOfOrder(method, frame.message.id))
                } else {
                    answered(frame.message.result)
                }
            is Frame.Error ->
                if (frame.message.id != id(awaiting)) {
                    Step.Failed(StartupFailure.OutOfOrder(method, frame.message.id))
                } else {
                    Step.Failed(StartupFailure.ErrorAnswer(method, frame.message.error.code, frame.message.error.message))
                }
            is Frame.Notification, is Frame.Request, is Frame.Malformed -> NOTHING
        }.also { if (it is Step.Failed) awaiting = Awaiting.Done }
    }

    /** Why the codex of [userAgent] cannot be used, or null when its major version is the pinned one. */
    private fun versionFailure(userAgent: String): StartupFailure? {
        val match = USER_AGENT_VERSION.find(userAgent) ?: return StartupFailure.UnknownVersion
        val (version, major) = match.destructured
        return if (major == pinnedVersion.substringBefore('.')) null else StartupFailure.OtherMajorVersion(version, pinnedVersion)
    }

    private fun answered(result: JsonElement): Step =
        when (val current = awaiting) {
            is Awaiting.Initialize ->
                decode(result, InitializeResponse.serializer(), "initialize") { response ->
                    versionFailure(response.userAgent)?.let { return@decode Step.Failed(it) }
                    val initialized = notification(ClientNotification.Initialized)
                    if (strictMcp) {
                        val config = Awaiting.Config(ids.getAndIncrement())
                        awaiting = config
                        Step.Write(listOf(initialized, configRead(config)))
                    } else {
                        Step.Write(listOf(initialized, threadStart(baseConfig())))
                    }
                }
            is Awaiting.Config ->
                decode(result, ConfigReadResponse.serializer(), "config/read") {
                    val names = mcpServerNames(result) ?: return@decode Step.Failed(StartupFailure.InvalidAnswer("config/read"))
                    Step.Write(listOf(threadStart(strictConfig(names))))
                }
            is Awaiting.Thread ->
                decode(result, ThreadStartResponse.serializer(), "thread/start") { response ->
                    modelProvider = response.modelProvider
                    model = response.model
                    val turn = Awaiting.Turn(response.thread.id, ids.getAndIncrement())
                    awaiting = turn
                    Step.Write(listOf(turnStart(turn, prompt)))
                }
            is Awaiting.Turn ->
                decode(result, TurnStartResponse.serializer(), "turn/start") { response ->
                    awaiting = Awaiting.Done
                    Step.Started(current.threadId, response.turn.id)
                }
            Awaiting.Done -> NOTHING
        }

    private fun <T> decode(
        result: JsonElement,
        serializer: KSerializer<T>,
        method: String,
        next: (T) -> Step,
    ): Step {
        val response =
            try {
                CodexJson.format.decodeFromJsonElement(serializer, result)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, and so is a wrong JSON type for a member.
                return Step.Failed(StartupFailure.InvalidAnswer(method))
            }
        return next(response)
    }

    /**
     * Writes `thread/start` with [config], the thread's config overrides, and awaits its answer. With [tools] the params
     * also carry them as `dynamicTools` (m3-22), a member of the experimental `ThreadStartParams` that the stable type
     * lacks, so it is added to the written JSON; each entry is a `DynamicToolSpec`, which the pinned schema holds.
     */
    private fun threadStart(config: JsonObject): String {
        val thread = Awaiting.Thread(ids.getAndIncrement())
        awaiting = thread
        val request =
            ClientRequest.ThreadStart(
                id = id(thread),
                params =
                    ThreadStartParams(
                        approvalPolicy = APPROVAL_POLICY,
                        sandbox = sandbox,
                        cwd = workspace.toAbsolutePath().toString(),
                        config = config,
                        model = configuredModel,
                    ),
            )
        if (tools == null) return request(request)
        val json = CodexJson.format.encodeToJsonElement(ClientRequest.serializer(), request).jsonObject
        val params = JsonObject(json.getValue("params").jsonObject + (DYNAMIC_TOOLS to tools))
        return JsonObject(json + ("params" to params)).toString() + "\n"
    }

    private fun configRead(config: Awaiting.Config): String =
        request(ClientRequest.ConfigRead(id = id(config), params = ConfigReadParams(cwd = workspace.toAbsolutePath().toString())))

    /** The names of the MCP servers of a `config/read` [result], none when it has no table; null when the table is not an object. */
    private fun mcpServerNames(result: JsonElement): Set<String>? {
        val servers = (result as? JsonObject)?.let { (it["config"] as? JsonObject)?.get(MCP_SERVERS) } ?: return emptySet()
        return (servers as? JsonObject)?.keys
    }

    /**
     * The `config` of every thread: `allow_login_shell` off, so Codex runs the agent's commands with `-c`, not `-lc`,
     * and a login profile export never brings back a name the agent env policy removed (send back 2 of pull request
     * #104). Checked on codex 0.167.0: with it, a command the model asks to run in a login shell is refused.
     */
    private fun baseConfig(): JsonObject = buildJsonObject { put(ALLOW_LOGIN_SHELL, false) }

    /** The `config` of a strict thread: [baseConfig], each of [names] disabled, and the `apps` feature, `codex_apps`, off. */
    private fun strictConfig(names: Set<String>): JsonObject =
        JsonObject(
            baseConfig() +
                mapOf(
                    MCP_SERVERS to JsonObject(names.sorted().associateWith { buildJsonObject { put("enabled", false) } }),
                    "features" to buildJsonObject { put("apps", false) },
                ),
        )

    private fun turnStart(
        turn: Awaiting.Turn,
        input: String,
    ): String =
        request(
            ClientRequest.TurnStart(
                id = id(turn),
                params =
                    TurnStartParams(
                        threadId = turn.threadId,
                        input = listOf(UserInput.Text(text = input)),
                        approvalPolicy = APPROVAL_POLICY,
                        sandboxPolicy = sandboxPolicy,
                        cwd = workspace.toAbsolutePath().toString(),
                    ),
            ),
        )

    private fun request(request: ClientRequest): String = CodexJson.format.encodeToString(ClientRequest.serializer(), request) + "\n"

    private fun notification(notification: ClientNotification): String =
        CodexJson.format.encodeToString(ClientNotification.serializer(), notification) + "\n"

    private fun id(step: Awaiting): JsonPrimitive = JsonPrimitive(step.id)

    /** The request whose answer the client waits for, with its request id and method. */
    private sealed class Awaiting(
        val id: Long,
        val method: String?,
    ) {
        data class Initialize(
            val requestId: Long,
        ) : Awaiting(requestId, "initialize")

        /** The `config/read` of a strict MCP start (m3-20). */
        data class Config(
            val requestId: Long,
        ) : Awaiting(requestId, "config/read")

        data class Thread(
            val requestId: Long,
        ) : Awaiting(requestId, "thread/start")

        /** A `turn/start` on [threadId] with the request id [requestId]. */
        data class Turn(
            val threadId: String,
            val requestId: Long,
        ) : Awaiting(requestId, "turn/start")

        data object Done : Awaiting(0, null)
    }

    companion object {
        /** The `clientInfo.name` Loop introduces itself with; Codex writes it as the thread's `originator`. */
        const val CLIENT_NAME = "loop"

        /**
         * Codex asks the client before every command it does not hold as known safe and before every file change
         * (m3-20), so the runner can refuse the denied commands and the holdout folder by rule, see
         * [ServerRequestPolicy]. Each request is answered at once, so the turn never waits on a person. Before m3-20
         * it was `never`, under which Codex asked nothing and nothing could be refused.
         */
        val APPROVAL_POLICY: AskForApproval = AskForApproval.Untrusted

        /** No Codex sandbox on the thread, as the Claude Code runner runs in bypass mode; the guards are the approvals. */
        val SANDBOX: SandboxMode = SandboxMode.DANGER_FULL_ACCESS

        /** The same posture as [SANDBOX], in the policy form `turn/start` takes. */
        val SANDBOX_POLICY: SandboxPolicy = SandboxPolicy.DangerFullAccess

        /**
         * The sandbox of the merge risk reviewer (bl-214): Codex runs the commands it holds as known safe without asking,
         * and the sandbox keeps them from writing. A command Codex asks about would run outside it once accepted, as the
         * recorded `approval.jsonl` shows, so the reviewer declines every approval, see [ServerRequestPolicy].
         */
        val READ_ONLY_SANDBOX: SandboxMode = SandboxMode.READ_ONLY

        /** The same posture as [READ_ONLY_SANDBOX], with no network, in the policy form `turn/start` takes. */
        val READ_ONLY_SANDBOX_POLICY: SandboxPolicy = SandboxPolicy.ReadOnly(networkAccess = false)

        private val NOTHING = Step.Write(emptyList())

        /** The config table of the MCP servers. */
        private const val MCP_SERVERS = "mcp_servers"

        /** The `thread/start` member that declares the client tools (m3-22), experimental in the pinned protocol. */
        private const val DYNAMIC_TOOLS = "dynamicTools"

        /** The config key that lets Codex run the agent's commands in a login shell; Loop sets it to false. */
        private const val ALLOW_LOGIN_SHELL = "allow_login_shell"

        /**
         * The version at the start of a `userAgent`, after the originator and its slash and up to a blank or the end:
         * `major.minor`, an optional patch and an optional pre-release or build suffix, every part bounded, so a reason
         * never quotes more of what the server wrote. Group 1 is the version, group 2 its major.
         */
        private val USER_AGENT_VERSION = Regex("""^[^/\s]+/((\d{1,9})\.\d{1,9}(?:\.\d{1,9})?(?:[-+][0-9A-Za-z.-]{1,64})?)(?:\s|$)""")
    }
}
