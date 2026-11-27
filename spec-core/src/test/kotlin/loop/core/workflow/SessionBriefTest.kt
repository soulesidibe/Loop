package loop.core.workflow

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.Issue
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.template.TemplateEngine
import loop.core.verify.LazyFetchTrap
import loop.core.verify.WorkspaceGit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m3-10: the session brief of plan 9.2, built from what Loop recorded, the agent's notes last and unverified. */
class SessionBriefTest {
    private val project = ProjectId("p1")
    private val root: Path = createTempDirectory("loop-session-brief").toRealPath()
    private val workspace: Path = root.resolve("m3-10").createDirectories()
    private val git = WorkspaceGit()

    private val issue =
        Issue(
            id = "id-m3-10",
            identifier = "m3-10",
            title = "Session brief built from facts",
            state = "todo",
            priority = 2,
            labels = listOf("ready-for-agent"),
            blockedBy = emptyList(),
            description =
                "## What\n\nBuild the brief.\n\n## Acceptance criteria\n\n- [ ] The brief holds five parts.\n- [ ] A missing part gives one line.\n\n## Comments\n\nnone",
            createdAt = null,
        )

    private val attempt = AttemptId("a1")

    private fun gitIn(vararg args: String): String {
        val outcome = git.run(workspace, *args)
        check(outcome.code == 0) { "git ${args.joinToString(" ")}: ${outcome.firstLine()}" }
        return outcome.stdout.trim()
    }

    /** A work tree with a base commit, one commit of work on top and an uncommitted change; returns the base sha. */
    private fun gitWorkspace(): String {
        gitIn("init", "-q", "-b", "main")
        gitIn("config", "user.email", "loop@example.com")
        gitIn("config", "user.name", "Loop")
        workspace.resolve("README.md").writeText("base\n")
        gitIn("add", ".")
        gitIn("commit", "-q", "-m", "base commit")
        val base = gitIn("rev-parse", "HEAD")
        workspace.resolve("Brief.kt").writeText("class Brief\n")
        gitIn("add", ".")
        gitIn("commit", "-q", "-m", "m3-10: add the brief")
        workspace.resolve("README.md").writeText("base\nchanged\n")
        return base
    }

    private fun event(
        kind: String,
        data: Map<String, String>,
    ): RecordedEvent =
        RecordedEvent(ts = Instant.parse("2026-11-16T10:00:00Z"), projectId = project, kind = kind, attemptId = attempt, data = data)

    private val verification: List<RecordedEvent> =
        listOf(
            event("result_submitted", mapOf("to" to "review", "status" to "done", "result" to "a1.result-1.json")),
            event("check_finished", mapOf("check" to "0", "command" to "diff_policy base=main", "exit_code" to "0")),
            event("check_finished", mapOf("check" to "1", "command" to "./gradlew build", "exit_code" to "1")),
            event("check_finished", mapOf("check" to "1", "command" to "./gradlew build", "exit_code" to "1", "rerun" to "true")),
            event(
                "check_finished",
                mapOf("check" to "0", "command" to "/home/holdout/secret.sh", "holdout" to "true", "result" to "failed"),
            ),
            event("verdict", mapOf("verdict" to "refuted", "checks" to "2")),
        )

    private fun row(
        number: Int,
        id: AttemptId = attempt,
        tokens: LedgerTokens? = LedgerTokens(1000, 200, 300, 0),
        cost: Double? = 1.25,
    ): LedgerRow =
        LedgerRow(
            LedgerAttempt(
                projectId = project,
                runId = null,
                issueId = issue.id,
                identifier = issue.identifier,
                attemptId = id,
                setup = LedgerSetup("files", "code", "claude-code", null, "t", null),
                startedAt = Instant.parse("2026-11-16T09:00:00Z"),
                finishedAt = Instant.parse("2026-11-16T10:00:00Z"),
                outcome = "failed",
                reason = "refuted",
                turns = 3,
                issueState = "todo",
                tokens = tokens,
                costUsd = cost,
                checks = 2,
                guards = null,
                verdict = "refuted",
            ),
            number = number,
            humanTouches = 0,
        )

    private fun ledger(rows: Result<List<LedgerRow>>): AttemptLedger =
        object : AttemptLedger by AttemptLedger.NONE {
            override suspend fun attemptRows(
                projectId: ProjectId,
                issueId: String,
            ): Result<List<LedgerRow>> = rows
        }

    private fun brief(
        rows: Result<List<LedgerRow>> = Result.success(listOf(row(1))),
        events: Map<AttemptId, List<RecordedEvent>> = mapOf(attempt to verification),
        results: Map<String, String> =
            mapOf("a1.result-1.json" to """{"status":"done","notes_for_next_attempt":"Fix the build in Brief.kt next."}"""),
        git: WorkspaceGit = this.git,
        redactor: Redactor = Redactor.NONE,
    ): SessionBrief =
        SessionBrief(
            git = git,
            ledger = ledger(rows),
            events = { id -> Result.success(events[id].orEmpty()) },
            savedResult = { ref -> results[ref] ?: throw IOException("no saved result $ref") },
            redactor = redactor,
        )

