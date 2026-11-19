package loop.core.verify

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** m2-05: the fingerprint of the workspace state a verification ran on, real git and real files. */
class WorkspaceFingerprintTest {
    private val workspace: Path = createTempDirectory("loop-fingerprint").toRealPath()

    private fun git(vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
    }

    private fun repo() {
        git("init", "-q")
        workspace.resolve(".gitignore").writeText("build/\n")
        workspace.resolve("a.txt").writeText("one\n")
        git("add", ".")
        git("commit", "-q", "-m", "first")
    }

    private fun fingerprint(): String = WorkspaceFingerprint.of(workspace).getOrThrow()

    @Test
    fun `two fingerprints of the same git state are the same`() {
        repo()
        workspace.resolve("a.txt").writeText("changed\n")
        workspace.resolve("new.txt").writeText("untracked\n")

        val first = fingerprint()

        assertEquals(first, fingerprint())
        assertTrue(first.startsWith("git:"), first)
    }

    @Test
    fun `a changed tracked file changes the fingerprint`() {
        repo()
        val before = fingerprint()

        workspace.resolve("a.txt").writeText("two\n")

        assertNotEquals(before, fingerprint())
    }

    @Test
    fun `a changed untracked file changes the fingerprint, an ignored one does not`() {
        repo()
        workspace.resolve("new.txt").writeText("first\n")
        workspace.resolve("build").createDirectories()
        val before = fingerprint()

        workspace.resolve("build/out.txt").writeText("ignored\n")
        assertEquals(before, fingerprint(), "ignored files are not workspace state")

        workspace.resolve("new.txt").writeText("second\n")
        assertNotEquals(before, fingerprint())
    }

    @Test
    fun `a commit changes the head part, staging does not change anything`() {
        repo()
        workspace.resolve("a.txt").writeText("two\n")
        val unstaged = fingerprint()

        git("add", "a.txt")
        assertEquals(unstaged, fingerprint(), "staged or not, the files are the same")

        git("commit", "-q", "-m", "second")
        assertNotEquals(unstaged.split(":")[1], fingerprint().split(":")[1])
    }

    @Test
    fun `a git fingerprint holds the head, the diff hash and the untracked hash`() {
        repo()

        val parts = fingerprint().split(":")

        assertEquals(4, parts.size, parts.toString())
        assertTrue(Regex("[0-9a-f]{40,64}").matches(parts[1]), parts[1])
        assertTrue(Regex("[0-9a-f]{16}").matches(parts[2]), parts[2])
        assertTrue(Regex("[0-9a-f]{16}").matches(parts[3]), parts[3])
    }

    @Test
    fun `a repository with no commit yet has no head`() {
        git("init", "-q")
        workspace.resolve("a.txt").writeText("one\n")

        val parts = fingerprint().split(":")

        assertEquals("none", parts[1])
    }

    @Test
    fun `a folder outside git gets a content hash that one changed file changes`() {
        workspace.resolve("a.txt").writeText("one\n")
        workspace.resolve("sub").createDirectories()
        workspace.resolve("sub/b.txt").writeText("two\n")

        val first = fingerprint()
        assertEquals(first, fingerprint())
        assertTrue(Regex("tree:[0-9a-f]{16}").matches(first), first)

        workspace.resolve("sub/b.txt").writeText("three\n")
        assertNotEquals(first, fingerprint())
    }

    @Test
    fun `a folder inside another repository is hashed on its own, not as that repository`() {
        repo()
        val inner = workspace.resolve("inner").createDirectories()
        inner.resolve("x.txt").writeText("x\n")

        val fingerprint = WorkspaceFingerprint.of(inner).getOrThrow()

        assertTrue(fingerprint.startsWith("tree:"), fingerprint)
    }

    @Test
    fun `the result file is not workspace state in a git work tree, other loop files are (m2-15)`() {
        repo()
        workspace.resolve(".loop/specs").createDirectories()
        workspace.resolve(".loop/specs/s.md").writeText("spec\n")
        git("add", ".")
        git("commit", "-q", "-m", "spec")
        val before = fingerprint()

        workspace.resolve(".loop/result.json").writeText("{\"status\": \"done\"}\n")
        assertEquals(before, fingerprint(), "the result file the agent writes is not the work")

        workspace.resolve(".loop/specs/s.md").writeText("changed\n")
        assertNotEquals(before, fingerprint(), "a tracked file under .loop/ is still the work")
    }

