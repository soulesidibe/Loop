package loop.runner.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.JSONRPCError
import loop.runner.codex.protocol.JSONRPCErrorError
import loop.runner.codex.protocol.JSONRPCResponse
import loop.runner.codex.protocol.ServerNotification
import loop.runner.codex.protocol.ServerRequest
import loop.runner.codex.protocol.v2.CodexErrorInfo
import loop.runner.codex.protocol.v2.ThreadResumeResponse
import loop.runner.codex.protocol.v2.ThreadStartResponse
import loop.runner.codex.protocol.v2.Turn
import loop.runner.codex.protocol.v2.TurnCompletedNotification
import loop.runner.codex.protocol.v2.TurnError
import loop.runner.codex.protocol.v2.TurnStartResponse
import loop.runner.codex.protocol.v2.TurnStatus
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A scripted codex app server (m3-17): a bash script in its own folder that replays [Script] steps in order, the base
 * the Codex runner's tests stand on until recorded sessions exist (m3-03). Start it as the runner does, by the words of
 * [command] with no shell (m3-20).
 *
 * Messages are built from the generated types of the pinned schema and written with [CodexJson.format], one per line,
 * through the base type of a union so the `method` tag is written. [Script.replay] takes recorded lines and refuses
 * one that is not a protocol message; [Script.raw] writes anything, for the malformed cases. Each line is kept in its
 * own file and written with `cat`, so no shell quoting touches it.
 *
 * After its last step the script reads stdin until it closes, recording each line, then exits 0, as a real app server
 * waits for its client; an [Script.exit] step ends it earlier. Every line it read is in [received], one per line.
 */
