package loop.store

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.LedgerAttempt
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m3-16: the machine slots of the shared store. A slot is a row written in one `BEGIN IMMEDIATE` transaction that
 * counts the live slots of every project first, so several Loop processes never hold more than the machine cap.
 */
class SqliteMachineSlotsTest {
    @TempDir
    lateinit var tmp: Path

    private val first = ProjectId("aaaaaaaaaaaaaaaa")
    private val second = ProjectId("bbbbbbbbbbbbbbbb")
    private val at = Instant.parse("2026-11-15T10:00:00Z")
    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun open(): SqliteEventStore = SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE).getOrThrow()

    private fun slot(
        holder: String,
        project: ProjectId = first,
        runId: String = "r1",
        pid: Long = 100,
    ) = MachineSlot(project, holder, RunId(runId), pid, at)

    private val everyoneLive: (MachineSlot) -> Boolean = { true }

    @Test
    fun `a slot is written under the cap, refused at the cap, and freed by its release`() =
        runTest {
            open().use { store ->
                assertTrue(store.claimMachineSlot(slot("h1"), cap = 2, everyoneLive).getOrThrow())
                assertTrue(
                    store.claimMachineSlot(slot("h2", project = second, runId = "r2", pid = 200), cap = 2, everyoneLive).getOrThrow(),
                )
                assertFalse(store.claimMachineSlot(slot("h3"), cap = 2, everyoneLive).getOrThrow())
                assertEquals(listOf("h1", "h2"), store.machineSlots().getOrThrow().map { it.holder })
                assertEquals(slot("h2", project = second, runId = "r2", pid = 200), store.machineSlots().getOrThrow()[1])

                store.releaseMachineSlot(first, "h1").getOrThrow()
                assertTrue(store.claimMachineSlot(slot("h3"), cap = 2, everyoneLive).getOrThrow())
                assertEquals(listOf("h2", "h3"), store.machineSlots().getOrThrow().map { it.holder })
            }
        }

    @Test
    fun `a slot the liveness check rejects is deleted and not counted`() =
        runTest {
            open().use { store ->
                assertTrue(store.claimMachineSlot(slot("dead", runId = "crashed"), cap = 1, everyoneLive).getOrThrow())

                val live: (MachineSlot) -> Boolean = { it.runId != RunId("crashed") }
                assertTrue(store.claimMachineSlot(slot("h1"), cap = 1, live).getOrThrow())
                assertEquals(listOf("h1"), store.machineSlots().getOrThrow().map { it.holder })
            }
        }

    @Test
    fun `two writers racing for the last slot, one wins`() {
        open().use { it }
        repeat(ROUNDS) { round ->
            val stores = listOf(open(), open())
            try {
                // One slot held already: a cap of two leaves one, and both processes want it at the same moment.
                runBlocking { stores[0].claimMachineSlot(slot("held-$round"), cap = 2, everyoneLive).getOrThrow() }
                val barrier = CyclicBarrier(stores.size)
                val failure = AtomicReference<Throwable>()
                val won = IntArray(stores.size)
                val threads =
                    stores.mapIndexed { i, store ->
                        thread {
                            try {
                                barrier.await()
                                val claimed =
                                    runBlocking {
                                        store.claimMachineSlot(slot("w$i-$round", runId = "r$i", pid = 100L + i), cap = 2, everyoneLive)
                                    }.getOrThrow()
                                if (claimed) won[i] = 1
                            } catch (e: Throwable) {
                                failure.set(e)
                            }
                        }
                    }
                threads.forEach { it.join() }
                failure.get()?.let { throw it }
                assertEquals(1, won.sum(), "round $round: exactly one writer takes the last slot")
                assertEquals(2, runBlocking { stores[0].machineSlots() }.getOrThrow().size)
                runBlocking {
                    stores[0].machineSlots().getOrThrow().forEach {
                        stores[0]
                            .releaseMachineSlot(
                                it.projectId,
                                it.holder,
                            ).getOrThrow()
                    }
                }
            } finally {
                stores.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a claim beside another writing process never fails on a stale snapshot`() {
        open().use { store ->
            val failures =
                whileAnotherStoreWrites(::open, first) {
                    (1..CLAIMS).mapNotNull { n ->
                        runBlocking {
                            store.claimMachineSlot(slot("h$n"), cap = CLAIMS, everyoneLive).exceptionOrNull()
                                ?: store.releaseMachineSlot(first, "h$n").exceptionOrNull()
                        }
                    }
                }
            assertEquals(emptyList(), failures.map { it.message })
        }
    }

    @Test
    fun `the attempt rows of every project are read for the machine day`() =
        runTest {
            open().use { store ->
                store.recordAttempt(row("a1", first, finishedAt = Instant.parse("2026-11-15T09:00:00Z"))).getOrThrow()
                store.recordAttempt(row("a2", second, finishedAt = Instant.parse("2026-11-15T09:30:00Z"))).getOrThrow()
                store.recordAttempt(row("old", second, finishedAt = Instant.parse("2026-11-13T09:30:00Z"))).getOrThrow()

                val rows = store.machineAttemptRowsSince(Instant.parse("2026-11-14T10:00:00Z")).getOrThrow()
                assertEquals(listOf("a1", "a2"), rows.map { it.attempt.attemptId.value })
            }
        }

    @Test
    fun `a version six store is migrated to version seven with the machine slots table`() =
        runTest {
            open().use { store -> store.recordAttempt(row("a1", first, Instant.parse("2026-11-15T09:00:00Z"))).getOrThrow() }
            // A store left at version 6 by the m2-19 build: no machine_slots.
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE machine_slots")
                    st.execute("PRAGMA user_version = 6")
                }
            }

            open().use { store ->
                assertTrue(store.claimMachineSlot(slot("h1"), cap = 1, everyoneLive).getOrThrow())
                assertEquals(1, store.attemptRows(first, "1").getOrThrow().size)
            }
            val version =
                DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                    conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
                }
            assertEquals(7, version)
        }

    private fun row(
        id: String,
        project: ProjectId,
        finishedAt: Instant,
    ) = LedgerAttempt(
        projectId = project,
        runId = RunId("r1"),
        issueId = "1",
        identifier = "t-1",
        attemptId = AttemptId(id),
        setup = LedgerSetup("files", "code", "command", null, "t".repeat(64), null),
        startedAt = finishedAt,
        finishedAt = finishedAt,
        outcome = "succeeded",
        reason = null,
        turns = 1,
        issueState = "review",
        tokens = LedgerTokens(100, 20, 0, 0),
        costUsd = 0.5,
        checks = null,
        guards = null,
        verdict = null,
    )

    private companion object {
        const val ROUNDS = 20
        const val CLAIMS = 30
    }
}
