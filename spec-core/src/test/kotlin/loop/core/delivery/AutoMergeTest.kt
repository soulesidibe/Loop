package loop.core.delivery

import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.Mergeability
import loop.core.verify.DiffEntry
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.DenyGuard
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * m2-28: the hard conditions of an auto merge, each a veto. Each failing condition alone is named with its reason;
 * every condition holding names none.
 */
class AutoMergeTest {
    private val head = "a".repeat(40)

    private val holding =
        AutoMergeFacts(
            verifiedVerdict = true,
            hiddenChecksPassed = 1,
            holdouts = 1,
            fingerprint = MergeCheck.cleanFingerprint(head),
            mergedHead = head,
            shownHead = head,
            pullRequestNumber = 7,
            denyGuards = DenyGuard.entries.toList(),
            diff = listOf(DiffEntry("src/main/kotlin/App.kt", 10, 2), DiffEntry("src/test/kotlin/AppTest.kt", 8, 0)),
            autoMerge = AutoMergeConfig(enabled = true),
            unpinned = false,
        )

    private fun failed(facts: AutoMergeFacts): List<AutoMergeCondition> = AutoMerge.failures(facts).map { it.condition }

    @Test
    fun `every condition holding fails none`() {
        assertEquals(emptyList(), AutoMerge.failures(holding))
    }

    @Test
    fun `a verdict that is not verified, or a holdout that did not pass, fails verified`() {
        assertEquals(listOf(AutoMergeCondition.VERIFIED), failed(holding.copy(verifiedVerdict = false)))
        val holdout = AutoMerge.failures(holding.copy(hiddenChecksPassed = 0, holdouts = 2)).single()
        assertEquals(AutoMergeCondition.VERIFIED, holdout.condition)
        assertContains(holdout.reason, "0 of the 2 hidden checks")
    }

    @Test
    fun `a merged head that is not the delivered fingerprint or not the pull request head fails merge_check`() {
        assertEquals(
            listOf(AutoMergeCondition.MERGE_CHECK),
            failed(holding.copy(fingerprint = MergeCheck.cleanFingerprint("b".repeat(40)))),
        )
        val shown = AutoMerge.failures(holding.copy(shownHead = "c".repeat(40))).single()
        assertEquals(AutoMergeCondition.MERGE_CHECK, shown.condition)
        assertContains(shown.reason, "shows the head cccccccccccc")
    }

    private val ci =
        CiFacts(
            pullRequestNumber = 7,
            mergedHead = head,
            mergeability = Result.success(Mergeability(mergeable = true, state = "clean")),
            headRuns = Result.success(ActionsRuns(0, emptyList())),
            baseRuns = Result.success(ActionsRuns(0, emptyList())),
            workflows = Result.success(emptyList()),
        )

    private var nextRun = 1L

    private fun run(
        name: String,
        status: String,
        conclusion: String?,
    ): ActionsRun = ActionsRun(nextRun, name, nextRun++, "push", 1, 1, status, conclusion, "https://github.com/acme/widgets/actions/runs/1")

    private fun runs(vararg items: ActionsRun) = Result.success(ActionsRuns(items.size.toLong(), items.toList()))

    private fun ciReason(facts: CiFacts): String {
        val failure = checkNotNull(AutoMerge.ciFailure(facts)) { "ci holds on $facts" }
        assertEquals(AutoMergeCondition.CI, failure.condition)
        return failure.reason
    }

    @Test
    fun `ci is read apart, after the review, so the other conditions never name it`() {
        assertEquals(emptyList(), AutoMerge.failures(holding))
        assertEquals(null, AutoMerge.ciFailure(ci), "mergeable and clean, no check expected")
    }