    private fun assertInOrder(
        text: String,
        vararg parts: String,
    ) {
        val positions = parts.map { part -> text.indexOf(part).also { assertTrue(it >= 0, "missing \"$part\" in:\n$text") } }
        assertEquals(positions.sorted(), positions, "out of order in:\n$text")
    }

    @Test
    fun `the brief holds the five parts in order, the agent's notes last and marked unverified`() =
        runTest {
            val base = gitWorkspace()
            workspace.resolve(".loop").createDirectories()
            workspace.resolve(".loop/progress.md").writeText("- [x] tests written\n- [ ] build green\n")

            val text = brief().build(project, issue, workspace, base, findings = "Check: ./gradlew build\nExit code: 1")

            assertInOrder(
                text,
                "### Goal and acceptance criteria",
                "### Workspace state",
                "### Checks",
                "### Counters",
                "### Agent notes, unverified",
            )
            // 1: the goal and the acceptance criteria, nothing of the other sections.
            assertInOrder(
                text,
                "m3-10: Session brief built from facts",
                "- [ ] The brief holds five parts.",
                "- [ ] A missing part gives one line.",
            )
            assertFalse(text.contains("## Comments"), text)
            // 2: the commits and the diff stat against the base, and the uncommitted change.
            assertInOrder(text, "Base: $base", "m3-10: add the brief", "Brief.kt (+1 -0)", "README.md")
            assertFalse(text.contains("base commit"), text)
            // 3: the latest result of every check, a hidden check without its command, the verdict and the findings.
            assertInOrder(
                text,
                "Last verdict: refuted, attempt 1",
                "- check 0, diff_policy base=main: passed",
                "- check 1, ./gradlew build: failed with exit code 1",
                "- hidden check: failed",
                "Exit code: 1",
            )
            assertFalse(text.contains("secret.sh"), text)
            // 4: the counters.
            assertInOrder(text, "- Attempts before this one: 1", "- Rotations: 0", "- Spend so far: 1500 tokens, 1.25 USD")
            // 5: the notes, after every fact.
            val notes = text.substringAfter("### Agent notes, unverified")
            assertInOrder(notes, "not checked", "Fix the build in Brief.kt next.", "- [ ] build green")
        }

    @Test
    fun `m3-14 a brief cut to fit the prompt limit keeps the newest progress and the latest findings`() =
        runTest {
            val base = gitWorkspace()
            workspace.resolve(".loop").createDirectories()
            workspace.resolve(".loop/progress.md").writeText((1..40).joinToString("\n", postfix = "\n") { "- progress step $it" })
            val findings = "Check: ./gradlew build\nExit code: 1\nOutput:\n" + (1..40).joinToString("\n") { "finding line $it" }
            val text = brief().build(project, issue, workspace, base, findings)
            val source = "Rules.\n{{ issue.identifier }}{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}"
            val template = TemplateEngine().parse(source, PromptVariables.schema).getOrThrow()
            val counter = TokenCounters.estimate
            val content = PromptContent(issue.copy(description = null), null, text)
            // Room for about half of the brief, so every part is cut.
            val limit = counter.count(text) / 2L

            val filled = assertNotNull(PromptFill.fill(template, content, 1, true, null, counter, limit, "at x"))

            assertTrue(counter.count(filled.prompt) <= limit, "${counter.count(filled.prompt)} tokens over $limit")
            val cut = assertNotNull(filled.content.sessionBrief)
            val checks = cut.substringAfter("### Checks\n").substringBefore("\n\n###").lines()
            val notes = cut.substringAfter("### Agent notes, unverified\n").lines()
            // The checks part keeps its verdict line and its newest lines, the end of the latest findings.
            assertEquals("Last verdict: refuted, attempt 1", checks.first(), checks.joinToString("\n"))
            assertTrue("finding line 40" in checks, checks.joinToString("\n"))
            assertFalse("finding line 1" in checks, checks.joinToString("\n"))
            assertTrue("(older lines cut to fit the prompt limit)" in checks, checks.joinToString("\n"))
            // The notes part keeps the warning that they are unverified and the newest progress, the last lines.
            assertTrue(notes.first().startsWith("An earlier session wrote these notes."), notes.joinToString("\n"))
            assertTrue("- progress step 40" in notes, notes.joinToString("\n"))
            assertFalse("- progress step 1" in notes, notes.joinToString("\n"))
            // The goal part lists its facts first to last, so it keeps its first lines.
            val goal = cut.substringAfter("### Goal and acceptance criteria\n").substringBefore("\n\n###").lines()
            assertEquals("m3-10: Session brief built from facts", goal.first())
            assertEquals("(cut to fit the prompt limit)", goal.last())
        }

