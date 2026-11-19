package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name

/**
 * `loop install --repo <dir> <dest>` (m1-12, hidden): copies the build this launcher runs from to
 * [dest], outside the source repository, so a self hosted run never executes the checkout it works
 * on. Driven by the Gradle `installLoop` task. The copy lands in `<dest>.tmp` first, then replaces
 * [dest] through `<dest>.old`, so a failed copy leaves the previous install untouched.
 */
class InstallCommand(
    private val host: Host,
) : GuardedCommand("install", host) {
    private val repo by option("--repo", help = "The source repository the build comes from").required()
    private val dest by argument(name = "dest", help = "The install folder")

    override val hiddenFromHelp: Boolean = true

    override fun help(context: Context): String = "Install this build outside the source repository"

    override fun body(scope: RedactorScope) {
        val launcher = host.launcher().getOrElse { fail(it.sentence(), EXIT_REFUSED) }
        val appHome = launcher.parent.parent.toRealPath()
        val target = realOf(Path(dest))
        val tmp = target.resolveSibling("${target.name}.tmp")
        val old = target.resolveSibling("${target.name}.old")
        // The leftovers of an earlier install are deleted below, so they pass the same checks as the dest.
        listOf(target, tmp, old).forEach { refuseDest(it, appHome) }
        refuseLiveRuns()
        try {
            removeLeftover(tmp)
            removeLeftover(old)
            Files.createDirectories(target.parent)
            copyTree(appHome, tmp)
        } catch (e: IOException) {
            fail("cannot copy the build to $tmp: ${e.ioSentence()}. Nothing was installed, $target is unchanged", EXIT_IO)
        }
        swap(tmp, target, old)
        val version = BuildInfo.versionOf(target.resolve(BUILD_INFO))
        if (version == BuildInfo.UNKNOWN) {
            echo("Installed loop (version unknown) at $target.")
        } else {
            echo("Installed loop $version at $target.")
        }
    }

    /**
     * Refuses with [EXIT_REFUSED] a [target] the swap would damage: inside the source repository,
     * nesting with the running [appHome] either way, or holding the Loop store or the home folder
     * (`LOOP_HOME`, `HOME`, or an ancestor of either), or a folder that is neither empty nor a previous
     * install. Every path is real, so a symlink cannot hide one.
     */
    private fun refuseDest(
        target: Path,
        appHome: Path,
    ) {
        val repoPath = realOf(Path(repo))
        val loopHome = realOf(host.loopHome)
        val home = realOf(host.home)
        val defaultDest = loopHome.resolve(DEFAULT_DEST)
        when {
            target.startsWith(repoPath) ->
                fail(
                    "$target is inside the source repository at $repoPath. Choose a path outside it, the default is $defaultDest",
                    EXIT_REFUSED,
                )
            target.startsWith(appHome) || appHome.startsWith(target) ->
                fail("$target is the install this command runs from. Choose another path", EXIT_REFUSED)
            loopHome.startsWith(target) || home.startsWith(target) ->
                fail(
                    "$target holds the Loop store or your home folder. Choose a path of its own, the default is $defaultDest",
                    EXIT_REFUSED,
                )
            !replaceable(target) ->
                fail("$target is not empty and is not a Loop install. Choose an empty or new path", EXIT_REFUSED)
        }
    }

    /**
     * True when [target] is missing, an empty folder, or a previous install (it has `build-info.properties`).
     * A folder that cannot be listed is not known to be empty, so it is refused with the reason.
     */
    private fun replaceable(target: Path): Boolean {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isRegularFile(target.resolve(BUILD_INFO), LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.list(target).use { entries -> entries.findFirst().isEmpty }
        } catch (e: IOException) {
            fail("cannot read $target: ${e.ioSentence()}", EXIT_REFUSED)
        } catch (e: UncheckedIOException) {
            fail("cannot read $target: ${(e.cause ?: IOException(e)).ioSentence()}", EXIT_REFUSED)
        }
    }

    /**
     * Refuses with [EXIT_LIVE_RUN] while any project under this `LOOP_HOME` has a live run, or a run
     * file that cannot be read (plan D1). The run file lock is the only signal: a stale run file is
     * removed by the probe, as `loop stop` does, and open attempts in the store never block.
     */
    private fun refuseLiveRuns() {
        val runs =
            try {
                RunFiles.all(host.loopHome)
            } catch (e: IOException) {
                failRunFolder()
            } catch (e: UncheckedIOException) {
                failRunFolder()
            }
        val blocking =
            runs.mapNotNull { files ->
                val probe =
                    try {
                        files.probe()
                    } catch (e: IOException) {
                        RunFiles.Probe.Unreadable(e)
                    }
                when (probe) {
                    is RunFiles.Probe.Live -> {
                        val record = probe.record
                        "a run is in progress for project ${files.runFile.name.removeSuffix(".json")} (pid ${record.pid}), " +
                            "workflow ${record.workflow}. Run loop stop ${record.workflow} first, then install again."
                    }
                    is RunFiles.Probe.Unreadable ->
                        "cannot read the run file ${files.runFile}, a run may be in progress. Check it, then install again."
                    RunFiles.Probe.Nothing, is RunFiles.Probe.Stale -> null
                }
            }
        if (blocking.isEmpty()) return
        blocking.forEach { echo(redact("loop install: $it"), err = true) }
        throw ProgramResult(EXIT_LIVE_RUN)
    }

    /** The run folder cannot be listed, so a run may be live in it: refused like a live run. */
    private fun failRunFolder(): Nothing =
        fail(
            "cannot read the run folder ${RunFiles.dirOf(host.loopHome)}, a run may be in progress. Check it, then install again",
            EXIT_LIVE_RUN,
        )

    /** Moves the previous install aside, puts the new build in its place, then deletes the previous one. */
    private fun swap(
        tmp: Path,
        target: Path,
        old: Path,
    ) {
        when (val result = swapInstall(tmp, target, old)) {
            is SwapResult.Failed -> fail(result.message, EXIT_IO)
            SwapResult.Swapped -> Unit
        }
        try {
            removeLeftover(old)
        } catch (e: IOException) {
            val warning =
                "loop install: warning: cannot remove $old: ${e.ioSentence()}. " +
                    "The new build is installed, the next install removes $old."
            echo(redact(warning), err = true)
        }
    }

    /**
     * Copies the [source] tree to [target], keeping permissions, never following a symlink.
     * `build-info.properties` is copied first, so a copy cut short is still recognised as a Loop
     * install and the next install may delete it.
     */
    private fun copyTree(
        source: Path,
        target: Path,
    ) {
        val options = arrayOf<CopyOption>(StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
        Files.copy(source, target, *options)
        val buildInfo = source.resolve(BUILD_INFO)
        if (Files.exists(buildInfo, LinkOption.NOFOLLOW_LINKS)) Files.copy(buildInfo, target.resolve(BUILD_INFO), *options)
        try {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    if (path != source && path != buildInfo) {
                        Files.copy(path, target.resolve(source.relativize(path).toString()), *options)
                    }
                }
            }
        } catch (e: UncheckedIOException) {
            // The walk wraps a folder it cannot read; the caller reports the I/O failure itself.
            throw e.cause ?: IOException(e)
        }
    }

    /**
     * Deletes [path] and what it holds, once [refuseDest] found it missing, empty or a Loop install.
     * A symlink is removed, never followed.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun removeLeftover(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) path.deleteRecursively()
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop install: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val BUILD_INFO = "build-info.properties"
        const val DEFAULT_DEST = "install"
        const val EXIT_REFUSED = 1
        const val EXIT_LIVE_RUN = 2
        const val EXIT_IO = 3
    }
}

/** How [swapInstall] went; [Failed.message] says where the previous install and the new build are. */
internal sealed interface SwapResult {
    data object Swapped : SwapResult

