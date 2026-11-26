package loop.core.replay

import loop.api.RecordedEvent
import loop.api.RunId
import loop.core.events.BaseFetched
import loop.core.events.Delivery
import loop.core.events.Verdict

/**
 * The verdict `loop replay --verify` replays (m4-41): the [verdict] and the workspace [fingerprint] of the last `verdict`
 * event an attempt recorded, the run it was recorded in, and [diffBase], the base commit its pull request kind fetched
 * for that verification (`base_fetched`, bl-32), so a `diff_policy` check reads the same base again.
 */
data class RecordedVerdict(
    val verdict: String,
    val fingerprint: String,
    val diffBase: String?,
    val runId: RunId?,
) {
    companion object {
        /**
         * The last verdict of [attemptEvents], the events of one attempt in recording order, or null when it has none.
         * The verdict of a merge check (`merge_check` = `true`) is left out: replaying the merge check is not part of a
         * replay. The diff base is the sha of a `base_fetched` recorded after the verdict before it, null when none was.
         */
        fun of(attemptEvents: List<RecordedEvent>): RecordedVerdict? {
            val index =
                attemptEvents.indexOfLast { it.kind == Verdict.name && it.data[Verdict.MERGE_CHECK] != "true" }.takeIf { it >= 0 }
                    ?: return null
            val event = attemptEvents[index]
            val fingerprint = event.data[Verdict.FINGERPRINT] ?: return null
            val since = attemptEvents.subList(0, index).indexOfLast { it.kind == Verdict.name } + 1
            val base =
                attemptEvents
                    .subList(since, index)
                    .lastOrNull { it.kind == BaseFetched.name }
                    ?.data
                    ?.get(Delivery.SHA)
            return RecordedVerdict(event.data[Verdict.VERDICT].orEmpty(), fingerprint, base, event.runId)
        }
    }
}
