package loop.core.workflow

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PreflightTest {
    private val folder: Path = createTempDirectory("loop-preflight").toRealPath()

    private fun workflow(
        prompt: String = "Work on {{ issue.identifier }}",
        extra: String = "",
    ): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |$extra
                |---
                |$prompt
                """.trimMargin(),
            )
        }

    /** The result without the source of a failure, so a failure compares by its code and message. */
    private fun check(
        path: Path,
        trackerCheck: (WorkflowFile) -> String? = { null },
    ): PreflightResult = checkWithSource(path, trackerCheck).let { if (it is PreflightResult.Failed) it.copy(source = null) else it }

    private fun checkWithSource(
        path: Path,
        trackerCheck: (WorkflowFile) -> String? = { null },
        promptModel: (WorkflowFile) -> String? = { null },
    ): PreflightResult =
        runBlocking {
            Preflight(
                loader = WorkflowLoader(env = emptyMap()),
                path = path,
                templates = TemplateEngine(),
                trackerCheck = trackerCheck,
                promptModel = promptModel,
            ).check()
        }

    /** A `context:` section whose smallest window, 4000 tokens, gives a prompt limit of 200 tokens. */
    private val smallWindow =
        """
        |context:
        |  windows:
        |    test/small-model: 4000
        |    test/large-model: 400000
        """.trimMargin()

    @Test
    fun `m3-13 a template that alone is over the prompt limit fails preflight with invalid_config naming its tokens and the limit`() {
        // 603 characters with the issue emptied: 201 tokens, one over the limit of 200.
        val result = check(workflow("Work on {{ issue.identifier }}. " + "x".repeat(593), extra = smallWindow))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", result.code)
        assertEquals(
            "the prompt template alone is 201 tokens, over the prompt limit of 200 tokens " +
                "(context.prompt_limit 0.05 of a 4000 token window, estimate)",
            result.message,
        )
    }

    @Test
    fun `m3-13 the template is measured with an empty issue and no optional slot, so a long issue never fails preflight`() {
        val source =
            // 600 characters with the issue emptied and no optional slot: 200 tokens, the limit itself.
            "Work on {{ issue.identifier }}. " + "x".repeat(590) +
                "{% if loop.verify_findings %}{{ loop.verify_findings }}{% endif %}" +
                "{% if loop.session_brief %}{{ loop.session_brief }}{% endif %}"

        assertIs<PreflightResult.Ok>(check(workflow(source, extra = smallWindow)))
    }

    @Test
    fun `m3-13 the window is the one of the runner's model when context windows has it`() {
        val source = "Work on {{ issue.identifier }}. " + "x".repeat(593)

        assertIs<PreflightResult.Ok>(checkWithSource(workflow(source, extra = smallWindow), promptModel = { "test/large-model" }))
        assertIs<PreflightResult.Failed>(checkWithSource(workflow(source, extra = smallWindow), promptModel = { "unknown-model" }))
    }

    @Test
    fun `unknown variable in the template fails preflight`() {
        val result = check(workflow("Work on {{ issue.nope }}"))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("template_render_error", result.code)
        assertEquals("the prompt template uses \"issue.nope\", which is not a variable", result.message)
    }

    @Test
    fun `unknown filter fails preflight`() {
        val result = check(workflow("{{ issue.title | nope }}"))

        assertEquals(
            PreflightResult.Failed("template_parse_error", "the prompt template uses the filter \"nope\", which does not exist"),
            result,
        )
    }

    @Test
    fun `unknown variable in an untaken branch fails preflight`() {
        val result = check(workflow("{% if attempt %}Retry {{ issue.nope }}{% endif %}{% if false %}{{ nope }}{% endif %}"))

        assertEquals(
            PreflightResult.Failed("template_render_error", "the prompt template uses \"issue.nope\", which is not a variable"),
            result,
        )
    }

    @Test
    fun `a template that uses loop verify_findings passes preflight and a misspelled loop variable fails it (m2-06)`() {
        assertIs<PreflightResult.Ok>(check(workflow("Work.{% if loop.verify_findings %} {{ loop.verify_findings | strip }}{% endif %}")))

        val result = check(workflow("{% if attempt %}{{ loop.verify_finding }}{% endif %}"))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("the prompt template uses \"loop.verify_finding\", which is not a variable", result.message)
    }

    @Test
    fun `bl-19 a template broken only in the reused workspace branch fails preflight`() {
        assertIs<PreflightResult.Ok>(check(workflow("Work.{% if loop.workspace_reused %} Read the earlier work.{% endif %}")))
        // An index lookup is left to the render, so this template parses and fails only where the branch renders.
        val broken = "Work.{% if loop.workspace_reused %} {{ issue[\"nope\"] }}{% endif %}"
        assertIs<PreflightResult.Ok>(check(workflow(broken.replace("loop.workspace_reused", "false"))))

        val result = check(workflow(broken))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("template_render_error", result.code)
    }

    @Test
    fun `m3-10 a template broken only in the session brief branch fails preflight`() {
        assertIs<PreflightResult.Ok>(check(workflow("Work.{% if loop.session_brief %}\n{{ loop.session_brief }}{% endif %}")))
        // Broken only where the brief is set, then only where it is null: preflight renders both.
        val broken = "Work.{% if loop.session_brief %} {{ issue[\"nope\"] }}{% endif %}"
        val brokenWhenNull = "Work.{% unless loop.session_brief %} {{ issue[\"nope\"] }}{% endunless %}"
        assertIs<PreflightResult.Ok>(check(workflow(broken.replace("loop.session_brief", "false"))))

        val result = check(workflow(broken))
        val nullResult = check(workflow(brokenWhenNull))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("template_render_error", result.code)
        assertIs<PreflightResult.Failed>(nullResult)
        assertEquals("template_render_error", nullResult.code)
    }

    @Test
    fun `m3-11 preflight renders every retry kind, a template broken only for one of them fails`() {
        assertIs<PreflightResult.Ok>(check(workflow("Work.{% if loop.retry_kind %} {{ loop.retry_kind }}{% endif %}")))
        for (kind in listOf("continuation", "rotation", "error", "refuted")) {
            val broken = "Work.{% if loop.retry_kind == \"$kind\" %} {{ issue[\"nope\"] }}{% endif %}"

            val result = check(workflow(broken))

            assertIs<PreflightResult.Failed>(result, kind)
            assertEquals("template_render_error", result.code, kind)
        }
        // A rotation always has a brief: a template that needs one there passes.
        val rotation =
            "Work.{% if loop.retry_kind == \"rotation\" %}{% unless loop.session_brief %} {{ issue[\"nope\"] }}{% endunless %}{% endif %}"
        assertIs<PreflightResult.Ok>(check(workflow(rotation)))
    }

    @Test
    fun `template locals are not unknown variables`() {
        val prompt =
            "{% assign t = issue.title %}{% capture c %}x{% endcapture %}" +
                "{% for l in issue.labels %}{{ l }}{{ forloop.index }}{% endfor %}{{ t }}{{ c }}"

        assertIs<PreflightResult.Ok>(check(workflow(prompt)))
    }

    @Test
    fun `valid workflow passes preflight`() {
        val path = workflow()

        val result = check(path)

        assertIs<PreflightResult.Ok>(result)
        assertEquals(path, result.workflow.path)
        assertEquals("Work on {{ issue.identifier }}", result.workflow.promptTemplate)
    }

    @Test
    fun `an ok preflight carries the text and hash of the file it read`() {
        val path = workflow()

        val result = assertIs<PreflightResult.Ok>(check(path))

        assertEquals(path.toFile().readText(), result.workflow.sourceText)
        assertEquals(workflowHash(path.toFile().readText()), result.workflow.sourceHash)
    }

    @Test
    fun `a failed preflight carries the text and hash of the file it read`() {
        val path = workflow(extra = "agent:\n  max_turns: 0")

        val result = assertIs<PreflightResult.Failed>(checkWithSource(path))

        val source = checkNotNull(result.source)
        assertEquals(path.toFile().readText(), source.text)
        assertEquals(workflowHash(path.toFile().readText()), source.hash)
    }

    @Test
    fun `a failed template check carries the source too`() {
        val result = assertIs<PreflightResult.Failed>(checkWithSource(workflow("{{ nope }}")))

        assertEquals("template_render_error", result.code)
        assertEquals(workflowHash(folder.resolve("WORKFLOW.md").toFile().readText()), result.source?.hash)
    }

    @Test
    fun `a missing file fails without a source`() {
        val result = assertIs<PreflightResult.Failed>(checkWithSource(folder.resolve("nope/WORKFLOW.md")))

        assertEquals(null, result.source)
    }

    @Test
    fun `missing file fails preflight with its code`() {
        val result = check(folder.resolve("nope/WORKFLOW.md"))

        assertIs<PreflightResult.Failed>(result)
        assertEquals("missing_workflow_file", result.code)
    }

    @Test
    fun `invalid config fails preflight with its message`() {
        val result = check(workflow(extra = "agent:\n  max_turns: 0"))

        assertEquals(
            PreflightResult.Failed("invalid_config", "agent.max_turns must be a positive integer, got 0"),
            result,
        )
    }

    @Test
    fun `a max wake turns below 1 fails preflight naming the key (m4-03)`() {
        listOf("0", "-3").forEach { value ->
            val result = check(workflow(extra = "agent:\n  max_wake_turns: $value"))

            assertEquals(
                PreflightResult.Failed("invalid_config", "agent.max_wake_turns must be a positive integer, got $value"),
                result,
            )
        }
    }

    @Test
    fun `unset var fails preflight with its name`() {
        val result = check(workflow(extra = "workspace:\n  root: \$WS_ROOT"))

        assertEquals(
            PreflightResult.Failed("missing_env_var", "workspace.root uses \$WS_ROOT, which is not set"),
            result,
        )
    }

    @Test
    fun `tracker check failure fails preflight`() {
        val result = check(workflow()) { "tracker.active_states has \"nope\", which is not a state of the files tracker" }

        assertEquals(
            PreflightResult.Failed(
                "invalid_config",
                "tracker.active_states has \"nope\", which is not a state of the files tracker",
            ),
            result,
        )
    }

    @Test
    fun `a yaml parse error gives a single line message without a final period`() {
        val path =
            folder.resolve("WORKFLOW.md").also { it.writeText("---\ntracker: [unclosed\n  kind: files\n---\nPrompt\n") }

        val result = check(path)

        assertIs<PreflightResult.Failed>(result)
        assertEquals("workflow_parse_error", result.code)
        assertEquals(1, result.message.lines().size, result.message)
        assertFalse(result.message.endsWith("."), result.message)
    }

    @Test
    fun `increment and decrement counters are not unknown variables`() {
        assertIs<PreflightResult.Ok>(check(workflow("{% increment n %}{{ n }}{% decrement m %}{{ m }}")))
    }

    @Test
    fun `empty blank and nil in conditions pass preflight`() {
        val prompt =
            "{% if issue.description == blank or issue.labels == empty or issue.created_at == nil %}x{% endif %}"

        assertIs<PreflightResult.Ok>(check(workflow(prompt)))
    }

    @Test
    fun `template renders issue and attempt`() {
        val template =
            TemplateEngine()
                .parse("{{ issue.identifier }} {% if attempt %}retry {{ attempt }}{% else %}first{% endif %}", PromptVariables.schema)
                .getOrThrow()

        val first = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, attempt = null)).getOrThrow()
        val retry = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, attempt = 1)).getOrThrow()

        assertEquals("SAMPLE-1 first", first)
        assertEquals("SAMPLE-1 retry 1", retry)
    }
}
