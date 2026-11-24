package loop.runner.process

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the shared runner guard matchers in `plugins/runner-process`: [DeniedCommands], [HoldoutFolder] and
 * [ProtectedWorkspaceRoots].
 */
class GuardsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `bl-135 a denied command run by its path is denied`() {
        listOf(
            "/usr/bin/git push origin loop/c-01",
            "/Applications/Xcode.app/Contents/Developer/usr/bin/git push",
            "/opt/homebrew/bin/gh pr create --fill",
            "cd x && /usr/local/bin/gh api repos",
            "/opt/loop/bin/loop approve c-01",
        ).forEach { assertTrue(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `a custom launcher path running approve or resume is denied when launcher is provided`() {
        assertTrue(DeniedCommands.isDenied("/tmp/custom-bin approve c-01", launcher = "/tmp/custom-bin"))
        assertTrue(DeniedCommands.isDenied("/tmp/custom-bin resume c-01", launcher = "/tmp/custom-bin"))
        assertFalse(DeniedCommands.isDenied("/tmp/custom-bin tool move review", launcher = "/tmp/custom-bin"))
        assertFalse(DeniedCommands.isDenied("/tmp/other-bin approve c-01", launcher = "/tmp/custom-bin"))
    }

    @Test
    fun `bl-135 a git push after git's own options is denied`() {
        listOf(
            "git -c x=y push",
            "git -c user.name=a -c user.email=b push origin HEAD",
            "git -C . push",
            "git --git-dir=.git push",
            "git --git-dir .git --work-tree . push",
            "git --no-pager -c core.pager=cat push --force",
            "git -C . reset --hard origin/main",
            "/usr/bin/git -c x=y push",
        ).forEach { assertTrue(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `bl-135 a denied command spelled with a backslash or with its subcommand from a substitution is denied`() {
        listOf(
            "g\\h pr create",
            "\\gh pr create",
            "gi\\t pu\\sh",
            "git \$(echo push)",
            "git `echo push` origin",
            "git \${SUB} origin",
            "git \$SUB origin",
        ).forEach { assertTrue(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `bl-135 a commit message that names a denied command is not denied`() {
        listOf(
            "git commit -m \"use gh to open the pull request\"",
            "git add -A && git commit -am 'gh flow and git push docs'",
            "git commit --message=\"the gh label\"",
            "git commit --message 'gh'",
            "git commit -m\"gh\" -q",
            "git -C . commit -m 'loop approve is the operator command'",
            "bash -lc 'git commit -m \"use gh to open the pull request\"'",
            "git commit -m 'first line' -m 'mentions git push'",
        ).forEach { assertFalse(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `bl-135 a commit message keeps what runs, a substitution in it or a command after it`() {
        listOf(
            "git commit -m \"\$(gh pr create)\"",
            "git commit -m \"`gh pr create`\"",
            "git commit -m fix && gh pr create",
            "git commit -m fix; git push",
            "git commit --amend -m 'reword'",
        ).forEach { assertTrue(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `bl-135 a path that only ends in a denied name is not a denied command`() {
        listOf(
            "cd /tmp/gh && ls",
            "ls ./vendor/gh",
            "cat docs/gh",
            "git status",
            "git log --oneline -5",
            "./gradlew :plugins:runner-process:test",
        ).forEach { assertFalse(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `m3-20 the denied commands are still found anywhere in the text`() {
        listOf(
            "cd x && git push",
            "bash -lc 'gh pr create'",
            "python3 - <<'EOF'\nimport os\nos.system(\"git push\")\nEOF",
            "git rebase -i HEAD~3",
            "loop resume c-01",
        ).forEach { assertTrue(DeniedCommands.isDenied(it), it) }
    }

    @Test
    fun `holdout folder matches absolute, relative, tilde, env and symlink paths`() {
        val home = tmp.toRealPath().resolve("home").createDirectories()
        val realHoldout = home.resolve(".loop/holdout").createDirectories()
        val ws = tmp.toRealPath().resolve("ws").createDirectories()
        val link = Files.createSymbolicLink(ws.resolve("shortcut"), realHoldout)

        val holdout = HoldoutFolder(realHoldout, home)

        assertTrue(holdout.covers(realHoldout.resolve("check.sh").toString(), ws))
        assertTrue(holdout.covers("~/.loop/holdout/check.sh", ws))
        assertTrue(holdout.covers(link.resolve("check.sh").toString(), ws))
        assertTrue(holdout.covers("../home/.loop/holdout/check.sh", ws))
        assertTrue(holdout.names("cat ~/.loop/holdout/check.sh", ws))
        assertTrue(holdout.names("cat \$HOME/.loop/holdout/check.sh", ws))
        assertTrue(holdout.names("cat shortcut/check.sh", ws))
        assertFalse(holdout.covers("src/Main.kt", ws))
        assertFalse(holdout.names("cat src/Main.kt", ws))
    }

    @Test
    fun `protected workspace roots match all four agy roots and walk up to git root`() {
        val repo = tmp.toRealPath().resolve("repo").createDirectories()
        repo.resolve(".git").createDirectories()
        val nested = repo.resolve("modules/app").createDirectories()
        val link = Files.createSymbolicLink(nested.resolve("alias"), repo.resolve(".agents"))

        val protectedRoots = ProtectedWorkspaceRoots(nested, home = tmp.toRealPath())

        // Covers workspace-local and ancestor-to-git roots for all 4 names.
        listOf(
            ".agents/hooks.json",
            ".agent/mcp_config.json",
            "_agents/skills.json",
            "_agent/rules/rule.md",
            repo.resolve(".agents/hooks.json").toString(),
            repo.resolve("modules/.agent/hooks.json").toString(),
            link.resolve("hooks.json").toString(),
        ).forEach { path ->
            assertTrue(protectedRoots.covers(path, nested), "expected covered: $path")
        }

        // Commands targeting protected roots are caught.
        listOf(
            "echo '{}' > .agents/hooks.json",
            "cp evil.json ${repo.resolve("_agents/mcp_config.json")}",
            "touch alias/hooks.json",
        ).forEach { cmd ->
            assertTrue(protectedRoots.names(cmd, nested), "expected named: $cmd")
        }

        // Normal workspace paths and folders above the git root are not covered.
        val aboveGit = tmp.toRealPath().resolve(".agents/hooks.json")
        assertFalse(protectedRoots.covers("src/Main.kt", nested))
        assertFalse(protectedRoots.covers(".github/workflows/ci.yml", nested))
        assertFalse(protectedRoots.covers(aboveGit.toString(), nested))
        assertFalse(protectedRoots.names("./gradlew test", nested))
    }

    @Test
    fun `isCustomizationPath flags executable and config customization files under protected roots`() {
        listOf(
            ".agents/hooks.json",
            ".agent/mcp_config.json",
            "_agents/skills.json",
            "_agent/rules.json",
            ".agents/plugins.json",
            ".agents/plugins/evil/plugin.json",
            ".agents/rules/override.md",
            "sub/dir/.agents/hooks/pre.sh",
            ".AGENTS/Hooks.json",
        ).forEach { path ->
            assertTrue(ProtectedWorkspaceRoots.isCustomizationPath(path), "expected customization path: $path")
        }

        listOf(
            "src/Main.kt",
            "hooks.json",
            ".agents/README.md",
            ".agents/skills/tdd/SKILL.md",
            ".github/workflows/build.yml",
        ).forEach { path ->
            assertFalse(ProtectedWorkspaceRoots.isCustomizationPath(path), "expected safe path: $path")
        }
        assertEquals(listOf(".agents", ".agent", "_agents", "_agent"), ProtectedWorkspaceRoots.ROOT_NAMES)
    }
}
