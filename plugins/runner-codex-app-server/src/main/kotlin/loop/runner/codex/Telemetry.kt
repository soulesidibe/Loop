package loop.runner.codex

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.codex.protocol.CodexJson
import loop.runner.codex.protocol.v2.AccountRateLimitsUpdatedNotification
import loop.runner.codex.protocol.v2.ModelReroutedNotification
import loop.runner.codex.protocol.v2.RateLimitReachedType
import loop.runner.codex.protocol.v2.RateLimitWindow
import loop.runner.codex.protocol.v2.ThreadTokenUsageUpdatedNotification
import loop.runner.codex.protocol.v2.TokenUsageBreakdown
import kotlin.time.Instant

/**
 * The usage, occupancy and rate limit telemetry of one Codex session (m3-21, conformance 17.5 items 13 and 14, and
 * m3-23 for occupancy), read from the notifications of the pinned schema, one mapping per message kind:
 *
 * - `thread/tokenUsage/updated` gives [RunnerEvent.Usage]. Its `total` is the thread's absolute total so far, so the
 *   latest one of each thread replaces the earlier, and the session's usage is the sum of the latest totals of every
 *   thread it saw, a subagent's thread included. A total that leaves the sum unchanged, such as a repeated identical
 *   update, gives no event, so it counts once. The protocol's `inputTokens` holds the cached input, as the OpenAI usage
 *   does, so Loop's input is `inputTokens` minus `cachedInputTokens` and `cacheWriteInputTokens`, which become the
 *   cache read and cache creation counts, and the four add up to the protocol's `totalTokens`. `outputTokens` already
 *   holds the reasoning tokens. Codex reports no cost, so [RunnerEvent.Usage.totalCostUsd] is always null.
 * - The same notification of the main thread, the one [startTurn] names, also gives [RunnerEvent.ContextUsage] (m3-23,
 *   plan 8.3), after the usage: its `last`, the latest model request alone, never the cumulative `total`. The
 *   occupancy is `last.inputTokens`, which holds the cached input and the cache writes, so it is the input size of that
 *   request, cached tokens included; output never counts. A subagent's thread is not the session's occupancy. A report
 *   equal to the last one, same tokens and model, gives no event.
 * - `model/rerouted` of the main thread changes the model of the reports that follow to its `toModel`, with the
 *   thread's provider, until the next [startTurn] goes back to the thread's model. The model is the `context.windows`
 *   key `<modelProvider>/<model>`, null when the `thread/start` answer named none.
 * - `account/rateLimits/updated` gives [RunnerEvent.RateLimit]. It is a sparse update: a limit id, a name or a window
 *   it leaves out keeps the value an earlier update of the same limit gave, as the schema asks. An update that changes
 *   nothing gives no event. `resetsAt` is read as Unix seconds.
 *
 * A notification of any of these kinds that does not match the pinned schema gives a `loop:` line on stderr and is skipped:
 * telemetry never ends a turn. Any other notification is no signal.
 */
internal class Telemetry {
    /** What one notification says, turned into its event with the ids of the turn that reports it. */
    fun interface Signal {
        fun event(
            sessionId: SessionId,
            turnId: TurnId,
        ): RunnerEvent
    }

    private data class Tokens(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheCreation: Long,
    ) {
        operator fun plus(other: Tokens) =
            Tokens(input + other.input, output + other.output, cacheRead + other.cacheRead, cacheCreation + other.cacheCreation)
    }

    private data class Limit(
        val limitId: String?,
        val limitName: String?,
        val primary: RunnerEvent.RateLimit.Window?,
        val secondary: RunnerEvent.RateLimit.Window?,
        val reached: String?,
    )

    // The latest total of each thread, by thread id.
    private val threads = mutableMapOf<String, Tokens>()
    private var reported: Tokens? = null

    // The main thread, the provider the thread/start answer gave, and the model of the thread's requests now.
    private var mainThread: String? = null
    private var provider: String? = null
    private var model: String? = null
    private var occupancy: Pair<Long, String?>? = null

    // The merged snapshot of each limit, by limit id, and the id of the limit updated last.
    private val limits = mutableMapOf<String?, Limit>()
    private var lastLimitId: String? = null

    /**
     * A turn started on the main thread [threadId], whose `thread/start` answer named [provider] and [model] (m3-23):
     * occupancy is read from that thread, on that model.
     */
    fun startTurn(
        threadId: String,
        provider: String?,
        model: String?,
    ) {
        mainThread = threadId
        this.provider = provider
        this.model = model
    }

