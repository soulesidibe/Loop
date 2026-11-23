package loop.core.delivery

import loop.api.ActionsJob
import loop.api.ActionsJobs
import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.MergeMethod
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.MergeConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * m3-15: the CI verdict on the Actions runs of a merged head, a pure function, and what the CI wait does with it after
 * an answered poll: the verdict first, then the timeout only while runs are pending, then the no run rule only with
 * zero runs after the grace.
 */
class CiWaitTest {
    private var nextId = 100L

    private fun run(
        workflow: Long = 1,
        event: String = "push",
        number: Long = 1,
        status: String = "completed",
        conclusion: String? = "success",
        name: String = "build",
        attempt: Long = 1,
    ): ActionsRun =
        ActionsRun(
            id = nextId++,
            name = name,
            workflowId = workflow,
            event = event,
            runNumber = number,
            runAttempt = attempt,
            status = status,
            conclusion = conclusion,
            url = "https://github.com/acme/widgets/actions/runs/$nextId",
        )

    private fun runs(
        vararg items: ActionsRun,
        total: Long = items.size.toLong(),
    ) = ActionsRuns(total, items.toList())

    /** One row of the verdict table: the runs listed, the verdict expected and why. */
    private data class Row(
        val why: String,
        val listing: ActionsRuns,
        val expected: String,
    )

    private fun name(verdict: CiVerdict): String =
        when (verdict) {
            is CiVerdict.Green -> "green"
            is CiVerdict.Red -> "red"
            is CiVerdict.Pending -> "pending"
            is CiVerdict.NotStarted -> "not_started"
            CiVerdict.NoRun -> "no_run"
        }

    @Test
    fun `the verdict table`() {
        val table =
            listOf(
                Row("no run at all", runs(), "no_run"),
                Row("every run green", runs(run(1), run(2, event = "pull_request")), "green"),
                Row(
                    "success, skipped and neutral pass",
                    runs(run(1), run(2, conclusion = "skipped"), run(3, conclusion = "neutral")),
                    "green",
                ),
                Row("a failure", runs(run(1), run(2, conclusion = "failure")), "red"),
                Row("cancelled is red", runs(run(1, conclusion = "cancelled")), "red"),
                Row("timed out is red", runs(run(1, conclusion = "timed_out")), "red"),
                Row("action required is red", runs(run(1, conclusion = "action_required")), "red"),
                Row("an unknown conclusion is red, fail closed", runs(run(1, conclusion = "mystery")), "red"),
                Row("a completed run with no conclusion is red", runs(run(1, conclusion = null)), "red"),
                Row("a queued run is pending", runs(run(1), run(2, status = "queued", conclusion = null)), "pending"),
                Row("an in progress run is pending", runs(run(1, status = "in_progress", conclusion = null)), "pending"),
                Row("an unknown status is pending", runs(run(1, status = "waiting", conclusion = null)), "pending"),
                Row(
                    "a red run wins over a pending one, the head cannot turn green",
                    runs(run(1, conclusion = "failure"), run(2, status = "queued", conclusion = null)),
                    "red",
                ),
                Row(
                    "the highest run number of a workflow and event is kept, an older failure is forgotten",
                    runs(run(1, number = 4, conclusion = "failure"), run(1, number = 5)),
                    "green",
                ),
                Row(
                    "the highest run number is kept, whatever the order of the listing",
                    runs(run(1, number = 6, status = "queued", conclusion = null), run(1, number = 5)),
                    "pending",
                ),
                Row(
                    "the same workflow on another event is its own group",
                    runs(run(1, event = "push", number = 5), run(1, event = "pull_request", number = 3, conclusion = "failure")),
                    "red",
                ),
                Row("a rerun is judged on the attempt the listing shows", runs(run(1, attempt = 2)), "green"),
                Row("a total count above the page is pending", runs(run(1), total = 101), "pending"),
                Row(
                    "a total count above the page with a failure in it is still pending",
                    runs(run(1, conclusion = "failure"), total = 150),
                    "pending",
                ),
                Row("a total count above the page with nothing listed is pending, not no run", runs(total = 3), "pending"),
            )
        for (row in table) {
            assertEquals(row.expected, name(CiWait.verdict(row.listing)), row.why)
        }
    }

    private var nextJob = 900L

    /** The jobs of one run, one per entry of [steps], each with that many steps; [total] the count GitHub gives. */
    private fun jobs(
        vararg steps: Int,
        total: Long = steps.size.toLong(),
    ) = ActionsJobs(total, steps.map { ActionsJob(nextJob++, "build", "completed", "failure", it) })

    /** One row of the m4-07 table: the runs, the jobs read for them by run, the verdict expected and why. */
    private data class JobsRow(
        val why: String,
        val listing: ActionsRuns,
        val jobs: Map<Long, ActionsJobs>,
        val expected: String,
    )

