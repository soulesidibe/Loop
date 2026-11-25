package loop.resource.ios

import loop.api.ResourceDeclaration
import loop.api.ResourceException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `provider` map of an `ios-simulator` entry (m4-27), checked when the resource is prepared: a [deviceType], with
 * an optional [runtime], to create a simulator, or a [base] simulator, by name or UDID, to clone, and the
 * [prepareTimeout] of the create or clone, the boot and the wait together.
 */
internal data class SimulatorSettings(
    val deviceType: String?,
    val runtime: String?,
    val base: String?,
    val prepareTimeout: Duration,
) {
    companion object {
        const val DEVICE_TYPE = "device_type"
        const val RUNTIME = "runtime"
        const val BASE = "base"
        const val PREPARE_TIMEOUT = "prepare_timeout_seconds"
        val KEYS = listOf(DEVICE_TYPE, RUNTIME, BASE, PREPARE_TIMEOUT)
        val DEFAULT_PREPARE_TIMEOUT = 300.seconds
        val PREPARE_TIMEOUT_LIMITS = 1L..3600L

        /** The settings of [resource], or a [ResourceException] naming the resource and the key at fault. */
        fun of(resource: ResourceDeclaration): SimulatorSettings {
            val id = resource.id
            resource.provider.keys.firstOrNull { it !in KEYS }?.let {
                throw ResourceException("provider.$it of $id is not a key of the ios-simulator provider, use one of ${KEYS.joinToString()}")
            }

            fun value(key: String) = resource.provider[key]?.trim()?.takeIf { it.isNotEmpty() }
            val deviceType = value(DEVICE_TYPE)
            val runtime = value(RUNTIME)
            val base = value(BASE)
            when {
                deviceType != null && base != null ->
                    throw ResourceException("$id sets both provider.$DEVICE_TYPE and provider.$BASE, keep one")
                deviceType == null && base == null ->
                    throw ResourceException(
                        "$id needs provider.$DEVICE_TYPE, such as \"iPhone 16\", or provider.$BASE, a simulator to clone",
                    )
                runtime != null && base != null ->
                    throw ResourceException("$id sets provider.$RUNTIME with provider.$BASE, a clone keeps the runtime of its base")
            }
            val timeout =
                value(PREPARE_TIMEOUT)?.let { text ->
                    text.toLongOrNull()?.takeIf { it in PREPARE_TIMEOUT_LIMITS }?.seconds
                        ?: throw ResourceException(
                            "provider.$PREPARE_TIMEOUT of $id must be an integer from ${PREPARE_TIMEOUT_LIMITS.first} to " +
                                "${PREPARE_TIMEOUT_LIMITS.last}, not \"$text\"",
                        )
                } ?: DEFAULT_PREPARE_TIMEOUT
            return SimulatorSettings(deviceType, runtime, base, timeout)
        }
    }
}
