package loop.runner.claudecode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import loop.api.LedgerTokens
import loop.api.RiskReview
import loop.api.RiskReviewError
import loop.api.RiskReviewRequest
import loop.api.RiskReviewer
import loop.runner.process.agentEnvironment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/**
 * The merge risk reviewer of Claude Code (m2-28): one headless, read only invocation, `<command> -p --output-format json
 * --model <reviewer> --permission-mode plan --allowedTools Read,Grep,Glob --disallowedTools <writers> --settings <file>`
 * in the workspace, the prompt on stdin. Plan mode and the tool lists keep it from writing; the generated settings deny
 * reads under the holdout folder, like an agent's. It is isolated from the workspace it judges: `--setting-sources user`
 * loads no project or local settings, `--strict-mcp-config` with an empty `--mcp-config` starts no MCP server,
 * `--disable-slash-commands` loads no skill, and the env turns off CLAUDE.md and the auto memory. Its env is the agent
 * env policy, [hostEnv] minus [removedEnvNames], always without the Anthropic keys. The answer is the `result` text of
 * the JSON line Claude Code prints, with its tokens, read like the runner's (bl-71), and `total_cost_usd`. A run past
 * the timeout is stopped, with what it started, and is a [RiskReviewError.Timeout]; a cancelled review stops it too; a
 * run that cannot start, exits in error or prints no result is a [RiskReviewError.Crashed].
 */
