package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan seam 7 (m1-11), end to end through the installed launcher. */
class SecretRedactionEndToEndTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Adds `tracker.provider.token: $LOOP_TEST_SECRET` to the fixture's `WORKFLOW.md`. */
    private fun useTokenRef() {
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(text.replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"))
    }

    @Test
    fun `a secret under 4 characters is not redacted and loop run warns once naming it`() {
        useTokenRef()
        fixture.issue("t-1")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_TEST_SECRET" to "abc"))

        assertEquals(0, result.exitCode, result.toString())
        val warning = "loop run: warning: \$LOOP_TEST_SECRET is under 4 characters, so Loop does not redact it."
        assertEquals(1, result.stderr.lines().count { it == warning }, result.toString())
    }

    @Test
    fun `a config secret echoed by the agent and a hook leaves no trace in the store, the loop home or the output`() {
        val secret = "Lp7secretQ93xZ0vK2mN5"
        val workspaces = root.resolve("ws-root")
        fixture.writeWorkflow(extra = "hooks:\n  after_create: echo \"hook sees $secret\"")
        useTokenRef()
        fixture.workflow.writeText(fixture.workflow.readText().replace("  root: ./workspaces\n", "  root: \$LOOP_TEST_ROOT\n"))
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "agent stdout $secret"
            |echo "agent stderr $secret" >&2
            |loop tool comment "agent says $secret"
            |loop tool move review
            |
            """.trimMargin(),
        )
        fixture.issue("t-1")

        val result =
            fixture.loop(
                "run",
                fixture.workflow.toString(),
                "--once",
                env = mapOf("LOOP_TEST_SECRET" to secret, "LOOP_TEST_ROOT" to workspaces.toString()),
            )

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"), result.toString())
        // m1-23: loop tool cannot know the secret inside the agent; Loop redacts the comment host side.
        val issueText = fixture.issueFile("t-1").readText()
        assertFalse(secret in issueText, issueText)
        assertTrue("agent says [redacted:LOOP_TEST_SECRET]" in issueText, issueText)
        val lines = fixture.query("SELECT data FROM events WHERE kind = 'runner_output'").mapNotNull { it["data"] }
        assertTrue(lines.any { "[redacted:LOOP_TEST_SECRET]" in it }, lines.toString())
        // Every file under the loop home: store.db and, while they exist, store.db-wal and store.db-shm, plus
        // the Attempt file (m1-13), read decompressed so gzip cannot hide a secret.
        val written = Files.walk(fixture.loopHome).use { paths -> paths.filter { it.isRegularFile() }.toList() }
        assertTrue(written.any { it.fileName.toString() == "store.db" }, written.toString())
        assertTrue(written.any { it.fileName.toString().endsWith(".jsonl.gz") }, written.toString())
        val texts =
            written.associate { it.toString() to String(decompressed(it), Charsets.ISO_8859_1) } +
                mapOf("stdout" to result.stdout, "stderr" to result.stderr)
        texts.forEach { (where, text) ->
            assertFalse(secret in text, "the secret is in $where")
            assertFalse(secret.take(8) in text, "a fragment of the secret is in $where")
        }
        val visible = fixture.query("SELECT data FROM events WHERE kind = 'phase' AND phase = 'PreparingWorkspace'")
        assertTrue(visible.any { it["data"].orEmpty().contains(workspaces.toRealPath().toString()) }, visible.toString())
    }

    @Test
    fun `an anthropic key in a startup preflight failure is redacted`() {
        val key = "Ak9anthropicKeyValue77"
        fixture.writeWorkflow(activeStates = listOf(key))

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("ANTHROPIC_API_KEY" to key))

        assertEquals(1, result.exitCode, result.toString())
        assertTrue("[redacted:ANTHROPIC_API_KEY]" in result.stderr, result.toString())
        assertFalse(key.take(8) in result.stderr + result.stdout, result.toString())
    }

    @Test
    fun `a tracker provider secret in a startup preflight failure is redacted`() {
        val secret = "Tk4trackerTokenValue88"
        fixture.writeWorkflow(activeStates = listOf(secret))
        useTokenRef()

        val result = fixture.loop("run", fixture.workflow.toString(), "--once", env = mapOf("LOOP_TEST_SECRET" to secret))

        assertEquals(1, result.exitCode, result.toString())
        assertTrue("[redacted:LOOP_TEST_SECRET]" in result.stderr, result.toString())
        assertFalse(secret.take(8) in result.stderr + result.stdout, result.toString())
    }

    private fun decompressed(file: Path): ByteArray =
        if (file.fileName.toString().endsWith(".gz")) {
            GZIPInputStream(Files.newInputStream(file)).use { it.readBytes() }
        } else {
            file.readBytes()
        }
}
