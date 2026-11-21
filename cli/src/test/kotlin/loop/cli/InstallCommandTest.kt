package loop.cli

import loop.api.ProjectId
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `loop install --repo <dir> <dest>` (m1-12): the build copied outside the repository, guarded by live runs. */
class InstallCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    /** Stands in for the source repository: only its path matters to the command. */
    private val repo: Path by lazy { root.resolve("repo").createDirectories() }

    /** The app home of the launcher under test, `cli/build/install/loop`. */
    private val appHome: Path = Path(System.getProperty("loop.launcher")).parent.parent

    private val installedVersion: String =
        appHome
            .resolve("build-info.properties")
            .readText()
            .lines()
            .single { it.startsWith("version=") }
            .removePrefix("version=")

    private fun install(dest: Path): LoopResult = fixture.loop("install", "--repo", repo.toString(), dest.toString())

    /** Runs `<dest>/bin/loop` with [args], with the fixture's `LOOP_HOME` and `HOME` and no other `LOOP_*` variable. */
    private fun runInstalled(
        dest: Path,
        vararg args: String,
    ): LoopResult {
        val out = Files.createTempFile(root, "installed", ".out").toFile()
        val err = Files.createTempFile(root, "installed", ".err").toFile()
        val builder = ProcessBuilder(listOf(dest.resolve("bin/loop").toString()) + args).redirectOutput(out).redirectError(err)
        builder.environment().keys.removeIf { it.startsWith("LOOP_") || it == "JAVA_OPTS" }
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.environment()["LOOP_HOME"] = fixture.loopHome.toString()
        builder.environment()["HOME"] = fixture.homeDir.toString()
        val process = builder.start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("$dest/bin/loop ${args.joinToString(" ")} did not exit in 60s")
        }
        return LoopResult(process.exitValue(), out.readText(), err.readText())
    }

    // T4: a fresh install, then a second one over it.

    @Test
    fun `install copies the build to a new folder and a second install replaces it`() {
        val dest = root.resolve("machine/install")

        val first = install(dest)

        assertEquals(0, first.exitCode, first.toString())
        assertEquals("Installed loop $installedVersion at ${dest.toRealPath()}.", first.stdout.trim())
        assertTrue(Files.isExecutable(dest.resolve("bin/loop")), "bin/loop is not executable")
        assertTrue(Files.list(dest.resolve("lib")).use { files -> files.anyMatch { it.fileName.toString().endsWith(".jar") } })
        // The build's own file, copied as is: the version and, since bl-159, the full commit.
        val buildInfo = dest.resolve("build-info.properties").readText()
        assertEquals(appHome.resolve("build-info.properties").readText(), buildInfo)
        assertTrue(Regex("version=\\Q$installedVersion\\E\ncommit=[0-9a-f]{40}\n").matches(buildInfo), buildInfo)
        val version = runInstalled(dest, "--version")
        assertEquals(0, version.exitCode, version.toString())
        assertEquals("loop $installedVersion", version.stdout.trim())

        // A file the first install did not ship is gone after the second: the folder is replaced, not merged.
        dest.resolve("leftover.txt").writeText("from before")
        val second = install(dest)

        assertEquals(0, second.exitCode, second.toString())
        assertFalse(dest.resolve("leftover.txt").exists(), "the second install merged into the first")
        assertTrue(Files.isExecutable(dest.resolve("bin/loop")))
        assertFalse(dest.resolveSibling("install.tmp").exists(), "install.tmp left behind")
        assertFalse(dest.resolveSibling("install.old").exists(), "install.old left behind")
        assertEquals(0, runInstalled(dest, "--version").exitCode)
    }

    // T5: a live run of any project refuses the install.

    /** Holds the run file of [projectId] in this JVM, as a live `loop run` of that project would, while [block] runs. */
    private fun <T> withLiveRun(
        projectId: String,
        block: (RunRecord) -> T,
    ): T {
        val record = RunRecord.ofCurrentProcess("run-live", fixture.workflow)
        val acquired = RunFiles(fixture.loopHome, ProjectId(projectId)).acquire(record)
        val lock = (acquired as? RunFiles.Acquired.Held)?.lock ?: fail("could not hold the run file: $acquired")
        return try {
            block(record)
        } finally {
            lock.release()
        }
    }

    @Test
    fun `a live run of another project refuses the install and leaves the dest alone`() {
        val dest = root.resolve("machine/install")
        assertEquals(0, install(dest).exitCode)
        dest.resolve("marker.txt").writeText("previous install")

        val result = withLiveRun(OTHER_PROJECT) { install(dest) }

        assertEquals(2, result.exitCode, result.toString())
        val workflow = fixture.workflow.toString()
        assertEquals(
            "loop install: a run is in progress for project $OTHER_PROJECT (pid ${ProcessHandle.current().pid()}), " +
                "workflow $workflow. Run loop stop $workflow first, then install again.",
            result.stderr.trim(),
        )
        assertEquals("", result.stdout.trim())
        assertEquals("previous install", dest.resolve("marker.txt").readText())
        assertFalse(dest.resolveSibling("install.tmp").exists(), "install.tmp left behind")
    }

    // T6 and T7, plan D1: only a live run file lock blocks.

    @Test
    fun `a stale run file of a dead process does not block the install and is removed`() {
        val dead = ProcessBuilder("true").start()
        dead.waitFor()
        val runFile = fixture.loopHome.resolve("runs/$OTHER_PROJECT.json")
        runFile.parent.createDirectories()
        runFile.writeText("""{"pid":${dead.pid()},"run_id":"run-dead","workflow":"${fixture.workflow}"}""")
        val dest = root.resolve("machine/install")

        val result = install(dest)

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(Files.isExecutable(dest.resolve("bin/loop")))
        assertFalse(runFile.exists(), "the stale run file was kept")
    }

    @Test
    fun `an open attempt in the store from a dead run does not block the install`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted("a-1", "t-1"))
        val dest = root.resolve("machine/install")

        val result = install(dest)

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(Files.isExecutable(dest.resolve("bin/loop")))
    }

    // T8: dests the command refuses before any file work, exit 1.

    @Test
    fun `a dest inside the source repository is refused`() {
        val dest = repo.resolve("build/loop")

        val result = install(dest)

        assertEquals(1, result.exitCode, result.toString())
        assertEquals(
            "loop install: ${realOf(dest)} is inside the source repository at ${repo.toRealPath()}. " +
                "Choose a path outside it, the default is ${realOf(fixture.loopHome)}/install.",
            result.stderr.trim(),
        )
        assertFalse(repo.resolve("build").exists(), "the refused install wrote into the repository")
    }

    /**
     * Run from a copy of the build in the temporary folder, never from cli/build/install: a missing
     * guard then damages only the copy.
     */
    @Test
    fun `a dest that nests with the running install is refused`() {
        val running = root.resolve("machine/install")
        assertEquals(0, install(running).exitCode)

        listOf(running, running.parent, running.resolve("lib/nested")).forEach { dest ->
            val result = runInstalled(running, "install", "--repo", repo.toString(), dest.toString())

            assertEquals(1, result.exitCode, "for $dest\n$result")
            assertEquals(
                "loop install: ${realOf(dest)} is the install this command runs from. Choose another path.",
                result.stderr.trim(),
            )
            assertTrue(Files.isExecutable(running.resolve("bin/loop")), "the running install was damaged by $dest")
            assertFalse(running.resolve("lib/nested.tmp").exists(), "a copy was started for $dest")
            assertFalse(root.resolve("machine.tmp").exists(), "a copy was started for $dest")
        }
    }

    // T10: the store and the home folder are never replaced.

    @Test
    fun `LOOP_HOME, HOME and their ancestors are refused and nothing is deleted`() {
        // Both under folders of their own, so a missing guard on an ancestor cannot replace the test's temp folder.
        val store = root.resolve("store").createDirectories()
        val loopHome = store.resolve("loop-home").createDirectories()
        val storeFile = loopHome.resolve("store.db").apply { writeText("store") }
        val users = root.resolve("users").createDirectories()
        val home = users.resolve("me").createDirectories()
        val homeFile = home.resolve(".profile").apply { writeText("home") }
        val env = mapOf("LOOP_HOME" to loopHome.toString(), "HOME" to home.toString())

        listOf(loopHome, store, home, users).forEach { dest ->
            val result = fixture.loop("install", "--repo", repo.toString(), dest.toString(), env = env)

            assertEquals(1, result.exitCode, "for $dest\n$result")
            assertEquals(
                "loop install: ${realOf(dest)} holds the Loop store or your home folder. " +
                    "Choose a path of its own, the default is ${loopHome.toRealPath()}/install.",
                result.stderr.trim(),
            )
            assertEquals("store", storeFile.readText(), "the store was touched for $dest")
            assertEquals("home", homeFile.readText(), "the home folder was touched for $dest")
            assertFalse(dest.resolveSibling("${dest.fileName}.tmp").exists(), "a copy was started for $dest")
        }
    }

    // T11: a folder that is not a previous install is never replaced.

    @Test
    fun `a non empty dest without build info is refused and its contents stay`() {
        val dest = root.resolve("documents").createDirectories()
        val notes = dest.resolve("notes.txt").apply { writeText("keep me") }

        val result = install(dest)

        assertEquals(1, result.exitCode, result.toString())
        assertEquals(
            "loop install: ${dest.toRealPath()} is not empty and is not a Loop install. Choose an empty or new path.",
            result.stderr.trim(),
        )
        assertEquals("keep me", notes.readText())
        assertEquals(listOf("notes.txt"), Files.list(dest).use { files -> files.map { it.fileName.toString() }.toList() })
        assertFalse(root.resolve("documents.tmp").exists(), "a copy was started")
    }

    // A leftover is deleted only when it is a Loop install.

    @Test
    fun `a leftover old folder that is not a Loop install is refused and its contents stay`() {
        val dest = root.resolve("machine/install")
        val old = root.resolve("machine/install.old").createDirectories()
        val notes = old.resolve("notes.txt").apply { writeText("keep me") }

        val result = install(dest)

        assertEquals(1, result.exitCode, result.toString())
        assertEquals(
            "loop install: ${old.toRealPath()} is not empty and is not a Loop install. Choose an empty or new path.",
            result.stderr.trim(),
        )
        assertEquals("keep me", notes.readText())
        assertFalse(dest.exists(), "installed despite the leftover")
        assertFalse(root.resolve("machine/install.tmp").exists(), "a copy was started")
    }

    @Test
    fun `an empty existing dest is installed into`() {
        val dest = root.resolve("empty").createDirectories()

        val result = install(dest)

        assertEquals(0, result.exitCode, result.toString())
        assertTrue(Files.isExecutable(dest.resolve("bin/loop")))
    }

    // T12: a run file the guard cannot read counts as a live run.

    /** Permission tests mean nothing as root, who reads every file. */
    private fun assumeNotRoot() = assumeFalse(System.getProperty("user.name") == "root", "running as root")

    @Test
    fun `an unreadable run file refuses the install`() {
        assumeNotRoot()
        val runFile = fixture.loopHome.resolve("runs/$OTHER_PROJECT.json")
        runFile.parent.createDirectories()
        runFile.writeText("""{"pid":1,"run_id":"run-x","workflow":"${fixture.workflow}"}""")
        Files.setPosixFilePermissions(runFile, PosixFilePermissions.fromString("---------"))
        val dest = root.resolve("machine/install")

        val result =
            try {
                install(dest)
            } finally {
                Files.setPosixFilePermissions(runFile, PosixFilePermissions.fromString("rw-------"))
            }

        assertEquals(2, result.exitCode, result.toString())
        assertEquals(
            "loop install: cannot read the run file $runFile, a run may be in progress. Check it, then install again.",
            result.stderr.trim(),
        )
        assertFalse(dest.exists(), "installed despite the unreadable run file")
        assertTrue(runFile.exists(), "the unreadable run file was removed")
    }

    // Unreadable parts of the build, the run folder or the dest, and a build without a version.

    /** Runs [block] with [paths] set to mode 000, then gives them back to their owner, so the temp folder can be cleaned. */
    private fun <T> withNoAccess(
        vararg paths: Path,
        block: () -> T,
    ): T {
        paths.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("---------")) }
        return try {
            block()
        } finally {
            paths.filter { it.exists() }.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        }
    }

    @Test
    fun `a copy that cannot read part of the build fails with the copy message`() {
        assumeNotRoot()
        val running = root.resolve("machine/install")
        assertEquals(0, install(running).exitCode)
        val locked = running.resolve("extra").createDirectories()
        val dest = root.resolve("other/install")

        val result =
            withNoAccess(locked) {
                try {
                    runInstalled(running, "install", "--repo", repo.toString(), dest.toString())
                } finally {
                    // The copy may have recreated the locked folder in install.tmp, with the same mode.
                    root.resolve("other/install.tmp/extra").takeIf { it.exists() }?.let {
                        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
                    }
                }
            }

        assertEquals(3, result.exitCode, result.toString())
        val tmp = realOf(root.resolve("other/install.tmp"))
        val line = result.stderr.trim()
        assertTrue(line.startsWith("loop install: cannot copy the build to $tmp: "), line)
        assertTrue(line.endsWith(". Nothing was installed, ${realOf(dest)} is unchanged."), line)
        assertFalse(dest.exists())
    }

    @Test
    fun `an unreadable run folder refuses the install`() {
        assumeNotRoot()
        val runs = fixture.loopHome.resolve("runs").createDirectories()
        val dest = root.resolve("machine/install")

        val result = withNoAccess(runs) { install(dest) }

        assertEquals(2, result.exitCode, result.toString())
        assertEquals(
            "loop install: cannot read the run folder $runs, a run may be in progress. Check it, then install again.",
            result.stderr.trim(),
        )
        assertFalse(dest.exists())
    }

    @Test
    fun `a dest that cannot be listed is refused with the reason`() {
        assumeNotRoot()
        val dest = root.resolve("locked").createDirectories()
        dest.resolve("notes.txt").writeText("keep me")

        val result = withNoAccess(dest) { install(dest) }

        assertEquals(1, result.exitCode, result.toString())
        val line = result.stderr.trim()
        assertTrue(line.startsWith("loop install: cannot read ${dest.toRealPath()}: "), line)
        assertEquals("keep me", dest.resolve("notes.txt").readText())
    }

    @Test
    fun `a build without a known version installs with a neutral line`() {
        val running = root.resolve("machine/install")
        assertEquals(0, install(running).exitCode)
        running.resolve("build-info.properties").writeText("version=unknown\n")
        val dest = root.resolve("other/install")

        val result = runInstalled(running, "install", "--repo", repo.toString(), dest.toString())

        assertEquals(0, result.exitCode, result.toString())
        assertEquals("Installed loop (version unknown) at ${realOf(dest)}.", result.stdout.trim())
    }

    @Test
    fun `install without --repo is a usage error`() {
        val dest = root.resolve("machine/install")

        val result = fixture.loop("install", dest.toString())

        assertEquals(2, result.exitCode, result.toString())
        assertTrue("--repo" in result.stderr, result.stderr)
        assertFalse(dest.exists())
    }

    private companion object {
        /** A project id no fixture workflow has, so the guard is shown to look past the current project. */
        const val OTHER_PROJECT = "0123456789abcdef"
    }
}
