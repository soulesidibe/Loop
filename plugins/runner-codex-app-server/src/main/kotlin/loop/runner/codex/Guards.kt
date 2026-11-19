package loop.runner.codex

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * The commands a Codex agent may not run (story 40, m2-24, m3-20): `git push`, `gh`, the history rewrites, and the
 * operator commands `loop approve` and `loop resume`. The list is the claude-code runner's (`ClaudeSettingsFile` in
 * `plugins/runner-claude-code`), kept equal by hand, so both runners refuse the same commands.
 *
 * A command is matched on its whole text, as Claude Code matches its deny rules: it is split into words at blanks,
 * quotes and the shell's operators, and a denied command matches as a run of whole words at any place, so `cd x &&
 * git push`, `bash -lc 'gh pr create'` and a quoted script that names one are all denied. An operator command run
 * through a launcher path, a word ending in `/loop`, is denied too. This is a guardrail, not a sandbox: a command can
 * still dodge a prefix, as `git -C . push` or a script file written first and run next do.
 */
internal object DeniedCommands {
    private const val LOOP = "loop"

    /** The `loop` subcommands only the operator may run (m2-24). */
    private val OPERATOR_SUBCOMMANDS = listOf("approve", "resume")

    /** The denied command prefixes, the claude-code runner's list. */
    val commands: List<String> =
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
        ) + OPERATOR_SUBCOMMANDS.map { "$LOOP $it" }

    /** True when [command] holds a denied command anywhere in its text, see [DeniedCommands]. */
    fun isDenied(command: String): Boolean {
        val words = command.split(WORD_BREAK).filter { it.isNotEmpty() }
        return words.indices.any { start -> startsDenied(words.subList(start, words.size)) }
    }

    private fun startsDenied(words: List<String>): Boolean =
        deniedWords.any { prefix -> words.size >= prefix.size && words.subList(0, prefix.size) == prefix } ||
            (words.size >= 2 && words[0].endsWith("/$LOOP") && words[1] in OPERATOR_SUBCOMMANDS)

    private val deniedWords: List<List<String>> = commands.map { it.split(" ") }

    /** What splits a command into words: blanks, quotes, backslashes, the shell's operators and list punctuation. */
    private val WORD_BREAK = Regex("""[\s'"`\\;|&<>(){}\[\],=]+""")
}

/**
 * Loop's holdout folder (m2-14) as the approval rules of the Codex runner see it (m3-20): a path or a command
 * touches it when it names the folder.
 *
 * A path is resolved before it is compared: a relative one against the `cwd` of its request, `~` against [home],
 * symlinks by real path, and the comparison ignores case, since macOS paths do. A command names the folder when its
 * text holds the folder's path, also spelled from `~` or `$HOME`, or when one of its words resolves under it. Matching
 * more only declines a request that did not need it; matching less would let a read or an edit through.
 */
internal class HoldoutFolder(
    dir: Path,
    private val home: Path?,
) {
    /** The folder by its normalized path and by its real path, lower case. */
    private val forms: List<String> = forms(dir.toAbsolutePath())

    /** True when [path], as a request gives it, is the folder or lies under it; a relative path is read from [cwd]. */
    fun covers(
        path: String,
        cwd: Path,
    ): Boolean = resolve(path, cwd)?.let { under(it) } ?: false

    /** True when the shell [command] names the folder, see [HoldoutFolder]; a relative word is read from [cwd]. */
    fun names(
        command: String,
        cwd: Path,
    ): Boolean {
        val text = command.lowercase(Locale.ROOT)
        val homeForms = home?.let { forms(it.toAbsolutePath()) }.orEmpty()
        val spelled =
            forms.flatMap { folder ->
                listOf(folder) +
                    homeForms.filter { folder.startsWith("$it/") }.flatMap { h ->
                        val rest = folder.removePrefix(h)
                        listOf("~$rest", "\$home$rest", "\${home}$rest")
                    }
            }
        if (spelled.any { text.contains(it) }) return true
        return command.split(WORD_BREAK).filter { it.isNotEmpty() }.any { word -> covers(word, cwd) }
    }

    private fun under(path: Path): Boolean {
        val candidates = forms(path)
        return forms.any { folder -> candidates.any { it == folder || it.startsWith("$folder/") } }
    }

    /** [text] as an absolute path: `~` from [home], a relative one from [base]; null when it cannot be one. */
    private fun resolve(
        text: String,
        base: Path,
    ): Path? =
        try {
            val expanded =
                when {
                    text == "~" -> home
                    text.startsWith("~/") -> home?.resolve(text.removePrefix("~/"))
                    else -> Path.of(text)
                } ?: return null
            if (expanded.isAbsolute) expanded else base.resolve(expanded)
        } catch (e: InvalidPathException) {
            null
        }

    private companion object {
        /** What splits a shell command into words: blanks, quotes and the shell's operators. */
        val WORD_BREAK = Regex("""[\s'"`;|&<>()=]+""")

        /** [path] normalized and by real path, lower case, without a trailing `/`. */
        fun forms(path: Path): List<String> {
            val absolute = path.normalize()
            return listOf(absolute, real(absolute)).map { it.toString().lowercase(Locale.ROOT).trimEnd('/') }.distinct()
        }

        /** The real path of [path], or of its nearest existing parent with the rest appended when it does not exist. */
        fun real(path: Path): Path {
            var existing: Path? = path
            while (existing != null && !Files.exists(existing)) existing = existing.parent
            existing ?: return path
            return try {
                existing.toRealPath().resolve(existing.relativize(path)).normalize()
            } catch (e: IOException) {
                path
            }
        }
    }
}
