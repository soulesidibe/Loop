package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * `loop log [path-to-WORKFLOW.md] [--follow]`: the last [TAIL_LINES] lines of the project's human readable
 * run log (m1-39), then with `--follow` every line appended after, until interrupted. The project id comes
 * from the workflow as for the store. The log was redacted when it was written, so it prints as is.
 */
class LogCommand(
    private val host: Host,
) : GuardedCommand("log", host) {
    private val workflowPath by argument(
        name = "path-to-WORKFLOW.md",
        help = "The workflow file, default ./WORKFLOW.md",
    ).optional()
    private val follow by option("--follow", help = "Keep printing new lines until interrupted").flag()

    override fun help(context: Context): String = "Print the human readable run log of the project"

    override fun body(scope: RedactorScope) {
        val project = host.openProject(workflowPath ?: DEFAULT_WORKFLOW, Access.READ, scope)
        val file = humanLogFile(host.loopHome, project.projectId)
        if (!file.exists() && !follow) {
            echo("No human log yet for this project, loop run writes it to $file.")
            return
        }
        var offset = if (file.exists()) printTail(file) else 0L
        while (follow) {
            Thread.sleep(FOLLOW_POLL_MS)
            offset = printFrom(file, offset)
        }
    }

    /** Prints the last [TAIL_LINES] lines of [file]; returns the offset of its end as read. */
    private fun printTail(file: Path): Long {
        val bytes = Files.readAllBytes(file)
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        String(bytes, 0, complete, Charsets.UTF_8)
            .lines()
            .dropLast(1)
            .takeLast(TAIL_LINES)
            .forEach { echo(it) }
        return complete.toLong()
    }

    /** Prints the complete lines [file] holds after [offset]; returns the new offset. A shorter file starts over. */
    private fun printFrom(
        file: Path,
        offset: Long,
    ): Long {
        if (!file.exists()) return 0L
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val start = if (raf.length() < offset) 0L else offset
            if (raf.length() == start) return start
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
            if (complete == 0) return start
            String(bytes, 0, complete, Charsets.UTF_8).lines().dropLast(1).forEach { echo(it) }
            return start + complete
        }
    }

    private companion object {
        const val DEFAULT_WORKFLOW = "WORKFLOW.md"
        const val TAIL_LINES = 50
        const val FOLLOW_POLL_MS = 200L
    }
}
