package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import loop.core.events.ResultRequested
import loop.core.events.Results
import loop.core.orchestrator.SavedToolOutput
import loop.core.orchestrator.ToolOutputQueue
import loop.core.orchestrator.ToolRequest
import loop.core.verify.ResultFile
import loop.core.verify.SubmittedResult
import loop.core.workflow.TokenCounters
import loop.core.workflow.ToolOutputTrim
import loop.mcp.AskHandler
import loop.mcp.CommentHandler
import loop.mcp.LoopMcpServer
import loop.mcp.MoveHandler
import loop.mcp.ReplyTrimmer
import loop.mcp.ResourceHandler
import loop.mcp.SubmitResultHandler
import loop.mcp.ToolReply

/**
 * `loop tool mcp` (m2-16, story 67): Loop's MCP server on stdio, which the Claude Code runner configures for each
 * attempt. Like the other `loop tool` commands it reads only the attempt's tool config snapshot and the `LOOP_*` ids,
 * never `WORKFLOW.md`, holds no credential and writes nothing to the tracker: outside an attempt it refuses to start
 * with exit 4, as they do.
 *
 * Its `submit_result` tool takes the PLAN section 7 result. A call is validated like the result file
 * ([SubmittedResult.parse]) and refused at once when it is invalid or its attempt is not live, as a tool error that
 * ends with `Nothing queued.`, so the agent can fix it in the same turn. A valid one is queued in the attempt
 * directory and recorded as `result_requested`; Loop takes it host side when the turn ends, on the verdict path of
 * `.loop/result.json`.
 *
 * Its `move` and `comment` tools (m2-17) are `loop tool move` and `loop tool comment` over MCP: they call the same
 * [queueMove] and [queueComment], so they queue the same requests with the same refusals, replies and events, and
 * Loop applies them at the same drain. A refusal is a tool error that ends with `Nothing queued.`, as for
 * `submit_result`. Stdout belongs to the protocol: every line this command prints goes to stderr.
 *
 * Since m3-14 every reply, a refusal included, is trimmed to the attempt's `context.tool_output_tokens`, read from the
 * snapshot, and the full text of a trimmed one goes to the attempt file.
 *
 * Since m4-25, when the snapshot holds resources, it also serves `request_resource` and `release_resource`
 * ([ResourceTools]), answered inside the turn: this process takes, queues or marks the lock in the store itself, while
 * `loop run` runs every provider.
 *
 * Since m4-31 it serves `ask` ([queueAsk]), queued like `comment`: it answers `pending` at once, and when the turn ends
 * Loop posts the question on the issue and the issue waits as `Waiting(ask)`.
 */
