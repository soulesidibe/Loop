package loop.tracker.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import loop.api.Issue
import loop.api.IssueComment
import loop.api.TrackerError
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FilesTrackerTest {
    @TempDir
    lateinit var issuesDir: Path

    @TempDir
    lateinit var lockDir: Path

    private val malformed = mutableListOf<TrackerError.Malformed>()

    private val warnings = mutableListOf<String>()

    private fun tracker() = FilesTracker(issuesDir, lockDir, onMalformed = { malformed += it }, onWarning = { warnings += it })

    private fun issueFile(
        id: String,
        state: String = "todo",
        labels: String = "[ready-for-agent]",
        body: String = "## What\n\nDo $id.\n",
        extra: String = "",
        blockedBy: String = "[]",
    ): Path {
        val file = issuesDir.resolve("$id-slug.md")
        file.writeText(
            "---\nid: $id\ntitle: \"Title of $id\"\nstate: $state\npriority: 2\nlabels: $labels\nblocked_by: $blockedBy\n$extra---\n\n$body",
        )
        return file
    }

    private suspend fun FilesTracker.todo(id: String): Issue = fetchIssuesByStates(listOf("todo")).getOrThrow().single { it.id == id }

    @Test
    fun `an issue blocked by a non done issue is not dispatchable`() =
        runTest {
            issueFile("a-1", state = "in_progress")
            issueFile("a-2", blockedBy = "[a-1]")

            val issue = tracker().todo("a-2")

            assertEquals(false, issue.dispatchable)
            assertEquals("blocked_by a-1 (in_progress)", issue.blockedReason)
        }

    @Test
    fun `it becomes dispatchable once the blocker is done`() =
        runTest {
            issueFile("a-1", state = "in_progress")
            issueFile("a-2", blockedBy = "[a-1]")
            val tracker = tracker()
            assertEquals(false, tracker.todo("a-2").dispatchable)

            tracker.moveIssue("a-1", "done").getOrThrow()

            assertEquals(true, tracker.todo("a-2").dispatchable)
        }

    @Test
    fun `a canceled blocker keeps the issue blocked`() =
        runTest {
            issueFile("a-1", state = "canceled")
            issueFile("a-2", blockedBy = "[a-1]")

            assertEquals("blocked_by a-1 (canceled)", tracker().todo("a-2").blockedReason)
        }

    @Test
    fun `a missing blocker keeps the issue blocked and warns once`() =
        runTest {
            issueFile("a-2", blockedBy = "[a-9]")
            val tracker = tracker()

            assertEquals("blocked_by a-9 (missing)", tracker.todo("a-2").blockedReason)
            assertEquals("blocked_by a-9 (missing)", tracker.todo("a-2").blockedReason)

            assertEquals(listOf("a-2: blocked_by a-9 has no issue file, so a-2 stays blocked."), warnings)
        }

    @Test
    fun `a malformed blocker file keeps the issue blocked and warns once`() =
        runTest {
            issuesDir.resolve("a-1-slug.md").writeText("---\nid: a-1\nstate: done\n---\n")
            issueFile("a-2", blockedBy = "[a-1]")
            val tracker = tracker()

            assertEquals("blocked_by a-1 (malformed)", tracker.todo("a-2").blockedReason)
            assertEquals("blocked_by a-1 (malformed)", tracker.todo("a-2").blockedReason)

            assertEquals(listOf("a-2: blocked_by a-1 has an issue file that cannot be read, so a-2 stays blocked."), warnings)
        }

    @Test
    fun `fetch by ids computes the blocked reason too`() =
        runTest {
            issueFile("a-1", state = "review")
            issueFile("a-2", blockedBy = "[a-1]")

            val issue = tracker().fetchIssuesByIds(listOf("a-2")).getOrThrow().single()

            assertEquals("blocked_by a-1 (review)", issue.blockedReason)
        }

    @Test
    fun `the first unresolved blocker in blocked_by order is the reason`() =
        runTest {
            issueFile("a-1", state = "done")
            issueFile("a-2", state = "review")
            issueFile("a-3", state = "todo")
            issueFile("a-4", blockedBy = "[a-1, a-3, a-2]")

            assertEquals("blocked_by a-3 (todo)", tracker().todo("a-4").blockedReason)
        }

    @Test
    fun `front matter and body are read into an issue`() =
        runTest {
            issuesDir.resolve("m1-02-walking-skeleton.md").writeText(
                """
                |---
                |id: m1-02
                |title: "Walking skeleton: one issue from todo to review, traced"
                |state: todo
                |priority: 2
                |labels: [Ready-For-Agent, AFK]
                |blocked_by: [m1-01]
                |---
                |
                |## What
                |
                |Thin on purpose.
                |
                """.trimMargin(),
            )

            val issues = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow()

            assertEquals(
                listOf(
                    Issue(
                        id = "m1-02",
                        identifier = "m1-02",
                        title = "Walking skeleton: one issue from todo to review, traced",
                        state = "todo",
                        priority = 2,
                        labels = listOf("ready-for-agent", "afk"),
                        blockedBy = listOf("m1-01"),
                        description = "## What\n\nThin on purpose.",
                        createdAt = null,
                        blockedReason = "blocked_by m1-01 (missing)",
                    ),
                ),
                issues,
            )
        }

    @Test
    fun `missing or non integer priority is null and missing lists are empty`() =
        runTest {
            issuesDir.resolve("a-1.md").writeText("---\nid: a-1\ntitle: A\nstate: todo\npriority: high\n---\n")

            val issue = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow().single()

            assertNull(issue.priority)
            assertEquals(emptyList(), issue.labels)
            assertEquals(emptyList(), issue.blockedBy)
            assertNull(issue.createdAt)
        }

    @Test
    fun `labels are trimmed lowercased and blanks dropped`() =
        runTest {
            issueFile("a-1", labels = "[\" Ready-For-Agent \", \"\", \"  \", AFK]")

            val issue = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow().single()

            assertEquals(listOf("ready-for-agent", "afk"), issue.labels)
        }

    @Test
    fun `fetch by states returns only issues in those states`() =
        runTest {
            issueFile("a-1", state = "todo")
            issueFile("a-2", state = "review")
            issueFile("a-3", state = "in_progress")
            issueFile("a-4", state = "done")

            val ids =
                tracker()
                    .fetchIssuesByStates(listOf("todo", "in_progress"))
                    .getOrThrow()
                    .map { it.id }
                    .sorted()

            assertEquals(listOf("a-1", "a-3"), ids)
        }

    @Test
    fun `fetch by states skips a malformed file and reports it`() =
        runTest {
            issueFile("a-1")
            issuesDir.resolve("no-title.md").writeText("---\nid: a-2\nstate: todo\n---\nbody\n")
            issuesDir.resolve("broken-yaml.md").writeText("---\nid: [a-3\nstate: todo\n---\n")
            issuesDir.resolve("no-front-matter.md").writeText("just text\n")

            val ids = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow().map { it.id }

            assertEquals(listOf("a-1"), ids)
            assertEquals(
                listOf("broken-yaml.md", "no-front-matter.md", "no-title.md"),
                malformed.map { Path.of(it.source).name }.sorted(),
            )
        }

    @Test
    fun `fetch by ids returns the requested issues`() =
        runTest {
            issueFile("a-1")
            issueFile("a-2", state = "review")
            issueFile("a-3")

            val ids =
                tracker()
                    .fetchIssuesByIds(listOf("a-2", "a-3"))
                    .getOrThrow()
                    .map { it.id }
                    .sorted()

            assertEquals(listOf("a-2", "a-3"), ids)
        }

    @Test
    fun `17_3 scope is every md file directly in the folder, without dot files and subfolders`() =
        runTest {
            issueFile("a-1")
            issuesDir.resolve("notes.txt").writeText("---\nid: a-2\ntitle: Not an issue\nstate: todo\n---\n")
            issuesDir.resolve(".a-3-hidden.md").writeText("---\nid: a-3\ntitle: Hidden\nstate: todo\n---\n")
            val specs = issuesDir.resolve("specs").also { Files.createDirectory(it) }
            specs.resolve("a-4-spec.md").writeText("---\nid: a-4\ntitle: A spec\nstate: todo\n---\n")

            val ids = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow().map { it.id }

            assertEquals(listOf("a-1"), ids)
            assertEquals(emptyList(), tracker().fetchIssuesByIds(listOf("a-2", "a-3", "a-4")).getOrThrow())
            assertEquals(emptyList(), malformed)
        }

    @Test
    fun `17_3 unusable optional metadata normalizes to null or empty and keeps the required fields`() =
        runTest {
            issuesDir.resolve("a-1.md").writeText(
                "---\nid: a-1\ntitle: Kept title\nstate: todo\npriority: [1, 2]\nlabels: null\nblocked_by: ~\n---\n\n",
            )

            val issue = tracker().fetchIssuesByStates(listOf("todo")).getOrThrow().single()

            assertEquals(Issue("a-1", "a-1", "Kept title", "todo", null, emptyList(), emptyList(), null, null), issue)
        }

    @Test
    fun `17_3 the published adapter profile covers config, scope, normalization, tools and errors`() {
        val profile = Path.of(System.getProperty("loop.adapterProfile")).readText()
        val headings = profile.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

        for (section in listOf("Configuration", "Scope", "Normalization", "States", "Writes and the agent tool", "Errors")) {
            assertTrue(section in headings, "the profile has no \"## $section\" section: $headings")
        }
        for (state in FilesTracker.DEFINED_STATES) {
            assertTrue("| `$state` |" in profile, "the profile does not document the state $state")
        }
        for (error in listOf("Unavailable", "Malformed", "NotFound", "BlankComment", "UndefinedState")) {
            assertTrue("| `$error` |" in profile, "the profile does not map the error $error")
        }
    }

    @Test
    fun `m4-17 the area front matter is read normalized, a list or one path, and no area is empty`() =
        runTest {
            issueFile("a-1", extra = "area: [spec-core/, ./docs/agents, spec-core]\n")
            issueFile("a-2", extra = "area:\n  - plugins/tracker-files/src/main/kotlin/loop/tracker/files/FilesTracker.kt\n")
            issueFile("a-3", extra = "area: cli\n")
            issueFile("a-4", extra = "area: []\n")
            issueFile("a-5", extra = "area:\n")
            issueFile("a-6")
            val tracker = tracker()

            assertEquals(listOf("spec-core", "docs/agents"), tracker.todo("a-1").area)
            assertEquals(listOf("plugins/tracker-files/src/main/kotlin/loop/tracker/files/FilesTracker.kt"), tracker.todo("a-2").area)
            assertEquals(listOf("cli"), tracker.todo("a-3").area)
            listOf("a-4", "a-5", "a-6").forEach { assertEquals(emptyList(), tracker.todo(it).area, it) }
            assertEquals(listOf("spec-core", "docs/agents"), tracker.fetchIssuesByIds(listOf("a-1")).getOrThrow().single().area)
            assertEquals(emptyList(), malformed)
        }

    @Test
    fun `m4-17 a malformed area makes a malformed issue, skipped by states and failing a read by ids`() =
        runTest {
            issueFile("a-1")
            issueFile("a-2", extra = "area: [spec-core/*]\n")
            issueFile("a-3", extra = "area: [/etc]\n")
            issueFile("a-4", extra = "area: [../other-repo]\n")
            issueFile("a-5", extra = "area: [spec-core, [nested]]\n")
            issueFile("a-6", extra = "area: {spec-core: true}\n")
            issueFile("a-7", extra = "area: [\"\"]\n")
            val tracker = tracker()

            assertEquals(listOf("a-1"), tracker.fetchIssuesByStates(listOf("todo")).getOrThrow().map { it.id })
            assertEquals(
                (2..7).map { "a-$it-slug.md" },
                malformed.map { Path.of(it.source).name }.sorted(),
            )
            assertIs<TrackerError.Malformed>(tracker.fetchIssuesByIds(listOf("a-2")).exceptionOrNull())
        }

    @Test
    fun `fetch by ids fails when a file is malformed`() =
        runTest {
            issueFile("a-1")
            issuesDir.resolve("a-2-slug.md").writeText("---\nid: a-2\nstate: todo\n---\n")

            val error = tracker().fetchIssuesByIds(listOf("a-1", "a-2")).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error)
        }

    @Test
    fun `defined states are the six files states`() {
        assertEquals(
            listOf("todo", "in_progress", "review", "needs_info", "done", "canceled"),
            tracker().definedStates,
        )
    }

    @Test
    fun `move rewrites only the state line of the front matter`() =
        runTest {
            val file =
                issueFile(
                    "a-1",
                    body = "## What\n\nstate: todo\n\ttrailing spaces   \r\nno final newline",
                    extra = "# state: todo in a comment\n",
                )
            val before = file.readText()

            tracker().moveIssue("a-1", "review").getOrThrow()

            val expected = before.replaceFirst("\nstate: todo\n", "\nstate: review\n")
            assertContentEquals(expected.toByteArray(), file.readBytes())
            assertEquals(listOf(file.name), issuesDir.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `moved issue is fetched in its new state`() =
        runTest {
            issueFile("a-1")
            val tracker = tracker()

            tracker.moveIssue("a-1", "review").getOrThrow()

            assertEquals(
                "review",
                tracker
                    .fetchIssuesByIds(listOf("a-1"))
                    .getOrThrow()
                    .single()
                    .state,
            )
        }

    @Test
    fun `an undefined state is refused and the file is untouched`() =
        runTest {
            val file = issueFile("a-1")
            val before = file.readBytes()

            val error = tracker().moveIssue("a-1", "reviewed").exceptionOrNull()

            assertIs<TrackerError.UndefinedState>(error)
            assertEquals("reviewed", error.state)
            assertContentEquals(before, file.readBytes())
        }

    @Test
    fun `moving an unknown issue fails with not found`() =
        runTest {
            issueFile("a-1")

            val error = tracker().moveIssue("a-9", "review").exceptionOrNull()

            assertIs<TrackerError.NotFound>(error)
        }

    @Test
    fun `a label write rewrites only the labels line of the front matter`() =
        runTest {
            val file = issueFile("a-1", labels = "[ready-for-agent, afk]", body = "## What\n\nlabels: [x]\r\nno final newline")
            val before = file.readText()

            tracker().updateLabels("a-1", add = listOf("needs-info"), remove = listOf("AFK")).getOrThrow()

            val expected = before.replaceFirst("\nlabels: [ready-for-agent, afk]\n", "\nlabels: [ready-for-agent, needs-info]\n")
            assertContentEquals(expected.toByteArray(), file.readBytes())
            assertEquals(listOf(file.name), issuesDir.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `a block list of labels is rewritten as one flow list`() =
        runTest {
            val file = issueFile("a-1", labels = "\n  - ready-for-agent\n  - \"p1: urgent\"")

            tracker().updateLabels("a-1", add = listOf("needs-info")).getOrThrow()

            assertContains(file.readText(), "\nlabels: [ready-for-agent, \"p1: urgent\", needs-info]\nblocked_by: []\n")
            assertEquals(listOf("ready-for-agent", "p1: urgent", "needs-info"), tracker().todo("a-1").labels)
        }

    @Test
    fun `a missing labels key is added at the end of the front matter`() =
        runTest {
            val file = issuesDir.resolve("a-1-slug.md")
            file.writeText("---\nid: a-1\ntitle: A\nstate: todo\n---\n\nBody.\n")

            tracker().updateLabels("a-1", add = listOf("needs-info")).getOrThrow()

            assertEquals("---\nid: a-1\ntitle: A\nstate: todo\nlabels: [needs-info]\n---\n\nBody.\n", file.readText())
        }

    @Test
    fun `a label write that changes nothing leaves the file untouched`() =
        runTest {
            val file = issueFile("a-1", labels = "[Ready-For-Agent]")
            val before = file.readBytes()
            val modified = Files.getLastModifiedTime(file)
            Thread.sleep(20)

            tracker().updateLabels("a-1", add = listOf("ready-for-agent", " "), remove = listOf("afk")).getOrThrow()

            assertContentEquals(before, file.readBytes())
            assertEquals(modified, Files.getLastModifiedTime(file))
        }

    @Test
    fun `a label write on a malformed labels entry fails and the file is untouched`() =
        runTest {
            val file = issueFile("a-1")
            val broken = file.readText().replace("labels: [ready-for-agent]", "labels: {a: b}")
            file.writeText(broken)

            val error = tracker().updateLabels("a-1", add = listOf("needs-info")).exceptionOrNull()

            // The file no longer parses as an issue, so its id is not found.
            assertIs<TrackerError>(error)
            assertEquals(broken, file.readText())
        }

    /** Adds `needs-info` to `a-1` and checks the labels read back in order, every other field as it was. */
    private suspend fun assertLabelWriteReadsBack(
        file: Path,
        expected: List<String>,
    ) {
        val before = tracker().todo("a-1")

        tracker().updateLabels("a-1", add = listOf("needs-info")).getOrThrow()

        assertEquals(before.copy(labels = expected), tracker().todo("a-1"), file.readText())
    }

    @Test
    fun `a blank line inside a block list of labels is rewritten whole`() =
        runTest {
            val file = issueFile("a-1", labels = "\n  - ready-for-agent\n\n  - afk")

            assertLabelWriteReadsBack(file, listOf("ready-for-agent", "afk", "needs-info"))
            assertContains(file.readText(), "\nlabels: [ready-for-agent, afk, needs-info]\nblocked_by: []\n")
        }

    @Test
    fun `a column 0 comment inside a block list of labels is replaced and a comment after it is kept`() =
        runTest {
            val file = issueFile("a-1", labels = "\n  - ready-for-agent\n# between\n  - afk\n# after", blockedBy = "[b-1]")
            issueFile("b-1", state = "done")

            assertLabelWriteReadsBack(file, listOf("ready-for-agent", "afk", "needs-info"))
            assertContains(file.readText(), "\nlabels: [ready-for-agent, afk, needs-info]\n# after\nblocked_by: [b-1]\n")
        }

    @Test
    fun `a quoted labels key is rewritten in place and not added twice`() =
        runTest {
            val file = issueFile("a-1").also { it.writeText(it.readText().replace("\nlabels:", "\n\"labels\":")) }

            assertLabelWriteReadsBack(file, listOf("ready-for-agent", "needs-info"))
            assertEquals(1, file.readText().lines().count { "labels" in it }, file.readText())
        }

    @Test
    fun `a labels key with a space before the colon is rewritten in place and not added twice`() =
        runTest {
            val file = issueFile("a-1").also { it.writeText(it.readText().replace("\nlabels:", "\nlabels :")) }

            assertLabelWriteReadsBack(file, listOf("ready-for-agent", "needs-info"))
            assertEquals(1, file.readText().lines().count { "labels" in it }, file.readText())
        }

    @Test
    fun `a trailing comment on the labels entry is replaced with the entry`() =
        runTest {
            val file = issueFile("a-1", labels = "[ready-for-agent] # set by triage")
            val before = file.readText()

            assertLabelWriteReadsBack(file, listOf("ready-for-agent", "needs-info"))
            val expected = before.replace("\nlabels: [ready-for-agent] # set by triage\n", "\nlabels: [ready-for-agent, needs-info]\n")
            assertEquals(expected, file.readText())
        }

    @Test
    fun `a label with a trailing space stays quoted so it reads back as written`() =
        runTest {
            val file = issueFile("a-1", labels = "[\"afk \"]")

            assertLabelWriteReadsBack(file, listOf("afk", "needs-info"))
            assertContains(file.readText(), "\nlabels: [\"afk \", needs-info]\n")
        }

    @Test
    fun `a label write whose result would not parse fails as malformed and the file is untouched`() =
        runTest {
            // The labels entry ends a flow map that spans two lines, so a flow list line in its place leaves the map open.
            val file = issuesDir.resolve("a-1-slug.md")
            file.writeText("---\n{id: a-1, title: A, state: todo,\n labels: [afk]}\n---\n\nBody.\n")
            val before = file.readBytes()

            val error = tracker().updateLabels("a-1", add = listOf("needs-info")).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error)
            assertContentEquals(before, file.readBytes())
            assertEquals(listOf(file.name), issuesDir.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `a label write that would drop another field fails as malformed and the file is untouched`() =
        runTest {
            // The whole front matter is one flow map on the labels line, so a flow list line in its place drops id, title and state.
            val file = issuesDir.resolve("a-1-slug.md")
            file.writeText("---\n{id: a-1, title: A, state: todo, labels: [afk]}\n---\n\nBody.\n")
            val before = file.readBytes()

            val error = tracker().updateLabels("a-1", add = listOf("needs-info")).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error)
            assertContentEquals(before, file.readBytes())
        }

    private val head = "---\nid: a-1\ntitle: \"Title of a-1\"\nstate: todo\npriority: 2\nlabels: [ready-for-agent]\nblocked_by: []\n---\n"

    private fun rawIssue(
        rest: String,
        frontMatter: String = head,
    ): Path = issuesDir.resolve("a-1-slug.md").also { it.writeBytes((frontMatter + rest).toByteArray()) }

    private fun comment(
        text: String,
        author: String = "agent 7f3a1c2e",
        at: String = "2026-11-08T16:00:00Z",
    ) = IssueComment(author, Instant.parse(at), text)

    private suspend fun FilesTracker.commentOn(text: String) = addComment("a-1", comment(text)).getOrThrow()

    @Test
    fun `comment is appended under comments with author and time and the rest is unchanged`() =
        runTest {
            val prefix = "${head}\n## What\n\nDo it.\n\n## Comments\n\nolder\n"
            val suffix = "\n## Progress\n\nlater\n"
            val file = rawIssue("\n## What\n\nDo it.\n\n## Comments\n\nolder\n\n## Progress\n\nlater\n")

            tracker().commentOn("Should it include archived items?")

            val entry = "\n2026-11-08 16:00Z agent 7f3a1c2e: Should it include archived items?\n"
            assertContentEquals((prefix + entry + suffix).toByteArray(), file.readBytes())
        }

    @Test
    fun `a second comment adds a second entry`() =
        runTest {
            val file = rawIssue("\n## Comments\n\n")
            val tracker = tracker()

            tracker.commentOn("first")
            tracker.addComment("a-1", comment("second", author = "soule", at = "2026-11-09T08:30:00Z")).getOrThrow()

            assertEquals(
                "${head}\n## Comments\n\n2026-11-08 16:00Z agent 7f3a1c2e: first\n\n2026-11-09 08:30Z soule: second\n",
                file.readText(),
            )
        }

    @Test
    fun `multi line comment is indented and blank lines are dropped`() =
        runTest {
            val file = rawIssue("\n## Comments\n")

            tracker().commentOn("line one  \n\nline two\n   \n  line three\n\n")

            assertEquals(
                "${head}\n## Comments\n2026-11-08 16:00Z agent 7f3a1c2e: line one\n  line two\n    line three\n",
                file.readText(),
            )
        }

    @Test
    fun `body text that looks like a heading or a dated entry does not confuse a later append`() =
        runTest {
            val file = rawIssue("\n## Comments\n\n")
            val tracker = tracker()

            tracker.commentOn("see below\n## Progress\n2026-11-09 soule: x\n```\nopen fence")
            tracker.commentOn("second")

            assertEquals(
                "${head}\n## Comments\n\n" +
                    "2026-11-08 16:00Z agent 7f3a1c2e: see below\n  ## Progress\n  2026-11-09 soule: x\n  ```\n  open fence\n\n" +
                    "2026-11-08 16:00Z agent 7f3a1c2e: second\n",
                file.readText(),
            )
        }

    @Test
    fun `crlf file keeps crlf`() =
        runTest {
            val crlf = head.replace("\n", "\r\n")
            val file = rawIssue("\r\n## Comments\r\n\r\nold\r\n\r\n## Progress\r\n", crlf)

            tracker().commentOn("a\nb")

            assertEquals(
                "${crlf}\r\n## Comments\r\n\r\nold\r\n\r\n2026-11-08 16:00Z agent 7f3a1c2e: a\r\n  b\r\n\r\n## Progress\r\n",
                file.readText(),
            )
        }

    @Test
    fun `final newline is added only when missing`() =
        runTest {
            val file = rawIssue("\n## Comments\n\nold")

            tracker().commentOn("new")

            assertEquals("${head}\n## Comments\n\nold\n\n2026-11-08 16:00Z agent 7f3a1c2e: new\n", file.readText())
        }

    @Test
    fun `a comments heading inside a fence is ignored`() =
        runTest {
            val file = rawIssue("\n```\n## Comments\n```\n\n~~~\n## Comments\n~~~\n\n## Comments\n\nold\n")

            tracker().commentOn("new")

            assertEquals(
                "${head}\n```\n## Comments\n```\n\n~~~\n## Comments\n~~~\n\n## Comments\n\nold\n\n2026-11-08 16:00Z agent 7f3a1c2e: new\n",
                file.readText(),
            )
        }

    @Test
    fun `a comments heading in the front matter is ignored`() =
        runTest {
            val frontMatter = head.replace("blocked_by: []\n", "blocked_by: []\n## Comments\n")
            val file = rawIssue("\n## Comments\n", frontMatter)

            tracker().commentOn("new")

            assertEquals("${frontMatter}\n## Comments\n2026-11-08 16:00Z agent 7f3a1c2e: new\n", file.readText())
        }

    @Test
    fun `comments directly followed by progress gets a blank line before the heading`() =
        runTest {
            val file = rawIssue("\n## Comments\n## Progress\n\nx\n")

            tracker().commentOn("new")

            assertEquals(
                "${head}\n## Comments\n2026-11-08 16:00Z agent 7f3a1c2e: new\n\n## Progress\n\nx\n",
                file.readText(),
            )
        }

    @Test
    fun `missing comments section is created before progress`() =
        runTest {
            val file = rawIssue("\n## What\n\nDo it.\n\n## Progress\n\nx\n")

            tracker().commentOn("new")

            assertEquals(
                "${head}\n## What\n\nDo it.\n\n## Comments\n\n2026-11-08 16:00Z agent 7f3a1c2e: new\n\n## Progress\n\nx\n",
                file.readText(),
            )
        }

    @Test
    fun `missing comments section is created at the end without progress`() =
        runTest {
            val file = rawIssue("\n## What\n\nDo it.")

            tracker().commentOn("new")

            assertEquals(
                "${head}\n## What\n\nDo it.\n\n## Comments\n\n2026-11-08 16:00Z agent 7f3a1c2e: new\n",
                file.readText(),
            )
        }

    @Test
    fun `an unclosed fence before the insert point is malformed and the file is untouched`() =
        runTest {
            val file = rawIssue("\n## What\n\n```\ncode\n\n## Comments\n\nold\n")
            val before = file.readBytes()

            val error = tracker().addComment("a-1", comment("new")).exceptionOrNull()

            assertIs<TrackerError.Malformed>(error)
            assertContentEquals(before, file.readBytes())
        }

    @Test
    fun `progress heading with a trailing space still receives the new comments section before it`() =
        runTest {
            val file = rawIssue("\n## What\n\nDo it.\n\n## Progress \n\nx\n")

            tracker().commentOn("new")

            assertEquals(
                "${head}\n## What\n\nDo it.\n\n## Comments\n\n2026-11-08 16:00Z agent 7f3a1c2e: new\n\n## Progress \n\nx\n",
                file.readText(),
            )
        }

    @Test
    fun `the lock file lives in the lock folder and not in the issues folder`() =
        runTest {
            rawIssue("\n## Comments\n")

            tracker().commentOn("new")

            assertEquals(1, lockDir.listDirectoryEntries("tracker-*.lock").size)
            assertTrue(issuesDir.listDirectoryEntries().none { it.name.endsWith(".lock") })
        }

    @Test
    fun `commenting on an unknown issue fails with not found`() =
        runTest {
            rawIssue("\n## Comments\n")

            val error = tracker().addComment("a-9", comment("x")).exceptionOrNull()

            assertIs<TrackerError.NotFound>(error)
        }

    @Test
    fun `a blank comment is refused and the file is untouched`() =
        runTest {
            val file = rawIssue("\n## Comments\n")
            val before = file.readBytes()

            val error = tracker().addComment("a-1", comment(" \n\t\n")).exceptionOrNull()

            assertIs<TrackerError.BlankComment>(error)
            assertContentEquals(before, file.readBytes())
        }

    @Test
    fun `concurrent comments and a move all survive`() =
        runTest {
            val file = rawIssue("\n## Comments\n")
            val tracker = tracker()

            withContext(Dispatchers.IO) {
                (1..20)
                    .map { i -> async { tracker.addComment("a-1", comment("c$i")).getOrThrow() } }
                    .plus(async { tracker.moveIssue("a-1", "review").getOrThrow() })
                    .awaitAll()
            }

            val text = file.readText()
            assertTrue("\nstate: review\n" in text)
            for (i in 1..20) assertTrue("agent 7f3a1c2e: c$i\n" in text, "missing c$i")
            assertEquals(listOf(file.name), issuesDir.listDirectoryEntries().map { it.name })
        }

    @Test
    fun `a concurrent reader never sees a torn file`() =
        runTest {
            val bigBody = "line of the body that makes the file large enough to tear\n".repeat(20_000)
            val file = issueFile("a-1", body = bigBody)
            val todo = file.readBytes()
            val review = file.readText().replaceFirst("\nstate: todo\n", "\nstate: review\n").toByteArray()
            val done = AtomicBoolean(false)
            val reads = AtomicInteger(0)
            val failure = AtomicReference<String?>(null)

            val reader =
                Thread {
                    while (!done.get() && failure.get() == null) {
                        try {
                            val bytes = Files.readAllBytes(file)
                            if (!bytes.contentEquals(todo) && !bytes.contentEquals(review)) {
                                failure.set("torn read of ${bytes.size} bytes")
                            }
                            reads.incrementAndGet()
                        } catch (e: java.io.IOException) {
                            failure.set("read failed: $e")
                        }
                    }
                }
            reader.start()
            val tracker = tracker()
            try {
                repeat(100) { i ->
                    tracker.moveIssue("a-1", if (i % 2 == 0) "review" else "todo").getOrThrow()
                }
            } finally {
                done.set(true)
                reader.join()
            }

            assertNull(failure.get())
            assertTrue(reads.get() > 0)
        }
}
