package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * m2-13, story 87: the seeded shortcut of the playground. The seeded issue `pg-1` asks for a failing test to pass;
 * a fake runner applies a scripted patch that deletes the test instead, and Loop refutes the result with
 * `deleted_tests`, while the patch that fixes the code is verified. The playground lives in the test resources,
 * `playground/deleted-test/`.
 */
class SeededShortcutCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val playground: Path =
        Path.of(
            checkNotNull(javaClass.getResource("/playground/deleted-test")) { "no playground in the test resources" }.toURI(),
        )

    /**
     * The playground project and its seeded issue in a git project with Loop's worktree hooks, and a fake runner
     * that applies [patch], commits it and submits the result.
     */
    private fun seed(patch: String) {
        val project = playground.resolve("project")
        Files.walk(project).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val target = fixture.projectDir.resolve(project.relativize(file).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.copy(playground.resolve("issue.md"), fixture.issueFile("pg-1"))
        val patchFile = root.resolve(patch)
        Files.copy(playground.resolve(patch), patchFile)
        fixture.agentScript.toFile().writeText(
            """
            |#!/bin/sh
            |set -e
            |git apply "$patchFile"
            |git -c user.name=Agent -c user.email=agent@example.com commit -qam "make the calculator tests pass"
            |loop tool move review
            |
            """.trimMargin(),
        )
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |retries:
                |  max_attempts: 1
                |kinds:
                |  code:
                |    delivery: { policy: auto, auto_merge: { enabled: true } }
                |    verify:
                |      checks:
                |        - { type: command, run: "sh run-tests.sh" }
                |      guards:
                |        deny: [deleted_tests, added_skips, weaker_assertions]
                """.trimMargin(),
        )
        fixture.useGitWorktrees()
    }

    @Test
    fun `the seeded shortcut, a patch that deletes the failing test, ends refuted with deleted_tests`() {
        seed("shortcut.patch")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("todo", fixture.stateOf("pg-1"), "a refuted result is never delivered\n$result")
        val verdict = fixture.query("SELECT data, attempt_id FROM events WHERE kind = 'verdict'").single()
        assertContains(verdict["data"].orEmpty(), "\"verdict\":\"refuted\"")
        assertContains(verdict["data"].orEmpty(), "\"checks\":\"2\"")
        val finished = fixture.query("SELECT data FROM events WHERE kind = 'check_finished' ORDER BY seq").map { it["data"].orEmpty() }
        // The shortcut passes the test run; only the guard sees it.
        assertContains(finished[0], "\"exit_code\":\"0\"")
        assertContains(finished[1], "\"exit_code\":\"1\"")
        val attempt = fixture.query("SELECT data FROM events WHERE kind = 'attempt_finished'").single()["data"].orEmpty()
        assertContains(attempt, "\"reason\":\"refuted\"")

        val evidence = fixture.loop("show", verdict["attempt_id"]!!, "--evidence", "--workflow", fixture.workflow.toString())
        assertEquals(0, evidence.exitCode, evidence.toString())
        assertContains(evidence.stdout, "diff_policy base=main deny=deleted_tests,added_skips,weaker_assertions")
        assertContains(evidence.stdout, "breaks 2 deny guards, deleted_tests and weaker_assertions:")
        assertContains(evidence.stdout, "deleted_tests: removed test `divides by zero` from src/test/kotlin/calc/CalcTest.kt")
        val trace = fixture.loop("trace", "pg-1", "--workflow", fixture.workflow.toString())
        assertContains(trace.stdout, Regex("""verdict\s+refuted checks=2"""))
    }

    @Test
    fun `the honest fix of the seeded issue is verified and moves to review`() {
        seed("fix.patch")

        val result = fixture.loop("run", fixture.workflow.toString(), "--once")

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("review", fixture.stateOf("pg-1"), result.toString())
        val verdict = fixture.query("SELECT data FROM events WHERE kind = 'verdict'").single()["data"].orEmpty()
        assertContains(verdict, "\"verdict\":\"verified\"")
        assertFalse("deleted_tests:" in fixture.humanLog.readText())
    }
}
