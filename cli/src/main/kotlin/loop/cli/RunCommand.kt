package loop.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import loop.api.EventSink
import loop.api.Redactor
import loop.api.RunId
import loop.api.SecretRedactor
import loop.core.events.DeliveryQueuePaused
import loop.core.events.RunStopped
import loop.core.ledger.JsonlTurnLedger
import loop.core.log.HumanLog
import loop.core.log.KeyValueLog
import loop.core.log.LogContext
import loop.core.log.LogLevel
import loop.core.log.LogOutcome
import loop.core.machine.MachineConfig
import loop.core.orchestrator.AttemptOutcome
import loop.core.orchestrator.Orchestrator
import loop.core.orchestrator.TickResult
import loop.core.orchestrator.attemptFile
import loop.core.orchestrator.highestRecordedThreshold
import loop.core.resources.ResourceLocks
import loop.core.template.TemplateEngine
import loop.core.verify.CheckRunner
import loop.core.verify.CoreCheckRunner
import loop.core.verify.EvidenceFiles
import loop.core.verify.Holdouts
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.WorkflowFile
import loop.core.workspace.ShellHookRunner
import loop.core.workspace.WorkspaceManager
import loop.core.workspace.Workspaces
import loop.runner.process.agentEnvironment
import loop.store.GzipAttemptRecords
import loop.tracker.github.GitHubClients
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * `loop run [path-to-WORKFLOW.md] [--once]` (plan D15). A daemon until SIGINT or
 * SIGTERM, which cancel the running attempt and kill its process tree. `--once`
 * runs one tick, waits for its attempts and prints the summary of plan U3.
 * Exits nonzero only when startup fails, never for an attempt's outcome. A daemon
 * stopped by a signal or `loop stop` exits 0 once it unwound in time (m1-34), and one
 * that drained after `loop drain` ends by itself with 0 (m4-10).
 */
