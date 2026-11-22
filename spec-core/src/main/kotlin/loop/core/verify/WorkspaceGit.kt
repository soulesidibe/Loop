package loop.core.verify

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Every git command Loop runs in a workspace for itself (m2-25, m2-26, part of bl-55): the fingerprint, the tree
 * restore and the merge check. The workspace's `.git/config` is shared with the project and the agent can write it,
 * so each command gets Loop's [PINS] and [MORE_PINS] on its command line (no hooks, no fsmonitor, no signing, no
 * signature check, no gpg program, no automatic gc or maintenance, no replace refs), only `PATH`,
 * `HOME` and the locale of [env] ([minimal]: no token, no Anthropic key, no `GIT_` variable that could point git at
 * another repository) plus `GIT_TERMINAL_PROMPT=0`, and is stopped with what it started after [timeout]. Filters and
 * merge drivers a `.gitattributes` names can still run as Loop's user: bl-55 and M5 own that cure.
 */
class WorkspaceGit(
    env: Map<String, String> = System.getenv(),
    private val git: String = "git",
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
    private val workTreeLimits: WorkTreeLimits = WorkTreeLimits(),
) {
    /**
     * A switch that stops the git commands and the hashing of one read, such as [workTree], from another thread (bl-149):
     * [cancel] stops the git it started, if one runs, and every later step fails with an [IOException].
     */
    class Cancellation {
        @Volatile
        var cancelled: Boolean = false
            private set

        private var process: Process? = null

        /** Stops the read: its running git now, its next step at once. */
        fun cancel() {
            val running =
                synchronized(this) {
                    cancelled = true
                    process
                }
            running?.let(::stop)
        }

        /** The read started [started]: it is stopped at once when the read is already cancelled. */
        internal fun started(started: Process) {
            val stopNow =
                synchronized(this) {
                    process = started
                    cancelled
                }
            if (stopNow) stop(started)
        }

        /** Fails when the read was cancelled. */
        internal fun check() {
            if (cancelled) throw IOException(CANCELLED)
        }
    }

    private val env: Map<String, String> = minimal(env)

    /** What one git command printed and how it exited. */
    data class Outcome(
        val code: Int,
        val stdout: String,
        val stderr: String,
    ) {
        /** The first line of stderr, else of stdout, for a message. */
        fun firstLine(): String =
            (stderr.lineSequence() + stdout.lineSequence())
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("hint:") }
                ?: "no output"
    }

    /**
     * Runs git with [args] in [root], [read] consuming its stdout as it comes, stderr discarded; its exit code. [extra]
     * is added to the environment. A git that does not finish within the timeout is stopped and fails with an
     * [IOException], like one that cannot start.
     */
    fun stream(
        root: Path,
        args: List<String>,
        extra: Map<String, String> = emptyMap(),
        cancellation: Cancellation? = null,
        read: (InputStream) -> Unit,
    ): Int {
        val builder = ProcessBuilder(command(root, args)).redirectError(ProcessBuilder.Redirect.DISCARD)
        return start(builder, extra, cancellation) { process ->
            process.inputStream.use(read)
            process.waitFor()
        }
    }

    /**
     * Runs git with [args] in [root] and keeps what it prints; [extra] is added to the environment, and a [cancellation]
     * stops it.
     */
    fun run(
        root: Path,
        vararg args: String,
        extra: Map<String, String> = emptyMap(),
        cancellation: Cancellation? = null,
    ): Outcome {
        val builder = ProcessBuilder(command(root, args.toList()))
        return start(builder, extra, cancellation) { process ->
            val err = ByteArrayOutputStream()
            var errorRead: Result<Long>? = null
            val errors =
                Thread {
                    errorRead = runCatching { process.errorStream.use { it.copyTo(err) } }
                }.apply { isDaemon = true }
            errors.start()
            val out = runCatching { process.inputStream.use { it.readBytes() } }
            val code = process.waitFor()
            errors.join()
            errorRead!!.getOrThrow()
            Outcome(code, String(out.getOrThrow(), Charsets.UTF_8), err.toString(Charsets.UTF_8))
        }
    }

    /** The commit `HEAD` of [root] is at; a [cancellation] stops the read. */
    fun head(
        root: Path,
        cancellation: Cancellation? = null,
    ): String {
        val outcome = run(root, "rev-parse", "--verify", "-q", "HEAD", cancellation = cancellation)
        if (outcome.code != 0) throw IOException("git rev-parse HEAD exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout.trim()
    }

    /**
     * Each file [head] changes against its merge base with [base] (m2-28), the diff of the branch's own work: its
     * path and the lines added and deleted. Renames are listed as a deletion and an addition, so both paths are seen.
     * No external diff or text conversion runs, and no attribute can hide lines (send back 2 of PR #75): a file
     * numstat prints as `-`, a binary one or one a `-diff` or `binary` line in a committed `.gitattributes`, the shared
     * `info/attributes` or a `core.attributesFile` marks, is counted from its patch as text instead. One whose patch
     * cannot be read either is kept as not [DiffEntry.counted]. A git failure is an [IOException].
     */
    fun diffStat(
        root: Path,
        base: String,
        head: String,
    ): List<DiffEntry> {
        val outcome = run(root, *NO_ATTRIBUTES, "diff", "--numstat", "-z", *DIFF_AS_TEXT, "$base...$head", "--")
        if (outcome.code != 0) throw IOException("git diff exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) throw IOException("git diff printed an unexpected line: ${line.take(LINE_MAX)}")
                val path = parts[2].trimStart('\n')
                val added = parts[0].toIntOrNull()
                val deleted = parts[1].toIntOrNull()
                if (added != null && deleted != null) {
                    DiffEntry(path, added, deleted)
                } else {
                    textCounts(root, base, head, path)?.let { (plus, minus) -> DiffEntry(path, plus, minus) }
                        ?: DiffEntry(path, 0, 0, counted = false)
                }
            }
    }

    /**
     * The lines added and deleted in the patch of the one file [path], diffed as text whatever its attributes say; null
     * when git fails. Every line after the first hunk header that starts with `+` or `-` is a change.
     */
    private fun textCounts(
        root: Path,
        base: String,
        head: String,
        path: String,
    ): Pair<Int, Int>? {
        val outcome = run(root, *NO_ATTRIBUTES, "--literal-pathspecs", "diff", "--no-color", *DIFF_AS_TEXT, "$base...$head", "--", path)
        if (outcome.code != 0) return null
        val hunks = outcome.stdout.lineSequence().dropWhile { !it.startsWith("@@") }
        var added = 0
        var deleted = 0
        hunks.forEach { line ->
            when {
                line.startsWith("+") -> added++
                line.startsWith("-") -> deleted++
            }
        }
        return added to deleted
    }

    /**
     * The text of the diff of [head] against its merge base with [base] (m2-28), for the merge risk reviewer, cut at
     * [maxChars]. No color, external diff or text conversion, and every file as text whatever its attributes say, as
     * [diffStat] counts it; a NUL byte of a binary file is shown as U+FFFD. A git failure is an [IOException].
     */
    fun diffText(
        root: Path,
        base: String,
        head: String,
        maxChars: Int,
    ): String {
        val outcome = run(root, *NO_ATTRIBUTES, "diff", "--no-color", *DIFF_AS_TEXT, "$base...$head", "--")
        if (outcome.code != 0) throw IOException("git diff exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout.take(maxChars).replace('\u0000', '\uFFFD')
    }

    /**
     * The paths of the files that differ from [from] to [to] (bl-176), or, with [sinceMergeBase], from the merge base of
     * [from] and [to] to [to]. No rename detection, so a renamed file counts under both of its paths. A git failure is
     * an [IOException].
     */
    fun changedFiles(
        root: Path,
        from: String,
        to: String,
        sinceMergeBase: Boolean,
    ): List<String> {
        val range = if (sinceMergeBase) listOf("$from...$to") else listOf(from, to)
        val outcome = run(root, *NO_ATTRIBUTES, "diff", "--name-only", "-z", "--no-renames", *range.toTypedArray(), "--")
        if (outcome.code != 0) throw IOException("git diff exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout.split('\u0000').filter { it.isNotEmpty() }
    }

    /**
     * The paths of the files [commit] holds under the folder [dir] (m2-28), read from the commit, not the work tree;
     * empty when it holds none. A git failure is an [IOException].
     */
    fun files(
        root: Path,
        commit: String,
        dir: String,
    ): List<String> {
        val outcome = run(root, "ls-tree", "-r", "-z", "--name-only", commit, "--", dir)
        if (outcome.code != 0) throw IOException("git ls-tree exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout.split('\u0000').filter { it.isNotEmpty() }
    }

    /**
     * A fingerprint of the work tree of [root] against its HEAD (bl-136): the SHA-256 of the path of every entry
     * `git status` lists, tracked changes and untracked files git does not ignore, each with what its file holds, so a
     * second edit of a file already changed reads differently. Ignored files, such as a gitignored `.scratch/`, are left
     * out. It takes no optional lock, so the index is never rewritten.
     *
     * bl-149: it reads file content only. The status letters are left out and each path counts once, so `git add`,
     * `git reset` or a stash and its pop with no content change read the same. A regular file reads as the hash of its
     * content, or as its size and modified time when it is over [WorkTreeLimits.fileBytes] or Loop cannot open it, so
     * one unreadable file never fails the whole read and a change to it still shows. A symbolic link reads as the name
     * of its target, a deleted file as nothing, and a nested repository or submodule as its HEAD and its own work tree
     * fingerprint, down to [WorkTreeLimits.depth]. The read is bounded: `git status` is read as it streams, more than
     * [WorkTreeLimits.entries] entries in all fail it, and a [cancellation] stops its git and its hashing at the next
     * chunk. A git failure, in a folder that is not a git work tree for one, is an [IOException].
     */
    fun workTree(
        root: Path,
        cancellation: Cancellation = Cancellation(),
    ): String = workTree(root, cancellation, depth = 0, entries = intArrayOf(0))

    /** The [workTree] of [root], a repository [depth] levels below the first, [entries] counting the entries read so far. */
    private fun workTree(
        root: Path,
        cancellation: Cancellation,
        depth: Int,
        entries: IntArray,
    ): String {
        val paths = statusPaths(root, cancellation, entries)
        val digest = MessageDigest.getInstance(SHA_256)
        paths.forEach { path ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(content(root.resolve(path), cancellation, depth, entries).toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    /**
     * The paths `git status` lists in [root], sorted, each once: a path both staged for deletion and untracked is one
     * path. Read as git prints them, entry by entry, with at most [WorkTreeLimits.entries] counted in [entries].
     */
    private fun statusPaths(
        root: Path,
        cancellation: Cancellation,
        entries: IntArray,
    ): Set<String> {
        val paths = sortedSetOf<String>()
        var overflow = false
        val code =
            stream(root, STATUS_ARGS, cancellation = cancellation) { input ->
                val entry = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (!overflow) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    for (index in 0 until read) {
                        val byte = buffer[index]
                        if (byte.toInt() != 0) {
                            if (entry.size() < ENTRY_BYTES_MAX) entry.write(byte.toInt())
                            continue
                        }
                        val line = entry.toString(Charsets.UTF_8)
                        entry.reset()
                        if (line.length <= STATUS_PREFIX) continue
                        if (++entries[0] > workTreeLimits.entries) {
                            overflow = true
                            break
                        }
                        paths += line.substring(STATUS_PREFIX)
                    }
                }
            }
        if (overflow) throw IOException("git status lists more than ${workTreeLimits.entries} entries")
        if (code != 0) throw IOException("git status exited with code $code")
        return paths
    }

    /** What the entry at [path] holds, as [workTree] reads it. */
    private fun content(
        path: Path,
        cancellation: Cancellation,
        depth: Int,
        entries: IntArray,
    ): String {
        cancellation.check()
        val attributes =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return ""
            } catch (e: IOException) {
                return "unreadable"
            }
        val stamp = "${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"
        return when {
            attributes.isSymbolicLink ->
                "link:" + runCatching { Files.readSymbolicLink(path).toString() }.getOrDefault("unreadable")
            attributes.isRegularFile ->
                if (attributes.size() > workTreeLimits.fileBytes) {
                    "large:$stamp"
                } else {
                    try {
                        "file:" + contentHash(path, cancellation)
                    } catch (e: IOException) {
                        if (cancellation.cancelled) throw e
                        "unreadable:$stamp"
                    }
                }
            attributes.isDirectory && depth < workTreeLimits.depth && Files.exists(path.resolve(".git"), LinkOption.NOFOLLOW_LINKS) -> {
                val head = run(path, "rev-parse", "--verify", "-q", "HEAD", cancellation = cancellation)
                val commit = if (head.code == 0) head.stdout.trim() else ""
                "repo:$commit:" + workTree(path, cancellation, depth + 1, entries)
            }
            else -> "other"
        }
    }

    /** The SHA-256 of the content of the file at [path], never following a link, read chunk by chunk until cancelled. */
    private fun contentHash(
        path: Path,
        cancellation: Cancellation,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            while (true) {
                cancellation.check()
                buffer.clear()
                if (channel.read(buffer) < 0) break
                buffer.flip()
                digest.update(buffer)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    /** True when [sha] names a commit the repository of [root] holds. */
    fun hasCommit(
        root: Path,
        sha: String,
    ): Boolean {
        val outcome = run(root, "cat-file", "-t", sha)
        return outcome.code == 0 && outcome.stdout.trim() == "commit"
    }

    /** True when [ancestor] is an ancestor of [commit], or the same commit; a git failure is an [IOException]. */
    fun isAncestor(
        root: Path,
        ancestor: String,
        commit: String,
    ): Boolean {
        val outcome = run(root, "merge-base", "--is-ancestor", ancestor, commit)
        return when (outcome.code) {
            0 -> true
            1 -> false
            else -> throw IOException("git merge-base exited with code ${outcome.code}: ${outcome.firstLine()}")
        }
    }

    /** How a [merge] ended: [Merged] at its new head, or a [Conflict] already aborted, the work tree back where it was. */
    sealed interface MergeOutcome {
        data class Merged(
            val head: String,
        ) : MergeOutcome

        data class Conflict(
            val detail: String,
        ) : MergeOutcome
    }

    /**
     * Merges [sha] into the branch checked out in [root] with a merge commit, never a fast forward, authored and
     * committed by Loop with [message]. A merge that stops with conflicted paths, a [MergeOutcome.Conflict], is aborted
     * so the work tree is back at its commit. Any other failure of the merge (a lock file, an untracked file in the
     * way, a bad object) is an [IOException] quoting git, after the work tree is put back the same way; so is any other
     * git failure.
     */
    fun merge(
        root: Path,
        sha: String,
        message: String,
    ): MergeOutcome {
        val before = head(root)
        val outcome = run(root, "merge", "--no-ff", "--no-edit", "--no-verify", "-m", message, sha, extra = LOOP_IDENTITY)
        if (outcome.code == 0) return MergeOutcome.Merged(head(root))
        val conflicted = conflictedPaths(root)
        if (conflicted.isNotEmpty()) {
            abort(root)
            checkHead(root, before)
            return MergeOutcome.Conflict(conflictDetail(conflicted))
        }
        val cleanup = runCatching { if (merging(root)) abort(root) }.exceptionOrNull()
        checkHead(root, before)
        val failed = "git merge exited with code ${outcome.code}: ${outcome.firstLine()}"
        throw IOException(cleanup?.let { "$failed, then ${it.message}" } ?: failed)
    }

    /** Fails when `HEAD` of [root] is no longer [before] after a merge was undone. */
    private fun checkHead(
        root: Path,
        before: String,
    ) {
        val after = head(root)
        if (after != before) throw IOException("the aborted merge left HEAD at $after, not $before")
    }

    /** True when [root] has a merge in progress, a `MERGE_HEAD`. */
    private fun merging(root: Path): Boolean = run(root, "rev-parse", "--verify", "-q", "MERGE_HEAD").code == 0

    /** The paths `git diff --diff-filter=U` lists as conflicted in [root], empty when it lists none or fails. */
    private fun conflictedPaths(root: Path): List<String> {
        val listed = run(root, "diff", "--name-only", "--diff-filter=U", "-z")
        if (listed.code != 0) return emptyList()
        return listed.stdout.split('\u0000').filter { it.isNotEmpty() }
    }

    /** The conflicted [paths] as one short line. */
    private fun conflictDetail(paths: List<String>): String {
        val shown = paths.take(MAX_PATHS).joinToString(", ") { it.take(MAX_PATH_CHARS) }
        return "conflict in $shown" + if (paths.size > MAX_PATHS) " and ${paths.size - MAX_PATHS} more" else ""
    }

    /**
     * Aborts the merge Loop left in [root] at the commit [head] (m2-26), when a stop came between `git merge` and its
     * end: true when one was in progress there and is now aborted, false when there was none.
     */
    fun abortStrandedMerge(
        root: Path,
        head: String,
    ): Boolean {
        if (!merging(root) || head(root) != head) return false
        abort(root)
        checkHead(root, head)
        return true
    }

    /** The parents of the commit [sha] in [root], in order; a git failure is an [IOException]. */
    fun parents(
        root: Path,
        sha: String,
    ): List<String> {
        val outcome = run(root, "rev-list", "--parents", "-n", "1", sha, "--")
        if (outcome.code != 0) throw IOException("git rev-list exited with code ${outcome.code}: ${outcome.firstLine()}")
        return outcome.stdout
            .trim()
            .split(' ')
            .drop(1)
    }

    /** Aborts a merge in progress in [root], resetting its work tree to `HEAD` when git has no merge to abort. */
    private fun abort(root: Path) {
        val aborted = run(root, "merge", "--abort")
        if (aborted.code == 0) return
        val reset = run(root, "reset", "--hard", "--quiet", "HEAD")
        if (reset.code != 0) throw IOException("cannot abort the merge: ${aborted.firstLine()}, then ${reset.firstLine()}")
    }

    private fun command(
        root: Path,
        args: List<String>,
    ): List<String> = listOf(git) + PINS + listOf("-C", root.toString()) + MORE_PINS + args

    private fun <T> start(
        builder: ProcessBuilder,
        extra: Map<String, String>,
        cancellation: Cancellation?,
        body: (Process) -> T,
    ): T {
        cancellation?.check()
        builder.environment().clear()
        builder.environment().putAll(env)
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
        builder.environment().putAll(extra)
        val process = startProcess(builder)
        cancellation?.started(process)
        val timedOut = AtomicBoolean(false)
        val watchdog =
            WATCHDOG.schedule(
                {
                    if (process.isAlive) {
                        timedOut.set(true)
                        stop(process)
                    }
                },
                timeout.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        try {
            process.outputStream.close()
            val result = body(process)
            if (timedOut.get()) throw IOException("git did not finish within $timeout")
            cancellation?.check()
            return result
        } catch (e: IOException) {
            if (timedOut.get()) throw IOException("git did not finish within $timeout", e)
            if (cancellation?.cancelled == true) throw IOException(CANCELLED, e)
            throw e
        } finally {
            watchdog.cancel(false)
            if (process.isAlive) stop(process)
        }
    }

    override fun toString(): String = "WorkspaceGit(git=$git, timeout=$timeout)"

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.minutes

        /** No `core.attributesFile` of any config is read by a diff Loop counts (send back 2 of PR #75). */
        private val NO_ATTRIBUTES = arrayOf("-c", "core.attributesFile=/dev/null")

        /** Patches as text, no rename pairing, no external diff and no text conversion (m2-28); numstat ignores `--text`. */
        private val DIFF_AS_TEXT = arrayOf("--text", "--no-renames", "--no-ext-diff", "--no-textconv")

        /** The most of an unexpected git line an error shows. */
        private const val LINE_MAX: Int = 200

        /**
         * Loop's settings on the command line of every workspace git command: no hook of any config runs, no
         * fsmonitor program, no signing that could prompt, and no commit graph file, which an agent can edit to change
         * the merge base and ancestor answers Loop relies on (bl-32).
         */
        val PINS: List<String> =
            listOf("core.hooksPath=/dev/null", "core.fsmonitor=false", "commit.gpgsign=false", "core.commitGraph=false").flatMap {
                listOf("-c", it)
            }

        /**
         * More of Loop's settings, after `-C` (m2-26): no signature check on a merge, no gpg, ssh or x509 program of
         * any config, no automatic gc or maintenance started in the background, and replace refs ignored, so a
         * signed base tip or a planted `refs/replace/` changes nothing of what Loop runs or reads.
         */
        val MORE_PINS: List<String> =
            listOf(
                "merge.verifySignatures=false",
                "gpg.program=",
                "gpg.ssh.program=",
                "gpg.x509.program=",
                "gc.auto=0",
                "maintenance.auto=false",
            ).flatMap { listOf("-c", it) } + "--no-replace-objects"

        /** The author and committer of a merge commit Loop makes. */
        val LOOP_IDENTITY: Map<String, String> =
            mapOf(
                "GIT_AUTHOR_NAME" to "Loop",
                "GIT_AUTHOR_EMAIL" to "loop@localhost",
                "GIT_COMMITTER_NAME" to "Loop",
                "GIT_COMMITTER_EMAIL" to "loop@localhost",
            )

        /** `PATH`, `HOME` and the locale (`LANG`, `LANGUAGE`, `LC_*`) of [env], nothing else. */
        fun minimal(env: Map<String, String>): Map<String, String> = env.filterKeys { it in KEPT_ENV || it.startsWith("LC_") }

        private val KEPT_ENV = setOf("PATH", "HOME", "LANG", "LANGUAGE")

        private const val SHA_256 = "SHA-256"

        /** The `git status` of [workTree]: no optional lock, NUL separated, every untracked file, no rename pairs. */
        private val STATUS_ARGS =
            listOf("--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames")

        /** The `XY ` status and space before the path of a `git status --porcelain=v1` entry. */
        private const val STATUS_PREFIX = 3

        /** The most of one `git status` entry kept, far above any path a file system allows. */
        private const val ENTRY_BYTES_MAX = 64 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        private const val MAX_PATHS = 5
        private const val MAX_PATH_CHARS = 100

        private const val CANCELLED = "the git read was cancelled"

        /** Stops [process] and what it started. */
        private fun stop(process: Process) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "loop-workspace-git").apply { isDaemon = true } }
    }
}

/**
 * The bounds of one [WorkspaceGit.workTree] read (bl-149): at most [entries] `git status` entries in all, nested
 * repositories included, the content of a file hashed only up to [fileBytes] (a larger one reads as its size and
 * modified time), and nested repositories or submodules read down to [depth] levels.
 */
data class WorkTreeLimits(
    val entries: Int = 20_000,
    val fileBytes: Long = 1024L * 1024L,
    val depth: Int = 2,
)

/**
 * One file of a diff (m2-28): its [path] and the lines [added] and [deleted]. [counted] is false when git printed no
 * count for it, `-`, so its lines are unknown and an auto merge cannot hold it under the size limit.
 */
data class DiffEntry(
    val path: String,
    val added: Int,
    val deleted: Int,
    val counted: Boolean = true,
)
