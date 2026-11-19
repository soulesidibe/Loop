package loop.conformance

import kotlinx.coroutines.runBlocking
import loop.core.template.TemplateEngine
import loop.core.template.TemplateError
import loop.core.workflow.ConfigValue
import loop.core.workflow.EnvRef
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowError
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Symphony spec 17.1, workflow and config parsing, one test per item, against the
 * public loader, preflight and template API. Item 1 (path precedence) is in the cli's
 * RunCommandTest; items 2 and 3 (reload) are in [ReloadConformanceTest]. Item 10 covers path values
 * only: the files adapter documents no secret keys.
 */
class WorkflowConformanceTest {
    private val folder: Path = createTempDirectory("loop-conformance").toRealPath()
    private val home: Path = folder.resolve("home").createDirectories()
    private val systemTemp: Path = folder.resolve("tmp").createDirectories()

    private fun loader(env: Map<String, String> = emptyMap()): WorkflowLoader =
        WorkflowLoader(env = env, systemTemp = systemTemp, home = home)

    private fun write(
        frontMatter: String,
        prompt: String = "Work on {{ issue.identifier }}",
    ): Path = folder.resolve("WORKFLOW.md").also { it.writeText("---\n$frontMatter\n---\n$prompt\n") }

    /** The keys every valid file needs, followed by [extra]. */
    private fun valid(
        extra: String = "",
        tracker: String = "",
        prompt: String = "Work on {{ issue.identifier }}",
    ): Path =
        write(
            """
            |tracker:
            |  kind: files
            |$tracker
            |runner:
            |  kind: command
            |runners:
            |  command:
            |    command: ./agent.sh
            |$extra
            """.trimMargin(),
            prompt,
        )

    private fun load(
        path: Path,
        env: Map<String, String> = emptyMap(),
    ): WorkflowFile = loader(env).load(path).getOrThrow()

    /** The preflight result, a failure without its source so it compares by its code and message. */
    private fun preflight(
        path: Path,
        trackerCheck: (WorkflowFile) -> String? = { null },
    ): PreflightResult =
        runBlocking { Preflight(loader(), path, TemplateEngine(), trackerCheck).check() }
            .let { if (it is PreflightResult.Failed) it.copy(source = null) else it }

    @Test
    fun `17_1 item 4 missing workflow file returns a typed error`() {
        val missing = folder.resolve("nope/WORKFLOW.md")

        assertIs<WorkflowError.MissingFile>(loader().load(missing).exceptionOrNull())
        assertEquals("missing_workflow_file", assertIs<PreflightResult.Failed>(preflight(missing)).code)
    }

    @Test
    fun `17_1 item 5 invalid yaml front matter returns a typed error`() {
        val path = write("tracker: [unclosed")

        assertIs<WorkflowError.Parse>(loader().load(path).exceptionOrNull())
        assertEquals("workflow_parse_error", assertIs<PreflightResult.Failed>(preflight(path)).code)
    }

    @Test
    fun `17_1 item 6 front matter non map returns a typed error`() {
        val path = write("- a\n- b")

        assertIs<WorkflowError.FrontMatterNotAMap>(loader().load(path).exceptionOrNull())
        assertEquals("workflow_front_matter_not_a_map", assertIs<PreflightResult.Failed>(preflight(path)).code)
    }

    @Test
    fun `17_1 item 7 config defaults apply when optional values are missing`() {
        val workflow = load(valid())
        val config = workflow.config

        assertEquals(30_000L, workflow.pollingIntervalMs)
        assertEquals(30_000L, config.polling.intervalMs)
        assertEquals(60_000L, config.hooks.timeoutMs)
        assertEquals(10, config.agent.maxConcurrentAgents)
        assertEquals(20, config.agent.maxTurns)
        assertEquals(300_000L, config.agent.maxRetryBackoffMs)
        assertEquals(emptyMap(), config.agent.maxConcurrentAgentsByState)
        assertEquals("codex app-server", config.codex.command)
        assertEquals(3_600_000L, config.codex.turnTimeoutMs)
        assertEquals(5_000L, config.codex.readTimeoutMs)
        assertEquals(300_000L, config.codex.stallTimeoutMs)
        assertEquals(listOf("todo", "in_progress"), workflow.activeStates)
        assertEquals(listOf("done", "canceled"), workflow.terminalStates)
        assertEquals(systemTemp.resolve("loop_workspaces"), workflow.workspaceRoot)
    }

