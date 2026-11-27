package loop.core.verify

import loop.core.workflow.CheckConfig
import loop.core.workflow.TestLanguage
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * The core check types that run in process (m2-12, plan 6.4). Each gives a [CheckResult] like a `command`
 * check: exit code 0 when it passes, 1 when it fails, and an output that names what it found, so its
 * evidence and its findings work unchanged. A check that cannot look at all, such as `diff_policy` outside
 * a git work tree, sets [CheckResult.startError]: it could not run, so the verdict is `unverified`.
 */
object CoreChecks {
    /** `file_matches` and the deny guards read at most this many bytes of a file; a larger file cannot be checked. */
    const val FILE_MAX: Long = 16L * 1024 * 1024

    /** Each git command of `diff_policy` is stopped after this long. */
    const val GIT_TIMEOUT_MS: Long = 120_000L

    /** Passes when the path is a file or a folder inside the workspace. */
    fun fileExists(
        check: CheckConfig.FileExists,
        workspace: Path,
    ): CheckResult =
        timed {
            when (val target = locate(workspace, check.path)) {
                Located.Missing -> failed("${check.path} does not exist")
                Located.Outside -> failed("${check.path} resolves outside the workspace")
                is Located.Found ->
                    passed(if (Files.isDirectory(target.path)) "${check.path} exists, a folder" else "${check.path} exists")
            }
        }

    /** Passes when the pattern is found in the file, `^` and `$` matching at each line. */
    fun fileMatches(
        check: CheckConfig.FileMatches,
        workspace: Path,
    ): CheckResult =
        timed {
            when (val target = locate(workspace, check.path)) {
                Located.Missing -> failed("${check.path} does not exist")
                Located.Outside -> failed("${check.path} resolves outside the workspace")
                is Located.Found -> matches(check, target.path)
            }
        }

    private fun matches(
        check: CheckConfig.FileMatches,
        file: Path,
    ): Outcome {
        if (Files.isDirectory(file)) return failed("${check.path} is a folder, not a file")
        if (!Files.isRegularFile(file)) return failed("${check.path} is not a regular file")
        val text =
            try {
                if (Files.size(file) > FILE_MAX) return couldNotRun("${check.path} is over 16 MiB, too large to check")
                String(Files.readAllBytes(file), Charsets.UTF_8)
            } catch (e: IOException) {
                return couldNotRun("cannot read ${check.path}: ${e.message ?: e::class.simpleName}")
            }
        val match =
            Regex(check.pattern, RegexOption.MULTILINE).find(text)
                ?: return failed("${check.path} has no match for /${check.pattern}/")
        val line = 1 + text.substring(0, match.range.first).count { it == '\n' }
        return passed("${check.path} matches /${check.pattern}/ at line $line")
    }

