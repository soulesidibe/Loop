package loop.runner.claudecode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path

/**
 * The Claude Code settings file the runner passes with `--settings` for each Attempt.
 *
 * Its `permissions.deny` prefix rules block `git push`, `gh` and the history rewrites. They
 * hold under `--permission-mode bypassPermissions`. This is a guardrail, not a sandbox: a
 * prefix rule can be dodged, for example with `sh -c "gh ..."` or `git -C . push`.
 *
 * It also denies `loop approve` and `loop resume` (m2-24), by the bare name on `PATH` and by the attempt's
 * launcher path: an approval and a resume are the operator's, and the evidence summary an agent reads on its
 * issue names the approval.
 *
 * It also denies `Read` and `Edit` under each hidden folder, the holdout folder (m2-14), so
 * Claude Code's file tools cannot open or change a holdout check. A shell command such as
 * `cat` is not a file tool, so this is a guardrail too.
 */
internal object ClaudeSettingsFile {
    private const val LOOP = "loop"

    /** The `loop` subcommands only the operator may run (m2-24). */
    private val OPERATOR_SUBCOMMANDS = listOf("approve", "resume")

    /** The Bash command prefixes the agent may not run. */
    val deniedCommands: List<String> =
        listOf(
            "git push",
            "git push --force",
            "git push -f",
            "git push --force-with-lease",
            "gh",
            "git rebase",
            "git reset --hard",
            "git commit --amend",
            "git filter-branch",
            "git filter-repo",
            "git reflog expire",
            "git update-ref",
            "git replace",
        ) + operatorCommands(LOOP)

    /**
     * True when the Bash [command] holds a command the generated deny rules block, anywhere in its text: a
     * [deniedCommands] prefix, or an operator command run through a launcher path, which ends in `/loop`, whatever
     * the attempt's launcher was. Claude Code matches its rules on the text of the whole command, so it also denies
     * a command that only quotes one, in a heredoc body, a quoted string, after `&&` or `;`, or in a `python3 -`
     * script (bl-33). The command is split into words at blanks, quotes and the shell's operators, and a denied
     * command matches as a run of whole words at any place. Matching more than Claude Code does only keeps a turn
     * going after a denial the agent already saw; matching less would fail the turn as `turn_input_required`.
     */
    fun isDenied(command: String): Boolean {
        val words = command.split(WORD_BREAK).filter { it.isNotEmpty() }
        return words.indices.any { start -> startsDenied(words.subList(start, words.size)) }
    }

    /** True when [words] start with a [deniedCommands] prefix or an operator command through a launcher path. */
    private fun startsDenied(words: List<String>): Boolean =
        deniedWords.any { prefix -> words.size >= prefix.size && words.subList(0, prefix.size) == prefix } ||
            (words.size >= 2 && words[0].endsWith("/$LOOP") && words[1] in OPERATOR_SUBCOMMANDS)

    /** [deniedCommands], each as its words. */
    private val deniedWords: List<List<String>> = deniedCommands.map { it.split(" ") }

    /** What splits a command into words: blanks, quotes, backslashes, the shell's operators and list punctuation. */
    private val WORD_BREAK = Regex("""[\s'"`\\;|&<>(){}\[\],=]+""")

    /** The operator commands for the launcher [loop], a bare name or a path. */
    private fun operatorCommands(loop: String): List<String> = OPERATOR_SUBCOMMANDS.map { "$loop $it" }

    /**
     * The settings as JSON text, with `Read` and `Edit` denied under each of [hiddenDirs], absolute paths, and the
     * operator commands denied for the attempt's [launcher] path too, when it has one.
     * An absolute path rule starts with `//` in Claude Code, a single `/` is relative to the settings file.
     */
    fun json(
        hiddenDirs: List<Path> = emptyList(),
        launcher: String? = null,
    ): String {
        val launcherCommands = launcher?.takeIf { it.isNotBlank() && it != LOOP }?.let { operatorCommands(it) }.orEmpty()
        val bash = (deniedCommands + launcherCommands).map { "Bash($it:*)" }
        val files = hiddenDirs.flatMap { dir -> listOf("Read(/$dir/**)", "Edit(/$dir/**)") }
        val deny = JsonArray((bash + files).map { JsonPrimitive(it) })
        return JsonObject(mapOf("permissions" to JsonObject(mapOf("deny" to deny)))).toString()
    }

    /**
     * The paths to deny for the folder [dir]: its absolute normalized path, and its real path too when a
     * symlink makes them differ, since a rule matches the path a tool is given.
     */
    fun pathsOf(dir: Path): List<Path> {
        val absolute = dir.toAbsolutePath().normalize()
        val real =
            try {
                absolute.toRealPath()
            } catch (e: IOException) {
                absolute
            }
        return listOf(absolute, real).distinct()
    }
}
