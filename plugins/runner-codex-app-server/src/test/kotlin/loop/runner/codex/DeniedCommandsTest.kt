package loop.runner.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bl-135, the findings of the m3-20 merge risk review on [DeniedCommands]: a denied command run by its path, after a
 * git option, spelled with a backslash or with its subcommand from a substitution is denied, and a commit message that
 * only names one is not.
 */
class DeniedCommandsTest {
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
            "./gradlew :plugins:runner-codex-app-server:test",
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

    /**
     * The claude-code runner's list is read by reflection: `ClaudeSettingsFile` is internal to its module, so its
     * getter has a name mangled with the module's.
     */
    @Test
    fun `bl-135 the denied commands are the claude-code runner's list`() {
        val settings = Class.forName("loop.runner.claudecode.ClaudeSettingsFile")
        val instance = settings.getField("INSTANCE").get(null)
        val getter = settings.methods.single { it.name.startsWith("getDeniedCommands") }

        @Suppress("UNCHECKED_CAST")
        val claudeCode = getter.invoke(instance) as List<String>

        assertEquals(claudeCode, DeniedCommands.commands)
    }
}
