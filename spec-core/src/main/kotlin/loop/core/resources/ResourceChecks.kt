package loop.core.resources

import loop.api.ResourceDeclaration

/**
 * Runs the shell commands of a resource (m4-26), its `read_only_check` and its `restore` entries, with [env] over the
 * env policy of the process that runs them. The seam between the resource rules and processes: `loop tool mcp` runs
 * the check of a release, `loop run` the check and the restore at the end of an attempt and the check of a dirty
 * resource.
 */
fun interface ResourceCommands {
    suspend fun run(
        command: String,
        env: Map<String, String>,
    ): CommandOutcome

    companion object {
        /** Runs nothing: every command fails, so a declared check never passes without a runner. */
        val NONE: ResourceCommands = ResourceCommands { _, _ -> CommandOutcome(null, "no command runner") }
    }
}

/** What one resource command did: it passed on [exitCode] 0; [output] is its stdout and stderr, merged. */
data class CommandOutcome(
    val exitCode: Int?,
    val output: String,
) {
    val passed: Boolean get() = exitCode == 0
}

/** The read only check of a resource (m4-26), shared by `loop tool mcp` and `loop run`. */
object ReadOnlyChecks {
    /** The most of a check's output a difference keeps, its end, where a failing check says why. */
    const val DIFFERENCE_MAX: Int = 2_000

    /**
     * The env a resource command or a check that holds a resource gets from [prepared], what the provider's `prepare`
     * returned: `LOOP_RESOURCE_<KEY>` per key, upper case, each character other than a letter or a digit as `_`, so
     * `udid` is `LOOP_RESOURCE_UDID`.
     */
    fun env(prepared: Map<String, String>): Map<String, String> =
        prepared.entries.associate { (key, value) -> "LOOP_RESOURCE_" + key.uppercase().replace(NOT_NAME, "_") to value }

    /**
     * Runs the `read_only_check` of [resource] with the env of [prepared] through [commands]: the difference it found,
     * null when it passed or none is declared.
     */
    suspend fun run(
        resource: ResourceDeclaration,
        prepared: Map<String, String>,
        commands: ResourceCommands,
    ): String? {
        val command = resource.readOnlyCheck ?: return null
        val outcome = commands.run(command, env(prepared))
        return if (outcome.passed) null else difference(outcome)
    }

    /** What a failed check found: its exit code and the end of its output. */
    fun difference(outcome: CommandOutcome): String {
        val code = outcome.exitCode?.let { "exit code $it" } ?: "no exit code"
        val output = outcome.output.trim().takeLast(DIFFERENCE_MAX)
        return if (output.isEmpty()) code else "$code: $output"
    }

    private val NOT_NAME = Regex("[^A-Z0-9]")
}
