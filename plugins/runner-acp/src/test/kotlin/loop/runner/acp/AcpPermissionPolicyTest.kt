package loop.runner.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import loop.runner.process.HoldoutFolder
import loop.runner.process.ProtectedWorkspaceRoots
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcpPermissionPolicyTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var holdoutDir: Path

    @TempDir
    lateinit var home: Path

    private val customLauncher = "/opt/custom/bin/my-loop-bin"

    private fun policy(strictMcp: Boolean = true): AcpPermissionPolicy =
        AcpPermissionPolicy(
            workspace = workspace,
            holdout = HoldoutFolder(holdoutDir, home),
            protectedRoots = ProtectedWorkspaceRoots(workspace, home),
            strictMcp = strictMcp,
            launcher = customLauncher,
        )

    private fun defaultOptions(): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("optionId", "opt-allow-once")
                    put("kind", "allow_once")
                },
            )
            add(
                buildJsonObject {
                    put("optionId", "opt-allow-always")
                    put("kind", "allow_always")
                },
            )
            add(
                buildJsonObject {
                    put("optionId", "opt-reject-once")
                    put("kind", "reject_once")
                },
            )
        }

    private fun permissionRequest(
        id: Long = 10,
        toolCall: JsonObject,
        options: JsonArray? = defaultOptions(),
    ): AcpFrame.Request =
        AcpFrame.Request(
            line = "",
            id = JsonPrimitive(id),
            method = AcpHandshake.METHOD_REQUEST_PERMISSION,
            params =
                buildJsonObject {
                    put("sessionId", "s-1")
                    put("toolCall", toolCall)
                    if (options != null) put("options", options)
                },
        )

    private fun outcomeOf(answer: AcpPermissionPolicy.Answer.Write): JsonObject =
        AcpFraming.json
            .parseToJsonElement(answer.line.trimEnd('\n'))
            .jsonObject["result"]!!
            .jsonObject["outcome"]!!
            .jsonObject

    @Test
    fun `run_command allows safe commands with allow_once and fails closed when command text is missing`() {
        val safe =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "run_command")
                            put("kind", "execute")
                            put("rawInput", buildJsonObject { put("CommandLine", "./gradlew test") })
                        },
                ),
            )
        val allowWrite = assertIs<AcpPermissionPolicy.Answer.Write>(safe)
        assertEquals("selected", outcomeOf(allowWrite)["outcome"]!!.jsonPrimitive.content)
        assertEquals("opt-allow-once", outcomeOf(allowWrite)["optionId"]!!.jsonPrimitive.content)

        val missingCmd =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "run_command")
                            put("kind", "execute")
                            put("rawInput", buildJsonObject {})
                        },
                ),
            )
        val denyWrite = assertIs<AcpPermissionPolicy.Answer.Write>(missingCmd)
        assertEquals("opt-reject-once", outcomeOf(denyWrite)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.NO_COMMAND in denyWrite.note, denyWrite.note)
    }

    @Test
    fun `run_command declines denied commands including custom LOOP_LAUNCHER, holdoutDir, and protected roots`() {
        val deniedCmds =
            listOf(
                "git push origin main",
                "git -C . push",
                "gh pr create --fill",
                "git rebase main",
                "loop approve m4-01",
                "$customLauncher resume m4-01",
                "cat $holdoutDir/secret.sh",
                "echo '{}' > .agents/hooks.json",
                "mkdir -p _agent/rules",
            )
        for (cmd in deniedCmds) {
            val answer =
                policy().answer(
                    permissionRequest(
                        toolCall =
                            buildJsonObject {
                                put("name", "run_command")
                                put("args", buildJsonObject { put("CommandLine", cmd) })
                            },
                    ),
                )
            val write = assertIs<AcpPermissionPolicy.Answer.Write>(answer, cmd)
            assertEquals("opt-reject-once", outcomeOf(write)["optionId"]!!.jsonPrimitive.content, cmd)
            assertTrue(write.note.startsWith("declined "), "$cmd: ${write.note}")
        }
    }

    @Test
    fun `read_file allows workspace reads, declines holdoutDir including symlinks, and fails closed without a path`() {
        val okRead =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "read_file")
                            put("kind", "read")
                            put("rawInput", buildJsonObject { put("Path", workspace.resolve("README.md").toString()) })
                        },
                ),
            )
        assertEquals("opt-allow-once", outcomeOf(assertIs(okRead))["optionId"]!!.jsonPrimitive.content)

        val symlinkToHoldout = workspace.resolve("sneaky-link")
        Files.createSymbolicLink(symlinkToHoldout, holdoutDir)
        val holdoutRead =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "read_file")
                            put("kind", "read")
                            put(
                                "locations",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("path", symlinkToHoldout.resolve("check.sh").toString())
                                        },
                                    )
                                },
                            )
                        },
                ),
            )
        val holdoutDeny = assertIs<AcpPermissionPolicy.Answer.Write>(holdoutRead)
        assertEquals("opt-reject-once", outcomeOf(holdoutDeny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.HOLDOUT in holdoutDeny.note, holdoutDeny.note)

        val noPath =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "read_file")
                            put("kind", "read")
                        },
                ),
            )
        val noPathDeny = assertIs<AcpPermissionPolicy.Answer.Write>(noPath)
        assertEquals("opt-reject-once", outcomeOf(noPathDeny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.NO_PATH in noPathDeny.note, noPathDeny.note)
    }

    @Test
    fun `file write and edit tools decline holdoutDir and protected workspace roots including symlinks`() {
        val okWrite =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "write_file")
                            put("kind", "edit")
                            put("rawInput", buildJsonObject { put("file_path", "src/Main.kt") })
                        },
                ),
            )
        assertEquals("opt-allow-once", outcomeOf(assertIs(okWrite))["optionId"]!!.jsonPrimitive.content)

        val agentsDir = workspace.resolve(".agents").createDirectories()
        val linkToAgents = workspace.resolve("alias-dir")
        Files.createSymbolicLink(linkToAgents, agentsDir)

        val deniedWrites =
            listOf(
                holdoutDir.resolve("check.sh").toString(),
                ".agents/hooks.json",
                ".agent/mcp_config.json",
                "_agents/skills.json",
                "_agent/rules/rule.md",
                linkToAgents.resolve("hooks.json").toString(),
            )
        for (target in deniedWrites) {
            val answer =
                policy().answer(
                    permissionRequest(
                        toolCall =
                            buildJsonObject {
                                put("name", "edit_file")
                                put("kind", "edit")
                                put("args", buildJsonObject { put("Path", target) })
                            },
                    ),
                )
            val write = assertIs<AcpPermissionPolicy.Answer.Write>(answer, target)
            assertEquals("opt-reject-once", outcomeOf(write)["optionId"]!!.jsonPrimitive.content, target)
        }
    }

    @Test
    fun `MCP tools allow Loop tools and decline non-Loop MCP tools under strict_mcp`() {
        for (loopTool in listOf("mcp_loop_submit_result", "mcp_loop_move", "mcp_loop_comment")) {
            val answer = policy(strictMcp = true).answer(permissionRequest(toolCall = buildJsonObject { put("name", loopTool) }))
            assertEquals("opt-allow-once", outcomeOf(assertIs(answer))["optionId"]!!.jsonPrimitive.content, loopTool)
        }

        val otherStrict =
            policy(strictMcp = true).answer(permissionRequest(toolCall = buildJsonObject { put("name", "mcp_github_push") }))
        val otherStrictDeny = assertIs<AcpPermissionPolicy.Answer.Write>(otherStrict)
        assertEquals("opt-reject-once", outcomeOf(otherStrictDeny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.NON_LOOP_MCP in otherStrictDeny.note, otherStrictDeny.note)

        val otherPermissive =
            policy(strictMcp = false).answer(permissionRequest(toolCall = buildJsonObject { put("name", "mcp_github_push") }))
        assertEquals("opt-allow-once", outcomeOf(assertIs(otherPermissive))["optionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown tools fail closed, missing reject option falls back to cancelled, and human-input returns InputRequired`() {
        val unknown =
            policy().answer(
                permissionRequest(
                    toolCall = buildJsonObject { put("name", "send_command_input") },
                    options =
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("optionId", "only-allow")
                                    put("kind", "allow_once")
                                },
                            )
                        },
                ),
            )
        val unknownDeny = assertIs<AcpPermissionPolicy.Answer.Write>(unknown)
        assertEquals("cancelled", outcomeOf(unknownDeny)["outcome"]!!.jsonPrimitive.content)

        val askTool =
            policy().answer(permissionRequest(toolCall = buildJsonObject { put("name", "ask_question") }))
        assertIs<AcpPermissionPolicy.Answer.InputRequired>(askTool)

        val userInputRpc =
            policy().answer(
                AcpFrame.Request("", JsonPrimitive(11), "session/request_user_input", buildJsonObject {}),
            )
        assertIs<AcpPermissionPolicy.Answer.InputRequired>(userInputRpc)

        val unknownRpc =
            policy().answer(
                AcpFrame.Request("", JsonPrimitive(12), "unknown/method", buildJsonObject {}),
            )
        val rpcWrite = assertIs<AcpPermissionPolicy.Answer.Write>(unknownRpc)
        val err = assertIs<AcpFrame.Error>(AcpFraming.decode(rpcWrite.line.trimEnd('\n')))
        assertEquals(-32601L, err.code)
    }

    @Test
    fun `never falls back to allow_always or bare allow when allow_once is absent`() {
        val onlyAlways =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("optionId", "opt-allow-always")
                        put("kind", "allow_always")
                    },
                )
                add(
                    buildJsonObject {
                        put("optionId", "allow")
                    },
                )
                add(
                    buildJsonObject {
                        put("optionId", "opt-reject-once")
                        put("kind", "reject_once")
                    },
                )
            }
        val answer =
            policy().answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "read_file")
                            put("kind", "read")
                            put("rawInput", buildJsonObject { put("Path", workspace.resolve("README.md").toString()) })
                        },
                    options = onlyAlways,
                ),
            )
        val deny = assertIs<AcpPermissionPolicy.Answer.Write>(answer)
        assertEquals("opt-reject-once", outcomeOf(deny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.NO_ALLOW_OPTION in deny.note, deny.note)
    }

    @Test
    fun `declines file writes and commands targeting protected internal Loop directories`() {
        val internalDir = home.resolve(".loop/attempts/proj-1/att-1").createDirectories()
        val guardedPolicy =
            AcpPermissionPolicy(
                workspace = workspace,
                holdout = HoldoutFolder(holdoutDir, home),
                protectedRoots = ProtectedWorkspaceRoots(workspace, home),
                strictMcp = true,
                launcher = customLauncher,
                protectedWriteDirs = { listOf(HoldoutFolder(internalDir, home)) },
            )

        val writeAttempt =
            guardedPolicy.answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "write_file")
                            put("kind", "edit")
                            put(
                                "rawInput",
                                buildJsonObject {
                                    put("Path", internalDir.resolve("agy-gemini-1234/antigravity-cli/settings.json").toString())
                                },
                            )
                        },
                ),
            )
        val writeDeny = assertIs<AcpPermissionPolicy.Answer.Write>(writeAttempt)
        assertEquals("opt-reject-once", outcomeOf(writeDeny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.INTERNAL_LOOP_DIR in writeDeny.note, writeDeny.note)

        val cmdAttempt =
            guardedPolicy.answer(
                permissionRequest(
                    toolCall =
                        buildJsonObject {
                            put("name", "run_command")
                            put("kind", "execute")
                            put(
                                "rawInput",
                                buildJsonObject {
                                    put("CommandLine", "rm -rf $internalDir/agy-gemini-1234")
                                },
                            )
                        },
                ),
            )
        val cmdDeny = assertIs<AcpPermissionPolicy.Answer.Write>(cmdAttempt)
        assertEquals("opt-reject-once", outcomeOf(cmdDeny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.INTERNAL_LOOP_DIR in cmdDeny.note, cmdDeny.note)
    }
}
