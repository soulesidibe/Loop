package loop.core.resources

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One mutex per key, such as a lock of [ResourceGrants] by holder, kind and resource id (bl-208). A key is kept only
 * while some caller holds or waits for its mutex, and dropped when the last one leaves (bl-219): so a freed lock leaves
 * nothing behind, and a caller arriving while another holds the key always gets the same mutex, never a fresh one.
 */
internal class LockGuards<K : Any> {
    /** A mutex and the number of callers that hold it or wait for it. */
    private class Guard {
        val mutex = Mutex()
        var users = 0
    }

    private val guards = HashMap<K, Guard>()

    /** Runs [block] under the mutexes of [keys], taken in the order given, so callers that sort them never deadlock. */
    suspend fun <T> withLocks(
        keys: List<K>,
        block: suspend () -> T,
    ): T {
        suspend fun under(index: Int): T = if (index == keys.size) block() else withLock(keys[index]) { under(index + 1) }
        return under(0)
    }

    /** Takes the mutex of [key] when it is free, and returns what unlocks it; null when a caller holds it. */
    fun tryLock(key: K): (() -> Unit)? {
        val guard = enter(key)
        if (!guard.mutex.tryLock()) {
            leave(key, guard)
            return null
        }
        return {
            guard.mutex.unlock()
            leave(key, guard)
        }
    }

    /** The keys some caller holds or waits for now. */
    fun keys(): Set<K> = synchronized(guards) { guards.keys.toSet() }

    private suspend fun <T> withLock(
        key: K,
        block: suspend () -> T,
    ): T {
        val guard = enter(key)
        try {
            return guard.mutex.withLock { block() }
        } finally {
            leave(key, guard)
        }
    }

    private fun enter(key: K): Guard = synchronized(guards) { guards.getOrPut(key) { Guard() }.also { it.users++ } }

    private fun leave(
        key: K,
        guard: Guard,
    ) = synchronized(guards) {
        guard.users--
        if (guard.users == 0) guards.remove(key)
    }
}
