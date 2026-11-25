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
 * processes can tell a live slot from one a crashed run left. A project whose run file a claim cannot read goes to
 * [unreadable] once (bl-101), its slots counting, and again only after a claim read that file.
 */
internal class StoreMachine(
    private val loopHome: Path,
    private val store: SqliteEventStore,
    private val projectId: ProjectId,
    private val runId: RunId,
    private val pid: Long,
    private val clock: Clock,
    private val unreadable: (ProjectId, Throwable) -> Unit = { _, _ -> },
) : Machine {
    /** The projects whose run file the last claim could not read, already reported. */
    private var reported = emptySet<ProjectId>()

    override fun limits(): Result<MachineLimits?> = MachineConfig.read(loopHome)

    override suspend fun claimSlot(
        holder: String,
        cap: Int,
    ): Result<Boolean> {
        val found = mutableMapOf<ProjectId, Throwable>()
        val claimed =
            store.claimMachineSlot(MachineSlot(projectId, holder, runId, pid, clock.now()), cap) { slot ->
                slotLive(loopHome, slot, holding = projectId to runId) { found.putIfAbsent(slot.projectId, it) }
            }
        // A claim that failed read nothing for sure, so what was reported stays reported.
        if (claimed.isSuccess) {
            found.filterKeys { it !in reported }.forEach { (project, error) -> unreadable(project, error) }
            reported = found.keys.toSet()
        }
        return claimed
    }

    override suspend fun releaseSlot(holder: String): Result<Unit> = store.releaseMachineSlot(projectId, holder)

    override suspend fun attemptRowsSince(since: Instant): Result<List<LedgerRow>> = store.machineAttemptRowsSince(since)
}

/**
 * True when [slot] counts toward the machine cap (m3-16): the run file of its project names its run and its process,
 * and that process is alive and started when the run file says, so a reused pid does not count. No heartbeat: a
 * crashed run's slots stop counting at once. A run file Loop cannot read at the first try counts and goes to
 * [unreadable], so a half written one never lifts the cap. A `loop run` passes [holding], its own project and run: it
 * holds that project's run file lock, and reading the file would open and close a second descriptor, which drops a
 * POSIX lock, so `loop stop` would take the live run for a stale one. Its own project's slots count when they are of its own run, the only live run of that project.
 */
internal fun slotLive(
    loopHome: Path,
    slot: MachineSlot,
    holding: Pair<ProjectId, RunId>? = null,
    unreadable: (Throwable) -> Unit = {},
): Boolean {
    if (holding != null && slot.projectId == holding.first) return slot.runId == holding.second
    val peek =
        try {
            // bl-101: read once, with no wait, since a claim reads under the store's write lock; unreadable counts anyway.
            RunFiles(loopHome, slot.projectId).peek(retry = false)
        } catch (e: IOException) {
            RunFiles.Peek.Unreadable(e)
        }
    return when (peek) {
        is RunFiles.Peek.Live -> peek.record.runId == slot.runId.value && peek.record.pid == slot.pid
        is RunFiles.Peek.Unreadable -> {
            unreadable(peek.error)
            true
        }
        RunFiles.Peek.Nothing, is RunFiles.Peek.Gone -> false
    }
}

/**
 * True unless the run file of [projectId] shows no live run (bl-204): a resource lock or queue row of a project with
 * no live run was left by a dead process and is freed as abandoned. Read once, with no wait, since the free reads
 * under the store's write lock, and a run file Loop cannot read counts as live. Never call it for the caller's own
 * project: a `loop run` holds that run file's lock, which reading the file would drop.
 */
internal fun projectRunLive(
    loopHome: Path,
    projectId: ProjectId,
): Boolean {
    val peek =
        try {
            RunFiles(loopHome, projectId).peek(retry = false)
        } catch (e: IOException) {
            RunFiles.Peek.Unreadable(e)
        }
    return when (peek) {
        is RunFiles.Peek.Live, is RunFiles.Peek.Unreadable -> true
        RunFiles.Peek.Nothing, is RunFiles.Peek.Gone -> false
    }
}
