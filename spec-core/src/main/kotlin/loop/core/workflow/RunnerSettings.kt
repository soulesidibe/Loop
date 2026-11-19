package loop.core.workflow

import loop.api.PluginProfile

/**
 * The runner a `WORKFLOW.md` selects (m3-04): [kind] is `runner.kind`, [sections] the `runners` map as written, each
 * runner's section by its key, with string, list, map or null values. spec-core never reads a section: the runner
 * plugin whose kind matches does, through the host's [RunnerCheck] at load and when the host builds the runner.
 */
data class RunnerSettings(
    val kind: String,
    val sections: Map<String, Any?> = emptyMap(),
)

/**
 * The runner kinds the host found (m3-04), which [WorkflowLoader] asks on every load. spec-core never depends on a
 * runner plugin: the host hands it this seam, as it hands it the tracker kinds (bl-23).
 */
fun interface RunnerCheck {
    /**
     * A sentence naming what is wrong with [settings], without a final period, or null when a runner plugin of its
     * kind accepts its section. The load then fails with `invalid_config`.
     */
    fun problem(settings: RunnerSettings): String?

    /**
     * The profile of the runner plugin of [kind] (m3-05), null when there is none. The loader checks the plugin's
     * section against it before it asks [problem].
     */
    fun profile(kind: String): RunnerProfile? = null
}

/** The runner plugin's [profile] and the key of its [section] under `runners` (m3-05). */
data class RunnerProfile(
    val section: String,
    val profile: PluginProfile,
)
