package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking

/**
 * `loop tool move <state>` (plan D11, U2, m1-23). Run by the agent process of an attempt: it reads
 * the attempt's tool config snapshot, never `WORKFLOW.md`, refuses a state the tracker does not
 * define or an attempt that is not live, then queues the move in the attempt directory and records
 * `issue_move_requested`. Loop applies the move host side when the turn ends. It writes nothing to
 * the tracker. A refusal queues and records nothing. The validation and queueing are [queueMove], which
 * the `move` tool of `loop tool mcp` calls too (m2-17).
 */
class ToolMoveCommand(
    private val host: Host,
) : GuardedCommand("move", host) {
    private val state by argument(name = "state", help = "The tracker state to move the issue to")

    override fun help(context: Context): String = "Queue a move of the current attempt's issue to another state, applied when the turn ends"

    override fun body(scope: RedactorScope) {
        val tool = AttemptTool("move", host, scope) { echo(it, err = true) }
        val env = tool.env()
        echo(runBlocking { tool.queueMove(env, state) })
    }
}
