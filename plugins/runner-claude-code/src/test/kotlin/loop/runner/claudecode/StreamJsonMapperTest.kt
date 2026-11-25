package loop.runner.claudecode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The mapping of recorded `--output-format stream-json --verbose` transcripts. */
class StreamJsonMapperTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the init line carries Claude's session id`() {
        val init = assertIs<StreamLine.Init>(lines("success").first())

        assertEquals("claude-session-1", init.runtimeSessionId)
    }

    @Test
    fun `m3-08 the init line carries the session's model, null when it names none`() {
        assertEquals("claude-haiku-5-5", assertIs<StreamLine.Init>(lines("real_two_turns_one_process").first()).model)
        val bare = StreamJsonMapper.parse("""{"type":"system","subtype":"init","session_id":"s"}""")
        assertEquals(null, assertIs<StreamLine.Init>(bare).model)
    }

    @Test
    fun `bl-209 the recorded rate limit line carries its status and its unified windows`() {
        val line = lines("real_two_turns_one_process").filterIsInstance<StreamLine.RateLimit>().single()

        assertEquals("allowed", line.status)
        assertEquals(mapOf("seven_day" to StreamLine.RateLimit.Window(utilization = 0.74, resetsAt = 1794906000)), line.windows)
    }

    @Test
    fun `bl-209 a rate limit line without its info or with odd windows keeps what it can read`() {
        val bare = assertIs<StreamLine.RateLimit>(StreamJsonMapper.parse("""{"type":"rate_limit_event"}"""))
        assertEquals(StreamLine.RateLimit(status = null, windows = emptyMap()), bare)
        val odd =
            StreamJsonMapper.parse(
                """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","unifiedWindows":""" +
                    """{"five_hour":{"utilization":1,"resetsAt":1794906000},"seven_day":{"resetsAt":1795510800},"x":"y"}}}""",
            )
        assertEquals(
            StreamLine.RateLimit(
                status = "rejected",
                windows =
                    mapOf(
                        "five_hour" to StreamLine.RateLimit.Window(utilization = 1.0, resetsAt = 1794906000),
                        "seven_day" to StreamLine.RateLimit.Window(utilization = null, resetsAt = 1795510800),
                    ),
            ),
            odd,
        )
    }

    @Test
    fun `a success result completes the turn with the exit code`() {
        val events = turnEvents("success")

        assertEquals(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0), events.last())
    }

    @Test
    fun `an error result fails the turn with category turn_failed and the subtype`() {
        mapOf(
            "error_max_turns" to "turn_failed: error_max_turns",
            "error_during_execution" to "turn_failed: error_during_execution",
        ).forEach { (fixture, reason) ->
            val failed = assertIs<RunnerEvent.TurnFailed>(turnEvents(fixture).last(), fixture)

            assertEquals(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 0, reason = reason), failed)
        }
    }

    @Test
    fun `usage comes before the terminal event with the result totals, not a sum of the messages`() {
        // The assistant messages of the fixture report 30 input and 12 output tokens in all.
        val expected =
            RunnerEvent.Usage(
                SESSION,
                TURN,
                inputTokens = 1234,
                outputTokens = 567,
                cacheReadInputTokens = 89,
                cacheCreationInputTokens = 12,
                totalCostUsd = 0.0421,
            )

        assertEquals(listOf(expected, RunnerEvent.TurnCompleted(SESSION, TURN, 0)), turnEvents("success"))
        assertIs<RunnerEvent.Usage>(turnEvents("error_max_turns").first())
    }

    @Test
    fun `a malformed line mid stream does not fail the turn`() {
        val parsed = lines("malformed_line")

        assertEquals(StreamLine.Malformed, parsed[2])
        assertIs<RunnerEvent.TurnCompleted>(turnEvents("malformed_line").last())
    }

    @Test
    fun `a denial of a tool outside the deny rules ends the turn with input required naming the tool`() {
        val required = assertIs<RunnerEvent.TurnInputRequired>(turnEvents("input_required").last())

        assertEquals(RunnerEvent.TurnInputRequired(SESSION, TURN, reason = "WebFetch"), required)
    }

    @Test
    fun `a denied deny-listed git push alone is not a failure`() {
        val result = lines("denied_git_push").filterIsInstance<StreamLine.Result>().single()

        assertEquals(listOf("git push origin main"), result.permissionDenials.map { it.command })
        assertIs<RunnerEvent.TurnCompleted>(turnEvents("denied_git_push").last())
    }

    @Test
    fun `an error result whose only denials are deny-listed commands fails with turn_failed, not input required`() {
        val result =
            StreamLine.Result(
                subtype = "error_during_execution",
                isError = true,
                usage = null,
                permissionDenials = listOf(StreamLine.Denial("Bash", "gh pr create")),
            )

        val events = StreamJsonMapper.turnEvents(result, SESSION, TURN, exitCode = 1)

        assertEquals(listOf(RunnerEvent.TurnFailed(SESSION, TURN, 1, "turn_failed: error_during_execution")), events)
    }

    @Test
    fun `a denied loop approve or loop resume is a policy denial, by the bare name or a launcher path (m2-24)`() {
        val commands = listOf("loop approve t-1", "loop resume t-1", "/opt/loop/bin/loop approve t-1", "/opt/loop/bin/loop resume t-1")

        commands.forEach { assertTrue(StreamLine.Denial("Bash", it).isDenyListed, it) }
        assertFalse(StreamLine.Denial("Bash", "/opt/loop/bin/loop tool move review").isDenyListed)
        assertFalse(StreamLine.Denial("Bash", "/opt/loopy approve t-1").isDenyListed)
    }

    @Test
    fun `a denied heredoc whose body holds git push in backticks is a policy denial, so the turn completes (bl-33)`() {
        // The m2-14 attempt 1 denial: Claude Code matched Bash(git push:*) on the text of the heredoc body.
        val command =
            "cat > plugins/runner-claude-code/src/main/kotlin/loop/runner/claudecode/ClaudeSettingsFile.kt <<'EOF'\n" +
                "/**\n * Its `permissions.deny` prefix rules block `git push`, `gh` and the history rewrites.\n */\n" +
                "internal object ClaudeSettingsFile\nEOF"
        val line =
            """{"type":"result","subtype":"success","is_error":false,"permission_denials":[""" +
                """{"tool_name":"Bash","tool_input":{"command":${JsonPrimitive(command)}}}]}"""
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))

        assertEquals(listOf(command), result.permissionDenials.map { it.command })
        assertEquals(listOf(RunnerEvent.TurnCompleted(SESSION, TURN, 0)), StreamJsonMapper.turnEvents(result, SESSION, TURN, 0))
    }

    @Test
    fun `a denied python3 script quoting loop approve is a policy denial (bl-33)`() {
        // The m2-24 attempt 3 denial: a python3 - script that writes a test quoting the operator command.
        val command =
            "python3 - <<'PY'\nfrom pathlib import Path\np = Path('docs/spec-decisions.md')\n" +
                "p.write_text(p.read_text().replace('approve it', 'run \"loop approve t-1\" to approve it'))\nPY"

        assertTrue(StreamLine.Denial("Bash", command).isDenyListed)
        assertTrue(isPolicy(HiddenFolders.NONE, StreamLine.Denial("Bash", command)))
    }

    @Test
    fun `a denied command holding a deny-listed command after an operator or in quotes is a policy denial (bl-33)`() {
        val commands =
            listOf(
                "./gradlew build && git push origin loop/bl-33",
                "git status; gh pr create --fill",
                "cd /tmp || git reset --hard HEAD~1",
                "echo done | git commit --amend -F -",
                "sh -c \"gh pr view\"",
                "echo 'git rebase main'",
                "x=\$(gh api user)",
                "git log \\\n  && git push",
                "python3 -c 'import subprocess; subprocess.run([\"loop\", \"approve\", \"t-1\"])'",
                "printf '%s' \"/opt/loop/bin/loop resume t-1\"",
            )

        commands.forEach { assertTrue(StreamLine.Denial("Bash", it).isDenyListed, it) }
    }

    @Test
    fun `a denied command that only holds parts of a deny-listed command still needs input (bl-33)`() {
        val commands =
            listOf(
                "git status && git log",
                "echo github pushed",
                "git pushd",
                "/opt/loop/bin/loop tool move review",
                "loop tool comment 'the operator approves'",
                "/opt/loopy approve t-1",
                "approve loop",
            )

        commands.forEach { assertFalse(StreamLine.Denial("Bash", it).isDenyListed, it) }
        assertFalse(StreamLine.Denial("Read", "git push").isDenyListed, "only a Bash denial is matched on its command")
    }

    @Test
    fun `a denied read or edit under a hidden folder is a policy denial, not a request for input (m2-14)`() {
        val hidden = HiddenFolders(listOf(Path.of("/home/u/.loop/holdout")), cwd = null, home = null)
        val line =
            """{"type":"result","subtype":"success","is_error":false,"permission_denials":[""" +
                """{"tool_name":"Read","tool_input":{"file_path":"/home/u/.loop/holdout/acceptance.sh"}},""" +
                """{"tool_name":"Grep","tool_input":{"pattern":"x","path":"/home/u/.loop/holdout"}}]}"""
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))

        assertEquals(listOf("/home/u/.loop/holdout/acceptance.sh", "/home/u/.loop/holdout"), result.permissionDenials.map { it.path })
        assertIs<RunnerEvent.TurnCompleted>(StreamJsonMapper.turnEvents(result, SESSION, TURN, 0, hidden).last())
        val elsewhere = result.copy(permissionDenials = listOf(StreamLine.Denial("Read", null, path = "/home/u/.loop/holdout-x/a.sh")))
        assertIs<RunnerEvent.TurnInputRequired>(StreamJsonMapper.turnEvents(elsewhere, SESSION, TURN, 0, hidden).last())
    }

    /** The holdout folder and a workspace beside it, both real folders under [tmp]. */
    private fun holdoutAndWorkspace(): Pair<Path, Path> {
        val real = tmp.toRealPath()
        return real.resolve("loop-home/holdout").createDirectories() to real.resolve("ws").createDirectories()
    }

    /** True when the [denials] alone end the turn as a policy denial under [hidden], not as a request for input. */
    private fun isPolicy(
        hidden: HiddenFolders,
        vararg denials: StreamLine.Denial,
    ): Boolean {
        val result = StreamLine.Result("success", isError = false, usage = null, permissionDenials = denials.toList())
        return StreamJsonMapper.turnEvents(result, SESSION, TURN, 0, hidden).last() is RunnerEvent.TurnCompleted
    }

    @Test
    fun `a denied read through a workspace symlink to the hidden folder is a policy denial (m2-14)`() {
        val (holdout, ws) = holdoutAndWorkspace()
        Files.createSymbolicLink(ws.resolve("link"), holdout)
        val hidden = HiddenFolders(listOf(holdout), cwd = ws, home = null)

        assertTrue(isPolicy(hidden, StreamLine.Denial("Read", null, path = "$ws/link/acceptance.sh")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Read", null, path = "link/acceptance.sh")))
    }

    @Test
    fun `a denied grep or glob with a relative path to the hidden folder is a policy denial (m2-14)`() {
        val (holdout, ws) = holdoutAndWorkspace()
        val hidden = HiddenFolders(listOf(holdout), cwd = ws, home = null)

        assertTrue(isPolicy(hidden, StreamLine.Denial("Grep", null, path = "../loop-home/holdout", pattern = "answer")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Glob", null, path = "../loop-home/holdout/", pattern = "**/*.sh")))
        assertFalse(isPolicy(hidden, StreamLine.Denial("Grep", null, path = "src", pattern = "answer")))
    }

    @Test
    fun `a denied glob whose folder is only in its pattern is a policy denial (m2-14)`() {
        val (holdout, ws) = holdoutAndWorkspace()
        val hidden = HiddenFolders(listOf(holdout), cwd = ws, home = null)
        val line =
            """{"type":"result","subtype":"success","is_error":false,"permission_denials":[""" +
                """{"tool_name":"Glob","tool_input":{"pattern":"$holdout/**/*.sh"}}]}"""
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))

        assertEquals(listOf(StreamLine.Denial("Glob", null, path = null, pattern = "$holdout/**/*.sh")), result.permissionDenials)
        assertIs<RunnerEvent.TurnCompleted>(StreamJsonMapper.turnEvents(result, SESSION, TURN, 0, hidden).last())
        assertTrue(isPolicy(hidden, StreamLine.Denial("Glob", null, pattern = "../loop-home/holdout/*")))
        assertFalse(isPolicy(hidden, StreamLine.Denial("Glob", null, pattern = "**/*.kt")))
    }

    @Test
    fun `a denied read of the hidden folder in another case is a policy denial, as macOS paths ignore case (m2-14)`() {
        val hidden = HiddenFolders(listOf(Path.of("/Users/u/.loop/holdout")), cwd = null, home = null)

        assertTrue(isPolicy(hidden, StreamLine.Denial("Read", null, path = "/users/U/.loop/HOLDOUT/acceptance.sh")))
    }

    @Test
    fun `a denied bash command that names the hidden folder is a policy denial (m2-14)`() {
        val (holdout, ws) = holdoutAndWorkspace()
        val home = holdout.parent.parent
        val hidden = HiddenFolders(listOf(holdout), cwd = ws, home = home)

        assertTrue(isPolicy(hidden, StreamLine.Denial("Bash", "cat $holdout/acceptance.sh")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Bash", "cat \"$holdout/a b.sh\"")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Bash", "ls ../loop-home/holdout | head")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Bash", "cat ~/loop-home/holdout/acceptance.sh")))
        assertTrue(isPolicy(hidden, StreamLine.Denial("Bash", "cat \$HOME/loop-home/holdout/acceptance.sh")))
        assertFalse(isPolicy(hidden, StreamLine.Denial("Bash", "ls /tmp")))
    }

    @Test
    fun `an error result with a success subtype, such as an auth failure, fails with the first line of its text`() {
        val failed = assertIs<RunnerEvent.TurnFailed>(turnEvents("auth_failure").last())

        assertEquals("turn_failed: Invalid API key · Please run /login", failed.reason)
    }

    // bl-39: runner access failures, recognized on error results only.

    @Test
    fun `an error result with API Error 401 is runner_unavailable`() {
        assertUnavailable("unavailable_401", "turn_failed: API Error: 401")
    }

    @Test
    fun `an error result with API Error 403, the Cloudflare block of run c519fe87, is runner_unavailable`() {
        assertUnavailable("unavailable_403", "turn_failed: Failed to authenticate. API Error: 403 Attention Required! | Cloudflare")
    }

    @Test
    fun `an error result for an invalid API key is runner_unavailable`() {
        assertUnavailable("auth_failure", "turn_failed: Invalid API key · Please run /login")
    }

    @Test
    fun `an error result for an expired login is runner_unavailable`() {
        assertUnavailable("unavailable_login_expired", "turn_failed: OAuth token has expired.")
    }

    @Test
    fun `an error result with API Error 429 is runner_unavailable`() {
        assertUnavailable("unavailable_429", "turn_failed: API Error: 429")
    }

    @Test
    fun `an error result for repeated overloaded errors is runner_unavailable`() {
        assertUnavailable("unavailable_overloaded", "turn_failed: API Error: Repeated 529 Overloaded errors")
    }

    @Test
    fun `a retry-after in the error result sets retryAfter, else it stays null`() {
        val failed = assertUnavailable("unavailable_retry_after", "turn_failed: API Error: 429")

        assertEquals(300.seconds, failed.retryAfter)
        assertNull(assertUnavailable("unavailable_429", "turn_failed: API Error: 429").retryAfter)
    }

    @Test
    fun `the same texts in the agent's final answer, not an error result, complete the turn`() {
        assertEquals(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0), turnEvents("agent_quotes_auth_errors").last())
    }

    @Test
    fun `the same texts in a tool output before an ordinary error result leave the category null`() {
        val failed = assertIs<RunnerEvent.TurnFailed>(turnEvents("tool_output_quotes_auth_errors").last())

        assertEquals(RunnerEvent.TurnFailed(SESSION, TURN, exitCode = 0, reason = "turn_failed: error_during_execution"), failed)
        assertNull(failed.category)
    }

    @Test
    fun `an ordinary error result has no category`() {
        listOf("error_max_turns", "error_during_execution").forEach { fixture ->
            val failed = assertIs<RunnerEvent.TurnFailed>(turnEvents(fixture).last(), fixture)

            assertNull(failed.category, fixture)
            assertNull(failed.retryAfter, fixture)
        }
    }

    // m4-08, bl-123: a lost network is runner_unavailable too.

    @Test
    fun `the recorded ENOTFOUND result of bl-123 is runner_unavailable`() {
        val failed = assertUnavailable("network_enotfound", "turn_failed: API Error: Can't reach the API server")

        assertTrue(failed.reason.endsWith("check your internet or DNS (ENOTFOUND)"), failed.reason)
        assertNull(failed.retryAfter)
        assertEquals("connection_failed", assertIs<StreamLine.Result>(lines("network_enotfound").last()).apiError)
    }

    @Test
    fun `the recorded failure after the idle sleep of bl-156 is runner_unavailable, so it takes no attempt`() {
        // Attempt 10c2f759, turn 6: the Mac slept at 10:10Z and the turn failed at 10:21Z, its last recorded line.
        val result = assertIs<StreamLine.Result>(lines("wake_turns_10c2f759").last())

        val failed = assertIs<RunnerEvent.TurnFailed>(StreamJsonMapper.turnEvents(result, SESSION, TURN, null).last())

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
        assertTrue(failed.reason.endsWith("(ENOTFOUND)"), failed.reason)
    }

    @Test
    fun `each network error code and text in an error result is runner_unavailable`() {
        listOf(
            "API Error: Can't reach the API server, check your internet or DNS (ENOTFOUND)",
            "API Error: Can’t reach the API server",
            "API Error: connect ECONNREFUSED 160.79.104.10:443",
            "API Error: connect ETIMEDOUT 160.79.104.10:443",
            "API Error: read ECONNRESET",
            "API Error: getaddrinfo EAI_AGAIN api.anthropic.com",
            "API Error: Connection error.",
        ).forEach { text ->
            val failed = errorResult(text)

            assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category, text)
        }
    }

    @Test
    fun `an error result with api_error connection_failed is runner_unavailable whatever its text`() {
        val line = """{"type":"result","subtype":"success","is_error":true,"api_error":"connection_failed","result":"API Error"}"""
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))

        val failed = assertIs<RunnerEvent.TurnFailed>(StreamJsonMapper.turnEvents(result, SESSION, TURN, null).last())

        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category)
    }

    @Test
    fun `network words outside an error result, or in an ordinary error, leave the turn as it was`() {
        val answer = """{"type":"result","subtype":"success","is_error":false,"result":"The test covers ENOTFOUND and ECONNRESET."}"""
        val completed = StreamJsonMapper.turnEvents(assertIs(StreamJsonMapper.parse(answer)), SESSION, TURN, null).last()
        assertEquals(RunnerEvent.TurnCompleted(SESSION, TURN, exitCode = 0), completed)

        assertNull(errorResult("Tests failed: expected ECONNREFUSED", isError = false, subtype = "error_max_turns").category)
        assertNull(errorResult("The build failed").category)
    }

    /** The terminal event of a `result` line with [text], an error with a success subtype by default. */
    private fun errorResult(
        text: String,
        isError: Boolean = true,
        subtype: String = "success",
    ): RunnerEvent.TurnFailed {
        val line =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("result"),
                    "subtype" to JsonPrimitive(subtype),
                    "is_error" to JsonPrimitive(isError),
                    "result" to JsonPrimitive(text),
                ),
            ).toString()
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))
        return assertIs<RunnerEvent.TurnFailed>(StreamJsonMapper.turnEvents(result, SESSION, TURN, null).last(), text)
    }

    /** The fixture's turn fails as `runner_unavailable` with a reason starting with [reasonStart]. */
    private fun assertUnavailable(
        fixture: String,
        reasonStart: String,
    ): RunnerEvent.TurnFailed {
        val failed = assertIs<RunnerEvent.TurnFailed>(turnEvents(fixture).last(), fixture)
        assertEquals(RunnerEvent.TurnFailed.Category.RunnerUnavailable, failed.category, fixture)
        assertTrue(failed.reason.startsWith(reasonStart), failed.reason)
        return failed
    }

    @Test
    fun `the result text in a failure reason is its first line, capped at 200 characters`() {
        val long = "x".repeat(300)
        val line = """{"type":"result","subtype":"success","is_error":true,"result":"$long\nsecond line"}"""
        val result = assertIs<StreamLine.Result>(StreamJsonMapper.parse(line))

        val failed = assertIs<RunnerEvent.TurnFailed>(StreamJsonMapper.turnEvents(result, SESSION, TURN, 1).last())

        assertEquals("turn_failed: ${"x".repeat(200)}", failed.reason)
    }

    @Test
    fun `a real Claude Code transcript replays line by line (m2-18)`() {
        val parsed = lines(REAL)

        assertEquals(
            listOf(
                StreamLine.Init("claude-session-real-1", model = "claude-haiku-5-5"),
                StreamLine.Assistant("msg_fake_0001", parentToolUseId = null, usage = assistantUsage(8450, 24365, 16)),
                StreamLine.Other,
                StreamLine.Assistant("msg_fake_0001", parentToolUseId = null, usage = assistantUsage(8450, 24365, 16)),
                StreamLine.Other,
                StreamLine.Other,
                StreamLine.Assistant("msg_fake_0002", parentToolUseId = null, usage = assistantUsage(1869, 32815, 3)),
                StreamLine.Other,
            ),
            parsed.take(8),
        )
        val result = assertIs<StreamLine.Result>(parsed[8])
        assertEquals("success", result.subtype)
        assertEquals(false, result.isError)
        assertEquals("done", result.text)
        assertEquals("claude-session-real-1", result.sessionId)
        assertEquals(emptyList(), result.permissionDenials)
        assertEquals(StreamLine.Other, parsed[9], "a system line after the result")
        assertEquals(10, parsed.size)
    }

    @Test
    fun `the real transcript's result gives its absolute totals, then completes the turn`() {
        val usage =
            RunnerEvent.Usage(
                SESSION,
                TURN,
                inputTokens = 4,
                outputTokens = 223,
                cacheReadInputTokens = 57180,
                cacheCreationInputTokens = 10319,
                totalCostUsd = 0.0027475,
            )

        assertEquals(listOf(usage, RunnerEvent.TurnCompleted(SESSION, TURN, 0)), turnEvents(REAL))
    }

    // real_subagent and real_subagent_resumed (bl-71) are scrubbed like REAL, the task id and its output file too: a real
    // haiku turn of Claude Code 2.1.319 that runs one general-purpose subagent answering `pong`, then a `--resume` of
    // that session answering `ok`.

    @Test
    fun `bl-71 a result's tokens include its subagents, from model_usage_since_last_result`() {
        // The real recording of one turn that ran one subagent: its result's usage holds the main session only
        // (4, 308, 41,393, 18,845); model_usage_since_last_result adds the subagent, and total_cost_usd prices it.
        val result = lines("real_subagent").filterIsInstance<StreamLine.Result>().single()

        val usage = checkNotNull(result.usage)
        assertEquals(listOf(1226L, 331L, 41393L, 38365L), usage.tokens())
        assertEquals(0.006911029999999999, usage.totalCostUsd)
    }

    @Test
    fun `bl-71 a resumed process's result counts its own segment, not the session's restored modelUsage`() {
        // The real --resume of that session: modelUsage is the session's (1228, 335, 71,845, 38,405), the segment is new work only.
        val result = lines("real_subagent_resumed").filterIsInstance<StreamLine.Result>().single()

        assertEquals(listOf(2L, 4L, 30452L, 40L), checkNotNull(result.usage).tokens())
    }

    @Test
    fun `bl-71 model_usage_since_last_result is summed over its models`() {
        val line =
            """{"type":"result","subtype":"success","is_error":false,"total_cost_usd":1.5,""" +
                """"usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":4},""" +
                """"model_usage_since_last_result":{""" +
                """"claude-opus-5-5":{"inputTokens":10,"outputTokens":20,"cacheReadInputTokens":30,"cacheCreationInputTokens":40},""" +
                """"claude-sonnet-5-5":{"inputTokens":100,"outputTokens":200,"cacheReadInputTokens":300,"cacheCreationInputTokens":400}}}"""

        val usage = checkNotNull(assertIs<StreamLine.Result>(StreamJsonMapper.parse(line)).usage)

        assertEquals(listOf(110L, 220L, 330L, 440L), usage.tokens())
        assertEquals(1.5, usage.totalCostUsd)
    }

    @Test
    fun `bl-71 a result without model_usage_since_last_result, or with no model in it, keeps its usage`() {
        val usage = """"usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":4}"""
        listOf("", ""","model_usage_since_last_result":{}""", ""","model_usage_since_last_result":null""").forEach { extra ->
            val line = """{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.25,$usage$extra}"""

            val totals = checkNotNull(assertIs<StreamLine.Result>(StreamJsonMapper.parse(line)).usage) { extra }

            assertEquals(listOf(1L, 2L, 3L, 4L), totals.tokens(), extra)
            assertEquals(0.25, totals.totalCostUsd, extra)
        }
    }

    @Test
    fun `the real transcript holds a tool result line longer than 2000 characters, which Loop stores in full`() {
        val long = Fixtures.lines(REAL)[2]

        assertTrue(long.length > 2000, "line 3 has ${long.length} characters")
        assertEquals(StreamLine.Other, StreamJsonMapper.parse(long))
        assertTrue(long.endsWith("}"), "the recorded line is whole")
    }

    @Test
    fun `the real transcript keeps no recorded ids or account values, only obvious fakes`() {
        val text = Fixtures.lines(REAL).joinToString("\n")

        assertFalse("rate_limit_event" in text, "the rate limit line is removed")
        // Recorded ids look like msg_011Cgy...; the fakes keep the prefix the mapper reads.
        assertEquals(null, Regex("""\b(?:msg|toolu|req)_01[A-Za-z0-9]+""").find(text)?.value)
        val ids = Regex("""\b(?:msg|toolu|req)_fake_[0-9]+""").findAll(text).map { it.value }.toSet()
        assertEquals(setOf("msg_fake_0001", "msg_fake_0002", "toolu_fake_0001", "toolu_fake_0002", "req_fake_0001", "req_fake_0002"), ids)
        val uuids = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""").findAll(text).map { it.value }.toSet()
        assertTrue(uuids.isNotEmpty() && uuids.all { it.startsWith("00000000-0000-4000-8000-") }, uuids.toString())
        assertTrue("\"credential_expires_at\":\"2000-01-01T00:00:00.000Z\"" in text)
        assertTrue("\"time_origin_ms\":1000000000000.5" in text)
        assertTrue("\"entrypoint\":\"fake-entrypoint\"" in text)
    }

    private fun assistantUsage(
        cacheCreation: Long,
        cacheRead: Long,
        output: Long,
    ) = StreamLine.UsageTotals(
        inputTokens = 2,
        outputTokens = output,
        cacheReadInputTokens = cacheRead,
        cacheCreationInputTokens = cacheCreation,
        totalCostUsd = null,
    )

    /** The events the runner emits for the fixture's result line when the process exits 0. */
    private fun turnEvents(fixture: String): List<RunnerEvent> {
        val result = lines(fixture).filterIsInstance<StreamLine.Result>().single()
        return StreamJsonMapper.turnEvents(result, SESSION, TURN, exitCode = 0)
    }

    private fun lines(fixture: String): List<StreamLine> = Fixtures.lines(fixture).map(StreamJsonMapper::parse)

    private fun StreamLine.UsageTotals.tokens(): List<Long> =
        listOf(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens)

    private companion object {
        val SESSION = SessionId("thread-1-turn-1")
        val TURN = TurnId("turn-1")

        /**
         * A real `claude -p --output-format stream-json --verbose` session (Claude Code 2.1.319), scrubbed: the
         * work folder is `/work`, the session id a placeholder, the init line's plugins, skills and slash commands
         * are emptied, the rate limit line is removed, and the message, tool use and request ids, the uuids, the
         * credential expiry, the start time and the entrypoint are obvious fakes. It reads a 61 line file, runs
         * `wc -l` and answers `done`.
         */
        const val REAL = "real_read_and_bash"
    }
}
