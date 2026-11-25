package loop.core.workflow

import loop.api.PluginProfile
import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.ResourceDeclaration
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
    fun `m4-42 exporters are kept as written, none when the key is absent`() {
        assertEquals(emptyMap(), load(bare).exporters)
        val workflow =
            load(
                bare.replace(
                    "runner:",
                    "exporters:\n  otel:\n    endpoint: http://localhost:4318\n    headers:\n      authorization: \$OTEL_TOKEN\nrunner:",
                ),
            )

        assertEquals(
            mapOf("otel" to mapOf("endpoint" to "http://localhost:4318", "headers" to mapOf("authorization" to "\$OTEL_TOKEN"))),
            workflow.exporters,
        )
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

    /** [minimal] with the `kinds:` lines [kinds], its keys indented two spaces, and a `notes` files tracker beside the primary. */
    private fun withKinds(kinds: String): String =
        minimal.replace(
            "polling:",
            "trackers:\n  - { id: notes, kind: files, provider: { dir: notes } }\nkinds:\n" + kinds.trimEnd() + "\npolling:",
        )

    private fun kindsError(kinds: String): String = assertIs<WorkflowError.InvalidConfig>(loadError(withKinds(kinds))).message!!

    private val codeKind = "  code:\n    verify:\n      checks: []\n"

    @Test
    fun `m4-18 a workflow with only code and no match parses to the same config as before`() {
        val kinds = load(withKinds(codeKind + "    ready: { require_sections: [Acceptance criteria] }\n")).config.kinds

        assertEquals(KindsConfig(KindConfig(emptyList(), ReadyConfig(listOf("Acceptance criteria")))), kinds)
        assertEquals(KindMatch.DEFAULT, kinds?.code?.match)
        assertEquals(listOf("code"), kinds?.named?.keys?.toList())
    }

    @Test
    fun `m4-18 a second kind name next to code is accepted, with its own slots and match, in the order written`() {
        val kinds =
            load(
                withKinds(
                    codeKind +
                        "  content:\n    match: { label: Content }\n" +
                        "    ready: { require_sections: [Brief] }\n" +
                        "    verify:\n      checks:\n        - { type: file_exists, path: out/post.md }\n" +
                        "    delivery: { policy: human }\n" +
                        "  research:\n    match: { tracker: notes, state: Todo }\n    verify:\n      checks: []\n",
                ),
            ).config.kinds!!

        assertEquals(listOf("code", "content", "research"), kinds.named.keys.toList())
        assertEquals(
            KindConfig(
                checks = listOf(CheckConfig.FileExists("out/post.md")),
                ready = ReadyConfig(listOf("Brief")),
                match = KindMatch(label = "content"),
            ),
            kinds.named["content"],
        )
        assertEquals(KindMatch(tracker = "notes", state = "Todo"), kinds.named.getValue("research").match)
        assertEquals(KindConfig(emptyList()), kinds.code)
    }

    @Test
    fun `m4-18 the default may be another kind when code has a match`() {
        val kinds =
            load(
                withKinds(
                    "  code:\n    match: { label: code }\n    verify:\n      checks: []\n" +
                        "  content:\n    match: { default: true }\n    verify:\n      checks: []\n",
                ),
            ).config.kinds!!

        assertEquals(KindMatch(label = "code"), kinds.code.match)
        assertEquals(KindMatch.DEFAULT, kinds.named.getValue("content").match)
    }

    @Test
    fun `m4-18 preflight refuses two default kinds`() {
        assertEquals(
            "kinds.code and kinds.content are both the default kind, keep one match: { default: true }, " +
                "code is the default when it has no match",
            kindsError(codeKind + "  content:\n    match: { default: true }\n    verify:\n      checks: []\n"),
        )
    }

    @Test
    fun `m4-18 preflight refuses a kind with neither match nor default`() {
        assertEquals(
            "kinds.content.match is required, only the code kind is the default without one",
            kindsError(codeKind + "  content:\n    verify:\n      checks: []\n"),
        )
    }

    @Test
    fun `m4-18 preflight refuses an unknown match key, a front matter field key included`() {
        val supported = "use default, label, tracker or state"
        assertEquals(
            "kinds.content.match.field is not supported, $supported",
            kindsError(codeKind + "  content:\n    match: { field: { type: post } }\n    verify:\n      checks: []\n"),
        )
        assertEquals(
            "kinds.content.match.type is not supported, $supported",
            kindsError(codeKind + "  content:\n    match: { label: content, type: post }\n    verify:\n      checks: []\n"),
        )
    }

    @Test
    fun `m4-18 preflight refuses a match it cannot read`() {
        fun content(match: String) = kindsError(codeKind + "  content:\n    match: $match\n    verify:\n      checks: []\n")

        assertEquals("kinds.content.match must be a map", content("content"))
        assertEquals("kinds.content.match must hold default, label, tracker or state", content("{}"))
        assertEquals("kinds.content.match must hold default, label, tracker or state", content("{ default: false }"))
        assertEquals("kinds.content.match.default must be true or false, got \"yes\"", content("{ default: yes }"))
        assertEquals("kinds.content.match.default is alone in its match", content("{ default: true, label: post }"))
        assertEquals("kinds.content.match.label must not be blank", content("{ label: \"  \" }"))
        assertEquals("kinds.content.match.state must not be blank", content("{ state: \"\" }"))
        assertEquals(
            "kinds.content.match.tracker \"gh\" is not a tracker id, use one of default, notes",
            content("{ tracker: gh }"),
        )
    }

    @Test
    fun `m4-33 kinds kind followup tracker sends the follow ups of the kind to that tracker, none by default`() {
        val kinds =
            load(
                withKinds(
                    codeKind +
                        "  research:\n    match: { label: research }\n    followup: { tracker: notes }\n    verify:\n      checks: []\n",
                ),
            ).config.kinds!!

        assertEquals("notes", kinds.named.getValue("research").followupTracker)
        assertEquals(null, kinds.code.followupTracker)
    }

    @Test
    fun `m4-33 preflight refuses a followup tracker that is not in the registry`() {
        fun followup(value: String) = kindsError(codeKind.replace("    verify:", "    followup: $value\n    verify:"))

        assertEquals(
            "kinds.code.followup.tracker \"gh\" is not a tracker id, use one of default, notes",
            followup("{ tracker: gh }"),
        )
        assertEquals("kinds.code.followup must be a map", followup("notes"))
        assertEquals("kinds.code.followup.tracker must not be blank", followup("{ tracker: \" \" }"))
        assertEquals("kinds.code.followup.labels is not supported, use tracker", followup("{ labels: [a] }"))
    }

    @Test
    fun `m4-18 preflight refuses kinds with no default`() {
        assertEquals(
            "kinds has no default kind, give one kind match: { default: true }",
            kindsError("  code:\n    match: { label: code }\n    verify:\n      checks: []\n"),
        )
    }

    @Test
    fun `m4-18 a kind other than code reads its slots under its own name and cannot deliver a pull request yet`() {
        assertEquals(
            "kinds.content.verify is required",
            kindsError(codeKind + "  content:\n    match: { label: content }\n"),
        )
        assertEquals(
            "kinds.content.verify.checks[0].run is required",
            kindsError(codeKind + "  content:\n    match: { label: content }\n    verify:\n      checks:\n        - { type: command }\n"),
        )
        assertEquals(
            "kinds.content.delivery.pull_request is not supported yet, only the code kind delivers a pull request",
            kindsError(
                codeKind + "  content:\n    match: { label: content }\n    verify:\n      checks: []\n" +
                    "    delivery:\n      pull_request: { repo: o/r, token: \$GH }\n",
            ),
        )
    }

    @Test
    fun `m4-18 kinds still need a code kind`() {
        assertEquals(
            "kinds must be a map with a code kind",
            kindsError("  content:\n    match: { default: true }\n    verify:\n      checks: []\n"),
        )
    }

    @Test
    fun `m4-18 on_delivered move_to must be a terminal state for every kind`() {
        assertEquals(
            "kinds.content.delivery.on_delivered.move_to is \"review\", which is not a terminal state, use one of done, canceled",
            kindsError(
                codeKind + "  content:\n    match: { label: content }\n    verify:\n      checks: []\n" +
                    "    delivery:\n      on_delivered: { move_to: review }\n",
            ),
        )
    }

    /** m4-24: [bare] with the `resources:` lines [resources], loaded with a check whose providers serve `ios-simulator`. */
    private fun withResources(resources: String): Result<WorkflowFile> =
        WorkflowLoader(env = emptyMap(), resourceCheck = { kind -> Result.success(kind == "ios-simulator") })
            .load(write(bare.replace("runner:", "resources:\n" + resources.trimEnd() + "\nrunner:")))

    private fun resourcesError(resources: String): String =
        assertIs<WorkflowError.InvalidConfig>(withResources(resources).exceptionOrNull()).message!!

    @Test
    fun `m4-24 resources parses every field of each entry, in the order written`() {
        val workflow =
            withResources(
                "  - kind: ios-simulator\n    id: iphone-16\n    can: [ui tests, deep links]\n    cannot: [push notifications]\n" +
                    "    restore:\n      - xcrun simctl erase iphone-16\n      - xcrun simctl boot iphone-16\n" +
                    "    read_only_check: xcrun simctl listapps iphone-16\n" +
                    "  - { kind: ios-simulator, id: ipad, cannot: [real calls, sms] }\n",
            ).getOrThrow()

        assertEquals(
            listOf(
                ResourceDeclaration(
                    kind = "ios-simulator",
                    id = "iphone-16",
                    can = listOf("ui tests", "deep links"),
                    cannot = listOf("push notifications"),
                    restore = listOf("xcrun simctl erase iphone-16", "xcrun simctl boot iphone-16"),
                    readOnlyCheck = "xcrun simctl listapps iphone-16",
                ),
                ResourceDeclaration(kind = "ios-simulator", id = "ipad", cannot = listOf("real calls", "sms")),
            ),
            workflow.config.resources,
        )
        assertEquals(emptyList(), workflow.configWarnings)
        assertEquals(emptyList(), load(bare).config.resources)
    }

    @Test
    fun `m4-24 preflight refuses a duplicate resource id`() {
        assertEquals(
            "resources[1].id \"iphone-16\" is already the id of resources[0]",
            resourcesError("  - { kind: ios-simulator, id: iphone-16 }\n  - { kind: avd, id: iphone-16 }\n"),
        )
    }

    @Test
    fun `m4-24 preflight refuses a resource with no kind`() {
        assertEquals("resources[1].kind is required", resourcesError("  - { kind: ios-simulator, id: iphone-16 }\n  - { id: pixel }\n"))
        assertEquals("resources[0].kind is required", resourcesError("  - { kind: \" \", id: pixel }\n"))
    }

    @Test
    fun `m4-24 a resource kind with no provider loads as a plain lock, with one log line naming the kind`() {
        val workflow =
            withResources(
                "  - { kind: avd, id: pixel-1 }\n  - { kind: ios-simulator, id: iphone-16 }\n  - { kind: avd, id: pixel-2 }\n" +
                    "  - { kind: ios-simulater, id: typo }\n",
            ).getOrThrow()

        assertEquals(listOf("pixel-1", "iphone-16", "pixel-2", "typo"), workflow.config.resources.map { it.id })
        assertEquals(
            listOf(
                "resources kind \"avd\" has no resource provider, Loop holds its resources as plain locks",
                "resources kind \"ios-simulater\" has no resource provider, Loop holds its resources as plain locks",
            ),
            workflow.configWarnings,
        )
    }

    @Test
    fun `m4-24 preflight refuses resources it cannot read, and warns on an unknown key of an entry`() {
        assertEquals("resources must be a list", resourcesError("  iphone: { kind: ios-simulator }\n"))
        assertEquals("resources[0] must be a map", resourcesError("  - iphone-16\n"))
        assertEquals("resources[0].id is required", resourcesError("  - { kind: ios-simulator }\n"))
        assertEquals("resources[0].kind must be a string", resourcesError("  - { kind: [avd], id: pixel }\n"))
        assertEquals("resources[0].can must be a list of strings", resourcesError("  - { kind: avd, id: pixel, can: ui tests }\n"))
        assertEquals("resources[0].restore must be a list of strings", resourcesError("  - { kind: avd, id: pixel, restore: [[a]] }\n"))
        assertEquals(
            "resources[0].read_only_check must be a string",
            resourcesError("  - { kind: avd, id: pixel, read_only_check: [a, b] }\n"),
        )
        assertEquals(
            listOf("resources[0].restor is not a key of a resource, Loop ignores it"),
            withResources("  - { kind: ios-simulator, id: iphone-16, restor: [a] }\n").getOrThrow().configWarnings,
        )
    }

    @Test
    fun `m4-27 a resource entry's provider map reaches the provider as text, and an entry without one gets an empty map`() {
        val workflow =
            withResources(
                "  - kind: ios-simulator\n    id: iphone-16\n    provider:\n      device_type: iPhone 16\n      runtime: \"iOS 18.2\"\n" +
                    "      prepare_timeout_seconds: 600\n      base: 18.10\n" +
                    "  - { kind: ios-simulator, id: ipad }\n",
            ).getOrThrow()

        assertEquals(
            listOf(
                mapOf("device_type" to "iPhone 16", "runtime" to "iOS 18.2", "prepare_timeout_seconds" to "600", "base" to "18.10"),
                emptyMap(),
            ),
            workflow.config.resources.map { it.provider },
        )
        assertEquals(emptyList(), workflow.configWarnings)
    }

    @Test
    fun `m4-27 preflight refuses a provider map it cannot read`() {
        assertEquals("resources[0].provider must be a map", resourcesError("  - { kind: avd, id: pixel, provider: [a] }\n"))
        assertEquals(
            "resources[0].provider.runtime must be a string",
            resourcesError("  - { kind: avd, id: pixel, provider: { runtime: [a] } }\n"),
        )
    }

    @Test
    fun `m4-24 resource plugins that could not be found fail the load with the reason, and a loader without a check sees no provider`() {
        val failing =
            WorkflowLoader(env = emptyMap(), resourceCheck = { Result.failure(IllegalStateException("cannot load the resource plugins")) })
        val text = bare.replace("runner:", "resources:\n  - { kind: avd, id: pixel }\nrunner:")

        assertEquals(
            "cannot load the resource plugins",
            assertIs<WorkflowError.InvalidConfig>(failing.load(write(text)).exceptionOrNull()).message,
        )
        assertEquals(
            listOf("resources kind \"avd\" has no resource provider, Loop holds its resources as plain locks"),
            load(text).configWarnings,
        )
    }
}
