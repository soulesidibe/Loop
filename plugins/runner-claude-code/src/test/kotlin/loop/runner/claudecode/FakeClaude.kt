package loop.runner.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A fake `claude` executable, an sh script in [dir], long lived like Claude Code with `--input-format stream-json`
 * (m3-07). Each run records its argv (one argument per line), its working directory, its env and a copy of the
 * `--settings` and `--mcp-config` files in [record], then runs [before] (sh) once. It then reads stdin one line per
 * turn, records the line, writes a transcript to stdout and runs [after] (sh), until stdin ends. The transcript is
 * [fixture] for the first turn of a fresh run, [resumeFixture] for the first turn of a `--resume` run, and
 * [nextFixture] for the later turns of the same run. A null [fixture] writes nothing. `after = "exit 0"` ends the run
 * after its first turn, as a process that dies between turns.
 */
class FakeClaude(
    dir: Path,
    fixture: String? = "success",
    before: String = "",
    after: String = "",
    resumeFixture: String = "resumed",
    nextFixture: String = resumeFixture,
) {
    val record: Path = dir.resolve("record").createDirectories()
    val script: Path = dir.resolve("claude")

    init {
        val replay =
            fixture?.let {
                """
                |  if [ "${'$'}t" -gt 0 ]; then cat '${Fixtures.path(nextFixture)}'
                |  elif grep -qx -e '--resume' "$record/argv.${'$'}n"; then cat '${Fixtures.path(resumeFixture)}'
                |  else cat '${Fixtures.path(it)}'; fi
                """.trimMargin()
            } ?: "  :"
        script.writeText(
            """
            |#!/bin/sh
            |n=${'$'}(ls '$record' | grep -c '^argv[.]')
            |for arg in "${'$'}@"; do printf '%s\n' "${'$'}arg"; done > '$record/argv'
            |cp '$record/argv' "$record/argv.${'$'}n"
            |pwd -P > '$record/cwd'
            |env > '$record/env'
            |settings=""
            |mcp=""
            |previous=""
            |for arg in "${'$'}@"; do
            |  if [ "${'$'}previous" = "--settings" ]; then settings="${'$'}arg"; fi
            |  if [ "${'$'}previous" = "--mcp-config" ]; then mcp="${'$'}arg"; fi
            |  previous="${'$'}arg"
            |done
            |if [ -n "${'$'}settings" ]; then cp "${'$'}settings" '$record/settings.json'; fi
            |if [ -n "${'$'}mcp" ]; then cp "${'$'}mcp" '$record/mcp.json'; cp "${'$'}mcp" "$record/mcp.json.${'$'}n"; fi
            |$before
            |t=0
            |while IFS= read -r line; do
            |  printf '%s\n' "${'$'}line" > "$record/stdin.${'$'}n.${'$'}t"
            |  printf '%s\n' "${'$'}line" >> '$record/stdin'
            |$replay
            |  $after
            |  t=${'$'}((t + 1))
            |done
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    val argv: List<String> get() = record.resolve("argv").readLines()

    /** How many times the script ran, that is how many processes were spawned. */
    val invocations: Int get() =
        Files.list(record).use { files ->
            files.filter { it.fileName.toString().startsWith("argv.") }.count().toInt()
        }

    /** The argv of invocation [index] (0-based). */
    fun argvOf(index: Int): List<String> = record.resolve("argv.$index").readLines()

    /** The text of each user message invocation [index] (0-based) read, one per turn, in order. */
    fun promptsOf(index: Int): List<String> =
        generateSequence(0) { it + 1 }
            .map { record.resolve("stdin.$index.$it") }
            .takeWhile { it.exists() }
            .map { textOf(it.readText().trim()) }
            .toList()

    /** The text of every user message read, across invocations, in order. */
    val prompts: List<String> get() =
        record
            .resolve("stdin")
            .takeIf { it.exists() }
            ?.readLines()
            ?.map(::textOf)
            .orEmpty()

    /** The raw stdin lines read, across invocations, in order. */
    val stdinLines: List<String> get() =
        record
            .resolve("stdin")
            .takeIf { it.exists() }
            ?.readLines()
            .orEmpty()

    val cwd: String get() = record.resolve("cwd").readText().trim()

    /** The env the agent saw, by name. */
    val env: Map<String, String>
        get() =
            record
                .resolve("env")
                .readLines()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** The settings file content while the agent ran, or null when none was passed. */
    val settings: String? get() = record.resolve("settings.json").takeIf { it.exists() }?.readText()

    /** The MCP config content while the agent ran (m2-16), or null when none was passed. */
    val mcpConfig: String? get() = record.resolve("mcp.json").takeIf { it.exists() }?.readText()

    /** The MCP config content invocation [index] (0-based) was given, or null when none was passed. */
    fun mcpConfigOf(index: Int): String? = record.resolve("mcp.json.$index").takeIf { it.exists() }?.readText()

    /** The value after [flag] in the argv. */
    fun argAfter(flag: String): String? = argv.zipWithNext().firstOrNull { it.first == flag }?.second

    private companion object {
        /** The text of a stream-json user message line: its first text content block. */
        fun textOf(line: String): String =
            Json
                .parseToJsonElement(line)
                .jsonObject
                .getValue("message")
                .jsonObject
                .getValue("content")
                .jsonArray
                .first()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content
    }
}
