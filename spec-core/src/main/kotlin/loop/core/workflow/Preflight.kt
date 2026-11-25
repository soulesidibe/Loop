package loop.core.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import loop.core.template.Template
import loop.core.template.TemplateEngine
import loop.core.template.TemplateError
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** What a preflight found: the workflow read from disk, or why dispatch must not run. */
sealed interface PreflightResult {
    data class Ok(
        val workflow: WorkflowFile,
    ) : PreflightResult

    data class Failed(
        /** The spec error code, or `internal_error` for an unexpected failure. */
        val code: String,
        val message: String,
        /** The file as read, null when it could not be read: a rejected reload is recorded with its hash and diff (m1-15). */
        val source: WorkflowSource? = null,
    ) : PreflightResult
}

/** The check the Orchestrator runs at the start of every tick (spec 6.3, 16.2). Never throws. */
fun interface PreflightCheck {
    suspend fun check(): PreflightResult
}

/**
 * The dispatch preflight of spec 6.3 (plan D2, D5, D7): re-reads `WORKFLOW.md`,
 * runs the tracker check the host supplies and the trust gate of `acceptance_commands` (m2-09), then parses the prompt template with
 * the static variable check and renders it against a sample issue with
 * `attempt` null and 1, with and without verify findings, with each `loop.retry_kind` (m3-11), each with
 * `loop.workspace_reused` false and true, and `loop.session_brief` set exactly when the attempt is a retry, a rotation
 * or the workspace is reused, so it renders null and set. Since m3-13 it also renders the template with an empty issue
 * and no optional slot: when that alone is over the prompt limit of the window of [promptModel], the template can
 * never fit and the preflight fails with `invalid_config`. Since m4-19 it checks the template of every kind, its own
 * `template` or the body, the same way, renders each with `loop.context_sources` null and set, and refuses a kind that
 * declares `context.files` with a template that never uses `loop.context_sources`. Never throws, except to propagate
 * cancellation.
 */
