package loop.core.kinds

import loop.core.workflow.WorkflowFile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The context sources of a kind as one prompt shows them (m4-19): [text], the value of `loop.context_sources`, null
 * when the kind declares none, and [missing], the paths that were not in the workspace at render time, in the order
 * written.
 */
data class RenderedSources(
    val text: String?,
    val missing: List<String>,
)

/**
 * Renders a kind's `context.files` (m4-19, plan 8.6) as a short list of pointers the agent reads on demand, never
 * their text: one `- <path>` line per path, in the order written, and a path missing from the workspace gets
 * [MISSING_NOTE] after it. A missing path never fails the attempt; the caller records it.
 */
object ContextSources {
    /** The note after a path missing from the workspace. */
    const val MISSING_NOTE: String = "(missing from the workspace)"

    /** The list of [files], each checked against [workspace] now; null text for an empty list. */
    fun render(
        files: List<String>,
        workspace: Path,
    ): RenderedSources {
        if (files.isEmpty()) return RenderedSources(null, emptyList())
        val missing = files.filterNot { exists(workspace, it) }
        val text = files.joinToString("\n") { if (it in missing) "- $it $MISSING_NOTE" else "- $it" }
        return RenderedSources(text, missing)
    }

    private fun exists(
        workspace: Path,
        file: String,
    ): Boolean =
        try {
            Files.exists(workspace.resolve(file))
        } catch (e: InvalidPathException) {
            false
        }
}

/**
 * What the prompt of an issue renders from its kind (m4-19): the kind's own template or the `WORKFLOW.md` body, and its
 * context sources. The orchestrator only passes the attempt's kind.
 */
object KindPrompt {
    /** The template text of [kind]: its own template when it sets one, else the `WORKFLOW.md` body, as in Symphony mode. */
    fun template(
        workflow: WorkflowFile,
        kind: IssueKind?,
    ): String = kind?.config?.template?.text ?: workflow.promptTemplate

    /** The context sources of [kind], checked against [workspace]; none in Symphony mode. */
    fun sources(
        kind: IssueKind?,
        workspace: Path,
    ): RenderedSources = ContextSources.render(kind?.config?.context?.files.orEmpty(), workspace)
}