    /**
     * Passes when the workspace's diff against its merge base with the check's base touches no protected path (m2-12)
     * and breaks no deny guard (m2-13). The diff is the working tree against the merge base, so committed, staged and
     * unstaged changes all count, plus the untracked files git does not ignore, as added, and HEAD against the merge
     * base, so a committed change counts even when the working tree was put back. A rename counts on both of its
     * paths. git runs with [env] minus every `GIT_` variable but `GIT_CONFIG_NOSYSTEM`, so a variable cannot point it
     * at another repository, and without replace refs or grafts, so no object stands in for another.
     *
     * What git would hide (m2-13): an untracked protected file is found even when an ignore rule hides it, a nested
     * repository protects as every file under it (an ignored one as its folder), and a path flagged assume-unchanged,
     * or skip-worktree while present in the working tree, means the check could not run, since git cannot see the
     * edits. So does a protected or test path that skip-worktree keeps out of the working tree, a git filter on a
     * tracked protected or test path, from any config or attributes file, and such a path that git reads as unchanged
     * while its raw content differs from the merge base, a stat trick for example.
     */
    fun diffPolicy(
        check: CheckConfig.DiffPolicy,
        workspace: Path,
        env: Map<String, String>,
    ): CheckResult =
        timed {
            val git = Git(workspace.toRealPath(), env)
            val mergeBase = git.mergeBase(check.base).getOrElse { return@timed couldNotRun(it.message!!) }
            val flags = git.run("ls-files", "-v", "-z")
            if (flags.code != 0) return@timed couldNotRun("git ls-files exited with code ${flags.code}")
            val (unseen, sparse) = flagged(git.root, flags.out)
            if (unseen.isNotEmpty()) {
                val paths = if (unseen.size == 1) "1 path" else "${unseen.size} paths"
                return@timed couldNotRun("git cannot see the edits of $paths marked assume-unchanged or skip-worktree: ${shown(unseen)}")
            }
            val globs = watchedGlobs(check)
            val sparseWatched = sparse.filter { globs.match(it) != null }
            if (sparseWatched.isNotEmpty()) {
                val paths = if (sparseWatched.size == 1) "1 protected or test path" else "${sparseWatched.size} protected or test paths"
                val them = if (sparseWatched.size == 1) "it" else "them"
                val why = "skip-worktree keeps $paths out of the working tree, so git cannot see $them changed or deleted"
                return@timed couldNotRun("$why: ${shown(sparseWatched)}")
            }
            val index = git.run("ls-files", "-s", "-z")
            if (index.code != 0) return@timed couldNotRun("git ls-files exited with code ${index.code}")
            val watched = watchedPaths(globs, index.out)
            val filtered = filteredPaths(git, watched)
            if (filtered.isNotEmpty()) {
                val paths = if (filtered.size == 1) "1 protected or test path" else "${filtered.size} protected or test paths"
                val named = filtered.map { (path, filter) -> "$path (filter=$filter)" }
                return@timed couldNotRun("a git filter can hide the edits of $paths: ${shown(named)}")
            }
            val diff = git.run("diff", "--name-status", "-z", "-M", "--no-ext-diff", "--no-relative", mergeBase, "--")
            if (diff.code != 0) return@timed couldNotRun("git diff exited with code ${diff.code}")
            val head = git.run("diff", "--name-status", "-z", "-M", "--no-ext-diff", "--no-relative", mergeBase, "HEAD", "--")
            if (head.code != 0) return@timed couldNotRun("git diff exited with code ${head.code}")
            val untracked = git.run("ls-files", "--others", "--exclude-standard", "-z")
            if (untracked.code != 0) return@timed couldNotRun("git ls-files exited with code ${untracked.code}")
            val visible = nulSeparated(untracked.out)
            val ignored =
                if (check.protect.isEmpty()) {
                    emptyList()
                } else {
                    val everyUntracked = git.run("ls-files", "--others", "-z")
                    if (everyUntracked.code != 0) return@timed couldNotRun("git ls-files exited with code ${everyUntracked.code}")
                    nulSeparated(everyUntracked.out) - visible.toSet()
                }
            val changes = parseNameStatus(diff.out) + visible.map(::untrackedChange)
            val headChanges = parseNameStatus(head.out)
            val headOnly = headChanges.filter { it !in changes }.map { it.copy(inHead = true) }
            val hidden = hiddenEdits(git, mergeBase, watched, changes)
            if (hidden.isNotEmpty()) {
                val paths = if (hidden.size == 1) "1 protected or test path" else "${hidden.size} protected or test paths"
                val its = if (hidden.size == 1) "its" else "their"
                return@timed couldNotRun("git reads $paths as unchanged, but $its content differs from the merge base: ${shown(hidden)}")
            }
            val protectedPaths = ProtectedPaths(check.protect, ignoreCase = true)
            val touched =
                (changes + headOnly + ignored.map(::ignoredChange))
                    .mapNotNull { change -> protectedMatch(git.root, protectedPaths, change)?.let { change to it } }
                    .sortedBy { it.first.path }
            val guarded = check.deny.isNotEmpty()
            val tests = if (guarded) testFiles(git, mergeBase, changes, check.languages) { git.root.readText(it) } else emptyList()
            val headTests = if (guarded) testFiles(git, mergeBase, headChanges, check.languages) { git.blob("HEAD:$it") } else emptyList()
            val findings = guardFindings(check, tests, headTests)
            val against = "The diff against the merge base with ${check.base} (${mergeBase.take(SHORT_SHA)})"
            val failures = mutableListOf<String>()
            if (touched.isNotEmpty()) {
                val count = if (touched.size == 1) "1 protected path" else "${touched.size} protected paths"
                failures +=
                    "$against touches $count:\n" +
                    touched.joinToString("\n") { (change, glob) -> "${change.describe()} (protected by $glob)" }
            }
            if (findings.isNotEmpty()) {
                val guards = check.deny.filter { guard -> findings.any { it.guard == guard } }.map { it.key }
                val count = if (guards.size == 1) "1 deny guard" else "${guards.size} deny guards"
                failures += "$against breaks $count, ${andList(guards)}:\n" + findings.joinToString("\n")
            }
            if (failures.isNotEmpty()) return@timed failed(failures.joinToString("\n"))
            val passes = mutableListOf<String>()
            if (check.protect.isNotEmpty()) {
                val checked = changes.size + headOnly.size
                val count = if (checked == 1) "1 changed path" else "$checked changed paths"
                passes += "$against touches no protected path: $count checked against ${check.protect.joinToString(", ")}."
            }
            if (check.deny.isNotEmpty()) {
                val read = (tests + headTests).map { it.newPath ?: it.oldPath }.distinct().size
                val count = if (read == 1) "1 changed test file" else "$read changed test files"
                passes += "$against breaks no deny guard (${check.deny.joinToString(", ") { it.key }}): $count read."
            }
            passed(passes.joinToString("\n"))
        }

