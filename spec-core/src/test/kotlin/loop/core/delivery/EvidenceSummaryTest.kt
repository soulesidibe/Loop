package loop.core.delivery

import kotlin.test.Test
import kotlin.test.assertEquals

/** m2-24: the evidence summary Loop posts on an issue whose verified result waits for approval. */
class EvidenceSummaryTest {
    private val attempt = "0f3c9a2e-4b7d-4e1a-9c2b-1d2e3f4a5b6c"

    @Test
    fun `the summary names the attempt, the fingerprint, each check and that an operator approves it`() {
        val text =
            EvidenceSummary.text(
                identifier = "t-1",
                attemptId = attempt,
                to = "review",
                fingerprint = "git:abc:def:123",
                checks =
                    listOf(
                        EvidenceSummary.Check(
                            0,
                            "./gradlew --no-daemon build",
                            exitCode = 0,
                            durationMs = 42_100,
                            rerun = false,
                            "$attempt.evidence-1.log",
                        ),
                        EvidenceSummary.Check(1, "file_exists docs/usage.md", exitCode = 0, durationMs = 3, rerun = true, null),
                    ),
            )

        assertEquals(
            """
            |Loop verified the result of attempt 0f3c9a2e: every check passed.
            |
            |Fingerprint: git:abc:def:123
            |Checks:
            |- check 0, ./gradlew --no-daemon build: passed with exit code 0 in 42.1s, evidence $attempt.evidence-1.log
            |- check 1, file_exists docs/usage.md: passed on its rerun with exit code 0 in 3ms, no evidence saved
            |
            |The delivery policy is human: t-1 moves to review once an operator approves it.
            |`loop show $attempt --evidence --check <n>` prints the evidence of check n.
            """.trimMargin(),
            text,
        )
    }

    @Test
    fun `the summary holds no loop approve command an agent reading the issue could run`() {
        val text = EvidenceSummary.text("t-1", attempt, "review", "unknown", listOf(EvidenceSummary.Check(0, "true", 0, 1, false, null)))

        assertEquals(false, "loop approve" in text, text)
        assertEquals(false, "loop resume" in text, text)
    }

    @Test
    fun `a long command shows its first line, cut to 200 characters`() {
        val command = "x".repeat(300) + "\nsecond line"
        val text = EvidenceSummary.text("t-1", attempt, "review", "unknown", listOf(EvidenceSummary.Check(0, command, 0, 1, false, null)))

        val line = text.lines().single { it.startsWith("- check 0") }
        assertEquals("- check 0, ${"x".repeat(200)}: passed with exit code 0 in 1ms, no evidence saved", line)
    }

    @Test
    fun `a hidden check shows only that it passed, never its command, exit code, duration or evidence`() {
        val hidden = EvidenceSummary.Check(2, "holdout.sh --secret", 0, 1_234, rerun = false, "$attempt.evidence-3.log", holdout = true)
        val text = EvidenceSummary.text("t-1", attempt, "review", "unknown", listOf(hidden))

        assertEquals("- hidden check 2: passed", text.lines().single { it.startsWith("- ") })
        assertEquals(false, "holdout.sh" in text || "evidence-3" in text || "1.2" in text)
    }
}
