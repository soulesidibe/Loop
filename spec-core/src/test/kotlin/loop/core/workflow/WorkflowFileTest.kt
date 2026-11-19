package loop.core.workflow

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class WorkflowFileTest {
    private val folder: Path = createTempDirectory("loop-workflow").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private val minimal =
        """
        ---
        tracker:
          kind: files
          active_states: [todo]
          required_labels: [ready-for-agent]
        workspace:
          root: ./workspaces
        runner:
          kind: command
        runners:
          command:
            command: ./agent.sh
        polling:
          interval_ms: 1000
        ---
        Work on {{ issue.identifier }}: {{ issue.title }}
        """.trimIndent()

    private val bare =
        """
        ---
        tracker:
          kind: files
        runner:
          kind: command
        runners:
          command:
            command: ./agent.sh
        ---
        Prompt
        """.trimIndent()

    private fun write(text: String): Path = folder.resolve("WORKFLOW.md").also { it.writeText(text) }

    private fun load(text: String): WorkflowFile = loader.load(write(text)).getOrThrow()

    private fun loadError(text: String): Throwable? = loader.load(write(text)).exceptionOrNull()

    @Test
    fun `the minimal workflow gives its tracker settings`() {
        val workflow = load(minimal)

        assertEquals("files", workflow.trackerKind)
        assertEquals(listOf("todo"), workflow.activeStates)
        assertEquals(listOf("ready-for-agent"), workflow.requiredLabels)
    }

    @Test
    fun `the minimal workflow gives its runner command`() {
        assertEquals(commandRunner("./agent.sh"), load(minimal).runner)
    }

    @Test
    fun `the minimal workflow gives its polling interval`() {
        assertEquals(1000L, load(minimal).pollingIntervalMs)
    }

    @Test
    fun `the path is the absolute path of the file read`() {
        assertEquals(folder.resolve("WORKFLOW.md"), load(minimal).path)
    }

    @Test
    fun `the prompt template is the trimmed body after the front matter`() {
        assertEquals("Work on {{ issue.identifier }}: {{ issue.title }}", load("$minimal\n\n").promptTemplate)
    }

    @Test
    fun `absent active states default to todo and in_progress`() {
        assertEquals(listOf("todo", "in_progress"), load(bare).activeStates)
    }

    @Test
    fun `absent terminal states default to done and canceled`() {
        assertEquals(listOf("done", "canceled"), load(bare).terminalStates)
    }

    @Test
    fun `absent required labels default to none`() {
        assertEquals(emptyList(), load(bare).requiredLabels)
    }

    @Test
    fun `absent polling interval defaults to 30000`() {
        assertEquals(30_000L, load(bare).pollingIntervalMs)
    }

    @Test
    fun `absent tracker dir defaults to loop issues under the workflow folder`() {
        assertEquals(folder.resolve(".loop/issues"), load(bare).trackerDir)
    }

    @Test
    fun `a relative tracker dir resolves against the workflow folder`() {
        val text = bare.replace("kind: files", "kind: files\n  provider:\n    dir: ./tickets")

        assertEquals(folder.resolve("tickets"), load(text).trackerDir)
    }

    @Test
    fun `a relative workspace root resolves against the workflow folder`() {
        assertEquals(folder.resolve("workspaces"), load(minimal).workspaceRoot)
    }

    @Test
    fun `unknown keys are ignored`() {
        val text = bare.replace("runner:", "hooks:\n  before_run: echo hi\nextra: 1\nrunner:")

        assertEquals(commandRunner("./agent.sh"), load(text).runner)
    }

    @Test
    fun `a tracker kind other than files is an error`() {
        assertIs<WorkflowError.InvalidConfig>(loadError(bare.replace("kind: files", "kind: linear")))
    }

    @Test
    fun `a runner kind the runner check refuses fails with its sentence`() {
        val checked =
            WorkflowLoader(
                env = emptyMap(),
                runnerCheck = { settings ->
                    "runner.kind \"${settings.kind}\" is not supported, use command".takeIf {
                        settings.kind !=
                            "command"
                    }
                },
            )

        val error = checked.load(write(bare.replace("kind: command", "kind: codex"))).exceptionOrNull()

        assertEquals("runner.kind \"codex\" is not supported, use command", assertIs<WorkflowError.InvalidConfig>(error).message)
        assertEquals(commandRunner("./agent.sh"), checked.load(write(bare)).getOrThrow().runner)
    }

    @Test
    fun `an absent runner kind fails`() {
        assertIs<WorkflowError.InvalidConfig>(loadError(bare.replace("runner:\n  kind: command\n", "")))
    }

    @Test
    fun `runners that is not a map fails`() {
        val error = loadError(bare.replace("runners:\n  command:\n    command: ./agent.sh\n", "runners: [a]\n"))

        assertEquals("runners must be a map", assertIs<WorkflowError.InvalidConfig>(error).message)
    }

    @Test
    fun `the runner check sees the kind and every runners section as written, which the loader never reads (m3-04)`() {
        val text =
            bare.replace(
                "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n",
                "runner:\n  kind: claude-code\nrunners:\n  claude-code:\n    model: opus\n" +
                    "    turn_timeout_ms: 600000\n  command:\n    command: ./agent.sh\n",
            )
        val seen = mutableListOf<RunnerSettings>()

        val runner =
            WorkflowLoader(env = emptyMap(), runnerCheck = {
                seen += it
                null
            }).load(write(text)).getOrThrow().runner

        val expected =
            RunnerSettings(
                "claude-code",
                mapOf(
                    "claude-code" to mapOf("model" to "opus", "turn_timeout_ms" to "600000"),
                    "command" to mapOf("command" to "./agent.sh"),
                ),
            )
        assertEquals(expected, runner)
        assertEquals(listOf(expected), seen)
    }

    /** m3-05: a runner check whose `command` plugin declares a profile. */
    private val profiled =
        object : RunnerCheck {
            override fun problem(settings: RunnerSettings): String? = null

            override fun profile(kind: String): RunnerProfile? =
                RunnerProfile(
                    "command",
                    PluginProfile(
                        keys =
                            listOf(
                                ProfileKey("command", ProfileType.STRING, required = true),
                                ProfileKey("timeout_ms", ProfileType.INTEGER),
                                ProfileKey("api_key", ProfileType.STRING),
                            ),
                        secrets = setOf("api_key"),
                        limits = mapOf("timeout_ms" to 1L..1000L),
                    ),
                ).takeIf { kind == "command" }
        }

    private fun profiledError(section: String): Throwable? =
        WorkflowLoader(env = mapOf("SET" to "value-of-set"), runnerCheck = profiled)
            .load(write(bare.replace("  command:\n    command: ./agent.sh\n", section)))
            .exceptionOrNull()

    @Test
    fun `m3-05 the selected runner section is checked against its profile with invalid_config`() {
        assertEquals(
            "runners.command.command is required",
            assertIs<WorkflowError.InvalidConfig>(profiledError("  command:\n    timeout_ms: 5\n")).message,
        )
        assertEquals(
            "runners.command.timeout_ms must be an integer",
            assertIs<WorkflowError.InvalidConfig>(profiledError("  command:\n    command: x\n    timeout_ms: soon\n")).message,
        )
        assertEquals(
            "runners.command.timeout_ms must be between 1 and 1000, got 0",
            assertIs<WorkflowError.InvalidConfig>(profiledError("  command:\n    command: x\n    timeout_ms: 0\n")).message,
        )
        assertEquals(
            "runners.command must be a map",
            assertIs<WorkflowError.InvalidConfig>(profiledError("  command: [x]\n")).message,
        )
        assertNull(profiledError("  command:\n    command: x\n    api_key: \$SET\n"))
    }

    @Test
    fun `m3-05 an unresolved runner secret fails like any unset variable, naming it`() {
        val error = assertIs<WorkflowError.MissingEnvVar>(profiledError("  command:\n    command: x\n    api_key: \$UNSET\n"))

        assertEquals("UNSET", error.name)
        assertEquals("runners.command.api_key uses \$UNSET, which is not set", error.message)
    }

    @Test
    fun `m3-05 unknown keys of the selected runner section are warnings, other sections are not read`() {
        val text = bare.replace("    command: ./agent.sh\n", "    command: ./agent.sh\n    comand: typo\n  other:\n    anything: 1\n")

        val workflow = WorkflowLoader(env = emptyMap(), runnerCheck = profiled).load(write(text)).getOrThrow()

        assertEquals(listOf("runners.command.comand is not a key of the command runner, Loop ignores it"), workflow.configWarnings)
    }

    @Test
    fun `without a runner check any runner kind loads`() {
        assertEquals(
            RunnerSettings("codex", mapOf("command" to mapOf("command" to "./agent.sh"))),
            load(bare.replace("kind: command", "kind: codex")).runner,
        )
    }

    @Test
    fun `the source hash follows the file text`() {
        val first = load(bare).sourceHash
        val same = load(bare).sourceHash
        val changed = load("$bare\nmore").sourceHash

        assertEquals(64, first.length)
        assertEquals(first, same)
        assertNotEquals(first, changed)
    }

    @Test
    fun `a missing file fails with missing file`() {
        val error = loader.load(folder.resolve("nope/WORKFLOW.md")).exceptionOrNull()

        assertIs<WorkflowError.MissingFile>(error)
    }

    @Test
    fun `malformed front matter fails with a parse error`() {
        assertIs<WorkflowError.Parse>(loadError("---\ntracker: [unclosed\n---\nPrompt"))
    }

    @Test
    fun `front matter that is not a map fails`() {
        assertIs<WorkflowError.FrontMatterNotAMap>(loadError("---\n- a\n- b\n---\nPrompt"))
    }
}
