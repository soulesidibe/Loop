package loop.core.workflow

/**
 * m2-09: the commands of an issue's acceptance criteria run on the host with `sh -lc`, so only a tracker whose
 * issue text Loop trusts may give them. Until a check on who wrote or edited an issue exists, that is the `files`
 * tracker alone: a GitHub issue body edited after labeling must never run a command.
 */
fun acceptanceCommandsNeedFiles(trackerKind: String): String = "acceptance_commands needs tracker.kind files, not \"$trackerKind\""

/**
 * The `invalid_config` message of the first kind that declares `acceptance_commands` on a tracker other than `files`,
 * null when the workflow does not; preflight fails with it (m2-09).
 */
fun untrustedAcceptanceCommands(workflow: WorkflowFile): String? {
    if (workflow.trackerKind == "files") return null
    // m4-18: every kind, in the order written, since any of them may take an issue of the tracker.
    return workflow.config.kinds
        ?.named
        .orEmpty()
        .firstNotNullOfOrNull { (name, kind) ->
            kind.checks
                .indexOfFirst { it is CheckConfig.AcceptanceCommands }
                .takeIf { it >= 0 }
                ?.let { index ->
                    "kinds.$name.verify.checks[$index] ${acceptanceCommandsNeedFiles(
                        workflow.trackerKind,
                    )}, until Loop checks who wrote an issue"
                }
        }
}