    @Test
    fun `a pull request that is not mergeable and clean fails ci, an unknown state included`() {
        for (state in listOf(
            Mergeability(true, "unstable"),
            Mergeability(false, "dirty"),
            Mergeability(null, "unknown"),
            Mergeability(true, "blocked"),
        )) {
            assertContains(ciReason(ci.copy(mergeability = Result.success(state))), "pull request #7", message = "$state")
        }
        assertContains(ciReason(ci.copy(mergeability = Result.failure(IllegalStateException("503")))), "503")
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, a fresh head with no check run yet fails ci when checks are expected`() {
        assertEquals(
            "no Actions run is registered on the merged head aaaaaaaaaaaa yet, while the merged head holds the workflows .github/workflows/ci.yml",
            ciReason(ci.copy(workflows = Result.success(listOf(".github/workflows/ci.yml")))),
        )
        assertEquals(
            "no Actions run is registered on the merged head aaaaaaaaaaaa yet, while the base tip has 1 Actions runs",
            ciReason(ci.copy(baseRuns = runs(run("build", "completed", "success")))),
        )
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, a check run still running or not passed fails ci`() {
        val expected = ci.copy(workflows = Result.success(listOf(".github/workflows/ci.yml")))
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa are pending: 1 of the 2 kept runs not completed, lint (in_progress)",
            ciReason(expected.copy(headRuns = runs(run("build", "completed", "success"), run("lint", "in_progress", null)))),
        )
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa did not pass: build (failure)",
            ciReason(expected.copy(headRuns = runs(run("build", "completed", "failure")))),
        )
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa did not pass: build (mystery)",
            ciReason(ci.copy(headRuns = runs(run("build", "completed", "mystery")))),
            "an unknown conclusion is red",
        )
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa are pending: 101 runs, more than one page of 100",
            ciReason(ci.copy(headRuns = Result.success(ActionsRuns(101, listOf(run("build", "completed", "success")))))),
        )
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, completed passing check runs hold ci`() {
        val green = runs(run("build", "completed", "success"), run("docs", "completed", "skipped"), run("x", "completed", "neutral"))
        assertEquals(
            null,
            AutoMerge.ciFailure(
                ci.copy(headRuns = green, baseRuns = green, workflows = Result.success(listOf(".github/workflows/ci.yml"))),
            ),
        )
        assertEquals(null, AutoMerge.ciFailure(ci.copy(headRuns = green, ci = CiMode.Actions)))
    }

    @Test
    fun `m3-15, under ci actions a head with no run follows ci_no_run`() {
        assertEquals(
            "no Actions run on the merged head aaaaaaaaaaaa, and ci_no_run is fail",
            ciReason(ci.copy(ci = CiMode.Actions)),
        )
        assertEquals(null, AutoMerge.ciFailure(ci.copy(ci = CiMode.Actions, noRun = CiNoRun.Pass)))
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, check runs or workflows that cannot be read fail ci`() {
        val error = Result.failure<ActionsRuns>(IllegalStateException("403 the token needs the Actions: read permission"))
        assertContains(ciReason(ci.copy(headRuns = error)), "cannot read the Actions runs of the merged head aaaaaaaaaaaa: 403")
        assertContains(ciReason(ci.copy(headRuns = error)), "Actions: read")
        assertContains(ciReason(ci.copy(baseRuns = error)), "cannot read the Actions runs of the base tip: 403")
        assertContains(
            ciReason(ci.copy(workflows = Result.failure(IllegalStateException("git ls-tree exited")))),
            "cannot read the workflows of the merged head",
        )
    }

    @Test
    fun `a merge check that ran without every deny guard fails no_lost_tests`() {
        val failure = AutoMerge.failures(holding.copy(denyGuards = listOf(DenyGuard.DELETED_TESTS))).single()
        assertEquals(AutoMergeCondition.NO_LOST_TESTS, failure.condition)
        assertContains(failure.reason, "added_skips, weaker_assertions")
    }

    @Test
    fun `a touched protected path fails protected_paths`() {
        val failure = AutoMerge.failures(holding.copy(diff = holding.diff + DiffEntry("scripts/release.sh", 1, 0))).single()
        assertEquals(AutoMergeCondition.PROTECTED_PATHS, failure.condition)
        assertContains(failure.reason, "scripts/release.sh (protected by scripts/)")
    }

    @Test
    fun `send back 3, the reviewer's own config and memory files and git attributes are protected by default`() {
        for (path in listOf(
            ".claude/settings.json",
            "CLAUDE.md",
            "AGENTS.md",
            ".mcp.json",
            ".gitattributes",
            "app/CLAUDE.md",
            "app/AGENTS.md",
            "app/.claude/skills/x/SKILL.md",
            "app/.gitattributes",
        )) {
            val failure = AutoMerge.failures(holding.copy(diff = holding.diff + DiffEntry(path, 1, 0))).single()
            assertEquals(AutoMergeCondition.PROTECTED_PATHS, failure.condition, path)
            assertContains(failure.reason, "the diff touches $path (protected by")
        }
    }

    @Test
    fun `round 2 of PR #75, protected_paths matches case variants of a root file, a nested file and a folder glob`() {
        for ((path, glob) in listOf(
            "workflow.md" to "WORKFLOW.md",
            "Workflow.MD" to "WORKFLOW.md",
            "context.md" to "CONTEXT.md",
            "claude.md" to "**/CLAUDE.md",
            "app/Agents.md" to "**/AGENTS.md",
            "app/.GitAttributes" to "**/.gitattributes",
            ".Claude/settings.json" to "**/.claude/",
            "Scripts/release.sh" to "scripts/",
            "Gradle/wrapper/gradle-wrapper.properties" to "gradle/",
            "Docs/ADR/0001-x.md" to "docs/adr/",
        )) {
            val failure = AutoMerge.failures(holding.copy(diff = holding.diff + DiffEntry(path, 1, 0))).single()
            assertEquals(AutoMergeCondition.PROTECTED_PATHS, failure.condition, path)
            assertContains(failure.reason, "the diff touches $path (protected by $glob)")
        }
    }

    @Test
    fun `a diff over the size limit fails diff_size`() {
        val failure = AutoMerge.failures(holding.copy(autoMerge = AutoMergeConfig(enabled = true, maxDiffLines = 19))).single()
        assertEquals(AutoMergeCondition.DIFF_SIZE, failure.condition)
        assertEquals("the diff changes 20 lines, over the limit of 19", failure.reason)
    }

    @Test
    fun `a file whose lines git did not count fails diff_size, whatever the limit (send back 2 of PR 75)`() {
        val failure =
            AutoMerge
                .failures(
                    holding.copy(diff = holding.diff + DiffEntry("src/main/kotlin/Hidden.kt", 0, 0, counted = false)),
                ).single()
        assertEquals(AutoMergeCondition.DIFF_SIZE, failure.condition)
        assertEquals("the lines of src/main/kotlin/Hidden.kt were not counted, so the diff size is unknown", failure.reason)
    }

    @Test
    fun `a diff_base_unpinned event on the delivered attempt fails diff_base_pinned`() {
        assertEquals(listOf(AutoMergeCondition.DIFF_BASE_PINNED), failed(holding.copy(unpinned = true)))
    }

    @Test
    fun `the fallback comment names each failed condition and the merge risk`() {
        val failures = AutoMerge.failures(holding.copy(unpinned = true, diff = holding.diff + DiffEntry("WORKFLOW.md", 1, 1)))
        val text = AutoMerge.fallbackComment("7", failures, risk = null, riskSkipped = "a condition failed")

        assertContains(text, "Loop did not merge pull request #7 on its own")
        assertContains(text, "- protected_paths: the diff touches WORKFLOW.md (protected by WORKFLOW.md)")
        assertContains(text, "- diff_base_pinned:")
        assertContains(text, "Merge risk: not assessed, a condition failed.")
    }
}
