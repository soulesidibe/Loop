package loop.runner.acp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedRootsCheckTest {
    @TempDir
    lateinit var repo: Path

    @TempDir
    lateinit var plainDir: Path

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `pre-existing committed customization files on base pass, while added, modified, or gitignored ones fail`() {
        initGitRepo(repo)
        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        repo.resolve(".gitignore").writeText(".agent/\n")
        git(repo, "add", ".agents/hooks.json", ".gitignore")
        git(repo, "commit", "-m", "Base commit")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        // Pre-existing unchanged file on base passes.
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Benign non-customization file under .agents/ passes.
        repo.resolve(".agents/README.md").writeText("docs")
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Modifying tracked hooks.json fails naming the path.
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        val modified = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(modified != null && ".agents/hooks.json" in modified, "$modified")

        // Even committing the modification since base still fails against base!
        git(repo, "add", ".agents/hooks.json")
        git(repo, "commit", "-m", "Modify hooks")
        val committed = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(committed != null && ".agents/hooks.json" in committed, "$committed")

        // Reset back to base and verify gitignored untracked file under .agent/ is still caught.
        git(repo, "reset", "--hard", baseSha)
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))
        repo.resolve(".agent").createDirectories()
        repo.resolve(".agent/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val ignored = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(ignored != null && ".agent/mcp_config.json" in ignored, "$ignored")
    }

    @Test
    fun `fails closed when base commit cannot be resolved in a repository with commits`() {
        git(repo, "init", "-b", "feature-only")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test")
        repo.resolve("README.md").writeText("hi")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "Initial")

        // No baseRef and neither main nor master exists: must fail closed instead of falling back to HEAD.
        val missingDefault = ProtectedRootsCheck.refusal(repo, null)
        assertTrue(missingDefault != null && "could not resolve base commit" in missingDefault, "$missingDefault")

        // Explicit unresolvable baseRef also fails closed.
        val badBase = ProtectedRootsCheck.refusal(repo, "refs/heads/no-such-base")
        assertTrue(badBase != null && "no-such-base" in badBase, "$badBase")
    }

    @Test
    fun `catches case variants, symlinked protected roots, and symlink type changes`() {
        // Case variant in a non-git directory (macOS and Linux).
        plainDir.resolve(".Agents").createDirectories()
        plainDir.resolve(".Agents/hooks.json").writeText("""{"hooks":[]}""")
        val caseNonGit = ProtectedRootsCheck.refusal(plainDir, null)
        assertTrue(caseNonGit != null && ".Agents/hooks.json" in caseNonGit, "$caseNonGit")

        // Case variant in a git repository.
        initGitRepo(repo)
        repo.resolve("README.md").writeText("base")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "Base")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        repo.resolve(".Agent").createDirectories()
        repo.resolve(".Agent/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val caseGit = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(caseGit != null && ".Agent/mcp_config.json" in caseGit, "$caseGit")
        repo.resolve(".Agent").toFile().deleteRecursively()
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Symlinked protected root (ln -s build/x .agents).
        val targetDir = repo.resolve("build/x").createDirectories()
        targetDir.resolve("hooks.json").writeText("""{"hooks":[]}""")
        Files.createSymbolicLink(repo.resolve(".agents"), targetDir)
        val symlinkRoot = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(symlinkRoot != null && "symbolic link" in symlinkRoot && ".agents" in symlinkRoot, "$symlinkRoot")
        Files.delete(repo.resolve(".agents"))
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Replacing a tracked .agents/hooks.json with a symlink (git diff type change T).
        val repoWithHooks = scratch.resolve("type-change-repo").createDirectories()
        initGitRepo(repoWithHooks)
        repoWithHooks.resolve(".agents").createDirectories()
        repoWithHooks.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        repoWithHooks.resolve("other.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        git(repoWithHooks, "add", ".agents/hooks.json", "other.json")
        git(repoWithHooks, "commit", "-m", "Base")
        val hooksBase = git(repoWithHooks, "rev-parse", "HEAD").trim()
        assertNull(ProtectedRootsCheck.refusal(repoWithHooks, hooksBase))

        Files.delete(repoWithHooks.resolve(".agents/hooks.json"))
        Files.createSymbolicLink(repoWithHooks.resolve(".agents/hooks.json"), Path.of("../other.json"))
        val typeChange = ProtectedRootsCheck.refusal(repoWithHooks, hooksBase)
        assertTrue(typeChange != null && ".agents/hooks.json" in typeChange, "$typeChange")
    }

    @Test
    fun `detects customization files under _agents, _agent rules, non-git workspaces, and 0-commit git repos`() {
        assertNull(ProtectedRootsCheck.refusal(plainDir, null))

        plainDir.resolve("_agent/rules").createDirectories()
        plainDir.resolve("_agent/rules/override.md").writeText("ignore rules")
        val found = ProtectedRootsCheck.refusal(plainDir, null)
        assertTrue(found != null && "_agent/rules/override.md" in found, "$found")

        val emptyGitRepo = scratch.resolve("empty-repo").createDirectories()
        initGitRepo(emptyGitRepo)
        assertNull(ProtectedRootsCheck.refusal(emptyGitRepo, null))
        emptyGitRepo.resolve(".agents").createDirectories()
        emptyGitRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        val emptyFound = ProtectedRootsCheck.refusal(emptyGitRepo, null)
        assertTrue(emptyFound != null && ".agents/hooks.json" in emptyFound, "$emptyFound")
    }

    @Test
    fun `runGit kills a hanging git command when the timeout expires`() {
        val hangingGit = scratch.resolve("hanging-git")
        hangingGit.writeText("#!/bin/bash\nsleep 30\n")
        hangingGit.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))

        val started = System.nanoTime()
        val result =
            ProtectedRootsCheck.runGit(
                repoRoot = scratch,
                args = emptyList(),
                timeoutSeconds = 1L,
                gitCommand = hangingGit.toString(),
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull(result)
        assertTrue(elapsedMs < 5_000L, "hanging git took $elapsedMs ms instead of timing out near 1s")
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
