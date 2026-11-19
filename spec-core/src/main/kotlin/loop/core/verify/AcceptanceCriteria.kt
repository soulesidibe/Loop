package loop.core.verify

import loop.core.kinds.MarkdownSections
import java.security.MessageDigest

/**
 * One acceptance criterion of an issue (m2-09): its [text], continuation lines joined with a space, and the
 * [command] that checks it, null for a judgment call, which no check passes.
 */
data class AcceptanceCriterion(
    val text: String,
    val command: String?,
)

/** The commands of the criteria that have one, in order: each is run as a check. */
val List<AcceptanceCriterion>.commands: List<String> get() = mapNotNull { it.command }

/** The text of the criteria without a command, in order: the judgment calls left to a human. */
val List<AcceptanceCriterion>.judgmentCalls: List<String> get() = filter { it.command == null }.map { it.text }

/**
 * Reads the acceptance criteria of an issue description (m2-09), the convention of
 * `docs/agents/issue-tracker.md`. The criteria are the list items of the first section whose heading opens
 * with `Acceptance criteria` ([MarkdownSections.sectionsOpeningWith]): a column 0 `-`, `*`, `+`, `1.` or `1)`
 * item, with or without a `[ ]` or `[x]` box, and the indented lines that follow it, a nested item included.
 * Prose, thematic breaks such as `- - -`, column 0 fenced blocks and headings inside the section are not
 * criteria; fences are found at column 0, like the headings of [MarkdownSections]. Only the first section
 * counts, so a heading quoted in a later comment never adds a command.
 *
 * A criterion is runnable when its first line opens with one backticked span followed by the word `passes`,
 * as in `` `./gradlew build` passes. ``, and the span looks like a command: it holds a space, or starts with
 * `./`, `/` or a path segment such as `scripts/`. Any other criterion, a backticked bare name such as
 * `` `TraceCommandTest` passes `` included, is a judgment call.
 */
object AcceptanceCriteria {
    /** The section that holds the criteria, the one the readiness check of Loop's own kind requires. */
    const val SECTION = "Acceptance criteria"

    /**
     * The `unverified` reason, and the `error` of the `issue_parked` event, of an issue whose criteria differ from
     * the list frozen at its first dispatch: the one park whose changed list `loop resume` accepts.
     */
    const val CHANGED_REASON = "acceptance commands changed"

    /**
     * The digest of a command list, as the store keeps it (m2-09): SHA-256 over the count and each command with
     * its length, so no two lists share a digest by joining. The commands never leave Loop: one can hold a secret.
     */
    fun digest(commands: List<String>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update("${commands.size}\n".toByteArray())
        for (command in commands) {
            val bytes = command.toByteArray()
            sha.update("${bytes.size}:".toByteArray())
            sha.update(bytes)
        }
        return "sha256:" + sha.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(description: String?): List<AcceptanceCriterion> {
        val lines = description.orEmpty().lines()
        val section = MarkdownSections.sectionsOpeningWith(lines, SECTION).firstOrNull() ?: return emptyList()
        val items = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        var fence: Char? = null
        for (index in section) {
            val line = lines[index]
            val marker = MarkdownSections.fenceMarker(line)
            when {
                fence != null -> if (marker == fence) fence = null
                marker != null -> {
                    fence = marker
                    current = null
                }
                line.isBlank() -> Unit
                RULE.matches(line) -> current = null
                ITEM.matchEntire(line) != null -> {
                    current = mutableListOf(ITEM.matchEntire(line)!!.groupValues[1].trim()).also { items += it }
                }
                line.first().isWhitespace() -> current?.add(line.trim())
                else -> current = null
            }
        }
        return items.map { parts ->
            val command =
                COMMAND
                    .matchEntire(parts.first())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.takeIf { SHAPE.containsMatchIn(it) }
            AcceptanceCriterion(parts.filter { it.isNotEmpty() }.joinToString(" "), command)
        }
    }

    private val ITEM = Regex("""(?:[-*+]|\d+[.)])[ \t]+(?:\[[ xX]](?:[ \t]+|$))?(.*)""")
    private val COMMAND = Regex("""`([^`]*)`[ \t]+passes(?![\w-]).*""")

    /** A thematic break such as `---`, `- - -`, `* * *` or `___`, which is a rule, never a criterion. */
    private val RULE = Regex("""[ ]{0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*""")

    /** What a command looks like: a blank inside it, or a start of `./`, `/` or a path segment and a `/`. */
    private val SHAPE = Regex("""\s|^\.{0,2}/|^[^\s/]+/""")
}
