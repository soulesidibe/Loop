package loop.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import loop.api.Redactor
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

class LoopCommand(
    host: Host = Host.current(),
) : CliktCommand(name = "loop") {
    init {
        subcommands(
            RunCommand(host),
            StopCommand(host),
            DrainCommand(host),
            StatusCommand(host),
            TraceCommand(host),
            ShowCommand(host),
            LogCommand(host),
            ResumeCommand(host),
            ApproveCommand(host),
            ToolCommand(host),
            InstallCommand(host),
        )
        versionOption(host.loopVersion, help = "Print the version of this build and exit", message = { "loop $it" })
    }

    override fun help(context: Context): String = "Loop, an issue execution harness"

    override fun run() = Unit
}

/** `loop tool <subcommand>`: commands an agent calls from inside an attempt. */
class ToolCommand(
    host: Host,
) : CliktCommand(name = "tool") {
    init {
        subcommands(ToolMoveCommand(host), ToolCommentCommand(host), ToolMcpCommand(host))
    }

    override fun help(context: Context): String = "Commands for the agent process of an attempt"

    override fun run() = Unit
}

/** Usage errors exit 2 for every command (plan U2), instead of Clikt's default 1. */
const val EXIT_USAGE: Int = 2

fun main(args: Array<String>) {
    runMain(args, System.getenv(), ::exitProcess, System.err::println) { LoopCommand(Host.current()) }
}

/**
 * [main] with its machine seams: the [env], [exit] and [err]. The command is built inside the try, so a
 * failure of [Host] or of a command's construction is covered too. Anything a command did not handle
 * (m1-26) leaves as one redacted line and exit 1. [GuardedCommand] reports the failures of a running
 * leaf command with its own redactor; this fallback only knows the Anthropic keys, the token patterns
 * and the tracker secrets of a best effort read of `LOOP_WORKFLOW` or `./WORKFLOW.md`. Inside an attempt
 * (`LOOP_TOOL_CONFIG` set) it reads no workflow: the Anthropic keys and the token patterns only.
 */
internal fun runMain(
    args: Array<String>,
    env: Map<String, String>,
    exit: (Int) -> Unit,
    err: (String) -> Unit,
    workingDir: Path = Path("").toAbsolutePath().normalize(),
    build: () -> CliktCommand,
) {
    try {
        val command = build()
        try {
            command.parse(args)
        } catch (e: CliktError) {
            command.echoFormattedHelp(e)
            exit(if (e is UsageError) EXIT_USAGE else e.statusCode)
        }
    } catch (e: Exception) {
        // A malformed LOOP_WORKFLOW must not hide the original error: fall back to the redactor without the workflow token.
        // Inside an attempt (LOOP_TOOL_CONFIG set, m1-23) no credential is reachable and no workflow is read.
        val redactor =
            if (!env["LOOP_TOOL_CONFIG"].isNullOrBlank()) {
                fallbackRedactor(env)
            } else {
                workflowRedactor(env, workingDir)
            }
        err("${commandPathOf(args)}: internal error: ${redactor.redact(e.sentence())}")
        exit(EXIT_INTERNAL_ERROR)
    }
}

/** The fallback redactor of a best effort read of `LOOP_WORKFLOW` or `./WORKFLOW.md`, else the plain fallback. */
private fun workflowRedactor(
    env: Map<String, String>,
    workingDir: Path,
): Redactor =
    runCatching {
        val path = workingDir.resolve(env["LOOP_WORKFLOW"]?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKFLOW)
        val home = Path(env["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"))
        startupRedactor(path, workflowLoader(env, home), env)
    }.getOrElse { fallbackRedactor(env) }

/** `loop` followed by the leading subcommand names of [args], for an error that happens before a command is known. */
internal fun commandPathOf(args: Array<String>): String {
    val first = args.firstOrNull()?.takeIf { it in SUBCOMMAND_NAMES } ?: return "loop"
    // Only `tool` has subcommands: `loop trace run` is `loop trace`, `loop move` is `loop`.
    val second = args.getOrNull(1)?.takeIf { first == "tool" && it in TOOL_SUBCOMMANDS }
    return listOfNotNull("loop", first, second).joinToString(" ")
}

internal val SUBCOMMAND_NAMES = setOf("run", "stop", "drain", "status", "trace", "show", "log", "resume", "approve", "tool", "install")

internal val TOOL_SUBCOMMANDS = setOf("move", "comment", "mcp")

private const val DEFAULT_WORKFLOW = "WORKFLOW.md"
