package loop.core.trackers

import loop.api.Issue
import loop.api.IssueArea
import java.util.Locale

/**
 * The overlap rule of the area lock (m4-17, bl-10): two area entries overlap when, normalized by [IssueArea], they are
 * equal or one is a parent folder of the other, on whole path segments, so `spec-core` is a parent of `spec-core/src`
 * and not of `spec-core-x`. Paths compare case folded, after the NFC of [IssueArea], so the lock fails closed: on a
 * case insensitive file system `Docs` and `docs` are one folder, and on a case sensitive one two issues are at worst
 * kept apart for nothing. A malformed entry overlaps nothing.
 */
object AreaOverlap {
    /** True when the entries [a] and [b] overlap. */
    fun overlaps(
        a: String,
        b: String,
    ): Boolean {
        val x = IssueArea.normalize(a)?.lowercase(Locale.ROOT) ?: return false
        val y = IssueArea.normalize(b)?.lowercase(Locale.ROOT) ?: return false
        return x == y || y.startsWith("$x/") || x.startsWith("$y/")
    }

    /** The first entry of [a], with the first entry of [b] it overlaps, that overlaps; null when none does. */
    fun first(
        a: List<String>,
        b: List<String>,
    ): Pair<String, String>? = a.firstNotNullOfOrNull { x -> b.firstOrNull { y -> overlaps(x, y) }?.let { x to it } }
}

/**
 * The area lock of the dispatch (m4-17, story 117, bl-10): two issues whose declared areas overlap ([AreaOverlap]) are
 * never worked at once. Deterministic code, no model (principle 2). Overlap is computed on declared areas only, never on
 * the files an attempt changed so far.
 *
 * The area of an issue is busy while its issue is claimed: running, waiting while its verification runs, or queued for
 * a retry. It frees when the claim is released, and conflicts after that are the merge check's job. An issue with no
 * area never blocks and is never blocked. Not thread safe: like the orchestrator, it is used from one coroutine at a
 * time.
 */
class AreaLock {
    /** The latest read of each holder, by id, so a holder whose tracker failed this tick's poll still holds its area. */
    private val known = mutableMapOf<String, Issue>()

    /**
     * [candidates], in the slot order they come in, without those whose area a holder keeps busy. Each one left out
     * gets [REASON] in [skips], naming its holder and the holder's overlapping entry, such as `area_busy m4-03
     * (spec-core)`; it keeps its place, so it is dispatched on the first tick its area is free. There is no reordering.
     *
     * [holders] are the ids of the claimed issues, and [seen] the issues read this tick, later ones winning, from which
     * each holder's area is taken; a holder not in [seen] keeps the area of its last read, and one never read holds
     * nothing. A candidate is blocked by a holder that is not a candidate, and by a candidate that is a holder, such as a
     * due retry, unless both are holders and the other comes later, so two due retries of one area never block each
     * other. A fresh candidate blocks nothing here: the slot plan asks [heldBy], so only one given a slot holds its area.
     */
    fun admit(
        candidates: List<Issue>,
        holders: Set<String>,
        seen: List<Issue>,
        skips: MutableMap<Issue, String>,
    ): List<Issue> {
        seen.filter { it.id in holders }.forEach { known[it.id] = it }
        known.keys.retainAll(holders)
        val candidateIds = candidates.map { it.id }.toSet()
        val held = known.values.filter { it.id !in candidateIds && it.area.isNotEmpty() }
        val admitted = mutableListOf<Issue>()
        for ((index, candidate) in candidates.withIndex()) {
            val holding =
                candidates.filterIndexed { other, issue ->
                    other != index && issue.id in holders && (candidate.id !in holders || other < index)
                }
            val reason = blockedBy(candidate, held + holding)
            if (reason == null) admitted += candidate else skips[candidate] = reason
        }
        return admitted
    }

    /**
     * The [REASON] that holds [candidate] in the slot plan, naming the first of [launched], the launches the plan gave
     * a slot before it this tick, whose area overlaps its own; null when none does.
     */
    fun heldBy(
        candidate: Issue,
        launched: List<Issue>,
    ): String? = blockedBy(candidate, launched)

    private fun blockedBy(
        candidate: Issue,
        holders: List<Issue>,
    ): String? {
        if (candidate.area.isEmpty()) return null
        val blocker =
            holders.firstNotNullOfOrNull { holder ->
                AreaOverlap.first(holder.area, candidate.area)?.let { holder to it.first }
            } ?: return null
        return "$REASON ${blocker.first.identifier} (${blocker.second})"
    }

    companion object {
        /** The `candidate_skipped` reason of a candidate whose area is busy. */
        const val REASON = "area_busy"
    }
}
