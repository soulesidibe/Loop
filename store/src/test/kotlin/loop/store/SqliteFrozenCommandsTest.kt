package loop.store

import kotlinx.coroutines.test.runTest
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.SecretRedactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** m2-09: the acceptance command list of each issue, frozen at its first dispatch, and the version 5 migration. */
class SqliteFrozenCommandsTest {
    @TempDir
    lateinit var tmp: Path

    private val projectA = ProjectId("aaaaaaaaaaaaaaaa")
    private val projectB = ProjectId("bbbbbbbbbbbbbbbb")

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))

    private fun open(redactor: Redactor = Redactor.NONE): SqliteEventStore =
        SqliteEventStore.open(loopHome, projectRoot, redactor).getOrThrow()

    private fun frozen(
        digest: String = "sha256:first",
        count: Int = 2,
        projectId: ProjectId = projectA,
    ) = FrozenCommands(projectId, "1", "t-1", digest, count, Instant.parse("2026-11-12T09:00:00Z"))

    @Test
    fun `the first freeze writes, a later one keeps the first list and says so`() =
        runTest {
            open().use { store ->
                val first = store.freeze(frozen()).getOrThrow()
                assertTrue(first.written)
                assertEquals(frozen(), first.stored)

                val later = store.freeze(frozen(digest = "sha256:second", count = 1)).getOrThrow()
                assertFalse(later.written)
                assertEquals(frozen(), later.stored)
            }
        }

    @Test
    fun `a changed list waits for loop resume, which makes it the frozen one`() =
        runTest {
            open().use { store ->
                store.freeze(frozen()).getOrThrow()
                store.markChanged(projectA, "1", "sha256:second", 1).getOrThrow()

                val waiting = store.freeze(frozen(digest = "sha256:third")).getOrThrow()
                assertEquals(frozen().copy(changedDigest = "sha256:second", changedCount = 1), waiting.stored)

                val accepted = store.acceptChanged(projectA, "1", "sha256:second").getOrThrow()
                assertEquals(frozen(digest = "sha256:second", count = 1), accepted)
                assertEquals(accepted, store.freeze(frozen()).getOrThrow().stored)
                assertEquals(accepted, store.frozen(projectA, "1").getOrThrow())
                assertNull(store.acceptChanged(projectA, "1", "sha256:second").getOrThrow(), "nothing waits any more")
            }
        }

    @Test
    fun `an accept names the list it accepts, any other list leaves the changed one waiting`() =
        runTest {
            open().use { store ->
                store.freeze(frozen()).getOrThrow()
                store.markChanged(projectA, "1", "sha256:second", 1).getOrThrow()

                assertNull(store.acceptChanged(projectA, "1", "sha256:third").getOrThrow())
                assertEquals(frozen().copy(changedDigest = "sha256:second", changedCount = 1), store.frozen(projectA, "1").getOrThrow())
            }
        }

    @Test
    fun `clearing the changed list keeps the frozen one, nothing is left to accept`() =
        runTest {
            open().use { store ->
                store.freeze(frozen()).getOrThrow()
                store.markChanged(projectA, "1", "sha256:second", 1).getOrThrow()

                store.clearChanged(projectA, "1").getOrThrow()

                assertEquals(frozen(), store.frozen(projectA, "1").getOrThrow())
                assertNull(store.acceptChanged(projectA, "1", "sha256:second").getOrThrow())
                store.clearChanged(projectA, "2").getOrThrow()
                assertNull(store.frozen(projectA, "2").getOrThrow(), "no record, nothing written")
            }
        }

    @Test
    fun `a change or an accept of an issue with no record does nothing`() =
        runTest {
            open().use { store ->
                store.markChanged(projectA, "1", "sha256:second", 1).getOrThrow()
                assertNull(store.acceptChanged(projectA, "1", "sha256:second").getOrThrow())
                assertTrue(store.freeze(frozen()).getOrThrow().written)
            }
        }

    @Test
    fun `lists are kept per project and survive closing and reopening the store`() =
        runTest {
            open().use { store ->
                store.freeze(frozen(projectId = projectA)).getOrThrow()
                store.freeze(frozen(digest = "sha256:b", projectId = projectB)).getOrThrow()
            }

            open().use { store ->
                assertEquals(frozen(), store.freeze(frozen(digest = "sha256:x")).getOrThrow().stored)
                assertEquals(
                    frozen(digest = "sha256:b", projectId = projectB),
                    store.freeze(frozen(digest = "sha256:x", projectId = projectB)).getOrThrow().stored,
                )
            }
        }

    @Test
    fun `the identifier is redacted before the insert`() =
        runTest {
            val secret = "s3cr3t-value"
            val redactor = SecretRedactor().apply { learn(mapOf("LOOP_TRACKER_TOKEN" to secret)) }
            open(redactor).use { store ->
                val stored = store.freeze(frozen().copy(identifier = "t-$secret")).getOrThrow()

                assertEquals(
                    "t-[redacted:LOOP_TRACKER_TOKEN]",
                    store
                        .freeze(frozen())
                        .getOrThrow()
                        .stored.identifier,
                )
                assertTrue(stored.written)
            }
        }

    @Test
    fun `a version four store is migrated to the current version`() =
        runTest {
            open().use { }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TABLE acceptance_commands")
                    st.execute("PRAGMA user_version = 4")
                }
            }

            open().use { store -> assertTrue(store.freeze(frozen()).getOrThrow().written) }
            DriverManager.getConnection("jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA user_version").use { rs ->
                        rs.next()
                        assertEquals(SqliteEventStore.SCHEMA_VERSION, rs.getInt(1))
                    }
                }
            }
        }
}
