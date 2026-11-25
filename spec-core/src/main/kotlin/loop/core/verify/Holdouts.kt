package loop.core.verify

import loop.core.workflow.CheckConfig
import loop.core.workflow.WorkflowFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Holdout checks (m2-14, plan 6.4, story 69): a kind's hidden checks, kept in the holdout folder of Loop's own
 * folder, `<LOOP_HOME>/holdout/`, outside every workspace and the repository. A holdout runs with [ENV] set to
 * that folder, so its `run` reads `$LOOP_HOLDOUT/<script>`.
 */
object Holdouts {
    /** The holdout folder's name under `LOOP_HOME`. */
    const val DIR_NAME = "holdout"

    /** The variable a holdout check gets with the holdout folder; visible checks and agents never get it from Loop. */
    const val ENV = "LOOP_HOLDOUT"

    /** The holdout folder of [loopHome]. */
    fun dir(loopHome: Path): Path = loopHome.resolve(DIR_NAME)

    /**
     * What keeps the holdouts of [workflow] from staying hidden in [holdoutDir], as a sentence for preflight, or
     * null when nothing does or the workflow declares no holdout. The folder must exist, since every holdout
     * would fail where the agent cannot fix it, and must not overlap `workspace.root` or the repository, the git
     * top level of the folder of `WORKFLOW.md` (that folder when there is none), compared by real path. A command
     * holdout's `run` must reference [ENV] or a path under the folder, so a script in the repository, which the
     * agent can read and change, never passes for a hidden one.
     */
    fun problem(
        workflow: WorkflowFile,
        holdoutDir: Path,
    ): String? {
        // m4-18: every kind's holdouts, each named by its kind's key, in the order the kinds are written.
        val kinds =
            workflow.config.kinds
                ?.named
                .orEmpty()
                .filterValues { it.holdout.isNotEmpty() }
        val first = kinds.keys.firstOrNull() ?: return null
        val key = "kinds.$first.verify.holdout"
        if (!Files.isDirectory(holdoutDir)) return "$key needs the holdout folder $holdoutDir, which does not exist"
        val dir = real(holdoutDir)
        val workspaces = real(workflow.workspaceRoot)
        val repository = repositoryOf(real(workflow.path.parent))
        return when {
            dir.startsWith(workspaces) -> inside(key, holdoutDir, "workspace.root $workspaces")
            dir.startsWith(repository) -> inside(key, holdoutDir, "the repository $repository")
            workspaces.startsWith(dir) -> "$key: workspace.root $workspaces is inside the holdout folder $holdoutDir. $MOVE"
            repository.startsWith(dir) -> "$key: the repository $repository is inside the holdout folder $holdoutDir. $MOVE"
            else -> kinds.firstNotNullOfOrNull { (name, kind) -> unreferenced("kinds.$name.verify.holdout", kind.holdout, holdoutDir, dir) }
        }
    }

    /** The problem of the first command holdout whose `run` names neither [ENV] nor a path under [dir], the real holdout folder. */
    private fun unreferenced(
        key: String,
        holdouts: List<CheckConfig>,
        holdoutDir: Path,
        dir: Path,
    ): String? {
        val index =
            holdouts.indexOfFirst { check ->
                check is CheckConfig.Command && !REFERENCE.containsMatchIn(check.run) && check.run.split(WORD_BREAK).none { under(it, dir) }
            }
        if (index < 0) return null
        return "$key[$index]: run must reference \$$ENV or a path in the holdout folder $holdoutDir, where the agent cannot read the script"
    }

    /** True when [word] is an absolute path under [dir] by real path. */
    private fun under(
        word: String,
        dir: Path,
    ): Boolean =
        try {
            word.startsWith("/") && real(Path.of(word)).startsWith(dir)
        } catch (e: InvalidPathException) {
            false
        }

    /** `$LOOP_HOLDOUT` or `${LOOP_HOLDOUT}`, not a longer name. */
    private val REFERENCE = Regex("""\$(\{$ENV\}|$ENV(?![A-Za-z0-9_]))""")

    /** What splits a shell command into words: blanks, quotes and the shell's operators. */
    private val WORD_BREAK = Regex("""[\s'"`;|&<>()=]+""")

    private const val MOVE = "Set LOOP_HOME outside it"

    private fun inside(
        key: String,
        holdoutDir: Path,
        place: String,
    ): String = "$key: the holdout folder $holdoutDir is inside $place, where the agent can read it. $MOVE"

    /** The nearest folder from [folder] up that holds a `.git` entry, [folder] itself when none does. */
    private fun repositoryOf(folder: Path): Path =
        generateSequence(folder) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) } ?: folder

    /**
     * The real path of [path], symlinks resolved; for a path that does not exist yet, the real path of its
     * nearest existing parent with the rest appended, so a missing workspace root still compares right.
     */
    private fun real(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing == null) return absolute
        val resolved =
            try {
                existing.toRealPath()
            } catch (e: IOException) {
                existing
            }
        return resolved.resolve(existing.relativize(absolute)).normalize()
    }
}
