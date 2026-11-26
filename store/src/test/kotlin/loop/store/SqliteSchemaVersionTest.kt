package loop.store

import loop.api.Redactor
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * bl-191: a store whose schema version is newer than the build's is refused, never opened or migrated. A build never
 * writes a version higher than its own, so the store file is set one version ahead by hand, which is what a build with
 * an older [SqliteEventStore.SCHEMA_VERSION] finds after a newer build migrated the shared store.
 */
class SqliteSchemaVersionTest {
    @TempDir
    lateinit var tmp: Path

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val projectRoot: Path get() = Files.createDirectories(tmp.resolve("project"))
    private val storeUrl: String get() = "jdbc:sqlite:${loopHome.resolve(SqliteEventStore.FILE_NAME)}"

    private fun open(): Result<SqliteEventStore> = SqliteEventStore.open(loopHome, projectRoot, Redactor.NONE)

    private fun userVersion(): Int =
        DriverManager.getConnection(storeUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `bl-191 a store with a newer schema version is refused and left at its version`() {
        open().getOrThrow().close()
        val newer = SqliteEventStore.SCHEMA_VERSION + 1
        DriverManager.getConnection(storeUrl).use { conn ->
            conn.createStatement().use { st -> st.execute("PRAGMA user_version = $newer") }
        }

        val error = open().exceptionOrNull()

        assertIs<StoreError.Unavailable>(error)
        assertEquals("Store schema version $newer is newer than ${SqliteEventStore.SCHEMA_VERSION}", error.message)
        assertEquals(newer, userVersion())
    }
}
