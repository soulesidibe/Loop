package loop.cli

import loop.core.verify.VerifyFindings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * m2-14, end to end with the command runner: a kind's `holdout:` checks live in `<LOOP_HOME>/holdout/`, outside
 * every workspace and the repository, run after the visible checks, and refute a result without showing the
 * agent what they check.
 */
class HoldoutCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** What the hidden check prints when it fails, which the agent must never read. */
    private val secret = "expected the answer 42 from the hidden acceptance test"

    /** Loop mode with a passing visible check and one holdout, [maxTurns] turns per attempt. */
    private fun holdoutMode(maxTurns: Int = 1) =
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: $maxTurns
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                |      holdout:
                |        - { type: command, run: "${'$'}LOOP_HOLDOUT/acceptance.sh" }
                """.trimMargin(),
        )

    /** A line of the hidden check's script, its content, which the agent must never read either. */
    private val scriptMarker = "hidden acceptance script of m2-14"

    /** Writes the hidden check under [loopHome]: it passes once `fixed.txt` is in the workspace, else prints [secret]. */
    private fun hiddenCheck(loopHome: Path = fixture.loopHome): Path {
        val script = loopHome.resolve("holdout").createDirectories().resolve("acceptance.sh")
        script.writeText("#!/bin/sh\n# $scriptMarker\ntest -f fixed.txt && exit 0\necho \"$secret\"\nexit 1\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    /** Each turn of the agent saves its stdin, the turn input, to `inputs/turn-<n>.txt` outside the project. */
    private val inputs: Path by lazy { root.resolve("inputs").createDirectories() }

    /** An agent that saves each turn's input, writes `fixed.txt` from turn [fixOn] on, and submits on every turn. */
    private fun recordingAgent(fixOn: Int) {
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |cat > "$inputs/turn-${'$'}LOOP_TURN_NUMBER.txt"
            |if [ "${'$'}LOOP_TURN_NUMBER" -ge $fixOn ]; then touch fixed.txt; fi
            |loop tool move review
            |
            """.trimMargin(),
        )
    }

    @Test
    fun `a failing holdout refutes the attempt and the guidance says a hidden check failed, without its content or output`() {
        fixture.issue("t-1")
        hiddenCheck()
        recordingAgent(fixOn = 2)
        holdoutMode(maxTurns = 3)

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        val verdicts = fixture.query("SELECT data FROM events WHERE kind = 'verdict' ORDER BY seq").map { it["data"].orEmpty() }
        assertEquals(2, verdicts.size, result.toString())
        assertContains(verdicts[0], "\"verdict\":\"refuted\"")
        assertContains(verdicts[0], "\"checks\":\"2\"")
        assertContains(verdicts[1], "\"verdict\":\"verified\"")
        val guidance = inputs.resolve("turn-2.txt").readText()
        assertContains(guidance, VerifyFindings.hidden().text)
        assertContains(guidance, "a hidden check failed")
        assertFalse(secret in guidance, guidance)
        assertFalse("acceptance.sh" in guidance, guidance)
        assertFalse("Exit code" in guidance, guidance)
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        // Nothing the agent can read outside the holdout folder holds the hidden output.
        val evidence =
            fixture.loopHome
                .resolve("attempts")
                .toFile()
                .walk()
                .filter { it.name.contains(".evidence-") }
                .toList()
        assertTrue(evidence.isNotEmpty())
        evidence.forEach { assertFalse(secret in it.readText(), it.toString()) }
        assertFalse(secret in fixture.humanLog.readText())
        assertContains(fixture.humanLog.readText(), "hidden check \$LOOP_HOLDOUT/acceptance.sh failed\n")
        val trace = fixture.loop("trace", "t-1", "--workflow", fixture.workflow.toString())
        val hiddenLines = trace.stdout.lines().filter { "check_finished" in it && "check=1" in it }
        // The failed holdout, its rerun (m2-08), then the holdout that passed.
        assertEquals(3, hiddenLines.size, trace.toString())
        hiddenLines.forEach { assertContains(it, " holdout") }
        assertEquals(listOf("result=failed", "result=failed", "result=passed"), hiddenLines.map { Regex("result=\\w+").find(it)?.value })
        hiddenLines.forEach { assertFalse("exit=" in it || "duration=" in it, it) }
        assertContains(fixture.humanLog.readText(), "rerun of hidden check \$LOOP_HOLDOUT/acceptance.sh failed\n")
        assertFalse(
            "exit code" in
                fixture.humanLog
                    .readText()
                    .lines()
                    .filter { "hidden check" in it }
                    .joinToString("\n"),
        )
        assertFalse(secret in trace.stdout)
        assertFalse(secret in result.stderr)
        val attemptId = fixture.query("SELECT attempt_id FROM events WHERE kind = 'verdict' ORDER BY seq").first()["attempt_id"]!!
        val shown = fixture.loop("show", attemptId, "--evidence", "--check", "1", "--workflow", fixture.workflow.toString())
        assertEquals(0, shown.exitCode, shown.toString())
        assertContains(shown.stdout, "holdout: true\n")
        assertContains(shown.stdout, "result: ")
        assertFalse("exit_code:" in shown.stdout || "duration_ms:" in shown.stdout, shown.toString())
        assertFalse(secret in shown.stdout, shown.toString())
    }

    @Test
    fun `a holdout stays hidden in the result file evidence and in the raw runner stream records`() {
        fixture.issue("t-1")
        hiddenCheck()
        // Echoes each turn input to stdout, so the raw runner stream holds the guidance, then submits through the
        // result file (m2-15), writing fixed.txt from turn 2 on.
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |cat
            |if [ "${'$'}LOOP_TURN_NUMBER" -ge 2 ]; then touch fixed.txt; fi
            |mkdir -p .loop
            |echo '{ "status": "done", "summary": "Turn ${'$'}LOOP_TURN_NUMBER." }' > .loop/result.json
            |
            """.trimMargin(),
        )
        holdoutMode(maxTurns = 3)

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        val verdicts = fixture.query("SELECT data FROM events WHERE kind = 'verdict' ORDER BY seq").map { it["data"].orEmpty() }
        assertEquals(2, verdicts.size, result.toString())
        assertContains(verdicts[0], "\"verdict\":\"refuted\"")
        assertContains(verdicts[1], "\"verdict\":\"verified\"")
        val attempts = fixture.loopHome.resolve("attempts/${fixture.projectId()}")
        val files = attempts.listDirectoryEntries().filter { Files.isRegularFile(it) }
        val results = files.filter { it.fileName.toString().contains(".result-") }
        assertEquals(2, results.size, "both submitted results are saved beside the evidence: $files")
        val streams = files.filter { it.fileName.toString().endsWith(".jsonl.gz") }
        assertEquals(1, streams.size, files.toString())
        val stream = GZIPInputStream(Files.newInputStream(streams.single())).use { it.readBytes().toString(Charsets.UTF_8) }
        // The raw stream holds turn 2's guidance, so it does record what the agent was told after the refutation.
        assertContains(stream, "a hidden check failed")
        assertFalse("acceptance.sh" in stream, "the raw runner stream never names the hidden script")
        val readable = files.filter { it !in streams }.map { it to it.readText() } + (streams.single() to stream)
        for ((file, text) in readable) {
            assertFalse(secret in text, "$file holds the hidden check's output")
            assertFalse(scriptMarker in text, "$file holds the hidden check's content")
        }
        val attemptId = fixture.query("SELECT attempt_id FROM events WHERE kind = 'verdict' ORDER BY seq").first()["attempt_id"]!!
        for (view in listOf("--events", "--evidence")) {
            val shown = fixture.loop("show", attemptId, view, "--workflow", fixture.workflow.toString())
            assertEquals(0, shown.exitCode, shown.toString())
            assertFalse(secret in shown.stdout, shown.toString())
            assertFalse(scriptMarker in shown.stdout, shown.toString())
        }
    }

    @Test
    fun `LOOP_HOLDOUT exported on the host reaches neither the agent nor a visible check, only the holdout`() {
        fixture.issue("t-1")
        val script = hiddenCheck()
        script.writeText("#!/bin/sh\necho \"${'$'}LOOP_HOLDOUT\" > \"$inputs/holdout-env.txt\"\nexit 0\n")
        fixture.agentScript.toFile().writeText(
            "#!/bin/sh\nset -e\necho \"agent=${'$'}{LOOP_HOLDOUT-unset}\" > \"$inputs/agent-env.txt\"\nloop tool move review\n",
        )
        fixture.writeWorkflow(
            extra =
                """
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "echo check=${'$'}{LOOP_HOLDOUT-unset} > $inputs/check-env.txt" }
                |      holdout:
                |        - { type: command, run: "${'$'}LOOP_HOLDOUT/acceptance.sh" }
                """.trimMargin(),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_HOLDOUT" to "/exported/by/the/operator"))

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        assertEquals("agent=unset", inputs.resolve("agent-env.txt").readText().trim())
        assertEquals("check=unset", inputs.resolve("check-env.txt").readText().trim())
        assertEquals(fixture.loopHome.resolve("holdout").toString(), inputs.resolve("holdout-env.txt").readText().trim())
    }

    @Test
    fun `a holdout whose run points at a script in the repository fails preflight`() {
        fixture.issue("t-1")
        hiddenCheck()
        fixture.writeWorkflow(
            extra =
                """
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks: []
                |      holdout:
                |        - { type: command, run: "./scripts/acceptance.sh" }
                """.trimMargin(),
        )

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "kinds.code.verify.holdout[0]: run must reference \$LOOP_HOLDOUT")
        assertEquals("todo", fixture.stateOf("t-1"), "nothing was dispatched")
    }

    @Test
    fun `a holdout folder inside the repository fails preflight`() {
        fixture.issue("t-1")
        val loopHome = fixture.projectDir.resolve(".loop-home")
        hiddenCheck(loopHome)
        holdoutMode()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_HOME" to loopHome.toString()))

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "kinds.code.verify.holdout: the holdout folder")
        assertContains(result.stderr, "is inside the repository")
        assertEquals("todo", fixture.stateOf("t-1"), "nothing was dispatched")
    }

    @Test
    fun `a holdout folder inside the workspace root fails preflight`() {
        fixture.issue("t-1")
        val loopHome = fixture.projectDir.resolve("workspaces/.loop-home")
        hiddenCheck(loopHome)
        holdoutMode()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_HOME" to loopHome.toString()))

        assertNotEquals(0, result.exitCode, result.toString())
        assertContains(result.stderr, "is inside workspace.root")
        assertFalse(
            fixture.projectDir
                .resolve("workspaces")
                .listDirectoryEntries()
                .any { it.fileName.toString() == "t-1" },
            "no workspace was created",
        )
    }
}
