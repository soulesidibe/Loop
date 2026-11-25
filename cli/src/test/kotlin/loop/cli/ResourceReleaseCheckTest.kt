package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import loop.api.DirtyResource
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.ResourceLockUpdate
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * m4-26: the read only check of a resource. On `release_resource` the declared `read_only_check` runs in the tool
 * process while the resource is up, and a failure keeps the lock and answers the difference and the `restore` entries;
 * a pass lets `loop run` run the provider's `release`, then its own check, whose failure marks the resource dirty
 * without asking the agent. At the end of an attempt a failing check gets the `restore` entries once, then a second
 * failure marks the resource dirty. A dirty resource records `resource_check_failed`, shows in `loop status` and is lent
 * to nobody until a check of it passes. The agents are scripts that speak JSON-RPC to the real `loop tool mcp`, and
 * `loop run` runs in process so the fake provider of the test classpath serves its `fake-device` kind.
 */
class ResourceReleaseCheckTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root.resolve("b")) }

    /** A resource id only this test uses, since the fake provider's calls are shared by the tests of the JVM. */
    private val resource = "res-${UUID.randomUUID()}"

    /** The file whose presence the read only check reads as a change the agent left on the resource. */
    private val changed: Path by lazy { root.resolve("changed") }

    private val failingCheck: String get() = "test ! -f $changed || { echo app installed; exit 1; }"

    @Test
    fun `a failing read only check keeps the lock and answers the difference and restore, a passing one releases it`() {
        fixture.issue("t-1")
        workflow(readOnlyCheck = failingCheck, restore = "rm -f $changed")
        ResourceAgent.write(
            fixture,
            """
            |call request_resource $resource
            |until_granted $resource
            |touch "$changed"
            |call release_resource $resource
            |call request_resource $resource
            |rm -f "$changed"
            |call release_resource $resource
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${ResourceAgent.transcripts(fixture, "t-1")}")
        val answers = ResourceAgent.answers(fixture, "t-1").dropWhile { it.first == "request_resource" }
        val (failed, held, released) = answers.map { it.second }
        assertEquals("read_only_check_failed", failed["error"]?.jsonPrimitive?.content, "$failed")
        assertContains(failed.getValue("difference").jsonPrimitive.content, "app installed")
        assertEquals(JsonArray(listOf(JsonPrimitive("rm -f $changed"))), failed["restore"], "$failed")
        assertContains(failed.getValue("message").jsonPrimitive.content, "rm -f $changed")
        assertEquals("already_held", held["error"]?.jsonPrimitive?.content, "the lock stayed with the attempt: $held")
        assertEquals("released", released.status(), "$released")
        assertEquals(listOf("prepare", "release", "check"), calls(), "the provider's release, then its own check")
        assertEquals(1, events("resource_released").size)
        assertEquals(emptyList(), events("resource_check_failed"))
        assertEquals(emptyList(), fixture.query("SELECT kind FROM resource_dirty"))
    }

    @Test
    fun `a provider check that fails after release marks the resource dirty without asking the agent`() {
        fixture.issue("t-1")
        workflow(readOnlyCheck = "true", restore = "true")
        FakeResourceProvider.changed += resource
        ResourceAgent.write(
            fixture,
            """
            |call request_resource $resource
            |until_granted $resource
            |call release_resource $resource
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${ResourceAgent.transcripts(fixture, "t-1")}")
        val answers = ResourceAgent.answers(fixture, "t-1")
        assertEquals("release_resource" to "released", answers.last().let { it.first to it.second.status() }, "$answers")
        assertEquals(listOf("prepare", "release", "check"), calls())
        val failed = events("resource_check_failed").single()
        assertContains(failed, "\"check\":\"provider\"")
        assertContains(failed, "fake difference on $resource")
        assertEquals(listOf(mapOf("resource_id" to resource, "check" to "provider")), dirtyRows())
        assertStatusShowsDirty("provider")
    }

    @Test
    fun `an attempt that ends with a failing read only check runs restore once, then marks the resource dirty`() {
        fixture.issue("t-1")
        val restored = root.resolve("restored.log")
        workflow(readOnlyCheck = failingCheck, restore = "echo restored >> $restored")
        ResourceAgent.write(
            fixture,
            """
            |call request_resource $resource
            |until_granted $resource
            |touch "$changed"
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${ResourceAgent.transcripts(fixture, "t-1")}")
        assertEquals(listOf("restored"), restored.readLines(), "the restore entries ran once")
        val failed = events("resource_check_failed").single()
        assertContains(failed, "\"check\":\"read_only_check\"")
        assertContains(failed, "app installed")
        assertEquals(listOf(mapOf("resource_id" to resource, "check" to "read_only_check")), dirtyRows())
        assertStatusShowsDirty("read_only_check")
        val trace = fixture.inProcess("trace", "t-1", "--workflow", fixture.workflow.toString())
        assertTrue(trace.stdout.lines().any { "resource_check_failed" in it && resource in it }, trace.stdout)
    }

    @Test
    fun `an attempt whose restore fixes the resource leaves it clean`() {
        fixture.issue("t-1")
        workflow(readOnlyCheck = failingCheck, restore = "rm -f $changed")
        ResourceAgent.write(
            fixture,
            """
            |call request_resource $resource
            |until_granted $resource
            |touch "$changed"
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${ResourceAgent.transcripts(fixture, "t-1")}")
        assertTrue(!changed.exists(), "the restore ran")
        assertEquals(emptyList(), events("resource_check_failed"))
        assertEquals(emptyList(), dirtyRows())
    }

    @Test
    fun `a dirty resource is not granted until its check passes again, then it is granted`() {
        fixture.issue("t-1")
        val clean = root.resolve("clean")
        workflow(readOnlyCheck = "test -f $clean || { echo still changed; exit 1; }", restore = "true")
        markDirty()
        ResourceAgent.write(
            fixture,
            """
            |call request_resource $resource
            |await queued-seen
            |until_granted $resource
            |call release_resource $resource
            |finish
            """.trimMargin(),
        )
        // The check fails until the agent saw its request queued as dirty, so the grant can only follow a pass.
        val watcher =
            Thread {
                val transcript = ResourceAgent.dir(fixture).resolve("transcript-t-1.txt")
                val deadline = System.nanoTime() + 60_000_000_000L
                while (System.nanoTime() < deadline) {
                    if (transcript.exists() && transcript.readLines().isNotEmpty()) break
                    Thread.sleep(50)
                }
                clean.toFile().createNewFile()
                ResourceAgent
                    .dir(fixture)
                    .resolve("queued-seen")
                    .toFile()
                    .createNewFile()
            }.apply { start() }

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())
        watcher.join()

        assertEquals(0, run.exitCode, "$run\n${ResourceAgent.transcripts(fixture, "t-1")}")
        val answers = ResourceAgent.answers(fixture, "t-1")
        val first = answers.first().second
        assertEquals("queued", first.status(), "$answers")
        assertEquals("dirty", first["reason"]?.jsonPrimitive?.content, "$first")
        assertEquals("granted", answers.last { it.first == "request_resource" }.second.status(), "$answers")
        assertEquals(emptyList(), dirtyRows(), "the passing check cleared the mark")
        assertTrue(events("resource_queued").single().contains("\"reason\":\"dirty\""), events("resource_queued").toString())
        val ops = calls()
        assertEquals(listOf("prepare", "release", "check"), ops.takeLast(3), "the grant, then its own release: $ops")
        assertTrue(ops.size >= 6, "a check of the dirty resource ran before the grant: $ops")
    }

    /** One `fake-device` resource, [resource], with [readOnlyCheck] and one [restore] entry, one agent, one turn. */
    private fun workflow(
        readOnlyCheck: String,
        restore: String,
    ) {
        fixture.writeWorkflow(
            extra =
                """
                |agent:
                |  max_turns: 1
                |retries:
                |  max_attempts: 1
                |  max_continuations: 1
                |resources:
                |  - kind: fake-device
                |    id: $resource
                |    restore: ["$restore"]
                |    read_only_check: "$readOnlyCheck"
                """.trimMargin(),
        )
    }

    /** Marks [resource] dirty in the machine's store, as a failed check of an earlier run left it. */
    private fun markDirty() {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            val mark =
                DirtyResource("fake-device", resource, ProjectId(fixture.projectId()), "provider", "app installed", Clock.System.now())
            runBlocking {
                store.updateResourceLocks { rows -> ResourceLockUpdate(rows.copy(dirty = rows.dirty + mark), Unit) }.getOrThrow()
            }
        }
    }

    private fun assertStatusShowsDirty(check: String) {
        val status = fixture.inProcess("status", fixture.workflow.toString())
        assertEquals(0, status.exitCode, status.toString())
        assertTrue(
            status.stdout.lines().any { it.startsWith("Resource fake-device $resource  dirty check=$check") },
            status.stdout,
        )
    }

    private fun calls(): List<String> = FakeResourceProvider.calls.filter { it.id == resource }.map { it.op }

    private fun events(kind: String): List<String> =
        fixture.query("SELECT data FROM events WHERE kind = '$kind' ORDER BY seq").map { it["data"].orEmpty() }

    private fun dirtyRows(): List<Map<String, String?>> = fixture.query("SELECT resource_id, check_name AS \"check\" FROM resource_dirty")
}
