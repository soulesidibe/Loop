package loop.resource.ios

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import loop.api.ResourceException
import loop.api.RunnerEvent.Output.Stream
import loop.runner.process.LaunchSpec
import loop.runner.process.ProcessEvent
import loop.runner.process.ProcessGroupLauncher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Runs `xcrun simctl` (m4-27): `xcrun` found on the `PATH` of [environment], the whole environment the process gets,
 * in its own process group through the shared launcher. Every call has a timeout, which kills the group, and a failure
 * is a [ResourceException] naming the command and its exit code, or the timeout.
 */
internal class Xcrun(
    private val environment: Map<String, String>,
    private val launcher: ProcessGroupLauncher = ProcessGroupLauncher(),
    private val cwd: Path = Path.of(System.getProperty("java.io.tmpdir")),
) {
    /** True when an executable `xcrun` is on the `PATH` of [environment]. */
    fun available(): Boolean =
        environment["PATH"]
            .orEmpty()
            .split(':')
            .filter { it.isNotEmpty() }
            .any { Files.isExecutable(Path.of(it, "xcrun")) }

    /** The simulators of the machine, from `xcrun simctl list devices -j`, every runtime together. */
    fun devices(timeout: Duration): List<Simulator> {
        val args = listOf("simctl", "list", "devices", "-j")
        val text = run(args, timeout)
        val listed =
            try {
                JSON.decodeFromString<DeviceList>(text)
            } catch (e: SerializationException) {
                throw ResourceException("${shown(args)} printed a list Loop cannot read: ${e.message?.lineSequence()?.first()}", e)
            } catch (e: IllegalArgumentException) {
                throw ResourceException("${shown(args)} printed a list Loop cannot read: ${e.message?.lineSequence()?.first()}", e)
            }
        return listed.devices.values.flatten()
    }

    /** Runs `xcrun` with [args] and returns its stdout, or fails once [timeout] passed or when it exits other than 0. */
    fun run(
        args: List<String>,
        timeout: Duration,
    ): String {
        val command = shown(args)
        if (!timeout.isPositive()) throw ResourceException("$command timed out after the prepare timeout")
        val stdout = StringBuilder()
        val stderr = mutableListOf<String>()
        var exitCode: Int? = null
        var spawnFailed: String? = null
        val finished =
            runBlocking {
                withTimeoutOrNull(timeout) {
                    launcher.launch(LaunchSpec(listOf("xcrun") + args, cwd, environment)).collect { event ->
                        when (event) {
                            is ProcessEvent.Line ->
                                if (event.stream ==
                                    Stream.Stdout
                                ) {
                                    stdout.appendLine(event.text)
                                } else {
                                    stderr += event.text
                                }
                            is ProcessEvent.Exited -> exitCode = event.exitCode
                            is ProcessEvent.SpawnFailed -> spawnFailed = event.message
                            is ProcessEvent.Started -> Unit
                        }
                    }
                } != null
            }
        if (!finished) throw ResourceException("$command timed out after $timeout")
        spawnFailed?.let { throw ResourceException("cannot run $command: $it") }
        if (exitCode != 0) {
            val reason = stderr.lastOrNull { it.isNotBlank() }?.trim()?.take(MAX_REASON)
            throw ResourceException("$command exited $exitCode" + (reason?.let { ": $it" } ?: ""))
        }
        return stdout.toString()
    }

    private companion object {
        const val MAX_REASON = 200

        val JSON = Json { ignoreUnknownKeys = true }

        /** The command as a person would type it, an argument holding a blank in quotes. */
        fun shown(args: List<String>): String =
            (listOf("xcrun") + args).joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
    }
}

/** One simulator of `xcrun simctl list devices -j`: its [udid], its [name] and its [state], such as `Booted` or `Shutdown`. */
@Serializable
internal data class Simulator(
    val udid: String,
    val name: String,
    val state: String,
) {
    val shutDown: Boolean get() = state == SHUTDOWN

    override fun toString(): String = "$name ($udid)"

    companion object {
        const val SHUTDOWN = "Shutdown"
    }
}

@Serializable
private data class DeviceList(
    val devices: Map<String, List<Simulator>> = emptyMap(),
)
