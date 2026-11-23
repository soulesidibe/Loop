package loop.core.delivery

import loop.api.ActionsJob
import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.Mergeability
import loop.core.verify.DiffEntry
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.DenyGuard
import loop.core.workflow.TestLanguage
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

    @Test
    fun `bl-96 a pull request that shows no head fails merge_check (finding 7 of bl-76)`() {
        val failure = AutoMerge.failures(holding.copy(shownHead = null)).single()
        assertEquals(AutoMergeCondition.MERGE_CHECK, failure.condition)
        assertEquals("pull request #7 shows no head, so the checked aaaaaaaaaaaa cannot be proven its head", failure.reason)
    }

    private val ci =
        CiFacts(
            pullRequestNumber = 7,
            mergedHead = head,
            mergeability = Result.success(Mergeability(mergeable = true, state = "clean")),
            headRuns = Result.success(ActionsRuns(0, emptyList())),
        )

    /** m4-05: the Actions runs are read only under `ci: actions`, so the tests of runs read them there. */
    private val actions = ci.copy(ci = CiMode.Actions)

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

    /** m4-05: `unstable` fails only under `ci: actions`, the other states under both values of `ci`. */
    @Test
    fun `a pull request that is not mergeable and clean fails ci, an unknown state included`() {
        for (state in listOf(
            Mergeability(true, "unstable"),
            Mergeability(false, "dirty"),
            Mergeability(null, "unknown"),
            Mergeability(true, "blocked"),
        )) {
            assertContains(ciReason(actions.copy(mergeability = Result.success(state))), "pull request #7", message = "$state")
        }
        assertContains(ciReason(ci.copy(mergeability = Result.failure(IllegalStateException("503")))), "503")
        assertContains(ciReason(actions.copy(mergeability = Result.failure(IllegalStateException("503")))), "503")
    }

    /**
     * m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. m4-05: checks are
     * expected only under `ci: actions`, where a head with no run fails under `ci_no_run: fail`; under `ci: none` no
     * run is read, so a head with none holds.
     */
    @Test
    fun `send back 5, a fresh head with no check run yet fails ci when checks are expected`() {
        assertEquals(
            "no Actions run on the merged head aaaaaaaaaaaa, and ci_no_run is fail",
            ciReason(actions),
        )
        assertEquals(
            null,
            AutoMerge.ciFailure(ci),
            "under ci none a head with no run holds",
        )
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, a check run still running or not passed fails ci`() {
        val expected = actions
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
            ciReason(actions.copy(headRuns = runs(run("build", "completed", "mystery")))),
            "an unknown conclusion is red",
        )
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa are pending: 101 runs, more than one page of 100",
            ciReason(actions.copy(headRuns = Result.success(ActionsRuns(101, listOf(run("build", "completed", "success")))))),
        )
    }

    /** m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. */
    @Test
    fun `send back 5, completed passing check runs hold ci`() {
        val green = runs(run("build", "completed", "success"), run("docs", "completed", "skipped"), run("x", "completed", "neutral"))
        assertEquals(
            null,
            AutoMerge.ciFailure(ci.copy(headRuns = green)),
        )
        assertEquals(null, AutoMerge.ciFailure(ci.copy(headRuns = green, ci = CiMode.Actions)))
    }

    @Test
    fun `m4-07, a run GitHub did not start fails ci as not started, never as a red run, under ci_no_run pass too`() {
        val refused = run("build", "completed", "failure")
        val zeroSteps = Result.success(ActionsJobs(1, listOf(ActionsJob(9, "build", "completed", "failure", 0))))
        for (facts in listOf(actions, actions.copy(noRun = CiNoRun.Pass))) {
            assertEquals(
                "GitHub did not start the Actions runs on the merged head aaaaaaaaaaaa, so the code was not judged: build (failure)",
                ciReason(facts.copy(headRuns = runs(refused), headJobs = mapOf(refused.id to zeroSteps))),
            )
        }
        val withSteps = Result.success(ActionsJobs(1, listOf(ActionsJob(9, "build", "completed", "failure", 4))))
        assertEquals(
            "Actions runs on the merged head aaaaaaaaaaaa did not pass: build (failure)",
            ciReason(actions.copy(headRuns = runs(refused), headJobs = mapOf(refused.id to withSteps))),
        )
        val forbidden = Result.failure<ActionsJobs>(IllegalStateException("403 the token needs the Actions: read permission"))
        val unread = ciReason(actions.copy(headRuns = runs(refused), headJobs = mapOf(refused.id to forbidden)))
        assertContains(unread, "cannot read the jobs of the Actions run ${refused.id} of aaaaaaaaaaaa: 403")
        assertContains(unread, "Actions: read")
    }

    @Test
    fun `m3-15, under ci actions a head with no run follows ci_no_run`() {
        assertEquals(
            "no Actions run on the merged head aaaaaaaaaaaa, and ci_no_run is fail",
            ciReason(ci.copy(ci = CiMode.Actions)),
        )
        assertEquals(null, AutoMerge.ciFailure(ci.copy(ci = CiMode.Actions, noRun = CiNoRun.Pass)))
    }

    /**
     * m3-15 read check runs as Actions runs; the m2-28 name is kept so the test is never lost. m4-05: no base tip run
     * and no workflow file is read any more, and the runs of the merged head only under `ci: actions`, where runs that
     * were not read fail closed.
     */
    @Test
    fun `send back 5, check runs or workflows that cannot be read fail ci`() {
        val error = Result.failure<ActionsRuns>(IllegalStateException("403 the token needs the Actions: read permission"))
        assertContains(ciReason(actions.copy(headRuns = error)), "cannot read the Actions runs of the merged head aaaaaaaaaaaa: 403")
        assertContains(ciReason(actions.copy(headRuns = error)), "Actions: read")
        assertEquals(null, AutoMerge.ciFailure(ci.copy(headRuns = error)), "under ci none the runs are not read")
        assertContains(
            ciReason(actions.copy(headRuns = null)),
            "the Actions runs of the merged head aaaaaaaaaaaa were not read",
        )
    }

    /** m4-05 (bl-146): under `ci: none` green means the merge check passed, so whatever the runs say, ci holds. */
    @Test
    fun `m4-05, under ci none ci holds once the merge check passed, whatever the Actions runs of the merged head`() {
        val table =
            listOf(
                "runs not read" to null,
                "no run" to runs(),
                "red" to runs(run("build", "completed", "failure")),
                "pending" to runs(run("build", "completed", "success"), run("lint", "queued", null)),
                "unreadable" to Result.failure(IllegalStateException("403 the token needs the Actions: read permission")),
            )
        for ((case, headRuns) in table) {
            assertEquals(null, AutoMerge.ciFailure(ci.copy(headRuns = headRuns)), case)
        }
    }

    /**
     * m4-05: under `ci: none` a state of `unstable`, failing checks GitHub reports such as the runs Actions refused for
     * billing (bl-98), holds ci; the states that are not CI still fail it.
     */
    @Test
    fun `m4-05, under ci none unstable holds ci while dirty, blocked, behind, an unknown state and not mergeable fail it`() {
        val table =
            listOf(
                Mergeability(true, "clean") to null,
                Mergeability(true, "has_hooks") to null,
                Mergeability(true, "unstable") to null,
                Mergeability(true, "UNSTABLE") to null,
                Mergeability(false, "dirty") to "pull request #7 is not mergeable and green, mergeable false, state dirty",
                Mergeability(true, "blocked") to "pull request #7 is not mergeable and green, mergeable true, state blocked",
                Mergeability(true, "behind") to "pull request #7 is not mergeable and green, mergeable true, state behind",
                Mergeability(true, "draft") to "pull request #7 is not mergeable and green, mergeable true, state draft",
                Mergeability(true, null) to "pull request #7 is not mergeable and green, mergeable true, state unknown",
                Mergeability(null, "unknown") to "GitHub has not said whether pull request #7 is mergeable (state unknown)",
                Mergeability(false, "unstable") to "pull request #7 is not mergeable and green, mergeable false, state unstable",
                Mergeability(false, "clean") to "pull request #7 is not mergeable and green, mergeable false, state clean",
            )
        for ((state, reason) in table) {
            assertEquals(reason, AutoMerge.ciFailure(ci.copy(mergeability = Result.success(state)))?.reason, "$state")
        }
        assertEquals(
            "pull request #7 is not mergeable and green, mergeable true, state unstable",
            ciReason(
                actions.copy(
                    mergeability = Result.success(Mergeability(true, "unstable")),
                    headRuns = runs(run("build", "completed", "success")),
                ),
            ),
            "under ci actions unstable still fails",
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
    fun `bl-96 local Claude memory files and gitmodules are protected by default (finding 23 of bl-76)`() {
        for ((path, glob) in listOf(
            "CLAUDE.local.md" to "**/CLAUDE.local.md",
            "app/CLAUDE.local.md" to "**/CLAUDE.local.md",
            ".gitmodules" to "**/.gitmodules",
            "vendor/.gitmodules" to "**/.gitmodules",
        )) {
            val failure = AutoMerge.failures(holding.copy(diff = holding.diff + DiffEntry(path, 1, 0))).single()
            assertEquals(AutoMergeCondition.PROTECTED_PATHS, failure.condition, path)
            assertContains(failure.reason, "the diff touches $path (protected by $glob)")
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
        assertEquals("the diff changes 20 lines outside tests and docs, over the limit of 19", failure.reason)
    }

    private val testGlobs = TestLanguage.BUILT_IN.flatMap { it.files }

    @Test
    fun `bl-96 a diff large only in tests and docs passes diff_size, only the other lines count against the limit`() {
        val facts =
            holding.copy(
                testGlobs = testGlobs,
                diff =
                    listOf(
                        DiffEntry("src/main/kotlin/App.kt", 10, 2),
                        DiffEntry("src/test/kotlin/AppTest.kt", 900, 40),
                        DiffEntry("docs/guide.txt", 500, 100),
                        DiffEntry("app/README.md", 300, 0),
                    ),
                autoMerge = AutoMergeConfig(enabled = true, maxDiffLines = 12),
            )

        assertEquals(emptyList(), AutoMerge.failures(facts))
        assertEquals(12, AutoMerge.sizedLines(facts.diff, testGlobs, AutoMergeConfig.DEFAULT_DOC_PATHS))
    }

    @Test
    fun `bl-96 the lines outside tests and docs over the limit fail diff_size, the reason giving the lines in all`() {
        val failure =
            AutoMerge
                .failures(holding.copy(testGlobs = testGlobs, autoMerge = AutoMergeConfig(enabled = true, maxDiffLines = 11)))
                .single()

        assertEquals(AutoMergeCondition.DIFF_SIZE, failure.condition)
        assertEquals("the diff changes 12 lines outside tests and docs, over the limit of 11, 20 lines in all", failure.reason)
    }

    @Test
    fun `bl-96 doc_paths replace the default doc globs, and tests and docs match with their exact case`() {
        val diff =
            listOf(
                DiffEntry("guides/setup.txt", 50, 0),
                DiffEntry("README.md", 7, 0),
                DiffEntry("DOCS/notes.txt", 3, 0),
                DiffEntry("src/TEST/kotlin/AppTest.KT", 2, 0),
            )

        assertEquals(12, AutoMerge.sizedLines(diff, testGlobs, listOf("guides/")))
        assertEquals(55, AutoMerge.sizedLines(diff, testGlobs, AutoMergeConfig.DEFAULT_DOC_PATHS))
    }

    @Test
    fun `bl-96 a test or doc file whose lines git did not count still fails diff_size`() {
        val failure =
            AutoMerge
                .failures(
                    holding.copy(testGlobs = testGlobs, diff = holding.diff + DiffEntry("docs/diagram.md", 0, 0, counted = false)),
                ).single()

        assertEquals(AutoMergeCondition.DIFF_SIZE, failure.condition)
        assertEquals("the lines of docs/diagram.md were not counted, so the diff size is unknown", failure.reason)
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
        val text = AutoMerge.fallbackComment("7", failures, risk = null, riskSkipped = "no risk reviewer is configured")

        assertContains(text, "Loop did not merge pull request #7 on its own")
        assertContains(text, "- protected_paths: the diff touches WORKFLOW.md (protected by WORKFLOW.md)")
        assertContains(text, "- diff_base_pinned:")
        assertContains(text, "Merge risk: not assessed, no risk reviewer is configured.")
    }
}
