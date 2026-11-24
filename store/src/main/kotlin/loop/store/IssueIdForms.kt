package loop.store

/**
 * How the store reads the issue ids of its rows (m4-12). Since m4-12 the core carries namespaced ids,
 * `<tracker id>:<native id>`, and rows written before hold bare ids, which are the primary tracker's. [read] gives
 * the id the core carries for a stored or a given id, and [legacy] the bare id a row written before m4-12 holds for
 * that id, null when it had none. The rule itself is `loop.core.trackers.NamespacedId`; the cli builds this from it.
 * Rows are never rewritten: a by id read or delete matches both forms, and a write to a row that holds the bare id
 * keeps it.
 */
class IssueIdForms(
    private val read: (String) -> String,
    private val legacy: (String) -> String?,
) {
    /** The id the core carries for the stored or given [issueId]. */
    fun read(issueId: String): String = read.invoke(issueId)

    /** The two forms a row of [issueId] may hold: the id [read] gives and its bare id, the same id twice when none. */
    internal fun of(issueId: String): Pair<String, String> {
        val id = read(issueId)
        return id to (legacy(id) ?: id)
    }

    companion object {
        /** Ids as they are stored, the store's behavior before m4-12, for the commands that do not know the tracker. */
        val AS_STORED: IssueIdForms = IssueIdForms({ it }, { null })
    }
}
