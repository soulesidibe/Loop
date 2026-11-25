package loop.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.Redactor
import loop.api.RunId
import loop.core.resources.ResourceLocks
import loop.store.SqliteEventStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * m4-25: `request_resource` and `release_resource` on `loop tool mcp`, answered inside the turn. The agents are scripts
 * that speak JSON-RPC to the real server over stdio, so the grants run without a model. The tool process takes, queues
 * or marks the lock in the store from the tool config snapshot alone; `loop run`, here in process so the fake provider
 * of the test classpath serves its `fake-device` kind, prepares and gives back each resource on its tick.
 */
class ResourceToolsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root.resolve("b")) }

    /** A resource id only this test uses, since the fake provider's calls are shared by the tests of the JVM. */
    private val resource = "res-${UUID.randomUUID()}"

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
            """"clientInfo":{"name":"claude-code","version":"2.1.0"}}}"""
    private val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    @Test
    fun `two attempts over loop tool mcp, preparing then granted, queued then granted, and loop trace prints the four events`() {
        fixture.issue("t-1")
        fixture.issue("t-2")
        resourcesWorkflow(fixture)
        agent(
            fixture,
            """
            |if [ "${D}LOOP_ISSUE_IDENTIFIER" = t-1 ]; then
            |  call request_resource $resource
            |  until_granted $resource
            |  touch "${D}R/t1-granted"
            |  await t2-queued
            |  call release_resource $resource
            |else
            |  await t1-granted
            |  call request_resource $resource
            |  touch "${D}R/t2-queued"
            |  until_granted $resource
            |  call release_resource $resource
            |fi
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${transcripts(fixture)}")
        assertEquals("review", fixture.stateOf("t-1"), run.toString())
        assertEquals("review", fixture.stateOf("t-2"), run.toString())

        val first = answers(fixture, "t-1")
        assertEquals("preparing", first.first().second.status(), "$first")
        val firstRequests = first.filter { it.first == "request_resource" }
        assertTrue(firstRequests.dropLast(1).all { it.second.status() == "preparing" }, "$first")
        assertGranted(firstRequests.last().second)
        assertEquals(listOf("release_resource" to "released"), first.drop(firstRequests.size).map { it.first to it.second.status() })

        val second = answers(fixture, "t-2")
        val queued = second.first().second
        assertEquals("queued", queued.status(), "$second")
        assertEquals(JsonPrimitive(1), queued["position"])
        val secondRequests = second.filter { it.first == "request_resource" }
        assertTrue(secondRequests.dropLast(1).all { it.second.status() in setOf("queued", "preparing") }, "$second")
        assertGranted(secondRequests.last().second)
        assertEquals("released", second.last().second.status(), "$second")

        val pid = ProcessHandle.current().pid()
        assertEquals(
            listOf("prepare" to pid, "release" to pid, "prepare" to pid, "release" to pid),
            FakeResourceProvider.calls.filter { it.id == resource }.map { it.op to it.pid },
        )

        val events = fixture.query("SELECT kind, issue_identifier, data FROM events WHERE kind LIKE 'resource_%' ORDER BY seq")

        fun kinds(issue: String) = events.filter { it["issue_identifier"] == issue }.map { it["kind"] }
        assertEquals(listOf("resource_requested", "resource_granted", "resource_released"), kinds("t-1"))
        assertEquals(listOf("resource_requested", "resource_queued", "resource_granted", "resource_released"), kinds("t-2"))
        val trace = fixture.inProcess("trace", "t-2", "--workflow", fixture.workflow.toString())
        assertEquals(0, trace.exitCode, trace.toString())
        for (kind in listOf("resource_requested", "resource_queued", "resource_granted", "resource_released")) {
            assertTrue(trace.stdout.lines().any { kind in it && resource in it }, "$kind\n${trace.stdout}")
        }
        assertTrue(trace.stdout.lines().any { "resource_queued" in it && "position=1" in it }, trace.stdout)
        assertTrue(trace.stdout.lines().any { "resource_released" in it && "reason=released" in it }, trace.stdout)
    }

    @Test
    fun `prepare and release run in the loop run process, never in loop tool mcp`() {
        fixture.issue("t-1")
        resourcesWorkflow(fixture)
        agent(
            fixture,
            """
            |call request_resource $resource
            |until_granted $resource
            |call release_resource $resource
            |n=0
            |until grep -q released "${D}transcript"; do n=$D((n + 1)); [ ${D}n -lt 100 ] || exit 6; sleep 0.1; done
            |finish
            """.trimMargin(),
        )

        val run = fixture.inProcess("run", "--once", fixture.workflow.toString())

        assertEquals(0, run.exitCode, "$run\n${transcripts(fixture)}")
        val loopRun = ProcessHandle.current().pid()
        val toolProcess =
            root
                .resolve("b/mcp-t-1.pid")
                .readText()
                .trim()
                .toLong()
        assertNotEquals(loopRun, toolProcess, "loop tool mcp is its own process")
        val calls = FakeResourceProvider.calls.filter { it.id == resource }
        assertEquals(listOf("prepare", "release"), calls.map { it.op })
        assertEquals(listOf(loopRun, loopRun), calls.map { it.pid }, "each provider call ran in loop run")
    }

    @Test
    fun `the snapshot carries the resources, and loop tool mcp answers from it with no WORKFLOW_md`() {
        fixture.issue("t-1")
        resourcesWorkflow(fixture)
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        val snapshot = Json.parseToJsonElement(Path.of(env.getValue("LOOP_TOOL_CONFIG")!!).readText()).jsonObject
        assertEquals(
            Json.parseToJsonElement("""[{"kind":"fake-device","id":"$resource","can":["ui tests"],"cannot":["push"]}]"""),
            snapshot["resources"],
            "the kind, id, can and cannot, never the restore or read only commands",
        )
        // `loop tool mcp` never reads WORKFLOW.md: one that fails to load changes nothing.
        fixture.workflow.writeText("not a workflow\n")

        val replies = session(env, list(), request(resource))

        assertEquals(TOOLS + listOf("request_resource", "release_resource"), replies[0].toolNames())
        assertEquals("preparing", replies[1].answer().status(), "${replies[1]}")
        val locks = fixture.query("SELECT attempt_id, resource_id FROM resource_locks")
        assertEquals(listOf(mapOf("attempt_id" to ATTEMPT, "resource_id" to resource)), locks)
    }

    @Test
    fun `without resources loop tool mcp advertises no resource tool`() {
        fixture.issue("t-1")
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")

        val replies = session(env, list())

        assertEquals(TOOLS, replies[0].toolNames())
    }

    @Test
    fun `an unknown name is a structured failure and the turn goes on`() {
        fixture.issue("t-1")
        resourcesWorkflow(fixture)
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val replies = session(fixture.toolEnv(ATTEMPT, "t-1"), request("nope"), request(resource))

        val failure = replies[0].getValue("result").jsonObject
        assertEquals(true, failure["isError"]?.jsonPrimitive?.boolean, "$failure")
        val answer = replies[0].answer()
        assertEquals("error", answer.status())
        assertEquals(JsonPrimitive("unknown_resource"), answer["error"])
        assertContains(
            answer.getValue("message").jsonPrimitive.content,
            "\"nope\" is not a resource of the workflow. The resources are: $resource.",
        )
        assertEquals("preparing", replies[1].answer().status(), "the next call is served")
        assertEquals(0, fixture.query("SELECT 1 FROM events WHERE kind = 'resource_requested' AND data LIKE '%nope%'").size)
    }

    @Test
    fun `a second request of a resource the attempt holds is a structured failure and the turn goes on`() {
        fixture.issue("t-1")
        resourcesWorkflow(fixture)
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))
        val env = fixture.toolEnv(ATTEMPT, "t-1")
        assertEquals("preparing", session(env, request(resource)).single().answer().status())
        // What loop run writes on its tick: the prepare result.
        withLocks { locks ->
            val lock =
                locks
                    .rows()
                    .getOrThrow()
                    .locks
                    .single()
            locks.prepared(lock, Result.success(mapOf("serial" to "fake-$resource"))).getOrThrow()
        }

        val replies = session(env, request(resource), request(resource), release(resource))

        assertGranted(replies[0].answer())
        val double = replies[1].answer()
        assertEquals(
            true,
            replies[1]
                .getValue("result")
                .jsonObject["isError"]
                ?.jsonPrimitive
                ?.boolean,
        )
        assertEquals(JsonPrimitive("already_held"), double["error"], "$double")
        assertContains(double.getValue("message").jsonPrimitive.content, "You already hold $resource, granted with serial fake-$resource.")
        assertEquals("released", replies[2].answer().status(), "the next call is served")
        assertEquals(1, fixture.query("SELECT 1 FROM events WHERE kind = 'resource_requested'").size)
    }

    @Test
    fun `a release of a resource the attempt does not hold is a structured failure and the turn goes on`() {
        fixture.issue("t-1")
        resourcesWorkflow(fixture)
        fixture.seed(fixture.attemptStarted(ATTEMPT, "t-1"))

        val replies = session(fixture.toolEnv(ATTEMPT, "t-1"), release(resource), request(resource))

        val failure = replies[0].answer()
        assertEquals(
            true,
            replies[0]
                .getValue("result")
                .jsonObject["isError"]
                ?.jsonPrimitive
                ?.boolean,
        )
        assertEquals(JsonPrimitive("not_held"), failure["error"], "$failure")
        assertContains(failure.getValue("message").jsonPrimitive.content, "You do not hold $resource")
        assertEquals("preparing", replies[1].answer().status(), "the next call is served")
    }

    @Test
    fun `bl-204 a project killed while holding a lock never keeps it, the queued request of another project is granted`() {
        val machine = root.resolve("loop-home")
        val dead = LoopFixture(root.resolve("a"), machine)
        val live = LoopFixture(root.resolve("b"), machine)
        for (project in listOf(dead, live)) {
            project.issue("t-1")
            resourcesWorkflow(project)
        }
        val deadAgentPid = root.resolve("a/agent.pid")
        agent(
            dead,
            """
            |echo ${D}$D > '$deadAgentPid'
            |call request_resource $resource
            |until_granted $resource
            |touch "$root/a-granted"
            |exec sleep 120
            """.trimMargin(),
        )
        val deadRunPid = root.resolve("a/run.pid")
        agent(
            live,
            """
            |n=0; until [ -f "$root/a-granted" ]; do n=$D((n + 1)); [ ${D}n -lt 900 ] || exit 8; sleep 0.1; done
            |call request_resource $resource
            |kill -9 "$D(cat '$deadRunPid')"
            |until_granted $resource
            |call release_resource $resource
            |finish
            """.trimMargin(),
        )
        val deadRun = dead.start("run", dead.workflow.toString())
        try {
            deadRunPid.writeText(deadRun.process.pid().toString())

            val run = live.inProcess("run", "--once", live.workflow.toString())

            assertEquals(0, run.exitCode, "$run\n${transcripts(live)}")
            assertTrue(deadRun.process.waitFor(30, TimeUnit.SECONDS), "the first loop run outlived kill -9")
            val answers = answers(live, "t-1")
            assertEquals("queued", answers.first().second.status(), "the holder was alive at the first call: $answers")
            assertGranted(answers.last { it.first == "request_resource" }.second)
            val released = live.query("SELECT issue_identifier, data FROM events WHERE kind = 'resource_released' ORDER BY seq")
            assertTrue(released.first()["data"].orEmpty().contains("\"reason\":\"abandoned\""), "$released")
            assertEquals("review", live.stateOf("t-1"), run.toString())
        } finally {
            deadRun.process.destroyForcibly()
            if (deadAgentPid.exists()) ProcessBuilder("kill", "-9", deadAgentPid.readText().trim()).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    /** One `fake-device` resource, [resource], served by the fake provider, two agents at once, one turn each. */
    private fun resourcesWorkflow(project: LoopFixture) {
        project.writeWorkflow(
            extra =
                """
                |agent:
                |  max_concurrent_agents: 2
                |  max_turns: 1
                |retries:
                |  max_attempts: 1
                |  max_continuations: 1
                |resources:
                |  - kind: fake-device
                |    id: $resource
                |    can: [ui tests]
                |    cannot: [push]
                |    restore: ["true"]
                |    read_only_check: "true"
                """.trimMargin(),
        )
    }

    /**
     * Writes the agent of [project]: [body] runs with a `loop tool mcp` session open on stdio, through `call TOOL NAME`,
     * which waits for the reply and appends the tool and the reply to the issue's transcript; `until_granted NAME`
     * calls `request_resource` again until it is granted; `await FILE` waits for a file of the project root; `finish`
     * closes the session and moves the issue to review.
     */
    private fun agent(
        project: LoopFixture,
        body: String,
    ) {
        val dir = project.agentScript.parent.parent
        project.agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |R='$dir'
            |replies="${D}R/replies-${D}LOOP_ISSUE_IDENTIFIER.jsonl"
            |transcript="${D}R/transcript-${D}LOOP_ISSUE_IDENTIFIER.txt"
            |: > "${D}replies"
            |rpc=$D(mktemp -d)
            |mkfifo "${D}rpc/in"
            |loop tool mcp < "${D}rpc/in" > "${D}replies" &
            |echo $D! > "${D}R/mcp-${D}LOOP_ISSUE_IDENTIFIER.pid"
            |exec 3> "${D}rpc/in"
            |printf '%s\n' '$initialize' '$initialized' >&3
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
            |granted='\"status\":\"granted\"'
            |until_granted() {
            |  n=0
            |  until printf '%s' "${D}reply" | grep -Fq "${D}granted"; do
            |    n=$D((n + 1)); [ ${D}n -lt 300 ] || exit 7; sleep 0.2; call request_resource "${D}1"
            |  done
            |}
            |await() { n=0; until [ -f "${D}R/${D}1" ]; do n=$D((n + 1)); [ ${D}n -lt 600 ] || exit 8; sleep 0.1; done; }
            |finish() { exec 3>&-; wait; loop tool move review; }
            |$body
            |
            """.trimMargin(),
        )
    }

    /** The tool and the answer of each call the agent of [issue] in [project] made, in order. */
    private fun answers(
        project: LoopFixture,
        issue: String,
    ): List<Pair<String, JsonObject>> =
        project.agentScript.parent.parent
            .resolve("transcript-$issue.txt")
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val tool = line.substringBefore(' ')
                tool to Json.parseToJsonElement(line.substringAfter(' ')).jsonObject.answer()
            }

    private fun transcripts(project: LoopFixture): String {
        val dir = project.agentScript.parent.parent
        return listOf("t-1", "t-2")
            .mapNotNull { issue ->
                dir.resolve("transcript-$issue.txt").takeIf { it.exists() }?.let { "--- $issue\n${it.readText()}" }
            }.joinToString("\n")
    }

    private fun assertGranted(answer: JsonObject) {
        assertEquals("granted", answer.status(), "$answer")
        assertEquals(JsonObject(mapOf("serial" to JsonPrimitive("fake-$resource"))), answer["prepared"], "$answer")
        assertEquals(JsonArray(listOf(JsonPrimitive("ui tests"))), answer["can"], "$answer")
        assertEquals(JsonArray(listOf(JsonPrimitive("push"))), answer["cannot"], "$answer")
    }

    private fun list() = """{"jsonrpc":"2.0","id":%d,"method":"tools/list","params":{}}"""

    private fun request(name: String) = toolCall("request_resource", name)

    private fun release(name: String) = toolCall("release_resource", name)

    private fun toolCall(
        tool: String,
        name: String,
    ) = """{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"$tool","arguments":{"name":"$name"}}}"""

    /**
     * Runs one `loop tool mcp` session with [env]: the handshake, then each of [messages], a JSON-RPC request whose
     * `%d` takes its id, one at a time, each waiting for the reply of the one before. Returns the replies in order.
     */
    private fun session(
        env: Map<String, String?>,
        vararg messages: String,
    ): List<JsonObject> {
        val run = fixture.start("tool", "mcp", cwd = root, env = env)
        try {
            val stdin = run.process.outputStream.bufferedWriter()
            listOf(initialize, initialized).forEach { stdin.write(it + "\n") }
            stdin.flush()
            val replies =
                messages.mapIndexed { index, message ->
                    val id = index + 2
                    stdin.write(message.format(id) + "\n")
                    stdin.flush()
                    val deadline = System.nanoTime() + 60_000_000_000L
                    var reply: JsonObject? = null
                    while (reply == null && System.nanoTime() < deadline && run.process.isAlive) {
                        reply = messages(run.stdoutSoFar()).firstOrNull { it["id"]?.jsonPrimitive?.content == "$id" }
                        if (reply == null) Thread.sleep(50)
                    }
                    reply ?: fail("no reply to $id: ${run.await()}")
                }
            stdin.close()
            val result = run.await()
            assertEquals(0, result.exitCode, result.toString())
            return replies
        } finally {
            run.process.destroyForcibly()
        }
    }

    private fun messages(stdout: String): List<JsonObject> =
        stdout.lines().filter { it.isNotBlank() }.map { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrElse { fail("not a JSON-RPC line on stdout: $line") }
        }

    /** The answer of a tool call reply: the JSON object of its text. */
    private fun JsonObject.answer(): JsonObject =
        Json
            .parseToJsonElement(
                getValue("result")
                    .jsonObject
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            ).jsonObject

    private fun JsonObject.status(): String? = this["status"]?.jsonPrimitive?.content

    private fun JsonObject.toolNames(): List<String> =
        getValue("result").jsonObject.getValue("tools").jsonArray.map {
            it.jsonObject
                .getValue("name")
                .jsonPrimitive.content
        }

    private fun withLocks(block: suspend (ResourceLocks) -> Unit) {
        SqliteEventStore.open(fixture.loopHome, fixture.projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking { block(ResourceLocks(store, store, Clock.System, RunId(TEST_RUN))) }
        }
    }

    private companion object {
        const val D = "$"
        const val ATTEMPT = "7c1e9d2a-0000-4000-8000-000000000025"
        const val TEST_RUN = "7e570000-0000-4000-8000-000000000002"
        val TOOLS = listOf("submit_result", "move", "comment")
    }
}