    @Test
    fun `17_1 item 8 tracker kind validation enforces a supported adapter`() {
        val path =
            write(
                """
                |tracker:
                |  kind: jira
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                """.trimMargin(),
            )

        val error = assertIs<WorkflowError.InvalidConfig>(loader().load(path).exceptionOrNull())
        assertEquals("tracker.kind \"jira\" is not supported, use files", error.message)
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.kind \"jira\" is not supported, use files"),
            preflight(path),
        )
    }

    @Test
    fun `17_1 item 9 tracker provider preserves adapter owned keys and validates them through the adapter`() {
        val path = valid(tracker = "  provider:\n    dir: ./issues\n    board: alpha\n    labels: [a, b]")
        val workflow = load(path)

        assertEquals(folder.resolve("issues"), workflow.trackerDir)
        assertEquals(
            mapOf(
                "dir" to ConfigValue.Str("./issues"),
                "board" to ConfigValue.Str("alpha"),
                "labels" to ConfigValue.ListValue(listOf(ConfigValue.Str("a"), ConfigValue.Str("b"))),
            ),
            workflow.config.trackerProvider,
        )

        // A fake adapter check that owns the board key.
        val adapter = { checked: WorkflowFile ->
            val board = (checked.config.trackerProvider["board"] as? ConfigValue.Str)?.value
            if (board == "beta") null else "tracker.provider.board must be beta, got $board"
        }
        assertEquals(
            PreflightResult.Failed("invalid_config", "tracker.provider.board must be beta, got alpha"),
            preflight(path, adapter),
        )
    }

    @Test
    fun `17_1 item 10 dollar var resolution works for path values`() {
        val issues = folder.resolve("from-env-issues").toString()
        val workspaces = folder.resolve("from-env-workspaces").toString()
        val path = valid(extra = "workspace:\n  root: \$WS_ROOT", tracker = "  provider:\n    dir: \$ISSUES_DIR")

        val workflow = load(path, env = mapOf("WS_ROOT" to workspaces, "ISSUES_DIR" to issues))

        assertEquals(Path.of(workspaces), workflow.workspaceRoot)
        assertEquals(Path.of(issues), workflow.trackerDir)
        assertEquals(
            setOf(EnvRef("WS_ROOT", "workspace.root"), EnvRef("ISSUES_DIR", "tracker.provider.dir")),
            workflow.config.envRefs.toSet(),
        )
        val unset =
            assertIs<WorkflowError.MissingEnvVar>(loader(mapOf("ISSUES_DIR" to issues)).load(path).exceptionOrNull())
        assertEquals("workspace.root uses \$WS_ROOT, which is not set", unset.message)
    }

    @Test
    fun `17_1 item 11 tilde path expansion works`() {
        val workflow = load(valid(extra = "workspace:\n  root: ~/loop-ws", tracker = "  provider:\n    dir: ~/issues"))

        assertEquals(home.resolve("loop-ws"), workflow.workspaceRoot)
        assertEquals(home.resolve("issues"), workflow.trackerDir)
    }

    @Test
    fun `17_1 item 12 codex command is preserved as a shell command string`() {
        val command = "codex app-server --dir ~/work \$FOO && echo done"
        val workflow = load(valid(extra = "codex:\n  command: '$command'"), env = mapOf("FOO" to "bar"))

        assertEquals(command, workflow.config.codex.command)
    }

    @Test
    fun `17_1 item 13 per state concurrency override map normalizes state names and ignores invalid values`() {
        val extra =
            """
            |agent:
            |  max_concurrent_agents_by_state:
            |    " In_Progress ": 2
            |    Todo: "3"
            |    review: 0
            |    done: -1
            |    canceled: abc
            """.trimMargin()

        val workflow = load(valid(extra = extra))

        assertEquals(mapOf("in_progress" to 2, "todo" to 3), workflow.config.agent.maxConcurrentAgentsByState)
    }

    @Test
    fun `17_1 item 14 prompt template renders issue and attempt`() {
        val source =
            "{{ issue.identifier }} {{ issue.title }} " +
                "{% if attempt %}retry {{ attempt }}{% else %}first{% endif %}"
        val template = TemplateEngine().parse(source, PromptVariables.schema).getOrThrow()

        val first = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, attempt = null)).getOrThrow()
        val retry = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, attempt = 1)).getOrThrow()

        assertEquals("SAMPLE-1 Sample title first", first)
        assertEquals("SAMPLE-1 Sample title retry 1", retry)
        assertIs<PreflightResult.Ok>(preflight(valid(prompt = "{{ issue.title }} {{ attempt }}")))
    }

    @Test
    fun `17_1 item 15 prompt rendering fails on unknown variables`() {
        val template = TemplateEngine().parse("Work on {{ nope }}").getOrThrow()

        val rendered = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, attempt = null))

        assertIs<TemplateError.Render>(rendered.exceptionOrNull())
        assertEquals(
            PreflightResult.Failed(
                "template_render_error",
                "the prompt template uses \"issue.nope\", which is not a variable",
            ),
            preflight(valid(prompt = "Work on {{ issue.nope }}")),
        )
    }
}
