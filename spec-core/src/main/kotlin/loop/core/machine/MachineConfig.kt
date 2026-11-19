package loop.core.machine

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The machine limits of m3-16 (story 85, ADR 0001), shared by every Loop process of the machine: at most
 * [maxConcurrentAgents] agents and merge checks at once, and no new dispatch once the attempts of every project spent
 * more than [perDayUsd] dollars or [perDayTokens] tokens in the last 24 hours. A null limit is no limit.
 */
data class MachineLimits(
    val maxConcurrentAgents: Int? = null,
    val perDayUsd: Double? = null,
    val perDayTokens: Long? = null,
)

/** A machine config Loop cannot use; [key] is the dotted key at fault, null when the file as a whole is. */
class MachineConfigError(
    val key: String?,
    message: String,
) : Exception(message)

/**
 * Reads `<LOOP_HOME>/config.yaml` (m3-16), the machine level config, never a project `WORKFLOW.md`. It is opt in: no
 * file means no machine limit. Its keys live under `machine:`; an unknown key there fails, so a typo never silently
 * lifts a limit, while other top level sections are left for later configs.
 */
object MachineConfig {
    const val FILE_NAME: String = "config.yaml"
    const val MAX_CONCURRENT_AGENTS: String = "machine.max_concurrent_agents"
    const val PER_DAY_USD: String = "machine.per_day_usd"
    const val PER_DAY_TOKENS: String = "machine.per_day_tokens"

    private const val SECTION = "machine"
    private const val POSITIVE_INTEGER = "a positive integer"
    private const val DOLLARS = "a positive number of dollars"
    private val DECIMAL = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
    private val KEYS = listOf(MAX_CONCURRENT_AGENTS, PER_DAY_USD, PER_DAY_TOKENS).map { it.removePrefix("$SECTION.") }

    /** The machine config file under [loopHome]. */
    fun file(loopHome: Path): Path = loopHome.resolve(FILE_NAME)

    /** The limits of the file under [loopHome]; null when there is no file, a [MachineConfigError] when Loop cannot use it. */
    fun read(loopHome: Path): Result<MachineLimits?> {
        val file = file(loopHome)
        val text =
            try {
                Files.readString(file)
            } catch (e: NoSuchFileException) {
                return Result.success(null)
            } catch (e: IOException) {
                return Result.failure(MachineConfigError(null, "$file: cannot read it: ${e.message ?: e::class.simpleName}"))
            }
        return try {
            Result.success(parse(text))
        } catch (e: MachineConfigError) {
            Result.failure(MachineConfigError(e.key, "$file: ${e.message}"))
        }
    }

    private fun parse(text: String): MachineLimits {
        if (text.isBlank()) return MachineLimits()
        val root =
            try {
                Yaml.default.parseToYamlNode(text)
            } catch (e: RuntimeException) {
                throw MachineConfigError(null, "not valid yaml: ${e.message?.lineSequence()?.first()}")
            }
        val machine =
            when (root) {
                is YamlNull -> return MachineLimits()
                is YamlMap -> root.get<YamlNode>(SECTION)
                else -> throw MachineConfigError(null, "the file must be a map")
            }
        val section =
            when (machine) {
                null, is YamlNull -> return MachineLimits()
                is YamlMap -> machine
                else -> throw MachineConfigError(SECTION, "$SECTION must be a map")
            }
        section.entries.keys
            .map { it.content }
            .firstOrNull { it !in KEYS }
            ?.let {
                throw MachineConfigError(
                    "$SECTION.$it",
                    "$SECTION.$it is not a machine key, expected one of ${KEYS.joinToString(", ")}",
                )
            }
        return MachineLimits(
            maxConcurrentAgents = section.scalar(MAX_CONCURRENT_AGENTS, POSITIVE_INTEGER)?.let { positiveInt(MAX_CONCURRENT_AGENTS, it) },
            perDayUsd = section.scalar(PER_DAY_USD, DOLLARS)?.let { dollars(PER_DAY_USD, it) },
            perDayTokens = section.scalar(PER_DAY_TOKENS, POSITIVE_INTEGER)?.let { positiveLong(PER_DAY_TOKENS, it) },
        )
    }

    /** The text of [key] in this section, null when absent or null; a list or a map fails naming the key. */
    private fun YamlMap.scalar(
        key: String,
        expected: String,
    ): String? =
        when (val node = get<YamlNode>(key.removePrefix("$SECTION."))) {
            null, is YamlNull -> null
            is YamlScalar -> node.content
            else -> throw MachineConfigError(key, "$key must be $expected")
        }

    private fun positiveInt(
        key: String,
        text: String,
    ): Int = text.trim().toIntOrNull()?.takeIf { it > 0 } ?: throw invalid(key, POSITIVE_INTEGER, text)

    private fun positiveLong(
        key: String,
        text: String,
    ): Long = text.trim().toLongOrNull()?.takeIf { it > 0 } ?: throw invalid(key, POSITIVE_INTEGER, text)

    private fun dollars(
        key: String,
        text: String,
    ): Double {
        val value = text.trim().takeIf { DECIMAL.matches(it) }?.toDoubleOrNull()
        return value?.takeIf { it > 0 && it.isFinite() } ?: throw invalid(key, DOLLARS, text)
    }

    private fun invalid(
        key: String,
        expected: String,
        text: String,
    ): MachineConfigError {
        val trimmed = text.trim()
        val shown = if (DECIMAL.matches(trimmed)) trimmed else "\"$text\""
        return MachineConfigError(key, "$key must be $expected, got $shown")
    }
}
