package loop.core.workflow

import loop.api.TrackerProvider

/**
 * The tracker plugins the host found (m3-05), which [WorkflowLoader] asks on every load for the provider of
 * `tracker.kind`. spec-core never depends on a tracker plugin: the host hands it this seam, as it hands it the
 * runner plugins through [RunnerCheck].
 */
fun interface TrackerCheck {
    /**
     * The tracker plugin of [kind], or a failure whose message, without a final period, says why there is none. The
     * load then fails with `invalid_config`.
     */
    fun select(kind: String): Result<TrackerProvider>

    companion object {
        /** The check over [providers]: the one whose kind matches, else a failure naming every kind found, sorted. */
        fun of(providers: List<TrackerProvider>): TrackerCheck =
            TrackerCheck { kind ->
                providers.firstOrNull { it.kind == kind }?.let { Result.success(it) }
                    ?: Result.failure(IllegalArgumentException(unsupported(kind, providers.map { it.kind })))
            }

        /** The sentence for a `tracker.kind`, or the kind at [key] (m4-11), none of [kinds] answers to. */
        fun unsupported(
            kind: String,
            kinds: List<String>,
            key: String = "tracker.kind",
        ): String {
            val sorted = kinds.sorted()
            if (sorted.isEmpty()) return "$key \"$kind\" is not supported, no tracker plugin was found"
            val names = if (sorted.size == 1) sorted.single() else sorted.dropLast(1).joinToString(", ") + " or " + sorted.last()
            return "$key \"$kind\" is not supported, use $names"
        }
    }
}
