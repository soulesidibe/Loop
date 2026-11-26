package loop.core.verify

import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.ProjectId
import loop.api.RunId
import loop.api.SecretRedactor
import loop.api.TrackerAdapter
import loop.core.delivery.PullRequestDelivery
import loop.core.events.EventDefinition
import loop.core.log.KeyValueLog
import loop.core.waiting.Wait
import loop.core.workflow.WorkflowFile
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * What a [Verification] reads from the process that runs it (m4-54): the services of the run, and the steps that stay
 * with the orchestrator, the frozen acceptance command lists, the base fetch, the pull request delivery and the
 * approval wait. The orchestrator gives its own, so a verification behaves as it did as an inner class of it.
 */
interface VerificationHost {
    val projectId: ProjectId
    val runId: RunId

    /** The Loop home, whose holdout folder a hidden check gets in its env (m2-14). */
    val loopHome: Path
    val tracker: TrackerAdapter
    val log: KeyValueLog
    val clock: Clock
    val redactor: SecretRedactor

    /** Where the blocking reads and writes run. */
    val io: CoroutineContext
    val fingerprinter: Fingerprinter
    val evidence: EvidenceWriter
    val frozenCommands: FrozenAcceptanceCommands

    /** The model of the run's runner, whose token counter trims the findings of a failed check (m3-14). */
    val model: String?

    /**
     * The deliverable comments posted and not yet followed by their move or wait, by issue, attempt and fingerprint
     * (m4-22), shared by every verification of the run so a retry never posts the same deliverable twice.
     */
    val postedDeliverables: MutableSet<String>

    /**
     * Freezes [commands], the acceptance command list of [issueId], when the issue has none yet (m2-09), recording
     * through [record]. The list in the store, the one frozen at the first dispatch, or why the store could not give it.
     */
    suspend fun freezeAcceptanceCommands(
        issueId: String,
        identifier: String,
        commands: List<String>,
        digest: String,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
    ): Result<FrozenCommands>

    /** The base commit of [workflow]'s pull request delivery, fetched host side now (bl-32), or why it could not be. */
    suspend fun fetchBase(workflow: WorkflowFile): Result<String>

    /**
     * bl-28: the commit [ref] resolves to in the project repository of [workflow], read host side, never in the
     * workspace; null when it resolves to none. The default reads nothing.
     */
    suspend fun projectCommit(
        workflow: WorkflowFile,
        ref: String,
    ): String? = null

    /**
     * bl-28: imports the verified commit [sha] of the clone [workspace] into the project of [workflow] and writes its
     * `loop/<key>` branch there; why it failed, null when it was imported or there was nothing to import. The default
     * imports nothing.
     */
    suspend fun importVerified(
        workflow: WorkflowFile,
        workspace: Path,
        sha: String,
    ): String? = null

    /** The pull request delivery of [workflow] (m2-25), recording through [record]. */
    fun pullRequestDelivery(
        workflow: WorkflowFile,
        record: suspend (EventDefinition, Map<String, String>) -> Unit,
    ): PullRequestDelivery

    /** True when the issue [issueId] already waits for approval (m2-24). */
    fun awaitsApproval(issueId: String): Boolean

    /**
     * Holds the approval [wait] in memory and writes it to the store, telling the human once for [reason] when no one
     * approved it yet (m4-30). A failed write is logged and never stops anything.
     */
    suspend fun startApprovalWait(
        wait: Wait.Approval,
        reason: String,
    )
}
