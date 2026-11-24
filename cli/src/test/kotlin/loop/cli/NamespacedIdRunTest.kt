package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * m4-12: `loop run` on a `files` tracker whose id is `local`, not the default. Its issues reach the core as
 * `local:<id>`, so events, the store's rows, the ledger and the logs carry that id, while the workspace key, the
 * `LOOP_ISSUE_*` variables and the identifier stay native. A store and a ledger written before m4-12 hold bare ids,
 * read as the primary tracker's and never rewritten.
 */
class NamespacedIdRunTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy {
        LoopFixture(root).also { f ->
            f.workflow.writeText(f.workflow.readText().replace("  kind: files\n", "  kind: files\n  id: local\n"))
            f.agentScript.writeText(
                """
                |#!/bin/sh
                |set -e
                |env | grep '^LOOP_' > "$agentEnv"
                |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
                |loop tool move review
                |
                """.trimMargin(),
            )
        }
    }

    private val agentEnv: Path get() = root.resolve("agent-env.txt")
    private val ledgerFile: Path get() = fixture.loopHome.resolve("ledger").resolve("${fixture.projectId()}.jsonl")

    @Test
    fun `m4-12 events, rows, ledger lines and logs carry the namespaced id, the workspace and variables stay native`() {
        fixture.issue("t-1")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("t-1"))
        assertEquals(
            listOf("local:t-1"),
            fixture.query("SELECT DISTINCT issue_id FROM events WHERE issue_id IS NOT NULL").map { it["issue_id"] },
        )
        assertEquals(listOf("t-1"), fixture.query("SELECT DISTINCT issue_identifier FROM events WHERE issue_id IS NOT NULL").map { it["issue_identifier"] })
        assertEquals(listOf("local:t-1"), fixture.query("SELECT issue_id FROM attempts").map { it["issue_id"] })
        val lines = ledgerFile.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(lines.isNotEmpty(), "no ledger line")
        lines.forEach { line ->
            assertEquals("local:t-1", line.getValue("issue_id").jsonPrimitive.content, "$line")
            assertEquals("t-1", line.getValue("issue_identifier").jsonPrimitive.content, "$line")
        }
        assertTrue(result.stderr.lines().any { "issue_id=local:t-1" in it && "issue_identifier=t-1" in it }, result.toString())
        // The primary tracker's issue keeps the workspace key of its identifier.
        assertTrue(fixture.workspace("t-1").resolve("hello.txt").exists())
        val env = agentEnv.readLines().associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("t-1", env["LOOP_ISSUE_ID"])
        assertEquals("t-1", env["LOOP_ISSUE_IDENTIFIER"])
        assertEquals("local", env["LOOP_TRACKER_ID"])
        assertEquals("t-1", env["LOOP_WORKSPACE_KEY"])
    }

    @Test
    fun `m4-12 a store and a ledger written before are read with their bare ids as the primary tracker's, never rewritten`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        writtenBefore()
        val legacyLine = """{"issue_id":"t-1","issue_identifier":"t-1"}"""
        ledgerFile.parent.createDirectories()
        ledgerFile.writeText("$legacyLine\n")

        val result = fixture.loop("run", "--once")

        assertEquals(0, result.exitCode, result.toString())
        // The parked row of t-2, held under its bare id, still parks it.
        assertEquals("todo", fixture.stateOf("t-2"))
        assertEquals(0, fixture.query("SELECT count(*) AS n FROM events WHERE kind = 'attempt_started' AND issue_id LIKE '%t-2'").single()["n"]?.toInt())
        assertEquals(listOf("t-2"), fixture.query("SELECT issue_id FROM parked_issues").map { it["issue_id"] })
        // t-1's earlier ledger row counts: its new row is its second attempt.
        assertEquals("review", fixture.stateOf("t-1"))
        val rows = fixture.query("SELECT issue_id, attempt_number FROM attempts ORDER BY seq")
        assertEquals(listOf("t-1" to "1", "local:t-1" to "2"), rows.map { it["issue_id"] to it["attempt_number"] })
        // The ledger file is appended to, its first line as it was.
        val lines = ledgerFile.readLines().filter { it.isNotBlank() }
        assertEquals(legacyLine, lines.first())
        assertTrue(lines.size > 1)
        lines.drop(1).forEach { assertContains(it, "\"issue_id\":\"local:t-1\"") }
    }

    /** The store of a Loop before m4-12: a finished attempt row of t-1 and a parked t-2, both under bare ids. */
    private fun writtenBefore() {
        val project = ProjectId(fixture.projectId())
        val at = Clock.System.now()
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking {
                store
                    .recordAttempt(
                        LedgerAttempt(
                            projectId = project,
                            runId = RunId("r0"),
                            issueId = "t-1",
                            identifier = "t-1",
                            attemptId = AttemptId("00000000-0000-4000-8000-000000000412"),
                            setup = LedgerSetup("files", "code", "command", null, "t".repeat(64), null),
                            startedAt = at,
                            finishedAt = at,
                            outcome = "failed",
                            reason = "exit 1",
                            turns = 1,
                            issueState = "todo",
                            tokens = null,
                            costUsd = null,
                            checks = null,
                            guards = null,
                            verdict = null,
                        ),
                    ).getOrThrow()
                store.park(ParkedIssue(project, "t-2", "t-2", ParkReason("stalled"), "todo", setOf("ready-for-agent"), at)).getOrThrow()
            }
        }
    }
}
