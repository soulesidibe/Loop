package loop.runner.acp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import loop.api.AttemptContext
import loop.api.AttemptId
import loop.api.Issue
import loop.api.ProjectId
import loop.api.RunId
import loop.api.RunnerEvent
import loop.api.RunnerEvent.Output.Stream
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AcpRunnerTimeoutTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `missing handshake response within read_timeout_ms kills server and fails with response_timeout`() =
        runTest {
            var cancelled = false
            val runner =
                runner(readTimeout = 5.seconds, turnTimeout = 60.seconds) { spec ->
                    channelFlow {
                        send(ProcessEvent.Started(1))
                        try {
                            spec.stdinLines!!.receive()
                            delay(60.seconds)
                        } finally {
                            cancelled = true
                        }
                    }
                }

            val events = runner.run(context(), "Work").toList()

            val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
            assertEquals("response_timeout: no initialize response within 5000 ms", failed.reason)
            assertNull(failed.exitCode)
            assertTrue(cancelled)
        }

    @Test
    fun `silent prompt turn fails with turn_timeout while protocol messages reset it and stderr does not`() =
        runTest {
            val runner =
                runner(readTimeout = 5.seconds, turnTimeout = 10.seconds) { spec ->
                    channelFlow {
                        send(ProcessEvent.Started(1))
                        val stdin = spec.stdinLines!!
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(1), buildJsonObject { put("protocolVersion", 1) })))
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(2), buildJsonObject { put("sessionId", "s-1") })))
                        stdin.receive()
                        // Valid notification after 6s resets the 10s silence timer.
                        delay(6.seconds)
                        send(
                            stdoutLine(
                                AcpFraming.notification(
                                    AcpHandshake.METHOD_SESSION_UPDATE,
                                    buildJsonObject { put("sessionId", "s-1") },
                                ),
                            ),
                        )
                        // Stderr and malformed stdout at +6s do NOT reset the silence timer.
                        delay(6.seconds)
                        send(ProcessEvent.Line(Stream.Stderr, "still thinking"))
                        send(ProcessEvent.Line(Stream.Stdout, "not-json"))
                        delay(60.seconds)
                    }
                }

            val events = runner.run(context(), "Work").toList()

            val failed = assertIs<RunnerEvent.TurnFailed>(events.last())
            assertEquals("turn_timeout: no protocol message for 10000 ms", failed.reason)
            assertNull(failed.exitCode)
        }

    @Test
    fun `idle server between turns does not time out and stops cleanly when exiting within stopGrace`() =
        runTest {
            val runner =
                runner(readTimeout = 5.seconds, turnTimeout = 10.seconds, stopGrace = 5.seconds) { spec ->
                    channelFlow {
                        send(ProcessEvent.Started(1))
                        val stdin = spec.stdinLines!!
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(1), buildJsonObject { put("protocolVersion", 1) })))
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(2), buildJsonObject { put("sessionId", "s-idle") })))
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(3), buildJsonObject { put("stopReason", "end_turn") })))
                        // Wait for turn 2 across a long idle gap.
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(4), buildJsonObject { put("stopReason", "end_turn") })))
                        // Wait until stdin closes, then exit cleanly.
                        for (ignored in stdin) continue
                        send(ProcessEvent.Exited(0))
                    }
                }

            val session = runner.openSession(context())
            val turn1 = session.runTurn("Turn 1", 1).toList()
            assertIs<RunnerEvent.TurnCompleted>(turn1.last())

            delay(30.seconds)

            val turn2 = session.runTurn("Turn 2", 2).toList()
            assertIs<RunnerEvent.TurnCompleted>(turn2.last())

            val stopEvents = mutableListOf<RunnerEvent>()
            session.stop { stopEvents += it }
            assertEquals(emptyList(), stopEvents)
        }

    @Test
    fun `stop(report) emits StopGraceExpired when server ignores stdin EOF past stopGrace`() =
        runTest {
            val runner =
                runner(readTimeout = 5.seconds, turnTimeout = 10.seconds, stopGrace = 5.seconds) { spec ->
                    channelFlow {
                        send(ProcessEvent.Started(1))
                        val stdin = spec.stdinLines!!
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(1), buildJsonObject { put("protocolVersion", 1) })))
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(2), buildJsonObject { put("sessionId", "s-grace") })))
                        stdin.receive()
                        send(stdoutLine(AcpFraming.response(JsonPrimitive(3), buildJsonObject { put("stopReason", "end_turn") })))
                        delay(60.seconds)
                    }
                }

            val session = runner.openSession(context())
            val turn1 = session.runTurn("Turn 1", 1).toList()
            val started = assertIs<RunnerEvent.SessionStarted>(turn1.first())

            val stopEvents = mutableListOf<RunnerEvent>()
            session.stop { stopEvents += it }

            val expired = assertIs<RunnerEvent.StopGraceExpired>(stopEvents.single())
            assertEquals(started.sessionId, expired.sessionId)
            assertEquals(started.turnId, expired.turnId)
            assertEquals(5_000L, expired.graceMs)
        }

    private fun TestScope.runner(
        readTimeout: Duration,
        turnTimeout: Duration,
        stopGrace: Duration = AcpRunner.STOP_GRACE,
        launch: (LaunchSpec) -> Flow<ProcessEvent>,
    ): AcpRunner =
        AcpRunner(
            settings =
                AcpSettings(
                    command = "fake-acp",
                    strictMcp = false,
                    readTimeout = readTimeout,
                    turnTimeout = turnTimeout,
                ),
            hostEnv = emptyMap(),
            removedEnvNames = emptySet(),
            addedEnv = emptyMap(),
            launchProcess = launch,
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
            stopGrace = stopGrace,
        )

    private fun stdoutLine(encoded: String): ProcessEvent.Line = ProcessEvent.Line(Stream.Stdout, encoded.trimEnd('\n'))

    private fun context(): AttemptContext =
        AttemptContext(
            runId = RunId("run-1"),
            projectId = ProjectId("project-1"),
            issue =
                Issue(
                    id = "issue-1",
                    identifier = "acp-02",
                    title = "Timeout test",
                    state = "todo",
                    priority = null,
                    labels = emptyList(),
                    blockedBy = emptyList(),
                    description = null,
                    createdAt = null,
                ),
            attemptId = AttemptId("attempt-1"),
            workspace = workspace,
            env = emptyMap(),
        )
}
