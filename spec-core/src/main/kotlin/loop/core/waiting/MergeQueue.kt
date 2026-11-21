package loop.core.waiting

/**
 * The delivery queue of a project (bl-172): its merge waits, the oldest `since` first, ties broken by issue id, sorted
 * here since the store gives no order. No lock state is stored. The head is the first wait still in the queue, and only
 * the head advances, its merge check, its CI wait and its merge: the others stay in phase `check` and do nothing. A merge
 * wait gets its `since` when it starts and keeps it through every new round, so it holds its place until it ends; a new
 * approval after a refusal or a fallback starts a new merge wait, at the back.
 */
object MergeQueue {
    private val order: Comparator<Wait.Merge> = compareBy<Wait.Merge>({ it.since }, { it.issueId })

    /** The merge waits among [waits], in queue order. */
    fun of(waits: Iterable<Wait>): List<Wait.Merge> = waits.filterIsInstance<Wait.Merge>().sortedWith(order)
}
