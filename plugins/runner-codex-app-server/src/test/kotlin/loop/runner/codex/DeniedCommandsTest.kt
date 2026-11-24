package loop.runner.codex

import kotlin.io.path.Path
import kotlin.io.path.readText
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
     * The claude-code runner's list is read from its source, `ClaudeSettingsFile.kt` in the sibling module, found from
     * this module's schema folder: the object is internal to its module, and this module does not depend on it. The
     * string literals of its `deniedCommands` list and of its `OPERATOR_SUBCOMMANDS` are compared with this runner's.
     */
    @Test
    fun `bl-135 the denied commands are the claude-code runner's list`() {
        val module = Path(System.getProperty("loop.codexSchema")).parent.parent
        val source =
            module
                .resolve("../runner-claude-code/src/main/kotlin/loop/runner/claudecode/ClaudeSettingsFile.kt")
                .normalize()
                .readText()

        val commands = literals(source, "val deniedCommands: List<String> =")
        val operator = literals(source, "private val OPERATOR_SUBCOMMANDS =")

        assertTrue(commands.isNotEmpty() && operator.isNotEmpty(), "the lists were not found in ClaudeSettingsFile.kt")
        assertTrue("+ operatorCommands(LOOP)" in source && "LOOP = \"loop\"" in source, "the operator commands are built another way")
        assertEquals(commands + operator.map { "loop $it" }, DeniedCommands.commands)
    }

    /** The string literals of the first `listOf(...)` after [declaration] in [source]. */
    private fun literals(
        source: String,
        declaration: String,
    ): List<String> {
        val start = source.indexOf(declaration)
        if (start < 0) return emptyList()
        val open = source.indexOf("listOf(", start)
        val close = source.indexOf(")", open)
        return Regex("\"([^\"]*)\"").findAll(source.substring(open, close)).map { it.groupValues[1] }.toList()
    }
}
