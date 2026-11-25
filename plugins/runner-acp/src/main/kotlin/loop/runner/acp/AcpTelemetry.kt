package loop.runner.acp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId

/**
 * Tracks session-wide absolute token totals and deduplicated per-request context occupancy for an
 * ACP session.
 *
 * - `usage_update` notifications (`used > 0`) map to [RunnerEvent.ContextUsage] with `google/<model>`
 *   for Gemini models.
 * - `session/prompt` `result.usage` maps to [RunnerEvent.Usage] accumulated into absolute session
 *   totals with `totalCostUsd = null`.
 */
internal class AcpTelemetry(
    private val isAgyPreset: Boolean,
) {
    private var totalInputTokens: Long = 0L
    private var totalOutputTokens: Long = 0L
    private var totalCacheReadTokens: Long = 0L
    private var totalCacheCreationTokens: Long = 0L

    private var lastOccupancyTokens: Long? = null
    private var lastOccupancyModel: String? = null

    /**
     * Reads one `session/update` notification; returns [RunnerEvent.ContextUsage] when a
     * `usage_update` reports `used > 0` and either occupancy or model changed since the last report.
     */
    fun readNotification(
        frame: AcpFrame.Notification,
        sessionId: SessionId,
        turnId: TurnId,
        activeModel: String?,
    ): RunnerEvent.ContextUsage? {
        if (frame.method != AcpHandshake.METHOD_SESSION_UPDATE) return null
        val params = frame.params as? JsonObject ?: return null
        val update = params["update"] as? JsonObject ?: return null
        val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNull
        if (kind != "usage_update") return null
        val used = update.long("used") ?: return null
        if (used <= 0L) return null
        val modelKey = modelKey(activeModel)
        if (used == lastOccupancyTokens && modelKey == lastOccupancyModel) return null
        lastOccupancyTokens = used
        lastOccupancyModel = modelKey
        return RunnerEvent.ContextUsage(
            sessionId = sessionId,
            turnId = turnId,
            occupancyTokens = used,
            model = modelKey,
        )
    }

    /**
     * Reads `result.usage` from a `session/prompt` response, adds its non-negative counts into the
     * session's running absolute totals, and returns [RunnerEvent.Usage] (or null when no `usage`
     * object is present).
     */
    fun readPromptUsage(
        result: JsonObject,
        sessionId: SessionId,
        turnId: TurnId,
    ): RunnerEvent.Usage? {
        val usage = result["usage"] as? JsonObject ?: return null
        val input = (usage.long("inputTokens") ?: 0L).coerceAtLeast(0L)
        val output = (usage.long("outputTokens") ?: 0L).coerceAtLeast(0L)
        val cacheRead = (usage.long("cachedReadTokens") ?: 0L).coerceAtLeast(0L)
        val cacheWrite = (usage.long("cachedWriteTokens") ?: 0L).coerceAtLeast(0L)
        totalInputTokens += input
        totalOutputTokens += output
        totalCacheReadTokens += cacheRead
        totalCacheCreationTokens += cacheWrite
        return RunnerEvent.Usage(
            sessionId = sessionId,
            turnId = turnId,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            cacheReadInputTokens = totalCacheReadTokens,
            cacheCreationInputTokens = totalCacheCreationTokens,
            totalCostUsd = null,
        )
    }

    internal fun modelKey(rawModel: String?): String? {
        val trimmed = rawModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ('/' in trimmed) return trimmed
        if (isAgyPreset || trimmed.startsWith("gemini-")) return "google/$trimmed"
        return trimmed
    }

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}
