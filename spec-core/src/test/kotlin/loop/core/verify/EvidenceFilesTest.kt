package loop.core.verify

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.SecretRedactor
import loop.core.orchestrator.attemptFile
import loop.core.orchestrator.attemptsFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** m2-05: each check's output saved as an evidence file beside the attempt file, redacted before writing. */
class EvidenceFilesTest {
    private val home: Path = createTempDirectory("loop-evidence").toRealPath()
    private val project = ProjectId("p1")
    private val attempt = AttemptId("a1")
    private val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to "s3cr3t-value")) }
    private val files = EvidenceFiles(home, redactor)

    private fun evidence(
        check: Int = 0,
        output: String = "BUILD SUCCESSFUL\n",
        exitCode: Int? = 0,
    ) = Evidence(
        check = check,
        command = "./gradlew build",
        exitCode = exitCode,
        timedOut = false,
        durationMs = 2100,
        fingerprint = "git:abc:def:123",
        output = output,
        outputTruncated = false,
    )

    @Test
    fun `the evidence sits beside the attempt file, a header then the full output`() {
        val output = (1..5000).joinToString("\n") { "line $it" } + "\n"

        val name = files.write(project, attempt, evidence(output = output))

        val file = attemptsFolder(home, project).resolve(name)
        assertEquals(attemptFile(home, project, attempt).parent, file.parent)
        assertEquals("a1.evidence-1.log", name)
        val text = file.readText()
        assertEquals(
            listOf(
                "attempt: a1",
                "check: 0",
                "command: ./gradlew build",
                "exit_code: 0",
                "timed_out: false",
                "duration_ms: 2100",
                "fingerprint: git:abc:def:123",
                "output_truncated: false",
                "",
            ),
            text.lines().take(9),
        )
        assertTrue(text.endsWith(output), "the output is kept whole, not a tail")
        assertEquals(output, files.read(project, name).getOrThrow().substringAfter("\n\n"))
    }

    @Test
    fun `judgment calls are an evidence file of their own, numbered with the check runs, one criterion a line (m2-09)`() {
        files.write(project, attempt, evidence())

        val name =
            files.writeJudgmentCalls(
                project,
                attempt,
                JudgmentCalls("git:abc:def:123", listOf("The docs state the convention.", "A token s3cr3t-value is never shown.")),
            )

        assertEquals("a1.evidence-2.log", name)
        assertEquals(
            listOf(
                "attempt: a1",
                "judgment_calls: 2",
                "fingerprint: git:abc:def:123",
                "",
                "- The docs state the convention.",
                "- A token [redacted:LOOP_TRACKER_TOKEN] is never shown.",
                "",
            ),
            files.read(project, name).getOrThrow().lines(),
        )
    }

    @Test
    fun `a holdout's evidence is stamped holdout, says passed or failed only and keeps no output (m2-14)`() {
        val hidden =
            Evidence(1, "\$LOOP_HOLDOUT/acceptance.sh", null, false, 0, "git:abc:def:123", "", false, holdout = true, result = "failed")
        val name = files.write(project, attempt, hidden)

        val text = attemptsFolder(home, project).resolve(name).readText()

        assertEquals(
            listOf(
                "attempt: ${attempt.value}",
                "check: 1",
                "command: \$LOOP_HOLDOUT/acceptance.sh",
                "fingerprint: git:abc:def:123",
                "holdout: true",
                "result: failed",
                "output: hidden, a holdout check's output is never saved",
                "",
                "",
            ),
            text.lines(),
        )
    }

    @Test
    fun `secrets are redacted before the file is written`() {
        val name = files.write(project, attempt, evidence(output = "token s3cr3t-value and ghp_abcdefghijklmnopqrstuvwxyz0123456789\n"))

        val text = attemptsFolder(home, project).resolve(name).readText()

        assertFalse("s3cr3t-value" in text, text)
        assertFalse("ghp_abcdef" in text, text)
        assertTrue("[redacted:LOOP_TRACKER_TOKEN]" in text, text)
        assertTrue("[redacted:github-token]" in text, text)
    }

    @Test
    fun `each check gets the next number, also after the files a previous process left`() {
        assertEquals("a1.evidence-1.log", files.write(project, attempt, evidence(check = 0)))
        assertEquals("a1.evidence-2.log", files.write(project, attempt, evidence(check = 1)))

        val again = EvidenceFiles(home, redactor)

        assertEquals("a1.evidence-3.log", again.write(project, attempt, evidence(check = 0)))
        assertEquals("a2.evidence-1.log", again.write(project, AttemptId("a2"), evidence()))
    }

    @Test
    fun `a check that did not exit has no exit code line`() {
        val name = files.write(project, attempt, evidence(exitCode = null))

        val text = attemptsFolder(home, project).resolve(name).readText()

        assertFalse("exit_code:" in text, text)
    }

    @Test
    fun `the file is owner only and a symlink in its place is never followed`() {
        val name = files.write(project, attempt, evidence())
        val permissions = Files.getPosixFilePermissions(attemptsFolder(home, project).resolve(name))
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)

        val target = home.resolve("elsewhere.txt")
        target.writeText("untouched")
        val folder = attemptsFolder(home, project).createDirectories()
        folder.resolve("a3.evidence-1.log").createSymbolicLinkPointingTo(target)

        val written = files.write(project, AttemptId("a3"), evidence())

        assertEquals("a3.evidence-2.log", written)
        assertEquals("untouched", target.readText())
    }

    @Test
    fun `reading a name that is not an evidence file of the folder fails`() {
        files.write(project, attempt, evidence())

        assertTrue(files.read(project, "../../etc/passwd").isFailure)
        assertTrue(files.read(project, "a1.jsonl.gz").isFailure)
        assertTrue(files.read(project, "a1.evidence-9.log").isFailure)
        assertFalse(attemptsFolder(home, project).resolve("a1.evidence-9.log").exists())
    }

    @Test
    fun `a submitted result is saved beside the evidence, redacted, with the next number of its attempt (m2-15)`() {
        val text = "{\"status\": \"done\", \"summary\": \"used s3cr3t-value\"}\n"

        val first = files.writeResult(project, attempt, text)
        val second = files.writeResult(project, attempt, "{}")

        assertEquals("a1.result-1.json", first)
        assertEquals("a1.result-2.json", second)
        val file = attemptsFolder(home, project).resolve(first)
        assertEquals(attemptFile(home, project, attempt).parent, file.parent)
        assertEquals("{\"status\": \"done\", \"summary\": \"used [redacted:LOOP_TRACKER_TOKEN]\"}\n", file.readText())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        assertEquals("a1.evidence-1.log", files.write(project, attempt, evidence()), "results and evidence number apart")
    }
}