class ClaudeCodeRiskReviewer(
    private val command: String,
    private val hostEnv: Map<String, String> = System.getenv(),
    private val removedEnvNames: Set<String> = emptySet(),
    private val holdoutDir: Path? = null,
) : RiskReviewer {
    override suspend fun review(request: RiskReviewRequest): Result<RiskReview> {
        val settingsDir = createTempDirectory(SETTINGS_DIR_PREFIX)
        try {
            val settings = settingsDir.resolve("settings.json")
            settings.writeText(ClaudeSettingsFile.json(holdoutDir?.let { ClaudeSettingsFile.pathsOf(it) }.orEmpty()))
            val mcp = settingsDir.resolve("mcp.json")
            mcp.writeText(EMPTY_MCP_CONFIG)
            val argv =
                listOf(command, "-p", "--output-format", "json", "--model", request.model, "--permission-mode", "plan") +
                    listOf("--allowedTools", READ_TOOLS, "--disallowedTools", WRITE_TOOLS, "--settings", settings.toString()) +
                    listOf("--setting-sources", "user", "--strict-mcp-config", "--mcp-config", mcp.toString(), "--disable-slash-commands")
            val process =
                try {
                    ProcessBuilder(argv)
                        .directory(request.workspace.toFile())
                        .apply {
                            environment().clear()
                            environment().putAll(agentEnvironment(removed = removedEnvNames, host = hostEnv))
                            environment().putAll(ISOLATION_ENV)
                        }.start()
                } catch (e: Exception) {
                    return Result.failure(RiskReviewError.Crashed("cannot start $command: ${e.message}", e))
                }
            try {
                return answer(process, request)
            } finally {
                // A timeout, a cancellation or an error: nothing the reviewer started outlives the review.
                if (process.isAlive) stop(process)
            }
        } finally {
            settingsDir.toFile().deleteRecursively()
        }
    }

    /**
     * The answer of [process]: the prompt is written on a thread of its own and the exit is awaited without blocking, so
     * a reviewer that never reads its prompt cannot hold the timeout or a cancellation (send back 1 of PR #75).
     */
    private suspend fun answer(
        process: Process,
        request: RiskReviewRequest,
    ): Result<RiskReview> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val threads =
            listOf(
                Thread { process.inputStream.use { copyCapped(it, out) } },
                Thread { process.errorStream.use { it.copyTo(err) } },
                Thread {
                    try {
                        process.outputStream.use { it.write(request.prompt.toByteArray(Charsets.UTF_8)) }
                    } catch (_: Exception) {
                        // A reviewer that exits or is stopped before reading the prompt is judged by its exit and output.
                    }
                },
            ).onEach {
                it.isDaemon = true
                it.start()
            }
        withTimeoutOrNull(request.timeoutMs) { process.onExit().await() }
            ?: return Result.failure(RiskReviewError.Timeout("no answer within ${request.timeoutMs} ms"))
        // One wait for both readers, so a child that keeps stdout and stderr open delays the answer once (bl-96).
        val deadline = System.nanoTime() + JOIN_MS * 1_000_000
        withContext(Dispatchers.IO) {
            threads.take(2).forEach { it.join(maxOf(1, (deadline - System.nanoTime()) / 1_000_000)) }
        }
        val stdout = out.toString(Charsets.UTF_8)
        if (out.size() > STDOUT_MAX) return Result.failure(RiskReviewError.Crashed("$command printed more than $STDOUT_MAX bytes"))
        if (process.exitValue() != 0) {
            val detail = err.toString(Charsets.UTF_8).lines().firstOrNull { it.isNotBlank() } ?: stdout.lines().firstOrNull().orEmpty()
            return Result.failure(
                RiskReviewError.Crashed("$command exited with code ${process.exitValue()}: ${detail.take(DETAIL_MAX)}"),
            )
        }
        return parse(stdout)
    }

    /**
     * Copies [input] into [out] up to one byte past [STDOUT_MAX], then reads and drops the rest, so a reviewer that prints
     * without end never sits in memory whole (bl-96) and the size check still sees it went over.
     */
    private fun copyCapped(
        input: InputStream,
        out: ByteArrayOutputStream,
    ) {
        val buffer = ByteArray(READ_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val room = STDOUT_MAX + 1 - out.size()
            if (room > 0) out.write(buffer, 0, minOf(read, room))
        }
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** The answer of the JSON line [stdout] holds: its `result`, usage and cost; an error result or no result fails. */
    private fun parse(stdout: String): Result<RiskReview> {
        val line =
            stdout.lines().lastOrNull { it.trimStart().startsWith("{") }
                ?: return Result.failure(RiskReviewError.Crashed("$command printed no result"))
        val json =
            runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull()
                ?: return Result.failure(RiskReviewError.Crashed("$command printed a result that is not JSON"))
        if ((json["is_error"] as? JsonPrimitive)?.booleanOrNull == true) {
            val detail = (json["result"] as? JsonPrimitive)?.content.orEmpty()
            return Result.failure(RiskReviewError.Crashed("$command ended in error: ${detail.take(DETAIL_MAX)}"))
        }
        val text =
            (json["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Result.failure(RiskReviewError.Crashed("$command printed no result text"))
        // The same tokens the runner records, every model's (bl-71).
        val usage = StreamJsonMapper.usageOf(json)
        val tokens = usage?.let { LedgerTokens(it.inputTokens, it.outputTokens, it.cacheReadInputTokens, it.cacheCreationInputTokens) }
        val cost = (json["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull
        return Result.success(RiskReview(text, tokens, cost))
    }

    override fun toString(): String = "ClaudeCodeRiskReviewer(command=$command)"

    private companion object {
        const val SETTINGS_DIR_PREFIX = "loop-risk-reviewer-"
        const val READ_TOOLS = "Read,Grep,Glob"
        const val WRITE_TOOLS = "Bash,Edit,Write,MultiEdit,NotebookEdit,WebFetch,WebSearch,Task"
        const val DETAIL_MAX = 300

        /** The bytes of stdout kept: the answer is one JSON line far below it. */
        const val STDOUT_MAX = 1_048_576

        /** The bytes read from stdout at a time. */
        const val READ_BYTES = 8_192
        const val EMPTY_MCP_CONFIG = "{\"mcpServers\":{}}"

        /** No CLAUDE.md or other memory file of the workspace, the user or the auto memory reaches the reviewer. */
        val ISOLATION_ENV = mapOf("CLAUDE_CODE_DISABLE_CLAUDE_MDS" to "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1")
        const val JOIN_MS = 5_000L
    }
}
