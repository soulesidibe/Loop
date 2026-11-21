package loop.core.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import loop.core.verify.WorkspaceGit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * bl-149: the HEAD and working tree read for the progress between rotations is bounded and cancellable. A read that
 * blocks, as on a FIFO swapped in for a file, gives no facts and a failure once its budget runs out, and a cancel stops
 * the wait at once.
 */
class WorkspaceFactsTest {
    private val root: Path = createTempDirectory("loop-workspace-facts").toRealPath()

    /** Released at the end of each test, so a read left blocked on it ends. */
    private val release = CountDownLatch(1)

    @AfterTest
    fun releaseReads() = release.countDown()

    /** Blocks until [release], ignoring interrupts, as an open of a FIFO with no writer does, then reads nothing. */
    private fun blockUninterruptibly(): WorkspaceFacts.Facts {
        while (true) {
            try {
                release.await()
                return WorkspaceFacts.Facts(null, null, emptyMap())
            } catch (_: InterruptedException) {
                // An open of a FIFO ignores interrupts too.
            }
        }
    }

    private fun git(
        dir: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()}: $output" }
    }

    @Test
    fun `bl-149 a read that blocks past its budget gives no facts and a failure without waiting for it`() =
        runBlocking {
            val facts = WorkspaceFacts(WorkspaceGit(), budget = 300.milliseconds) { _, _ -> blockUninterruptibly() }
            val started = System.nanoTime()

            val read = facts.of(root)

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 5_000, "the read took $elapsedMs ms")
            assertNull(read.head)
            assertNull(read.tree)
            val reason = assertNotNull(read.failures[WorkspaceFacts.WORKSPACE])
            assertTrue("did not finish within 300ms" in reason, reason)
        }

    @Test
    fun `bl-149 a cancel stops the wait at once and cancels the git reads`() =
        runBlocking {
            val started = CompletableDeferred<WorkspaceGit.Cancellation>()
            val facts =
                WorkspaceFacts(WorkspaceGit(), budget = 60.seconds) { _, cancellation ->
                    started.complete(cancellation)
                    blockUninterruptibly()
                }
            val reading = async { facts.of(root) }
            val cancellation = withTimeout(5.seconds) { started.await() }

            withTimeout(5.seconds) { reading.cancelAndJoin() }

            assertTrue(reading.isCancelled)
            assertTrue(cancellation.cancelled)
        }

    @Test
    fun `bl-149 a git work tree gives its HEAD and working tree, a plain folder neither and no failure`() =
        runBlocking {
            val repo = Files.createDirectories(root.resolve("repo"))
            git(repo, "init", "-q")
            repo.resolve("a.txt").writeText("one\n")
            git(repo, "add", "a.txt")
            git(repo, "commit", "-q", "-m", "first")
            val facts = WorkspaceFacts(WorkspaceGit())

            val read = facts.of(repo)
            assertNotNull(read.head)
            assertNotNull(read.tree)
            assertEquals(emptyMap(), read.failures)

            val plain = facts.of(Files.createDirectories(root.resolve("plain")))
            assertEquals(WorkspaceFacts.Facts(null, null, emptyMap()), plain)
        }

    @Test
    fun `bl-149 a HEAD git cannot read is a failure while the working tree still reads`() =
        runBlocking {
            val repo = Files.createDirectories(root.resolve("unborn"))
            git(repo, "init", "-q")

            val read = WorkspaceFacts(WorkspaceGit()).of(repo)

            assertNull(read.head)
            assertNotNull(read.tree)
            val reason = assertNotNull(read.failures[WorkspaceFacts.HEAD])
            assertTrue("rev-parse" in reason, reason)
        }
}
