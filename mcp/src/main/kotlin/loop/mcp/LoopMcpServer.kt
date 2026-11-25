package loop.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.io.OutputStream

/** What a tool answers the agent: one text, a tool error when [isError]. */
data class ToolReply(
    val text: String,
    val isError: Boolean = false,
)

/** Handles a `submit_result` call: [result] is the call's arguments as the agent wrote them, an empty object when it gave none. */
fun interface SubmitResultHandler {
    suspend fun submit(result: JsonObject): ToolReply
}

/** Handles a `move` call (m2-17): [state] is the state the agent asked for, as it wrote it. */
fun interface MoveHandler {
    suspend fun move(state: String): ToolReply
}

/** Handles a `comment` call (m2-17): [text] is the comment as the agent wrote it, not yet redacted or trimmed. */
fun interface CommentHandler {
    suspend fun comment(text: String): ToolReply
}

/**
 * Handles the resource tools (m4-25): `request_resource` and `release_resource`, whose `name` is the id of a resource
 * of `resources:` as the agent wrote it. Unlike the other tools, both are answered inside the turn.
 */
interface ResourceHandler {
    /** The ids of the declared resources, which the `name` argument's description lists. */
    val names: List<String>

    suspend fun request(name: String): ToolReply

    suspend fun release(name: String): ToolReply
}

/**
 * Trims a tool's reply before the agent reads it (m3-14, plan 8.7): [tool] is the tool's name, [reply] the full reply.
 * The trimmer keeps the full text where it belongs, Loop's attempt file.
 */
fun interface ReplyTrimmer {
    suspend fun trim(
        tool: String,
        reply: ToolReply,
    ): ToolReply

    companion object {
        /** Leaves every reply whole. */
        val NONE: ReplyTrimmer = ReplyTrimmer { _, reply -> reply }
    }
}

/**
 * Loop's MCP server (m2-16, story 67), built on the official MCP Kotlin SDK. It serves Loop's tools to an MCP
 * runtime such as Claude Code: `submit_result`, which takes the PLAN section 7 result as its arguments
 * ([SubmitResultSchema]), and since m2-17 the tracker tools `move` and `comment`, which take the state or the text
 * `loop tool move` and `loop tool comment` take. This module only speaks the protocol: it reads a call's arguments,
 * refuses one that lacks its required string, and leaves what a call does to the tool's handler. Since m3-14 every
 * reply, a refusal included, goes through a [ReplyTrimmer] before it is sent. Since m4-25 a server given a
 * [ResourceHandler], which `loop tool mcp` gives when the workflow declares `resources:`, also serves
 * `request_resource` and `release_resource`.
 */
object LoopMcpServer {
    /** The server name in the runtime's MCP config; Claude Code names the tool `mcp__loop__submit_result`. */
    const val NAME = "loop"

    const val SUBMIT_RESULT = "submit_result"

    const val MOVE = "move"

    const val COMMENT = "comment"

    const val REQUEST_RESOURCE = "request_resource"

    const val RELEASE_RESOURCE = "release_resource"

    private const val SUBMIT_RESULT_DESCRIPTION =
        "Submit the result of your attempt, in Loop's result shape. Loop applies it when this turn ends, " +
            "like .loop/result.json. status done: Loop runs its checks and moves the issue to review only if they pass. " +
            "status needs_info: summary is your question. status blocked or failed: summary is the reason, and the attempt " +
            "ends as a failure. Call it once, and never move the issue yourself."

    private const val MOVE_DESCRIPTION =
        "Ask Loop to move your issue to another state of its tracker, like loop tool move. Loop queues the move and " +
            "applies it when this turn ends. In Loop mode, end your attempt with submit_result: move is for needs_info, " +
            "and for runs without Loop mode. Call it once per move."

    private const val COMMENT_DESCRIPTION =
        "Ask Loop to add a comment to your issue, like loop tool comment, for example a question for a human. " +
            "Loop queues the comment, redacted, and adds it when this turn ends. Call it once per comment."

    private const val REQUEST_RESOURCE_DESCRIPTION =
        "Ask Loop for a resource of the workflow, such as a device or a simulator, by its name, before you use it. " +
            "Loop answers at once with a JSON object. status granted: it is yours, with what you need to use it " +
            "(for example a udid or a serial) and what it can and cannot test. status preparing: it is yours and Loop " +
            "is preparing it, call request_resource again with the same name in a few seconds. status queued: position is " +
            "your place in its queue and reason says why it waits: held by another attempt, limit when the machine holds " +
            "as many of its kind as allowed, release one you hold if you do not need both, or dirty when its read only " +
            "check failed. Call request_resource again later to check, your place is kept. Never use a resource before " +
            "it is granted. Call release_resource as soon as you are done with it. When you submit your result, Loop " +
            "takes back any resource you still hold that a check of the verification needs, before the check runs."

