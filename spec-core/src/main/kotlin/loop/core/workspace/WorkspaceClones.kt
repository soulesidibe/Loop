package loop.core.workspace

import loop.core.verify.CloneLayout
import loop.core.verify.WorkspaceGit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * bl-28: clone workspaces, `workspace.git: clone`. Each workspace is a `git clone --shared` of the project repository
 * [projectDir]: it borrows the project's objects read only through `objects/info/alternates` and owns its refs, config,
 * hooks and info, so nothing an agent runs in it through git changes the operator's repository.
 *
 * The clone's config belongs to the agent, so every git command here runs through [git], with Loop's pins on its
 * command line (no hook, no fsmonitor), the minimal environment and lazy fetch off. Loop never reads a ref of the clone
 * to choose a base, a diff base or a push target: it passes commit ids it resolved in the project. Lazy fetch off needs
 * git 2.45 or later, so [create] and [reuse] refuse an older git (bl-246).
 *
 * In the project Loop writes three refs only: `refs/heads/loop/<key>` at an imported commit ([import]),
 * `refs/loop/workspace/<key>` at the start commit of a live clone ([keepRef]), and `refs/loop/base/<base>`, which the
 * base fetch writes.
 */
class WorkspaceClones(
    private val projectDir: Path,
    private val git: WorkspaceGit,
) {
    /**
     * Makes [workspace], an empty folder, a clone of the project on `loop/<key>`, its files checked out. The branch
     * starts at the project's `refs/heads/loop/<key>` when it exists, else at [base] resolved in the project, else at
     * the project's `HEAD`; the clone's own `loop/<key>`, which `git clone` makes when the project has it checked out,
     * is reset there (bl-246). The clone gets no `origin` remote, the project's local `user.name` and `user.email` when
     * set, and the project the keep ref of [key] at the start commit. No sparse pattern: that is the hooks' job.
     *
     * The clone is built in a sibling folder ([building]) and moved into place only once every step is done, so a
     * kill half way never leaves a half made clone at [workspace]; the next build clears what such a kill left. A
     * failure deletes the sibling folder and throws an [IOException] naming the step and git's first line; the caller
     * deletes the keep ref. [cancellation] stops the running git.
     */
    fun create(
        workspace: Path,
        key: WorkspaceKey,
        base: String?,
        cancellation: WorkspaceGit.Cancellation? = null,
    ) {
        requireGitVersion(cancellation)
        if (Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) && entries(workspace) { true }.isNotEmpty()) {
            throw IOException("the workspace $workspace is not empty and has no .git, Loop clones into an empty folder only")
        }
        val branch = branch(key)
        val start =
            projectCommit("refs/heads/$branch", cancellation)
                ?: base?.let {
                    projectCommit(it, cancellation) ?: throw IOException("the base $it is not a commit of the project at $projectDir")
                }
                ?: projectCommit("HEAD", cancellation)
                ?: throw IOException("the project at $projectDir has no commit to start the clone from")
        val building = building(workspace)
        delete(building)
        try {
            check(git.run(projectDir, "update-ref", keepRef(key), start, cancellation = cancellation), "write ${keepRef(key)}")
            val clone =
                git.run(
                    projectDir,
                    "clone",
                    "--quiet",
                    "--shared",
                    "--no-checkout",
                    "--no-tags",
                    "--template=",
                    projectDir.toString(),
                    building.toString(),
                    cancellation = cancellation,
                )
            check(clone, "clone the project into $building")
            check(git.run(building, "remote", "remove", "origin", cancellation = cancellation), "remove the origin remote of the clone")
            for (name in IDENTITY) {
                val value = git.run(projectDir, "config", "--local", "--get", name, cancellation = cancellation)
                if (value.code != 0) continue
                check(git.run(building, "config", name, value.stdout.trim(), cancellation = cancellation), "copy $name into the clone")
            }
            check(
                git.run(building, "checkout", "--quiet", "-B", branch, start, cancellation = cancellation),
                "check out $branch in the clone",
            )
            cancellation?.check()
            Files.deleteIfExists(workspace)
            Files.move(building, workspace, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { delete(building) }.exceptionOrNull()?.let { e.addSuppressed(it) }
            throw e
        }
    }

    /**
     * Checks that [workspace], a clone of the project ([state]), still holds what [create] made, before it is reused
     * as it is: a remote is removed, so a push by name cannot reach the project; a missing `loop/<key>` branch fails
     * with an [IOException] naming it, since the clone was not finished; a missing keep ref is written back at the
     * newest commit on the first parent line of `loop/<key>` that the project has. Where `HEAD` stands is the agent's
     * and the hooks' (`before_run` checks the branch out). A git older than 2.45 fails it, as in [create].
     * [cancellation] stops the running git.
     */
    fun reuse(
        workspace: Path,
        key: WorkspaceKey,
        cancellation: WorkspaceGit.Cancellation? = null,
    ) {
        requireGitVersion(cancellation)
        val remotes = git.run(workspace, "remote", cancellation = cancellation)
        check(remotes, "list the remotes of the clone $workspace")
        for (remote in remotes.stdout
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }) {
            check(git.run(workspace, "remote", "remove", remote, cancellation = cancellation), "remove the remote $remote of the clone")
        }
        val branch = branch(key)
        val tip =
            git
                .run(workspace, "rev-parse", "--verify", "-q", "refs/heads/$branch^{commit}", cancellation = cancellation)
                .takeIf { it.code == 0 }
                ?.stdout
                ?.trim()
                ?.takeIf { COMMIT_ID.matches(it) }
                ?: throw IOException(
                    "the clone $workspace has no branch $branch, it was not finished, remove the workspace so Loop makes a new one",
                )
        if (projectCommit(keepRef(key), cancellation) != null) return
        val line = git.run(workspace, "rev-list", "--first-parent", "--max-count=$KEEP_SEARCH_MAX", tip, cancellation = cancellation)
        check(line, "read the history of $branch in the clone")
        val kept =
            line.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { COMMIT_ID.matches(it) }
                .firstOrNull { sha -> git.run(projectDir, "cat-file", "-e", "$sha^{commit}", cancellation = cancellation).code == 0 }
                ?: throw IOException(
                    "the keep ref ${keepRef(key)} is missing and no commit of $branch is in the project, remove the workspace " +
                        "so Loop makes a new one",
                )
        check(git.run(projectDir, "update-ref", keepRef(key), kept, cancellation = cancellation), "write ${keepRef(key)}")
    }

    /**
     * What the `.git` of [workspace] is: absent, a clone of the project (a `.git` folder whose alternates name the
     * project's object folder, in the layout [CloneLayout] requires), or anything else, with the reason, which names
     * the offending path or key (bl-253).
     */
    fun state(workspace: Path): CloneState {
        val dotGit = workspace.resolve(".git")
        if (!Files.exists(dotGit, LinkOption.NOFOLLOW_LINKS)) return CloneState.Absent
        CloneLayout.refusal(workspace, git::refStorage)?.let { return CloneState.Foreign(it) }
        val objects = projectObjects()
        val alternates = dotGit.resolve("objects").resolve("info").resolve("alternates")
        val borrowed =
            try {
                if (Files.isRegularFile(alternates, LinkOption.NOFOLLOW_LINKS)) Files.readAllLines(alternates) else emptyList()
            } catch (e: IOException) {
                throw IOException("cannot read $alternates: ${e.message}", e)
            }
        val clone =
            borrowed.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.any { line ->
                runCatching { dotGit.resolve("objects").resolve(line).toRealPath() }.getOrNull() == objects
            }
        return if (clone) CloneState.Clone else CloneState.Foreign("$dotGit does not borrow the objects of the project at $projectDir")
    }

    /**
     * Writes `refs/loop/base/[base]` in the clone [workspace] of [key] at [sha], a commit Loop resolved in the project,
     * and the base keep ref of [key] in the project at it ([baseKeepRef]), so a rewritten base and a gc there cannot
     * drop a base commit the clone merged and borrows. A [workspace] that is not a clone of the project ([state]) is
     * refused with its reason, nothing written (bl-253). [cancellation] stops the running git.
     */
    fun writeBase(
        workspace: Path,
        key: WorkspaceKey,
        base: String,
        sha: String,
        cancellation: WorkspaceGit.Cancellation? = null,
    ) {
        requireCommitId(sha)
        requireClone(workspace)
        check(git.run(projectDir, "update-ref", baseKeepRef(key), sha, cancellation = cancellation), "write ${baseKeepRef(key)}")
        check(git.run(workspace, "update-ref", "$BASE_REFS$base", sha, cancellation = cancellation), "write $BASE_REFS$base in the clone")
    }

    /**
     * Imports the commit [sha] of the clone [workspace] into the project and points `refs/heads/[branch]` of the project
     * at it, host side and without any token. The fetch runs in a temporary bare repository that borrows the project's
     * objects, with no config file, Loop's pins and `transfer.fsckObjects`, by commit id. It holds the project's keep
     * refs of the workspace, its `refs/heads/[branch]` and its `refs/loop/base/` commits as its own refs, so git tells the
     * clone it has them and only the objects the project lacks travel and are checked. Its objects then reach the
     * project's object folder by plain file copy, and only then, in the project, git checks the commit is there and
     * writes the branch. A branch already at [sha] is left alone. A branch a worktree of the project has checked out
     * is never moved under the operator (bl-246): the import then fails naming it and that worktree, with no project
     * ref changed. A [workspace] that is not a clone of the project ([state]) is refused with its reason, even when
     * the branch is already at [sha] (bl-253). Fails with an [IOException].
     */
    fun import(
        workspace: Path,
        sha: String,
        branch: String,
    ) {
        requireCommitId(sha)
        requireClone(workspace)
        if (projectCommit("refs/heads/$branch").equals(sha, ignoreCase = true)) return
        val objects = projectObjects()
        synchronized(lockOf(objects)) {
            val dir = Files.createTempDirectory("loop-import-")
            try {
                val repository = dir.resolve("import.git")
                val format = if (sha.length == SHA256_LENGTH) listOf("--object-format=sha256") else emptyList()
                val init = listOf("init", "--bare", "--quiet", "--template=") + format + repository.toString()
                check(git.run(dir, *init.toTypedArray(), extra = NO_CONFIG_FILES), "prepare the import")
                Files.writeString(
                    Files.createDirectories(repository.resolve("objects").resolve("info")).resolve("alternates"),
                    "$objects\n",
                )
                val gitDir = "--git-dir=$repository"
                haves(branch).forEachIndexed { index, have ->
                    check(git.run(dir, gitDir, "update-ref", "$HAVE_REF$index", have, extra = NO_CONFIG_FILES), "prepare the import")
                }
                val fetch =
                    listOf(gitDir) + IMPORT_PINS +
                        listOf(
                            "fetch",
                            "--quiet",
                            "--no-tags",
                            "--no-recurse-submodules",
                            "--no-write-fetch-head",
                            workspace.toString(),
                            "+$sha:$IMPORTED_REF",
                        )
                check(git.run(dir, *fetch.toTypedArray(), extra = NO_CONFIG_FILES), "fetch $sha from the clone")
                val read = git.run(dir, gitDir, "rev-parse", "--verify", "-q", "$IMPORTED_REF^{commit}", extra = NO_CONFIG_FILES)
                check(read, "read the imported commit")
                if (!read.stdout.trim().equals(sha, ignoreCase = true)) {
                    throw IOException("the import read ${read.stdout.trim()}, not $sha")
                }
                land(repository.resolve("objects"), objects)
            } finally {
                runCatching { dir.toFile().deleteRecursively() }
            }
            val type = git.run(projectDir, "cat-file", "-t", sha)
            if (type.code != 0 || type.stdout.trim() != "commit") {
                throw IOException("the imported $sha is not a commit of the project at $projectDir: ${type.firstLine()}")
            }
            checkedOut(branch)?.let { worktree ->
                throw IOException(
                    "the project has $branch checked out in $worktree, and Loop never moves a checked out branch; " +
                        "switch that worktree to another branch, then deliver again",
                )
            }
            check(git.run(projectDir, "update-ref", "refs/heads/$branch", sha), "write refs/heads/$branch in the project")
        }
    }

    /**
     * The warning for a new clone of [key] while a worktree of the project, the main checkout or a linked one, has
     * `loop/<key>` checked out: [import] refuses to move that branch until the operator switches away. Null when no
     * worktree has it checked out, or when git cannot tell.
     */
    fun checkedOutWarning(key: WorkspaceKey): String? {
        val branch = branch(key)
        val worktree = runCatching { checkedOut(branch) }.getOrNull() ?: return null
        return "the project has $branch checked out in $worktree, so delivery will refuse to move that branch until " +
            "that worktree switches to another branch"
    }

    /**
     * The worktree of the project that has `refs/heads/[branch]` checked out, the main checkout or a linked one, null
     * when none has. Fails with an [IOException] when git cannot tell.
     */
    private fun checkedOut(branch: String): Path? {
        val ref = "refs/heads/$branch"
        val listed = git.run(projectDir, "for-each-ref", "--format=%(refname) %(worktreepath)", ref)
        check(listed, "find the worktree that has $branch checked out")
        return listed.stdout
            .lineSequence()
            .firstOrNull { it.startsWith("$ref ") }
            ?.removePrefix("$ref ")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
    }

    /**
     * Fails with an [IOException] naming the version found and the minimum when the installed git is older than
     * [MIN_GIT], which ignores `GIT_NO_LAZY_FETCH`, so an agent's promisor could run from Loop's own git (bl-246). Only the
     * leading numbers of `git version` count, since the line can end with a vendor suffix. A version that passed is not
     * read again.
     */
    private fun requireGitVersion(cancellation: WorkspaceGit.Cancellation?) {
        if (gitChecked) return
        val outcome = git.run(projectDir, "version", cancellation = cancellation)
        check(outcome, "read the git version")
        val line =
            outcome.stdout
                .trim()
                .lineSequence()
                .firstOrNull()
                .orEmpty()
        val found =
            GIT_VERSION.find(line)?.destructured?.let { (major, minor, patch) ->
                listOf(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
            } ?: throw IOException("clone workspaces need git $MIN_GIT_TEXT or later, and git printed \"${line.take(SHOWN_MAX)}\"")
        val older = found.zip(MIN_GIT).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a < b } ?: false
        if (older) {
            throw IOException(
                "clone workspaces need git $MIN_GIT_TEXT or later, which can turn lazy fetch off, and found git ${found.joinToString(".")}",
            )
        }
        gitChecked = true
    }

    @Volatile
    private var gitChecked: Boolean = false

    /** Deletes the keep ref and the base keep ref of [key] in the project; nothing to do for one that is not there. */
    fun dropKeep(key: WorkspaceKey) {
        for (ref in listOf(keepRef(key), baseKeepRef(key))) {
            if (projectCommit(ref) == null) continue
            check(git.run(projectDir, "update-ref", "-d", ref), "delete $ref")
        }
    }

    /**
     * The commits of the project the import of [branch] tells the clone it has: the keep refs of its workspace, the
     * project's `refs/heads/[branch]` and every `refs/loop/base/` ref, each a commit the clone borrows or merged.
     */
    private fun haves(branch: String): List<String> {
        val own =
            branch
                .takeIf { it.startsWith(BRANCH_PREFIX) }
                ?.let { WorkspaceKey(it.removePrefix(BRANCH_PREFIX)) }
                ?.let { listOf(keepRef(it), baseKeepRef(it)) }
                .orEmpty()
        val bases =
            git
                .run(projectDir, "for-each-ref", "--format=%(refname)", BASE_REFS)
                .takeIf { it.code == 0 }
                ?.stdout
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        return (own + "refs/heads/$branch" + bases).mapNotNull { projectCommit(it) }.distinct()
    }

    /** The commit [ref] of the project resolves to, null when none. */
    fun projectCommit(
        ref: String,
        cancellation: WorkspaceGit.Cancellation? = null,
    ): String? =
        git
            .run(projectDir, "rev-parse", "--verify", "-q", "$ref^{commit}", cancellation = cancellation)
            .takeIf { it.code == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { COMMIT_ID.matches(it) }

    /** The real path of the project's object folder, shared by its worktrees. */
    private fun projectObjects(): Path {
        val common = git.run(projectDir, "rev-parse", "--git-common-dir")
        check(common, "find the repository at $projectDir")
        return projectDir.resolve(common.stdout.trim()).resolve("objects").toRealPath()
    }

    /**
     * Copies the packs (`.pack`, then `.rev`, then `.idx`, so git never sees an index without its pack) and the loose
     * objects of [from] into [objects], each file through a temporary name, then moved into place; a file already
     * there is left alone.
     */
    private fun land(
        from: Path,
        objects: Path,
    ) {
        val packs = from.resolve("pack")
        if (Files.isDirectory(packs)) {
            val files = entries(packs) { Files.isRegularFile(it) }
            for (extension in PACK_FILES) {
                files
                    .filter { it.fileName.toString().endsWith(extension) }
                    .forEach { copyOnce(it, objects.resolve("pack").resolve(it.fileName.toString())) }
            }
        }
        for (dir in entries(from) { Files.isDirectory(it) && LOOSE_DIR.matches(it.fileName.toString()) }) {
            entries(dir) { Files.isRegularFile(it) && LOOSE_FILE.matches(it.fileName.toString()) }
                .forEach { copyOnce(it, objects.resolve(dir.fileName.toString()).resolve(it.fileName.toString())) }
        }
    }

    /** Deletes [dir] and what it holds, links not followed; nothing to do when it is not there. */
    @OptIn(ExperimentalPathApi::class)
    private fun delete(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        dir.deleteRecursively()
    }

    private fun entries(
        dir: Path,
        keep: (Path) -> Boolean,
    ): List<Path> = Files.list(dir).use { list -> list.filter(keep).toList() }

    private fun copyOnce(
        file: Path,
        target: Path,
    ) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        val dir = Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(dir, "tmp_loop_", null)
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING)
            runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("r--r--r--")) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun check(
        outcome: WorkspaceGit.Outcome,
        what: String,
    ) {
        if (outcome.code != 0) throw IOException("cannot $what: ${outcome.firstLine()}")
    }

    /** Fails with an [IOException] naming the reason when [workspace] is not a clone of the project ([state]). */
    private fun requireClone(workspace: Path) {
        when (val state = state(workspace)) {
            CloneState.Clone -> Unit
            CloneState.Absent -> throw IOException("${workspace.resolve(".git")} is missing, the workspace is no longer a clone")
            is CloneState.Foreign -> throw IOException("the workspace $workspace is not a clone of the project, ${state.reason}")
        }
    }

    private fun requireCommitId(sha: String) {
        if (!COMMIT_ID.matches(sha)) throw IOException("\"${sha.take(SHOWN_MAX)}\" is not a full commit id")
    }

    companion object {
        /** The branch of the workspace [key], the one a verified commit is pushed to: `loop/<key>`. */
        fun branch(key: WorkspaceKey): String = "loop/${key.value}"

        /** The project ref that keeps the start commit of the clone of [key], and the objects it borrows, from a gc. */
        fun keepRef(key: WorkspaceKey): String = "refs/loop/workspace/${key.value}"

        /** The project ref that keeps the last base commit Loop wrote in the clone of [key] ([writeBase]) from a gc. */
        fun baseKeepRef(key: WorkspaceKey): String = "refs/loop/workspace-base/${key.value}"

        /** The sibling folder a new clone of [workspace] is built in before it is moved into place. */
        fun building(workspace: Path): Path = workspace.resolveSibling(".loop-clone-${workspace.fileName}")

        private const val BRANCH_PREFIX = "loop/"

        private const val HAVE_REF = "refs/have/"

        /** How far back on the first parent line [reuse] looks for a commit the project has. */
        private const val KEEP_SEARCH_MAX = 1000

        private const val BASE_REFS = "refs/loop/base/"

        private const val IMPORTED_REF = "refs/heads/loop-imported"

        private val IDENTITY = listOf("user.name", "user.email")

        /** Neither the user's nor the system's git config, for the processes of the import repository. */
        private val NO_CONFIG_FILES =
            mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_SYSTEM" to "/dev/null", "GIT_CONFIG_GLOBAL" to "/dev/null")

        /**
         * Loop's settings for the import fetch, beside [WorkspaceGit]'s pins: every object checked, the file protocol
         * only, objects kept as a pack, no alternate refs command, no commit graph, no maintenance. Given with `-c`,
         * they also reach the `upload-pack` git starts in the clone and win over its config.
         */
        private val IMPORT_PINS =
            listOf(
                "transfer.fsckObjects=true",
                "protocol.allow=never",
                "protocol.file.allow=always",
                "fetch.unpackLimit=1",
                "core.alternateRefsCommand=true",
                "fetch.writeCommitGraph=false",
                "uploadpack.allowAnySHA1InWant=true",
            ).flatMap { listOf("-c", it) }

        private val PACK_FILES = listOf(".pack", ".rev", ".idx")

        private val LOOSE_DIR = Regex("[0-9a-f]{2}")

        private val LOOSE_FILE = Regex("[0-9a-f]{38}|[0-9a-f]{62}")

        private val COMMIT_ID = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

        private const val SHA256_LENGTH = 64

        /** The first git that honours `GIT_NO_LAZY_FETCH`, which every git Loop runs in a clone sets (bl-246). */
        private val MIN_GIT = listOf(2, 45, 0)

        private val MIN_GIT_TEXT = MIN_GIT.joinToString(".")

        /** The leading numbers of `git version`, such as `git version 2.54.0 (Apple Git-157)`. */
        private val GIT_VERSION = Regex("""^git version (\d+)\.(\d+)(?:\.(\d+))?""")

        private const val SHOWN_MAX = 80

        /** One lock per project object folder, so two imports never copy into it at once. */
        private val locks = ConcurrentHashMap<Path, Any>()

        private fun lockOf(objects: Path): Any = locks.computeIfAbsent(objects) { Any() }
    }
}

/** bl-28: what the `.git` of a workspace is, for [WorkspaceClones.state]. */
sealed interface CloneState {
    /** No `.git`: Loop creates the clone. */
    data object Absent : CloneState

    /** A clone of the project, reused as it is, never reset. */
    data object Clone : CloneState

    /** Anything else, which fails the attempt with [reason]. */
    data class Foreign(
        val reason: String,
    ) : CloneState
}
