package loop.resource.ios

import loop.api.AttemptId
import loop.api.ResourceDeclaration
import loop.api.ResourceException
import loop.api.ResourceHolder
import loop.api.ResourceProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The iOS simulator provider (m4-27, story 124, PLAN 6.9 and 12), serving `ios-simulator`. Each resource gets a self
 * managed simulator, created from a device type and a runtime or cloned from a base (see [SimulatorSettings]), booted
 * and waited for in [prepare], which returns its `udid`, then shut down and deleted in [release]. Every `xcrun` call
 * has a timeout: the create or clone, the boot and the wait share the prepare timeout, every other call has
 * [commandTimeout].
 *
 * The simulator is named [PREFIX], the resource id, a dash, [pid], the process that prepared it, `loop run`, then `@`
 * and the attempt that holds the lock (bl-208), made safe for a name. The attempt never holds an `@`, so the last one
 * ends the resource id and the pid, whatever the resource id holds. The lock is keyed by kind and id across the machine,
 * so one resource has at most one live holder. The provider only
 * ever shuts down or deletes a simulator of that shape whose process is this one or no longer [alive]: so a Loop run
 * on another Loop home of the machine, such as the one of a test, never removes the simulator of a live run, and a
 * simulator a person named never matches. [recover] also keeps those whose resource a live `ios-simulator` holder has.
 */
public class IosSimulatorProvider internal constructor(
    private val xcrun: Xcrun,
    private val commandTimeout: Duration = COMMAND_TIMEOUT,
    private val pid: Long = ProcessHandle.current().pid(),
    private val alive: (Long) -> Boolean = { other -> ProcessHandle.of(other).map { it.isAlive }.orElse(false) },
) : ResourceProvider {
    /** The provider `ServiceLoader` builds, running the `xcrun` of the host's `PATH` in the host's environment. */
    public constructor() : this(Xcrun(System.getenv()))

    override val kinds: Set<String> = setOf(KIND)

    override fun prepare(
        resource: ResourceDeclaration,
        attemptId: AttemptId,
    ): Map<String, String> {
        val settings = SimulatorSettings.of(resource)
        val started = TimeSource.Monotonic.markNow()

        fun left() = settings.prepareTimeout - started.elapsedNow()
        val name = PREFIX + resource.id + "-" + pid + "@" + safe(attemptId.value)
        val devices = xcrun.devices(commandTimeout)
        // The lock is the caller's, so a removable simulator of the resource is stale: a failed release, a crash or a
        // prepare of the same attempt whose outcome was lost left it (bl-208).
        ofResource(devices, resource).forEach(::remove)
        val output =
            if (settings.base != null) {
                val base =
                    devices.firstOrNull { it.udid == settings.base } ?: devices.firstOrNull { it.name == settings.base }
                        ?: throw ResourceException("the base simulator \"${settings.base}\" of ${resource.id} is not on the machine")
                if (!base.shutDown) {
                    throw ResourceException("the base simulator $base of ${resource.id} is ${base.state}, a clone needs it shut down")
                }
                xcrun.run(listOf("simctl", "clone", base.udid, name), left())
            } else {
                xcrun.run(listOfNotNull("simctl", "create", name, settings.deviceType, settings.runtime), left())
            }
        val udid =
            output.lines().lastOrNull { it.isNotBlank() }?.trim()
                ?: throw ResourceException("xcrun simctl printed no UDID for the simulator $name of ${resource.id}")
        xcrun.run(listOf("simctl", "boot", udid), left())
        xcrun.run(listOf("simctl", "bootstatus", udid, "-b"), left())
        return mapOf(UDID to udid)
    }

    override fun release(resource: ResourceDeclaration) {
        ofResource(xcrun.devices(commandTimeout), resource).forEach(::remove)
    }

    override fun readOnlyCheck(resource: ResourceDeclaration): List<String> {
        val devices = xcrun.devices(commandTimeout)
        val left = ofResource(devices, resource).map { "the simulator $it of ${resource.id} is still on the machine, ${it.state}" }
        val base = resource.provider[SimulatorSettings.BASE]?.trim()?.takeIf { it.isNotEmpty() } ?: return left
        val kept = devices.firstOrNull { it.udid == base } ?: devices.firstOrNull { it.name == base }
        return left +
            when {
                kept == null -> listOf("the base simulator \"$base\" of ${resource.id} is no longer on the machine")
                !kept.shutDown -> listOf("the base simulator $kept of ${resource.id} is ${kept.state}, not shut down")
                else -> emptyList()
            }
    }

    /** Removes what a crashed run left, and nothing on a host with no `xcrun`, where no simulator can be. */
    override fun recover(liveHolders: List<ResourceHolder>) {
        if (!xcrun.available()) return
        val held = liveHolders.filter { it.kind in kinds }.map { it.resourceId }.toSet()
        xcrun.devices(commandTimeout).filter { simulator -> removable(simulator)?.let { it.first !in held } == true }.forEach(::remove)
    }

    /** The simulators of [devices] made for [resource] that the provider may remove. */
    private fun ofResource(
        devices: List<Simulator>,
        resource: ResourceDeclaration,
    ) = devices.filter { removable(it)?.first == resource.id }

    /**
     * The resource id and the pid of [simulator] when the provider may remove it: a name of the provider's shape whose
     * process is this one or no longer alive. Null for every other simulator.
     */
    private fun removable(simulator: Simulator): Pair<String, Long>? {
        val match = NAME.matchEntire(simulator.name) ?: return null
        val owner = match.groupValues[2].toLongOrNull() ?: return null
        if (owner != pid && alive(owner)) return null
        return match.groupValues[1] to owner
    }

    /** Shuts [simulator] down when it is not, then deletes it. */
    private fun remove(simulator: Simulator) {
        if (!simulator.shutDown) xcrun.run(listOf("simctl", "shutdown", simulator.udid), commandTimeout)
        xcrun.run(listOf("simctl", "delete", simulator.udid), commandTimeout)
    }

    public companion object {
        /** The kind this provider serves. */
        public const val KIND: String = "ios-simulator"

        /** The key of the UDID in what [prepare] returns. */
        public const val UDID: String = "udid"

        /** The start of the name of every simulator the provider creates, then the resource id, a dash, a pid, `@` and an attempt. */
        public const val PREFIX: String = "loop-sim-"

        /** The timeout of each `xcrun` call outside the create or clone, the boot and the wait. */
        public val COMMAND_TIMEOUT: Duration = 60.seconds

        private val NAME = Regex(Regex.escape(PREFIX) + "(.+)-([0-9]+)@([A-Za-z0-9._-]+)")

        /** [attempt] with each character but a letter, a digit, `.`, `_` and `-` replaced by `_`. */
        internal fun safe(attempt: String): String = attempt.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
