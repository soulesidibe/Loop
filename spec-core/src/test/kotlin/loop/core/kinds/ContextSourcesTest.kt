package loop.core.kinds

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import loop.core.workflow.KindConfig
import loop.core.workflow.KindContext
import loop.core.workflow.KindMatch
import loop.core.workflow.KindTemplate
import loop.core.workflow.KindsConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.PromptSlots
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowConfig
import loop.core.workflow.WorkflowError
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.core.workflow.commandRunner
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * m4-19: a kind's `context.files` are pointers the agent reads on demand. They render as a short list where the
 * template places `loop.context_sources`, counted in the `context_sources` slot, and a path missing from the workspace
 * is listed as missing. A kind may set its own `template`, rendered instead of the `WORKFLOW.md` body.
 */
class ContextSourcesTest {
    private val folder: Path = createTempDirectory("loop-context-sources").toRealPath()
    private val workspace: Path = folder.resolve("workspace").createDirectories()

    private val templates = TemplateEngine()

    private fun template(text: String) = templates.parse(text, PromptVariables.schema).getOrThrow()

    private val placed =
        "Work on {{ issue.identifier }}.{% if loop.context_sources %}\nRead when needed:\n{{ loop.context_sources }}{% endif %}"

    @Test
    fun `each path renders as one line, in the order written`() {
        workspace.resolve("docs/brand").createDirectories()
        workspace.resolve("docs/brand/voice.md").writeText("voice")
        workspace.resolve("posts/published").createDirectories()

        val sources = ContextSources.render(listOf("docs/brand/voice.md", "posts/published/"), workspace)

        assertEquals("- docs/brand/voice.md\n- posts/published/", sources.text)
        assertEquals(emptyList(), sources.missing)
    }

    @Test
    fun `an empty list renders nothing`() {
        val sources = ContextSources.render(emptyList(), workspace)

        assertNull(sources.text)
        assertEquals(emptyList(), sources.missing)
    }

    @Test
    fun `a path missing from the workspace is listed with a note and named as missing`() {
        workspace.resolve("present.md").writeText("here")

        val sources = ContextSources.render(listOf("present.md", "gone.md", "posts/"), workspace)

        assertEquals("- present.md\n- gone.md (missing from the workspace)\n- posts/ (missing from the workspace)", sources.text)
        assertEquals(listOf("gone.md", "posts/"), sources.missing)
    }

    @Test
    fun `the list renders where the template places loop context_sources, and nothing without sources`() {
        val issue = PromptVariables.SAMPLE_ISSUE
        val with = template(placed).render(PromptVariables.of(issue, null, contextSources = "- a.md\n- b/")).getOrThrow()
        val without = template(placed).render(PromptVariables.of(issue, null)).getOrThrow()

        assertEquals("Work on SAMPLE-1.\nRead when needed:\n- a.md\n- b/", with)
        assertEquals("Work on SAMPLE-1.", without)
    }

    @Test
    fun `the sources count in the context_sources slot, and an absent list measures 0`() {
        val issue = PromptVariables.SAMPLE_ISSUE
        val sources = "- a.md\n- b/"
        val rendered = template(placed).render(PromptVariables.of(issue, null, contextSources = sources)).getOrThrow()

        val slots = PromptSlots.measure(template(placed), issue, null, rendered, contextSources = sources)!!

        assertEquals("\nRead when needed:\n$sources".length, slots.contextSources)
        assertEquals(rendered.length, slots.total)
        val bare = template(placed).render(PromptVariables.of(issue, null)).getOrThrow()
        assertEquals(0, PromptSlots.measure(template(placed), issue, null, bare)!!.contextSources)
    }

    @Test
    fun `the kind's own template is rendered for its issues, and the body for code without one`() {
        val content =
            IssueKind(
                "content",
                KindConfig(
                    checks = emptyList(),
                    match = KindMatch(label = "content"),
                    template = KindTemplate(".loop/templates/content.md", "Write"),
                ),
            )
        val code = IssueKind("code", KindConfig(checks = emptyList()))
        val workflow = workflowFile(KindsConfig(linkedMapOf("code" to code.config, "content" to content.config)))

        assertEquals("Write", KindPrompt.template(workflow, content))
        assertEquals("Body", KindPrompt.template(workflow, code))
        assertEquals("Body", KindPrompt.template(workflow, null))
    }

    @Test
    fun `a kind's sources are rendered against the workspace, none for a kind without context files`() {
        val content = IssueKind("content", KindConfig(checks = emptyList(), context = KindContext(listOf("gone.md"))))

        assertEquals(RenderedSources("- gone.md (missing from the workspace)", listOf("gone.md")), KindPrompt.sources(content, workspace))
        assertEquals(RenderedSources(null, emptyList()), KindPrompt.sources(IssueKind("code", KindConfig(emptyList())), workspace))
        assertEquals(RenderedSources(null, emptyList()), KindPrompt.sources(null, workspace))
    }

    @Test
    fun `the loader reads context files and a kind template relative to the WORKFLOW md folder`() {
        folder.resolve(".loop/templates").createDirectories()
        folder.resolve(".loop/templates/content.md").writeText("Write {{ issue.identifier }}. {{ loop.context_sources }}")

        val kinds =
            load(
                contentKind("    template: .loop/templates/content.md\n    context:\n      files: [docs/voice.md, posts/]"),
            ).config.kinds!!

        val content = kinds.named.getValue("content")
        assertEquals(listOf("docs/voice.md", "posts/"), content.context.files)
        assertEquals(
            KindTemplate(".loop/templates/content.md", "Write {{ issue.identifier }}. {{ loop.context_sources }}"),
            content.template,
        )
        assertEquals(KindContext(), kinds.code.context)
        assertNull(kinds.code.template)
    }

