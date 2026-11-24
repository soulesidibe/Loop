package loop.core.log

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.api.SessionId
import loop.api.TurnId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

enum class LogLevel { INFO, WARN, ERROR }

enum class LogOutcome { STARTED, COMPLETED, FAILED, IDLE, SCHEDULED, PARKED, RESUMED, REACHED, RELEASED, ROTATED }

/** Ids printed after `outcome`, in this order; null ids are omitted. */
data class LogContext(
    val runId: RunId? = null,
    val projectId: ProjectId? = null,
    val issueId: String? = null,
    val issueIdentifier: String? = null,
    val attemptId: AttemptId? = null,
    val sessionId: SessionId? = null,
    val turnId: TurnId? = null,
)

/**
 * Operator log in `key=value` lines (plan U3). Each line goes to [out]; the
 * caller decides where lines end up (stderr in the cli).
 *
 * Order: `ts level event outcome`, the ids of [LogContext], the event keys in
 * the order given, then `reason`. A value is quoted when it is empty or holds a
 * space, a quote, `=`, a backslash or a control character. A reason keeps only
 * its first line, cut to [REASON_MAX] characters.
 *
 * The reason, the issue identifier and every event key value go through [redactor] before
 * they are cut or quoted (m1-11), so no part of a secret survives the cut.
 */
class KeyValueLog(
    private val out: (String) -> Unit,
    private val clock: Clock,
    private val redactor: Redactor,
    /**
     * How the core id of an issue is shown as `issue_id` (m4-47): as the core carries it by default, `IssueIdDisplay.id`
     * in `loop run`, so a project with no extra tracker logs bare ids.
     */
    private val issueIds: (String) -> String = { it },
) {
    fun log(
        level: LogLevel,
        event: String,
        outcome: LogOutcome,
        context: LogContext = LogContext(),
        fields: Map<String, Any?> = emptyMap(),
        reason: String? = null,
    ) {
        val pairs = mutableListOf<Pair<String, Any?>>()
        pairs += "ts" to clock.now().toString()
        pairs += "level" to level.name.lowercase()
        pairs += "event" to event
        pairs += "outcome" to outcome.name.lowercase()
        pairs += "run_id" to context.runId?.value
        pairs += "project_id" to context.projectId?.value
        pairs += "issue_id" to context.issueId?.let(issueIds)
        pairs += "issue_identifier" to context.issueIdentifier?.let(::redact)
        pairs += "attempt_id" to context.attemptId?.value
        pairs += "session_id" to context.sessionId?.value
        pairs += "turn_id" to context.turnId?.value
        fields.forEach { (key, value) -> pairs += key to value?.let { redact(it.toString()) } }
        pairs += "reason" to reason?.let { concise(redact(it)) }
        out(
            pairs
                .filter { it.second != null }
                .joinToString(" ") { (key, value) -> "$key=${format(value.toString())}" },
        )
    }

    /**
     * Runs [write] against the sink named [sink]. A failure is logged as
     * `level=warn event=sink outcome=failed` and never thrown to the caller.
     * Inline so that [write] may suspend when the caller does.
     */
    inline fun guardSink(
        sink: String,
        context: LogContext = LogContext(),
        write: () -> Unit,
    ) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(
                LogLevel.WARN,
                "sink",
                LogOutcome.FAILED,
                context,
                fields = mapOf("sink" to sink),
                reason = e.message ?: e::class.simpleName ?: "unknown error",
            )
        }
    }

    /**
     * [Redactor.redact] never throws by contract; a [Redactor] that breaks it still never stops
     * logging or [guardSink], and its input is never printed (Symphony 17.6).
     */
    private fun redact(text: String): String =
        try {
            redactor.redact(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Redactor.FAILED_MARKER
        } catch (e: StackOverflowError) {
            // A regex engine overflows its stack on some inputs; any Redactor may hit it.
            Redactor.FAILED_MARKER
        }

    private fun concise(reason: String): String = reason.lineSequence().first().take(REASON_MAX)

    private fun format(value: String): String {
        if (value.isNotEmpty() && value.none(::needsQuotes)) return value
        val escaped =
            buildString {
                for (c in value) {
                    when {
                        c == '\\' -> append("\\\\")
                        c == '"' -> append("\\\"")
                        c == '\n' -> append("\\n")
                        c == '\r' -> append("\\r")
                        c == '\t' -> append("\\t")
                        c.isISOControl() -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
            }
        return "\"$escaped\""
    }

    private fun needsQuotes(c: Char): Boolean = c.isWhitespace() || c == '"' || c == '=' || c == '\\' || c.isISOControl()

    private companion object {
        const val REASON_MAX = 200
    }
}
