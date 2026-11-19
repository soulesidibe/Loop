package loop.core.verify

import loop.api.Redactor
import loop.api.SecretRedactor
import loop.core.workflow.TokenCounters
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m2-06: the findings of a refuted check, as the agent reads them in its guidance and in `loop.verify_findings`. */
class VerifyFindingsTest {
    private fun failed(
        output: String,
        exitCode: Int? = 1,
        timedOut: Boolean = false,
    ) = CheckResult(exitCode, timedOut = timedOut, durationMs = 4200, output = output)

    @Test
    fun `the text names the check, its exit code and the last lines of its output`() {
        val output = (1..50).joinToString("\n", postfix = "\n") { "line $it" } + "BUILD FAILED\n"

        val text = VerifyFindings.of("./gradlew test", failed(output), Redactor.NONE).text

        assertEquals(
            "Your last result was refuted: Loop ran its checks and one failed, so the issue did not move. " +
                "Fix what the check found without changing or skipping it, then move the issue again.\n" +
                "Check: ./gradlew test\n" +
                "Exit code: 1\n" +
                "Last 20 lines of its output:\n" +
                (32..50).joinToString("\n", postfix = "\n") { "line $it" } +
                "BUILD FAILED",
            text,
        )
    }

    @Test
    fun `the output part stays bounded whatever the output`() {
        val longLine = "x".repeat(10_000)
        val output = (1..5_000).joinToString("\n") { "$it $longLine" }

        val text = VerifyFindings.of("c".repeat(1_000), failed(output), Redactor.NONE).text

        assertTrue(TokenCounters.estimate.count(text) <= VerifyFindings.TOOL_OUTPUT_TOKENS, "${text.length} chars")
        assertContains(text, "Check: ${"c".repeat(200)}\n")
        assertFalse("c".repeat(201) in text)
        assertTrue(text.lines().last().startsWith("5000 x"), text.lines().last().take(40))
        val outputLines = text.substringAfter("lines of its output:\n").lines()
        assertTrue(outputLines.size in 2..VerifyFindings.OUTPUT_LINES, "${outputLines.size} lines")
        assertTrue(outputLines.all { it.length <= VerifyFindings.LINE_MAX }, "an output line is longer than ${VerifyFindings.LINE_MAX}")
    }

    @Test
    fun `m3-14 the findings are trimmed to tool_output_tokens, the check, its exit code and the newest lines kept`() {
        val output = (1..50).joinToString("\n") { "failing item $it with a line of detail" }

        val text =
            VerifyFindings
                .of(
                    "./gradlew test",
                    failed(output),
                    Redactor.NONE,
                    maxTokens = 120,
                    counter = TokenCounters.estimate,
                ).text

        assertTrue(TokenCounters.estimate.count(text) <= 120, "${TokenCounters.estimate.count(text)} tokens")
        assertContains(text, "Check: ./gradlew test\nExit code: 1\n")
        assertTrue(text.endsWith("failing item 50 with a line of detail"), text)
        val shown = text.substringAfter("lines of its output:\n").lines()
        assertTrue(shown.size in 1 until VerifyFindings.OUTPUT_LINES, "${shown.size} lines")
        assertTrue(Regex("Last ${shown.size} lines of its output:").containsMatchIn(text), text)

        val none = VerifyFindings.of("./gradlew test", failed(output), Redactor.NONE, maxTokens = 10, counter = TokenCounters.estimate).text
        assertTrue(none.endsWith("Exit code: 1\nIts output is left out to fit context.tool_output_tokens."), none)
    }

    @Test
    fun `a timed out check has no exit code and says so, an empty output says so`() {
        val text = VerifyFindings.of("sleep 99", failed("", exitCode = null, timedOut = true), Redactor.NONE).text

        assertContains(text, "Check: sleep 99\nExit code: none, it timed out after 4.2 s\nIts output was empty.")
    }

    @Test
    fun `trailing blank lines are not output lines`() {
        val text = VerifyFindings.of("x", failed("one\ntwo\n\n\n"), Redactor.NONE).text

        assertTrue(text.endsWith("Last 2 lines of its output:\none\ntwo"), text)
    }

    @Test
    fun `secrets in the command and the output are redacted`() {
        val redactor = SecretRedactor().apply { learn(mapOf("TOKEN" to "super-secret-value")) }

        val text = VerifyFindings.of("echo super-secret-value", failed("got super-secret-value\n"), redactor).text

        assertFalse("super-secret-value" in text, text)
    }

    @Test
    fun `a hidden check's findings say a hidden check failed, with neither its content nor its output (m2-14)`() {
        val text = VerifyFindings.hidden().text

        assertEquals(
            "Your last result was refuted: Loop ran its checks and a hidden check failed, so the issue did not move. " +
                "Hidden checks stay hidden: their command, content and output are never shown. " +
                "Check the work against every acceptance criterion of the issue, then move the issue again.",
            text,
        )
        assertFalse("Exit code" in text, text)
        assertTrue(TokenCounters.estimate.count(text) < VerifyFindings.TOOL_OUTPUT_TOKENS)
    }

    @Test
    fun `the findings of an uncommitted work tree name the uncommitted and the untracked files (m2-25)`() {
        val text = VerifyFindings.uncommitted(UncommittedFiles(listOf("Config/Version.xcconfig"), listOf("notes.txt")), Redactor.NONE).text

        assertEquals(
            "Your last result was refuted: every check passed, but the work tree holds files that are not committed, " +
                "and a pull request carries only commits, so the issue did not move. Commit what belongs to the work, " +
                "restore or remove what does not, such as files a check or a build regenerates, then move the issue again.\n" +
                "Uncommitted changes:\n- Config/Version.xcconfig\n" +
                "Untracked files:\n- notes.txt",
            text,
        )
    }

    @Test
    fun `the uncommitted file lists stay bounded and redacted, and an empty list says so (m2-25)`() {
        val redactor = SecretRedactor().apply { learn(mapOf("TOKEN" to "super-secret-value")) }
        val many = (1..500).map { "dir/file-$it-super-secret-value.txt" }

        val text = VerifyFindings.uncommitted(UncommittedFiles(many, many), redactor).text

        assertFalse("super-secret-value" in text, text)
        assertContains(text, "- dir/file-10-")
        assertFalse("- dir/file-11-" in text, text)
        assertContains(text, "and 490 more")
        assertTrue(TokenCounters.estimate.count(text) < VerifyFindings.TOOL_OUTPUT_TOKENS, "${text.length}")

        val unknown = VerifyFindings.uncommitted(UncommittedFiles(emptyList(), emptyList()), Redactor.NONE).text
        assertTrue(unknown.endsWith("Loop could not list the files: run git status to see them."), unknown)
    }
}