    /** The signals of [notification] in order, none when it is no signal or says nothing new. */
    fun read(notification: Frame.Notification): List<Signal> {
        val method = notification.message.method
        val params = notification.message.params
        return when (method) {
            TOKEN_USAGE -> {
                val update = decode(ThreadTokenUsageUpdatedNotification.serializer(), params) ?: return listOf(unreadable(method))
                listOfNotNull(usage(update), occupancy(update))
            }
            RATE_LIMITS ->
                listOfNotNull(
                    rateLimit(
                        decode(AccountRateLimitsUpdatedNotification.serializer(), params) ?: return listOf(unreadable(method)),
                    ),
                )
            MODEL_REROUTED -> {
                rerouted(decode(ModelReroutedNotification.serializer(), params) ?: return listOf(unreadable(method)))
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun occupancy(update: ThreadTokenUsageUpdatedNotification): Signal? {
        if (update.threadId != mainThread) return null
        val report = update.tokenUsage.last.inputTokens to key(model)
        if (report == occupancy) return null
        occupancy = report
        return Signal { sessionId, turnId -> RunnerEvent.ContextUsage(sessionId, turnId, report.first, report.second) }
    }

    private fun rerouted(update: ModelReroutedNotification) {
        if (update.threadId == mainThread) model = update.toModel
    }

    /** The `context.windows` key of [model] on the thread's provider, null when either is unknown. */
    private fun key(model: String?): String? = if (provider == null || model == null) null else "$provider/$model"

    private fun usage(update: ThreadTokenUsageUpdatedNotification): Signal? {
        threads[update.threadId] = tokensOf(update.tokenUsage.total)
        val sum = threads.values.reduce(Tokens::plus)
        if (sum == reported) return null
        reported = sum
        return Signal { sessionId, turnId ->
            RunnerEvent.Usage(sessionId, turnId, sum.input, sum.output, sum.cacheRead, sum.cacheCreation, totalCostUsd = null)
        }
    }

    private fun rateLimit(update: AccountRateLimitsUpdatedNotification): Signal? {
        val snapshot = update.rateLimits
        val key = snapshot.limitId ?: lastLimitId
        val previous = limits[key]
        val merged =
            Limit(
                limitId = snapshot.limitId ?: previous?.limitId,
                limitName = snapshot.limitName ?: previous?.limitName,
                primary = snapshot.primary?.let(::windowOf) ?: previous?.primary,
                secondary = snapshot.secondary?.let(::windowOf) ?: previous?.secondary,
                reached = snapshot.rateLimitReachedType?.let(::wireOf),
            )
        limits[key] = merged
        lastLimitId = key
        if (merged == previous) return null
        return Signal { sessionId, turnId ->
            RunnerEvent.RateLimit(sessionId, turnId, merged.limitId, merged.limitName, merged.primary, merged.secondary, merged.reached)
        }
    }

    private fun tokensOf(total: TokenUsageBreakdown): Tokens {
        val cacheWrite = total.cacheWriteInputTokens ?: 0
        return Tokens(
            input = (total.inputTokens - total.cachedInputTokens - cacheWrite).coerceAtLeast(0),
            output = total.outputTokens,
            cacheRead = total.cachedInputTokens,
            cacheCreation = cacheWrite,
        )
    }

    private fun windowOf(window: RateLimitWindow) =
        RunnerEvent.RateLimit.Window(window.usedPercent, window.windowDurationMins, window.resetsAt?.let(Instant::fromEpochSeconds))

    private fun wireOf(reached: RateLimitReachedType): String =
        CodexJson.format
            .encodeToJsonElement(RateLimitReachedType.serializer(), reached)
            .jsonPrimitive.content

    private fun unreadable(method: String): Signal {
        val text = "loop: the $method notification does not match the pinned schema, skipped"
        val note = RunnerEvent.Output(RunnerEvent.Output.Stream.Stderr, text)
        return Signal { _, _ -> note }
    }

    private fun <T> decode(
        serializer: KSerializer<T>,
        params: JsonElement?,
    ): T? =
        try {
            params?.let { CodexJson.format.decodeFromJsonElement(serializer, it) }
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is a wrong JSON type for a member.
            null
        }

    companion object {
        const val TOKEN_USAGE = "thread/tokenUsage/updated"
        const val RATE_LIMITS = "account/rateLimits/updated"
        const val MODEL_REROUTED = "model/rerouted"

        /** The notification kinds of the pinned schema the telemetry reads. */
        val METHODS: Set<String> = setOf(TOKEN_USAGE, RATE_LIMITS)
    }
}
