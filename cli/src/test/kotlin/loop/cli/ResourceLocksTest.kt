package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.ResourceHolder
import loop.api.ResourceLockRows
import loop.api.RunId
import loop.core.resources.ResourceGrant
import loop.core.resources.ResourceLocks
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m4-50: every lock and queue row of an attempt is freed when it ends, whatever the end, and records
 * `resource_released`; the locks of an attempt a dead process left are freed by the startup sweep of the next run,
 * which then calls each provider's `recover` once with the holders of the locks left on the machine. The tools that
 * take a lock come in m4-25, so the test takes them for the running attempt through [ResourceLocks] on the store.
 * Its kinds, `phone` and `display`, are plain locks no provider serves, so no real `adb` runs (m4-28).
 */
class ResourceLocksTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    private val project by lazy { ProjectId(fixture.projectId()) }

    private val other = ProjectId("other-project")

    private val attemptIdFile by lazy { root.resolve("attempt-id") }

    private val goFile by lazy { root.resolve("go") }

    @Test
    fun `an attempt that succeeds frees its lock and its queue row and records resource_released`() {
        fixture.issue("t-1")
        agentWaitingThen("loop tool move review")
        val run = fixture.start("run", "--once", fixture.workflow.toString())
        try {
            val attempt = takeLocksOfRunningAttempt(run)
            goFile.createFile()
            val result = run.await(60)

            assertEquals(0, result.exitCode, result.toString())
            assertFreed(attempt, "attempt_ended", result.toString())
        } finally {
            run.process.destroyForcibly()
        }
    }

    @Test
    fun `an attempt that fails frees its lock and its queue row and records resource_released`() {
        fixture.issue("t-1")
        agentWaitingThen("exit 1")
        val run = fixture.start("run", "--once", fixture.workflow.toString())
        try {
            val attempt = takeLocksOfRunningAttempt(run)
            goFile.createFile()
            val result = run.await(60)

            assertEquals(
                1,
                fixture.query("SELECT 1 FROM events WHERE kind = 'attempt_finished' AND data LIKE '%failed%'").size,
                result.toString(),
            )
            assertFreed(attempt, "attempt_ended", result.toString())
        } finally {
            run.process.destroyForcibly()
        }
    }

    @Test
    fun `an attempt that loop stop ends frees its lock and its queue row and records resource_released`() {
        fixture.issue("t-1")
        agentWaitingThen("exec sleep 60")
        val run = fixture.start("run", fixture.workflow.toString())
        try {
            val attempt = takeLocksOfRunningAttempt(run)
            val stop = fixture.loop("stop", fixture.workflow.toString())
            val result = run.await(60)

            assertEquals(0, stop.exitCode, stop.toString())
            assertEquals(0, result.exitCode, result.toString())
            assertFreed(attempt, "attempt_ended", result.toString())
        } finally {
            run.process.destroyForcibly()
        }
    }

    @Test
    fun `after a crash the next run frees the dead attempt's locks and calls each provider's recover once with the live holders`() {
        fixture.issue("t-1")
        val agentPid = root.resolve("agent.pid")
        agentWaitingThen("exec sleep 60", pidFile = agentPid)
        val crashed = fixture.start("run", fixture.workflow.toString())
        val marker = "only-${UUID.randomUUID()}"
        val attempt: String
        try {
            attempt = takeLocksOfRunningAttempt(crashed)
            withLocks { it.request(other, "9", "o-9", AttemptId("other-attempt"), "phone", marker).getOrThrow() }
            // kill -9 of loop run mid turn: nothing of the run gets to free its locks.
            kill9(crashed.process.pid())
            assertTrue(crashed.process.waitFor(30, TimeUnit.SECONDS), "loop run outlived kill -9")
        } finally {
            crashed.process.destroyForcibly()
            if (agentPid.exists()) kill9(agentPid.readText().trim().toLong())
        }
        assertEquals(1, fixture.query("SELECT 1 FROM resource_locks WHERE attempt_id = '$attempt'").size)

        fixture.agentScript.writeText("#!/bin/sh\nloop tool move review\n")
        val restarted = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, restarted.exitCode, restarted.toString())
        assertFreed(attempt, "abandoned", restarted.toString(), extraLocks = listOf("other-project phone $marker other-attempt"))
        val calls = FakeResourceProvider.recovered.filter { holders -> holders.any { it.resourceId == marker } }
        assertEquals(1, calls.size, "recover calls with the marker: $calls")
        assertEquals(
            setOf(
                ResourceHolder(other, "display", "main", "other-attempt"),
                ResourceHolder(other, "phone", marker, "other-attempt"),
            ),
            calls.single().toSet(),
        )
        val warning = restarted.stderr.lines().filter { "event=resource" in it && "reason=abandoned" in it }
        assertEquals(1, warning.size, restarted.toString())
        assertTrue("resource_id=api35" in warning.single(), restarted.toString())
    }

    /** An agent that writes its attempt id, and its pid to [pidFile], waits for [goFile], then runs [then]. */
    private fun agentWaitingThen(
        then: String,
        pidFile: Path? = null,
    ) {
        fixture.agentScript.writeText(
            """
            |#!/bin/sh
            |${pidFile?.let { "echo \$\$ > '$it'" }.orEmpty()}
            |echo "${'$'}LOOP_ATTEMPT_ID" > '$attemptIdFile'
            |i=0
            |while [ ! -f '$goFile' ] && [ ${'$'}i -lt 600 ]; do sleep 0.1; i=${'$'}((i + 1)); done
            |$then
            |
            """.trimMargin(),
        )
    }

    /**
     * Waits for the agent of [run], then takes for its attempt the lock of `phone api35` and a place in the queue of
     * `display main`, which a holder of another project keeps. Returns the attempt id.
     */
    private fun takeLocksOfRunningAttempt(run: LoopFixture.RunningLoop): String {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!attemptIdFile.exists() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        if (!attemptIdFile.exists()) fail("the agent never started\n${run.await(30)}")
        val attempt = attemptIdFile.readText().trim()
        val issueId = fixture.query("SELECT issue_id FROM events WHERE kind = 'attempt_started'").single()["issue_id"]!!
        withLocks { locks ->
            locks.request(other, "9", "o-9", AttemptId("other-attempt"), "display", "main").getOrThrow()
            assertIs<ResourceGrant.Granted>(locks.request(project, issueId, "t-1", AttemptId(attempt), "phone", "api35").getOrThrow())
            assertIs<ResourceGrant.Queued>(locks.request(project, issueId, "t-1", AttemptId(attempt), "display", "main").getOrThrow())
        }
        return attempt
    }

    private fun withLocks(block: suspend (ResourceLocks) -> Unit) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking { block(ResourceLocks(store, store, Clock.System, RunId(TEST_RUN))) }
        }
    }

    /**
     * The attempt [attempt] holds no lock and waits in no queue, its lock of `phone api35` recorded `resource_released`
     * with [reason], and only the locks of the other project are left: `display main` and [extraLocks].
     */
    private fun assertFreed(
        attempt: String,
        reason: String,
        output: String,
        extraLocks: List<String> = emptyList(),
    ) {
        val rows =
            SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
                runBlocking { store.resourceLockRows().getOrThrow() }
            }
        assertEquals(
            (listOf("other-project display main other-attempt") + extraLocks).sorted(),
            rows.locks.map { "${it.projectId.value} ${it.kind} ${it.resourceId} ${it.attemptId.value}" }.sorted(),
            output,
        )
        assertEquals(ResourceLockRows().queue, rows.queue, output)
        val released = fixture.query("SELECT attempt_id, data FROM events WHERE kind = 'resource_released'")
        assertEquals(1, released.size, "$released\n$output")
        assertEquals(attempt, released.single()["attempt_id"], output)
        val data = released.single()["data"]!!
        assertTrue(
            "\"reason\":\"$reason\"" in data && "\"resource_id\":\"api35\"" in data && "\"kind\":\"phone\"" in data,
            "$data\n$output",
        )
    }

    private fun kill9(pid: Long) {
        ProcessBuilder("kill", "-9", pid.toString()).start().waitFor(10, TimeUnit.SECONDS)
    }

    private companion object {
        const val TEST_RUN = "7e570000-0000-4000-8000-000000000001"
        const val WAIT_NANOS = 30_000_000_000L
        const val POLL_MS = 50L
    }
}
