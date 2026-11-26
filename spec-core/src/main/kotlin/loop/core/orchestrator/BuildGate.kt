package loop.core.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import loop.core.log.LogContext

/**
 * The build gate of a project (bl-188): at most [capacity] verification builds and merge check builds run at once,
 * whoever started them, an attempt's verification, a merge check or the rerun of a restored `verify` wait. A build that
 * finds no room waits in a first in, first out queue. [capacity] is read on every admission, so a reload changes what
 * starts next and never stops a build already running; a raised capacity admits queued builds at the next [admit].
 * Agent slots are a separate matter: a restored wait still takes none (bl-173), only its build passes here.
 *
 * [onQueued] is told once when a build has to wait, with the number of builds ahead of it, running or queued.
 */
internal class BuildGate(
    private val capacity: () -> Int,
    private val onQueued: (LogContext, Int) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val queue = ArrayDeque<CompletableDeferred<Unit>>()

    private var builds = 0

    /** The builds running now. */
    val running: Int
        get() = synchronized(lock) { builds }

    /** The builds waiting for room now. */
    val queued: Int
        get() = synchronized(lock) { queue.size }

    /**
     * Runs [block] once the gate has room for it, after every build queued before it. Waiting can be cancelled, which
     * leaves the queue; the room is given back when [block] ends, also on an exception or a cancellation.
     */
    suspend fun <T> withBuild(
        context: LogContext,
        block: suspend () -> T,
    ): T {
        acquire(context)
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Admits the queued builds the capacity has room for now, oldest first, for a capacity raised since. */
    fun admit() {
        synchronized(lock) { admitLocked() }
    }

    private suspend fun acquire(context: LogContext) {
        val ticket = CompletableDeferred<Unit>()
        val ahead =
            synchronized(lock) {
                if (queue.isEmpty() && builds < capacity()) {
                    builds++
                    return
                }
                queue.addLast(ticket)
                builds + queue.size - 1
            }
        onQueued(context, ahead)
        try {
            ticket.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                // Admitted just before the cancel came: the room it was given goes to the next build.
                if (!queue.remove(ticket)) {
                    builds--
                    admitLocked()
                }
            }
            throw e
        }
    }

    private fun release() {
        synchronized(lock) {
            builds--
            admitLocked()
        }
    }

    private fun admitLocked() {
        while (queue.isNotEmpty() && builds < capacity()) {
            builds++
            queue.removeFirst().complete(Unit)
        }
    }
}
