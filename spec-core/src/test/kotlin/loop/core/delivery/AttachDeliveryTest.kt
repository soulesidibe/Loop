package loop.core.delivery

import loop.core.kinds.DeliverableFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m4-22, the comment of the attach delivery, a pure function: each deliverable file under its path in a fenced block
 * longer than any backtick fence it holds, and a file that does not fit under [AttachComment.MAX_CHARS] listed by path,
 * size and digest instead, so nothing is cut in silence.
 */
class AttachDeliveryTest {
    private fun file(
        path: String,
        text: String,
    ) = AttachedFile(DeliverableFile(path, text.toByteArray().size.toLong(), digest(path)), text)

    private fun digest(path: String) =
        path
            .hashCode()
            .toUInt()
            .toString(16)
            .padStart(64, '0')

    @Test
    fun `the cap is below GitHub's limit of 65,536 characters per comment`() {
        assertEquals(60_000, AttachComment.MAX_CHARS)
        assertTrue(AttachComment.MAX_CHARS < 65_536)
    }

    @Test
    fun `each file is posted under its path in a fenced block, in the order given`() {
        val text =
            AttachComment.build(
                "p-1",
                "a1",
                listOf(file("out/2026/second.md", "# Second\n"), file("out/post.md", "# Post\n\nA line.")),
            )

        assertEquals(
            """
            |Loop attached the deliverable of p-1, verified in attempt a1: 2 files.
            |
            |`out/2026/second.md`, 9 bytes:
            |
            |```
            |# Second
            |```
            |
            |`out/post.md`, 15 bytes:
            |
            |```
            |# Post
            |
            |A line.
            |```
            """.trimMargin(),
            text,
        )
    }

    @Test
    fun `a file holding a code fence is wrapped in a longer fence, so the blocks stay whole`() {
        val post = "# Post\n\n```kotlin\nval x = 1\n```\n"
        val longer = "Text\n`````\nnested\n`````\n"

        val text = AttachComment.build("p-1", "a1", listOf(file("out/a.md", post), file("out/b.md", longer)))

        assertContains(text, "`out/a.md`, ${post.length} bytes:\n\n````\n$post````")
        assertContains(text, "`out/b.md`, ${longer.length} bytes:\n\n``````\n$longer``````")
        assertEquals("```", AttachComment.fence("no backtick"))
        assertEquals("```", AttachComment.fence("one ` and two `` backticks"))
        assertEquals("````", AttachComment.fence("```"))
        assertEquals("````````", AttachComment.fence("a ``````` run"))
    }

    @Test
    fun `a file over the cap is listed by path, size and digest instead of inlined`() {
        val big = file("out/big.md", "a".repeat(70_000))
        val small = file("out/small.md", "# Small\n")

        val text = AttachComment.build("p-1", "a1", listOf(big, small))

        assertTrue(text.length <= AttachComment.MAX_CHARS, "${text.length}")
        assertFalse(text.contains("aaaaaaaaaa"), "the big file is not inlined")
        assertContains(text, "Loop attached the deliverable of p-1, verified in attempt a1: 2 files.")
        assertContains(text, "`out/small.md`, 8 bytes:\n\n```\n# Small\n```")
        assertTrue(
            text.endsWith(
                "Listed, not inlined, the workspace is kept until the issue is delivered:\n" +
                    "- `out/big.md`, 70000 bytes, sha256 ${digest("out/big.md")}, over the 60000 character cap of a comment",
            ),
            text,
        )
    }

    @Test
    fun `files that fit alone but not together are inlined in order until the cap, the rest listed`() {
        val files = (1..4).map { file("out/$it.md", "$it".repeat(25_000)) }

        val text = AttachComment.build("p-1", "a1", files)

        assertTrue(text.length <= AttachComment.MAX_CHARS, "${text.length}")
        assertContains(text, "`out/1.md`, 25000 bytes:")
        assertContains(text, "`out/2.md`, 25000 bytes:")
        assertContains(
            text,
            "- `out/3.md`, 25000 bytes, sha256 ${digest("out/3.md")}, no room left under the 60000 character cap of a comment",
        )
        assertContains(
            text,
            "- `out/4.md`, 25000 bytes, sha256 ${digest("out/4.md")}, no room left under the 60000 character cap of a comment",
        )
    }

    @Test
    fun `a file that cannot be inlined is listed with its reason`() {
        val binary = AttachedFile(DeliverableFile("out/logo.png", 120, digest("out/logo.png")), text = null, reason = "not UTF-8 text")

        val text = AttachComment.build("p-1", "a1", listOf(binary))

        assertEquals(
            "Loop attached the deliverable of p-1, verified in attempt a1: 1 file.\n\n" +
                "Listed, not inlined, the workspace is kept until the issue is delivered:\n" +
                "- `out/logo.png`, 120 bytes, sha256 ${digest("out/logo.png")}, not UTF-8 text",
            text,
        )
    }

    @Test
    fun `many files stay under the cap, the listing ending with how many more there are`() {
        val files = (1..400).map { file("out/post-${it.toString().padStart(3, '0')}.md", "x".repeat(1_000)) }

        val text = AttachComment.build("p-1", "a1", files)

        assertTrue(text.length <= AttachComment.MAX_CHARS, "${text.length}")
        assertContains(text, "`out/post-001.md`, 1000 bytes:")
        val listed = text.lines().count { it.startsWith("- `out/post-") }
        val inlined = text.lines().count { it.startsWith("`out/post-") }
        val more = Regex("- and (\\d+) more files, listed in the deliverable evidence of the verdict").find(text)
        assertTrue(more != null, text.takeLast(500))
        assertEquals(400, inlined + listed + more.groupValues[1].toInt())
    }

    @Test
    fun `a path holding a backtick is shown in a longer code span, and a text with no final newline still closes its fence`() {
        val text = AttachComment.build("p-1", "a1", listOf(file("out/a`b.md", "no newline")))

        assertContains(text, "`` out/a`b.md ``, 10 bytes:\n\n```\nno newline\n```")
    }
}
