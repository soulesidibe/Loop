package loop.core.verify

import loop.api.Redactor
import loop.core.workflow.TokenCounter
import loop.core.workflow.TokenCounters

/**
 * What a refuted verification tells the agent (m2-06, story 73): the failed check, its exit code and the last
 * lines of its output, redacted and bounded in size. The same [text] is the extra paragraph of the next turn's
 * continuation guidance and the template variable `loop.verify_findings` of the next attempt. The full output
 * stays in the evidence file (plan 8.7: the model gets the exit code and a few lines). Since m3-14 the bound is
 * `context.tool_output_tokens`, counted by the caller's counter, instead of a number of characters.
 */
class VerifyFindings private constructor(
    val text: String,
) {
    override fun toString(): String = text

    companion object {
        /** At most this many trailing output lines, the few lines of plan 8.7. */
        const val OUTPUT_LINES = 20

        /** A longer output line is cut to this many characters. */
        const val LINE_MAX = 200

        /** The command is cut to this many characters, like in the check events. */
        const val COMMAND_MAX = 200

        /** The bound of the whole text when the caller gives none, the default of `context.tool_output_tokens`. */
        const val TOOL_OUTPUT_TOKENS = 2_000

        /** Only the end of the output is read: a check may print up to 16 MiB of evidence. */
        private const val TAIL_READ = 64 * 1024

        /** The whole text of the findings of a failed holdout check (m2-14). */
        const val HIDDEN =
            "Your last result was refuted: Loop ran its checks and a hidden check failed, so the issue did not move. " +
                "Hidden checks stay hidden: their command, content and output are never shown. " +
                "Check the work against every acceptance criterion of the issue, then move the issue again."

        const val LEAD =
            "Your last result was refuted: Loop ran its checks and one failed, so the issue did not move. " +
                "Fix what the check found without changing or skipping it, then move the issue again."

        const val UNCOMMITTED_LEAD =
            "Your last result was refuted: every check passed, but the work tree holds files that are not committed, " +
                "and a pull request carries only commits, so the issue did not move. Commit what belongs to the work, " +
                "restore or remove what does not, such as files a check or a build regenerates, then move the issue again."

        /** m4-21: the start of the findings of a result with no file matching the kind's `deliverable.files`. */
        const val NO_DELIVERABLE_LEAD =
            "Your last result was refuted: no deliverable file, every check passed but no file of the workspace matches " +
                "the kind's deliverable.files, so the issue did not move. Write the deliverable where the issue asks, " +
                "at a path these globs match, then move the issue again."

        /** At most this many files of each list of [uncommitted], each name cut to [FILE_MAX] characters. */
        const val FILES_SHOWN = 10
        const val FILE_MAX = 100

        /**
         * The findings of a verified work tree that holds uncommitted changes or untracked files while the kind
         * delivers a pull request (m2-25): it names them, redacted, so the next turn can commit or remove them.
         */
        fun uncommitted(
            files: UncommittedFiles,
            redactor: Redactor,
        ): VerifyFindings {
            fun section(
                title: String,
                names: List<String>,
            ): String? {
                if (names.isEmpty()) return null
                val shown =
                    names.take(FILES_SHOWN).map {
                        "- " +
                            redactor
                                .redact(it)
                                .lineSequence()
                                .first()
                                .take(FILE_MAX)
                    }
                val more = if (names.size > FILES_SHOWN) listOf("- and ${names.size - FILES_SHOWN} more") else emptyList()
                return (listOf("$title:") + shown + more).joinToString("\n")
            }
            val sections =
                listOfNotNull(section("Uncommitted changes", files.changed), section("Untracked files", files.untracked))
                    .ifEmpty { listOf("Loop could not list the files: run git status to see them.") }
            return VerifyFindings((listOf(UNCOMMITTED_LEAD) + sections).joinToString("\n"))
        }

        /**
         * The findings of a result whose checks all passed while no file matches the [globs] of the kind's
         * `deliverable.files` (m4-21): the globs, redacted, one a line, so the next turn knows where the files go.
         */
        fun noDeliverable(
            globs: List<String>,
            redactor: Redactor,
        ): VerifyFindings {
            val shown = globs.take(FILES_SHOWN).map { "- " + redactor.redact(it).take(FILE_MAX) }
            val more = if (globs.size > FILES_SHOWN) listOf("- and ${globs.size - FILES_SHOWN} more") else emptyList()
            return VerifyFindings((listOf(NO_DELIVERABLE_LEAD, "deliverable.files:") + shown + more).joinToString("\n"))
        }

        /**
         * The findings of a failed holdout check (m2-14): only that a hidden check failed, never its command,
         * exit code or output, so the agent cannot learn what it checks.
         */
        fun hidden(): VerifyFindings = VerifyFindings(HIDDEN)

        /**
         * The findings of the check whose command is [command] and whose run is [result], a failed one. Since m2-08
         * a timed out check is `unverified` and gets no findings; its exit line is kept for any caller. The command and the output are redacted with [redactor]
         * before they are cut, so a cut never splits a secret out of the redactor's sight. Since m3-14 the whole text
         * stays within [maxTokens] of [counter], `context.tool_output_tokens`: the lead, the check and the exit code
         * always show, then the newest output lines that fit, at most [OUTPUT_LINES].
         */
        fun of(
            command: String,
            result: CheckResult,
            redactor: Redactor,
            maxTokens: Int = TOOL_OUTPUT_TOKENS,
            counter: TokenCounter = TokenCounters.estimate,
        ): VerifyFindings {
            val shownCommand =
                redactor
                    .redact(command)
                    .lineSequence()
                    .first()
                    .take(COMMAND_MAX)
            val exit =
                when {
                    result.exitCode != null -> result.exitCode.toString()
                    result.timedOut -> "none, it timed out after ${seconds(result.durationMs)}"
                    else -> "none"
                }
            val head = "$LEAD\nCheck: $shownCommand\nExit code: $exit\n"
            val lines = tail(redactor.redact(outputEnd(result.output)))
            if (lines.isEmpty()) return VerifyFindings("${head}Its output was empty.")

            fun text(newest: List<String>): String {
                val count = if (newest.size == 1) "Last line" else "Last ${newest.size} lines"
                return "$head$count of its output:\n" + newest.joinToString("\n")
            }
            val kept = ArrayDeque<String>()
            for (line in lines.asReversed()) {
                kept.addFirst(line)
                if (counter.count(text(kept)) > maxTokens) {
                    kept.removeFirst()
                    break
                }
            }
            return VerifyFindings(if (kept.isEmpty()) "${head}Its output is left out to fit context.tool_output_tokens." else text(kept))
        }

        /** The end of [output], from the first whole line within its last [TAIL_READ] characters. */
        private fun outputEnd(output: String): String {
            if (output.length <= TAIL_READ) return output
            val end = output.takeLast(TAIL_READ)
            return end.substringAfter('\n', "")
        }

        /** The last non blank lines of [output], at most [OUTPUT_LINES], each cut to [LINE_MAX]. */
        private fun tail(output: String): List<String> =
            output
                .lines()
                .map { it.trimEnd('\r') }
                .dropLastWhile { it.isBlank() }
                .takeLast(OUTPUT_LINES)
                .map { it.take(LINE_MAX) }
    }
}
