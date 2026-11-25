package loop.core.kinds

import loop.core.delivery.AttachDelivery
import loop.core.workflow.CheckConfig
import loop.core.workflow.KindConfig
import loop.core.workflow.KindDeliverable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m4-21, the listing of a kind's `deliverable.files`: the files of the workspace that match its globs, with their sizes
 * and digests, and the preflight rule of a kind that declares them.
 */
class DeliverableFilesTest {
    private val root: Path = createTempDirectory("loop-deliverable").toRealPath()

    private fun write(
        path: String,
        text: String,
    ): Path = root.resolve(path).also { it.parent.createDirectories() }.apply { writeText(text) }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // Matching, a pure function of paths and globs.

    @Test
    fun `a double star glob matches files at any depth under its folder, the folder itself included`() {
        val paths = listOf("out/post.md", "out/2026/11/post.md", "out/post.txt", "drafts/post.md", "outside/out/post.md")

        assertEquals(listOf("out/2026/11/post.md", "out/post.md"), DeliverableFiles.matching(paths, listOf("out/**/*.md")))
    }

    @Test
    fun `a single star stays in one folder, globs are anchored at the workspace root and every glob adds its matches`() {
        val paths = listOf("README.md", "docs/README.md", "out/a.md", "out/sub/b.md", "report.pdf")

        assertEquals(listOf("README.md"), DeliverableFiles.matching(paths, listOf("*.md")))
        assertEquals(listOf("out/a.md"), DeliverableFiles.matching(paths, listOf("out/*.md")))
        assertEquals(listOf("out/a.md", "report.pdf"), DeliverableFiles.matching(paths, listOf("out/*.md", "*.pdf", "out/a.md")))
    }

    @Test
    fun `no path matching any glob is an empty list`() {
        assertEquals(emptyList(), DeliverableFiles.matching(listOf("notes.txt", "out"), listOf("out/**/*.md")))
        assertEquals(emptyList(), DeliverableFiles.matching(emptyList(), listOf("out/**/*.md")))
    }

    @Test
    fun `a glob naming a folder does not match the files under it`() {
        assertEquals(emptyList(), DeliverableFiles.matching(listOf("out/a.md"), listOf("out")))
        assertEquals(listOf("out/a.md"), DeliverableFiles.matching(listOf("out/a.md"), listOf("out/**")))
    }

    // Listing a workspace.

    @Test
    fun `the files of a workspace that match are listed in path order with their size and sha256 digest`() {
        write("out/b.md", "# B\n")
        write("out/2026/a.md", "# A, longer\n")
        write("out/skip.txt", "not markdown\n")
        write("notes.md", "not under out\n")

        val files = DeliverableFiles.list(root, listOf("out/**/*.md"))

        assertEquals(
            listOf(
                DeliverableFile("out/2026/a.md", 12, sha256("# A, longer\n")),
                DeliverableFile("out/b.md", 4, sha256("# B\n")),
            ),
            files,
        )
    }

    @Test
    fun `a workspace with no matching file, or without the folder at all, lists nothing`() {
        assertEquals(emptyList(), DeliverableFiles.list(root, listOf("out/**/*.md")))
        write("out/post.txt", "text\n")
        assertEquals(emptyList(), DeliverableFiles.list(root, listOf("out/**/*.md")))
    }

    @Test
    fun `git's own entries, folders and symlinks are never listed`() {
        write(".git/info/exclude.md", "git state\n")
        write("sub/.git", "gitdir: elsewhere\n")
        write("out/real.md", "# Real\n")
        root.resolve("out/folder.md").createDirectories()
        val outside = createTempDirectory("loop-deliverable-outside").resolve("secret.md").apply { writeText("outside\n") }
        Files.createSymbolicLink(root.resolve("out/link.md"), outside)

        val files = DeliverableFiles.list(root, listOf("**/*.md", "**/.git"))

        assertEquals(listOf("out/real.md"), files.map { it.path })
    }

    @Test
    fun `a file matched by two globs is listed once`() {
        write("out/post.md", "# Post\n")

        assertEquals(listOf("out/post.md"), DeliverableFiles.list(root, listOf("out/*.md", "out/**")).map { it.path })
    }

    // What verification gets.

    private fun kind(files: List<String>) =
        IssueKind("anything", KindConfig(listOf(CheckConfig.Command("true")), deliverable = KindDeliverable(files)))

    @Test
    fun `a kind with no deliverable files gets no listing, whatever its name`() {
        assertNull(DeliverableFiles.listing(null, root))
        assertNull(DeliverableFiles.listing(kind(emptyList()), root))
        assertNull(DeliverableFiles.listing(IssueKind("content", KindConfig(emptyList())), root))
    }

    @Test
    fun `a kind with deliverable files gets its listing, and an empty one is missing`() {
        val empty = assertIs<DeliverableListing.Listed>(DeliverableFiles.listing(kind(listOf("out/**/*.md")), root))
        assertEquals(true, empty.missing)
        write("out/post.md", "# Post\n")
        val listed = assertIs<DeliverableListing.Listed>(DeliverableFiles.listing(kind(listOf("out/**/*.md")), root))
        assertEquals(false, listed.missing)
        assertEquals(listOf("out/post.md"), listed.files.map { it.path })
        assertEquals(listOf("out/**/*.md"), listed.globs)
    }

    @Test
    fun `a workspace that cannot be read gives a failed listing with the reason, never a throw`() {
        val gone = root.resolve("gone")

        val failed = assertIs<DeliverableListing.Failed>(DeliverableFiles.listing(kind(listOf("*.md")), gone))

        assertEquals(listOf("*.md"), failed.globs)
        assertEquals(true, failed.reason.startsWith("cannot list the deliverable files: "), failed.reason)
    }

    // Preflight.

    @Test
    fun `policy auto is refused on a kind with deliverable files, and human or no deliverable is accepted`() {
        // m4-22: the rule moved to the attach delivery, which defines what auto means for such a kind.
        assertEquals(
            "kinds.posts.delivery.policy is auto, which a kind with deliverable.files supports only with " +
                "kinds.posts.delivery.attach: true and the opt in kinds.posts.delivery.attach_auto.enabled: true, or use policy: human",
            AttachDelivery.refusal(
                "kinds.posts",
                listOf("out/**/*.md"),
                attach = false,
                attachAuto = false,
                pullRequest = false,
                auto = true,
            ),
        )
        assertNull(
            AttachDelivery.refusal(
                "kinds.posts",
                listOf("out/**/*.md"),
                attach = false,
                attachAuto = false,
                pullRequest = false,
                auto = false,
            ),
        )
        assertNull(AttachDelivery.refusal("kinds.code", emptyList(), attach = false, attachAuto = false, pullRequest = false, auto = true))
    }
}