class ToolMcpCommand(
    private val host: Host,
) : GuardedCommand("mcp", host) {
    override fun help(context: Context): String = "Serve Loop's tools to the attempt's agent over MCP on stdio"

    override fun body(scope: RedactorScope) {
        val startup = AttemptTool(NAME, host, scope) { echo(it, err = true) }
        val env = startup.env()
        runBlocking {
            LoopMcpServer.serve(
                System.`in`,
                System.out,
                host.loopVersion,
                SubmitResultHandler { submit(scope, env, it) },
                MoveHandler { state -> call(scope) { queueMove(env, state) } },
                CommentHandler { text -> call(scope) { queueComment(env, text) } },
                ReplyTrimmer { tool, reply -> trim(env, tool, reply) },
                resourceHandler(scope, env),
                AskHandler { call -> answered(scope) { queueAsk(env, call) } },
            )
        }
    }

    /**
     * [reply] trimmed to the attempt's `context.tool_output_tokens` (m3-14, plan 8.7), counted with the estimate. The
     * full text of a trimmed reply is saved in the attempt directory, and Loop moves it into the attempt file at the
     * drain. A failed save is said on stderr and never fails the call: the agent still gets its reply.
     */
    private fun trim(
        env: AttemptTool.Env,
        tool: String,
        reply: ToolReply,
    ): ToolReply {
        val text = ToolOutputTrim.trim(reply.text, env.config.toolOutputTokens, TokenCounters.estimate, FULL_TEXT_AT)
        if (text == reply.text) return reply
        try {
            ToolOutputQueue(env.attemptDir).save(SavedToolOutput(tool, reply.text))
        } catch (e: Exception) {
            echo("loop tool $NAME: cannot keep the full reply of $tool for the attempt file: ${e.sentence()}.", err = true)
        }
        return reply.copy(text = text)
    }

    /** One `submit_result` call: queued and recorded, or refused with the reason as a tool error. */
    private suspend fun submit(
        scope: RedactorScope,
        env: AttemptTool.Env,
        arguments: JsonObject,
    ): ToolReply =
        call(scope) {
            val text = arguments.toString()
            if (text.toByteArray().size > ResultFile.MAX_BYTES) {
                refuse("the result is larger than 1 MiB. ${AttemptTool.NOTHING_QUEUED}", AttemptTool.EXIT_REJECTED_ARGUMENT)
            }
            val result =
                SubmittedResult.parse(text).getOrElse { error ->
                    refuse(
                        "the result is invalid: ${error.message}. Fix it and call submit_result again. ${AttemptTool.NOTHING_QUEUED}",
                        AttemptTool.EXIT_REJECTED_ARGUMENT,
                    )
                }
            val status = result.status.value
            openStore(env).use { store ->
                val started = liveAttempt(store, env)
                enqueue(env, ToolRequest.Result(text), "the result of ${env.issueName}")
                record(store, env, started, ResultRequested, mapOf(Results.STATUS to status), "queued")
            }
            "Queued a $status result for ${env.issueName}. ${appliedWhen(result.status)} Do not call submit_result again."
        }

    /** The resource tools of the attempt (m4-25), null when its workflow declares no resource, so none is advertised. */
    private fun resourceHandler(
        scope: RedactorScope,
        env: AttemptTool.Env,
    ): ResourceHandler? {
        if (env.config.resources.isEmpty()) return null
        return object : ResourceHandler {
            override val names = env.config.resources.map { it.id }

            override suspend fun request(name: String) = answered(scope) { ResourceTools(this, env).request(name) }

            override suspend fun release(name: String) = answered(scope) { ResourceTools(this, env).release(name) }
        }
    }

    /**
     * Runs one resource call (m4-25) or `ask` call (m4-31) with a fresh [AttemptTool]: its answer, or the refusal it
     * printed, such as an attempt that is not live, as a tool error.
     */
    private suspend fun answered(
        scope: RedactorScope,
        block: suspend AttemptTool.() -> ToolReply,
    ): ToolReply {
        var refusal: String? = null
        val tool =
            AttemptTool(NAME, host, scope) { line ->
                refusal = line
                echo(line, err = true)
            }
        return try {
            tool.block()
        } catch (e: ProgramResult) {
            ToolReply(refusal ?: "loop tool $NAME: the call failed. ${AttemptTool.NOTHING_QUEUED}", isError = true)
        }
    }

    /**
     * Runs one tool call with a fresh [AttemptTool]: its line once the request is queued, or the refusal it printed
     * (to stderr, the `loop tool` refusal) as a tool error, so the agent can fix the call in the same turn.
     */
    private suspend fun call(
        scope: RedactorScope,
        block: suspend AttemptTool.() -> String,
    ): ToolReply {
        var refusal: String? = null
        val tool =
            AttemptTool(NAME, host, scope) { line ->
                refusal = line
                echo(line, err = true)
            }
        return try {
            ToolReply(tool.block())
        } catch (e: ProgramResult) {
            ToolReply(refusal ?: "loop tool $NAME: the call failed. ${AttemptTool.NOTHING_QUEUED}", isError = true)
        }
    }

    /** What Loop does with a result of [status] when the turn ends, as the result file's statuses say (m2-15). */
    private fun appliedWhen(status: SubmittedResult.Status): String =
        when (status) {
            SubmittedResult.Status.Done ->
                "Loop applies it when this turn ends: it runs its checks and moves the issue to review only if they pass."
            SubmittedResult.Status.NeedsInfo ->
                "Loop applies it when this turn ends: it comments your question on the issue and moves it to needs_info."
            SubmittedResult.Status.Blocked, SubmittedResult.Status.Failed ->
                "Loop applies it when this turn ends, and the attempt ends as a failure."
        }

    private companion object {
        const val NAME = "mcp"

        /** Where the note of a trimmed reply sends the reader for the full text (m3-14). */
        const val FULL_TEXT_AT = "The full text is in the attempt file."
    }
}
