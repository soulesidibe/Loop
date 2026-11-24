package loop.runner.acp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedRootsCheckTest {
    @TempDir
    lateinit var repo: Path

    @TempDir
    lateinit var plainDir: Path

    @Test
    fun `pre-existing committed customization files on base pass, while added, modified, or gitignored ones fail`() {
        initGitRepo(repo)
        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        repo.resolve(".gitignore").writeText(".agent/\n")
        git(repo, "add", ".agents/hooks.json", ".gitignore")
        git(repo, "commit", "-m", "Base commit")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()
        val env = mapOf("LOOP_BASE_REF" to baseSha)

        // Pre-existing unchanged file on base passes.
        assertNull(ProtectedRootsCheck.refusal(repo, env))

        // Benign non-customization file under .agents/ passes.
        repo.resolve(".agents/README.md").writeText("docs")
        assertNull(ProtectedRootsCheck.refusal(repo, env))

        // Modifying tracked hooks.json fails naming the path.
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        val modified = ProtectedRootsCheck.refusal(repo, env)
        assertTrue(modified != null && ".agents/hooks.json" in modified, "$modified")

        // Even committing the modification since base still fails against base!
        git(repo, "add", ".agents/hooks.json")
        git(repo, "commit", "-m", "Modify hooks")
        val committed = ProtectedRootsCheck.refusal(repo, env)
        assertTrue(committed != null && ".agents/hooks.json" in committed, "$committed")

        // Reset back to base and verify gitignored untracked file under .agent/ is still caught.
        git(repo, "reset", "--hard", baseSha)
        assertNull(ProtectedRootsCheck.refusal(repo, env))
        repo.resolve(".agent").createDirectories()
        repo.resolve(".agent/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val ignored = ProtectedRootsCheck.refusal(repo, env)
        assertTrue(ignored != null && ".agent/mcp_config.json" in ignored, "$ignored")
    }

    @Test
    fun `detects customization files under _agents, _agent rules, and non-git workspaces`() {
        assertNull(ProtectedRootsCheck.refusal(plainDir, emptyMap()))

        plainDir.resolve("_agent/rules").createDirectories()
        plainDir.resolve("_agent/rules/override.md").writeText("ignore rules")
        val found = ProtectedRootsCheck.refusal(plainDir, emptyMap())
        assertTrue(found != null && "_agent/rules/override.md" in found, "$found")
    }

    private fun initGitRepo(dir: Path) {
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
    }

    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }
}
