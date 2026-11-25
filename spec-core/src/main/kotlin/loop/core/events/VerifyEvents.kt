package loop.core.events

import loop.api.RecordedEvent
import loop.core.events.HumanText.commands

/** The fields of a check run of a submitted result (m2-03). */
abstract class CheckEvent(
    name: String,
) : EventDefinition(name) {
    override val trace = TraceShow.TIMELINE

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        add(CHECK, data[CHECK])
        add(COMMAND, data[COMMAND])
        add("exit", data[EXIT_CODE])
        // m2-14: a holdout's outcome, in place of its exit code and duration.
        add(RESULT, data[RESULT])
        add("duration", TraceText.millis(data[DURATION_MS]))
        if (data[TIMED_OUT] == "true") word(TIMED_OUT)
        if (data[CANCELED] == "true") word(CANCELED)
        // m2-14: a hidden check of the kind's holdout list.
        if (data[HOLDOUT] == "true") word(HOLDOUT)
        // m2-08: the second run of a failed check.
        if (data[RERUN] == "true") word(RERUN)
        add(REASON, data[REASON])
    }

    companion object {
        const val CHECK = "check"
        const val COMMAND = "command"
        const val EXIT_CODE = "exit_code"
        const val RESULT = "result"
        const val DURATION_MS = "duration_ms"
        const val TIMED_OUT = "timed_out"
        const val CANCELED = "canceled"
        const val HOLDOUT = "holdout"
        const val RERUN = "rerun"
        const val REASON = "reason"
        const val FINGERPRINT = "fingerprint"
        const val EVIDENCE = "evidence"

        /** The [RESULT] of a holdout that could not run. */
        const val COULD_NOT_RUN = "could_not_run"
    }
}

object CheckStarted : CheckEvent("check_started") {
    override val sample = mapOf(CHECK to "0", COMMAND to "./gradlew test")
}

object CheckFinished : CheckEvent("check_finished") {
    override val human = HumanShow.ISSUE
    override val sample = mapOf(CHECK to "0", COMMAND to "./gradlew test", EXIT_CODE to "0", DURATION_MS to "61200")

    /** One finished check (m2-03), or the rerun of a failed one (m2-08). */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        // m2-14: a holdout reads as a hidden check, so the log never looks like a visible check failed.
        val check = (if (data[HOLDOUT] == "true") "hidden check " else "check ") + data[COMMAND]
        val name = if (data[RERUN] == "true") "rerun of $check" else check
        val took = data[DURATION_MS]?.toLongOrNull()
        return when {
            data[CANCELED] == "true" -> "$name was stopped"
            // A holdout records only its outcome, never an exit code or duration.
            data[RESULT] == COULD_NOT_RUN -> "$name could not run"
            data[RESULT] != null -> "$name ${data[RESULT]}"
            data[TIMED_OUT] == "true" -> "$name timed out" + took?.let { " after ${HumanText.duration(it)}" }.orEmpty()
            data[EXIT_CODE] == "0" -> "$name passed" + took?.let { " in ${HumanText.duration(it)}" }.orEmpty()
            data[EXIT_CODE] != null ->
                "$name failed with exit code ${data[EXIT_CODE]}" + took?.let { " in ${HumanText.duration(it)}" }.orEmpty()
            else -> "$name could not run: ${data[REASON]}"
        }
    }
}

/** m2-05: the verdict that ends a verification, then what it rests on. */
object Verdict : EventDefinition("verdict") {
    const val VERDICT = "verdict"

    /** m2-26: the rerun of a merge check, not a new verification. */
    const val MERGE_CHECK = "merge_check"
    const val CHECKS = "checks"
    const val FINGERPRINT = "fingerprint"

    /** The evidence references the verdict rests on, comma separated. */
    const val EVIDENCE = "evidence"

    /** m2-08: why the verdict is `unverified`. */
    const val REASON = "reason"

    /** m2-09: the acceptance criteria with no command, never passed. */
    const val JUDGMENT_CALLS = "judgment_calls"

    /** m2-25: the files a pull request would not carry. */
    const val UNCOMMITTED = "uncommitted"

    /** m4-21: how many files matched the kind's `deliverable.files`, absent when the kind declares none. */
    const val DELIVERABLE = "deliverable"

    /** m4-21: the evidence file that lists them, each with its size and sha256 digest. */
    const val DELIVERABLE_EVIDENCE = "deliverable_evidence"

    /** m4-23: how many findings reading the report of a `deliverable.report` kind gave, absent when none. */
    const val REPORT_FINDINGS = "report_findings"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(VERDICT to "verified", CHECKS to "1", FINGERPRINT to "git:abc:def:0", EVIDENCE to "1")