    /**
     * Passes when the lines the workspace's diff against its merge base with the check's base adds or changes are
     * covered at least `min_percent` percent by the JaCoCo XML reports the check's globs find (bl-117). The diff is the
     * working tree against the merge base, so committed, staged and unstaged changes all count, and every line of an
     * untracked file git does not ignore. Only the lines a report lists with instructions count. It runs after the
     * checks before it, so a `command` check that runs the tests writes the reports. A glob that finds no report, a
     * report that cannot be parsed or the git work tree it cannot read means it could not run, never a pass.
     */
    fun changedLineCoverage(
        check: CheckConfig.ChangedLineCoverage,
        workspace: Path,
        env: Map<String, String>,
    ): CheckResult =
        timed {
            val git = Git(workspace.toRealPath(), env)
            val mergeBase = git.mergeBase(check.base).getOrElse { return@timed couldNotRun(it.message!!) }
            val found = ChangedLineCoverage.find(git.root, check.reports)
            found.missing?.let { return@timed couldNotRun("no coverage report matches $it") }
            val coverage =
                try {
                    ChangedLineCoverage.read(git.root, found.files)
                } catch (e: ChangedLineCoverage.Unreadable) {
                    return@timed couldNotRun(e.message!!)
                }
            val diff =
                git.run(
                    "diff",
                    "-U0",
                    "-M",
                    "--text",
                    "--no-color",
                    "--no-ext-diff",
                    "--no-textconv",
                    "--no-relative",
                    "--src-prefix=a/",
                    "--dst-prefix=b/",
                    mergeBase,
                    "--",
                )
            if (diff.code != 0) return@timed couldNotRun("git diff exited with code ${diff.code}")
            val untracked = git.run("ls-files", "--others", "--exclude-standard", "-z")
            if (untracked.code != 0) return@timed couldNotRun("git ls-files exited with code ${untracked.code}")
            val changed =
                ChangedLineCoverage.changedLines(diff.out) +
                    nulSeparated(
                        untracked.out,
                    ).filter { !it.endsWith("/") }.map { ChangedLineCoverage.ChangedFile(it, emptySet(), whole = true) }
            val against = "Changed line coverage against the merge base with ${check.base} (${mergeBase.take(SHORT_SHA)})"
            val verdict =
                ChangedLineCoverage.judge(changed, coverage, found.files.size, check.minPercent, against) {
                    ChangedLineCoverage.declaredPackage(git.root, it)
                }
            if (verdict.passed) passed(verdict.output) else failed(verdict.output)
        }

    /**
     * The merge base of [base] and HEAD, or why there is none: the workspace is not the top of a git work tree, or git
     * finds no merge base.
     */
    private fun Git.mergeBase(base: String): Result<String> {
        val top = run("rev-parse", "--show-toplevel")
        val isTop = top.code == 0 && runCatching { Path.of(top.out.trim()).toRealPath() == root }.getOrDefault(false)
        if (!isTop) return Result.failure(IOException("the workspace is not the top of a git work tree"))
        val found = run("merge-base", base, "HEAD")
        if (found.code != 0) {
            val why =
                found.err
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
            return Result.failure(IOException("no merge base with $base and HEAD" + (why?.let { ": $it" } ?: "")))
        }
        return Result.success(found.out.trim())
    }

    /** How many paths a could not run reason names before it counts the rest. */
    private const val SHOWN = 5

