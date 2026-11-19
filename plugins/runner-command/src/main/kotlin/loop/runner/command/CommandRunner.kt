package loop.runner.command

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import loop.api.AgentRunner
import loop.api.AgentSession
import loop.api.AttemptContext
import loop.api.RunnerCapabilities
import loop.api.RunnerEvent
import loop.api.SessionId
import loop.api.TurnId
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import loop.runner.process.agentEnvironment
import loop.runner.process.resolveAddedEnv
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [command] with `sh -c` in the attempt workspace (not a login shell, so
 * PATH is kept). The prompt is written on stdin then closed; stdout and stderr
 * lines are emitted as [RunnerEvent.Output]. The script env follows the shared
 * [agentEnvironment] policy: [hostEnv] minus [removedEnvNames], plus [addedEnv] (`agent.env`,
 * each `{workspace}` replaced by the attempt's workspace), plus the context env and
 * `LOOP_SESSION_ID`, `LOOP_THREAD_ID`, `LOOP_TURN_ID`, `LOOP_TURN_NUMBER` (Loop's own
 * names survive removal), always minus `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN`. A session ([openSession]) re-invokes the command for each
 * turn with the same thread id, a new turn id and the turn's input on stdin.
 *
 * The script runs in its own process group through [ProcessGroupLauncher], which
 * describes how the group is started and cleaned up. Failure reasons follow the spec 10.6
 * categories: if the process cannot be started the turn fails with
 * `agent_not_found: <command>: <message>`; a nonzero exit fails it with
 * `turn_failed: exit code N`.
 *
 * Terminal signals: Ctrl-C in the terminal does not reach the script, only this
 * runner's cancel or exit path stops it. `loop run` cancels the attempt from its
 * SIGINT and SIGTERM shutdown hook and waits up to 10 s for this cleanup to end
 * before the JVM exits (RunCommand `untilSignal`). A SIGKILL of the JVM skips
 * every hook, so the script's group survives it: a known limit.
 */
class CommandRunner internal constructor(
    private val command: String,
    private val launcher: ProcessGroupLauncher,
    private val hostEnv: Map<String, String>,
    private val removedEnvNames: Set<String>,
    private val addedEnv: Map<String, String>,
) : AgentRunner {
    constructor(
        command: String,
        gracePeriod: Duration = 2.seconds,
        drainTimeout: Duration = 500.milliseconds,
        hostEnv: Map<String, String> = System.getenv(),
        removedEnvNames: Set<String> = emptySet(),
        addedEnv: Map<String, String> = emptyMap(),
    ) : this(command, ProcessGroupLauncher(gracePeriod, drainTimeout), hostEnv, removedEnvNames, addedEnv)

    internal constructor(
        command: String,
        gracePeriod: Duration,
        drainTimeout: Duration,
        signaller: GroupSignaller,
        warn: (String) -> Unit = { line -> System.err.println(line) },
        hostEnv: Map<String, String> = System.getenv(),
        removedEnvNames: Set<String> = emptySet(),
    ) : this(
        command,
        ProcessGroupLauncher(gracePeriod, drainTimeout, signaller.toProcessSignaller(), warn),
        hostEnv,
        removedEnvNames,
        emptyMap(),
    )

    /**
     * Continuation (one invocation per turn), Loop's tools as files and the command line (`loop tool`, the result
     * file), and nothing else: a script reports no usage, no cost and no occupancy, and runs unconfined.
     */
    override val capabilities: RunnerCapabilities = CAPABILITIES

    override fun run(
        ctx: AttemptContext,
        prompt: String,
    ): Flow<RunnerEvent> = turn(ctx, prompt, threadId = UUID.randomUUID().toString(), turn = 1)

    override fun openSession(ctx: AttemptContext): AgentSession = CommandSession(ctx, UUID.randomUUID().toString())

    /** One thread: every turn re-invokes the command under the same `LOOP_THREAD_ID`; nothing is held between turns. */
    private inner class CommandSession(
        private val ctx: AttemptContext,
        private val threadId: String,
    ) : AgentSession {
        override fun runTurn(
            input: String,
            turn: Int,
        ): Flow<RunnerEvent> = turn(ctx, input, threadId, turn)

        override suspend fun stop() = Unit
    }

    private fun turn(
        ctx: AttemptContext,
        input: String,
        threadId: String,
        turn: Int,
    ): Flow<RunnerEvent> =
        flow {
            val turnId = TurnId(UUID.randomUUID().toString())
            val sessionId = SessionId("$threadId-${turnId.value}")
            val ids =
                mapOf(
                    ENV_SESSION_ID to sessionId.value,
                    ENV_THREAD_ID to threadId,
                    ENV_TURN_ID to turnId.value,
                    ENV_TURN_NUMBER to turn.toString(),
                )
            val spec =
                LaunchSpec(
                    argv = listOf("sh", "-c", command),
                    cwd = ctx.workspace,
                    environment = agentEnvironment(ctx.env + ids, removedEnvNames, hostEnv, resolveAddedEnv(addedEnv, ctx.workspace)),
                    stdin = input,
                )
            launcher.launch(spec).collect { event ->
                when (event) {
                    is ProcessEvent.SpawnFailed -> {
                        emit(RunnerEvent.TurnFailed(sessionId, turnId, exitCode = null, reason = agentNotFound(event.message)))
                    }
                    is ProcessEvent.Started ->
                        emit(
                            if (turn == 1) {
                                RunnerEvent.SessionStarted(sessionId, threadId, turnId)
                            } else {
                                RunnerEvent.TurnStarted(sessionId, threadId, turnId, turn)
                            },
                        )
                    is ProcessEvent.Line -> emit(RunnerEvent.Output(event.stream, event.text))
                    is ProcessEvent.Exited -> emit(turnResult(sessionId, turnId, event.exitCode))
                }
            }
        }

    private fun turnResult(
        sessionId: SessionId,
        turnId: TurnId,
        exitCode: Int,
    ): RunnerEvent =
        if (exitCode == 0) {
            RunnerEvent.TurnCompleted(sessionId, turnId, exitCode)
        } else {
            RunnerEvent.TurnFailed(sessionId, turnId, exitCode, reason = "turn_failed: exit code $exitCode")
        }

    private fun agentNotFound(message: String): String = "agent_not_found: $command: ${message.trimEnd('.')}"

    private companion object {
        const val ENV_SESSION_ID = "LOOP_SESSION_ID"
        const val ENV_THREAD_ID = "LOOP_THREAD_ID"
        const val ENV_TURN_ID = "LOOP_TURN_ID"
        const val ENV_TURN_NUMBER = "LOOP_TURN_NUMBER"

        val CAPABILITIES =
            RunnerCapabilities(
                continuation = true,
                loopTools = RunnerCapabilities.LoopTools.Files,
                usage = false,
                cost = false,
                occupancy = false,
                sandbox = false,
            )
    }
}

/*
 * The group signalling lives in :plugins:runner-process. The types below keep the test seam
 * of CommandRunnerTest in this package; a typealias cannot reach the nested result classes,
 * so the result is mirrored and adapted.
 */

/** What sending a signal to a process group found, see [loop.runner.process.GroupSignalResult]. */
internal sealed interface GroupSignalResult {
    data object Members : GroupSignalResult

    data object Empty : GroupSignalResult

    data class Failed(
        val message: String,
    ) : GroupSignalResult
}

/** Sends a signal to every process of a group, see [loop.runner.process.GroupSignaller]. */
internal fun interface GroupSignaller {
    fun signal(
        pgid: Long,
        signal: String,
    ): GroupSignalResult
}

/** [loop.runner.process.ShellGroupSignaller] seen through this package's types. */
internal object ShellGroupSignaller : GroupSignaller {
    override fun signal(
        pgid: Long,
        signal: String,
    ): GroupSignalResult =
        when (
            val result =
                loop.runner.process.ShellGroupSignaller
                    .signal(pgid, signal)
        ) {
            loop.runner.process.GroupSignalResult.Members -> GroupSignalResult.Members
            loop.runner.process.GroupSignalResult.Empty -> GroupSignalResult.Empty
            is loop.runner.process.GroupSignalResult.Failed -> GroupSignalResult.Failed(result.message)
        }
}

private fun GroupSignaller.toProcessSignaller(): loop.runner.process.GroupSignaller =
    loop.runner.process.GroupSignaller { pgid, signal ->
        when (val result = signal(pgid, signal)) {
            GroupSignalResult.Members -> loop.runner.process.GroupSignalResult.Members
            GroupSignalResult.Empty -> loop.runner.process.GroupSignalResult.Empty
            is GroupSignalResult.Failed ->
                loop.runner.process.GroupSignalResult
                    .Failed(result.message)
        }
    }
