package loop.cli

import loop.core.workflow.WorkflowFile
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * m4-06: the merge risk reviewer runs through the Claude Code command whatever `runner.kind` is, so under `policy: auto`
 * with merge, startup warns when that command cannot be found: every result would wait for `loop approve`.
 */
class RiskReviewerWarningTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val token = "ghp_m406WarnToken0123456789abcdefghijklmno"

    private fun host(): Host =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString(), "LOOP_PR_TOKEN" to token),
            launcherProperty = null,
            userHome = "/tmp",
        )

    /**
     * Sets `runner.kind: codex-app-server` with [codexCommand], [claudeCode] as the `runners.claude-code` section when
     * set, and [delivery].
     */
    private fun useCodex(
        claudeCode: String? = null,
        delivery: String = AUTO_MERGE,
        codexCommand: String = "codex app-server",
    ) {
        val text = fixture.workflow.readText()
        val start = text.indexOf("runner:\n")
        val end = text.indexOf("polling:\n")
        val section = claudeCode?.let { "  claude-code:\n    command: \"$it\"\n" }.orEmpty()
        val runner =
            "runner:\n  kind: codex-app-server\nrunners:\n  codex:\n    command: \"$codexCommand\"\n$section" +
                "kinds:\n  code:\n    delivery:\n$delivery    verify:\n      checks: []\n"
        fixture.workflow.writeText(text.substring(0, start) + runner + text.substring(end))
    }

    private fun workflow(): WorkflowFile =
        host()
            .workflowLoader()
            .load(fixture.workflow)
            .getOrThrow()

    /** Turns the project into a git repository with an `origin` remote, which pull request delivery needs at startup. */
    private fun withRemote() {
        val bare = Files.createDirectories(root.resolve("remote.git"))
        ProcessBuilder("git", "init", "--bare", "-q", bare.toString()).inheritIO().start().waitFor()
        fixture.useGitWorktrees().git("remote", "add", "origin", bare.toString())
    }

    private fun executable(path: Path): Path {
        Files.createDirectories(path.parent)
        path.writeText("#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    @Test
    fun `m4-06 under policy auto a reviewer command missing from PATH warns, naming the command and the fix`() {
        useCodex()

        assertEquals(
            "kinds.code.delivery.risk.reviewer is claude-sonnet-5-5, but its command claude cannot be found, so no " +
                "merge risk reviewer can run and every result waits for loop approve. Install Claude Code or set " +
                "runners.claude-code.command",
            riskReviewerWarning(workflow(), mapOf("PATH" to root.resolve("empty").toString())),
        )
    }

    @Test
    fun `m4-06 an absolute reviewer command that is not an executable file warns`() {
        val missing = root.resolve("nowhere/claude")
        useCodex(claudeCode = missing.toString())

        assertContains(
            riskReviewerWarning(workflow(), mapOf("PATH" to "/usr/bin:/bin")).orEmpty(),
            "but its command $missing cannot be found",
        )
    }

    @Test
    fun `m4-06 a reviewer command found on PATH or at its path gives no warning`() {
        val bin = root.resolve("bin-claude")
        executable(bin.resolve("claude"))
        useCodex()
        assertNull(riskReviewerWarning(workflow(), mapOf("PATH" to "/usr/bin:$bin")))

        useCodex(claudeCode = executable(root.resolve("pinned/claude")).toString())
        assertNull(riskReviewerWarning(workflow(), mapOf("PATH" to root.resolve("empty").toString())))
    }

    @Test
    fun `m4-06 no warning when no reviewer would run, under policy human or without merge`() {
        val empty = mapOf("PATH" to root.resolve("empty").toString())
        useCodex(delivery = PULL_REQUEST.replace("POLICY", "human") + MERGE)
        assertNull(riskReviewerWarning(workflow(), empty), "policy human")

        useCodex(delivery = "      auto_merge: { enabled: true }\n" + PULL_REQUEST.replace("POLICY", "auto"))
        assertNull(riskReviewerWarning(workflow(), empty), "policy auto without merge runs no reviewer")
    }

    @Test
    fun `m4-06 loop run warns at startup when the reviewer command cannot be found`() {
        useCodex(claudeCode = root.resolve("nowhere/claude").toString())
        withRemote()

        val result = fixture.loop("run", "--once", env = mapOf("LOOP_PR_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertContains(
            result.stderr,
            "loop run: warning: kinds.code.delivery.risk.reviewer is claude-sonnet-5-5, but its command " +
                "${root.resolve("nowhere/claude")} cannot be found",
        )
    }

    @Test
    fun `m4-06 loop run with no auto merge never mentions the reviewer`() {
        useCodex(delivery = PULL_REQUEST.replace("POLICY", "human") + MERGE)
        withRemote()

        val result = fixture.loop("run", "--once", env = mapOf("LOOP_PR_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertFalse("risk.reviewer" in result.stderr, result.stderr)
    }

    @Test
    fun `bl-214 a Codex reviewer model whose codex cannot be found warns, naming codex and its fix`() {
        useCodex(delivery = AUTO_MERGE.replace("claude-sonnet-5-5", "gpt-6.1-sol"))

        assertEquals(
            "kinds.code.delivery.risk.reviewer is gpt-6.1-sol, but its command codex cannot be found, so no " +
                "merge risk reviewer can run and every result waits for loop approve. Install Codex or set " +
                "runners.codex.command",
            riskReviewerWarning(workflow(), mapOf("PATH" to root.resolve("empty").toString())),
        )
    }

    @Test
    fun `bl-214 a Codex reviewer model whose codex is on PATH gives no warning, even without claude`() {
        val bin = root.resolve("bin-codex")
        executable(bin.resolve("codex"))
        useCodex(delivery = AUTO_MERGE.replace("claude-sonnet-5-5", "gpt-6.1-sol"))

        assertNull(riskReviewerWarning(workflow(), mapOf("PATH" to "$bin")))
    }

    @Test
    fun `bl-214 loop run warns at startup when the Codex reviewer's codex cannot be found`() {
        val missing = root.resolve("nowhere/codex")
        useCodex(delivery = AUTO_MERGE.replace("claude-sonnet-5-5", "gpt-6.1-sol"), codexCommand = "$missing app-server")
        withRemote()

        val result = fixture.loop("run", "--once", env = mapOf("LOOP_PR_TOKEN" to token))

        assertEquals(0, result.exitCode, result.toString())
        assertContains(
            result.stderr,
            "loop run: warning: kinds.code.delivery.risk.reviewer is gpt-6.1-sol, but its command $missing cannot be " +
                "found, so no merge risk reviewer can run and every result waits for loop approve. Install Codex or set " +
                "runners.codex.command",
        )
    }

    private companion object {
        const val PULL_REQUEST =
            "      policy: POLICY\n" +
                "      pull_request:\n" +
                "        repo: acme/widgets\n" +
                "        token: \$LOOP_PR_TOKEN\n" +
                "        base: main\n"
        const val MERGE = "        merge: { method: merge, ci: none }\n      on_delivered: { move_to: done }\n"
        const val AUTO_MERGE =
            "      auto_merge: { enabled: true }\n" +
                "      risk: { reviewer: claude-sonnet-5-5 }\n" +
                "      policy: auto\n" +
                "      pull_request:\n" +
                "        repo: acme/widgets\n" +
                "        token: \$LOOP_PR_TOKEN\n" +
                "        base: main\n" + MERGE
    }
}