    private fun shown(paths: List<String>): String =
        paths.take(SHOWN).joinToString(", ") + if (paths.size > SHOWN) " and ${paths.size - SHOWN} more" else ""

    private fun andList(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /**
     * The paths of `git ls-files -v -z` output whose edits git cannot see, an assume-unchanged path (a lower case tag)
     * or a skip-worktree one (`S`) present in the working tree, and the skip-worktree paths missing from it, which a
     * sparse checkout leaves out.
     */
    private fun flagged(
        root: Path,
        output: String,
    ): Pair<List<String>, List<String>> {
        val unseen = mutableListOf<String>()
        val sparse = mutableListOf<String>()
        for (entry in nulSeparated(output)) {
            if (entry.length < 3) continue
            val tag = entry[0]
            val path = entry.substring(2)
            when {
                tag.isLowerCase() -> unseen += path
                tag == 'S' -> if (Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS)) unseen += path else sparse += path
            }
        }
        return unseen to sparse
    }

    /** An untracked path git lists: a file, or a nested repository, which git lists as its folder with a trailing `/`. */
    private fun untrackedChange(path: String): PathChange = if (path.endsWith("/")) PathChange(NESTED, path) else PathChange("added", path)

    /** An ignored untracked path: a file, or a nested repository, matched by its folder only (see [protectedMatch]). */
    private fun ignoredChange(path: String): PathChange =
        if (path.endsWith("/")) PathChange(IGNORED_NESTED, path) else PathChange("added and ignored", path)

    private const val NESTED = "added nested repository"

    private const val IGNORED_NESTED = "added and ignored nested repository"

    /**
     * The paths git must not hide from the check: those `protect` matches, and with a deny guard on, the languages' test
     * files. Case folded, like `protect` itself, so a case variant of a protected path is watched too.
     */
    private fun watchedGlobs(check: CheckConfig.DiffPolicy): ProtectedPaths =
        ProtectedPaths(check.protect + if (check.deny.isEmpty()) emptyList() else check.languages.flatMap { it.files }, ignoreCase = true)

    /**
     * The tracked paths, from `git ls-files -s -z` output, that a git filter or a stale index could hide from the
     * check, the ones [globs] match. A submodule is left out, since git diffs it by commit, not by content.
     */
    private fun watchedPaths(
        globs: ProtectedPaths,
        output: String,
    ): List<String> =
        nulSeparated(output)
            .filter { !it.startsWith("$GITLINK ") }
            .map { it.substringAfter('\t') }
            .distinct()
            .filter { globs.match(it) != null }

    /**
     * The deny guard findings of the working tree's [tests] and of HEAD's [headTests], each against the merge base,
     * within one budget. A finding only HEAD has, a test deleted in a commit and put back in the working tree, is
     * marked `(in HEAD)`. The HEAD pass is skipped when HEAD's test files are the working tree's.
     */
    private fun guardFindings(
        check: CheckConfig.DiffPolicy,
        tests: List<TestFile>,
        headTests: List<TestFile>,
    ): List<GuardFinding> {
        val start = System.nanoTime()
        val findings = DenyGuards.find(check.deny, check.languages, tests, startNanos = start)
        if (headTests == tests) return findings
        val headOnly =
            DenyGuards
                .find(check.deny, check.languages, headTests, startNanos = start)
                .filter { it !in findings }
                .map { it.copy(message = it.message + " (in HEAD)") }
        return (findings + headOnly).sortedBy { check.deny.indexOf(it.guard) }
    }

    private const val GITLINK = "160000"

    /**
     * The [paths] that a git filter applies to, with its name, from `git check-attr filter`. The attribute can come
     * from any attributes file, the user's too, and the filter from any config: the agent runs as the same user, so
     * neither is out of its reach. A filter on any other path, `git lfs` on images for example, is left alone.
     */
    private fun filteredPaths(
        git: Git,
        paths: List<String>,
    ): List<Pair<String, String>> {
        if (paths.isEmpty()) return emptyList()
        val attributes = git.run("check-attr", "-z", "--stdin", "filter", input = paths.joinToString("\u0000", postfix = "\u0000"))
        if (attributes.code != 0) throw IOException("git check-attr exited with code ${attributes.code}")
        return attributes.out
            .split('\u0000')
            .dropLast(1)
            .chunked(ATTRIBUTE_FIELDS)
            .filter { it.size == ATTRIBUTE_FIELDS && it[2] != "unspecified" && it[2] != "unset" }
            .map { it[0] to it[2] }
    }

