package loop.runner.codex

import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * A stand in for the `loop` launcher (m3-22), a bash script: `loop tool mcp` answers the MCP handshake and one
 * `tools/call` with [reply], the way Loop's MCP server does, so the Codex runner's tests see what the runner sends it
 * without a store or an attempt. Each run appends its argv to [argv], the MCP lines it read to [received] and the
 * `LOOP_*` names of its env, `NAME=value`, to [env].
 *
 * Its answers use the fixed request ids the runner writes: 1 for `initialize`, 2 for `tools/call`. With [exitCode]
 * it prints [stderr] on stderr and exits before reading anything, as `loop tool mcp` refuses to start outside an
 * attempt.
 */
class FakeLoopTool private constructor(
    /** The launcher, an absolute path, the value of `LOOP_LAUNCHER`. */
    val launcher: Path,
    private val home: Path,
) {
    val argv: Path get() = home.resolve("argv")
    val received: Path get() = home.resolve("received.jsonl")
    val env: Path get() = home.resolve("env")

    /** The MCP lines the fake read, every run, in order; none when it never ran. */
    fun receivedLines(): List<String> = if (received.exists()) received.readLines() else emptyList()

    /** How many times the fake started. */
    fun runs(): Int = if (argv.exists()) argv.readLines().size else 0

    companion object {
        /** A `tools/call` result as Loop's MCP server writes one: one text, a tool error when [isError]. */
        fun reply(
            text: String,
            isError: Boolean = false,
        ): String {
            val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
            return """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"$escaped"}],"isError":$isError}}"""
        }

        /** Writes the fake under [dir] (created if needed); each `tools/call` is answered with [reply], a whole line. */
        fun write(
            dir: Path,
            reply: String = reply("Queued."),
            exitCode: Int? = null,
            stderr: String = "",
        ): FakeLoopTool {
            val home = dir.resolve("fake-loop").createDirectories()
            home.resolve("reply.json").writeText(reply + "\n")
            home.resolve("stderr.txt").writeText(stderr)
            val initialize =
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},""" +
                    """"serverInfo":{"name":"loop","version":"test"}}}"""
            home.resolve("initialize.json").writeText(initialize + "\n")
            val launcher = home.resolve("loop")
            val exit =
                if (exitCode == null) {
                    ""
                } else {
                    "cat '$home/stderr.txt' >&2\nexit $exitCode\n"
                }
            launcher.writeText(
                "#!/bin/bash\n" +
                    "printf '%s\\n' \"\$*\" >> '$home/argv'\n" +
                    "env | grep '^LOOP_' | sort >> '$home/env'\n" +
                    exit +
                    "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$home/received.jsonl'\n" +
                    "cat '$home/initialize.json'\n" +
                    "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$home/received.jsonl'\n" +
                    "IFS= read -r line || exit 0\nprintf '%s\\n' \"\$line\" >> '$home/received.jsonl'\n" +
                    "cat '$home/reply.json'\n" +
                    "while IFS= read -r line; do printf '%s\\n' \"\$line\" >> '$home/received.jsonl'; done\nexit 0\n",
            )
            launcher.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            return FakeLoopTool(launcher, home)
        }
    }
}
