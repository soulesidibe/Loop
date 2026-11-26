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

    @Test
    fun `bl-205 read only declines every MCP tool, writes under internal Loop directories, fetch and a mutating tool of kind think`() {
        val internalDir = home.resolve(".loop/attempts/proj-1/att-1").createDirectories()
        val readOnly =
            AcpPermissionPolicy(
                workspace = workspace,
                holdout = HoldoutFolder(holdoutDir, home),
                protectedRoots = ProtectedWorkspaceRoots(workspace, home),
                strictMcp = false,
                launcher = customLauncher,
                protectedWriteDirs = { listOf(HoldoutFolder(internalDir, home)) },
                readOnly = true,
            )

        fun declined(
            toolCall: JsonObject,
            reason: String,
        ) {
            val answer = assertIs<AcpPermissionPolicy.Answer.Write>(readOnly.answer(permissionRequest(toolCall = toolCall)))
            assertEquals("opt-reject-once", outcomeOf(answer)["optionId"]!!.jsonPrimitive.content, answer.note)
            assertTrue(reason in answer.note, answer.note)
        }

        declined(buildJsonObject { put("name", "mcp_github_push") }, AcpPermissionPolicy.READ_ONLY)
        declined(buildJsonObject { put("name", "mcp__loop__move") }, AcpPermissionPolicy.READ_ONLY)
        declined(
            buildJsonObject {
                put("name", "write_file")
                put("kind", "edit")
                put("rawInput", buildJsonObject { put("Path", internalDir.resolve("settings.json").toString()) })
            },
            AcpPermissionPolicy.INTERNAL_LOOP_DIR,
        )
        declined(
            buildJsonObject {
                put("name", "web_fetch")
                put("kind", "fetch")
                put("rawInput", buildJsonObject { put("url", "https://example.com") })
            },
            AcpPermissionPolicy.UNSUPPORTED_TOOL,
        )
        declined(
            buildJsonObject {
                put("name", "write_file")
                put("kind", "think")
                put("rawInput", buildJsonObject { put("Path", workspace.resolve("notes.md").toString()) })
            },
            AcpPermissionPolicy.READ_ONLY,
        )

        val think = readOnly.answer(permissionRequest(toolCall = buildJsonObject { put("kind", "think") }))
        assertEquals("opt-allow-once", outcomeOf(assertIs(think))["optionId"]!!.jsonPrimitive.content)
    }

    /**
     * A `session/request_permission` frame of the shape agy 1.5.18 sends for a file tool called by URI: the recorded
     * frame envelope, `args` and `rawInput` carrying the `Path` as given, and `locations[].path`. Each path is optional
     * so a test can put a `file:` URI in one place only.
     */
    private fun agyFileFrame(
        name: String,
        kind: String,
        locationPath: String? = null,
        argsPath: String? = null,
        rawInputPath: String? = null,
        rawInputPaths: List<String>? = null,
    ): AcpFrame.Request {
        val input =
            buildJsonObject {
                if (argsPath != null) put("Path", argsPath)
                put("toolSummary", "Read the file")
            }
        val raw =
            buildJsonObject {
                if (rawInputPath != null) put("Path", rawInputPath)
                if (rawInputPaths != null) put("paths", buildJsonArray { rawInputPaths.forEach { add(JsonPrimitive(it)) } })
                put("toolSummary", "Read the file")
            }
        val frame =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", AcpHandshake.METHOD_REQUEST_PERMISSION)
                put(
                    "params",
                    buildJsonObject {
                        put("sessionId", "49b1bcf6-00cb-402b-bd98-732c89023e5e")
                        put(
                            "toolCall",
                            buildJsonObject {
                                put("sessionUpdate", "tool_call")
                                put("toolCallId", "call_15852867417007420952")
                                put("name", name)
                                put("args", input)
                                put("status", "pending")
                                put("kind", kind)
                                put("title", "Read the file")
                                put("rawInput", raw)
                                if (locationPath != null) {
                                    put("locations", buildJsonArray { add(buildJsonObject { put("path", locationPath) }) })
                                }
                            },
                        )
                        put(
                            "options",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("optionId", "allow")
                                        put("name", "Allow")
                                        put("kind", "allow_once")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("optionId", "deny")
                                        put("name", "Deny")
                                        put("kind", "reject_once")
                                    },
                                )
                            },
                        )
                    },
                )
                put("id", "1")
            }
        return assertIs<AcpFrame.Request>(AcpFraming.decode(frame.toString()))
    }

    private fun fileUri(path: Path): String = "file://" + path.toAbsolutePath().toString()

    @Test
    fun `file URIs in locations path, rawInput and args path keys under the holdout are declined`() {
        val secret = holdoutDir.resolve("secret.txt").toString()
        val spaced = holdoutDir.resolve("has space").createDirectories().resolve("secret.txt")
        val uris =
            mapOf(
                "plain" to "file://$secret",
                "localhost" to "file://localhost$secret",
                "percent encoded" to "file://" + spaced.toString().replace(" ", "%20"),
                "percent encoded letters" to "file://" + secret.replace("secret", "%73%65cret"),
                "single slash" to "file:$secret",
                "upper case scheme" to "FILE://$secret",
            )
        for ((label, uri) in uris) {
            for ((name, kind) in listOf("read_file" to "read", "write_file" to "edit")) {
                val frames =
                    mapOf(
                        "locations[].path" to agyFileFrame(name, kind, locationPath = uri),
                        "args.Path" to agyFileFrame(name, kind, argsPath = uri),
                        "rawInput.Path" to agyFileFrame(name, kind, rawInputPath = uri),
                        "rawInput.paths" to agyFileFrame(name, kind, rawInputPaths = listOf("README.md", uri)),
                    )
                for ((where, frame) in frames) {
                    val context = "$label $name $where"
                    val write = assertIs<AcpPermissionPolicy.Answer.Write>(policy().answer(frame), context)
                    assertEquals("deny", outcomeOf(write)["optionId"]!!.jsonPrimitive.content, context)
                    assertTrue(AcpPermissionPolicy.HOLDOUT in write.note, "$context: ${write.note}")
                }
            }
        }
    }

    @Test
    fun `the agy 1_5_18 frame shape with the URI only in args and rawInput is declined, and a workspace URI is allowed`() {
        val uri = fileUri(holdoutDir.resolve("secret.txt"))
        val outside =
            agyFileFrame("read_file", "read", locationPath = workspace.resolve("README.md").toString(), argsPath = uri, rawInputPath = uri)
        val deny = assertIs<AcpPermissionPolicy.Answer.Write>(policy().answer(outside))
        assertEquals("deny", outcomeOf(deny)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.HOLDOUT in deny.note, deny.note)

        val inside = fileUri(workspace.resolve("README.md"))
        val allowed =
            agyFileFrame(
                "read_file",
                "read",
                locationPath = workspace.resolve("README.md").toString(),
                argsPath = inside,
                rawInputPath = inside,
            )
        val allow = assertIs<AcpPermissionPolicy.Answer.Write>(policy().answer(allowed))
        assertEquals("allow", outcomeOf(allow)["optionId"]!!.jsonPrimitive.content, allow.note)
    }

    @Test
    fun `a file URI under a protected workspace root is declined for writes`() {
        val uri = fileUri(workspace.resolve(".agents/hooks.json"))
        val write = assertIs<AcpPermissionPolicy.Answer.Write>(policy().answer(agyFileFrame("write_file", "edit", argsPath = uri)))
        assertEquals("deny", outcomeOf(write)["optionId"]!!.jsonPrimitive.content)
        assertTrue(AcpPermissionPolicy.PROTECTED_ROOT in write.note, write.note)
    }

    @Test
    fun `another URI scheme, a remote file host or broken percent encoding fails closed naming the problem`() {
        val cases =
            mapOf(
                "https://example.com/README.md" to "URI scheme https",
                "vscode-remote://ssh/x/README.md" to "URI scheme vscode-remote",
                "file://otherhost/tmp/README.md" to "file URI host",
                "file:///tmp/%zzREADME.md" to "percent encoding",
                "file://" to "file URI",
            )
        for ((value, expected) in cases) {
            for (frame in listOf(
                agyFileFrame("read_file", "read", argsPath = value),
                agyFileFrame("read_file", "read", locationPath = value),
            )) {
                val write = assertIs<AcpPermissionPolicy.Answer.Write>(policy().answer(frame), value)
                assertEquals("deny", outcomeOf(write)["optionId"]!!.jsonPrimitive.content, value)
                assertTrue(expected in write.note, "$value: ${write.note}")
                assertTrue("example.com" !in write.note && "otherhost" !in write.note, "$value: ${write.note}")
            }
        }
    }
}