    @Test
    fun `a part that cannot be read gives one line saying so`() =
        runTest {
            // A git that cannot start, no verification yet, a ledger that fails and no notes at all.
            val text =
                brief(
                    rows = Result.failure(IOException("the store is locked")),
                    git = WorkspaceGit(git = root.resolve("no-such-git").toString()),
                ).build(project, issue, workspace, base = "main", findings = null)

            assertInOrder(
                text,
                "### Goal and acceptance criteria",
                "Workspace state: not available, ",
                "Checks: not available, cannot read the ledger: the store is locked.",
                "Counters: not available, cannot read the ledger: the store is locked.",
                "Agent notes, unverified: not available, ",
            )
            assertFalse(text.contains("### Workspace state"), text)
            assertEquals(1, text.lines().count { it.startsWith("Workspace state:") })
        }

    @Test
    fun `no verification yet and a base the workspace does not hold each give one line`() =
        runTest {
            gitWorkspace()

            val text = brief(events = emptyMap(), results = emptyMap()).build(project, issue, workspace, base = "nope", findings = null)

            assertTrue(text.contains("Workspace state: not available, the base nope is not a commit of this workspace."), text)
            assertTrue(text.contains("Checks: not available, no earlier attempt has a verdict."), text)
            assertTrue(text.contains("### Counters"), text)
        }

    @Test
    fun `bl-246 a promisor the agent configures never runs from the git reads of the brief`() =
        runTest {
            val base = gitWorkspace()
            val trap = LazyFetchTrap(workspace, root.resolve("lazy-fetch-ran"), "refs/heads/main").arm()
            trap.proveLive()
            val env = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to root.toString())
            // The same brief without the variable runs the agent's program.
            brief(git = LazyFetchTrap.unguarded(env)).build(project, issue, workspace, base, findings = null)
            assertTrue(trap.marker.exists(), "the brief does not fetch unguarded, so the test proves nothing")
            Files.delete(trap.marker)

            brief(git = WorkspaceGit(env)).build(project, issue, workspace, base, findings = null)

            assertFalse(trap.marker.exists(), "the brief ran the agent's program")
        }

    @Test
    fun `a workspace that is not a git work tree lists its files`() =
        runTest {
            workspace.resolve("notes").createDirectories()
            workspace.resolve("notes/draft.md").writeText("draft")

            val text = brief().build(project, issue, workspace, base = "main", findings = null)

            assertInOrder(text, "### Workspace state", "not a git work tree", "- notes/draft.md")
        }

    @Test
    fun `each part is bounded, newest first`() =
        runTest {
            val base = gitWorkspace()
            repeat(SessionBrief.MAX_COMMITS + 5) { n -> gitIn("commit", "-q", "--allow-empty", "-m", "commit $n") }
            workspace.resolve(".loop").createDirectories()
            val progress = "old line\n" + "x".repeat(SessionBrief.MAX_PROGRESS_CHARS) + "\nnewest line\n"
            workspace.resolve(".loop/progress.md").writeText(progress)

            val text = brief().build(project, issue, workspace, base, findings = null)

            assertTrue(text.contains("commit ${SessionBrief.MAX_COMMITS + 4}"), text)
            assertFalse(text.contains("- commit 0\n") || text.contains(" commit 0\n"), text)
            assertTrue(text.contains("older commits left out"), text)
            assertTrue(text.contains("newest line"), text)
            assertFalse(text.contains("old line"), text)
            assertTrue(
                text.substringAfter("### Workspace state").substringBefore("### Checks").length <= SessionBrief.MAX_WORKSPACE_CHARS + 40,
            )
        }

    @Test
    fun `the brief goes through the redactor`() =
        runTest {
            val base = gitWorkspace()
            val redactor = Redactor { it.replace("Brief.kt next", "[redacted]") }

            val text = brief(redactor = redactor).build(project, issue, workspace, base, findings = null)

            assertTrue(text.contains("Fix the build in [redacted]."), text)
        }

    @Test
    fun `counters sum the spend of every earlier attempt and say when none was reported`() =
        runTest {
            gitWorkspace()
            val rows = listOf(row(1, AttemptId("a0"), tokens = null, cost = null), row(2))

            val reported = brief(rows = Result.success(rows)).build(project, issue, workspace, "main", null)
            val none =
                brief(rows = Result.success(listOf(row(1, tokens = null, cost = null)))).build(project, issue, workspace, "main", null)

            assertTrue(reported.contains("- Attempts before this one: 2"), reported)
            assertTrue(reported.contains("- Spend so far: 1500 tokens, 1.25 USD"), reported)
            assertTrue(none.contains("- Spend so far: not reported"), none)
        }

    @Test
    fun `m3-11 the rotations counter is the rotations of the current attempt`() =
        runTest {
            gitWorkspace()

            val rotated = brief().build(project, issue, workspace, "main", null, rotations = 2)
            val fresh = brief().build(project, issue, workspace, "main", null)

            assertTrue(rotated.contains("- Rotations: 2"), rotated)
            assertTrue(fresh.contains("- Rotations: 0"), fresh)
        }
}
