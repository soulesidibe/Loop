package loop.core.verify

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * m2-26 (part of bl-55): every git command Loop runs in a workspace for the merge check, the tree restore and the
 * fingerprint gets `core.hooksPath=/dev/null`, `core.fsmonitor=false`, the minimal environment, no token and a
 * timeout. A hook or an fsmonitor planted in the shared `.git/config` never runs.
 */
class WorkspaceGitTest {
    @Test
    fun `a timeout takes precedence over closed stream failures`() {
        val process = ClosedStreamProcess()
        val git = WorkspaceGit(timeout = 1.seconds, startProcess = { process })

        val error = assertFailsWith<IOException> { git.run(repo, "status") }

        assertTrue("did not finish within" in error.message!!, error.message)
        assertEquals(2, process.failures.get())
    }

    private val folder: Path = createTempDirectory("loop-workspace-git").toRealPath()
    private val home: Path = Files.createDirectories(folder.resolve("home"))
    private val repo: Path = Files.createDirectories(folder.resolve("repo"))
    private val marker: Path = folder.resolve("hook-ran")

    /** A host env with a token, an Anthropic key and a `GIT_` variable, none of which may reach git. */
    private val hostEnv =
        mapOf(
            "PATH" to System.getenv("PATH").orEmpty(),
            "HOME" to home.toString(),
            "LANG" to "C",
            "LOOP_PR_TOKEN" to "ghp_secretToken0123456789abcdefghijklmn",
            "ANTHROPIC_API_KEY" to "sk-ant-secret",
            "GIT_DIR" to folder.resolve("elsewhere").toString(),
        )

    private val workspaceGit = WorkspaceGit(hostEnv)

