package loop.core.waiting

/**
 * The data keys of a wait record in the store (m2-04, m2-24, m2-25, m2-26). The store keeps the data as an opaque
 * map, so a record written by an earlier Loop decodes with these same keys. Only [Wait] and [Waits] read them;
 * `approved_by` and `approved_at` stay on `WaitingIssue`, since the store's `approve` writes them.
 */
object WaitKeys {
    /** The data key of the state the result moves the issue to. */
    const val TO: String = "to"

    /** The data key of the workspace fingerprint an approval wait was verified on. */
    const val FINGERPRINT: String = "fingerprint"

    /** The data key of the rendered check lines of an approval wait, one per line, for the pull request body (m2-25). */
    const val CHECKS: String = "checks"

    /** The data key of the number of judgment calls of an approval wait (m2-25). */
    const val JUDGMENT_CALLS: String = "judgment_calls"

    /** The data key of the URL of the pull request already commented on the issue by an earlier delivery (m2-25). */
    const val PULL_REQUEST: String = "pull_request"

    /** The data key of the number of the pull request a merge wait merges (m2-26). */
    const val PULL_REQUEST_NUMBER: String = "pull_request_number"

    /** The data key of the phase of a merge wait, [PHASE_CHECK] or [PHASE_MERGE] (m2-26). */
    const val PHASE: String = "phase"

    /**
     * The data key of the base commit the merge check of a merge wait ran against, empty before the first (m2-26).
     * In phase [PHASE_CHECK] it is written before Loop merges the base, so a stop after the merge commit finds it.
     */
    const val BASE_SHA: String = "base_sha"

    /** The data key of the fingerprint the result was verified on before its merge check (m2-26). */
    const val VERIFIED_FINGERPRINT: String = "verified_fingerprint"

    /** The data key of the commit that passed the merge check, the one merged, empty before (m2-26). */
    const val MERGED_HEAD: String = "merged_head"

    /** The data key of the round of the merge check, from 1, redone when the base moved (m2-26). */
    const val ROUND: String = "round"

    /**
     * The data key set to `true` once a merge was refused because a branch moved under it (m2-26): a second such
     * refusal counts as permanent.
     */
    const val MERGE_RETRY: String = "merge_retry"

    /** The data key of the time the merged head was pushed for the CI wait, phase [PHASE_CI] (m3-15). */
    const val CI_STARTED_AT: String = "ci_started_at"

    /**
     * The data key of the milliseconds the CI wait has counted so far (m3-15): only the time between two answered polls,
     * so a hold, an unreachable provider or a restart never counts toward the timeout or the grace.
     */
    const val CI_WAITED_MS: String = "ci_waited_ms"

    /** The merge check runs: fetch the base, merge it, run the checks again (m2-26). */
    const val PHASE_CHECK: String = "check"

    /** The merge check passed: push the merged head, read the base again, merge the pull request (m2-26). */
    const val PHASE_MERGE: String = "merge"

    /** The merge check passed and the merged head was pushed: wait for its Actions runs (m3-15). */
    const val PHASE_CI: String = "ci"

    /** The keys of the CI wait, dropped by every new round of the merge check (m3-15). */
    internal val CI_KEYS: Set<String> = setOf(CI_STARTED_AT, CI_WAITED_MS)

    /** The keys only a merge wait holds, dropped when it goes back to an approval wait. */
    internal val MERGE_KEYS: Set<String> =
        setOf(PULL_REQUEST_NUMBER, PHASE, BASE_SHA, VERIFIED_FINGERPRINT, MERGED_HEAD, ROUND, MERGE_RETRY) + CI_KEYS
}