    @Test
    fun `m4-07, the not started table`() {
        val refused = run(1, conclusion = "failure")
        val built = run(2, conclusion = "failure")
        val green = run(3)
        val queued = run(4, status = "queued", conclusion = null)
        val cancelled = run(5, conclusion = "cancelled")
        val noJob = run(6, conclusion = "failure")
        val unread = run(7, conclusion = "failure")
        val table =
            listOf(
                JobsRow(
                    "a failed run whose only job has zero steps is not started",
                    runs(refused),
                    mapOf(refused.id to jobs(0)),
                    "not_started",
                ),
                JobsRow("every job with zero steps is not started", runs(refused), mapOf(refused.id to jobs(0, 0, 0)), "not_started"),
                JobsRow("a failed run with steps stays red", runs(built), mapOf(built.id to jobs(7)), "red"),
                JobsRow("one job with steps among refused ones is red", runs(built), mapOf(built.id to jobs(0, 3)), "red"),
                JobsRow("a failed run whose jobs were not read stays red, fail closed", runs(unread), emptyMap(), "red"),
                JobsRow("a failed run with no job at all stays red, a broken workflow file", runs(noJob), mapOf(noJob.id to jobs()), "red"),
                JobsRow(
                    "zero steps on a page of jobs shorter than the total stays red",
                    runs(refused),
                    mapOf(refused.id to jobs(0, total = 150)),
                    "red",
                ),
                JobsRow(
                    "a not started run next to a green one is not started",
                    runs(refused, green),
                    mapOf(refused.id to jobs(0)),
                    "not_started",
                ),
                JobsRow(
                    "a not started run next to a pending one is not started, the head can no longer turn green",
                    runs(refused, queued),
                    mapOf(refused.id to jobs(0)),
                    "not_started",
                ),
                JobsRow(
                    "a not started run next to a red one is red",
                    runs(refused, built),
                    mapOf(refused.id to jobs(0), built.id to jobs(4)),
                    "red",
                ),
                JobsRow("a cancelled run with zero steps is not started", runs(cancelled), mapOf(cancelled.id to jobs(0)), "not_started"),
                JobsRow("jobs of a green run change nothing", runs(green), mapOf(green.id to jobs(0)), "green"),
                JobsRow("a pending run is not judged on its jobs", runs(queued), mapOf(queued.id to jobs(0)), "pending"),
                JobsRow("no run is no run, never not started", runs(), emptyMap(), "no_run"),
                JobsRow(
                    "a total above the page is pending, never not started",
                    runs(refused, total = 120),
                    mapOf(refused.id to jobs(0)),
                    "pending",
                ),
            )
        for (row in table) {
            assertEquals(row.expected, name(CiWait.verdict(row.listing, row.jobs)), row.why)
        }
    }

    @Test
    fun `m4-07, the jobs are read only for the failed kept runs, at most 10 per poll`() {
        val green = run(1)
        val queued = run(2, status = "queued", conclusion = null)
        val older = run(3, number = 1, conclusion = "failure")
        val newer = run(3, number = 2)
        val failed = (10L..21L).map { run(it, conclusion = if (it % 2 == 0L) "failure" else "cancelled") }

        assertEquals(emptyList(), CiWait.jobsToRead(runs(green, queued, older, newer)), "no failed kept run, no jobs read")
        assertEquals(failed.take(10).map { it.id }, CiWait.jobsToRead(runs(green, *failed.toTypedArray(), queued)).map { it.id })
        assertEquals(emptyList(), CiWait.jobsToRead(runs(*failed.toTypedArray(), total = 101)), "a listing over a page reads no jobs")

        val readJobs = failed.take(10).associate { it.id to jobs(0) }
        val verdict = assertIs<CiVerdict.Red>(CiWait.verdict(runs(*failed.toTypedArray()), readJobs))
        assertEquals(failed.drop(10).map { it.id }, verdict.failed.map { it.id }, "the runs past the 10 read stay red")
    }

    @Test
    fun `m4-07, a not started verdict names the refused runs and a red one names only the red runs`() {
        val refused = run(1, conclusion = "failure")
        val built = run(2, conclusion = "failure")
        val notStarted = assertIs<CiVerdict.NotStarted>(CiWait.verdict(runs(refused, run(3)), mapOf(refused.id to jobs(0))))
        assertEquals(listOf(refused.id), notStarted.notStarted.map { it.id })
        assertEquals(2, notStarted.kept.size)

        val red = assertIs<CiVerdict.Red>(CiWait.verdict(runs(refused, built), mapOf(refused.id to jobs(0), built.id to jobs(5))))
        assertEquals(listOf(built.id), red.failed.map { it.id })
    }

    @Test
    fun `m4-07, not started fails with its own outcome at once, never red and never no run, under ci_no_run pass too`() {
        val refused = run(1, conclusion = "failure")
        val verdict = CiWait.verdict(runs(refused), mapOf(refused.id to jobs(0)))

        for (merge in listOf(config, config.copy(ciNoRun = CiNoRun.Pass))) {
            for (waited in listOf(0L, 5 * MINUTE, 90 * MINUTE)) {
                val failed = assertIs<CiDecision.Fail>(CiWait.decide(verdict, waited, merge), "waited $waited, ${merge.ciNoRun}")
                assertEquals(CiWait.NOT_STARTED, failed.outcome)
                assertContains(failed.reason, "GitHub did not start")
                assertContains(failed.reason, "the code was not judged")
            }
        }
        assertEquals("not_started", CiWait.NOT_STARTED)
        assertEquals(
            6,
            setOf(CiWait.GREEN, CiWait.RED, CiWait.NO_RUN_PASS, CiWait.NO_RUN_FAIL, CiWait.TIMEOUT, CiWait.NOT_STARTED).size,
            "not_started is its own outcome",
        )
    }

