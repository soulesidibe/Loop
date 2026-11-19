package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The swap of `loop install` (m1-12): each failing rename leaves a message that says
 * where the previous install and the new build really are. The renames are injected, so every
 * branch runs without a real file system failure.
 */
class InstallSwapTest {
    @TempDir
    lateinit var root: Path

    private val target: Path by lazy { root.resolve("install") }
    private val tmp: Path by lazy { root.resolve("install.tmp") }
    private val old: Path by lazy { root.resolve("install.old") }

    /** A previous install at [target] and a new build at [tmp]. */
    private fun bothBuilds() {
        target.createDirectories().resolve("build-info.properties").writeText("version=previous\n")
        tmp.createDirectories().resolve("build-info.properties").writeText("version=new\n")
    }

    /** Renames for real, except the calls whose number (from 1) is in [failing]. */
    private fun movesFailing(vararg failing: Int): (Path, Path) -> Unit {
        var call = 0
        return { from, to ->
            if (++call in failing) throw IOException("disk says no")
            Files.move(from, to)
        }
    }

    @Test
    fun `a failure to move the previous install aside leaves it in place`() {
        bothBuilds()

        val result = swapInstall(tmp, target, old, movesFailing(1))

        assertEquals(
            SwapResult.Failed(
                "cannot move the previous install at $target aside: disk says no. " +
                    "$target is unchanged, the new build is at $tmp",
            ),
            result,
        )
        assertEquals("version=previous\n", target.resolve("build-info.properties").readText())
        assertTrue(tmp.exists())
    }

    @Test
    fun `a failure to put the new build in place moves the previous install back`() {
        bothBuilds()

        val result = swapInstall(tmp, target, old, movesFailing(2))

        assertEquals(
            SwapResult.Failed(
                "cannot replace $target with the new build: disk says no. " +
                    "The previous install is restored at $target, the new build is at $tmp",
            ),
            result,
        )
        assertEquals("version=previous\n", target.resolve("build-info.properties").readText())
        assertFalse(old.exists())
        assertTrue(tmp.exists())
    }

    @Test
    fun `when the previous install cannot be moved back the message says it is still aside`() {
        bothBuilds()

        val result = swapInstall(tmp, target, old, movesFailing(2, 3))

        assertEquals(
            SwapResult.Failed(
                "cannot replace $target with the new build: disk says no. " +
                    "The previous install is still at $old, the new build is at $tmp",
            ),
            result,
        )
        assertEquals("version=previous\n", old.resolve("build-info.properties").readText())
        assertFalse(target.exists())
        assertTrue(tmp.exists())
    }

    @Test
    fun `a failure with no previous install says so`() {
        tmp.createDirectories().resolve("build-info.properties").writeText("version=new\n")

        val result = swapInstall(tmp, target, old, movesFailing(1))

        assertEquals(
            SwapResult.Failed(
                "cannot install the new build at $target: disk says no. " +
                    "There was no previous install, the new build is at $tmp",
            ),
            result,
        )
        assertFalse(target.exists())
        assertFalse(old.exists())
        assertTrue(tmp.exists())
    }
}