    /** `git check-attr -z` gives a path, the attribute and its value for each path. */
    private const val ATTRIBUTE_FIELDS = 3

    /**
     * The [watched] paths that the diff leaves out although their raw content, hashed like `git hash-object
     * --no-filters`, is not the merge base's blob: git trusted a stale index entry, such as a same size edit with its
     * mtime restored under `core.trustctime=false`, or something else made the edit invisible. Line ends alone explain
     * a difference: a file whose CRLF line ends, turned into LF, give the merge base's blob is unchanged.
     */
    private fun hiddenEdits(
        git: Git,
        mergeBase: String,
        watched: List<String>,
        changes: List<PathChange>,
    ): List<String> {
        val inDiff = changes.flatMap { listOfNotNull(it.path, it.from) }.toSet()
        val unchanged = watched.filter { it !in inDiff }
        if (unchanged.isEmpty()) return emptyList()
        val tree = git.run("ls-tree", "-r", "-z", "--full-tree", mergeBase)
        if (tree.code != 0) throw IOException("git ls-tree exited with code ${tree.code}")
        val blobs =
            nulSeparated(tree.out).associate { entry ->
                entry.substringAfter('\t') to entry.substringBefore('\t').split(' ').last()
            }
        val algorithm = if (mergeBase.length == SHA256_HEX) "SHA-256" else "SHA-1"
        return unchanged.filter { path -> !sameContent(git.root.resolve(path), blobs[path], algorithm) }
    }

    private const val SHA256_HEX = 64

