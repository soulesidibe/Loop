package loop.store

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceLockUpdate
import loop.api.ResourceRequest
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m4-50: the resource locks and their queue in the shared store. A lock row is keyed by kind and resource id, never by
 * project, so every handle on the machine's store, of any project, sees the same lock, and the rows survive a reopen.
 * The queue rules are the core's (`ResourceQueue`); here [request] plays a minimal one, enough to show the store keeps
 * what a change writes, in its order, one change at a time.
 */
class SqliteResourceLocksTest {
    @TempDir
    lateinit var tmp: Path

    private val first = ProjectId("aaaaaaaaaaaaaaaa")
    private val second = ProjectId("bbbbbbbbbbbbbbbb")
    private val at = Instant.parse("2026-11-25T10:00:00Z")
    private val loopHome: Path get() = tmp.resolve("loop-home")

    private fun open(
        project: String = "project",
        redactor: Redactor = Redactor.NONE,
    ): SqliteEventStore = SqliteEventStore.open(loopHome, Files.createDirectories(tmp.resolve(project)), redactor).getOrThrow()

    private fun asking(
        attempt: String,
        project: ProjectId = first,
        kind: String = "avd",
        id: String = "api35",
        second: Int = 0,
    ) = ResourceRequest(kind, id, project, "issue-$attempt", "T-$attempt", AttemptId(attempt), at + second.seconds)

    private fun ResourceRequest.lock(prepared: Map<String, String> = emptyMap()) =
        ResourceLock(kind, resourceId, projectId, issueId, issueIdentifier, attemptId, requestedAt, prepared)

    /** Grants [request] when its kind and id has no lock, else queues it at the end: true when granted. */
    private suspend fun SqliteEventStore.request(request: ResourceRequest): Boolean =
        updateResourceLocks { rows ->
            val held = rows.locks.any { it.kind == request.kind && it.resourceId == request.resourceId }
            if (held) {
                ResourceLockUpdate(rows.copy(queue = rows.queue + request), false)
            } else {
                ResourceLockUpdate(rows.copy(locks = rows.locks + request.lock()), true)
            }
        }.getOrThrow()

    @Test
    fun `lock and queue rows survive a reopen, with the prepared map and the queue order`() =
        runTest {
            val rows =
                ResourceLockRows(
                    locks =
                        listOf(
                            asking("a1").lock(prepared = mapOf("serial" to "emulator-5554", "udid" to "X-1")),
                            asking("a2", kind = "ios-simulator", id = "iphone-16").lock(),
                        ),
                    queue =
                        listOf(
                            asking("a3", second = 3),
                            asking("a4", project = second, kind = "ios-simulator", id = "iphone-16", second = 1),
                            asking("a5", second = 2),
                        ),
                )
            open().use { store -> assertEquals("written", store.updateResourceLocks { ResourceLockUpdate(rows, "written") }.getOrThrow()) }

            open().use { store -> assertEquals(rows, store.resourceLockRows().getOrThrow()) }
        }

    @Test
    fun `two handles on one store file see the same lock and each other's changes`() =
        runTest {
            open().use { one ->
                open().use { two ->
                    assertTrue(one.request(asking("a1")))

                    assertEquals(listOf(asking("a1").lock()), two.resourceLockRows().getOrThrow().locks)
                    assertEquals(false, two.request(asking("a2", second = 1)))
                    assertEquals(listOf(asking("a2", second = 1)), one.resourceLockRows().getOrThrow().queue)
                }
            }
        }

    @Test
    fun `two projects asking for one kind and id share one lock, and the key refuses a second lock row`() =
        runTest {
            open("project-a").use { a ->
                open("project-b").use { b ->
                    assertTrue(a.request(asking("a1", project = first)))
                    assertEquals(false, b.request(asking("b1", project = second, second = 1)))

                    val rows = b.resourceLockRows().getOrThrow()
                    assertEquals(listOf(first), rows.locks.map { it.projectId })
                    assertEquals(listOf(second), rows.queue.map { it.projectId })
                    // A change that would grant the resource of another project's lock a second time fails whole.
                    val twice = rows.copy(locks = rows.locks + asking("b1", project = second).lock())
                    val refused = b.updateResourceLocks { ResourceLockUpdate(twice, Unit) }
                    assertTrue(refused.isFailure)
                    assertEquals(rows, a.resourceLockRows().getOrThrow())
                }
            }
        }

    @Test
    fun `requests of several handles at once are applied one at a time, so exactly one gets the lock`() {
        val handles = (1..4).map { open("project-$it") }
        try {
            val barrier = CyclicBarrier(handles.size)
            val granted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads =
                handles.mapIndexed { index, store ->
                    thread {
                        try {
                            barrier.await()
                            val attempt = "a${index + 1}"
                            granted[attempt] = runBlocking { store.request(asking(attempt, second = index)) }
                        } catch (e: Throwable) {
                            failures += e
                        }
                    }
                }
            threads.forEach { it.join() }
            assertEquals(emptyList(), failures.toList())

            val rows = runBlocking { handles.first().resourceLockRows().getOrThrow() }
            assertEquals(1, granted.values.count { it }, granted.toString())
            assertEquals(1, rows.locks.size)
            assertEquals(granted.filterValues { it }.keys, rows.locks.map { it.attemptId.value }.toSet())
            assertEquals(3, rows.queue.size)
            assertEquals(granted.filterValues { !it }.keys, rows.queue.map { it.attemptId.value }.toSet())
        } finally {
            handles.forEach { it.close() }
        }
    }

    @Test
    fun `a freed lock and its dropped queue rows are gone for every handle`() =
        runTest {
            open().use { store ->
                store.request(asking("a1"))
                store.request(asking("a2", second = 1))
                store.updateResourceLocks { ResourceLockUpdate(ResourceLockRows(), Unit) }.getOrThrow()
            }

            open().use { store -> assertEquals(ResourceLockRows(), store.resourceLockRows().getOrThrow()) }
        }

    @Test
    fun `the identifier and the prepared values go through the redactor`() =
        runTest {
            val secret = "sk-test-0123456789abcdef"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TEST_SECRET" to secret)) }
            open(redactor = redactor).use { store ->
                val lock = asking("a1").copy(issueIdentifier = "T-$secret").lock(prepared = mapOf("token" to secret))
                store.updateResourceLocks { ResourceLockUpdate(ResourceLockRows(locks = listOf(lock)), Unit) }.getOrThrow()

                val read = store.resourceLockRows().getOrThrow().locks.single()
                assertEquals(false, secret in read.issueIdentifier)
                assertEquals(false, read.prepared.values.any { secret in it })
                assertContains(read.issueIdentifier, "T-")
            }
        }

    @Test
    fun `a version seven store is migrated to version eight with the resource tables`() =
        runTest {
            open().use { store -> store.request(asking("a1")) }
            // A store left at version 7 by the m3-16 build: no resource tables.
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE resource_locks")
                    st.execute("DROP TABLE resource_queue")
                    st.execute("PRAGMA user_version = 7")
                }
            }

            open().use { store ->
                assertEquals(ResourceLockRows(), store.resourceLockRows().getOrThrow())
                assertTrue(store.request(asking("a2")))
            }
            val version =
                DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                    conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
                }
            assertEquals(8, version)
        }
}
