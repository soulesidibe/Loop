package loop.core.workflow

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowConfigDefaultsTest {
    private val folder: Path = createTempDirectory("loop-workflow-defaults").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private val requiredOnly =
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

    private fun load(text: String): WorkflowFile = loader.load(folder.resolve("WORKFLOW.md").also { it.writeText(text) }).getOrThrow()

    @Test
    fun `missing keys take the spec defaults`() {
        val config = load(requiredOnly).config

        assertEquals(
            WorkflowConfig(
                polling = PollingConfig(intervalMs = 30_000L),
                hooks =
                    HooksConfig(
                        afterCreate = null,
                        beforeRun = null,
                        afterRun = null,
                        beforeRemove = null,
                        timeoutMs = 60_000L,
                    ),
                agent =
                    AgentConfig(
                        maxConcurrentAgents = 10,
                        maxTurns = 20,
                        maxRetryBackoffMs = 300_000L,
                        maxConcurrentAgentsByState = emptyMap(),
                    ),
                codex =
                    CodexConfig(
                        command = "codex app-server",
                        approvalPolicy = null,
                        threadSandbox = null,
                        turnSandboxPolicy = null,
                        turnTimeoutMs = 3_600_000L,
                        readTimeoutMs = 5_000L,
                        stallTimeoutMs = 300_000L,
                    ),
                retries = RetriesConfig(maxAttempts = 5),
            ),
            config,
        )
    }

    @Test
    fun `retries max attempts defaults to five`() {
        assertEquals(5, load(requiredOnly).config.retries.maxAttempts)
    }

    @Test
    fun `retries max continuations defaults to three`() {
        assertEquals(3, load(requiredOnly).config.retries.maxContinuations)
    }

    @Test
    fun `budgets per attempt tokens has no default, so there is no ceiling`() {
        assertEquals(null, load(requiredOnly).config.budgets.perAttemptTokens)
    }

    @Test
    fun `no spend budget has a default (m2-20)`() {
        val budgets = load(requiredOnly).config.budgets
        assertEquals(
            listOf(null, null, null, null, null),
            listOf(budgets.perAttemptUsd, budgets.perIssueTokens, budgets.perIssueUsd, budgets.perDayTokens, budgets.perDayUsd),
        )
    }

    @Test
    fun `budgets token threshold every defaults to ten million`() {
        assertEquals(10_000_000L, load(requiredOnly).config.budgets.tokenThresholdEvery)
    }

    @Test
    fun `hooks on token threshold has no default`() {
        assertEquals(null, load(requiredOnly).config.hooks.onTokenThreshold)
    }

    @Test
    fun `observability retention days defaults to 90 (m2-18)`() {
        assertEquals(90, load(requiredOnly).config.observability.retentionDays)
    }
}