    private fun saved(data: Map<String, String>): Int = data[EVIDENCE]?.split(',')?.count { it.isNotEmpty() } ?: 0

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        val data = event.data
        word(data[VERDICT])
        if (data[MERGE_CHECK] == "true") word(MERGE_CHECK)
        add(CHECKS, data[CHECKS])
        add(FINGERPRINT, data[FINGERPRINT])
        word("$EVIDENCE=${saved(data)}")
        add(REASON, data[REASON])
        add(JUDGMENT_CALLS, data[JUDGMENT_CALLS])
        add(UNCOMMITTED, data[UNCOMMITTED])
        add(DELIVERABLE, data[DELIVERABLE])
        add(DELIVERABLE_EVIDENCE, data[DELIVERABLE_EVIDENCE])
        add(REPORT_FINDINGS, data[REPORT_FINDINGS])
    }

    /** The verdict and how many check outputs were saved as evidence. */
    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val evidence =
            when (val saved = saved(data)) {
                0 -> "no evidence saved"
                1 -> "evidence of 1 check saved"
                else -> "evidence of $saved checks saved"
            }
        // m2-25: a verified work tree refuted for files a pull request would not carry. m4-21: or with no deliverable file.
        // m4-23: or with a report that fails its checks.
        val reason =
            data[REASON]?.let { " ($it)" }
                ?: data[UNCOMMITTED]?.let { " (${if (it == "1") "1 file is" else "$it files are"} not committed)" }
                ?: data[REPORT_FINDINGS]?.let { " (${if (it == "1") "1 report finding" else "$it report findings"})" }
                ?: " (no deliverable file)".takeIf { data[DELIVERABLE] == "0" }.orEmpty()
        val deliverable =
            when (val count = data[DELIVERABLE]) {
                null, "0" -> ""
                "1" -> ", 1 deliverable file listed"
                else -> ", $count deliverable files listed"
            }
        val judgment =
            when (val count = data[JUDGMENT_CALLS]) {
                null -> ""
                "1" -> ", 1 judgment call left to a human"
                else -> ", $count judgment calls left to a human"
            }
        val mergeCheck = if (data[MERGE_CHECK] == "true") " of the merge check" else ""
        return "verdict ${data[VERDICT]}$mergeCheck$reason, $evidence$judgment$deliverable"
    }
}

/** m2-09: the acceptance criteria with no command of a verification, and whether they were saved. */
object JudgmentCalls : EventDefinition("judgment_calls") {
    const val COUNT = "count"

    /** The file is named; `loop show --evidence` prints check runs only. */
    const val EVIDENCE = "evidence"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(COUNT to "2", EVIDENCE to "a1.evidence-2.log")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(COUNT, event.data[COUNT])
        add(EVIDENCE, event.data[EVIDENCE])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ): String {
        val count = data[COUNT]
        val calls = if (count == "1") "1 acceptance criterion is a judgment call" else "$count acceptance criteria are judgment calls"
        return calls + if (data[EVIDENCE] != null) ", listed in the evidence for a human" else ", not saved as evidence"
    }
}

/** The fields of the frozen command list events (m2-09). */
object AcceptanceCommands {
    const val COUNT = "count"
    const val FROZEN = "frozen"
    const val JUDGMENT_CALLS = "judgment_calls"
}

/** m2-09: the acceptance command list of the issue, frozen at its first dispatch. */
object AcceptanceCommandsFrozen : EventDefinition("acceptance_commands_frozen") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(AcceptanceCommands.COUNT to "3")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(AcceptanceCommands.COUNT, event.data[AcceptanceCommands.COUNT]) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "acceptance commands frozen, ${commands(data[AcceptanceCommands.COUNT])}"
}

/** m2-09: the acceptance criteria hold other commands than the frozen list; none runs until `loop resume` accepts them. */
object AcceptanceCommandsChanged : EventDefinition("acceptance_commands_changed") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(AcceptanceCommands.FROZEN to "2", AcceptanceCommands.COUNT to "3")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) {
        add(AcceptanceCommands.FROZEN, event.data[AcceptanceCommands.FROZEN])
        add(AcceptanceCommands.COUNT, event.data[AcceptanceCommands.COUNT])
    }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "acceptance criteria hold ${commands(data[AcceptanceCommands.COUNT])} where ${commands(data[AcceptanceCommands.FROZEN])} " +
        "were frozen at the first dispatch, none runs until loop resume accepts them"
}

/** m2-09: the acceptance criteria hold no command, only judgment calls. */
object AcceptanceCommandsEmpty : EventDefinition("acceptance_commands_empty") {
    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(AcceptanceCommands.JUDGMENT_CALLS to "2")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(AcceptanceCommands.JUDGMENT_CALLS, event.data[AcceptanceCommands.JUDGMENT_CALLS]) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "acceptance criteria hold no command, ${HumanText.judgmentCalls(data[AcceptanceCommands.JUDGMENT_CALLS])} instead"
}

/** m2-09: `loop resume` accepted the changed acceptance commands as the new frozen list. */
object AcceptanceCommandsAccepted : EventDefinition("acceptance_commands_accepted") {
    override val trace = TraceShow.ISSUE_LEVEL
    override val human = HumanShow.ISSUE
    override val sample = mapOf(AcceptanceCommands.COUNT to "3")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(AcceptanceCommands.COUNT, event.data[AcceptanceCommands.COUNT]) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "loop resume accepted the changed acceptance commands, ${commands(data[AcceptanceCommands.COUNT])}"
}

/** bl-32: the base of a pull request kind could not be fetched, so diff_policy read the local branch, guards.base. */
object DiffBaseUnpinned : EventDefinition("diff_base_unpinned") {
    const val REASON = "reason"

    override val trace = TraceShow.TIMELINE
    override val human = HumanShow.ISSUE
    override val sample = mapOf(REASON to "fetch failed")

    override fun traceDetails(
        event: RecordedEvent,
        context: TraceContext,
    ) = traceDetails(context) { add(REASON, event.data[REASON]) }

    override fun humanSentence(
        data: Map<String, String>,
        facts: HumanFacts,
    ) = "could not fetch the base, diff_policy uses guards.base: ${data[REASON]}"
}
