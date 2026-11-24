package loop.runner.acp

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.RunnerConfigException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration of the ACP runner, read from `runners.acp` ([of]).
 *
 * [command] is split into [argv] without a shell ([CommandWords]). Optional [preset] enables an agent
 * specific preset (`agy` is the only supported value). Under `preset: agy`, [command] must be `agy acp`
 * (bare or by path, with no extra flags) and [model] is required and must start with `gemini-`.
 * Without a preset, [model] is forbidden and [strictMcp] must be false because Loop cannot isolate an
 * unknown ACP agent's MCP configuration.
 */
data class AcpSettings(
    val command: String,
    val preset: String? = null,
    val model: String? = null,
    val turnTimeout: Duration = DEFAULT_TURN_TIMEOUT_MS.milliseconds,
    val readTimeout: Duration = DEFAULT_READ_TIMEOUT_MS.milliseconds,
    val strictMcp: Boolean = true,
    val holdoutDir: Path? = null,
) {
    /** [command] split into the words the runner launches; throws [RunnerConfigException]. */
    val argv: List<String> get() = CommandWords.of(command, "runners.$SECTION.command")

    /** True when `runners.acp.preset` is `agy`. */
    val isAgyPreset: Boolean get() = preset == PRESET_AGY

    companion object {
        /** The `runner.kind` of the ACP runner. */
        const val KIND: String = "acp"

        /** The section key under `runners`. */
        const val SECTION: String = "acp"

        /** The Antigravity CLI preset name. */
        const val PRESET_AGY: String = "agy"

        /** Pinned `agy` version from Spike 0 (major `1` is enforced at `initialize`). */
        const val PINNED_AGY_VERSION: String = "1.5.16"

        /** Default `turn_timeout_ms`: 1 hour. */
        const val DEFAULT_TURN_TIMEOUT_MS: Long = 3_600_000L

        /** Default `read_timeout_ms`: 2 minutes. */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 120_000L

        /** Maximum `turn_timeout_ms` and `read_timeout_ms`: 1 day. */
        const val MAX_TIMEOUT_MS: Long = 86_400_000L

        private const val AGY_PROGRAM: String = "agy"
        private const val AGY_SUBCOMMAND: String = "acp"
        private const val GEMINI_MODEL_PREFIX: String = "gemini-"

        /** The plugin profile of `runners.acp`. */
        val PROFILE: PluginProfile =
            PluginProfile(
                keys =
                    listOf(
                        ProfileKey("command", ProfileType.STRING, required = true),
                        ProfileKey("preset", ProfileType.STRING),
                        ProfileKey("model", ProfileType.STRING),
                        ProfileKey("turn_timeout_ms", ProfileType.INTEGER, default = "$DEFAULT_TURN_TIMEOUT_MS"),
                        ProfileKey("read_timeout_ms", ProfileType.INTEGER, default = "$DEFAULT_READ_TIMEOUT_MS"),
                        ProfileKey("strict_mcp", ProfileType.BOOLEAN, default = "true"),
                    ),
                limits =
                    mapOf(
                        "turn_timeout_ms" to 1L..MAX_TIMEOUT_MS,
                        "read_timeout_ms" to 1L..MAX_TIMEOUT_MS,
                    ),
            )

        /**
         * Reads and validates `runners.acp` as written. Throws [RunnerConfigException] naming the key,
         * never echoing an invalid value.
         */
        fun of(
            config: Map<String, Any?>,
            holdoutDir: Path? = null,
        ): AcpSettings {
            val command =
                when (val value = config["command"]) {
                    null -> throw RunnerConfigException("runners.$SECTION.command is required")
                    is String ->
                        value.trim().takeIf { it.isNotEmpty() }
                            ?: throw RunnerConfigException("runners.$SECTION.command is required")
                    else -> throw RunnerConfigException("runners.$SECTION.command must be a string")
                }
            val words = CommandWords.of(command, "runners.$SECTION.command")
            val preset = config.optionalString("preset")
            if (preset != null && preset != PRESET_AGY) {
                throw RunnerConfigException("runners.$SECTION.preset must be $PRESET_AGY")
            }
            val model = config.optionalString("model")
            val turnTimeout = config.millis("turn_timeout_ms") ?: DEFAULT_TURN_TIMEOUT_MS.milliseconds
            val readTimeout = config.millis("read_timeout_ms") ?: DEFAULT_READ_TIMEOUT_MS.milliseconds
            val strictMcp = config.boolean("strict_mcp") ?: true

            if (preset == null) {
                if (model != null) {
                    throw RunnerConfigException("runners.$SECTION.model requires runners.$SECTION.preset: $PRESET_AGY")
                }
                if (strictMcp) {
                    throw RunnerConfigException(
                        "runners.$SECTION.strict_mcp cannot be true without runners.$SECTION.preset: " +
                            "set runners.$SECTION.strict_mcp to false or use runners.$SECTION.preset: $PRESET_AGY",
                    )
                }
            } else {
                if (!isAgyAcpCommand(words)) {
                    throw RunnerConfigException(
                        "runners.$SECTION.command must be $AGY_PROGRAM $AGY_SUBCOMMAND " +
                            "(or <path>/$AGY_PROGRAM $AGY_SUBCOMMAND) when runners.$SECTION.preset is $PRESET_AGY",
                    )
                }
                if (model == null) {
                    throw RunnerConfigException("runners.$SECTION.model is required when runners.$SECTION.preset is $PRESET_AGY")
                }
                if (!model.startsWith(GEMINI_MODEL_PREFIX)) {
                    throw RunnerConfigException(
                        "runners.$SECTION.model must start with $GEMINI_MODEL_PREFIX when runners.$SECTION.preset is $PRESET_AGY",
                    )
                }
            }

            return AcpSettings(
                command = command,
                preset = preset,
                model = model,
                turnTimeout = turnTimeout,
                readTimeout = readTimeout,
                strictMcp = strictMcp,
                holdoutDir = holdoutDir,
            )
        }

        private fun isAgyAcpCommand(words: List<String>): Boolean {
            if (words.size != 2 || words[1] != AGY_SUBCOMMAND) return false
            val fileName = runCatching { Path.of(words[0]).fileName?.toString() }.getOrNull()
            return fileName == AGY_PROGRAM
        }

        private fun Map<String, Any?>.optionalString(key: String): String? =
            when (val value = get(key)) {
                null -> null
                is String -> value.trim().takeIf { it.isNotEmpty() }
                else -> throw RunnerConfigException("runners.$SECTION.$key must be a string")
            }

        private fun Map<String, Any?>.boolean(key: String): Boolean? {
            val value = get(key)?.takeUnless { it is String && it.isBlank() } ?: return null
            return ProfileType.booleanOf(value) ?: throw RunnerConfigException("runners.$SECTION.$key must be a boolean")
        }

        private fun Map<String, Any?>.millis(key: String): Duration? {
            val text =
                when (val value = get(key)) {
                    null -> return null
                    is String -> value.takeIf { it.isNotBlank() }?.trim() ?: return null
                    is Int, is Long -> value.toString()
                    else -> null
                }
            val ms =
                text?.toLongOrNull()?.takeIf { it in 1L..MAX_TIMEOUT_MS }
                    ?: throw RunnerConfigException("runners.$SECTION.$key must be an integer between 1 and $MAX_TIMEOUT_MS")
            return ms.milliseconds
        }
    }
}
