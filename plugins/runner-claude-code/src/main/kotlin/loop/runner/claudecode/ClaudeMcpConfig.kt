package loop.runner.claudecode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The MCP config the runner passes with `--mcp-config` for each turn (m2-16, story 67): one stdio server, `loop`,
 * which is `<LOOP_LAUNCHER> tool mcp`, Loop's MCP server, so the agent gets `mcp__loop__submit_result`, and since m2-17
 * `mcp__loop__move` and `mcp__loop__comment`.
 *
 * It is secret free by construction. The server reads its settings from the tool config snapshot `LOOP_TOOL_CONFIG`
 * points to, which holds no credential (m1-23), and the config passes only the Loop ids and paths the server reads
 * ([PASSED]), never the host env or `agent.env`.
 */
internal object ClaudeMcpConfig {
    /** The server name, `LoopMcpServer.NAME` in the mcp module. */
    const val SERVER = "loop"

    /** The env names the server reads: the snapshot, the attempt and its issue, the store's folder and the turn's ids. */
    val PASSED: List<String> =
        listOf(
            "LOOP_TOOL_CONFIG",
            "LOOP_ATTEMPT_ID",
            "LOOP_ISSUE_ID",
            "LOOP_ISSUE_IDENTIFIER",
            "LOOP_HOME",
            "LOOP_SESSION_ID",
            "LOOP_TURN_ID",
        )

    private const val LAUNCHER = "LOOP_LAUNCHER"
    private const val TOOL_CONFIG = "LOOP_TOOL_CONFIG"

    /**
     * The config as JSON text for a turn whose Loop variables are [env], or null when it has no tool config snapshot
     * or no launcher: outside an attempt Loop started there is no server to configure.
     */
    fun json(env: Map<String, String>): String? {
        val launcher = env[LAUNCHER]?.takeIf { it.isNotBlank() } ?: return null
        if (env[TOOL_CONFIG].isNullOrBlank()) return null
        val serverEnv = PASSED.mapNotNull { name -> env[name]?.let { name to JsonPrimitive(it) } }.toMap()
        val server =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("stdio"),
                    "command" to JsonPrimitive(launcher),
                    "args" to JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("mcp"))),
                    "env" to JsonObject(serverEnv),
                ),
            )
        return JsonObject(mapOf("mcpServers" to JsonObject(mapOf(SERVER to server)))).toString()
    }
}
