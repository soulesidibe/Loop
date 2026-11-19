package loop.core.delivery

import loop.api.ActionsRun
import loop.api.ActionsRuns
import loop.api.MergeMethod
import loop.core.workflow.CiMode
import loop.core.workflow.CiNoRun
import loop.core.workflow.MergeConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
