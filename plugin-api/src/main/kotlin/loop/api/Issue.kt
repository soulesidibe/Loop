package loop.api

import kotlin.time.Instant

/**
 * An issue read from a tracker, normalized to one shape whatever the tracker.
 * Labels are lowercased by the adapter; [createdAt] is null when the tracker cannot tell.
 *
 * The adapter decides whether its blockers hold the issue back (spec-decisions: the
 * orchestrator never reads [blockedBy]); [blockedReason] says why, null when nothing blocks it.
 */
public data class Issue(
    public val id: String,
    public val identifier: String,
    public val title: String,
    public val state: String,
    public val priority: Int?,
    public val labels: List<String>,
    public val blockedBy: List<String>,
    public val description: String?,
    public val createdAt: Instant?,
    /** Why the issue may not be dispatched, for example `blocked_by m1-02 (todo)`; null when it may. */
    public val blockedReason: String? = null,
    /**
     * Non secret provider ids kept for provider native tools (spec 4.1.1 `native_ref`), such as the GitHub
     * node id; null when the tracker has no id apart from [id]. Opaque to the orchestrator.
     */
    public val nativeRef: Map<String, String>? = null,
    /**
     * The repository relative paths, folders or files, the issue declares it edits (m4-17), normalized by
     * [IssueArea]; empty when it declares none. Two issues whose areas overlap are never worked at once.
     */
    public val area: List<String> = emptyList(),
) {
    /** False while a blocker holds the issue back; the orchestrator dispatches only dispatchable issues. */
    public val dispatchable: Boolean get() = blockedReason == null

    init {
        require(labels.all { it == it.lowercase() }) { "Issue labels must be lowercased: $labels" }
    }
}
