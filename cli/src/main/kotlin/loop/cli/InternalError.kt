package loop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import loop.api.Redactor
import loop.api.SecretRedactor

/**
 * The redactor an internal error is printed through (m1-26). It starts as the fallback, which
 * knows the Anthropic keys and the token patterns, and the command swaps in its own redactor as
 * soon as it has built it, so a failure before that point is still covered as far as it can be.
 */
class RedactorScope(
    @Volatile var redactor: Redactor,
)

/**
 * A leaf command whose uncaught exceptions are reported through a redactor (m1-26), instead of
 * reaching the JVM's default handler with a raw stack trace. Subclasses implement [body]. A
 * [StartupFailure] becomes the line `loop <command>: <redacted sentence>.` and exit [EXIT_STARTUP]. Every
 * other exception but a [CliktError] and an [Error] becomes the line
 * `loop <command path>: internal error: <redacted message>` and exit 1.
 */
abstract class GuardedCommand(
    name: String,
    private val host: Host,
) : CliktCommand(name = name) {
    /** The command body; set `scope.redactor` once the command's own redactor exists. */
    protected abstract fun body(scope: RedactorScope)

    private var scope = RedactorScope(Redactor.NONE)

    /** [text] through the redactor of the moment, for the failure lines a command prints itself. */
    protected fun redact(text: String): String = scope.redactor.redact(text)

    final override fun run() {
        scope = RedactorScope(fallbackRedactor(host.rawEnv))
        try {
            body(scope)
        } catch (e: CliktError) {
            throw e
        } catch (e: StartupFailure) {
            // bl-78: the one place a startup failure becomes its line and exit code.
            echo(redact("loop $commandName: ${e.sentence}."), err = true)
            throw ProgramResult(EXIT_STARTUP)
        } catch (e: Exception) {
            reportInternalError(e, scope.redactor)
            throw ProgramResult(EXIT_INTERNAL_ERROR)
        }
    }

    /** Prints the internal error line, then the stack trace when [debugEnabled]. */
    protected open fun reportInternalError(
        error: Exception,
        redactor: Redactor,
    ) {
        val path = currentContext.commandNameWithParents().joinToString(" ")
        echo("$path: internal error: ${redactor.redact(error.sentence())}", err = true)
        echoStackTrace(error, redactor)
    }

    /** With `LOOP_DEBUG=1`, the stack trace of [error], redacted as a whole so a secret split across the message and a cause is caught. */
    protected fun echoStackTrace(
        error: Throwable,
        redactor: Redactor,
    ) {
        if (host.env(LOOP_DEBUG) == "1") echo(redactor.redact(error.stackTraceToString()).trimEnd(), err = true)
    }
}

/** The redactor used before a command has its own: the Anthropic keys and the token patterns. */
internal fun fallbackRedactor(env: Map<String, String>): SecretRedactor = SecretRedactor().apply { learn(secretValues(emptyMap(), env)) }

/** `LOOP_DEBUG=1` also prints the redacted stack trace of an internal error. */
internal const val LOOP_DEBUG = "LOOP_DEBUG"

internal const val EXIT_INTERNAL_ERROR = 1
