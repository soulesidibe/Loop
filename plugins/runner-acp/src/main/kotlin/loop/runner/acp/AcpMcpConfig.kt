package loop.runner.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the `mcpServers` array passed to ACP `session/new` and `session/load` for Loop's MCP
 * server (`<LOOP_LAUNCHER> tool mcp`).
 *
 * Secret free by construction: forwards only the Loop variables in [PASSED] (matching
 * `ClaudeMcpConfig.PASSED`), never the host environment or `agent.env`.
 */
internal object AcpMcpConfig {
    /** The server name in ACP `mcpServers`. */
    const val SERVER: String = "loop"

    /** The 8 env vars passed to `loop tool mcp`, matching `ClaudeMcpConfig.PASSED`. */
    val PASSED: List<String> =
        listOf(
            "LOOP_TOOL_CONFIG",
            "LOOP_ATTEMPT_ID",
            "LOOP_ISSUE_ID",
            "LOOP_ISSUE_IDENTIFIER",
            "LOOP_TRACKER_ID",
            "LOOP_HOME",
            "LOOP_SESSION_ID",
            "LOOP_TURN_ID",
        )

    private const val LAUNCHER = "LOOP_LAUNCHER"
    private const val TOOL_CONFIG = "LOOP_TOOL_CONFIG"

    /**
     * Returns a single element `mcpServers` array with Loop's stdio MCP server when [env] carries
     * both `LOOP_LAUNCHER` and `LOOP_TOOL_CONFIG`, or an empty array outside a Loop attempt.
     */
    fun mcpServers(env: Map<String, String>): JsonArray {
        val launcher = env[LAUNCHER]?.takeIf { it.isNotBlank() } ?: return JsonArray(emptyList())
        if (env[TOOL_CONFIG].isNullOrBlank()) return JsonArray(emptyList())
        val serverEnv =
            buildJsonArray {
                for (name in PASSED) {
                    val value = env[name] ?: continue
                    add(
                        buildJsonObject {
                            put("name", name)
                            put("value", value)
                        },
                    )
                }
            }
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("name", SERVER)
                    put("command", launcher)
                    put(
                        "args",
                        buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("tool"))
                            add(kotlinx.serialization.json.JsonPrimitive("mcp"))
                        },
                    )
                    put("env", serverEnv)
                },
            )
        }
    }
}