class RunCommand(
    private val host: Host,
) : GuardedCommand("run", host) {
    private val workflowPath by argument(
        name = "path-to-WORKFLOW.md",
        help = "The workflow file, default ./WORKFLOW.md",
    ).optional()
    private val once by option("--once", help = "Run one tick, wait for its attempts, then exit").flag()

    /** The operator log and the ids of the run, once they exist: an internal error then goes through them. */
    private var operatorLog: KeyValueLog? = null
    private var logContext = LogContext()

    /** The first exception no code of the run handled (m1-26); once set, the run is canceled for it. */
    private val failure = AtomicReference<Throwable?>()

    override fun reportInternalError(
        error: Exception,
        redactor: Redactor,
    ) {
        val log = operatorLog ?: return super.reportInternalError(error, redactor)
        log.log(LogLevel.ERROR, "run", LogOutcome.FAILED, logContext, reason = "internal error: ${error.sentence()}")
        echoStackTrace(error, redactor)
    }

    override fun help(context: Context): String = "Poll the tracker and start an attempt for each eligible issue"

    override fun body(scope: RedactorScope) {
        val clock = Clock.System
        val templates = TemplateEngine()
        // Plan D5: the startup preflight's workflow is the loaded workflow, the orchestrator reruns it every tick.
        // m2-14: the holdout folder must stay out of every workspace and the repository, checked on every tick too.
        val holdoutDir = Holdouts.dir(host.loopHome)
        lateinit var preflight: Preflight
        // m3-05: a failed discovery fails the load first, with its reason, so the classpath's plugins are only a stand in.
        val trackers = host.trackerPlugins.getOrElse { TrackerPlugins.classpath }
        // m1-11: one redactor for the run, shared by the log, the store and the orchestrator. It knows
        // the secrets of a best effort read of WORKFLOW.md before the startup preflight, those of the
        // loaded workflow after it, and the orchestrator adds those of every workflow its preflight reads.
        val project =
            host.openProject(workflowPath ?: DEFAULT_WORKFLOW, Access.WRITE, scope) { loader, path ->
                preflight =
                    Preflight(loader, path, templates, promptModel = { promptModel(it, host) }) {
                        trackerProblem(it, trackers) ?: Holdouts.problem(it, holdoutDir) ?: deliveryProblem(it, host.rawEnv)
                            ?: exporterProblem(it)
                    }
                when (val checked = runBlocking { preflight.check() }) {
                    // m3-16: a malformed machine config fails the startup preflight too, naming the key.
                    is PreflightResult.Ok ->
                        MachineConfig.read(host.loopHome).fold(
                            onSuccess = { Result.success(checked.workflow) },
                            onFailure = { Result.failure(StartupFailure(it.message ?: "the machine config cannot be read")) },
                        )
                    is PreflightResult.Failed -> Result.failure(StartupFailure(checked.message))
                }
            }
        val workflow = project.workflow
        val redactor = project.redactor
        // Background threads log too, so the lines are written under one lock and do not mix.
        val sinkLock = Any()
        // m4-47: the logs show bare ids with no `trackers:`, namespaced ids with it.
        val log = KeyValueLog({ line -> synchronized(sinkLock) { echo(line, err = true) } }, clock, redactor, project.ids::id)
        operatorLog = log
        // m3-05: unknown keys under a plugin's section stay allowed, for forward compatibility, with a warning each.
        workflow.configWarnings.forEach { warn(redactor.redact(it)) }
        shortSecretNames(workflow, host.rawEnv).forEach { name ->
            warn("\$$name is under ${SecretRedactor.MIN_SECRET_LENGTH} characters, so Loop does not redact it")
        }
        // bl-06: the template is the only source of the prompt, so the issue body, and the answers under its comments,
        // reach the agent only through issue.description. A warning, never a failure: the run goes on.
        if (templates.parse(workflow.promptTemplate).getOrNull()?.uses("issue", "description") == false) {
            warn(
                "the prompt template never uses issue.description, " +
                    "so the agent will not see the issue body or the answers under ## Comments",
            )
        }
        val runId = RunId(UUID.randomUUID().toString())
        val projectId = project.projectId
        val runFiles = RunFiles(host.loopHome, projectId)
        // m1-09: one live run per project (ADR 0001), so `loop stop` has one unambiguous target.
        val record = RunRecord.ofCurrentProcess(runId.value, workflow.path)
        val acquired =
            try {
                runFiles.acquire(record)
            } catch (e: IOException) {
                fail("cannot write the run file at ${runFiles.runFile}: ${e.ioSentence()}")
            }
        val runLock =
            when (acquired) {
                is RunFiles.Acquired.Held -> acquired.lock
                is RunFiles.Acquired.Busy -> {
                    val pid = acquired.record?.let { " (pid ${it.pid})" } ?: ""
                    fail("a run is already in progress for project ${projectId.value}$pid. Run loop stop first")
                }
            }
        try {
            runHoldingLock(project, runId, runFiles, runLock, log, clock, templates, preflight)
        } finally {
            // Normally already released before the shutdown hook lets the JVM go; this covers a failed startup.
            runLock.release()
        }
    }

    /**
     * The run itself, once it holds the run file of its project. A canceled run records the run
     * level `run_stopped` event once its attempts unwound, before the store closes; the run end
     * line and the release of the run file happen before the shutdown hook lets the JVM exit.
     * Workspaces are never removed on a cancel.
     */
    private fun runHoldingLock(
        project: OpenedProject,
        runId: RunId,
        runFiles: RunFiles,
        runLock: RunLock,
        log: KeyValueLog,
        clock: Clock,
        templates: TemplateEngine,
        preflight: Preflight,
    ) {
        val workflow = project.workflow
        val projectId = project.projectId
        val redactor = project.redactor
        val context = LogContext(runId = runId, projectId = projectId)
        logContext = context
        // m2-23: the trackers are built once, but a move to a handoff state removes the required labels of the effective
        // workflow, which follows reloads, so the issue leaves the active set the next poll checks. m4-13: each tracker
        // polled on its own, a handoff removing the labels of the issue's own tracker; one a reload removed keeps its own.
        val trackersAt = AtomicReference(workflow.trackers)
        // m4-48 (bl-50): one GitHub client per API origin and token for the life of the run, shared by the trackers and
        // pull request delivery, the CI wait's polls included, so a request one of them spends holds the others.
        val gitHub = GitHubClients()
        val tracker =
            project.trackerPoll(
                onWarning = { warning -> log.log(LogLevel.WARN, "tracker", LogOutcome.FAILED, context, reason = warning.trimEnd('.')) },
                onMalformed = { malformed -> log.log(LogLevel.WARN, "tracker", LogOutcome.FAILED, context, reason = malformed.sentence()) },
                requiredLabels = { id -> (trackersAt.get().entry(id) ?: workflow.trackers.entry(id))?.requiredLabels.orEmpty() },
                gitHub = gitHub,
            )
        val runnerCheck = host.runnerPreflight(workflow)
        runnerCheck.warnings.forEach { warn(redactor.redact(it)) }
        runnerCheck.error?.let { fail(it) }
        // m1-15: each attempt builds its runner and workspaces from the workflow it starts with, so a reload of the
        // runner settings, of a $VAR the agent env must not see, of workspace.root or of a hook reaches the next attempt.
        val holdoutDir = Holdouts.dir(host.loopHome)
        // m3-04: the startup preflight loaded the workflow through these plugins, so they were found.
        val runnerPlugins = host.runnerPlugins.getOrThrow()
        val runnerOf = { wf: WorkflowFile ->
            runnerFor(wf, runnerPlugins, hostEnv = host.rawEnv, removedEnvNames = agentEnvRemovedNames(wf), holdoutDir = holdoutDir)
        }
        // m3-21: a runner that reports no cost, such as Codex, spends nothing against a dollar budget; said once at startup.
        dollarBudgetWarning(workflow, runnerOf(workflow).capabilities)?.let { warn(redactor.redact(it)) }
        // m4-06: the merge risk reviewer runs through the Claude Code command whatever the runner; said once at startup.
        riskReviewerWarning(workflow, host.rawEnv)?.let { warn(redactor.redact(it)) }
        val workspacesOf = { wf: WorkflowFile -> WorkspaceManager(Workspaces(wf.workspaceRoot), wf.config.hooks, ShellHookRunner()) }
        // m2-03: a check runs code the agent wrote, so it gets the agent env policy, never the full host env, and
        // the orchestrator adds only the issue and attempt ids: no tool config and no launcher on PATH.
        val checksOf = { wf: WorkflowFile ->
            val removed = agentEnvRemovedNames(wf)
            val runner = CoreCheckRunner()
            CheckRunner {
                check,
                cwd,
                env,
                ->
                runner.run(check, cwd, agentEnvironment(overrides = env, removed = removed, host = host.rawEnv))
            }
        }
        val launcher = host.launcher().getOrElse { fail(it.sentence()) }
        val store = project.store().getOrElse { fail(it.sentence()) }
        // m1-39: the human readable run log, next to this key=value log. A write that fails warns here once.
        val humanLogPath = humanLogFile(host.loopHome, projectId)
        val humanLogFile =
            HumanLogFile(humanLogPath) { error ->
                log.log(
                    LogLevel.WARN,
                    "human_log",
                    LogOutcome.FAILED,
                    context,
                    fields = mapOf("path" to humanLogPath),
                    reason = error.sentence(),
                )
            }
        val humanLog = HumanLog(humanLogFile, clock, redactor, base = workflow.path.parent, names = project.ids::name)
        // m4-09, bl-156: an idle sleep assertion held while an attempt, a verification or a merge check runs.
        val keepAwake = KeepAwake(host.awakeLauncher, log, context)
        // m2-19: the ledger line of each turn, in a file of its own like the human log; a failed write warns once.
        val ledgerPath = ledgerFile(host.loopHome, projectId)
        val ledgerLines =
            HumanLogFile(ledgerPath) { error ->
                log.log(
                    LogLevel.WARN,
                    "ledger",
                    LogOutcome.FAILED,
                    context,
                    fields = mapOf("path" to ledgerPath),
                    reason = error.sentence(),
                )
            }
        // m1-23: the run lock is held, so every attempt directory of this Project belongs to a dead run.
        runBlocking { sweepAttemptDirectories(host.loopHome, projectId, runId, store, redactor, log, clock) }
        // m2-18: and no live attempt writes an attempt file, so the ones past the retention go.
        pruneAttemptFiles(host.loopHome, projectId, runId, workflow.config.observability.retentionDays, log, clock)
        // m1-36: for the same reason every open attempt of this Project belongs to a dead process; it closes as abandoned.
        runBlocking { closeAbandonedAttempts(projectId, runId, store, log, clock) }
        // m4-50: and every resource lock of this Project; then each provider recovers what no live lock holds.
        val resourcePlugins =
            host.resourcePlugins.getOrElse { error ->
                log.log(
                    LogLevel.WARN,
                    "resource",
                    LogOutcome.FAILED,
                    context,
                    reason = "no resource provider recovers: ${error.sentence()}",
                )
                null
            }
        val startupLocks =
            ResourceLocks(store, store, clock, runId) { event, error ->
                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, context.copy(attemptId = event.attemptId), reason = error.sentence())
            }
        runBlocking { freeAbandonedLocks(projectId, startupLocks, resourcePlugins?.providers.orEmpty(), log, context) }
        // m2-02: and every stored claim was left by a dead process; it is released and recorded.
        runBlocking { releaseAbandonedClaims(projectId, runId, store, log, clock) }
        val sink = exportingSink(workflow, store, log, context, runnerPlugins, redactor)
        val runner = runnerOf(workflow)
        // m3-09: a runner that declares no occupancy gets only the prompt limit and the turn cap (plan 8.3), said once
        // at startup; `loop status` says it again for each of its running attempts, whatever runner a reload picks.
        if (!runner.capabilities.occupancy) {
            log.log(
                LogLevel.WARN,
                CONTEXT_OCCUPANCY,
                LogOutcome.FAILED,
                context,
                fields = mapOf("runner" to workflow.runner.kind),
                reason =
                    "the ${workflow.runner.kind} runner reports no context occupancy, " +
                        "so only the prompt limit and the turn cap (agent.max_turns) hold for it",
            )
        }
        val orchestrator =
            Orchestrator(
                workflow = workflow,
                tracker = tracker,
                runner = runner,
                sink = sink,
                log = log,
                clock = clock,
                workspaces = workspacesOf(workflow),
                newId = { UUID.randomUUID().toString() },
                runId = runId,
                projectId = projectId,
                launcher = launcher,
                loopHome = host.loopHome,
                hostPath = host.path,
                templates = templates,
                preflight = preflight,
                parked = store,
                redactor = redactor,
                secretsOf = { secretValues(it, host.rawEnv) },
                cancelReason = { cancelReason(runFiles) },
                // m4-10: `loop drain` names this run in its marker.
                drainRequested = { runFiles.drainRequested(runId.value) },
                toolConfigWriter = ToolConfigSnapshotWriter,
                io = Dispatchers.IO,
                loopVersion = host.loopVersion,
                // m1-13 and m2-18: the turn 1 prompt and every runner line, redacted by the run's redactor before they are written.
                attemptRecords = GzipAttemptRecords(redactor) { project, attempt -> attemptFile(host.loopHome, project, attempt) },
                // m1-38: a step the store already holds for an attempt is never recorded again.
                recordedThreshold = { attempt -> highestRecordedThreshold(store.eventsForAttempt(attempt).getOrThrow().map { it.event }) },
                observer = RunObservers(humanLog, keepAwake),
                runnerFor = runnerOf,
                workspacesFor = workspacesOf,
                checkRunnerFor = checksOf,
                // Called once per applied reload: the trackers' required labels follow the new effective workflow too.
                reloadWarnings = { previous, reloaded ->
                    trackersAt.set(reloaded.trackers)
                    reloadWarnings(previous, reloaded, host.rawEnv)
                },
                // m2-02: the retry queue, its counters and the claims survive a restart.
                retryStates = store,
                claims = store,
                // m2-04: an issue whose checks run waits, and the wait survives a restart.
                waiting = store,
                // m2-05: each check's output, redacted by the run's redactor, beside the attempt file.
                evidence = EvidenceFiles(host.loopHome, redactor),
                // m2-19: a ledger line per turn, redacted before it is written, and a row per attempt in the store.
                turnLedger = JsonlTurnLedger(ledgerLines, redactor),
                attemptLedger = store,
                // m2-09: the acceptance command list of each issue, frozen at its first dispatch.
                frozenCommands = store,
                // m3-15: one executor per delivery settings for the life of the run, so a rate limit hold lasts across ticks.
                // m4-48: over the trackers' GitHub clients, so the hold is theirs too.
                deliveryFor = DeliveryExecutors(host.rawEnv, gitHub = gitHub)::of,
                // m2-28: the auto merge gate reads the delivered attempt's events, and its reviewer is read only.
                attemptEvents = { attempt -> store.eventsForAttempt(attempt).map { events -> events.map { it.event } } },
                riskReviewerFor = { wf -> riskReviewerFor(wf, host.rawEnv, agentEnvRemovedNames(wf), holdoutDir) },
                // m3-16: the machine limits of <LOOP_HOME>/config.yaml and the machine slots of the shared store.
                // bl-101: a run file a claim cannot read counts its slots, with a warning once until it reads again.
                machine =
                    StoreMachine(host.loopHome, store, projectId, runId, ProcessHandle.current().pid(), clock) { other, error ->
                        log.log(
                            LogLevel.WARN,
                            "machine_slot",
                            LogOutcome.FAILED,
                            context,
                            fields = mapOf("project" to other.value),
                            reason = "run file unreadable, its slots count: ${error.message ?: error::class.simpleName}",
                        )
                    },
                // bl-175: a pause of the delivery queue an earlier run left, which this restart ends.
                // m4-50: the resource locks of the shared store; an attempt's go at its end.
                resourceLockStore = store,
                queuePausedBefore = {
                    store.eventsOfKinds(projectId, DeliveryQueuePaused.QUEUE_KINDS).getOrNull()?.let { stored ->
                        DeliveryQueuePaused.openIn(stored.map { it.event })
                    }
                },
            )
        log.log(
            LogLevel.INFO,
            "run",
            LogOutcome.STARTED,
            context,
            fields = mapOf("workflow" to workflow.path, "store" to project.storeFile, "once" to once),
        )
        humanLog.runStarted(workflow.path)
        // Set when the run is canceled, for the run end line.
        var stopReason: String? = null
        val onEnd: (Boolean) -> Unit = { failed ->
            try {
                keepAwake.close()
                if (!failed) {
                    val reason = stopReason
                    val fields = if (reason == null) emptyMap() else mapOf(RunStopped.ATTEMPTS_CANCELED to orchestrator.attemptsCanceled)
                    log.log(LogLevel.INFO, "run", LogOutcome.COMPLETED, context, fields = fields, reason = reason)
                }
                humanLog.runEnded(if (failed) INTERNAL_ERROR else stopReason, orchestrator.attemptsCanceled)
            } finally {
                runLock.release()
            }
        }
        var broken = 0
        // The store closes inside untilSignal, so the shutdown hook waits for it.
        untilSignal(log, context, redactor, onEnd, keepAwake::close) {
            store.use {
                // m4-42: the exporters take what their queues hold before the store closes, within about 2.5 s
                // (ExportingSink.DRAIN_TIMEOUT and CLOSE_TIMEOUT), so a hanging collector never outlasts the stop grace.
                sink.use {
                    try {
                        // m1-16, spec 8.6: the workspaces of terminal issues go before the first tick, in both modes.
                        orchestrator.removeTerminalWorkspaces()
                        if (once) {
                            broken = printSummary(orchestrator.tick(), redactor)
                        } else {
                            orchestrator.runUntilCancelled()
                            // m4-10: the daemon returns only once it drained after `loop drain`.
                            stopReason = DRAINED
                        }
                    } catch (e: CancellationException) {
                        // The attempts have unwound (each recorded canceled with this reason); the store is still open.
                        val reason = cancelReason(runFiles)
                        stopReason = reason
                        withContext(NonCancellable) {
                            val event =
                                RunStopped.event(
                                    ts = clock.now(),
                                    projectId = projectId,
                                    data =
                                        mapOf(
                                            RunStopped.REASON to reason,
                                            RunStopped.ATTEMPTS_CANCELED to orchestrator.attemptsCanceled.toString(),
                                        ),
                                    runId = runId,
                                )
                            store.record(event).onFailure { error ->
                                log.log(LogLevel.WARN, "store", LogOutcome.FAILED, context, reason = error.sentence())
                            }
                        }
                        throw e
                    }
                }
            }
        }
        // An attempt that broke on an internal error is a failure of Loop, not an attempt outcome.
        if (broken > 0) throw ProgramResult(EXIT_INTERNAL_ERROR)
    }

    /**
     * m4-42: the preflight problem of [workflow]'s `exporters:` sections, null when it has none or they are valid. A
     * failed discovery of the exporter plugins matters only to a workflow that turns one on.
     */
    private fun exporterProblem(workflow: WorkflowFile): String? {
        if (workflow.exporters.isEmpty()) return null
        return host.exporterPlugins.fold({ it.problem(workflow.exporters, host.rawEnv) }, { it.sentence() })
    }

    /**
     * m4-42: the sink the orchestrator records into: the store, then each exporter [workflow] turns on, wrapped in the
     * run's redactor, which first learns the secrets the exporters send. Logs the plugins found, `event=plugins`, and
     * an exporter's failures, `event=export outcome=failed` at most once a minute and once more at the end of the run with
     * the events dropped then, which never stop the run. The exporters are built
     * once: a reload that changes `exporters:` is checked by the preflight and takes effect at the next run.
     */
    private fun exportingSink(
        workflow: WorkflowFile,
        store: EventSink,
        log: KeyValueLog,
        context: LogContext,
        runners: RunnerPlugins,
        redactor: SecretRedactor,
    ): ExportingSink {
        // The startup preflight checked the sections, so the plugins were found when the workflow turns one on.
        val exporters = host.exporterPlugins.getOrElse { ExporterPlugins.classpath }
        exporters.warnings(workflow.exporters, host.rawEnv).forEach { warn(redactor.redact(it)) }
        redactor.learn(
            exporters
                .secretEnvNames(workflow.exporters)
                .mapNotNull { name -> host.rawEnv[name]?.let { name to it } }
                .toMap()
                .filterValues { it.length >= SecretRedactor.MIN_SECRET_LENGTH },
        )
        val enabled = exporters.create(workflow.exporters, host.rawEnv, redactor)
        val trackers = host.trackerPlugins.getOrElse { TrackerPlugins.classpath }
        log.log(
            LogLevel.INFO,
            "plugins",
            LogOutcome.FOUND,
            context,
            fields =
                mapOf(
                    "runners" to runners.kinds.sorted().joinToString(","),
                    "trackers" to trackers.kinds.sorted().joinToString(","),
                    "exporters" to
                        exporters.keys
                            .sorted()
                            .joinToString(",")
                            .ifEmpty { "none" },
                    "enabled_exporters" to enabled.joinToString(",") { it.key }.ifEmpty { "none" },
                ),
        )
        return ExportingSink(store, enabled, onFailure = { key, error, failures ->
            val fields = mapOf("exporter" to key, "failures" to failures)
            log.log(LogLevel.WARN, "export", LogOutcome.FAILED, context, fields = fields, reason = error.sentence())
        })
    }

    /**
     * Why the run was canceled: `internal_error` when an unhandled exception canceled it, `stop_requested`
     * when `loop stop` created the stop marker before its SIGTERM, else `signal`.
     */
    private fun cancelReason(runFiles: RunFiles): String =
        when {
            failure.get() != null -> INTERNAL_ERROR
            runFiles.stopRequested() -> STOP_REQUESTED
            else -> SIGNAL
        }

    /**
     * Runs [block] until it returns or a SIGINT or SIGTERM cancels it. The shutdown hook
     * waits for [block] to finish its cancellation (attempt recorded, process tree killed,
     * store closed, run end line logged, run file released) for at most [SHUTDOWN_WAIT_SECONDS].
     *
     * m1-34: a daemon stop that unwound within that grace is clean, so the hook halts the JVM
     * with 0, or with [EXIT_INTERNAL_ERROR] when an internal error canceled the run, instead of
     * the JVM's signal code. When the grace runs out, or with `--once`, the hook just returns and
     * the JVM keeps its signal code, 143 for SIGTERM and 130 for SIGINT.
     *
     * m4-09: the hook calls [release] once the wait ends, so the sleep assertion ends with the run even when the grace
     * ran out before [onEnd].
     */
    private fun untilSignal(
        log: KeyValueLog,
        context: LogContext,
        redactor: Redactor,
        onEnd: (failed: Boolean) -> Unit,
        release: () -> Unit,
        block: suspend () -> Unit,
    ) {
        val job = Job()
        val stopped = CountDownLatch(1)
        val hook =
            Thread({
                job.cancel()
                val unwound = stopped.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
                release()
                if (unwound && !once) {
                    // Everything the run prints went out before the latch opened; halt skips nothing else.
                    System.out.flush()
                    System.err.flush()
                    Runtime.getRuntime().halt(if (failure.get() == null) 0 else EXIT_INTERNAL_ERROR)
                }
            }, "loop-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        // m1-26: an exception no code of the run handled, on any thread, is logged redacted and
        // stops the run; nothing exits from here, run() ends with exit 1 once the run unwound.
        // The handler goes in after the hook, so the restore in the finally always pairs with it.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                when {
                    error is Exception && failure.compareAndSet(null, error) ->
                        try {
                            log.log(LogLevel.ERROR, "run", LogOutcome.FAILED, context, reason = "internal error: ${error.sentence()}")
                        } finally {
                            job.cancel()
                        }
                    // A later exception keeps the first failure, and is logged redacted, never raw.
                    error is Exception ->
                        log.log(LogLevel.ERROR, "run", LogOutcome.FAILED, context, reason = "internal error: ${error.sentence()}")
                    previousHandler != null -> previousHandler.uncaughtException(thread, error)
                    else -> {
                        // What the JVM default prints. thread.threadGroup.uncaughtException would recurse into this handler.
                        System.err.print("Exception in thread \"${thread.name}\" ")
                        error.printStackTrace()
                    }
                }
            }
            runBlocking(job) { block() }
        } catch (e: CancellationException) {
            if (!job.isCancelled) throw e
            // Stopped by a signal: the attempt was cancelled and recorded, nothing left to do.
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            try {
                // Before the latch: once it opens, the shutdown hook returns or halts the JVM.
                onEnd(failure.get() != null)
                failure.get()?.let { error -> echoStackTrace(error, redactor) }
            } finally {
                stopped.countDown()
                try {
                    Runtime.getRuntime().removeShutdownHook(hook)
                } catch (e: IllegalStateException) {
                    // The JVM is already shutting down, the hook is running.
                }
            }
        }
        if (failure.get() != null) throw ProgramResult(EXIT_INTERNAL_ERROR)
    }

    /**
     * The `--once` summary; the identifier and the state come from the tracker, so they are redacted.
     * Returns the number of attempts that broke on an internal error, which makes `--once` exit nonzero.
     */
    private fun printSummary(
        result: TickResult,
        redactor: Redactor,
    ): Int {
        val attempts = result.attempts
        val broken = result.brokenAttempts
        if (broken > 0) {
            echo("$broken ${if (broken == 1) "attempt" else "attempts"} broke on an internal error, see the log above.")
        }
        if (attempts.isEmpty()) {
            if (broken == 0) echo("No eligible issue.")
            return broken
        }
        attempts.forEach { attempt ->
            val outcome = attempt.outcome.name.lowercase()
            val state = attempt.issueState?.let(redactor::redact) ?: "unknown"
            echo("${redactor.redact(attempt.issueIdentifier)}  $outcome  $state  ${attempt.duration.inWholeSeconds}s")
        }
        val succeeded = attempts.count { it.outcome == AttemptOutcome.Succeeded }
        val failed = attempts.size - succeeded
        val noun = if (attempts.size == 1) "attempt" else "attempts"
        echo("${attempts.size} $noun, $succeeded succeeded, $failed failed.")
        return broken
    }

    /**
     * m3-13: the model of the runner [workflow] would build, whose window the preflight's prompt limit is a fraction of;
     * null when the plugins were not found or the runner cannot be built, so the smallest window applies.
     */
    private fun promptModel(
        workflow: WorkflowFile,
        host: Host,
    ): String? {
        val plugins = host.runnerPlugins.getOrNull() ?: return null
        return runCatching { runnerFor(workflow, plugins, hostEnv = emptyMap(), removedEnvNames = emptySet()).model }.getOrNull()
    }

    /** A startup warning, [message] without its final period. */
    private fun warn(message: String) {
        echo("loop run: warning: $message.", err = true)
    }

    /** Stops the run at startup, see [StartupFailure]. */
    private fun fail(message: String): Nothing = throw StartupFailure(message)

    private companion object {
        const val DEFAULT_WORKFLOW = "WORKFLOW.md"
        const val SHUTDOWN_WAIT_SECONDS = 10L
        const val CONTEXT_OCCUPANCY = "context_occupancy"
    }
}