    @Test
    fun `an untracked file under the loop folder is workspace state in a git work tree (m2-15)`() {
        repo()
        val before = fingerprint()

        workspace.resolve(".loop").createDirectories()
        workspace.resolve(".loop/result.json").writeText("{}\n")
        assertEquals(before, fingerprint())

        workspace.resolve(".loop/notes.md").writeText("note\n")
        assertNotEquals(before, fingerprint(), "only .loop/result.json is left out")
    }

    @Test
    fun `the result file is not workspace state in a folder outside git, other loop files are (m2-15)`() {
        workspace.resolve("a.txt").writeText("one\n")
        workspace.resolve("sub/.loop").createDirectories()
        workspace.resolve("sub/.loop/result.json").writeText("{}\n")
        val before = fingerprint()

        workspace.resolve(".loop").createDirectories()
        workspace.resolve(".loop/result.json").writeText("{}\n")
        assertEquals(before, fingerprint())

        workspace.resolve(".loop/notes.md").writeText("note\n")
        val withNotes = fingerprint()
        assertNotEquals(before, withNotes, "only .loop/result.json is left out")

        workspace.resolve("sub/.loop/result.json").writeText("{\"status\": \"done\"}\n")
        assertNotEquals(withNotes, fingerprint(), "only the result file at the top of the workspace is left out")
    }

    @Test
    fun `the uncommitted files are the changed tracked ones and the untracked ones, never ignored ones or the result file (m2-25)`() {
        repo()
        assertEquals(UncommittedFiles(emptyList(), emptyList()), WorkspaceFingerprint.uncommitted(workspace).getOrThrow())

        workspace.resolve("a.txt").writeText("changed\n")
        workspace.resolve("staged.txt").writeText("staged\n")
        git("add", "staged.txt")
        workspace.resolve("dir").createDirectories()
        workspace.resolve("dir/new file.txt").writeText("untracked\n")
        workspace.resolve("build").createDirectories()
        workspace.resolve("build/out.txt").writeText("ignored\n")
        workspace.resolve(".loop").createDirectories()
        workspace.resolve(".loop/result.json").writeText("{}")

        val files = WorkspaceFingerprint.uncommitted(workspace).getOrThrow()

        assertEquals(listOf("a.txt", "staged.txt"), files.changed)
        assertEquals(listOf("dir/new file.txt"), files.untracked)
    }

    @Test
    fun `a work tree with no commit lists its staged and unstaged files as changed (m2-25)`() {
        git("init", "-q")
        workspace.resolve("a.txt").writeText("one\n")
        git("add", "a.txt")
        workspace.resolve("a.txt").writeText("two\n")
        workspace.resolve("b.txt").writeText("b\n")

        val files = WorkspaceFingerprint.uncommitted(workspace).getOrThrow()

        assertEquals(listOf("a.txt"), files.changed)
        assertEquals(listOf("b.txt"), files.untracked)
    }

    @Test
    fun `restore puts back the committed tree, keeping ignored files and the result file (m2-25)`() {
        repo()
        val clean = fingerprint()
        workspace.resolve("a.txt").writeText("regenerated by a check\n")
        workspace.resolve("staged.txt").writeText("staged\n")
        git("add", "staged.txt")
        workspace.resolve("dir").createDirectories()
        workspace.resolve("dir/new file.txt").writeText("untracked\n")
        workspace.resolve("build").createDirectories()
        workspace.resolve("build/out.txt").writeText("ignored\n")
        workspace.resolve(".loop").createDirectories()
        workspace.resolve(".loop/result.json").writeText("{}")

        WorkspaceFingerprint.restore(workspace).getOrThrow()

        assertEquals(clean, fingerprint())
        assertEquals("one\n", workspace.resolve("a.txt").readText())
        assertFalse(workspace.resolve("staged.txt").exists())
        assertFalse(workspace.resolve("dir").exists())
        assertEquals("ignored\n", workspace.resolve("build/out.txt").readText(), "an ignored file stays")
        assertEquals("{}", workspace.resolve(".loop/result.json").readText(), "the result file stays")
    }

    @Test
    fun `restore outside the top of a git work tree fails and changes nothing (m2-25)`() {
        workspace.resolve("a.txt").writeText("one\n")

        assertTrue(WorkspaceFingerprint.restore(workspace).isFailure)
        assertEquals("one\n", workspace.resolve("a.txt").readText())
    }
}
