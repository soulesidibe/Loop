package loop.runner.claudecode

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * The folders the generated settings deny reads and edits under, the holdout folder (m2-14), as the denials of a
 * turn are matched against them. A denial that touches one is a policy denial: the agent saw it and goes on, like
 * a deny-listed command, so it never fails the turn with `turn_input_required`.
 *
 * Claude Code may report a path the way the agent wrote it, so a path is resolved before it is compared: a
 * relative path against [cwd], the agent's workspace, `~` against [home], symlinks by real path (a workspace link
 * to the folder), and the comparison ignores case, since macOS paths do. A file tool's `path` and the literal
 * folder at the start of its `pattern` (a Glob may name the folder only there) both count. A Bash denial counts
 * when its command names a folder, by its text or by a word that resolves under it. Matching more than the deny
 * rules do only keeps a turn going after a denial the agent already saw; matching less would fail the turn.
 */
internal class HiddenFolders(
    dirs: List<Path>,
    private val cwd: Path?,
    private val home: Path?,
) {
    /** Each folder by its normalized path and by its real path, lower case. */
    private val folders: List<String> = dirs.flatMap { forms(it) }.distinct()

    fun covers(denial: StreamLine.Denial): Boolean {
        if (folders.isEmpty()) return false
        return if (denial.toolName == BASH) {
            names(denial.command ?: return false)
        } else {
            val base = denial.path?.let { resolve(it, cwd) }
            val pattern = denial.pattern?.let { literalFolder(it) }?.let { resolve(it, base ?: cwd) }
            listOfNotNull(base, pattern).any { under(it) }
        }
    }

    /** True when the [command] names a folder: its text holds one, or one of its words resolves under one. */
    private fun names(command: String): Boolean {
        val text = command.lowercase(Locale.ROOT)
        val homeForms = home?.let { forms(it) }.orEmpty()
        val spelled =
            folders.flatMap { folder ->
                listOf(folder) +
                    homeForms.filter { folder.startsWith("$it/") }.flatMap { h ->
                        val rest = folder.removePrefix(h)
                        listOf("~$rest", "\$home$rest", "\${home}$rest")
                    }
            }
        if (spelled.any { text.contains(it) }) return true
        return command.split(WORD_BREAK).filter { it.isNotEmpty() }.any { word ->
            resolve(word, cwd)?.let { under(it) } ?: false
        }
    }

    private fun under(path: Path): Boolean {
        val candidates = forms(path)
        return folders.any { folder -> candidates.any { it == folder || it.startsWith("$folder/") } }
    }

    /** [text] as an absolute path: `~` from [home], a relative one from [base]; null when it cannot be one. */
    private fun resolve(
        text: String,
        base: Path?,
    ): Path? =
        try {
            val expanded =
                when {
                    text == "~" -> home
                    text.startsWith("~/") -> home?.resolve(text.removePrefix("~/"))
                    else -> Path.of(text)
                } ?: return null
            if (expanded.isAbsolute) expanded else base?.resolve(expanded)
        } catch (e: InvalidPathException) {
            null
        }

    companion object {
        /** No hidden folder: every denial outside the deny-listed commands needs input. */
        val NONE = HiddenFolders(emptyList(), cwd = null, home = null)

        private const val BASH = "Bash"

        /** What splits a shell command into words: blanks, quotes and the shell's operators. */
        private val WORD_BREAK = Regex("""[\s'"`;|&<>()=]+""")

        /** The glob characters of a Glob pattern. */
        private val GLOB = Regex("""[*?\[{]""")

        /** The folder a [pattern] names before its first glob character, null when it names none. */
        private fun literalFolder(pattern: String): String? {
            val firstGlob = GLOB.find(pattern)?.range?.first ?: return pattern
            return pattern.substring(0, firstGlob).substringBeforeLast('/', "").ifEmpty { if (pattern.startsWith("/")) "/" else null }
        }

        /** [path] normalized and by real path, lower case, without a trailing `/`. */
        private fun forms(path: Path): List<String> {
            val absolute = path.normalize()
            return listOf(absolute, real(absolute)).map { it.toString().lowercase(Locale.ROOT).trimEnd('/') }.distinct()
        }

        /** The real path of [path], or of its nearest existing parent with the rest appended when it does not exist. */
        private fun real(path: Path): Path {
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
