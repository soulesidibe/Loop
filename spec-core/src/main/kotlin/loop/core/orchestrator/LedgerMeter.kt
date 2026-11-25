package loop.core.orchestrator

import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.RunnerEvent
import loop.core.events.Verdict
import loop.core.workflow.KindsConfig
import loop.core.workflow.WorkflowFile
import loop.core.workflow.workflowHash

/**
 * This setup with [reported], the model the runtime reported as `context.windows` keys it, `<provider>/<model>`, in place
 * of the configured one, kept when the runtime reported none (bl-147).
 */
internal fun LedgerSetup.withModel(reported: String?): LedgerSetup = if (reported == null) this else copy(model = reported)

/**
 * What the ledger counts for one attempt (m2-19). A runner reports absolute totals for its session, so the meter
 * keeps the latest report, never a sum: a turn's share is the latest total at its end minus the latest at the end
 * of the turn before. A turn without a report has no share, and an attempt without any has no totals: null, never
 * zero. It also keeps the attempt's latest verdict and how many checks its verifications ran.
 */
internal class LedgerMeter {
    private var latest: LedgerTokens? = null
    private var atTurnStart: LedgerTokens? = null
    private var reportedThisTurn = false

    /** The latest cost the runner reported, kept when a later report has none. */
    private var cost: Double? = null
    private var costAtTurnStart: Double? = null
    private var costThisTurn = false

    /**
     * The totals and the cost before the first report read between two turns, at a session's stop (bl-132), and
     * whether one of those reports had a cost; null when none came. The next turn starts from them, so its share
     * holds what was read between the turns.
     */
    private var between: Between? = null

    /** True while usage a stop read is held for the next turn, so no turn line holds it yet (m4-04). */
    val holdsStopUsage: Boolean get() = between != null

    /** True once a cost below the cost at a turn start was reported, so it is warned about once per attempt. */
    private var costDropSeen = false

    /** The latest verdict of the attempt, null before any (m2-05). */
    var verdict: String? = null
        private set

    /** The checks the attempt's verifications ran, null before any verification. */
    var checks: Int? = null
        private set

    /** Marks the start of a turn: what it reports from now on is its share, with what was read since the turn before. */
    fun startTurn() {
        val held = between
        between = null
        atTurnStart = if (held != null) held.tokens else latest
        costAtTurnStart = if (held != null) held.cost else cost
        reportedThisTurn = held != null
        costThisTurn = held?.costReported == true
    }

    /**
     * A usage report read between two turns, at a session's stop (bl-132), absolute like any other: it counts in the
     * attempt's totals now and in the share of the next turn. Returns the warning of [usage].
     */
    fun usageBetweenTurns(event: RunnerEvent.Usage): String? {
        val held = between ?: Between(latest, cost, costReported = false)
        between = held.copy(costReported = held.costReported || event.totalCostUsd != null)
        return usage(event)
    }

    /**
     * A usage report, absolute for the session so far: it replaces the last one. Returns the warning to log when
     * its cost is below the cost at the turn start, the first time in the attempt only (bl-37), null otherwise.
     */
    fun usage(event: RunnerEvent.Usage): String? {
        latest =
            LedgerTokens(
                event.inputTokens.coerceAtLeast(0),
                event.outputTokens.coerceAtLeast(0),
                event.cacheReadInputTokens.coerceAtLeast(0),
                event.cacheCreationInputTokens.coerceAtLeast(0),
            )
        reportedThisTurn = true
        event.totalCostUsd?.let {
            cost = it
            costThisTurn = true
        }
        val start = costAtTurnStart ?: return null
        val end = event.totalCostUsd ?: return null
        if (end >= start || costDropSeen) return null
        costDropSeen = true
        return "decreasing_cost: total_cost_usd $end is below $start at the turn start, the turn's cost share counts as 0"
    }

    /** A `verdict` event's data: the verdict and how many checks it ran. */
    fun verdict(data: Map<String, String>) {
        verdict = data[Verdict.VERDICT]
        checks = (checks ?: 0) + (data[Verdict.CHECKS]?.toIntOrNull() ?: 0)
    }

    /** The attempt's tokens so far, null before any report. */
    val tokens: LedgerTokens? get() = latest

    /** The attempt's cost so far, null before any report with one. */
    val costUsd: Double? get() = cost

    /** This turn's share of the tokens, null when it reported none. A total that went down counts as none. */
    fun turnTokens(): LedgerTokens? {
        val end = latest?.takeIf { reportedThisTurn } ?: return null
        val start = atTurnStart ?: return end
        return LedgerTokens(
            (end.input - start.input).coerceAtLeast(0),
            (end.output - start.output).coerceAtLeast(0),
            (end.cacheRead - start.cacheRead).coerceAtLeast(0),
            (end.cacheCreation - start.cacheCreation).coerceAtLeast(0),
        )
    }

    /**
     * This turn's share of the cost, null when it reported none. A cost below the cost at the turn start counts as
     * none: a runtime that stopped restoring the session's cost on `--resume` would report one, and [usage] warns.
     */
    fun turnCostUsd(): Double? {
        val end = cost?.takeIf { costThisTurn } ?: return null
        return (end - (costAtTurnStart ?: 0.0)).coerceAtLeast(0.0)
    }

    private data class Between(
        val tokens: LedgerTokens?,
        val cost: Double?,
        val costReported: Boolean,
    )

    companion object {
        /**
         * What the ledger records of [workflow]'s setup on every line and row of an attempt: `runner.kind`, and
         * [model], the model of the attempt's runner (m3-04: its settings belong to its plugin). Since m4-13 `tracker`
         * is the id of the tracker of [issueId], the primary one's when it is null, so `loop report` groups by tracker.
         * Since m4-18 `kind` is [kind], the kind the issue matched; it defaults to `code` in Loop mode, for the code
         * only paths, and is null in Symphony mode. Since m4-19 `template_version` is the hash of the template that kind
         * renders, its own `template` or the body.
         */
        fun setupOf(
            workflow: WorkflowFile,
            model: String? = null,
            issueId: String? = null,
            kind: String? = workflow.config.kinds?.let { KindsConfig.CODE },
        ): LedgerSetup =
            LedgerSetup(
                tracker = issueId?.let { workflow.trackers.entryOf(it).id } ?: workflow.trackerId,
                kind = kind,
                runner = workflow.runner.kind,
                model = model,
                templateVersion =
                    workflowHash(
                        workflow.config.kinds
                            ?.named
                            ?.get(kind)
                            ?.template
                            ?.text ?: workflow.promptTemplate,
                    ),
                configVersion = workflow.sourceHash.ifEmpty { null },
            )
    }
}
