package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking

/**
 * `loop tool comment <text>` (m1-10, m1-23). Same environment, authorization and exit codes as
 * `loop tool move`: it queues a comment only for the issue of a live attempt of this project, and
 * Loop adds it host side when the turn ends, redacted again with the run's full redactor. The text
 * is redacted (token patterns) before it is trimmed and queued, and `issue_comment_requested`
 * records the length of that text, never the text. A refusal queues and records nothing. The validation and
 * queueing are [queueComment], which the `comment` tool of `loop tool mcp` calls too (m2-17).
 */
class ToolCommentCommand(
    private val host: Host,
) : GuardedCommand("comment", host) {
    private val text by argument(name = "text", help = "The comment to add, for example a question for a human")

    override fun help(context: Context): String = "Queue a comment on the current attempt's issue, applied when the turn ends"

    override fun body(scope: RedactorScope) {
        val tool = AttemptTool("comment", host, scope) { echo(it, err = true) }
        val env = tool.env()
        echo(runBlocking { tool.queueComment(env, text) })
    }
}
