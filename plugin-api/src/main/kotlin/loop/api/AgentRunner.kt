package loop.api

import kotlinx.coroutines.flow.Flow

/**
 * Runs an agent for one attempt. The flow is cold: collecting it starts the
 * agent process, and cancelling the collection kills the process and its children.
 *
 * Every implementation passes `AgentRunnerContract`, the runner neutral contract tests of the
 * `plugin-api` test fixtures (m3-06), which also check that [capabilities] match what it does.
 */
public interface AgentRunner {
    /** What this runner can do, declared before it runs anything (m3-06). */
    public val capabilities: RunnerCapabilities

    public fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent>

    /**
     * Opens a session that runs several turns on one agent thread, or returns null when the
     * runner has no continuation: the attempt then uses [run] for its single turn.
     * It returns a session exactly when [RunnerCapabilities.continuation] is true.
     */
    public fun openSession(ctx: AttemptContext): AgentSession? = null

    /** The model the runner is configured with, recorded in the ledger (m2-19); null when it has none. */
    public val model: String? get() = null
}

/**
 * What an [AgentRunner] declares it can do (plan 6.3, m3-06). The contract tests check each
 * declaration that has a behavior to observe. Nothing in Loop reads them yet; m3-08 and m3-09 do.
 *
 * @property continuation the runner opens an [AgentSession] for turns 2 to n on the same thread;
 *   false means [AgentRunner.openSession] returns null.
 * @property loopTools how Loop's tools (`submit_result`, `move`, `comment`) reach the agent.
 * @property usage the runner emits [RunnerEvent.Usage], absolute totals for the session; false
 *   means it never emits one, and the ledger records null tokens.
 * @property cost [RunnerEvent.Usage.totalCostUsd] is set; false means it is always null. A cost
 *   needs [usage], since it travels on the usage event.
 * @property occupancy the runner emits [RunnerEvent.ContextUsage], the occupancy of the main
 *   session; false means it never emits one, and Loop can enforce only the prompt limit and a
 *   turn cap for it (plan 8.3).
 * @property sandbox the runner confines the agent in an OS sandbox or a container. The deny rules
 *   of generated runtime settings are a guardrail, not a sandbox (plan 11.1), so they do not count.
 */
public data class RunnerCapabilities(
    public val continuation: Boolean,
    public val loopTools: LoopTools,
    public val usage: Boolean,
    public val cost: Boolean,
    public val occupancy: Boolean,
    public val sandbox: Boolean,
) {
    init {
        require(!cost || usage) { "a runner that reports cost must report usage, the cost travels on the usage event" }
    }

    /** How Loop's tools reach the agent (plan 6.3). */
    public enum class LoopTools(
        public val wire: String,
    ) {
        /** Loop's MCP server, `loop tool mcp` (Claude Code, ACP agents). */
        Mcp("mcp"),

        /** Client tools the runner declares to the runtime and serves itself (the Codex app-server). */
        ClientTools("client_tools"),

        /** Files and the command line: `loop tool move`, `loop tool comment` and the result file `.loop/result.json`. */
        Files("files"),
    }
}
