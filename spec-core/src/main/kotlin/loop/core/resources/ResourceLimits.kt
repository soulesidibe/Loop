package loop.core.resources

/**
 * The machine wide limits of the resource locks (m4-26, PLAN 6.9): at most [ANDROID] Android devices and emulators
 * together, `android-device` and `avd` counted as one pool, and at most [IOS_SIMULATORS] self managed iOS simulators.
 * The store is shared by every project of the machine (ADR 0001), so a limit counts every lock of its pool, of every
 * project. A grant that would pass a limit waits in its queue with the reason `limit`; a kind of no pool has no limit.
 * Declaring more resources than a limit is valid: the extra ones wait their turn.
 */
object ResourceLimits {
    /** Android devices and emulators held at once on the machine, `android-device` and `avd` together. */
    const val ANDROID: Int = 2

    /** Self managed iOS simulators held at once on the machine. */
    const val IOS_SIMULATORS: Int = 2

    /** The kinds that count against one [limit], named [name] in the docs. */
    data class Pool(
        val name: String,
        val kinds: Set<String>,
        val limit: Int,
    )

    val POOLS: List<Pool> =
        listOf(
            Pool("android", setOf("android-device", "avd"), ANDROID),
            Pool("ios-simulator", setOf("ios-simulator"), IOS_SIMULATORS),
        )

    /** The pool [kind] counts against, null when it has no limit. */
    fun poolOf(kind: String): Pool? = POOLS.firstOrNull { kind in it.kinds }
}
