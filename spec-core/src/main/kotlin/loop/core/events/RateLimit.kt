package loop.core.events

import loop.api.RecordedEvent

/**
 * The runner's latest rate limit snapshot of its account (conformance 17.5 item 13, m3-21), recorded once per change
 * the runner reports. Each key is absent when the runner did not say. It reports, it never stops anything: a turn the
 * runtime refuses past its limit fails on its own. Distinct from spend: nothing sums it.
 */
object RateLimit : EventDefinition("rate_limit") {
    const val LIMIT_ID = "limit_id"
    const val LIMIT_NAME = "limit_name"
    const val PRIMARY_USED_PERCENT = "primary_used_percent"
    const val PRIMARY_WINDOW_MINUTES = "primary_window_minutes"
    const val PRIMARY_RESETS_AT = "primary_resets_at"
    const val SECONDARY_USED_PERCENT = "secondary_used_percent"
    const val SECONDARY_WINDOW_MINUTES = "secondary_window_minutes"
    const val SECONDARY_RESETS_AT = "secondary_resets_at"

    /** The runtime's word for a limit it says is reached, for example `rate_limit_reached`. */
    const val REACHED = "reached"

    override val trace = TraceShow.TIMELINE
    override val sample =
        mapOf(
            LIMIT_ID to "codex",
            LIMIT_NAME to "Codex",
            PRIMARY_USED_PERCENT to "42",
            PRIMARY_WINDOW_MINUTES to "300",
            PRIMARY_RESETS_AT to "2026-11-17T12:00:00Z",
            SECONDARY_USED_PERCENT to "7",
            SECONDARY_WINDOW_MINUTES to "10080",
        )

    /**
     * `limit=<id> name=<name> primary=<used>%/<minutes>m primary_resets_at=<instant> secondary=... reached=<word>`, each
     * part left out when its key is absent; a window without its length prints its use alone.
     */
    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add("limit", data[LIMIT_ID])
        add("name", data[LIMIT_NAME])
        add("primary", window(data[PRIMARY_USED_PERCENT], data[PRIMARY_WINDOW_MINUTES]))
        add("primary_resets_at", data[PRIMARY_RESETS_AT])
        add("secondary", window(data[SECONDARY_USED_PERCENT], data[SECONDARY_WINDOW_MINUTES]))
        add("secondary_resets_at", data[SECONDARY_RESETS_AT])
        add("reached", data[REACHED])
    }

    private fun window(
        used: String?,
        minutes: String?,
    ): String? = used?.let { "$it%" + (minutes?.let { "/${it}m" } ?: "") }
}
