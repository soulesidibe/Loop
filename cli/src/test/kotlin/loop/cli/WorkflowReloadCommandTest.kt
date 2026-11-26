package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** m1-15: `loop run` picks up edits of `WORKFLOW.md` while it runs, end to end through the installed launcher. */
class WorkflowReloadCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Created by the test to let the first agent call end. */
    private val go: Path by lazy { root.resolve("go") }

    /**
     * An agent that logs its calls to [LoopFixture.agentCallsFile]. The first call waits for [go], then exits 0
     * without moving the issue; with `max_turns: 1` the attempt ends there and a continuation attempt follows.
     * A later call moves the issue to review.
     */
    private fun useTwoCallAgent() {
        val calls = fixture.agentCallsFile
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}LOOP_ATTEMPT_ID" >> "$calls"
            |if [ "${'$'}(wc -l < "$calls")" -eq 1 ]; then
            |  while [ ! -f "$go" ]; do sleep 0.1; done
            |  exit 0
            |fi
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(fixture.agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun maxTurns(value: String) = fixture.writeWorkflow(extra = "agent:\n  max_turns: $value")

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun await(
        what: String,
        running: LoopFixture.RunningLoop,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        stop(running)
        fail("$what never happened\n${running.await(30)}")
    }

    private fun stop(running: LoopFixture.RunningLoop): LoopResult {
        fixture.loop("stop", fixture.workflow.toString())
        return running.await(30)
    }

    private fun data(row: Map<String, String?>): Map<String, String> =
        Json
            .parseToJsonElement(row["data"] ?: "{}")
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    private fun events(kind: String): List<Map<String, String>> =
        fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map(::data)

    /** The one event of [kind]; when there is not exactly one, the failure lists every event of that kind. */
    private fun single(kind: String): Map<String, String> {
        val all = events(kind)
        return all.singleOrNull() ?: fail("expected one $kind event, got ${all.size}:\n${all.joinToString("\n")}")
    }

    @Test
    fun `an edit of max_turns while loop run runs reaches the next attempt and loop trace shows each config version`() {
        useTwoCallAgent()
        maxTurns("1")
        val first = hash(fixture.workflow.readText())
        fixture.issue("t-1")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            await("the first agent call", running) { fixture.agentCalls() == 1 }
            maxTurns("2")
            val second = hash(fixture.workflow.readText())
            go.writeText("")
            await("the move to review", running) { fixture.stateOf("t-1") == "review" }
            val run = stop(running)

            val turns = events("turn_completed").map { it["max_turns"] }
            assertEquals(listOf("1", "2"), turns, run.toString())
            assertEquals(listOf(first, second), events("attempt_started").map { it["config_version"] }, run.toString())
            val reloaded = single("config_reloaded")
            assertEquals(second, reloaded["config_version"])
            assertEquals(first, reloaded["previous_version"])
            assertTrue("-  max_turns: 1\n+  max_turns: 2\n" in reloaded.getValue("diff"), reloaded.toString())
            assertTrue(
                run.stderr.lines().any {
                    "level=info event=workflow_reload outcome=completed" in it && "config_version=${second.take(8)}" in it
                },
                run.toString(),
            )
            assertTrue("WORKFLOW.md reloaded, config ${second.take(8)} replaces ${first.take(8)}" in fixture.humanLog.readText())

            val trace = fixture.loop("trace", "t-1")
            assertEquals(0, trace.exitCode, trace.toString())
            val headers = trace.stdout.lines().filter { it.startsWith("Attempt ") }
            assertEquals(2, headers.size, trace.stdout)
            assertTrue("  config ${first.take(8)}  " in headers[0], headers[0])
            assertTrue("  config ${second.take(8)}  " in headers[1], headers[1])
            val full = fixture.loop("trace", "t-1", "--full-ids")
            assertTrue("  config $second  " in full.stdout, full.stdout)
        } finally {
            stopForcibly(running)
        }
    }

    @Test
    fun `an invalid edit is rejected the last good config stays active and the error is recorded`() {
        useTwoCallAgent()
        maxTurns("1")
        val good = hash(fixture.workflow.readText())
        fixture.issue("t-1")
        val running = fixture.start("run", fixture.workflow.toString())
        try {
            await("the first agent call", running) { fixture.agentCalls() == 1 }
            maxTurns("0")
            val bad = hash(fixture.workflow.readText())
            go.writeText("")
            await("the rejected edit", running) {
                fixture.storeFile.exists() &&
                    fixture.query("SELECT count(*) AS n FROM events WHERE kind = 'config_reload_failed'").single()["n"] == "1"
            }
            // The continuation is due 1 s after the first attempt; two more polls of 1 s pass without an attempt.
            Thread.sleep(3_000)
            assertEquals(1, fixture.agentCalls(), "an attempt started from the invalid file")

            // Back to the last good text: nothing to reload, the next attempt runs with the version it kept.
            maxTurns("1")
            await("the move to review", running) { fixture.stateOf("t-1") == "review" }
            val run = stop(running)

            val rejected = single("config_reload_failed")
            assertEquals(bad, rejected["config_version"])
            assertEquals(good, rejected["active_version"])
            assertEquals("invalid_config", rejected["error"])
            assertEquals("agent.max_turns must be a positive integer, got 0", rejected["message"])
            assertTrue("-  max_turns: 1\n+  max_turns: 0\n" in rejected.getValue("diff"), rejected.toString())
            assertEquals(emptyList(), events("config_reloaded"))
            assertEquals(listOf(good, good), events("attempt_started").map { it["config_version"] })
            assertEquals(listOf("1", "1"), events("turn_completed").map { it["max_turns"] })
            assertTrue(
                run.stderr.lines().any {
                    "level=error event=workflow_reload outcome=failed" in it &&
                        "error=invalid_config" in it &&
                        "reason=\"agent.max_turns must be a positive integer, got 0\"" in it
                },
                run.toString(),
            )
            assertTrue(
                "WORKFLOW.md edit rejected, config ${good.take(8)} stays active: agent.max_turns must be a positive integer, got 0" in
                    fixture.humanLog.readText(),
                fixture.humanLog.readText(),
            )
        } finally {
            stopForcibly(running)
        }
    }

    private fun stopForcibly(running: LoopFixture.RunningLoop) {
        running.process.descendants().forEach { it.destroyForcibly() }
        running.process.destroyForcibly()
    }

    private companion object {
        const val WAIT_NANOS = 60_000_000_000L
        const val POLL_MS = 100L
    }
}
