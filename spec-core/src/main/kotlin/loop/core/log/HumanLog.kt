package loop.core.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.events.AttemptFinished
import loop.core.events.AttemptStarted
import loop.core.events.EventDefinitions
import loop.core.events.HumanFacts
import loop.core.events.HumanShow
import loop.core.events.HumanText
import loop.core.events.Phase
import loop.core.events.Preflight
import loop.core.events.RetryDropped
import loop.core.events.RetryScheduled
import loop.core.events.TurnCompleted
import loop.core.events.TurnFailed
import loop.core.events.Usage
import loop.core.orchestrator.RunObserver
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The human readable run log (m1-39): what a run does, in plain sentences, one line each, next to the
 * `key=value` operator log. A line is `HH:mm:ss  <identifier>  <sentence>` in [zone], or without the
 * identifier for a run level line. Each line goes through [redactor] before [out] gets it.
 *
 * It reads what the run already records: the events, the polls and the runner lines. Agent activity
 * comes from Claude Code's stream-json lines: files read (grouped while they come in a row), files
 * edited or written, and commands run, with their result once it is known. The agent's own text is
 * never printed, and a runner line that is not stream-json gives nothing.
 *
 * Paths under [base] (the workflow's folder) print relative to it, files under an attempt's workspace
 * relative to that workspace. Never throws: a failure of [out] or of a line is dropped, the run goes on.
 * Thread safe, so a line written from the shutdown path never mixes with the orchestrator's.
 */
class HumanLog(
    private val out: (String) -> Unit,
    private val clock: Clock,
    private val redactor: Redactor,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val base: Path? = null,
) : RunObserver {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)

    /** What is known of each running attempt, by attempt id. */
    private val attempts = mutableMapOf<String, AttemptLines>()

    /** The name of the next attempt of an issue, `retry 2` or `continuation 1`, by issue id, from its `retry_scheduled`. */
    private val nextAttempt = mutableMapOf<String, String>()

    /** True once a "nothing to do" line was written and no poll found work since. */
    private var idle = false

    /** The last "eligible" line without its time, so an unchanged poll writes nothing. */
    private var lastEligible: String? = null

    /** True while preflights fail, so only the first failure and the recovery get a line. */
    private var preflightFailing = false

    @Synchronized
    fun runStarted(workflow: Path) = guard { write(clock.now(), null, "run started for ${workflow.normalize()}") }

    /**
     * [reason] is null when the run ended on its own, `drained` when it ended after `loop drain` (m4-10), else
     * `stop_requested`, `signal` or `internal_error`.
     */
    @Synchronized
    fun runEnded(
        reason: String?,
        attemptsCanceled: Int,
    ) = guard {
        val ended =
            when (reason) {
                null -> "run ended"
                STOP_REQUESTED -> "run stopped by loop stop"
                SIGNAL -> "run stopped by a signal"
                INTERNAL_ERROR -> "run stopped by an internal error"
                DRAINED -> "run ended after loop drain"
                else -> "run stopped, $reason"
            }
        val canceled = if (attemptsCanceled > 0) ", ${count(attemptsCanceled, "attempt")} canceled" else ""
        write(clock.now(), null, ended + canceled)
    }

    @Synchronized
    override fun polled(
        eligible: List<String>,
        running: Int,
        maxRunning: Int,
    ) = guard {
        when {
            eligible.isNotEmpty() -> {
                idle = false
                val names = eligible.take(NAMES_MAX).joinToString(", ") + more(eligible.size - NAMES_MAX)
                val noun = if (eligible.size == 1) "issue" else "issues"
                // With every slot busy nothing starts at this poll, so the line must not read as if it does.
                val waiting = if (running >= maxRunning) ", waiting for a free slot" else ""
                val text = "${eligible.size} $noun eligible$waiting ($names)"
                if (text != lastEligible) write(clock.now(), null, text)
                lastEligible = text
            }
            running > 0 -> {
                idle = false
                lastEligible = null
            }
            else -> {
                lastEligible = null
                if (!idle) write(clock.now(), null, "nothing to do, waiting for eligible issues")
                idle = true
            }
        }
    }

    @Synchronized
    override fun recorded(event: RecordedEvent) = guard { onEvent(event) }

    @Synchronized
    override fun runnerLine(
        event: RecordedEvent,
        line: String,
    ) = guard {
        val json = parse(line) ?: return@guard
        val attempt = attemptOf(event) ?: return@guard
        when (json.string("type")) {
            "assistant" -> toolUses(json).forEach { attempt.toolUse(it, event.ts) }
            "user" -> toolResults(json).forEach { attempt.toolResult(it, event.ts) }
        }
    }

    /**
     * Writes the sentence of [event]'s definition (bl-80). The state the sentences need comes first: an attempt's
     * lines, its workspace, its spend, and its pending reads and commands before a turn or the attempt ends. The state
     * an event changes for later events comes after its line.
     */
    private fun onEvent(event: RecordedEvent) {
        val definition = EventDefinitions.of(event.kind) ?: return
        val data = event.data
        when (definition) {
            AttemptStarted -> {
                val id = event.attemptId?.value ?: return
                val name = event.issueId?.let(nextAttempt::remove) ?: "attempt"
                attempts[id] = AttemptLines(event.issueIdentifier ?: event.issueId ?: "", name)
            }
            Phase -> onPhase(event)
            Usage -> {
                val attempt = attemptOf(event) ?: return
                Usage.attemptTokens(data)?.toLongOrNull()?.let { attempt.tokens = it }
                Usage.costUsd(data)?.let { attempt.cost = it }
            }
            TurnCompleted, TurnFailed, AttemptFinished -> attemptOf(event)?.flushAll(event.ts)
            else -> Unit
        }
        definition.humanSentence(data, facts(event))?.let { text ->
            when (definition.human) {
                HumanShow.ISSUE -> issueLine(event, text)
                HumanShow.RUN -> write(event.ts, null, text)
                HumanShow.SILENT -> Unit
            }
        }
        when (definition) {
            AttemptFinished -> event.attemptId?.value?.let(attempts::remove)
            RetryScheduled -> RetryScheduled.attemptName(data)?.let { name -> event.issueId?.let { nextAttempt[it] = name } }
            RetryDropped -> event.issueId?.let(nextAttempt::remove)
            Preflight -> preflightFailing = Preflight.failed(data)
            else -> Unit
        }
    }

    /** What this log knows of [event]'s attempt and issue, read when its sentence is written. */
    private fun facts(event: RecordedEvent): HumanFacts {
        val attempt = attemptOf(event)
        return object : HumanFacts {
            override val attemptName = attempt?.name ?: "attempt"
            override val issueState = attempt?.issueState
            override val spend = spend(attempt)
            override val preflightFailing = this@HumanLog.preflightFailing
            override val nextAttempt = event.issueId?.let(this@HumanLog.nextAttempt::get)
        }
    }

    private fun onPhase(event: RecordedEvent) {
        val attempt = attemptOf(event) ?: return
        Phase.workspaceOf(event)?.let { workspace ->
            attempt.workspace = runCatching { Path.of(workspace) }.getOrNull()
            attempt.flushStart(event.ts)
        }
        if (Phase.isSucceeded(event)) attempt.issueState = event.data[Phase.ISSUE_STATE]
    }

    private fun spend(attempt: AttemptLines?): String {
        attempt ?: return ""
        val tokens = attempt.tokens?.let { ", ${size(it)} tokens" }.orEmpty()
        val cost = attempt.cost?.let { ", ${money(it)}" }.orEmpty()
        return tokens + cost
    }

    private fun attemptOf(event: RecordedEvent): AttemptLines? = event.attemptId?.value?.let(attempts::get)

    /** A line of [event]'s issue; the attempt's pending start and reads come first. */
    private fun issueLine(
        event: RecordedEvent,
        text: String,
    ) {
        attemptOf(event)?.let { attempt ->
            attempt.flushStart(event.ts)
            attempt.flushReads()
        }
        write(event.ts, event.issueIdentifier, text)
    }

    private fun write(
        ts: Instant,
        identifier: String?,
        text: String,
    ) {
        val prefix = identifier?.takeIf { it.isNotEmpty() }?.let { "$it  " }.orEmpty()
        val line = "${time.format(ts.toJavaInstant())}  $prefix$text"
        // One line each: a newline in a reason or a command never starts a line of its own.
        out(redact(line.replace(NEWLINES, " ")))
    }

    private fun redact(text: String): String =
        try {
            redactor.redact(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Redactor.FAILED_MARKER
        } catch (e: StackOverflowError) {
            Redactor.FAILED_MARKER
        }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The human log never fails the run; the writer reports its own failures.
        }
    }

    /** [path] relative to [base] when under it, else as is. */
    private fun display(path: Path): String = relative(path, base)

    /** The lines of one attempt still to come: its start line, the current group of reads and the commands running. */
    private inner class AttemptLines(
        val identifier: String,
        /** `attempt` on a first dispatch, `retry 2` or `continuation 1` after a `retry_scheduled`. */
        val name: String,
    ) {
        var workspace: Path? = null
        var issueState: String? = null
        var tokens: Long? = null
        var cost: Double? = null
        private var started = false
        private val reads = mutableListOf<String>()
        private var readsAt: Instant? = null

        /** Commands run and not finished, tool use id to the command as printed. */
        private val commands = linkedMapOf<String, Pair<String, Instant>>()

        fun flushStart(ts: Instant) {
            if (started) return
            started = true
            val place = workspace?.let { " in ${display(it)}" }.orEmpty()
            write(ts, identifier, "$name started$place")
        }

        fun flushReads() {
            val at = readsAt ?: return
            val files = reads.distinct()
            reads.clear()
            readsAt = null
            flushStart(at)
            write(at, identifier, "read ${listed(files)}")
        }

        /** Before a turn or the attempt ends: the reads, then each command whose result never came. */
        fun flushAll(ts: Instant) {
            flushStart(ts)
            flushReads()
            commands.values.forEach { (command, _) -> write(ts, identifier, "ran $command") }
            commands.clear()
        }

        fun toolUse(
            use: JsonObject,
            ts: Instant,
        ) {
            val input = use["input"] as? JsonObject ?: JsonObject(emptyMap())
            when (use.string("name")) {
                "Read" -> {
                    val file = input.string("file_path") ?: return
                    flushStart(ts)
                    reads += file(file)
                    readsAt = ts
                }
                "Edit", "MultiEdit" -> input.string("file_path")?.let { activity(ts, "edited ${file(it)}") }
                "NotebookEdit" -> input.string("notebook_path")?.let { activity(ts, "edited ${file(it)}") }
                "Write" -> input.string("file_path")?.let { activity(ts, "wrote ${file(it)}") }
                "Bash" -> {
                    val command = input.string("command") ?: return
                    flushStart(ts)
                    flushReads()
                    val shown = command(command, workspace)
                    val id = use.string("id") ?: return activity(ts, "ran $shown")
                    commands[id] = shown to ts
                }
            }
        }

        fun toolResult(
            result: JsonObject,
            ts: Instant,
        ) {
            val id = result.string("tool_use_id") ?: return
            val (command, _) = commands.remove(id) ?: return
            flushReads()
            // `is_error` false only says the shell exited 0: `./gradlew test | tee out.txt` exits with tee's code.
            val outcome =
                when ((result["is_error"] as? JsonPrimitive)?.booleanOrNull) {
                    true -> " (error)"
                    false -> " (exit 0)"
                    null -> ""
                }
            write(ts, identifier, "ran $command$outcome")
        }

        private fun activity(
            ts: Instant,
            text: String,
        ) {
            flushStart(ts)
            flushReads()
            write(ts, identifier, text)
        }

        private fun file(path: String): String {
            val parsed = runCatching { Path.of(path) }.getOrNull() ?: return path
            val root = workspace
            return if (root != null && parsed.normalize().startsWith(root.normalize())) relative(parsed, root) else relative(parsed, base)
        }
    }

    companion object {
        private const val STOP_REQUESTED = "stop_requested"
        private const val SIGNAL = "signal"
        private const val INTERNAL_ERROR = "internal_error"
        private const val DRAINED = "drained"
        private const val NAMES_MAX = 10
        private const val TEXT_MAX = 200
        private const val COMMAND_MAX = 120
        private val NEWLINES = Regex("[\\r\\n]+")

        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /** A token count as people say it: `567`, `1.2K`, `7.2M`, `10M`, `1.5B`. */
        fun size(value: Long): String = HumanText.size(value)

        /** Money in dollars with cents: `$3.38`. */
        fun money(usd: Double): String = HumanText.money(usd)

        /**
         * [command] as a line shows it: a leading `cd <workspace> && ` dropped, [workspace] written `.`, then
         * its first line cut to [COMMAND_MAX] characters, followed by `(+N lines)` when more lines follow.
         */
        private fun command(
            command: String,
            workspace: Path?,
        ): String {
            var text = command.trim()
            val root = workspace?.normalize()?.toString()?.takeIf { it.length > 1 }
            if (root != null) {
                val path = Regex.escape(root)
                text = Regex("""^cd\s+(["']?)$path/?\1\s*&&\s*""").replaceFirst(text, "")
                text = Regex("""$path(?![\w.-])""").replace(text) { "." }
            }
            val lines = text.lines()
            val first = lines.first().trim()
            val cut = if (first.length > COMMAND_MAX) first.take(COMMAND_MAX) + "..." else first
            val more = lines.size - 1
            return if (more > 0) "$cut (+${count(more, "line")})" else cut
        }

        /** `A`, `A and B`, `A, B and C`, then `A, B and 6 other files`. */
        private fun listed(files: List<String>): String =
            when (files.size) {
                1 -> files[0]
                2 -> "${files[0]} and ${files[1]}"
                3 -> "${files[0]}, ${files[1]} and ${files[2]}"
                else -> "${files[0]}, ${files[1]} and ${files.size - 2} other files"
            }

        private fun count(
            n: Int,
            noun: String,
        ): String = HumanText.count(n, noun)

        private fun more(extra: Int): String = if (extra > 0) " and $extra more" else ""

        private fun relative(
            path: Path,
            root: Path?,
        ): String {
            val normalized = path.normalize()
            val rootNormalized = root?.normalize() ?: return normalized.toString()
            return if (normalized != rootNormalized && normalized.startsWith(rootNormalized)) {
                rootNormalized.relativize(normalized).toString()
            } else {
                normalized.toString()
            }
        }

        private fun parse(line: String): JsonObject? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                json.parseToJsonElement(trimmed) as? JsonObject
            } catch (e: Exception) {
                null
            }
        }

        private fun content(event: JsonObject): List<JsonObject> {
            val message = event["message"] as? JsonObject ?: return emptyList()
            val content = message["content"] as? JsonArray ?: return emptyList()
            return content.filterIsInstance<JsonObject>()
        }

        private fun toolUses(event: JsonObject): List<JsonObject> = content(event).filter { it.string("type") == "tool_use" }

        private fun toolResults(event: JsonObject): List<JsonObject> = content(event).filter { it.string("type") == "tool_result" }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
