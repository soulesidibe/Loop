package loop.cli

import loop.api.LedgerRow
import loop.api.ProjectId
import loop.api.RunId
import loop.core.machine.Machine
import loop.core.machine.MachineConfig
import loop.core.machine.MachineLimits
import loop.store.MachineSlot
import loop.store.SqliteEventStore
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The machine of one `loop run` (m3-16, ADR 0001): its limits read from `<LOOP_HOME>/config.yaml`, its slots and the
 * ledger rows of every project in the shared store. A slot row names this run and this process [pid], so the other
 * processes can tell a live slot from one a crashed run left.
 */
internal class StoreMachine(
    private val loopHome: Path,
    private val store: SqliteEventStore,
    private val projectId: ProjectId,
    private val runId: RunId,
    private val pid: Long,
    private val clock: Clock,
) : Machine {
    override fun limits(): Result<MachineLimits?> = MachineConfig.read(loopHome)

    override suspend fun claimSlot(
        holder: String,
        cap: Int,
    ): Result<Boolean> =
        store.claimMachineSlot(MachineSlot(projectId, holder, runId, pid, clock.now()), cap) {
            slotLive(loopHome, it, holding = projectId to runId)
        }

    override suspend fun releaseSlot(holder: String): Result<Unit> = store.releaseMachineSlot(projectId, holder)

    override suspend fun attemptRowsSince(since: Instant): Result<List<LedgerRow>> = store.machineAttemptRowsSince(since)
}

/**
 * True when [slot] counts toward the machine cap (m3-16): the run file of its project names its run and its process,
 * and that process is alive and started when the run file says, so a reused pid does not count. No heartbeat: a
 * crashed run's slots stop counting at once. A run file Loop cannot read counts, so a half written one never lifts
 * the cap. A `loop run` passes [holding], its own project and run: it holds that project's run file lock, and reading
 * the file would open and close a second descriptor, which drops a POSIX lock, so `loop stop` would take the live run
 * for a stale one. Its own project's slots count when they are of its own run, the only live run of that project.
 */
internal fun slotLive(
    loopHome: Path,
    slot: MachineSlot,
    holding: Pair<ProjectId, RunId>? = null,
): Boolean {
    if (holding != null && slot.projectId == holding.first) return slot.runId == holding.second
    val peek =
        try {
            RunFiles(loopHome, slot.projectId).peek()
        } catch (e: IOException) {
            RunFiles.Peek.Unreadable(e)
        }
    return when (peek) {
        is RunFiles.Peek.Live -> peek.record.runId == slot.runId.value && peek.record.pid == slot.pid
        is RunFiles.Peek.Unreadable -> true
        RunFiles.Peek.Nothing, is RunFiles.Peek.Gone -> false
    }
}