    private const val RELEASE_RESOURCE_DESCRIPTION =
        "Give back a resource Loop granted you, by its name, as soon as you are done with it. Loop first runs its " +
            "read only check: when it fails, the resource stays yours and the error gives the difference and the restore " +
            "commands to run before you call release_resource again. Once released, stop using it: Loop gives it back " +
            "and grants it to the next attempt in its queue. Loop also frees it when your attempt ends, and before a " +
            "check of your verification that needs a resource of its kind. Releasing a resource you do not hold is an error."

    /**
     * A server of [version] whose `submit_result`, `move` and `comment` calls go to [submitResult], [move] and [comment],
     * every reply trimmed by [trimmer] (m3-14).
     */
    fun create(
        version: String,
        submitResult: SubmitResultHandler,
        move: MoveHandler,
        comment: CommentHandler,
        trimmer: ReplyTrimmer = ReplyTrimmer.NONE,
        resources: ResourceHandler? = null,
    ): Server {
        val server =
            Server(
                Implementation(name = NAME, version = version),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            )
        server.addTool(
            name = SUBMIT_RESULT,
            description = SUBMIT_RESULT_DESCRIPTION,
            inputSchema = ToolSchema(properties = SubmitResultSchema.properties, required = SubmitResultSchema.required),
        ) { request ->
            val reply = submitResult.submit(request.arguments ?: JsonObject(emptyMap()))
            trimmer.trim(SUBMIT_RESULT, reply).toResult()
        }
        server.addStringTool(MOVE, MOVE_DESCRIPTION, STATE, "The tracker state to move the issue to, for example needs_info.", trimmer) {
            move.move(it)
        }
        server.addStringTool(COMMENT, COMMENT_DESCRIPTION, TEXT, "The comment to add, for example a question for a human.", trimmer) {
            comment.comment(it)
        }
        // m4-25: only for a workflow that declares resources.
        if (resources != null) {
            val names = "The name of a resource of the workflow: ${resources.names.joinToString(", ")}."
            server.addStringTool(REQUEST_RESOURCE, REQUEST_RESOURCE_DESCRIPTION, NAME_ARGUMENT, names, trimmer) { resources.request(it) }
            server.addStringTool(RELEASE_RESOURCE, RELEASE_RESOURCE_DESCRIPTION, NAME_ARGUMENT, names, trimmer) { resources.release(it) }
        }
        return server
    }

    /**
     * Adds a tool whose one required argument [argument] is a string, handed to [handle]. A call without it, or with
     * another JSON type, is a tool error that reaches no handler, so nothing is queued. Every reply goes through
     * [trimmer] (m3-14).
     */
    private fun Server.addStringTool(
        name: String,
        description: String,
        argument: String,
        argumentDescription: String,
        trimmer: ReplyTrimmer,
        handle: suspend (String) -> ToolReply,
    ) {
        val properties =
            buildJsonObject {
                putJsonObject(argument) {
                    put("type", "string")
                    put("description", argumentDescription)
                }
            }
        addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(properties = properties, required = listOf(argument)),
        ) { request ->
            val value = (request.arguments?.get(argument) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val reply = value?.let { handle(it) } ?: ToolReply("$argument is required, a string. $NOTHING_QUEUED", isError = true)
            trimmer.trim(name, reply).toResult()
        }
    }

    private fun ToolReply.toResult() = CallToolResult(content = listOf(TextContent(text)), isError = isError)

    private const val STATE = "state"
    private const val TEXT = "text"
    private const val NAME_ARGUMENT = "name"
    private const val NOTHING_QUEUED = "Nothing queued."

    /**
     * Serves one session over newline delimited JSON-RPC on [input] and [output], the stdio transport an MCP
     * runtime spawns its servers with, until [input] ends, every reply trimmed by [trimmer] (m3-14), with the resource
     * tools when [resources] is given (m4-25). Nothing else may
     * write to [output], so kotlin-logging's startup line, which goes to stdout, is turned off. The SDK's own log goes
     * to SLF4J's no-op provider.
     */
    suspend fun serve(
        input: InputStream,
        output: OutputStream,
        version: String,
        submitResult: SubmitResultHandler,
        move: MoveHandler,
        comment: CommentHandler,
        trimmer: ReplyTrimmer = ReplyTrimmer.NONE,
        resources: ResourceHandler? = null,
    ) {
        quietLogging()
        val server = create(version, submitResult, move, comment, trimmer, resources)
        val transport = StdioServerTransport(input.asSource().buffered(), output.asSink().buffered())
        val closed = CompletableDeferred<Unit>()
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    }

    /** Must run before the SDK first logs; a value the operator set on the command line wins. */
    private fun quietLogging() {
        if (System.getProperty(LOG_STARTUP_MESSAGE) == null) System.setProperty(LOG_STARTUP_MESSAGE, "false")
    }

    private const val LOG_STARTUP_MESSAGE = "kotlin-logging.logStartupMessage"
}