    private fun sameContent(
        file: Path,
        blob: String?,
        algorithm: String,
    ): Boolean {
        val raw = blobId(file, algorithm)
        if (raw == blob) return true
        if (raw == null || blob == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.size(file) > FILE_MAX) return false
        val bytes = Files.readAllBytes(file)
        if (CR !in bytes) return false
        return blobId(String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1), algorithm) == blob
    }

    private const val CR = '\r'.code.toByte()

    /** The blob id git would give the file's raw content, a symlink's target like git stores it, null when it is missing. */
    private fun blobId(
        file: Path,
        algorithm: String,
    ): String? {
        val digest = MessageDigest.getInstance(algorithm)
        when {
            Files.isSymbolicLink(file) -> return blobId(Files.readSymbolicLink(file).toString().toByteArray(Charsets.UTF_8), algorithm)
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> return null
            else -> {
                digest.update("blob ${Files.size(file)}\u0000".toByteArray(Charsets.US_ASCII))
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(HASH_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun blobId(
        content: ByteArray,
        algorithm: String,
    ): String {
        val digest = MessageDigest.getInstance(algorithm)
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
        return HexFormat.of().formatHex(digest.digest(content))
    }

    private const val HASH_BUFFER = 64 * 1024

    /**
     * The glob that protects [change]: one of its paths for a file, and for a nested repository its folder or any
     * file under it, since git shows none of them. An ignored nested repository, such as a SwiftPM checkout under
     * `.build/`, is matched by its folder only, never walked.
     */
    private fun protectedMatch(
        root: Path,
        globs: ProtectedPaths,
        change: PathChange,
    ): String? {
        if (change.kind == IGNORED_NESTED) return globs.match(change.path.removeSuffix("/"))
        if (change.kind != NESTED) return globs.match(change)
        globs.match(change.path.removeSuffix("/"))?.let { return it }
        val folder = root.resolve(change.path)
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return null
        Files.walk(folder).use { paths ->
            return paths
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
                .map { root.relativize(it).joinToString("/") }
                .filter { path -> "/.git/" !in "/$path" }
                .map { globs.match(it) }
                .filter { it != null }
                .findFirst()
                .orElse(null)
        }
    }

    /**
     * The changed files of the [languages]' test files, each with its text at the merge base and its new text from
     * [newText], the working tree or HEAD, read raw, without filters.
     */
    private fun testFiles(
        git: Git,
        mergeBase: String,
        changes: List<PathChange>,
        languages: List<TestLanguage>,
        newText: (String) -> String?,
    ): List<TestFile> {
        val globs = ProtectedPaths(languages.flatMap { it.files })
        val isTest = { path: String? -> path != null && globs.match(path) != null }
        return changes
            .filter { it.kind != NESTED && (isTest(it.path) || isTest(it.from)) }
            .map { change ->
                when (change.kind) {
                    "added" -> TestFile(newPath = change.path, newText = newText(change.path))
                    "deleted" -> TestFile(oldPath = change.path, oldText = git.blob("$mergeBase:${change.path}"))
                    else -> {
                        val old = change.from ?: change.path
                        TestFile(old, git.blob("$mergeBase:$old"), change.path, newText(change.path))
                    }
                }
            }
    }

    /** The text of the file at [path] in this folder, a symlink's target like git stores it, null when it is missing. */
    private fun Path.readText(path: String): String? {
        val file = resolve(path)
        return when {
            Files.isSymbolicLink(file) -> Files.readSymbolicLink(file).toString()
            !Files.isRegularFile(file) -> null
            Files.size(file) > FILE_MAX -> throw IOException("$path is over 16 MiB, too large to check")
            else -> String(Files.readAllBytes(file), Charsets.UTF_8)
        }
    }

    /** The changes in `git diff --name-status -z` output: a status, then one path, or two for a rename or copy. */
    private fun parseNameStatus(output: String): List<PathChange> {
        val tokens = nulSeparated(output)
        val changes = mutableListOf<PathChange>()
        var i = 0
        while (i < tokens.size) {
            val status = tokens[i]
            when (status.firstOrNull()) {
                'R' -> {
                    changes += PathChange("renamed", tokens[i + 2], from = tokens[i + 1])
                    i += 3
                }
                // A copy leaves its source as it was: only the new path is a change.
                'C' -> {
                    changes += PathChange("added", tokens[i + 2])
                    i += 3
                }
                'A' -> {
                    changes += PathChange("added", tokens[i + 1])
                    i += 2
                }
                'D' -> {
                    changes += PathChange("deleted", tokens[i + 1])
                    i += 2
                }
                else -> {
                    changes += PathChange("modified", tokens[i + 1])
                    i += 2
                }
            }
        }
        return changes
    }

    private fun nulSeparated(output: String): List<String> = output.split('\u0000').filter { it.isNotEmpty() }

    /** Where a check's path leads: nowhere, outside the workspace through a symlink, or to a real path inside it. */
    private sealed interface Located {
        data object Missing : Located

        data object Outside : Located

        data class Found(
            val path: Path,
        ) : Located
    }

    private fun locate(
        workspace: Path,
        path: String,
    ): Located {
        val root = workspace.toRealPath()
        val candidate = root.resolve(path)
        if (!Files.exists(candidate)) return Located.Missing
        val real = candidate.toRealPath()
        return if (real.startsWith(root)) Located.Found(real) else Located.Outside
    }

    /** What a check found: [code] 0 or 1, or a [startError] when it could not run. */
    private class Outcome(
        val code: Int?,
        val output: String,
        val startError: String? = null,
    )

    private fun passed(output: String) = Outcome(0, output + "\n")

    private fun failed(output: String) = Outcome(1, output + "\n")

    private fun couldNotRun(reason: String) = Outcome(null, "", startError = reason)

    /**
     * Runs [block] and times it. Whatever it throws, an error like a StackOverflowError from a configured pattern
     * too, means the check could not run, never a failed drain.
     */
    private inline fun timed(block: () -> Outcome): CheckResult {
        val start = System.nanoTime()
        val outcome =
            try {
                block()
            } catch (e: Throwable) {
                couldNotRun(e.message ?: e::class.simpleName ?: "unknown error")
            }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return CheckResult(
            exitCode = outcome.code,
            timedOut = false,
            durationMs = durationMs,
            startError = outcome.startError,
            output = outcome.output,
        )
    }

    private const val SHORT_SHA = 12

    /** An empty grafts file that nobody can write to. */
    private const val NO_GRAFTS = "/dev/null"

    /**
     * The environment of the checks' own git: [env] minus every `GIT_` variable but `GIT_CONFIG_NOSYSTEM`, never taking
     * the index lock, with replace refs and grafts off, and with lazy fetch off ([WorkspaceGit.NO_LAZY_FETCH], bl-246).
     */
    internal fun gitEnvironment(env: Map<String, String>): Map<String, String> =
        env.filterKeys { !it.startsWith("GIT_") || it == "GIT_CONFIG_NOSYSTEM" } +
            mapOf(
                "GIT_OPTIONAL_LOCKS" to "0",
                "GIT_NO_REPLACE_OBJECTS" to "1",
                "GIT_GRAFT_FILE" to NO_GRAFTS,
                WorkspaceGit.NO_LAZY_FETCH to "1",
            )

    /**
     * git in [root] with the [gitEnvironment] of [env], with [WorkspaceGit.PINS] (no hook, no fsmonitor, no signing),
     * and with replace refs, grafts, the commit graph and lazy fetch off: `refs/replace/`, `info/grafts` or
     * `objects/info/commit-graph`, written by hand too, could swap the merge base's tree or move the merge base itself.
     */
    private class Git(
        val root: Path,
        private val env: Map<String, String>,
    ) {
        class Run(
            val code: Int,
            val out: String,
            val err: String,
        )

        fun run(
            vararg args: String,
            input: String? = null,
        ): Run {
            // bl-28: the same pins as every other git Loop runs in a workspace, whose config the agent may write.
            val command = listOf("git") + WorkspaceGit.PINS + listOf("-C", root.toString(), "--no-replace-objects") + args
            val builder = ProcessBuilder(command)
            val processEnv = builder.environment()
            processEnv.clear()
            processEnv.putAll(gitEnvironment(env))
            val process = builder.start()
            val written =
                CompletableFuture.runAsync {
                    process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
                }
            val out = CompletableFuture.supplyAsync { process.inputStream.use(::readAll) }
            val err = CompletableFuture.supplyAsync { process.errorStream.use(::readAll) }
            if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw IOException("git ${args.first()} did not finish in ${GIT_TIMEOUT_MS / 1000} s")
            }
            runCatching { written.get() }
            return Run(process.exitValue(), out.get(), err.get())
        }

        /** The raw content of the blob [spec], such as `<commit>:<path>` or `:<path>` for the index, null when there is none. */
        fun blob(spec: String): String? {
            val result = run("cat-file", "blob", spec)
            return if (result.code == 0) result.out else null
        }

        private fun readAll(input: InputStream): String = String(input.readAllBytes(), Charsets.UTF_8)
    }
}

/**
 * One path of a diff: its [kind] (`added`, `modified`, `deleted` or `renamed`), the path a rename came [from], and
 * [inHead] when only HEAD has the change, not the working tree.
 */
internal data class PathChange(
    val kind: String,
    val path: String,
    val from: String? = null,
    val inHead: Boolean = false,
) {
    fun describe(): String = (if (from != null) "$kind $from to $path" else "$kind $path") + if (inHead) " in HEAD" else ""
}

/**
 * The protected path globs of `guards.protect` (m2-12), each anchored at the workspace root: `*` and `?` match
 * within one path segment, `**` any number of segments, and a glob that names a folder, with or without a
 * trailing `/`, covers everything under it. With [ignoreCase], the glob and the path are compared with their case
 * folded, Unicode included: a case insensitive checkout, the macOS default, writes `workflow.md` over `WORKFLOW.md`
 * (round 2 of the PR #75 review). Without [coversFolders], a glob matches only the paths it names, as the report
 * globs of `changed_line_coverage` need (bl-117).
 */
class ProtectedPaths(
    globs: List<String>,
    ignoreCase: Boolean = false,
    coversFolders: Boolean = true,
) {
    private val patterns = globs.map { it to regexOf(it, ignoreCase, coversFolders) }

    /** The first glob that [path] matches, null when it is not protected. */
    fun match(path: String): String? = patterns.firstOrNull { (_, regex) -> regex.matches(path) }?.first

    internal fun match(change: PathChange): String? = match(change.path) ?: change.from?.let { match(it) }

    private companion object {
        fun regexOf(
            glob: String,
            ignoreCase: Boolean,
            coversFolders: Boolean,
        ): Regex {
            val text = glob.trim().removePrefix("/").removeSuffix("/")
            val regex = StringBuilder()
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("**/", i) -> {
                        regex.append("(?:.*/)?")
                        i += 3
                    }
                    text.startsWith("**", i) -> {
                        regex.append(".*")
                        i += 2
                    }
                    text[i] == '*' -> {
                        regex.append("[^/]*")
                        i++
                    }
                    text[i] == '?' -> {
                        regex.append("[^/]")
                        i++
                    }
                    else -> {
                        regex.append(Regex.escape(text[i].toString()))
                        i++
                    }
                }
            }
            if (coversFolders) regex.append("(?:/.*)?")
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
            return Pattern.compile(regex.toString(), flags).toRegex()
        }
    }
}
