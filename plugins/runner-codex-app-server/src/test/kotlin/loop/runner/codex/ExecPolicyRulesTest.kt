package loop.runner.codex

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bl-135: a Codex execpolicy rule that allows a denied command lets Codex run it with no approval request, so
 * [ExecPolicyRules] reads every rules file Codex would load for a session, before it starts, and refuses the session
 * when one allows a denied command or cannot be read.
 */
class ExecPolicyRulesTest {
    @TempDir
    lateinit var dir: Path

    private val repo: Path by lazy { dir.resolve("repo").createDirectories().also { it.resolve(".git").writeText("gitdir: x\n") } }
    private val workspace: Path by lazy { repo.resolve("packages/app").createDirectories() }
    private val home: Path by lazy { dir.resolve("home").createDirectories() }

    @Test
    fun `bl-135 an allow rule for git push in the workspace's codex rules refuses the session, naming the file and the command`() {
        val file = rules(workspace, "default.rules", "prefix_rule(pattern=[\"git\", \"push\"], decision=\"allow\")\n")

        val refusal = assertNotNull(refusal())

        assertTrue(refusal.startsWith("exec_policy_rule: $file "), refusal)
        assertTrue("allows the denied command git push" in refusal, refusal)
    }

    @Test
    fun `bl-135 an operator rule in CODEX_HOME, or in HOME's codex folder by default, refuses the session`() {
        val codexHome = dir.resolve("codex-home")
        rules(codexHome, "default.rules", "prefix_rule(pattern = [\"gh\"])\n", inCodexFolder = false)
        assertNotNull(refusal(env = mapOf("CODEX_HOME" to codexHome.toString(), "HOME" to home.toString())))

        rules(home, "mine.rules", "prefix_rule(pattern = [\"git\", \"push\"], decision = \"allow\")\n")
        assertNotNull(refusal(env = mapOf("HOME" to home.toString())))
    }

    @Test
    fun `bl-135 a rule in a parent folder up to the project root counts, one above it does not`() {
        rules(dir, "above.rules", "prefix_rule(pattern=[\"gh\"])\n")
        assertNull(refusal())

        rules(repo, "root.rules", "prefix_rule(pattern=[\"gh\"])\n")
        assertNotNull(refusal())
    }

    @Test
    fun `bl-135 every rule form that can allow a denied command refuses the session`() {
        listOf(
            "prefix_rule(pattern=[\"git\", [\"status\", \"push\"]], decision=\"allow\")",
            "prefix_rule([\"git\", \"push\", \"origin\"])",
            "prefix_rule(pattern=[\"git\"], decision='allow', justification=\"all of git\")",
            "prefix_rule(pattern=[\"/usr/bin/git\", \"push\"])",
            "prefix_rule(pattern=[\"git\", \"-C\"])",
            "prefix_rule(pattern=[[\"gh\", \"hub\"], \"pr\"])",
            "prefix_rule(pattern=[\"loop\", \"approve\"])",
            "prefix_rule(pattern=[\"/opt/loop/bin/loop\", \"resume\"])",
            "prefix_rule(pattern=[\"git\", \"reset\"])",
            "# comment\nprefix_rule(\n    pattern = [\n        \"git\",\n        \"push\",  # trailing\n    ],\n)\n",
        ).forEachIndexed { i, rule ->
            val file = rules(workspace, "case-$i.rules", rule)

            val refusal = assertNotNull(refusal(), rule)

            assertTrue(refusal.startsWith("exec_policy_rule: $file "), refusal)
            file.toFile().delete()
        }
    }

    @Test
    fun `bl-135 a rule Loop cannot read as literals refuses the session, since it may allow a denied command`() {
        listOf(
            "PATTERN = [\"git\", \"push\"]\nprefix_rule(pattern=PATTERN)",
            "prefix_rule(pattern=[\"git\", \"push\"], decision=DECISION)",
            "allow = prefix_rule\nallow(pattern=[\"git\", \"push\"])",
            "prefix_rule(pattern=[\"git\", \"push\"",
            "prefix_rule(pattern=[\"git\", \"push]",
            "prefix_rule(pattern=[\"git\" + \"\", \"push\"])",
        ).forEachIndexed { i, rule ->
            val file = rules(workspace, "case-$i.rules", rule)

            val refusal = assertNotNull(refusal(), rule)

            assertTrue(refusal.startsWith("exec_policy_rule: $file cannot be read as literal rules ("), refusal)
            file.toFile().delete()
        }
    }

    @Test
    fun `bl-135 a rule that asks or forbids, or allows another command, lets the session start`() {
        rules(
            workspace,
            "fine.rules",
            """
            # Loop's own build.
            prefix_rule(pattern=["./gradlew"], decision="allow")
            prefix_rule(pattern=["git", ["status", "log", "diff"]], decision="allow", match=[["git", "status"]])
            prefix_rule(pattern=["git", "push"], decision="forbidden", justification="never push")
            prefix_rule(pattern=["gh"], decision="prompt")
            prefix_rule(pattern=[r"npm", 'test'], decision="allow", not_match=["npm publish"])
            host_executable(name="git", paths=["/usr/bin/git"])
            """.trimIndent(),
        )
        rules(workspace, "notes.txt", "prefix_rule(pattern=[\"gh\"])\n")

        assertNull(refusal())
    }

    @Test
    fun `bl-135 no rules folder at all lets the session start`() {
        assertNull(refusal())
    }

    @Test
    fun `bl-135 the folders read are CODEX_HOME's, the system one and each codex folder up to the project root`() {
        val folders = ExecPolicyRules.folders(workspace, mapOf("CODEX_HOME" to "/c"), systemDir = Path.of("/etc/codex"))

        assertEquals(
            listOf(
                Path.of("/c/rules"),
                Path.of("/etc/codex/rules"),
                workspace.resolve(".codex/rules"),
                repo.resolve("packages/.codex/rules"),
                repo.resolve(".codex/rules"),
            ),
            folders,
        )
    }

    private fun refusal(env: Map<String, String> = mapOf("HOME" to home.toString())): String? =
        ExecPolicyRules.refusal(ExecPolicyRules.folders(workspace, env, systemDir = null))

    private fun rules(
        base: Path,
        name: String,
        text: String,
        inCodexFolder: Boolean = true,
    ): Path {
        val folder = (if (inCodexFolder) base.resolve(".codex") else base).resolve("rules").createDirectories()
        return folder.resolve(name).also { it.writeText(text) }
    }
}
