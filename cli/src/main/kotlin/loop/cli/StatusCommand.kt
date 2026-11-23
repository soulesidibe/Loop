package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.RunId
import loop.core.machine.MachineConfig
import loop.core.machine.MachineLimits
import loop.core.waiting.Waits
import loop.store.SqliteEventStore
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * `loop status [path-to-WORKFLOW.md] [--full-ids]` (m1-14, spec 13.3 and 13.4): the runtime snapshot of the
 * project, one line for a runner hold of the live run (bl-39), then one line per running, retrying, waiting (m2-04) and
 * parked issue with its attempt. Everything comes from the store and the run file, so it works from any terminal while
 * `loop run` works in another process. With a machine config (m3-16) it also prints the machine slots in use, the cap
 * and the machine day spend. Since m3-09 a running attempt shows its running session's latest and peak occupancy, and
 * an attempt whose runner declares no occupancy gets a line saying only the prompt limit and the turn cap hold for it.
 * Since m4-02 a running session whose model has no window shows `window=none` and a line naming the model.
 * Since bl-172 a merge wait shows its place in the delivery queue, `queue=1` for the head, and since bl-175 one line says
 * when the live run keeps the delivery queue paused after a CI timeout.
 */
class StatusCommand(
    private val host: Host,
) : GuardedCommand("status", host) {
    private val workflowPath by argument(
        name = "path-to-WORKFLOW.md",
        help = "The workflow file, default ./WORKFLOW.md",
    ).optional()
    private val fullIds by option("--full-ids", help = "Print ids in full").flag()

    override fun help(context: Context): String = "Print what the project's run is doing: running, retrying, waiting and parked issues"

    override fun body(scope: RedactorScope) {
        // m1-26: only the messages go through the project's redactor; the store is read only and keeps Redactor.NONE.
        val project = host.openProject(workflowPath ?: DEFAULT_WORKFLOW, Access.READ, scope)
        val workflow = project.workflow
        val projectId = project.projectId
        val files = RunFiles(host.loopHome, projectId)
        val peek =
            try {
                files.peek()
            } catch (e: IOException) {
                RunFiles.Peek.Unreadable(e)
            }
        val live =
            when (peek) {
                is RunFiles.Peek.Live -> peek.record
                is RunFiles.Peek.Unreadable -> fail("cannot read the run file at ${files.runFile}: ${peek.error.sentence()}", EXIT_STORE)
                RunFiles.Peek.Nothing, is RunFiles.Peek.Gone -> null
            }
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        var machine = emptyList<String>()
        val snapshot =
            store.use {
                runBlocking {
                    machine = machineLines(store, storeFile.toString())
                    val events = store.eventsOfKinds(projectId, SNAPSHOT_KINDS)
                    val parked = store.list(projectId)
                    val waiting = Waits(store, projectId).read()
                    runtimeSnapshot(
                        events.getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) },
                        live?.let { RunId(it.runId) },
                        parked.getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) },
                        waiting.getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) },
                    )
                }
            }
        echo("Project ${projectId.value}   ${workflow.path}")
        echo(live?.let { "Run ${id(it.runId)}  pid ${it.pid}" } ?: "No run in progress.")
        machine.forEach { echo(redact(it)) }
        echo("")
        // bl-39: one line while the runner hold of the live run stops every dispatch.
        snapshot.hold?.let { hold ->
            val details = details("hold" to hold.hold, "next_probe" to hold.probeAt?.let(::clock), "reason" to hold.reason)
            echo("${HELD.padEnd(STATE_WIDTH)}runner unavailable  $details".trimEnd())
        }
        // bl-175: one line while the live run keeps the delivery queue paused after a CI timeout.
        snapshot.queuePause?.let { pause ->
            val details = details("issue" to pause.issue, "reason" to pause.reason, "since" to seconds(pause.since))
            echo("${PAUSED.padEnd(STATE_WIDTH)}delivery queue  $details  resume with loop approve or a restart".trimEnd())
        }
        val rows = rows(snapshot)
        if (rows.isEmpty()) {
            if (snapshot.hold == null && snapshot.queuePause == null) echo("Nothing running, retrying, waiting or parked.")
            return
        }
        val identifierWidth = rows.maxOf { it.identifier.length }
        val attemptWidth = rows.maxOf { it.attempt.length }
        rows.forEach { row ->
            echo(
                "${row.state.padEnd(STATE_WIDTH)}${row.identifier.padEnd(identifierWidth)}  " +
                    "${row.attempt.padEnd(attemptWidth)}  ${row.details}".trimEnd(),
            )
            row.notes.forEach { echo(" ".repeat(STATE_WIDTH) + it) }
        }
    }

    /**
     * The machine lines (m3-16), empty without `<LOOP_HOME>/config.yaml`: the live slots of every project against the
     * cap, then what the finished attempts of every project spent in the last 24 hours against the day budgets. The
     * live spend of running attempts is in no store row yet, so it is not counted here.
     */
    private suspend fun machineLines(
        store: SqliteEventStore,
        storeFile: String,
    ): List<String> {
        if (!Files.exists(MachineConfig.file(host.loopHome))) return emptyList()
        val read = MachineConfig.read(host.loopHome)
        val limits = read.getOrNull() ?: MachineLimits()
        val slots =
            store
                .machineSlots()
                .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
                .count { slotLive(host.loopHome, it) }
        val rows =
            store
                .machineAttemptRowsSince(Clock.System.now() - DAY)
                .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
        val tokens = rows.sumOf { it.attempt.tokens?.total ?: 0L }
        val usd = rows.sumOf { it.attempt.costUsd ?: 0.0 }
        val cap = limits.maxConcurrentAgents
        return listOfNotNull(
            read.exceptionOrNull()?.let { "Machine config fails, no dispatch until it is fixed: ${it.message}" },
            if (cap == null) "Machine slots $slots in use, no cap" else "Machine slots $slots of $cap in use",
            "Machine day spend $tokens tokens${of(limits.perDayTokens?.toString())}, " +
                "${dollars(usd)} usd${of(limits.perDayUsd?.let(::dollars))}",
        )
    }

    private fun of(limit: String?): String = limit?.let { " of $it" } ?: ""

    private fun dollars(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * One printed line: the runtime state, the issue, its attempt (`attempt <n>  <id>`) and `key=value` details, then
     * the [notes] printed under it, indented to the issue column.
     */
    private data class Line(
        val state: String,
        val identifier: String,
        val attempt: String,
        val details: String,
        val notes: List<String> = emptyList(),
    )

    private fun rows(snapshot: RuntimeSnapshot): List<Line> =
        snapshot.running.map { row ->
            val occupancy = row.occupancy
            Line(
                RUNNING,
                row.identifier,
                attempt(row.attempt),
                details(
                    "started" to clock(row.startedAt.toString()),
                    "turns" to row.turns.toString(),
                    "tokens" to row.tokens,
                    // m3-09: the running session's occupancy, its fractions only when the window is known.
                    "latest_tokens" to occupancy?.latest?.toString(),
                    "peak_tokens" to occupancy?.peak?.toString(),
                    // m4-02: a session whose model has no window says so, never a blank fraction.
                    "window" to (occupancy?.window?.toString() ?: NO_WINDOW.takeIf { occupancy?.noWindow == true }),
                    "fraction" to occupancy?.fraction,
                    "peak_fraction" to occupancy?.peakFraction,
                ),
                // m3-09: the weaker guarantee of a runner that declares no occupancy (plan 8.3).
                notes =
                    listOfNotNull(
                        NO_OCCUPANCY.takeIf { row.declaresOccupancy == false },
                        occupancy?.takeIf { it.noWindow }?.let { noWindow(it.model) },
                    ),
            )
        } +
            snapshot.retrying.map { row ->
                Line(
                    RETRYING,
                    row.identifier,
                    attempt(row.attempt),
                    details("kind" to row.kind, "retry" to row.retry, "due" to row.dueAt?.let(::clock), "error" to row.error),
                )
            } +
            snapshot.waiting.map { row ->
                Line(
                    WAITING,
                    row.identifier,
                    attempt(row.attempt),
                    details("reason" to row.reason, "since" to seconds(row.since), "queue" to row.queue?.toString()),
                )
            } +
            snapshot.parked.map { row ->
                Line(
                    PARKED,
                    row.identifier,
                    attempt(row.attempt),
                    details("reason" to row.reason, "state" to row.state, "since" to seconds(row.parkedAt)),
                )
            }

    private fun attempt(ref: AttemptRef?): String = ref?.let { "attempt ${it.number}  ${id(it.id.value)}" } ?: "no attempt"

    private fun details(vararg pairs: Pair<String, String?>): String =
        pairs.mapNotNull { (key, value) -> value?.let { "$key=${quoteValue(it)}" } }.joinToString(" ")

    /** The `HH:MM:SS` part of a stored timestamp, or the text as is when it is too short. */
    private fun clock(ts: String): String = if (ts.length >= SECONDS_PREFIX) ts.substring(TIME_START, SECONDS_PREFIX) else ts

    /** [instant] to the second, `2026-11-11T09:30:00Z`. */
    private fun seconds(instant: Instant): String = instant.toString().take(SECONDS_PREFIX) + "Z"

    private fun id(value: String): String = if (fullIds) value else value.take(SHORT_ID)

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop status: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val DEFAULT_WORKFLOW = "WORKFLOW.md"
        const val RUNNING = "running"
        const val RETRYING = "retrying"
        const val WAITING = "waiting"
        const val PARKED = "parked"
        const val HELD = "held"
        const val PAUSED = "paused"
        const val NO_OCCUPANCY =
            "the runner reports no context occupancy, so only the prompt limit and the turn cap (agent.max_turns) hold for this attempt"
        const val NO_WINDOW = "none"

        /** m4-02: the note of a running session whose [model] has no window, null for a runner that named none. */
        fun noWindow(model: String?): String =
            (model?.let { "no window for $it in context.windows" } ?: "the runner named no model") +
                ", so the soft and hard limits do not hold for this session"

        const val STATE_WIDTH = 10
        const val SHORT_ID = 8
        const val TIME_START = 11
        const val SECONDS_PREFIX = 19
        const val EXIT_STORE = 3
        val DAY = 24.hours
    }
}
