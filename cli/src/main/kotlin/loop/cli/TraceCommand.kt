package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.core.events.RunDrained
import loop.core.events.RunDraining
import loop.core.events.TraceText
import loop.store.StoredEvent
import java.nio.file.Path

/**
 * `loop trace <issue> [--full-ids]`: the recorded timeline of an issue (plan U1). The issue is read from the tracker,
 * or, when the tracker's secret is unset in this shell (bl-70), looked up by its id in the store, without its title.
 */
class TraceCommand(
    private val host: Host,
) : GuardedCommand("trace", host) {
    private val issue by argument(name = "issue", help = "The issue identifier")
    private val fullIds by option("--full-ids", help = "Print ids in full").flag()
    private val workflowPath by option("--workflow", help = "The workflow file, default ./WORKFLOW.md").default("WORKFLOW.md")

    override fun help(context: Context): String = "Print the recorded attempts of an issue"

    override fun body(scope: RedactorScope) {
        // m1-26: only the messages go through the project's redactor; the store is read only and keeps Redactor.NONE.
        val project = host.openProject(workflowPath, Access.READ, scope)
        val workflow = project.workflow
        // bl-70: without the secret of its tracker, the issue is looked up by its id in the store, and has no title.
        val secret = project.trackerSecret
        val found =
            if (secret == null) {
                fetch(project)
            } else {
                val why = "the tracker is not read, ${secret.sentence}. The issue is looked up by its id in the store"
                echo(redact("loop trace: $why."), err = true)
                null
            }
        val projectId = project.projectId
        val storeFile = project.storeFile
        val store = project.store().getOrElse { fail(it.sentence(), EXIT_STORE) }
        val unreadable = { error: Throwable -> fail("cannot read the store at $storeFile: ${error.sentence()}", EXIT_STORE) }
        val events =
            store.use {
                runBlocking {
                    val ofIssue = store.eventsForIssue(projectId, found?.id ?: issue).getOrElse(unreadable)
                    // m4-10: the drain of a run the issue took part in is run level, it prints among the issue's lines.
                    val runs = ofIssue.mapNotNull { it.event.runId }.toSet()
                    val drains =
                        store
                            .eventsOfKinds(projectId, DRAIN_KINDS)
                            .getOrElse(unreadable)
                            .filter { it.event.issueId == null && it.event.runId in runs }
                    (ofIssue + drains).sortedBy { it.seq }
                }
            }
        val blocks = blocksOf(events)
        // m1-09: a blocked issue has no attempt but its candidate_skipped lines say why, so it still prints.
        if (blocks.isEmpty()) {
            echo("No attempt recorded for this issue in project ${projectId.value}.")
            return
        }
        val heading =
            found?.let { "Issue ${it.identifier} \"${it.title}\"" }
                ?: "Issue ${events.lastOrNull { it.event.issueIdentifier != null }?.event?.issueIdentifier ?: issue}"
        echo(render(heading, projectId, workflow.path, blocks))
    }

    /** The issue from the project's tracker; fails with [EXIT_ISSUE] when it cannot be read or is not there. */
    private fun fetch(project: OpenedProject): Issue {
        val workflow = project.workflow
        return runBlocking { project.tracker().fetchIssuesByIds(listOf(issue)) }
            .getOrElse { fail("cannot read \"$issue\" in ${trackerLocation(workflow)}: ${it.sentence()}", EXIT_ISSUE) }
            .firstOrNull()
            ?: fail("no issue \"$issue\" in ${trackerLocation(workflow)}", EXIT_ISSUE)
    }

    private fun render(
        heading: String,
        projectId: ProjectId,
        workflowFile: Path,
        blocks: List<Block>,
    ): String =
        buildString {
            appendLine(heading)
            appendLine("Project ${projectId.value}   $workflowFile")
            var number = 0
            blocks.forEach { block ->
                appendLine()
                when (block) {
                    is Block.Attempt -> {
                        appendLine(TraceLines.header(++number, block.events, fullIds))
                        TraceLines.timeline(block.events, fullIds).forEach { appendLine("  $it".trimEnd()) }
                    }
                    is Block.IssueLevel -> appendLine(TraceLines.line(block.event, fullIds))
                }
            }
        }.trimEnd()

    /**
     * Events grouped by attempt, attempts in the order they started, with the issue
     * level events (no attempt id) as their own blocks in seq order between them.
     */
    private fun blocksOf(events: List<StoredEvent>): List<Block> {
        val sorted = events.sortedBy { it.seq }.map { it.event }
        val byAttempt = sorted.filter { it.attemptId != null }.groupBy { it.attemptId }
        val seen = mutableSetOf<AttemptId>()
        return sorted.mapNotNull { event ->
            val attemptId = event.attemptId
            when {
                attemptId == null -> if (TraceLines.issueLevel(event.kind)) Block.IssueLevel(event) else null
                seen.add(attemptId) -> Block.Attempt(byAttempt.getValue(attemptId))
                else -> null
            }
        }
    }

    /** An attempt and its events, or an issue level event recorded outside any attempt. */
    private sealed interface Block {
        data class Attempt(
            val events: List<RecordedEvent>,
        ) : Block

        data class IssueLevel(
            val event: RecordedEvent,
        ) : Block
    }

    private fun fail(
        message: String,
        code: Int,
    ): Nothing {
        echo(redact("loop trace: $message."), err = true)
        throw ProgramResult(code)
    }

    private companion object {
        const val EXIT_ISSUE = 2
        const val EXIT_STORE = 3

        /** The run level events `loop trace` prints for each run of the issue (m4-10). */
        val DRAIN_KINDS = setOf(RunDraining.name, RunDrained.name)
    }
}

/** [value] as is when it is one plain word, else in double quotes with `\` and `"` escaped (shared with `loop status`). */
internal fun quoteValue(value: String): String = TraceText.quote(value)
