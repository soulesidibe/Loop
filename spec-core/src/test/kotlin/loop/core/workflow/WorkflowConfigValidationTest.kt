package loop.core.workflow

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkflowConfigValidationTest {
    private val folder: Path = createTempDirectory("loop-workflow-validation").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private fun workflow(sections: String): String =
        """
        |---
        |tracker:
        |  kind: files
        |runner:
        |  kind: command
        |runners:
        |  command:
        |    command: ./agent.sh
        |$sections
        |---
        |Prompt
        """.trimMargin()

    private fun write(sections: String): Path = folder.resolve("WORKFLOW.md").also { it.writeText(workflow(sections)) }

    private fun config(sections: String): WorkflowConfig = loader.load(write(sections)).getOrThrow().config

    private fun error(sections: String): Throwable? = loader.load(write(sections)).exceptionOrNull()

    @Test
    fun `quoted integers coerce`() {
        assertEquals(7, config("agent:\n  max_turns: \"7\"").agent.maxTurns)
    }

    @Test
    fun `a zero max turns fails naming the key and the value`() {
        val error = error("agent:\n  max_turns: 0")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("agent.max_turns must be a positive integer, got 0", error.message)
    }

    @Test
    fun `max wake turns defaults to 30 and reads agent max_wake_turns (m4-03)`() {
        assertEquals(30, config("agent:\n  max_turns: 7").agent.maxWakeTurns)
        assertEquals(4, config("agent:\n  max_wake_turns: 4").agent.maxWakeTurns)
    }

    @Test
    fun `a non numeric max turns fails quoting the value`() {
        val error = error("agent:\n  max_turns: abc")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("agent.max_turns must be a positive integer, got \"abc\"", error.message)
    }

    @Test
    fun `a zero polling interval fails naming the value`() {
        assertEquals(
            "polling.interval_ms must be a positive integer, got 0",
            error("polling:\n  interval_ms: 0")?.message,
        )
    }

    @Test
    fun `every positive integer key rejects zero and negatives`() {
        val keys =
            listOf(
                "polling" to "interval_ms",
                "hooks" to "timeout_ms",
                "agent" to "max_concurrent_agents",
                "agent" to "max_turns",
                "agent" to "max_retry_backoff_ms",
                "codex" to "turn_timeout_ms",
                "codex" to "read_timeout_ms",
                "observability" to "retention_days",
            )
        for ((section, key) in keys) {
            for (value in listOf("0", "-5")) {
                assertEquals(
                    "$section.$key must be a positive integer, got $value",
                    error("$section:\n  $key: $value")?.message,
                )
            }
        }
    }

    @Test
    fun `observability retention days takes a positive number of days (m2-18)`() {
        assertEquals(7, config("observability:\n  retention_days: 7").observability.retentionDays)
    }

    @Test
    fun `set values replace the defaults`() {
        val config =
            config(
                """
                |polling:
                |  interval_ms: 1000
                |hooks:
                |  after_create: git clone x .
                |  before_run: make
                |  after_run: make clean
                |  before_remove: rm -rf tmp
                |  timeout_ms: 5000
                |agent:
                |  max_concurrent_agents: 3
                |  max_turns: 4
                |  max_retry_backoff_ms: 6000
                |codex:
                |  turn_timeout_ms: 7000
                |  read_timeout_ms: 8000
                |  stall_timeout_ms: 9000
                """.trimMargin(),
            )

        assertEquals(PollingConfig(intervalMs = 1000), config.polling)
        assertEquals(HooksConfig("git clone x .", "make", "make clean", "rm -rf tmp", 5000), config.hooks)
        assertEquals(AgentConfig(3, 4, 6000), config.agent)
        assertEquals(listOf(7000L, 8000L, 9000L), with(config.codex) { listOf(turnTimeoutMs, readTimeoutMs, stallTimeoutMs) })
    }

    @Test
    fun `retries max attempts accepts a positive integer`() {
        assertEquals(3, config("retries:\n  max_attempts: 3").retries.maxAttempts)
    }

    @Test
    fun `retries max attempts rejects zero and negatives`() {
        for (value in listOf("0", "-1")) {
            val error = error("retries:\n  max_attempts: $value")

            assertIs<WorkflowError.InvalidConfig>(error)
            assertEquals("retries.max_attempts must be a positive integer, got $value", error.message)
        }
    }

    @Test
    fun `retries max attempts rejects a non numeric value`() {
        val error = error("retries:\n  max_attempts: x")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("retries.max_attempts must be a positive integer, got \"x\"", error.message)
    }

    @Test
    fun `retries max attempts rejects a decimal`() {
        val error = error("retries:\n  max_attempts: 1.5")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("retries.max_attempts must be a positive integer, got \"1.5\"", error.message)
    }

    @Test
    fun `retries max continuations accepts a positive integer`() {
        assertEquals(1, config("retries:\n  max_continuations: 1").retries.maxContinuations)
    }

    @Test
    fun `retries max continuations rejects zero and negatives`() {
        for (value in listOf("0", "-1")) {
            val error = error("retries:\n  max_continuations: $value")

            assertIs<WorkflowError.InvalidConfig>(error)
            assertEquals("retries.max_continuations must be a positive integer, got $value", error.message)
        }
    }

    @Test
    fun `retries max continuations rejects a non numeric value`() {
        val error = error("retries:\n  max_continuations: x")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("retries.max_continuations must be a positive integer, got \"x\"", error.message)
    }

    @Test
    fun `retries max continuations rejects a decimal`() {
        val error = error("retries:\n  max_continuations: 1.5")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("retries.max_continuations must be a positive integer, got \"1.5\"", error.message)
    }

    @Test
    fun `retries must be a map`() {
        val error = error("retries: 3")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("retries must be a map", error.message)
    }

    @Test
    fun `a zero or negative stall timeout is valid`() {
        assertEquals(0L, config("codex:\n  stall_timeout_ms: 0").codex.stallTimeoutMs)
        assertEquals(-1L, config("codex:\n  stall_timeout_ms: -1").codex.stallTimeoutMs)
    }

    @Test
    fun `a non numeric stall timeout fails`() {
        assertEquals(
            "codex.stall_timeout_ms must be an integer, got \"soon\"",
            error("codex:\n  stall_timeout_ms: soon")?.message,
        )
    }

    @Test
    fun `per state limits trim and lowercase keys and drop invalid entries`() {
        val config =
            config(
                """
                |agent:
                |  max_concurrent_agents_by_state:
                |    " In_Progress ": 2
                |    Todo: "3"
                |    review: 0
                |    blocked: -1
                |    done: many
                |    "  ": 4
                |    "": 5
                """.trimMargin(),
            )

        assertEquals(mapOf("in_progress" to 2, "todo" to 3), config.agent.maxConcurrentAgentsByState)
    }

    @Test
    fun `codex command is kept verbatim`() {
        val command = config("codex:\n  command: ~/bin/codex --home \$CODEX_HOME app-server").codex.command

        assertEquals("~/bin/codex --home \$CODEX_HOME app-server", command)
    }

    @Test
    fun `codex policies pass through as config values`() {
        val codex =
            config(
                """
                |codex:
                |  approval_policy: never
                |  turn_sandbox_policy:
                |    mode: workspace-write
                |    paths: [a, b]
                """.trimMargin(),
            ).codex

        assertEquals(ConfigValue.Str("never"), codex.approvalPolicy)
        assertEquals(null, codex.threadSandbox)
        assertEquals(
            ConfigValue.MapValue(
                mapOf(
                    "mode" to ConfigValue.Str("workspace-write"),
                    "paths" to ConfigValue.ListValue(listOf(ConfigValue.Str("a"), ConfigValue.Str("b"))),
                ),
            ),
            codex.turnSandboxPolicy,
        )
    }

    @Test
    fun `budgets per attempt tokens accepts a positive integer`() {
        assertEquals(500_000L, config("budgets:\n  per_attempt_tokens: 500000").budgets.perAttemptTokens)
    }

    @Test
    fun `budgets per attempt tokens rejects zero and negatives`() {
        for (value in listOf("0", "-1")) {
            val error = error("budgets:\n  per_attempt_tokens: $value")

            assertIs<WorkflowError.InvalidConfig>(error)
            assertEquals("budgets.per_attempt_tokens must be a positive integer, got $value", error.message)
        }
    }

    @Test
    fun `budgets token threshold every accepts an explicit value`() {
        assertEquals(25_000_000L, config("budgets:\n  token_threshold_every: 25000000").budgets.tokenThresholdEvery)
    }

    @Test
    fun `budgets token threshold every of zero turns thresholds off`() {
        assertEquals(0L, config("budgets:\n  token_threshold_every: 0").budgets.tokenThresholdEvery)
    }

    @Test
    fun `budgets token threshold every rejects negatives and non numeric values`() {
        assertEquals(
            "budgets.token_threshold_every must be a non-negative integer, got -1",
            error("budgets:\n  token_threshold_every: -1")?.message,
        )
        assertEquals(
            "budgets.token_threshold_every must be a non-negative integer, got \"often\"",
            error("budgets:\n  token_threshold_every: often")?.message,
        )
    }

    @Test
    fun `hooks on token threshold is read as a shell command`() {
        assertEquals("notify-send tokens", config("hooks:\n  on_token_threshold: notify-send tokens").hooks.onTokenThreshold)
    }

    @Test
    fun `budgets per attempt tokens rejects a non numeric value`() {
        val error = error("budgets:\n  per_attempt_tokens: abc")

        assertIs<WorkflowError.InvalidConfig>(error)
        assertEquals("budgets.per_attempt_tokens must be a positive integer, got \"abc\"", error.message)
    }

    @Test
    fun `the spend budgets accept positive token counts and positive dollar amounts (m2-20)`() {
        val budgets =
            config(
                "budgets:\n  per_attempt_usd: 10\n  per_issue_tokens: 40000000\n  per_issue_usd: 25.5\n" +
                    "  per_day_tokens: 200000000\n  per_day_usd: 0.75",
            ).budgets

        assertEquals(10.0, budgets.perAttemptUsd)
        assertEquals(40_000_000L, budgets.perIssueTokens)
        assertEquals(25.5, budgets.perIssueUsd)
        assertEquals(200_000_000L, budgets.perDayTokens)
        assertEquals(0.75, budgets.perDayUsd)
    }

    @Test
    fun `a dollar budget rejects zero, negatives and text`() {
        for ((value, shown) in listOf("0" to "0", "-2" to "-2", "0.0" to "0.0", "ten" to "\"ten\"")) {
            val error = error("budgets:\n  per_day_usd: $value")

            assertIs<WorkflowError.InvalidConfig>(error)
            assertEquals("budgets.per_day_usd must be a positive number of dollars, got $shown", error.message)
        }
    }

    @Test
    fun `a token budget rejects zero and text`() {
        for ((value, shown) in listOf("0" to "0", "1.5" to "\"1.5\"")) {
            val error = error("budgets:\n  per_issue_tokens: $value")

            assertIs<WorkflowError.InvalidConfig>(error)
            assertEquals("budgets.per_issue_tokens must be a positive integer, got $shown", error.message)
        }
    }

    @Test
    fun `hooks on budget warning is read like the other hooks`() {
        assertEquals("echo warn", config("hooks:\n  on_budget_warning: echo warn").hooks.onBudgetWarning)
    }
}
