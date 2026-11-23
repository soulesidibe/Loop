package loop.cli

import com.github.ajalt.clikt.testing.test
import loop.core.workflow.WorkflowFile
import loop.runner.claudecode.PreflightResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `loop run` with `runner.kind: claude-code`, end to end with a fake `claude` script. The
 * command runs in process with an injected preflight, so the result does not depend on the
 * uid or the managed settings of the machine running the tests.
 */
class ClaudeCodeRunCommandTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a claude-code attempt moves the issue to review and traces the Claude session and usage`() {
        val claude = fakeClaude()
        fixture.workflow.writeText(
            """
            |---
            |tracker:
            |  kind: files
            |  active_states: [todo]
            |  required_labels: [ready-for-agent]
            |workspace:
            |  root: ./workspaces
            |runner:
            |  kind: claude-code
            |runners:
            |  claude-code:
            |    command: "$claude"
            |polling:
            |  interval_ms: 1000
            |---
            |Work on {{ issue.identifier }}: {{ issue.title }}
            |
            """.trimMargin(),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        val rows = fixture.query("SELECT kind, phase, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        val session = assertNotNull(rows.firstOrNull { it["phase"] == "InitializingSession" }, "$rows")
        assertContains(session["data"].orEmpty(), "claude-e2e-session")
        val usage = assertNotNull(rows.firstOrNull { it["kind"] == "usage" }, "$rows")
        assertContains(usage["data"].orEmpty(), "1234")
        // m3-09: Claude Code declares occupancy, so no weaker guarantee line at startup and attempt_started says so.
        assertFalse("event=context_occupancy" in result.stderr, result.stderr)
        assertContains(rows.first { it["kind"] == "attempt_started" }["data"].orEmpty(), "\"occupancy\":\"true\"")
    }

    @Test
    fun `an estimate crossing 10M mid turn records an estimated threshold before the turn ends, the hook told`() {
        val hookOut = root.resolve("hook-out.txt")
        val claude = estimatingClaude()
        fixture.workflow.writeText(
            claudeWorkflow(claude).replace(
                "workspace:",
                "hooks:\n  on_token_threshold: echo \"${'$'}LOOP_ISSUE ${'$'}LOOP_THRESHOLD ${'$'}LOOP_TOKENS\" >> '$hookOut'\nworkspace:",
            ),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        val rows = fixture.query("SELECT seq, kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        val threshold = rows.filter { it["kind"] == "token_threshold" }
        assertEquals(1, threshold.size, "$rows")
        val data = threshold.single()["data"].orEmpty()
        assertContains(data, "\"estimated\":\"true\"")
        assertContains(data, "\"threshold\":\"10000000\"")
        assertContains(data, "\"tokens\":\"10200210\"")
        val seq = threshold.single()["seq"].orEmpty().toLong()
        val usage = rows.first { it["kind"] == "usage" }["seq"].orEmpty().toLong()
        val completed = rows.first { it["kind"] == "turn_completed" }["seq"].orEmpty().toLong()
        assertTrue(seq < usage && seq < completed, "$rows")
        assertEquals(listOf("t-1 10000000 10200210"), hookOut.readLines())
        assertContains(result.stderr, "event=token_threshold outcome=reached")
    }

    @Test
    fun `a turn woken by a background task puts the sum of its result lines in the ledger line and the attempt row (bl-25)`() {
        // m3-07: the wake comes between turns, so the next turn reads it and counts it.
        fixture.workflow.writeText(claudeWorkflow(wakingClaude()))
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        // 11.69M before the wake plus 1.95M after it, and the latest cost, which already holds the first.
        val row =
            fixture
                .query("SELECT tokens, cache_read_input_tokens, cost_usd FROM attempts WHERE issue_id = 't-1'")
                .single()
        assertEquals("13640000", row["tokens"], "$row")
        assertEquals("13400000", row["cache_read_input_tokens"], "$row")
        assertEquals(9.75, assertNotNull(row["cost_usd"]).toDouble(), 1e-9)
        val lines =
            fixture.loopHome
                .resolve("ledger")
                .resolve("${fixture.projectId()}.jsonl")
                .readLines()
        assertEquals(2, lines.size, "$lines")
        // Turn 1 ends at its result line; turn 2 holds the woken segment, read while the process was idle.
        assertContains(lines[0], "\"tokens\":11690000")
        assertContains(lines[1], "\"tokens\":1950000")
        assertContains(lines[1], "\"attempt_tokens\":13640000")
        // The turn's share of the cost, then the session's latest, which already holds the first.
        assertContains(lines[1], "\"cost_usd\":1.25")
        assertContains(lines[1], "\"attempt_cost_usd\":9.75")
    }

    @Test
    fun `past its per attempt dollar budget the attempt stops and the issue parks with budget and the budget's name (m2-20)`() {
        fixture.workflow.writeText(
            claudeWorkflow(spendingClaude(cost = "12.5")).replace("workspace:", "budgets:\n  per_attempt_usd: 10\nworkspace:"),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("todo", fixture.stateOf("t-1"), result.output)
        assertEquals("budget", fixture.query("SELECT reason FROM parked_issues WHERE issue_id = 't-1'").single()["reason"])
        val parked = fixture.query("SELECT data FROM events WHERE kind = 'issue_parked' AND issue_id = 't-1'").single()["data"].orEmpty()
        assertContains(parked, "\"reason\":\"budget\"")
        assertContains(parked, "\"budget\":\"per_attempt_usd\"")
        assertEquals(1, fixture.query("SELECT kind FROM events WHERE kind = 'budget_reached'").size)
        assertEquals(0, fixture.query("SELECT kind FROM events WHERE kind = 'retry_scheduled'").size)
        assertContains(result.stderr, "event=budget_reached")

        val trace = LoopCommand(host { PreflightResult() }).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertEquals(0, trace.statusCode, trace.output)
        assertContains(trace.output, "budget_reached")
        assertContains(trace.output, "reason=budget budget=per_attempt_usd used=12.5/10")
        val status = LoopCommand(host { PreflightResult() }).test(listOf("status", fixture.workflow.toString()))
        assertContains(status.output, "reason=budget")
    }

    @Test
    fun `bl-71 the attempt's tokens include its subagents, in the row, the ledger line and the per attempt token budget`() {
        // The main session's usage is 54,200 tokens, under the budget; its subagents bring the turn to 1,004,800.
        fixture.workflow.writeText(
            claudeWorkflow(subagentClaude()).replace("workspace:", "budgets:\n  per_attempt_tokens: 1000000\nworkspace:"),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val row =
            fixture
                .query("SELECT tokens, input_tokens, cache_read_input_tokens, cost_usd FROM attempts WHERE issue_id = 't-1'")
                .single()
        assertEquals("1004800", row["tokens"], "$row")
        assertEquals("1500", row["input_tokens"], "$row")
        assertEquals("950000", row["cache_read_input_tokens"], "$row")
        assertEquals(3.5, assertNotNull(row["cost_usd"]).toDouble(), 1e-9)
        val line =
            fixture.loopHome
                .resolve("ledger")
                .resolve("${fixture.projectId()}.jsonl")
                .readLines()
                .single()
        assertContains(line, "\"tokens\":1004800")
        assertContains(line, "\"attempt_tokens\":1004800")
        val parked = fixture.query("SELECT data FROM events WHERE kind = 'issue_parked' AND issue_id = 't-1'").single()["data"].orEmpty()
        assertContains(parked, "\"budget\":\"per_attempt_tokens\"")
    }

    @Test
    fun `m3-11 a session over the soft limit is rotated at its turn end into a new claude process, without --resume`() {
        val calls = root.resolve("claude-calls")
        // m3-14: the prompt limit at the soft limit, 200 tokens of the 1000 token window, so the rotated prompt fits
        // whole and shows the brief as it is built. The m3-11 hard limit of 0.25, above the default of 0.21 (m4-02), so
        // the 220 token report rotates at the soft limit.
        val context = "context:\n  prompt_limit: 0.20\n  hard_limit: 0.25\n  windows:\n    anthropic/claude-fake: 1000\n"
        fixture.workflow.writeText(
            claudeWorkflow(rotatingClaude(calls))
                .replace("workspace:", "${context}workspace:")
                .replace(
                    "Work on {{ issue.identifier }}",
                    "Work on {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}" +
                        "{% if loop.session_brief %} BRIEF {{ loop.session_brief }}{% endif %}",
                ),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        // Two processes in the same workspace, the second started empty: no --resume.
        val argv = listOf(calls.resolve("argv-1").readLines().single(), calls.resolve("argv-2").readLines().single())
        argv.forEach { assertFalse("--resume" in it, it) }
        val cwd = listOf(calls.resolve("cwd-1").readLines().single(), calls.resolve("cwd-2").readLines().single())
        assertEquals(1, cwd.distinct().size, "$cwd")
        val first = calls.resolve("prompt-1").readLines().single()
        val second = calls.resolve("prompt-2").readLines().single()
        assertFalse("kind=" in first, first)
        assertContains(second, "Work on t-1 kind=rotation BRIEF ### Goal and acceptance criteria")
        assertContains(second, "- Rotations: 1")
        val rows = fixture.query("SELECT kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        val rotated = rows.filter { it["kind"] == "session_rotated" }
        assertEquals(1, rotated.size, "$rows")
        val data = rotated.single()["data"].orEmpty()
        listOf(
            "\"reason\":\"soft_limit\"",
            "\"occupancy_tokens\":\"220\"",
            "\"window\":\"1000\"",
            "\"fraction\":\"0.2200\"",
            "\"rotation\":\"1\"",
            "\"max_rotations\":\"5\"",
            "\"old_runtime_session_id\":\"claude-old\"",
            "\"new_runtime_session_id\":\"claude-new\"",
        ).forEach { assertContains(data, it) }
        val lines =
            fixture.loopHome
                .resolve("ledger")
                .resolve("${fixture.projectId()}.jsonl")
                .readLines()
        assertEquals(2, lines.size, "$lines")
        assertContains(lines[0], "\"rotations\":0")
        assertContains(lines[1], "\"rotations\":1")
        // The attempt's spend keeps the first session's tokens: 1000 then 100 more.
        assertContains(lines[1], "\"attempt_tokens\":1100")
        // bl-147: the workflow names no model, so each line holds the model of its session's init line.
        lines.forEach { assertContains(it, "\"model\":\"anthropic/claude-fake\"") }
        val trace = LoopCommand(host { PreflightResult() }).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(trace.stdout, "session_rotated")
        assertContains(trace.stdout, "reason=soft_limit rotation=1/5 occupancy_tokens=220 window=1000 fraction=0.2200")
    }

    @Test
    fun `m3-12 an occupancy over the hard limit interrupts the turn at once and rotates into a new claude process, without --resume`() {
        val calls = root.resolve("claude-calls")
        fixture.workflow.writeText(hardLimitWorkflow(hardLimitClaude(calls, workingSpawns = 1)))
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        // The first process never ended its turn on its own: Loop stopped it, then started an empty one.
        assertEquals("2", calls.resolve("count").readLines().single())
        listOf("argv-1", "argv-2").forEach { name ->
            val argv = calls.resolve(name).readLines().single()
            assertFalse("--resume" in argv, argv)
        }
        val second = calls.resolve("prompt-2").readLines().single()
        assertContains(second, "Work on t-1 kind=rotation")
        val rows = fixture.query("SELECT kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        val rotated = rows.single { it["kind"] == "session_rotated" }["data"].orEmpty()
        listOf("\"reason\":\"hard_limit\"", "\"occupancy_tokens\":\"300\"", "\"window\":\"1000\"", "\"rotation\":\"1\"")
            .forEach { assertContains(rotated, it) }
        // The interrupted turn is not a failed one.
        assertTrue(rows.none { it["kind"] == "turn_failed" }, "$rows")
        assertContains(rows.first { it["kind"] == "turn_completed" }["data"].orEmpty(), "\"interrupted\":\"hard_limit\"")
        val lines =
            fixture.loopHome
                .resolve("ledger")
                .resolve("${fixture.projectId()}.jsonl")
                .readLines()
        assertContains(lines[0], "\"interrupted\":\"hard_limit\"")
        // Loop asked claude to interrupt the turn, and the usage and cost of its result line count.
        assertContains(calls.resolve("interrupt-1").readLines().single(), "\"subtype\":\"interrupt\"")
        assertContains(lines[0], "\"tokens\":301")
        assertContains(lines[0], "\"cost_usd\":0.05")
        val trace = LoopCommand(host { PreflightResult() }).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(trace.stdout, "reason=hard_limit rotation=1/5 occupancy_tokens=300 window=1000 fraction=0.3000")
        assertContains(trace.stdout, "interrupted=hard_limit")
        val log = LoopCommand(host { PreflightResult() }).test(listOf("log", fixture.workflow.toString()))
        assertContains(log.stdout, "turn 1 was interrupted at the hard limit")
        assertContains(log.stdout, "interrupted its turn at 30 percent of its window and started a new session, rotation 1 of at most 5")
    }

    @Test
    fun `m3-12 two hard limit rotations in a row without a new commit park the issue with split_issue and say so`() {
        val calls = root.resolve("claude-calls")
        val git = "git -c user.name=t -c user.email=t@example.com"
        fixture.workflow.writeText(
            hardLimitWorkflow(hardLimitClaude(calls, workingSpawns = 5))
                .replace("workspace:", "hooks:\n  after_create: $git init -q && $git commit -q --allow-empty -m first\nworkspace:"),
        )
        fixture.issue("t-1")

        val result = LoopCommand(host { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals("2", calls.resolve("count").readLines().single(), result.output)
        val rows = fixture.query("SELECT kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        val parked = rows.single { it["kind"] == "issue_parked" }["data"].orEmpty()
        assertContains(parked, "\"reason\":\"split_issue\"")
        assertContains(parked, "\"without_progress\":\"2\"")
        assertContains(fixture.issueFile("t-1").readText(), "split this issue")
        val trace = LoopCommand(host { PreflightResult() }).test(listOf("trace", "t-1", "--workflow", fixture.workflow.toString()))
        assertContains(trace.stdout, "reason=hard_limit rotation=1/5")
        assertContains(trace.stdout, "reason=split_issue without_progress=2 rotations=1 limit=hard_limit fraction=0.3000")
        val log = LoopCommand(host { PreflightResult() }).test(listOf("log", fixture.workflow.toString()))
        assertContains(log.stdout, "rotation 1 of at most 5")
        assertContains(log.stdout, "parked with the finding split this issue, after 2 session rotations in a row without progress")
        assertContains(log.stdout, "commented that it should be split")
    }

    /** The claude-code workflow of the m3-12 tests: a 1000 token window for `claude-fake` and the retry kind in the prompt. */
    private fun hardLimitWorkflow(claude: Path): String =
        claudeWorkflow(claude)
            .replace("workspace:", "context:\n  windows:\n    anthropic/claude-fake: 1000\nworkspace:")
            .replace("    command: \"$claude\"", "    command: \"$claude\"\n    turn_timeout_ms: 20000")
            .replace(
                "Work on {{ issue.identifier }}",
                "Work on {{ issue.identifier }}{% if loop.retry_kind %} kind={{ loop.retry_kind }}{% endif %}",
            )

    /**
     * A fake `claude` whose spawns write their arguments and first input line to [calls]. The first [workingSpawns]
     * report an occupancy of 300 tokens of the `claude-fake` model, then go on working without ending their turn until
     * they read the next input line: an interrupt request, written to [calls], which they answer as Claude Code does,
     * with an `error_during_execution` result line of 301 tokens and 0.05 dollars. A later one reports 50, moves the
     * issue to review and ends its turn.
     */
    private fun hardLimitClaude(
        calls: Path,
        workingSpawns: Int,
    ): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        calls.createDirectories()

        fun init(session: String) =
            """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"],"model":"claude-fake"}"""

        fun assistant(
            session: String,
            occupancy: Int,
        ) = """{"type":"assistant","message":{"id":"msg-$session","type":"message","role":"assistant",""" +
            """"content":[{"type":"text","text":"Working."}],"usage":{"input_tokens":$occupancy,"output_tokens":1,""" +
            """"cache_read_input_tokens":0,"cache_creation_input_tokens":0}},"session_id":"$session"}"""
        val interrupted =
            """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"claude-old",""" +
                """"total_cost_usd":0.05,"usage":{"input_tokens":300,"output_tokens":1,""" +
                """"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"permission_denials":[]}"""
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"claude-new",""" +
                """"total_cost_usd":0.01,"usage":{"input_tokens":100,"output_tokens":0,""" +
                """"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"permission_denials":[]}"""
        script.writeText(
            listOf(
                "#!/bin/sh",
                "set -e",
                "n=$(cat '$calls/count' 2>/dev/null || echo 0)",
                "n=$((n + 1))",
                "echo \"${'$'}n\" > '$calls/count'",
                "echo \"${'$'}@\" > '$calls/argv-'\"${'$'}n\"",
                "read -r line",
                "printf '%s\\n' \"${'$'}line\" > '$calls/prompt-'\"${'$'}n\"",
                "if [ \"${'$'}n\" -le $workingSpawns ]; then",
                "echo '${init("claude-old")}'",
                "echo '${assistant("claude-old", 300)}'",
                "read -r ask",
                "printf '%s\\n' \"${'$'}ask\" > '$calls/interrupt-'\"${'$'}n\"",
                "case \"${'$'}ask\" in *interrupt*) echo '$interrupted' ;; esac",
                "else",
                "echo '${init("claude-new")}'",
                "echo '${assistant("claude-new", 50)}'",
                "loop tool move review > /dev/null",
                "echo '$result'",
                "fi",
                "while read -r more; do :; done",
                "",
            ).joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    @Test
    fun `preflight warnings are printed and the run goes on, a preflight error stops it`() {
        val warned = LoopCommand(host { PreflightResult(warnings = listOf("check skipped")) })
        val warning = warned.test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, warning.statusCode, warning.output)
        assertContains(warning.stderr, "loop run: warning: check skipped.")

        val failed = LoopCommand(host { PreflightResult(error = "cannot run here") })
        val error = failed.test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(1, error.statusCode, error.output)
        assertContains(error.stderr, "loop run: cannot run here.")
    }

    @Test
    fun `tracker provider credentials and the Anthropic keys are absent from the agent env, loop tool still works`() {
        val envDump = root.resolve("agent-env.txt")
        val claude = fakeClaude(envDump)
        // `tracker.provider.token` is a credential the adapter would resolve itself: the loader
        // keeps it unexpanded, so `loop tool` reloads WORKFLOW.md without it.
        fixture.workflow.writeText(claudeWorkflow(claude, provider = "token: ${'$'}LOOP_TEST_TRACKER_TOKEN"))
        fixture.issue("t-1")
        val extraEnv =
            mapOf(
                "LOOP_TEST_TRACKER_TOKEN" to "tracker-secret",
                "ANTHROPIC_API_KEY" to "sk-host",
                "ANTHROPIC_AUTH_TOKEN" to "token-host",
                "LOOP_TEST_HARMLESS" to "kept",
            )

        val result = LoopCommand(host(extraEnv) { PreflightResult() }).test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val agentEnv = envDump.readLines()
        assertContains(agentEnv, "LOOP_TEST_HARMLESS=kept")
        listOf("LOOP_TEST_TRACKER_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN").forEach { name ->
            assertTrue(agentEnv.none { it.startsWith("$name=") }, "$name in\n${agentEnv.joinToString("\n")}")
        }
        assertEquals("review", fixture.stateOf("t-1"), result.output)
    }

    @Test
    fun `a non-credential dollar var is absent from the agent env too, and loop tool moves the issue through the snapshot`() {
        val envDump = root.resolve("agent-env.txt")
        val claude = fakeClaude(envDump)
        fixture.workflow.writeText(claudeWorkflow(claude, workspaceRoot = "${'$'}LOOP_TEST_WORKSPACES"))
        fixture.issue("t-1")
        val workspaces = root.resolve("workspaces").toString()

        val result =
            LoopCommand(host(mapOf("LOOP_TEST_WORKSPACES" to workspaces)) { PreflightResult() })
                .test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val agentEnv = envDump.readLines()
        assertTrue(agentEnv.none { it.startsWith("LOOP_TEST_WORKSPACES=") }, agentEnv.joinToString("\n"))
        assertTrue(agentEnv.any { it.startsWith("LOOP_TOOL_CONFIG=") }, agentEnv.joinToString("\n"))
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        assertHostAppliedMove()
    }

    @Test
    fun `a var shared by tracker provider dir and workspace root is stripped, and loop tool still moves the issue`() {
        val envDump = root.resolve("agent-env.txt")
        val claude = fakeClaude(envDump)
        fixture.workflow.writeText(
            claudeWorkflow(
                claude,
                provider = "dir: ${'$'}LOOP_TEST_SHARED",
                workspaceRoot = "${'$'}LOOP_TEST_SHARED",
            ),
        )
        fixture.issue("t-1")

        val result =
            LoopCommand(host(mapOf("LOOP_TEST_SHARED" to fixture.issuesDir.toString())) { PreflightResult() })
                .test(listOf("run", fixture.workflow.toString(), "--once"))

        assertEquals(0, result.statusCode, result.output)
        val agentEnv = envDump.readLines()
        assertTrue(agentEnv.none { it.startsWith("LOOP_TEST_SHARED=") }, agentEnv.joinToString("\n"))
        assertEquals("review", fixture.stateOf("t-1"), result.output)
        assertHostAppliedMove()
    }

    /** The agent queued its move and Loop applied it host side: one `issue_move_requested`, one `issue_moved` by host. */
    private fun assertHostAppliedMove() {
        val kinds = fixture.query("SELECT kind, data FROM events WHERE issue_id = 't-1' ORDER BY seq")
        assertEquals(1, kinds.count { it["kind"] == "issue_move_requested" }, "$kinds")
        val moved = kinds.single { it["kind"] == "issue_moved" }
        assertContains(moved["data"].orEmpty(), "\"host\"")
    }

    /** A claude-code `WORKFLOW.md` with optional `tracker.provider` lines and `workspace.root`. */
    private fun claudeWorkflow(
        claude: Path,
        provider: String? = null,
        workspaceRoot: String = "./workspaces",
    ): String =
        """
        |---
        |tracker:
        |  kind: files
        |  active_states: [todo]
        |  required_labels: [ready-for-agent]
        |${provider?.let { "  provider:\n    $it" }.orEmpty()}
        |workspace:
        |  root: $workspaceRoot
        |runner:
        |  kind: claude-code
        |runners:
        |  claude-code:
        |    command: "$claude"
        |---
        |Work on {{ issue.identifier }}
        |
        """.trimMargin()

    /** A host on the fixture's folders, plus [extraEnv], whose runner preflight is [preflight]. */
    private fun host(
        extraEnv: Map<String, String> = emptyMap(),
        preflight: (WorkflowFile) -> PreflightResult,
    ): Host =
        Host(
            env =
                mapOf(
                    "PATH" to System.getenv("PATH"),
                    "HOME" to fixture.homeDir.toString(),
                    "LOOP_HOME" to fixture.loopHome.toString(),
                    "JAVA_HOME" to System.getProperty("java.home"),
                ) + extraEnv,
            launcherProperty = System.getProperty("loop.launcher"),
            userHome = fixture.homeDir.toString(),
            runnerPreflight = preflight,
        )

    /**
     * A fake `claude` whose assistant lines add up to 10,200,210 tokens, a subagent line not counted, then
     * moves the issue and prints a result with the same real total.
     */
    private fun estimatingClaude(): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-e2e-session"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""

        fun assistant(
            id: String,
            usage: String,
            parent: String? = null,
        ): String =
            """{"type":"assistant","message":{"id":"$id","role":"assistant","content":[],"usage":{$usage}},""" +
                """"parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"},"session_id":"$session"}"""
        val first =
            assistant(
                "msg_1",
                """"input_tokens":100,"output_tokens":80,"cache_read_input_tokens":4000000,"cache_creation_input_tokens":200000""",
            )
        val sub = assistant("msg_sub", """"input_tokens":5,"output_tokens":5,"cache_read_input_tokens":50000000""", parent = "toolu_task")
        val second =
            assistant("msg_2", """"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":6000000,"cache_creation_input_tokens":0""")
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                """"total_cost_usd":2.62,"usage":{"input_tokens":110,"output_tokens":100,""" +
                """"cache_read_input_tokens":10000000,"cache_creation_input_tokens":200000},"permission_denials":[]}"""
        script.writeText(
            listOf("#!/bin/sh", "set -e", "read -r line")
                .plus(listOf(init, first, sub, second).map { "echo '$it'" })
                .plus(listOf("loop tool move review > /dev/null", "echo '$result'", ""))
                .joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /** A fake `claude` that prints one result costing [cost] dollars and never moves the issue. */
    private fun spendingClaude(cost: String): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-e2e-session"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                """"total_cost_usd":$cost,"usage":{"input_tokens":1000,"output_tokens":200,""" +
                """"cache_read_input_tokens":50000,"cache_creation_input_tokens":3000},"permission_denials":[]}"""
        script.writeText(listOf("#!/bin/sh", "set -e", "read -r line", "echo '$init'", "echo '$result'", "").joinToString("\n"))
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /**
     * A fake `claude` that prints one result whose `usage`, the main session's, is 54,200 tokens and whose
     * `model_usage_since_last_result` adds its subagents, 1,004,800 tokens over two models, and never moves the issue.
     */
    private fun subagentClaude(): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-e2e-session"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""
        val opus = """"inputTokens":1000,"outputTokens":200,"cacheReadInputTokens":50000,"cacheCreationInputTokens":3000,"costUSD":2.5"""
        val sonnet = """"inputTokens":500,"outputTokens":100,"cacheReadInputTokens":900000,"cacheCreationInputTokens":50000,"costUSD":1.0"""
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                """"total_cost_usd":3.5,"usage":{"input_tokens":1000,"output_tokens":200,""" +
                """"cache_read_input_tokens":50000,"cache_creation_input_tokens":3000},""" +
                """"model_usage_since_last_result":{"claude-opus-5-5":{$opus},"claude-sonnet-5-5":{$sonnet}},"permission_denials":[]}"""
        script.writeText(listOf("#!/bin/sh", "set -e", "read -r line", "echo '$init'", "echo '$result'", "").joinToString("\n"))
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /**
     * A long lived fake `claude` whose agent starts a background task, prints a result line, is woken by the task's
     * notification and prints a second result line in the same write (the bl-25 shape): 11.69M tokens, then 1.95M,
     * the second cost holding the first. The first result ends turn 1, so the woken segment comes while the process is
     * idle. Turn 2 moves the issue and ends with a result that adds no tokens.
     */
    private fun wakingClaude(): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val session = "claude-e2e-session"
        val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"]}"""

        fun result(
            cost: String,
            usage: String,
        ): String =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                """"total_cost_usd":$cost,"usage":{$usage},"permission_denials":[]}"""
        val first =
            result(
                "8.5",
                """"input_tokens":1000,"output_tokens":40000,"cache_read_input_tokens":11500000,"cache_creation_input_tokens":149000""",
            )
        val second =
            result(
                "9.75",
                """"input_tokens":500,"output_tokens":9500,"cache_read_input_tokens":1900000,"cache_creation_input_tokens":40000""",
            )
        val third =
            result("9.75", """"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0""")
        script.writeText(
            listOf("#!/bin/sh", "set -e", "read -r line", "printf '%s\\n%s\\n%s\\n' '$init' '$first' '$second'")
                .plus(listOf("read -r line", "loop tool move review > /dev/null", "echo '$third'", ""))
                .joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /**
     * A fake `claude` whose spawns write their arguments, working directory and first input line to [calls]. The first
     * reports an occupancy of 220 tokens of the `claude-fake` model and ends its turn, then waits for its stdin to close;
     * the second reports 50, moves the issue to review and ends its turn. Each result line reports its session's usage.
     */
    private fun rotatingClaude(calls: Path): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        calls.createDirectories()

        fun spawn(
            session: String,
            occupancy: Int,
            tokens: Int,
            move: Boolean,
        ): List<String> {
            val init = """{"type":"system","subtype":"init","session_id":"$session","cwd":".","tools":["Bash"],"model":"claude-fake"}"""
            val assistant =
                """{"type":"assistant","message":{"id":"msg-$session","type":"message","role":"assistant",""" +
                    """"content":[{"type":"text","text":"Working."}],"usage":{"input_tokens":$occupancy,"output_tokens":1,""" +
                    """"cache_read_input_tokens":0,"cache_creation_input_tokens":0}},"session_id":"$session"}"""
            val result =
                """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"$session",""" +
                    """"total_cost_usd":0.01,"usage":{"input_tokens":$tokens,"output_tokens":0,""" +
                    """"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"permission_denials":[]}"""
            return listOfNotNull(
                "echo '$init'",
                "echo '$assistant'",
                if (move) "loop tool move review > /dev/null" else null,
                "echo '$result'",
            )
        }
        script.writeText(
            (
                listOf(
                    "#!/bin/sh",
                    "set -e",
                    "n=$(cat '$calls/count' 2>/dev/null || echo 0)",
                    "n=$((n + 1))",
                    "echo \"${'$'}n\" > '$calls/count'",
                    "echo \"${'$'}@\" > '$calls/argv-'\"${'$'}n\"",
                    "pwd > '$calls/cwd-'\"${'$'}n\"",
                    "read -r line",
                    "printf '%s\\n' \"${'$'}line\" > '$calls/prompt-'\"${'$'}n\"",
                    "if [ \"${'$'}n\" = 1 ]; then",
                ) + spawn("claude-old", occupancy = 220, tokens = 1000, move = false) +
                    listOf("else") + spawn("claude-new", occupancy = 50, tokens = 100, move = true) +
                    listOf("fi", "while read -r more; do :; done", "")
            ).joinToString("\n"),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /**
     * A fake `claude`: prints init, moves the issue with `loop tool`, then prints a success
     * result. With [envDump], it first writes its env there, one `NAME=value` per line. With
     * [toolErr], a failed `loop tool` is tolerated and its stderr written there.
     */
    private fun fakeClaude(
        envDump: Path? = null,
        toolErr: Path? = null,
    ): Path {
        val script = root.resolve("claude-bin").createDirectories().resolve("claude")
        val init = """{"type":"system","subtype":"init","session_id":"claude-e2e-session","cwd":".","tools":["Bash"]}"""
        val result =
            """{"type":"result","subtype":"success","is_error":false,"num_turns":1,"session_id":"claude-e2e-session",""" +
                """"total_cost_usd":0.01,"usage":{"input_tokens":1234,"output_tokens":56,""" +
                """"cache_read_input_tokens":0,"cache_creation_input_tokens":0},"permission_denials":[]}"""
        script.writeText(
            """
            |#!/bin/sh
            |set -e
            |read -r line
            |${envDump?.let { "env > '$it'" }.orEmpty()}
            |echo '$init'
            |loop tool move review > /dev/null ${toolErr?.let { "2> '$it' || true" }.orEmpty()}
            |echo '$result'
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }
}
