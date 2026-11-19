package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import loop.api.ProjectId
import loop.api.Redactor
import loop.api.RunId
import loop.store.MachineSlot
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * m3-16: only the slots of a live `loop run` count toward the machine cap. A slot counts while the run file of its
 * project names its run and its process, alive and started when the run file says; a crashed run or a reused pid
 * frees the slot at once, with no heartbeat.
 */
class MachineSlotLivenessTest {
    @TempDir
    lateinit var tmp: Path

    private val loopHome: Path get() = tmp.resolve("loop-home")
    private val project = ProjectId("aaaaaaaaaaaaaaaa")
    private val self = ProcessHandle.current()

    private fun open(): SqliteEventStore =
        SqliteEventStore.open(loopHome, Files.createDirectories(tmp.resolve("project")), Redactor.NONE).getOrThrow()

    private fun writeRunFile(record: RunRecord) {
        val files = RunFiles(loopHome, project)
        Files.createDirectories(files.dir)
        files.runFile.writeText(Json.encodeToString(RunRecord.serializer(), record))
    }

    private fun slot(
        pid: Long,
        runId: String = RUN,
    ) = MachineSlot(project, "h-$pid-$runId", RunId(runId), pid, Instant.parse("2026-11-15T10:00:00Z"))

    /** Whether a cap of one is free once [stale] is held: true means [stale] did not count. */
    private fun freeBeside(stale: MachineSlot): Boolean =
        open().use { store ->
            runBlocking {
                assertTrue(store.claimMachineSlot(stale, cap = 1) { true }.getOrThrow())
                val other = MachineSlot(ProjectId("bbbbbbbbbbbbbbbb"), "next", RunId("r2"), 1, Instant.parse("2026-11-15T10:01:00Z"))
                store.claimMachineSlot(other, cap = 1) { slotLive(loopHome, it) }.getOrThrow()
            }
        }

    @Test
    fun `a slot of the live run named by its run file counts`() {
        writeRunFile(RunRecord.ofCurrentProcess(RUN, tmp.resolve("WORKFLOW.md")))

        assertTrue(slotLive(loopHome, slot(self.pid())))
        assertFalse(freeBeside(slot(self.pid())), "the live slot holds the only slot")
    }

    @Test
    fun `a slot whose run file names a dead process does not count`() {
        val gone = ProcessBuilder("true").start()
        gone.waitFor()
        writeRunFile(RunRecord(pid = gone.pid(), startedAt = "2026-11-15T09:00:00Z", runId = RUN, workflow = "WORKFLOW.md"))

        assertFalse(slotLive(loopHome, slot(gone.pid())))
        assertTrue(freeBeside(slot(gone.pid())), "the crashed run's slot is freed")
        assertEquals(listOf("next"), open().use { store -> runBlocking { store.machineSlots().getOrThrow().map { it.holder } } })
    }

    @Test
    fun `a slot whose pid was reused by another process does not count`() {
        // The pid is alive, but its process started at another time than the run file says: the pid was reused.
        writeRunFile(RunRecord(pid = self.pid(), startedAt = "2001-01-01T00:00:00Z", runId = RUN, workflow = "WORKFLOW.md"))

        assertFalse(slotLive(loopHome, slot(self.pid())))
        assertTrue(freeBeside(slot(self.pid())))
    }

    @Test
    fun `a slot of an earlier run of the same project does not count`() {
        writeRunFile(RunRecord.ofCurrentProcess("a-later-run", tmp.resolve("WORKFLOW.md")))

        assertFalse(slotLive(loopHome, slot(self.pid(), runId = RUN)))
    }

    @Test
    fun `a slot without a run file does not count, an unreadable run file does`() {
        assertFalse(slotLive(loopHome, slot(self.pid())))

        val files = RunFiles(loopHome, project)
        Files.createDirectories(files.dir)
        files.runFile.writeText("{not json")
        assertTrue(slotLive(loopHome, slot(self.pid())), "a half written run file never lifts the cap")
    }

    @Test
    fun `a run answers for its own project without reading its run file`() {
        // No run file at all: the answer comes from the run itself, which holds that file's lock.
        val holding = project to RunId(RUN)

        assertTrue(slotLive(loopHome, slot(self.pid()), holding))
        assertFalse(slotLive(loopHome, slot(self.pid(), runId = "an-earlier-run"), holding))
    }

    private companion object {
        const val RUN = "5b2f0c3d-0000-4000-8000-00000000aaaa"
    }
}
