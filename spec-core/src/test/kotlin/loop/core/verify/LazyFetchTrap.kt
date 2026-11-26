package loop.core.verify

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertTrue

/**
 * bl-246: the hostile config of the test recipe. In [repo], `extensions.partialClone` is an `ext::` URL that creates
 * [marker] when git runs it, `protocol.ext.allow` is `always`, and [ref] is written by hand at a missing commit, so a
 * git command that needs that commit lazily fetches it and runs the program, unless lazy fetch is off.
 */
class LazyFetchTrap(
    val repo: Path,
    val marker: Path,
    val ref: String,
) {
    init {
        require(' ' !in marker.toString()) { "an ext:: URL takes no space in the marker path: $marker" }
    }

    /** Sets the config and writes [ref] at [MISSING]; [marker] is deleted. */
    fun arm(): LazyFetchTrap {
        git(repo, "config", "extensions.partialClone", "ext::sh -c touch% $marker")
        git(repo, "config", "protocol.ext.allow", "always")
        val file = Path.of(git(repo, "rev-parse", "--git-common-dir").trim()).let { if (it.isAbsolute) it else repo.resolve(it) }
        Files.createDirectories(file.resolve(ref).parent)
        Files.writeString(file.resolve(ref), "$MISSING\n")
        Files.deleteIfExists(marker)
        return this
    }

    /**
     * Proves the trap is live: a plain `rev-parse` of [ref] with Loop's pins but without `GIT_NO_LAZY_FETCH` creates
     * [marker]. The marker is deleted afterwards.
     */
    fun proveLive() {
        val args = WorkspaceGit.PINS + listOf("-C", repo.toString()) + WorkspaceGit.MORE_PINS + listOf("rev-parse", "--verify", "-q", "$ref^{commit}")
        git(repo, *args.toTypedArray(), ok = false)
        assertTrue(marker.exists(), "the trap is not live: the unguarded rev-parse did not run the program")
        Files.delete(marker)
    }

    companion object {
        /** A commit id no repository holds. */
        val MISSING: String = "1".repeat(40)

        /** A [WorkspaceGit] whose processes run without `GIT_NO_LAZY_FETCH`, to show a call is live unguarded. */
        fun unguarded(env: Map<String, String>): WorkspaceGit =
            WorkspaceGit(env, startProcess = { builder ->
                builder.environment().remove("GIT_NO_LAZY_FETCH")
                builder.start()
            })

        /** Runs plain git in [dir] with no user or system config, never with `GIT_NO_LAZY_FETCH`. */
        fun git(
            dir: Path,
            vararg args: String,
            ok: Boolean = true,
        ): String {
            val process =
                ProcessBuilder(listOf("git") + args)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().keys.removeIf { it.startsWith("GIT_") }
                        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                        environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                    }.start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (ok) check(code == 0) { "git ${args.toList()} failed: $out" }
            return out
        }
    }
}