    @Test
    fun `m4-07, the comment of a not started run says GitHub did not start it and the code was not judged`() {
        val refused = run(1, conclusion = "failure")
        val decision = assertIs<CiDecision.Fail>(CiWait.decide(CiWait.verdict(runs(refused), mapOf(refused.id to jobs(0))), 0, config))
        val comment = CiWait.failedComment("7", decision.reason, CiWait.failedRunLines(listOf(refused)) { it }, notStarted = true)

        assertContains(comment, "Loop did not merge pull request #7, ci: GitHub did not start")
        assertContains(comment, "the code was not judged")
        assertContains(comment, "Runs not started:\n- build (failure): https://github.com/acme/widgets/actions/runs/")
        assertFalse(comment.contains("Failed runs"), comment)
        assertFalse(comment.contains("fix the branch"), comment)
        assertContains(comment, "approve again with loop approve")
    }

    @Test
    fun `the kept runs are one per workflow and event, the highest run number`() {
        val old = run(1, number = 4, conclusion = "failure")
        val new = run(1, number = 5)
        val other = run(2, event = "pull_request", number = 9)
        val verdict = assertIs<CiVerdict.Green>(CiWait.verdict(runs(old, new, other)))

        assertEquals(listOf(new.id, other.id), verdict.kept.map { it.id })
    }

    @Test
    fun `a red verdict names the failed kept runs only`() {
        val failed = run(1, conclusion = "failure")
        val verdict = assertIs<CiVerdict.Red>(CiWait.verdict(runs(failed, run(2), run(3, status = "queued", conclusion = null))))

        assertEquals(listOf(failed.id), verdict.failed.map { it.id })
        assertEquals(3, verdict.kept.size)
    }

    private val config = MergeConfig(MergeMethod.Merge, CiMode.Actions, ciTimeoutMs = 45 * MINUTE, ciNoRunGraceMs = 5 * MINUTE)

    @Test
    fun `green merges and red fails at once, whatever the time waited`() {
        assertEquals(CiDecision.Merge(CiWait.GREEN), CiWait.decide(CiWait.verdict(runs(run(1))), 0, config))
        assertEquals(CiDecision.Merge(CiWait.GREEN), CiWait.decide(CiWait.verdict(runs(run(1))), 90 * MINUTE, config))
        val red = assertIs<CiDecision.Fail>(CiWait.decide(CiWait.verdict(runs(run(1, conclusion = "failure"))), 0, config))
        assertEquals(CiWait.RED, red.outcome)
    }

    @Test
    fun `pending runs keep the wait until the timeout, then fail it`() {
        val pending = CiWait.verdict(runs(run(1, status = "in_progress", conclusion = null)))

        assertEquals(CiDecision.Wait, CiWait.decide(pending, 45 * MINUTE - 1, config))
        val timeout = assertIs<CiDecision.Fail>(CiWait.decide(pending, 45 * MINUTE, config))
        assertEquals(CiWait.TIMEOUT, timeout.outcome)
        assertContains(timeout.reason, "45 minutes")
    }

    @Test
    fun `zero runs wait for the grace, then the no run rule decides, never the timeout`() {
        val none = CiWait.verdict(runs())

        assertEquals(CiDecision.Wait, CiWait.decide(none, 5 * MINUTE - 1, config))
        val failed = assertIs<CiDecision.Fail>(CiWait.decide(none, 5 * MINUTE, config))
        assertEquals(CiWait.NO_RUN_FAIL, failed.outcome)
        assertEquals(CiDecision.Merge(CiWait.NO_RUN_PASS), CiWait.decide(none, 5 * MINUTE, config.copy(ciNoRun = CiNoRun.Pass)))
    }

    @Test
    fun `the comment names at most 10 failed runs, each redacted and cut at 200 characters`() {
        val failed =
            (1L..12L).map {
                run(
                    it,
                    conclusion = "failure",
                    name =
                        if (it ==
                            1L
                        ) {
                            "x".repeat(300)
                        } else {
                            "build $it with ghp_secret"
                        },
                )
            }
        val lines = CiWait.failedRunLines(failed) { it.replace("ghp_secret", "[REDACTED]") }

        assertEquals(11, lines.size)
        assertTrue(lines.take(10).all { it.length <= 200 }, "each run line is cut at 200 characters")
        assertTrue(lines[0].endsWith("..."))
        assertContains(lines[1], "build 2 with [REDACTED] (failure): https://github.com/acme/widgets/actions/runs/")
        assertTrue(lines.none { it.contains("ghp_secret") })
        assertEquals("- and 2 more", lines[10])
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
