package loop.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * m4-26: the agents of the resource tests, scripts that speak JSON-RPC to the real `loop tool mcp` over stdio, as in
 * [ResourceToolsTest], so the resource tools run without a model.
 */
internal object ResourceAgent {
    private const val D = "$"

    private const val INITIALIZE =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
            """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
    private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    /** The folder of the agent's files: replies, transcripts and the files `await` waits for. */
    fun dir(project: LoopFixture) = project.agentScript.parent.parent

    /**
     * Writes the agent of [project]: [body] runs with a `loop tool mcp` session open on stdio, through `call TOOL NAME`,
     * which waits for the reply, keeps it in `reply` and appends the tool and the reply to the issue's transcript;
     * `until_granted NAME` calls `request_resource` again until it is granted; `until_status STATUS TOOL NAME` calls
     * TOOL again until its reply holds the status; `await FILE` waits for a file of the folder `R`; `finish` closes the
     * session and moves the issue to review.
     */
    fun write(
        project: LoopFixture,
        body: String,
    ) {
        project.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |R='${dir(project)}'
            |replies="${D}R/replies-${D}LOOP_ISSUE_IDENTIFIER.jsonl"
            |transcript="${D}R/transcript-${D}LOOP_ISSUE_IDENTIFIER.txt"
            |: > "${D}replies"
            |rpc=$D(mktemp -d)
            |mkfifo "${D}rpc/in"
            |loop tool mcp < "${D}rpc/in" > "${D}replies" &
            |exec 3> "${D}rpc/in"
            |printf '%s\n' '$INITIALIZE' '$INITIALIZED' >&3
            |id=1
            |reply=
            |call() {
            |  id=$D((id + 1))
            |  printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":{"name":"%s"}}}\n' "${D}id" "${D}1" "${D}2" >&3
            |  n=0
            |  until grep -Eq "\"id\":${D}id[,}]" "${D}replies"; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 9; sleep 0.1; done
            |  reply=$D(grep -E "\"id\":${D}id[,}]" "${D}replies")
            |  echo "${D}1 ${D}reply" >> "${D}transcript"
            |}
            |until_status() {
            |  n=0
            |  until printf '%s' "${D}reply" | grep -Fq "\\\"status\\\":\\\"${D}1\\\""; do
            |    n=$D((n + 1)); [ ${D}n -lt 300 ] || exit 7; sleep 0.2; call "${D}2" "${D}3"
            |  done
            |}
            |until_granted() { until_status granted request_resource "${D}1"; }
            |await() { n=0; until [ -f "${D}R/${D}1" ]; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 8; sleep 0.1; done; }
            |finish() { exec 3>&-; wait; loop tool move review; }
            |$body
            |
            """.trimMargin(),
        )
    }

    /** The tool and the answer of each call the agent of [issue] in [project] made, in order. */
    fun answers(
        project: LoopFixture,
        issue: String,
    ): List<Pair<String, JsonObject>> =
        dir(project)
            .resolve("transcript-$issue.txt")
            .readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.substringBefore(' ') to answer(Json.parseToJsonElement(line.substringAfter(' ')).jsonObject) }

    /** The transcripts of [issues] in [project], for a failure message. */
    fun transcripts(
        project: LoopFixture,
        vararg issues: String,
    ): String =
        issues
            .mapNotNull { issue ->
                dir(project).resolve("transcript-$issue.txt").takeIf { it.exists() }?.let { "--- $issue\n${it.readText()}" }
            }.joinToString("\n")

    /** The answer of a tool call reply: the JSON object of its text. */
    private fun answer(reply: JsonObject): JsonObject =
        Json
            .parseToJsonElement(
                reply
                    .getValue("result")
                    .jsonObject
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            ).jsonObject
}

/** The `status` of an answer of a resource tool. */
internal fun JsonObject.status(): String? = this["status"]?.jsonPrimitive?.content