    @Test
    fun `the loader refuses an absolute or escaping context path, a blank one and an unknown context key`() {
        assertEquals(
            "kinds.content.context.files[0] is /etc/voice.md, use a path relative to the workspace",
            loadError(contentKind("    context:\n      files: [/etc/voice.md]")),
        )
        assertEquals(
            "kinds.content.context.files[1] is ../voice.md, use a path relative to the workspace",
            loadError(contentKind("    context:\n      files: [a.md, ../voice.md]")),
        )
        assertEquals(
            "kinds.content.context.files must not hold a blank path",
            loadError(contentKind("    context:\n      files: [\" \"]")),
        )
        assertEquals(
            "kinds.content.context.file is not supported, use files",
            loadError(contentKind("    context:\n      file: [a.md]")),
        )
    }

    @Test
    fun `preflight refuses a kind template path that does not exist`() {
        val result = preflight(contentKind("    template: .loop/templates/nope.md"))

        val failed = assertIs<PreflightResult.Failed>(result)
        assertEquals("invalid_config", failed.code)
        assertEquals("kinds.content.template is .loop/templates/nope.md, which does not exist", failed.message)
    }

    @Test
    fun `preflight refuses a kind that declares context files with a template that never uses loop context_sources`() {
        val onBody = assertIs<PreflightResult.Failed>(preflight(contentKind("    context:\n      files: [docs/voice.md]")))
        assertEquals("invalid_config", onBody.code)
        assertEquals(
            "kinds.content.context.files is set, but the WORKFLOW.md body never uses loop.context_sources, " +
                "place {{ loop.context_sources }} where the sources go",
            onBody.message,
        )

        folder.resolve("content.md").writeText("Write {{ issue.identifier }}")
        val onOwn = assertIs<PreflightResult.Failed>(preflight(contentKind("    template: content.md\n    context:\n      files: [a.md]")))
        assertEquals(
            "kinds.content.context.files is set, but kinds.content.template (content.md) never uses loop.context_sources, " +
                "place {{ loop.context_sources }} where the sources go",
            onOwn.message,
        )

        folder
            .resolve(
                "content.md",
            ).writeText("Write {{ issue.identifier }}.{% if loop.context_sources %} {{ loop.context_sources }}{% endif %}")
        assertIs<PreflightResult.Ok>(preflight(contentKind("    template: content.md\n    context:\n      files: [a.md]")))
        assertIs<PreflightResult.Ok>(preflight(contentKind("    context:\n      files: [a.md]"), body = placed))
    }

    @Test
    fun `preflight renders loop context_sources for every kind, with and without sources`() {
        val brokenWithSources = "Work.{% if loop.context_sources %} {{ issue[\"nope\"] }}{% endif %}"
        val brokenWithout = "Work.{% unless loop.context_sources %} {{ issue[\"nope\"] }}{% endunless %}"

        // The body of code, a kind with no context files.
        assertEquals("template_render_error", assertIs<PreflightResult.Failed>(preflight(contentKind(""), body = brokenWithSources)).code)
        assertEquals("template_render_error", assertIs<PreflightResult.Failed>(preflight(contentKind(""), body = brokenWithout)).code)

        // The own template of another kind, the body being fine.
        folder.resolve("content.md").writeText(brokenWithSources)
        val own = assertIs<PreflightResult.Failed>(preflight(contentKind("    template: content.md")))
        assertEquals("template_render_error", own.code)
        assertEquals("the prompt template of kinds.content (content.md) uses \"issue[\"nope\"]\", which is not a variable", own.message)

        folder.resolve("content.md").writeText("Write.{% if loop.context_sources %} {{ loop.context_sources }}{% endif %}")
        assertIs<PreflightResult.Ok>(preflight(contentKind("    template: content.md")))
    }

    private fun workflowFile(kinds: KindsConfig): WorkflowFile =
        WorkflowFile(
            config = WorkflowConfig(kinds = kinds),
            path = folder.resolve("WORKFLOW.md"),
            trackerKind = "files",
            trackerDir = folder.resolve(".loop/issues"),
            activeStates = listOf("todo"),
            terminalStates = listOf("done"),
            requiredLabels = emptyList(),
            workspaceRoot = folder.resolve("workspaces"),
            runner = commandRunner("./agent.sh"),
            pollingIntervalMs = 1_000,
            promptTemplate = "Body",
        )

    /** A `kinds:` section with `code` and a `content` kind matched by label, [settings] added to `content`. */
    private fun contentKind(settings: String): String =
        "kinds:\n  code:\n    verify:\n      checks: []\n  content:\n    match: { label: content }\n    verify:\n      checks: []\n" +
            settings

    private fun write(
        kinds: String,
        body: String = "Work on {{ issue.identifier }}",
    ): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                "---\ntracker:\n  kind: files\nrunner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n$kinds\n---\n$body",
            )
        }

    private val loader = WorkflowLoader(env = emptyMap())

    private fun load(kinds: String): WorkflowFile = loader.load(write(kinds)).getOrThrow()

    private fun loadError(kinds: String): String =
        assertIs<WorkflowError.InvalidConfig>(loader.load(write(kinds)).exceptionOrNull()).message!!

    private fun preflight(
        kinds: String,
        body: String = "Work on {{ issue.identifier }}",
    ): PreflightResult =
        runBlocking {
            Preflight(loader, write(kinds, body), templates, trackerCheck = { null }).check().let {
                if (it is PreflightResult.Failed) it.copy(source = null) else it
            }
        }
}
