package loop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import loop.api.SecretRedactor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m1-26: an exception no command handled is reported through the command's redactor. */
class InternalErrorTest {
    private val secret = "Lp7secretQ93xZ0vK2mN5"

    private fun host(vararg env: Pair<String, String>) = Host(mapOf(*env), launcherProperty = null, userHome = "/tmp")

    /** A leaf command that learns the secret as LOOP_TEST_SECRET, then throws what [failure] builds. */
    private class Throwing(
        host: Host,
        private val secret: String,
        private val failure: () -> Throwable,
    ) : GuardedCommand("boom", host) {
        override fun body(scope: RedactorScope) {
            scope.redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) }
            throw failure()
        }
    }

    private fun parentOf(leaf: CliktCommand): CliktCommand =
        object : CliktCommand(name = "loop") {
            override fun run() = Unit
        }.subcommands(leaf)

    @Test
    fun `the message is printed through the redactor and the command exits 1`() {
        val command = parentOf(Throwing(host(), secret) { IllegalStateException("cannot reach $secret") })

        val result = command.test("boom")

        assertEquals(1, result.statusCode, result.output)
        assertEquals("loop boom: internal error: cannot reach [redacted:LOOP_TEST_SECRET]\n", result.stderr)
        assertFalse(secret in result.output)
        assertContains(result.stderr, "[redacted:LOOP_TEST_SECRET]")
    }

    @Test
    fun `the stack trace is dropped by default and printed redacted when LOOP_DEBUG is 1`() {
        fun failure() = IllegalStateException("outer $secret", RuntimeException("cause $secret"))

        val quiet = parentOf(Throwing(host(), secret, ::failure)).test("boom")
        val other = parentOf(Throwing(host("LOOP_DEBUG" to "true"), secret, ::failure)).test("boom")
        val debug = parentOf(Throwing(host("LOOP_DEBUG" to "1"), secret, ::failure)).test("boom")

        listOf(quiet, other).forEach { result ->
            assertEquals(1, result.stderr.lines().count { it.isNotBlank() }, result.stderr)
            assertFalse(FRAME.containsMatchIn(result.stderr), result.stderr)
        }
        assertEquals(1, debug.statusCode, debug.output)
        assertContains(debug.stderr, "loop boom: internal error: outer [redacted:LOOP_TEST_SECRET]")
        assertTrue(FRAME.containsMatchIn(debug.stderr), debug.stderr)
        assertContains(debug.stderr, "Caused by: java.lang.RuntimeException: cause [redacted:LOOP_TEST_SECRET]")
        assertFalse(secret in debug.output, debug.output)
    }

    @Test
    fun `a CliktError passes through with its own exit code and message`() {
        val result = parentOf(Throwing(host(), secret) { ProgramResult(4) }).test("boom")
        val usage = parentOf(Throwing(host(), secret) { UsageError("bad flag") }).test("boom")

        assertEquals(4, result.statusCode, result.output)
        assertEquals("", result.stderr)
        assertContains(usage.stderr, "bad flag")
        assertFalse("internal error" in usage.output, usage.output)
    }

    @Test
    fun `a CancellationException is reported as an internal error`() {
        val result = parentOf(Throwing(host(), secret) { CancellationException("stopped $secret") }).test("boom")

        assertEquals(1, result.statusCode, result.output)
        assertEquals("loop boom: internal error: stopped [redacted:LOOP_TEST_SECRET]\n", result.stderr)
    }

    @Test
    fun `every leaf command is guarded`() {
        fun leaves(command: CoreCliktCommand): List<CoreCliktCommand> {
            val subs = command.registeredSubcommands()
            return if (subs.isEmpty()) listOf(command) else subs.flatMap { leaves(it) }
        }

        val found = leaves(LoopCommand(host()))

        assertEquals(
            listOf(
                "run",
                "stop",
                "drain",
                "status",
                "trace",
                "show",
                "log",
                "resume",
                "approve",
                "answer",
                "move",
                "comment",
                "mcp",
                "install",
            ),
            found.map { it.commandName },
        )
        found.forEach { assertTrue(it is GuardedCommand, "${it.commandName} is not a GuardedCommand") }
    }

    @Test
    fun `the subcommand names of the fallback are the names registered on loop`() {
        fun names(command: CoreCliktCommand): Set<String> =
            command.registeredSubcommands().fold(emptySet()) { acc, sub -> acc + sub.commandName + names(sub) }

        assertEquals(names(LoopCommand(host())), SUBCOMMAND_NAMES + TOOL_SUBCOMMANDS)
    }

    private companion object {
        /** A stack frame line, whatever the whitespace before `at`. */
        val FRAME = Regex("""(?m)^\s+at \S+\(""")
    }
}