    data class Failed(
        val message: String,
    ) : SwapResult
}

/**
 * Puts the new build at [tmp] in place of [target] through two renames: the previous install, if
 * any, to [old], then [tmp] to [target]. [old] is left for the caller to delete. [move] is the
 * rename, injected so each failing step can be tested.
 */
internal fun swapInstall(
    tmp: Path,
    target: Path,
    old: Path,
    move: (Path, Path) -> Unit = { from, to -> Files.move(from, to) },
): SwapResult {
    val hadPrevious = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    if (hadPrevious) {
        try {
            move(target, old)
        } catch (e: IOException) {
            return SwapResult.Failed(
                "cannot move the previous install at $target aside: ${e.ioSentence()}. $target is unchanged, the new build is at $tmp",
            )
        }
    }
    try {
        move(tmp, target)
    } catch (e: IOException) {
        val reason = e.ioSentence()
        if (!hadPrevious) {
            return SwapResult.Failed(
                "cannot install the new build at $target: $reason. There was no previous install, the new build is at $tmp",
            )
        }
        // Put the previous install back, so the documented path keeps working.
        val previous =
            try {
                move(old, target)
                "The previous install is restored at $target"
            } catch (rollback: IOException) {
                "The previous install is still at $old"
            }
        return SwapResult.Failed("cannot replace $target with the new build: $reason. $previous, the new build is at $tmp")
    }
    return SwapResult.Swapped
}

/**
 * [path] made absolute with every symlink resolved, even when it does not exist yet: the real path
 * of its nearest existing ancestor, followed by the missing names.
 */
internal fun realOf(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    var existing: Path? = absolute
    while (existing != null && !Files.exists(existing)) existing = existing.parent
    val base = existing ?: return absolute
    return base.toRealPath().resolve(base.relativize(absolute).toString()).normalize()
}
