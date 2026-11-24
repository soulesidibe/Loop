package loop.cli

import loop.api.ProjectId
import loop.api.Redactor
import loop.api.SecretRedactor
import loop.api.TrackerAdapter
import loop.api.TrackerError
import loop.core.trackers.NamespacedId
import loop.core.trackers.NamespacedTracker
import loop.core.workflow.DeferredSecret
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.store.IssueIdForms
import loop.store.SqliteEventStore
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * How a command uses the store of its project: [READ] only reads it, so the store keeps [Redactor.NONE];
 * [WRITE] writes it too, every write going through the project's redactor. A [READ] command loads `WORKFLOW.md`
 * with deferred secrets (bl-70): it starts in a shell that does not hold the tracker token, and fails only when it
 * asks for the tracker that token is for, see [OpenedProject.trackerSecret]. A [WRITE] command requires every secret.
 */
enum class Access { READ, WRITE }

/**
 * A failure that stops a command at startup (bl-78): [sentence] is what to print, without the `loop <command>:`
 * prefix or a final period. [GuardedCommand] prints it through the redactor in scope and exits with [EXIT_STARTUP],
 * the one place that maps it.
 */
class StartupFailure(
    val sentence: String,
) : Exception(sentence)

/** The exit code of a [StartupFailure]. */
internal const val EXIT_STARTUP = 1

/**
 * Opens the project of the `WORKFLOW.md` at [path] for a command (bl-78), in the order every command needs: the path
 * made absolute, the host's loader, a redactor that learns the secrets of a best effort read of `tracker.provider`,
 * set in [scope] before the load so a load error is printed redacted, then the load, then the secrets of the loaded
 * workflow. [load] reads the workflow, the loader's own load by default; `loop run` passes its preflight. A load
 * failure throws [StartupFailure], with the error's sentence unless it is already a [StartupFailure].
 */
fun Host.openProject(
    path: String,
    access: Access,
    scope: RedactorScope,
    load: (WorkflowLoader, Path) -> Result<WorkflowFile> = { loader, file -> loader.load(file) },
): OpenedProject {
    val file = workingDir.resolve(path).toAbsolutePath().normalize()
    val loader = workflowLoader(deferSecrets = access == Access.READ)
    val redactor = startupRedactor(file, loader, rawEnv)
    scope.redactor = redactor
    val workflow = load(loader, file).getOrElse { throw it as? StartupFailure ?: StartupFailure(it.sentence()) }
    redactor.learn(secretValues(workflow, rawEnv))
    // The load selected the tracker through these plugins, so they were found.
    return OpenedProject(workflow, redactor, access, loopHome, trackerPlugins.getOrThrow())
}

/**
 * A project [openProject] opened: its loaded [workflow], its [redactor] and its id. Its tracker and its store are
 * built only when a command asks for them, so a command that needs neither never fails on them.
 */
class OpenedProject internal constructor(
    val workflow: WorkflowFile,
    val redactor: SecretRedactor,
    private val access: Access,
    private val loopHome: Path,
    private val trackers: TrackerPlugins,
) {
    /** Plan D5: the id of the project, from the real path of its `WORKFLOW.md`. */
    val projectId: ProjectId = projectIdOf(workflow.path)

    /**
     * bl-70: the unset secret of `tracker.provider` a [Access.READ] load deferred, null when the tracker can be built.
     * A command that can do without the tracker checks it before calling [tracker].
     */
    val trackerSecret: DeferredSecret? = workflow.deferredSecrets.firstOrNull { it.keyPath.startsWith("tracker.provider.") }

    /** The machine store file (ADR 0001), for a message. */
    val storeFile: Path = loopHome.resolve(SqliteEventStore.FILE_NAME)

    /**
     * The tracker named by `tracker.kind`, built by its plugin (m3-05), see [trackerFor] for the arguments. Throws
     * [StartupFailure] when no tracker plugin answers to the kind, or naming the variable when its [trackerSecret] is
     * unset. m4-12: a [NamespacedTracker], so its issues reach the core with namespaced ids.
     */
    fun tracker(
        onWarning: (String) -> Unit = {},
        onMalformed: (TrackerError.Malformed) -> Unit = {},
        requiredLabels: () -> List<String> = { workflow.requiredLabels },
    ): TrackerAdapter {
        trackerSecret?.let { throw StartupFailure(it.sentence) }
        if (trackers.provider(workflow.trackerKind) == null) throw StartupFailure(unsupportedTracker(workflow, trackers))
        val primary = workflow.trackerId
        return NamespacedTracker(primary, primary, trackerFor(workflow, loopHome, onWarning, onMalformed, requiredLabels, trackers))
    }

    /**
     * Opens the machine store for this project, as a writer through [redactor] or as a reader with [Redactor.NONE]
     * by [Access]. Its issue ids are read by [issueIdForms] (m4-12). A failure's message is the sentence `cannot open the store at <file>: <reason>`; the exit code is
     * the command's.
     */
    fun store(): Result<SqliteEventStore> =
        SqliteEventStore
            .open(loopHome, workflow.path.parent, if (access == Access.WRITE) redactor else Redactor.NONE, issueIdForms(workflow.trackerId))
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(IllegalStateException("cannot open the store at $storeFile: ${it.sentence()}", it)) },
            )
}

/**
 * m4-12: how the store reads the issue ids of a project whose primary tracker is [primary], by the reading rule of
 * [NamespacedId]: a bare id written before m4-12 is the primary tracker's.
 */
fun issueIdForms(primary: String): IssueIdForms =
    IssueIdForms(
        read = { NamespacedId.read(it, primary).value },
        legacy = { NamespacedId.read(it, primary).bareFor(primary) },
    )