class FakeAppServer private constructor(
    /** The script, an absolute path. */
    val script: Path,
    /** The client lines the script read from stdin, one per line; absent until it read one. */
    val received: Path,
) {
    /** The value of `runners.codex.command` that starts this fake: its script path, quoted so a blank in it stays in one word. */
    val command: String get() = "'$script'"

    /** The steps of a fake app server, written in order. */
    class Script internal constructor(
        private val dir: Path,
        private val received: Path,
    ) {
        private val body = StringBuilder()
        private var files = 0

        /** Writes a server notification on stdout, its line in two writes with a pause between when [split]. */
        fun notification(
            notification: ServerNotification,
            split: Boolean = false,
        ) = stdout(CodexJson.format.encodeToString(ServerNotification.serializer(), notification), split)

        /** Writes a server request on stdout, an approval or a tool call for example. */
        fun request(
            request: ServerRequest,
            split: Boolean = false,
        ) = stdout(CodexJson.format.encodeToString(ServerRequest.serializer(), request), split)

        /**
         * Answers the client's startup in the pinned protocol's order (m3-18): reads `initialize` and answers it, reads
         * `initialized` and `thread/start` and answers with the thread [threadId], reads `turn/start` and answers with
         * the turn [turnId]. Each answer is written only once its request was read.
         */
        fun handshake(
            threadId: String = THREAD_ID,
            turnId: String = TURN_ID,
        ) {
            awaitClient()
            response(1, initializeResult())
            awaitClient()
            awaitClient()
            response(2, threadStartResult(threadId))
            awaitClient()
            response(3, turnStartResult(turnId))
        }

        /**
         * Answers a strict MCP startup (m3-20): reads `initialize` and answers it, reads `initialized` and
         * `config/read` and answers with [config], reads `thread/start` and answers with the thread [threadId], reads
         * `turn/start` and answers with the turn [turnId]. Request ids 1 to 4.
         */
        fun strictHandshake(
            config: JsonElement = configReadResult(),
            threadId: String = THREAD_ID,
            turnId: String = TURN_ID,
        ) {
            awaitClient()
            response(1, initializeResult())
            awaitClient()
            awaitClient()
            response(2, config)
            awaitClient()
            response(3, threadStartResult(threadId))
            awaitClient()
            response(4, turnStartResult(turnId))
        }

        /**
         * Answers the startup of a resumed thread (bl-212): reads `initialize` and answers it, reads `initialized`, then
         * with [config] reads `config/read` and answers with it, reads `thread/resume` and answers with the thread
         * [threadId], reads `turn/start` and answers with the turn [turnId]. Request ids 1 to 3, or 1 to 4 with [config].
         */
        fun resumeHandshake(
            threadId: String = THREAD_ID,
            turnId: String = TURN_ID,
            config: JsonElement? = null,
        ) {
            awaitClient()
            response(1, initializeResult())
            awaitClient()
            awaitClient()
            var id = 2L
            if (config != null) {
                response(id++, config)
                awaitClient()
            }
            response(id++, threadResumeResult(threadId))
            awaitClient()
            response(id, turnStartResult(turnId))
        }

        /** Writes the server request [method] with the request id [id] and [params], as the protocol frames one. */
        fun serverRequest(
            id: Long,
            method: String,
            params: JsonObject,
        ) = stdout(serverRequestLine(id, method, params), split = false)

        /**
         * Answers a continuation turn (m3-19): reads the client's `turn/start` request [id] on the open thread and
         * answers it with the turn [turnId].
         */
        fun continuation(
            id: Long,
            turnId: String,
        ) {
            awaitClient()
            response(id, turnStartResult(turnId))
        }

        /**
         * Writes the `turn/completed` notification of the turn [turnId] on the thread [threadId], ending with [status],
         * its [error] typed by [errorInfo] when both are set.
         */
        fun turnCompleted(
            turnId: String = TURN_ID,
            status: TurnStatus = TurnStatus.COMPLETED,
            error: String? = null,
            threadId: String = THREAD_ID,
            errorInfo: CodexErrorInfo? = null,
        ) = notification(turnCompletedNotification(turnId, status, error, threadId, errorInfo))

        /** Writes the response to the client's request [id] with [result], the method's response type as JSON. */
        fun response(
            id: Long,
            result: JsonElement,
            split: Boolean = false,
        ) = stdout(CodexJson.format.encodeToString(JSONRPCResponse.serializer(), JSONRPCResponse(JsonPrimitive(id), result)), split)

        /** Writes an error response to the client's request [id]. */
        fun error(
            id: Long,
            code: Long,
            message: String,
        ) {
            val error = JSONRPCError(JSONRPCErrorError(code = code, message = message), JsonPrimitive(id))
            stdout(CodexJson.format.encodeToString(JSONRPCError.serializer(), error), split = false)
        }

        /** Writes recorded protocol [lines] on stdout, blank lines skipped; a line that is not a protocol message is refused. */
        fun replay(lines: List<String>) {
            lines.filter { it.isNotBlank() }.forEach { line ->
                val frame = ProtocolFraming.decode(line)
                require(frame !is Frame.Malformed) { "a replayed line is not a protocol message (${frame.reason()}): $line" }
                stdout(line, split = false)
            }
        }

        /**
         * Replays a recorded session (m3-03), the server's lines in order, waiting for the client where the real server
         * did: before the answer to the client's request `id`, it reads client lines until it has read that request,
         * so `initialized` and `thread/start` are both read before the `thread/start` answer; after a server request,
         * it reads client lines until it has read the answer to it. A client that never answers, as the runner does
         * for `item/tool/requestUserInput`, leaves it waiting until stdin closes, and it exits 0. Lines are refused as
         * [replay] refuses them.
         */
        fun replaySession(lines: List<String>) {
            lines.filter { it.isNotBlank() }.forEach { line ->
                val frame = ProtocolFraming.decode(line)
                require(frame !is Frame.Malformed) { "a replayed line is not a protocol message (${frame.reason()}): $line" }
                val message = CodexJson.format.parseToJsonElement(line).jsonObject
                val id = message["id"]
                when {
                    id == null -> stdout(line, split = false)
                    "method" in message -> {
                        stdout(line, split = false)
                        awaitId(id)
                    }
                    else -> {
                        awaitId(id)
                        stdout(line, split = false)
                    }
                }
            }
        }

        /** Reads client lines, each recorded in [received], until one carries the JSON-RPC id [id]; a closed stdin exits 0. */
        private fun awaitId(id: JsonElement) {
            val member = "\"id\":$id"
            body.append("await_id '${member.replace("'", "'\\''")}'\n")
        }

        /** Writes [line] on stdout as it is, valid or not. */
        fun raw(
            line: String,
            split: Boolean = false,
        ) = stdout(line, split)

        /** Writes [line] on stderr, where a real server writes its diagnostics. */
        fun stderr(line: String) {
            body.append("cat '${file(line + "\n")}' >&2\n")
        }

        /** Reads one client line from stdin and records it in [received]; a closed stdin exits 0. */
        fun awaitClient() {
            body.append("IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$received'\n")
        }

        /** Runs [command] in the script's shell, in the server's working directory. */
        fun shell(command: String) {
            body.append(command).append('\n')
        }

        /** Exits with [code] at once. */
        fun exit(code: Int) {
            body.append("exit $code\n")
        }

        private fun stdout(
            line: String,
            split: Boolean,
        ) {
            if (!split) {
                body.append("cat '${file(line + "\n")}'\n")
                return
            }
            val at = line.length / 2
            body.append("cat '${file(line.substring(0, at))}'\nsleep $SPLIT_PAUSE\ncat '${file(line.substring(at) + "\n")}'\n")
        }

        private fun file(text: String): Path = dir.resolve("line-${++files}.txt").also { it.writeText(text) }

        private fun Frame.reason(): String = (this as? Frame.Malformed)?.reason ?: "valid"

        internal fun text(): String =
            "#!/bin/bash\n" + AWAIT_ID.replace("RECEIVED", received.toString()) + body +
                "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$received'; done\nexit 0\n"
    }

    companion object {
        /** The thread id [Script.handshake] answers with by default. */
        const val THREAD_ID = "019a0000-0000-7000-8000-000000000001"

        /** The turn id [Script.handshake] answers with by default. */
        const val TURN_ID = "019a0000-0000-7000-8000-000000000002"

        /**
         * The read timeout of the tests that are not about it. A fake is a new bash script, and on a loaded machine, a
         * full build beside other agents' builds, starting one took more than the 5 s default, so a test failed with
         * `response_timeout` before the script read its first line. The read timeout tests set their own.
         */
        val READ_TIMEOUT: Duration = 60.seconds

        /** The real `initialize` and `thread/start` responses of codex 0.167.0, recorded in m3-02. */
        private val real: List<JsonObject> by lazy {
            FakeAppServer::class.java
                .getResource("/real/initialize_and_thread_start.jsonl")!!
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .map { CodexJson.format.parseToJsonElement(it).jsonObject }
                .filter { "result" in it }
        }

        /**
         * The real `config/read` result of codex 0.167.0, recorded in m3-20 from a `CODEX_HOME` whose `config.toml`
         * holds one MCP server, `operator_server`, paths scrubbed.
         */
        fun configReadResult(): JsonElement =
            CodexJson.format
                .parseToJsonElement(FakeAppServer::class.java.getResource("/real/config_read.json")!!.readText())
                .jsonObject
                .getValue("result")

        /** One server request line, its `method`, request [id] and [params]. */
        fun serverRequestLine(
            id: Long,
            method: String,
            params: JsonObject,
        ): String = JsonObject(mapOf("id" to JsonPrimitive(id), "method" to JsonPrimitive(method), "params" to params)).toString()

        /** The real `initialize` result. */
        fun initializeResult(): JsonElement = real[0].getValue("result")

        /** The real `thread/start` result, its thread id [threadId]. */
        fun threadStartResult(threadId: String): JsonElement {
            val response = CodexJson.format.decodeFromJsonElement(ThreadStartResponse.serializer(), real[1].getValue("result"))
            val renamed = response.copy(thread = response.thread.copy(id = threadId, sessionId = threadId))
            return CodexJson.format.encodeToJsonElement(ThreadStartResponse.serializer(), renamed)
        }

        /** A `thread/resume` result (bl-212), the real `thread/start` result in the resume response type, its thread id [threadId]. */
        fun threadResumeResult(threadId: String): JsonElement {
            val start = CodexJson.format.decodeFromJsonElement(ThreadStartResponse.serializer(), threadStartResult(threadId))
            val resume =
                ThreadResumeResponse(
                    approvalPolicy = start.approvalPolicy,
                    approvalsReviewer = start.approvalsReviewer,
                    cwd = start.cwd,
                    model = start.model,
                    modelProvider = start.modelProvider,
                    sandbox = start.sandbox,
                    thread = start.thread,
                )
            return CodexJson.format.encodeToJsonElement(ThreadResumeResponse.serializer(), resume)
        }

        /** A `turn/start` result as the pinned schema writes it: the turn [turnId], in progress, no items yet. */
        fun turnStartResult(turnId: String): JsonElement =
            CodexJson.format.encodeToJsonElement(
                TurnStartResponse.serializer(),
                TurnStartResponse(Turn(id = turnId, items = emptyList(), status = TurnStatus.IN_PROGRESS)),
            )

        /**
         * The `turn/completed` notification of the turn [turnId] on [threadId], with [status] and the error [error],
         * typed by [errorInfo].
         */
        fun turnCompletedNotification(
            turnId: String = TURN_ID,
            status: TurnStatus = TurnStatus.COMPLETED,
            error: String? = null,
            threadId: String = THREAD_ID,
            errorInfo: CodexErrorInfo? = null,
        ): ServerNotification =
            ServerNotification.TurnCompleted(
                params =
                    TurnCompletedNotification(
                        threadId = threadId,
                        turn =
                            Turn(
                                id = turnId,
                                items = emptyList(),
                                status = status,
                                error = error?.let { TurnError(message = it, codexErrorInfo = errorInfo) },
                            ),
                    ),
            )

        /**
         * The shell function [Script.replaySession] waits with: reads client lines, recording each, until one holds the
         * member `"id":<id>` followed by `,` or `}`, so id 1 never matches id 10; a closed stdin exits the script 0.
         */
        private val AWAIT_ID =
            """
            await_id() {
              while IFS= read -r line; do
                printf '%s\n' "${'$'}line" >> 'RECEIVED'
                case "${'$'}line" in *"${'$'}1,"*|*"${'$'}1}"*) return 0 ;; esac
              done
              exit 0
            }

            """.trimIndent() + "\n"

        /** The pause between the two writes of a split line, in seconds: long enough for the reader to see two reads. */
        private const val SPLIT_PAUSE = "0.3"

        /** Writes the fake under [dir] (a folder of its own, created if needed), with the steps of [steps]. */
        fun write(
            dir: Path,
            steps: Script.() -> Unit,
        ): FakeAppServer {
            val home = dir.resolve("fake-app-server").createDirectories()
            val received = home.resolve("received.jsonl")
            val script = home.resolve("app-server.sh")
            script.writeText(Script(home, received).apply(steps).text())
            script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            return FakeAppServer(script, received)
        }
    }
}