    private fun git(
        vararg args: String,
        dir: Path = repo,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + TEST_PINS + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    private fun commit(
        file: String,
        text: String,
        message: String,
    ): String {
        repo.resolve(file).writeText(text)
        git("add", file)
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    /** A repository with a main branch and a work branch that both moved since their fork. */
    private fun forked(conflict: Boolean = false): Pair<String, String> {
        git("init", "-q", "-b", "main")
        commit("a.txt", "one\n", "first")
        git("checkout", "-q", "-b", "loop/t-1")
        val work = commit("b.txt", "work\n", "work")
        git("checkout", "-q", "main")
        val base = if (conflict) commit("b.txt", "other\n", "base") else commit("c.txt", "base\n", "base")
        git("checkout", "-q", "loop/t-1")
        return work to base
    }

    /** Plants hooks and an fsmonitor in the shared config, each writing [marker] when it runs. */
    private fun plantHooks() {
        val hooks = Files.createDirectories(folder.resolve("hooks"))
        val script = "#!/bin/sh\necho \"\$0\" >> '$marker'\nexit 0\n"
        listOf(
            "pre-merge-commit",
            "prepare-commit-msg",
            "commit-msg",
            "post-merge",
            "post-checkout",
            "reference-transaction",
            "post-commit",
        ).forEach { name ->
            val hook = hooks.resolve(name)
            hook.writeText(script)
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val monitor = folder.resolve("fsmonitor.sh")
        monitor.writeText(script)
        Files.setPosixFilePermissions(monitor, PosixFilePermissions.fromString("rwxr-xr-x"))
        git("config", "core.hooksPath", hooks.toString())
        git("config", "core.fsmonitor", monitor.toString())
    }

    /** A `git` wrapper that logs its arguments and environment, then runs the real git. */
    private fun loggingGit(sleep: Int = 0): Pair<Path, Path> {
        val log = folder.resolve("git.log")
        val wrapper = folder.resolve("git-wrapper.sh")
        val real =
            ProcessBuilder("sh", "-c", "command -v git")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        wrapper.writeText(
            "#!/bin/sh\n{ echo \"ARGS \$*\"; env | sed 's/^/ENV /'; } >> '$log'\n" +
                (if (sleep > 0) "sleep $sleep\n" else "") +
                "exec '$real' \"\$@\"\n",
        )
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"))
        return wrapper to log
    }

    @Test
    fun `every command carries the pins and only the minimal environment`() {
        forked()
        val (wrapper, log) = loggingGit()

        WorkspaceGit(hostEnv, git = wrapper.toString()).head(repo)
        WorkspaceFingerprinter(WorkspaceGit(hostEnv, git = wrapper.toString())).of(repo).getOrThrow()
        WorkspaceFingerprinter(WorkspaceGit(hostEnv, git = wrapper.toString())).restore(repo).getOrThrow()

        val lines = log.readText().lines()
        val args = lines.filter { it.startsWith("ARGS ") }
        assertTrue(args.size >= 4, "git ran ${args.size} times")
        args.forEach { line ->
            assertTrue(line.startsWith("ARGS -c core.hooksPath=/dev/null -c core.fsmonitor=false -c commit.gpgsign=false "), line)
            // bl-32: merge base and ancestor answers never come from a commit graph file an agent can edit.
            assertTrue(line.contains(" -c core.commitGraph=false "), line)
            assertTrue(line.contains(" -C "), line)
        }
        val env = lines.filter { it.startsWith("ENV ") }.map { it.removePrefix("ENV ").substringBefore('=') }.toSet()
        assertFalse("LOOP_PR_TOKEN" in env)
        assertFalse("ANTHROPIC_API_KEY" in env)
        assertFalse("GIT_DIR" in env)
        assertTrue("GIT_TERMINAL_PROMPT" in env)
        assertFalse(log.readText().contains("ghp_secretToken"))
    }

    @Test
    fun `a git that does not finish in time is stopped`() {
        forked()
        val (wrapper, _) = loggingGit(sleep = 30)
        val slow = WorkspaceGit(hostEnv, git = wrapper.toString(), timeout = 1.seconds)

        val started = System.nanoTime()
        val error = assertFailsWith<IOException> { slow.head(repo) }

        assertTrue(error.message!!.contains("did not finish within"), error.message)
        assertTrue((System.nanoTime() - started) / 1_000_000_000 < 20, "the timeout stopped git")
    }

    @Test
    fun `the diff against a base lists each file of the branch's own changes with its lines, the base's left out (m2-28)`() {
        val (_, base) = forked()
        repo.resolve("b.txt").writeText("work\nmore\nlines\n")
        repo.resolve("bin.dat").toFile().writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        git("add", "b.txt", "bin.dat")
        git("commit", "-q", "-m", "more")
        git("merge", "-q", "--no-edit", base)
        val head = git("rev-parse", "HEAD")

        assertEquals(
            listOf(DiffEntry("b.txt", 3, 0), DiffEntry("bin.dat", 1, 0)),
            workspaceGit.diffStat(repo, base, head).sortedBy { it.path },
        )
        val text = workspaceGit.diffText(repo, base, head, maxChars = 10_000)
        assertTrue("+more" in text, text)
        assertFalse("c.txt" in text, text)
        assertEquals(20, workspaceGit.diffText(repo, base, head, maxChars = 20).length)
    }

    /** A branch whose last commit adds a 3 line Kotlin file, the base of its fork and the head. */
    private fun kotlinChange(): Pair<String, String> {
        val (_, base) = forked()
        val head = commit("App.kt", "fun a() = 1\nfun b() = 2\nfun c() = 3\n", "kotlin")
        return base to head
    }

    @Test
    fun `a committed gitattributes marking a file -diff hides none of its lines (send back 2 of PR 75)`() {
        val (_, base) = forked()
        repo.resolve(".gitattributes").writeText("*.kt -diff\n")
        git("add", ".gitattributes")
        git("commit", "-q", "-m", "attributes")
        val head = commit("App.kt", "fun a() = 1\nfun b() = 2\nfun c() = 3\n", "kotlin")

        assertEquals(
            listOf(DiffEntry(".gitattributes", 1, 0), DiffEntry("App.kt", 3, 0), DiffEntry("b.txt", 1, 0)),
            workspaceGit.diffStat(repo, base, head).sortedBy { it.path },
        )
        val text = workspaceGit.diffText(repo, base, head, maxChars = 10_000)
        assertTrue("+fun c() = 3" in text, text)
        assertFalse("Binary files" in text, text)
    }

    @Test
    fun `a -diff line in the info attributes of the shared git folder hides none of the lines (send back 2 of PR 75)`() {
        val (base, head) = kotlinChange()
        Files.createDirectories(repo.resolve(".git/info"))
        repo.resolve(".git/info/attributes").writeText("*.kt -diff\n* binary\n")
        val globalAttributes = folder.resolve("attributes").also { it.writeText("*.kt -diff\n") }
        git("config", "core.attributesFile", globalAttributes.toString())

        assertEquals(
            listOf(DiffEntry("App.kt", 3, 0), DiffEntry("b.txt", 1, 0)),
            workspaceGit.diffStat(repo, base, head).sortedBy { it.path },
        )
        val text = workspaceGit.diffText(repo, base, head, maxChars = 10_000)
        assertTrue("+fun c() = 3" in text, text)
        assertFalse("Binary files" in text, text)
    }

    @Test
    fun `bl-136 the work tree fingerprint follows tracked and untracked files and ignores what git ignores`() {
        git("init", "-q", "-b", "main")
        repo.resolve(".gitignore").writeText(".scratch/\n")
        git("add", ".gitignore")
        commit("a.txt", "one\n", "first")
        val clean = workspaceGit.workTree(repo)
        assertEquals(clean, workspaceGit.workTree(repo), "the same tree reads the same twice")

        // A file of the gitignored .scratch folder changes nothing.
        Files.createDirectories(repo.resolve(".scratch/t-1")).resolve("progress.md").writeText("notes\n")
        assertEquals(clean, workspaceGit.workTree(repo))

        // An edit of a tracked file changes it, and a second edit of the same file changes it again.
        repo.resolve("a.txt").writeText("two\n")
        val edited = workspaceGit.workTree(repo)
        assertTrue(edited != clean)
        repo.resolve("a.txt").writeText("three\n")
        val editedAgain = workspaceGit.workTree(repo)
        assertTrue(editedAgain != edited)

        // A new untracked file changes it, and so do its contents.
        repo.resolve("b.txt").writeText("new\n")
        val untracked = workspaceGit.workTree(repo)
        assertTrue(untracked != editedAgain)
        repo.resolve("b.txt").writeText("newer\n")
        assertTrue(workspaceGit.workTree(repo) != untracked)

        // Back to the committed content with nothing untracked is the clean tree again.
        Files.delete(repo.resolve("b.txt"))
        repo.resolve("a.txt").writeText("one\n")
        assertEquals(clean, workspaceGit.workTree(repo))
    }

    @Test
    fun `bl-136 a folder that is not a git work tree has no fingerprint`() {
        assertFailsWith<IOException> { workspaceGit.workTree(Files.createDirectories(folder.resolve("plain"))) }
    }

    @Test
    fun `a base already in the branch is an ancestor`() {
        val (work, base) = forked()

        assertFalse(workspaceGit.isAncestor(repo, base, work))
        git("merge", "-q", "--no-edit", base)
        assertTrue(workspaceGit.isAncestor(repo, base, git("rev-parse", "HEAD")))
        assertTrue(workspaceGit.hasCommit(repo, base))
        assertFalse(workspaceGit.hasCommit(repo, "0".repeat(40)))
    }

    @Test
    fun `a merge makes a merge commit by Loop and no planted hook runs`() {
        val (work, base) = forked()
        plantHooks()

        val merged = assertIs<WorkspaceGit.MergeOutcome.Merged>(workspaceGit.merge(repo, base, "m2-26: merge the base"))

        assertEquals(merged.head, git("rev-parse", "HEAD"))
        assertEquals("$work $base", git("log", "-1", "--format=%P"))
        assertEquals("Loop <loop@localhost>", git("log", "-1", "--format=%an <%ae>"))
        assertEquals("loop/t-1", git("rev-parse", "--abbrev-ref", "HEAD"))
        assertFalse(marker.exists(), "a hook ran: ${if (marker.exists()) marker.readText() else ""}")
    }

    @Test
    fun `a conflict is aborted and the work tree stays at its commit`() {
        val (work, base) = forked(conflict = true)
        plantHooks()

        val conflict = assertIs<WorkspaceGit.MergeOutcome.Conflict>(workspaceGit.merge(repo, base, "m2-26: merge the base"))

        assertTrue(conflict.detail.contains("b.txt"), conflict.detail)
        assertEquals(work, git("rev-parse", "HEAD"))
        assertEquals("", git("status", "--porcelain"))
        assertFalse(marker.exists(), "a hook ran")
    }

    @Test
    fun `the restore and the fingerprint run no planted hook or fsmonitor`() {
        forked()
        plantHooks()
        repo.resolve("a.txt").writeText("changed by a check\n")
        repo.resolve("new.txt").writeText("created by a check\n")

        val fingerprinter = WorkspaceFingerprinter(workspaceGit)
        fingerprinter.of(repo).getOrThrow()
        fingerprinter.restore(repo).getOrThrow()

        assertEquals("", git("status", "--porcelain"))
        assertFalse(marker.exists(), "a hook ran")
    }

    /** The commit graph files an automatic maintenance writes, to see whether one ran. */
    private fun commitGraphs(): List<String> =
        Files.list(repo.resolve(".git/objects/info")).use { files ->
            files.map { it.fileName.toString() }.filter { it.startsWith("commit-graph") }.toList()
        }

    /** [sha] once more, as a commit carrying a `gpgsig` header like the merges GitHub signs; its id. */
    private fun signed(sha: String): String {
        val body = git("cat-file", "commit", sha)
        val (headers, message) = body.split("\n\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val signature =
            listOf("-----BEGIN PGP SIGNATURE-----", "", "iQEzBAABCAAdFiEEfakefakefakefakefake", "=abcd", "-----END PGP SIGNATURE-----")
        val text = headers + "\ngpgsig " + signature.joinToString("\n ") + "\n\n" + message + "\n"
        val input = folder.resolve("signed-commit.txt")
        input.writeText(text)
        return git("hash-object", "-t", "commit", "-w", input.toString())
    }

    @Test
    fun `every command also pins signatures, gpg, automatic gc and maintenance off and ignores replace refs`() {
        forked()
        val (wrapper, log) = loggingGit()
        val logged = WorkspaceGit(hostEnv, git = wrapper.toString())

        logged.head(repo)
        WorkspaceFingerprinter(logged).of(repo).getOrThrow()
        WorkspaceFingerprinter(logged).restore(repo).getOrThrow()

        val args = log.readText().lines().filter { it.startsWith("ARGS ") }
        assertTrue(args.size >= 4, "git ran ${args.size} times")
        args.forEach { line ->
            for (pin in listOf(
                "-c merge.verifySignatures=false",
                "-c gpg.program= ",
                "-c gc.auto=0",
                "-c maintenance.auto=false",
                "--no-replace-objects",
            )) {
                assertTrue(pin in line, "$pin is missing: $line")
            }
        }
    }

    @Test
    fun `a signed base tip merges with no gpg program run and no automatic maintenance`() {
        val (work, base) = forked()
        val signedBase = signed(base)
        Files.createDirectories(repo.resolve(".git/objects/info"))
        val gpg = folder.resolve("gpg.sh")
        gpg.writeText("#!/bin/sh\necho gpg \"\$@\" >> '$marker'\nexit 1\n")
        Files.setPosixFilePermissions(gpg, PosixFilePermissions.fromString("rwxr-xr-x"))
        for ((key, value) in listOf(
            "merge.verifySignatures" to "true",
            "gpg.program" to gpg.toString(),
            "gpg.ssh.program" to gpg.toString(),
            "gpg.x509.program" to gpg.toString(),
            // An automatic maintenance after the merge would write a commit graph, in the foreground.
            "maintenance.auto" to "true",
            "maintenance.autoDetach" to "false",
            "maintenance.commit-graph.enabled" to "true",
            "maintenance.commit-graph.auto" to "-1",
            "gc.auto" to "1",
            "gc.autoDetach" to "false",
        )) {
            git("config", key, value)
        }

        val merged = assertIs<WorkspaceGit.MergeOutcome.Merged>(workspaceGit.merge(repo, signedBase, "m2-26: merge the base"))

        assertFalse(marker.exists(), "the gpg program ran: ${if (marker.exists()) marker.readText() else ""}")
        assertEquals(git("rev-parse", "HEAD"), merged.head)
        assertEquals(signedBase, git("rev-parse", "HEAD^2"))
        assertEquals(emptyList(), commitGraphs(), "an automatic maintenance ran")
        assertEquals(work, git("rev-parse", "HEAD^1"))
    }

    @Test
    fun `a merge that fails without a conflicted path is an error, not a conflict, and HEAD stays`() {
        val (work, base) = forked()
        val lock = Files.createFile(repo.resolve(".git/index.lock"))

        val error = assertFailsWith<IOException> { workspaceGit.merge(repo, base, "m2-26: merge the base") }

        assertTrue(error.message!!.contains("index.lock"), error.message)
        Files.delete(lock)
        assertEquals(work, git("rev-parse", "HEAD"))
        assertEquals("", git("status", "--porcelain"))
    }

    @Test
    fun `an untracked file in the way fails the merge as an error, not a conflict`() {
        val (work, base) = forked()
        repo.resolve("c.txt").writeText("an untracked file the base also adds\n")

        val error = assertFailsWith<IOException> { workspaceGit.merge(repo, base, "m2-26: merge the base") }

        assertTrue(error.message!!.contains("c.txt") || error.message!!.contains("untracked"), error.message)
        assertEquals(work, git("rev-parse", "HEAD"))
        assertEquals("an untracked file the base also adds\n", repo.resolve("c.txt").readText())
    }

    private companion object {
        /** The test's own git commands never run what the test plants either. */
        val TEST_PINS = listOf("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false")
    }
}

private class ClosedStreamProcess : Process() {
    private val stopped = java.util.concurrent.CountDownLatch(1)
    private val reading = java.util.concurrent.CountDownLatch(2)
    val failures =
        java.util.concurrent.atomic
            .AtomicInteger()

    private fun stream() =
        object : java.io.InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
                failures.incrementAndGet()
                throw java.io.IOException("Stream closed")
            }
        }

    override fun getInputStream() = stream()

    override fun getErrorStream() = stream()

    override fun getOutputStream() = java.io.ByteArrayOutputStream()

    override fun waitFor(): Int {
        check(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return 1
    }

    override fun waitFor(
        timeout: Long,
        unit: java.util.concurrent.TimeUnit,
    ): Boolean {
        check(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return false
    }

    override fun exitValue() = 1

    override fun isAlive() = stopped.count != 0L

    override fun descendants(): java.util.stream.Stream<ProcessHandle> =
        java.util.stream.Stream
            .empty()

    override fun destroy() {
        stopped.countDown()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}