class Preflight(
    private val loader: WorkflowLoader,
    private val path: Path,
    private val templates: TemplateEngine,
    /**
     * The model of the runner the workflow would build, whose window the prompt limit is a fraction of (m3-13); null
     * takes the smallest window of `context.windows`.
     */
    private val promptModel: (WorkflowFile) -> String?,
    /** A sentence naming what is wrong with the tracker settings, or null when they are fine. */
    private val trackerCheck: (WorkflowFile) -> String?,
) : PreflightCheck {
    /** A preflight whose prompt limit takes the smallest window of `context.windows`, for a runner with no known model. */
    constructor(
        loader: WorkflowLoader,
        path: Path,
        templates: TemplateEngine,
        trackerCheck: (WorkflowFile) -> String?,
    ) : this(loader, path, templates, { null }, trackerCheck)

    override suspend fun check(): PreflightResult =
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreflightResult.Failed(INTERNAL_ERROR, "preflight failed: ${e.firstLine()}")
        }

    private suspend fun run(): PreflightResult {
        val source =
            withContext(Dispatchers.IO) { loader.readSource(path) }
                .getOrElse { return workflowFailure(it) }
        return check(source).let { if (it is PreflightResult.Failed) it.copy(source = source) else it }
    }

    /** Every check on the text [run] read, so a failure is about that exact text. */
    private fun check(source: WorkflowSource): PreflightResult {
        val workflow = loader.parse(source).getOrElse { return workflowFailure(it) }
        trackerCheck(workflow)?.let { return PreflightResult.Failed(INVALID_CONFIG, it) }
        // m2-09: no remote issue text runs a command until Loop checks who wrote it.
        untrustedAcceptanceCommands(workflow)?.let { return PreflightResult.Failed(INVALID_CONFIG, it) }
        for (prompt in prompts(workflow)) {
            val template =
                templates
                    .parse(prompt.text, PromptVariables.schema)
                    .getOrElse { return templateFailure(it, prompt.name) }
            unusedSources(prompt, template)?.let { return PreflightResult.Failed(INVALID_CONFIG, it) }
            renderSamples(template)?.let { return templateFailure(it, prompt.name) }
            templateOverLimit(workflow, template, prompt.name)?.let { return PreflightResult.Failed(INVALID_CONFIG, it) }
        }
        return PreflightResult.Ok(workflow)
    }

    /**
     * A prompt template to check (m4-19): its [text], its [name] in render messages, null for the `WORKFLOW.md` body,
     * [sourcesKind], the key of its kind when that kind declares context sources, else null, and [source], how the
     * refusal of unused sources names the template.
     */
    private class PromptToCheck(
        val text: String,
        val name: String?,
        val sourcesKind: String?,
        val source: String,
    )

    /**
     * The template of every kind, in the order written, its own `template` or the body (m4-19); the body alone in Symphony
     * mode.
     */
    private fun prompts(workflow: WorkflowFile): List<PromptToCheck> {
        val kinds = workflow.config.kinds ?: return listOf(PromptToCheck(workflow.promptTemplate, null, null, BODY))
        return kinds.named.map { (name, kind) ->
            val own = kind.template
            PromptToCheck(
                text = own?.text ?: workflow.promptTemplate,
                name = own?.let { "kinds.$name (${it.path})" },
                sourcesKind = "kinds.$name".takeIf { kind.context.files.isNotEmpty() },
                source = own?.let { "kinds.$name.template (${it.path})" } ?: BODY,
            )
        }
    }

    /**
     * Why a kind that declares `context.files` cannot show them (m4-19): its template never uses `loop.context_sources`,
     * and Loop never inserts the list by itself. Null when it does or the kind declares none.
     */
    private fun unusedSources(
        prompt: PromptToCheck,
        template: Template,
    ): String? {
        val kindKey = prompt.sourcesKind ?: return null
        if (template.uses(PromptVariables.LOOP, PromptVariables.CONTEXT_SOURCES)) return null
        return "$kindKey.context.files is set, but ${prompt.source} never uses loop.${PromptVariables.CONTEXT_SOURCES}, " +
            "place {{ loop.${PromptVariables.CONTEXT_SOURCES} }} where the sources go"
    }

    /**
     * Renders [template] for a first run, each retry kind (m3-11) and a refutation with its findings (m2-06), each in a
     * new and a reused workspace (bl-19), each with and without context sources (m4-19), so every branch on them renders.
     * The session brief is set as the Orchestrator sets it (m3-10): null on the first run in a new workspace, set on every
     * other sample, so both of its branches render. The first render error, or null.
     */
    private fun renderSamples(template: Template): Throwable? {
        for ((attempt, findings, retryKind) in SAMPLES) {
            for (workspaceReused in listOf(false, true)) {
                val brief = PromptVariables.SAMPLE_SESSION_BRIEF.takeIf { attempt != null || workspaceReused || retryKind != null }
                for (sources in listOf(null, PromptVariables.SAMPLE_CONTEXT_SOURCES)) {
                    val variables =
                        PromptVariables.of(
                            PromptVariables.SAMPLE_ISSUE,
                            attempt,
                            findings,
                            workspaceReused,
                            brief,
                            retryKind,
                            sources,
                            PromptVariables.SAMPLE_ANSWER.takeIf { retryKind == PromptVariables.RETRY_ANSWER },
                        )
                    template.render(variables).exceptionOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Why the template alone is over the prompt limit (m3-13), or null when it fits or no window is known: the render
     * with an empty issue, a first dispatch in a new workspace, no findings, no brief and no retry kind, counted with
     * the counter of the runner's model.
     */
    private fun templateOverLimit(
        workflow: WorkflowFile,
        template: Template,
        name: String?,
    ): String? {
        val model = promptModel(workflow)
        val context = workflow.config.context
        val limit = context.promptLimitFor(model) ?: return null
        val alone =
            template
                .render(PromptVariables.of(PromptSlots.emptied(PromptVariables.SAMPLE_ISSUE), null, null, false))
                .getOrElse { return null }
        val counter = TokenCounters.forModel(model)
        val tokens = counter.count(alone)
        if (tokens <= limit.tokens) return null
        return "${subject(name)} alone is $tokens tokens, over the prompt limit of ${limit.tokens} tokens " +
            "(context.prompt_limit ${context.promptLimit} of a ${limit.window} token window, ${counter.method})"
    }

    private fun workflowFailure(error: Throwable): PreflightResult.Failed {
        val code =
            when (error) {
                is WorkflowError.MissingFile -> "missing_workflow_file"
                is WorkflowError.Parse -> "workflow_parse_error"
                is WorkflowError.FrontMatterNotAMap -> "workflow_front_matter_not_a_map"
                is WorkflowError.InvalidConfig -> INVALID_CONFIG
                is WorkflowError.MissingEnvVar -> "missing_env_var"
                is WorkflowError.TrackerConfig -> error.category
                else -> INTERNAL_ERROR
            }
        return PreflightResult.Failed(code, error.firstLine())
    }

    /** `the prompt template`, and of which kind when the template is a kind's own [name] (m4-19). */
    private fun subject(name: String?): String = if (name == null) "the prompt template" else "the prompt template of $name"

    private fun templateFailure(
        error: Throwable,
        name: String?,
    ): PreflightResult.Failed =
        when (error) {
            is TemplateError.Render ->
                PreflightResult.Failed(
                    "template_render_error",
                    error.variable?.let { "${subject(name)} uses \"$it\", which is not a variable" }
                        ?: "${subject(name)} does not render: ${error.firstLine()}",
                )
            is TemplateError.Parse ->
                PreflightResult.Failed(
                    "template_parse_error",
                    error.filter?.let { "${subject(name)} uses the filter \"$it\", which does not exist" }
                        ?: "${subject(name)} does not parse: ${error.firstLine()}",
                )
            else -> PreflightResult.Failed(INTERNAL_ERROR, "preflight failed: ${error.firstLine()}")
        }

    private fun Throwable.firstLine(): String = (message ?: this::class.simpleName ?: "unknown error").lineSequence().first().trimEnd('.')

    private companion object {
        /**
         * The `attempt`, `loop.verify_findings` and `loop.retry_kind` of each sample render: a first run, then each retry
         * kind as Loop sets it, a rotation both on a first dispatch and on a retry (m3-11), and the dispatch after an answer,
         * which also sets `loop.answer` (m4-52).
         */
        val SAMPLES: List<Triple<Int?, String?, String?>> =
            listOf(
                Triple(null, null, null),
                Triple(1, null, PromptVariables.RETRY_CONTINUATION),
                Triple(1, null, PromptVariables.RETRY_ERROR),
                Triple(1, PromptVariables.SAMPLE_VERIFY_FINDINGS, PromptVariables.RETRY_REFUTED),
                Triple(null, null, PromptVariables.RETRY_ROTATION),
                Triple(1, PromptVariables.SAMPLE_VERIFY_FINDINGS, PromptVariables.RETRY_ROTATION),
                // m4-52: the dispatch after `loop answer`, with `loop.answer` set.
                Triple(null, null, PromptVariables.RETRY_ANSWER),
            )

        const val INVALID_CONFIG = "invalid_config"
        const val BODY = "the WORKFLOW.md body"
        const val INTERNAL_ERROR = "internal_error"
    }
}
