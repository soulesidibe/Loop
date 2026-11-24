package loop.runner.codex

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * The commands a Codex agent may not run (story 40, m2-24, m3-20): `git push`, `gh`, the history rewrites, and the
 * operator commands `loop approve` and `loop resume`. The list is the claude-code runner's (`ClaudeSettingsFile` in
 * `plugins/runner-claude-code`), and `DeniedCommandsTest` fails when the two differ (bl-135).
 *
 * A command is matched on its whole text, as Claude Code matches its deny rules: it is split into words at blanks,
 * quotes and the shell's operators, and a denied command matches as a run of whole words at any place, so `cd x &&
 * git push`, `bash -lc 'gh pr create'` and a quoted script that names one are all denied. Since bl-135 also:
 *
 * - a program run by its path, a word ending in `/git`, `/loop` or, for `gh`, `/bin/gh`, so `/usr/bin/git push` is
 *   denied and `cd /tmp/gh` is not;
 * - git's own options before the subcommand, `git -c x=y push`, `git -C . push`, `git --git-dir=.git push`;
 * - a git subcommand from a substitution or a variable, `git $(echo push)`, `git $SUB`, since it may be `push`;
 * - a backslash a shell would drop, `g\h`;
 * - not a commit message: the message of a `git commit -m` or `--message` is left out, so `git commit -m "use gh"`
 *   is not denied, unless it holds a substitution, which runs.
 *
 * Matching more only declines a request. This is a guardrail, not a sandbox: a command can still dodge the words, as
 * a script file written first and run next does.
 */
internal object DeniedCommands {
    private const val LOOP = "loop"

    private const val GIT = "git"

    /** How deep a quoted script is read for its commit messages. */
    private const val MAX_DEPTH = 3

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

    /** [commands], each as its words. */
    val words: List<List<String>> = commands.map { it.split(" ") }

    /** True when [command] holds a denied command anywhere in its text, see [DeniedCommands]. */
    fun isDenied(command: String): Boolean {
        // A backslash a shell drops is dropped, and a backtick is read as the `$` of a substitution.
        val text = withoutCommitMessages(command, depth = 0).replace("\\", "").replace("`", " $ ")
        return WORD_BREAKS.any { breaks ->
            val words = text.split(breaks).filter { it.isNotEmpty() }
            words.indices.any { start -> words.holdsDeniedAt(start) }
        }
    }

    /** True when [word] runs [name]: the name itself or a path to it, see [DeniedCommands]. */
    fun runs(
        word: String,
        name: String,
    ): Boolean = word == name || word.endsWith(if (name == GH) "/bin/$GH" else "/$name")

    /** The index of the first of [words] after git's own options, from [from], see [GIT_OPTIONS_WITH_VALUE]. */
    private fun afterGitOptions(
        words: List<String>,
        from: Int,
    ): Int {
        var i = from
        while (i < words.size && words[i].startsWith("-")) i += if (words[i] in GIT_OPTIONS_WITH_VALUE) 2 else 1
        return i
    }

    /** True when these words, from [start], hold a denied command. */
    private fun List<String>.holdsDeniedAt(start: Int): Boolean =
        words.any { prefix ->
            if (!runs(this[start], prefix[0])) return@any false
            var i = start + 1
            if (prefix[0] == GIT) {
                i = afterGitOptions(this, i)
                // A subcommand from a substitution or a variable may be any of them.
                if (i < size && this[i].startsWith("$")) return@any true
            }
            prefix.drop(1).all { word -> (i < size && this[i] == word).also { i++ } }
        }

    /**
     * [command] as words, without the message of each `git commit` it runs; each quoted word that holds a blank may be
     * a script, so it is read the same way, down to [MAX_DEPTH] levels. A message that holds a substitution is kept.
     */
    private fun withoutCommitMessages(
        command: String,
        depth: Int,
    ): String {
        val tokens = ShellTokens.of(command)
        val texts = tokens.map { it.text }
        val kept = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val sub = if (!token.separator && runs(token.text, GIT)) afterGitOptions(texts, i + 1) else -1
            if (sub in tokens.indices && texts[sub] == "commit") {
                kept += texts.subList(i, sub + 1)
                i = sub + 1
                while (i < tokens.size && !tokens[i].separator) {
                    val word = texts[i]
                    val cluster = SHORT_MESSAGE.matchEntire(word)
                    when {
                        word == "--message" || (cluster != null && cluster.groupValues[1].isEmpty()) -> {
                            kept += word
                            val value = tokens.getOrNull(i + 1)
                            if (value != null && !value.separator) {
                                if (substitutes(value.text)) kept += value.text
                                i++
                            }
                        }
                        word.startsWith("--message=") || cluster != null -> if (substitutes(word)) kept += word
                        else -> kept += word
                    }
                    i++
                }
            } else {
                val script = !token.separator && depth < MAX_DEPTH && token.text.any { it.isWhitespace() }
                kept += if (script) withoutCommitMessages(token.text, depth + 1) else token.text
                i++
            }
        }
        return kept.joinToString(" ")
    }

    /** True when [text] holds a substitution, which a shell runs even inside a message. */
    private fun substitutes(text: String): Boolean = "$(" in text || "`" in text

    private const val GH = "gh"

    /** A short option cluster of `git commit` that ends in `m`, `-m` or `-am`, with the message attached to it or not. */
    private val SHORT_MESSAGE = Regex("-[A-Za-z]*?m(.*)")

    /** The options git reads before its subcommand that take a separate value. */
    private val GIT_OPTIONS_WITH_VALUE = setOf("-c", "-C", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env")

    /**
     * What splits a command into words: blanks, quotes, the shell's operators and list punctuation, once with `=` and
     * once without, so `git -c x=y push` keeps `x=y` as the value of `-c` and `--exec=gh` still names `gh`.
     */
    private val WORD_BREAKS = listOf(Regex("""[\s'"`;|&<>(){}\[\],=]+"""), Regex("""[\s'"`;|&<>(){}\[\],]+"""))
}

/**
 * A shell command as words (bl-135): split at blanks outside quotes, quotes removed, a backslash outside quotes
 * escaping the next character, and `;`, `&`, `|` and a newline as separator tokens. Nothing is expanded, and an
 * unterminated quote runs to the end of the text. It reads which commands a text runs for [DeniedCommands], it is not
 * a shell.
 */
internal object ShellTokens {
    /** One word, or a separator between two commands. */
    data class Token(
        val text: String,
        val separator: Boolean = false,
    )

    private const val SEPARATORS = ";&|\n"

    private const val DOUBLE_QUOTE_ESCAPES = "\"\\\$`"

    fun of(command: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        var inWord = false

        fun endWord() {
            if (inWord) tokens += Token(word.toString())
            word.clear()
            inWord = false
        }

        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c in SEPARATORS -> {
                    endWord()
                    tokens += Token(c.toString(), separator = true)
                }
                c.isWhitespace() -> endWord()
                c == '\'' -> {
                    val end = command.indexOf('\'', i + 1).let { if (it < 0) command.length else it }
                    word.append(command, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    i++
                    while (i < command.length && command[i] != '"') {
                        if (command[i] == '\\' && i + 1 < command.length && command[i + 1] in DOUBLE_QUOTE_ESCAPES) i++
                        word.append(command[i])
                        i++
                    }
                    inWord = true
                }
                c == '\\' -> {
                    if (i + 1 < command.length && command[i + 1] != '\n') word.append(command[i + 1])
                    inWord = true
                    i++
                }
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        endWord()
        return tokens
    }
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
