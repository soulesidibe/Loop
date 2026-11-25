package loop.cli

import loop.api.AgentEnvPolicy
import loop.api.AgentRunner
import loop.api.DeliveryExecutor
import loop.api.ProjectId
import loop.api.RiskReviewer
import loop.api.RunnerCapabilities
import loop.api.SecretRedactor
import loop.api.TrackerAdapter
import loop.api.TrackerContext
import loop.api.TrackerError
import loop.core.resources.ResourceCheck
import loop.core.trackers.TrackerEntry
import loop.core.trackers.TrackerRegistry
import loop.core.verify.Holdouts
import loop.core.workflow.ConfigValue
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.RunnerCheck
import loop.core.workflow.TrackerCheck
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.runner.acp.AcpSettings
import loop.runner.acp.AgyPreflight
import loop.runner.claudecode.ClaudeCodePreflight
import loop.runner.claudecode.ClaudeCodeRiskReviewer
import loop.runner.claudecode.ClaudeCodeSettings
import loop.runner.claudecode.PreflightResult
import loop.runner.codex.CodexAppServerSettings
import loop.runner.codex.CodexRiskReviewer
import loop.tracker.github.GitHubClients
import loop.tracker.github.GitHubDelivery
import loop.tracker.github.GitHubTracker
import loop.tracker.github.GitHubTrackerProvider
import loop.tracker.github.GitHubTrackerSettings
import loop.tracker.github.GitHubTransport
import loop.tracker.github.GitPush
import loop.tracker.github.JdkGitHubTransport
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Clock

/**
 * What the cli reads from the machine, read once in [main] and passed down: the env,
 * the launcher property the start script sets (plan D7) and the user's home.
 * [runnerPreflight] checks that the configured runner can start on this machine (uid,
 * managed settings), a seam so tests do not depend on the machine running them.
 */
class Host(
    env: Map<String, String>,
    private val launcherProperty: String?,
    userHome: String,
    val runnerPreflight: (WorkflowFile) -> PreflightResult = { runnerPreflight(it, env) },
    /** The running build's version (m1-12), recorded on every attempt; tests can fake it. */
    val loopVersion: String = BuildInfo.UNKNOWN,
    /** m4-09: what starts `loop run`'s sleep assertion, `caffeinate` on macOS, null on other systems; tests fake it. */
    val awakeLauncher: AwakeLauncher? = AwakeLauncher.forSystem(),
    /** Working directory for resolving relative workflow paths; defaults to the JVM's cwd. */
    val workingDir: Path = Path("").toAbsolutePath().normalize(),
) {
    /**
     * The process env as read in [main], unfiltered. The agent env starts from it (minus the
     * names a runner removes), so no runner reads `System.getenv()` on its own.
     */
    val rawEnv: Map<String, String> = env

    /** `HOME` when set, so a test or an operator can redirect it, else the JVM's user home. */
    val home: Path = Path(env("HOME") ?: userHome).toAbsolutePath().normalize()

    /** The machine level folder (ADR 0001): `LOOP_HOME`, default `~/.loop`. */
    val loopHome: Path =
        (env("LOOP_HOME")?.let { Path(it) } ?: home.resolve(".loop"))
            .toAbsolutePath()
            .normalize()

    val path: String? get() = env("PATH")

    /** The JARs of `<LOOP_HOME>/plugins/` (m3-04), opened once, so the runner and tracker plugins of a JAR share its loader. */
    private val pluginFolder: Result<PluginFolder> by lazy { PluginFolder.open(loopHome.resolve(PLUGINS_DIR)) }

    /** The runner plugins of this host (m3-04): the classpath's, then those of the JARs in `<LOOP_HOME>/plugins/`. */
    val runnerPlugins: Result<RunnerPlugins> by lazy {
        pluginFolder.fold(onSuccess = { RunnerPlugins.discover(it) }, onFailure = { Result.failure(it) })
    }

    /** The tracker plugins of this host (m3-05), found like [runnerPlugins]: `files` and `github` on the classpath, then the JARs'. */
    val trackerPlugins: Result<TrackerPlugins> by lazy {
        pluginFolder.fold(onSuccess = { TrackerPlugins.discover(it) }, onFailure = { Result.failure(it) })
    }

    /**
     * The event exporter plugins of this host (m4-42), found like [runnerPlugins]: `otel` on the classpath, then the
     * JARs'. Only `loop run` uses them, when `WORKFLOW.md` has an `exporters:` section.
     */
    val exporterPlugins: Result<ExporterPlugins> by lazy {
        pluginFolder.fold(onSuccess = { ExporterPlugins.discover(it) }, onFailure = { Result.failure(it) })
    }

    /**
     * The resource plugins of this host (m4-24), found like [runnerPlugins]: none on the classpath until the providers
     * of m4-27 and m4-28, then the JARs'. A `resources:` kind none serves is a plain lock.
     */
    val resourcePlugins: Result<ResourcePlugins> by lazy {
        pluginFolder.fold(onSuccess = { ResourcePlugins.discover(it) }, onFailure = { Result.failure(it) })
    }

    /**
     * The [WorkflowLoader] every command uses: this env and home, [trackerPlugins], [runnerPlugins] and
     * [resourcePlugins]. Plugins that could not be found fail every load with the reason, resource plugins only when
     * `resources:` declares a resource. With [deferSecrets] (bl-70), an unset secret is named in
     * the loaded workflow instead of failing the load, see [WorkflowLoader].
     */
    fun workflowLoader(deferSecrets: Boolean = false): WorkflowLoader =
        workflowLoader(
            rawEnv,
            home,
            deferSecrets = deferSecrets,
            runners = runnerPlugins.fold({ it }, { RunnerCheck { _ -> it.sentence() } }),
            trackers =
                trackerPlugins.fold({ it }, { error ->
                    TrackerCheck { _ ->
                        Result.failure(IllegalStateException(error.sentence()))
                    }
                }),
            resources =
                resourcePlugins.fold({ it }, { error ->
                    ResourceCheck { _ ->
                        Result.failure(IllegalStateException(error.sentence()))
                    }
                }),
        )

    /** A non blank env value, or null. */
    fun env(name: String): String? = rawEnv[name]?.takeIf { it.isNotBlank() }

    /** The `loop` launcher: the `loop.launcher` property, else `LOOP_LAUNCHER`; it must be an executable file. */
    fun launcher(): Result<Path> {
        val raw =
            launcherProperty?.takeIf { it.isNotBlank() }
                ?: env("LOOP_LAUNCHER")
                ?: return Result.failure(
                    IllegalStateException("no launcher, set the loop.launcher property or LOOP_LAUNCHER"),
                )
        val launcher = Path(raw).toAbsolutePath().normalize()
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            return Result.failure(IllegalStateException("the launcher $launcher is not an executable file"))
        }
        return Result.success(launcher)
    }

    companion object {
        fun current(): Host =
            Host(
                env = System.getenv(),
                launcherProperty = System.getProperty("loop.launcher"),
                userHome = System.getProperty("user.home"),
                loopVersion = BuildInfo.current(),
            )
    }
}

/** Plan D5: the first 16 hex characters of the SHA-256 of the real path of `WORKFLOW.md`. */
fun projectIdOf(workflowRealPath: Path): ProjectId {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(workflowRealPath.toString().toByteArray(Charsets.UTF_8))
    return ProjectId(digest.joinToString("") { "%02x".format(it) }.take(PROJECT_ID_HEX_CHARS))
}

/**
 * A [WorkflowLoader] over [env] and [home] that selects the tracker with [trackers] (m3-05), checks the runner with
 * [runners] (m3-04) and tells the resource kinds a provider serves with [resources] (m4-24), all the classpath's plugins
 * by default, requiring every secret unless [deferSecrets] (bl-70).
 */
fun workflowLoader(
    env: Map<String, String>,
    home: Path,
    runners: RunnerCheck = RunnerPlugins.classpath,
    trackers: TrackerCheck = TrackerPlugins.classpath,
    deferSecrets: Boolean = false,
    resources: ResourceCheck = ResourcePlugins.classpath,
): WorkflowLoader =
    WorkflowLoader(
        env = env,
        home = home,
        trackerCheck = trackers,
        runnerCheck = runners,
        deferSecrets = deferSecrets,
        resourceCheck = resources,
    )

/**
 * The tracker named by `tracker.kind` (m3-05): the provider of [plugins] whose kind matches builds it from the
 * settings the loader read. Throws [IllegalStateException] when no provider answers to the kind (bl-104), which a
 * workflow loaded through [plugins] never has. [onWarning] gets the adapter's operator
 * warnings, such as a `blocked_by` id with no issue file (m1-09). A move to a handoff state removes [requiredLabels],
 * by default the workflow's, read at each move (m2-23). A GitHub tracker sends through [gitHub] (m4-48).
 */
fun trackerFor(
    workflow: WorkflowFile,
    loopHome: Path,
    onWarning: (String) -> Unit = {},
    onMalformed: (TrackerError.Malformed) -> Unit = {},
    requiredLabels: () -> List<String> = { workflow.requiredLabels },
    plugins: TrackerPlugins = TrackerPlugins.classpath,
    gitHub: GitHubClients = GitHubClients(),
): TrackerAdapter = adapterFor(workflow.trackers.primary, loopHome, onWarning, onMalformed, requiredLabels, plugins, gitHub)

/**
 * m4-11: the [TrackerRegistry] of [workflow], each tracker built by its plugin like [trackerFor] builds the primary
 * one. m4-13: a tracker's move to a handoff state removes its own `required_labels`, never another tracker's, as
 * [requiredLabels] gives them by tracker id at each move, by default those of [workflow]. `loop run` polls them all.
 * m4-48: every GitHub tracker sends through [gitHub], so two of one origin and token share one rate limit hold, and
 * `loop run` passes the clients its pull request delivery uses too.
 */
fun trackerRegistryFor(
    workflow: WorkflowFile,
    loopHome: Path,
    onWarning: (String) -> Unit = {},
    onMalformed: (TrackerError.Malformed) -> Unit = {},
    requiredLabels: (String) -> List<String> = { id ->
        workflow.trackers
            .entry(id)
            ?.requiredLabels
            .orEmpty()
    },
    plugins: TrackerPlugins = TrackerPlugins.classpath,
    gitHub: GitHubClients = GitHubClients(),
): TrackerRegistry =
    TrackerRegistry.build(workflow.trackers) { entry ->
        adapterFor(entry, loopHome, onWarning, onMalformed, { requiredLabels(entry.id) }, plugins, gitHub)
    }

/** The adapter of [entry] by its plugin; the GitHub plugin builds it over [gitHub], the run's clients (m4-48). */
private fun adapterFor(
    entry: TrackerEntry,
    loopHome: Path,
    onWarning: (String) -> Unit,
    onMalformed: (TrackerError.Malformed) -> Unit,
    requiredLabels: () -> List<String>,
    plugins: TrackerPlugins,
    gitHub: GitHubClients,
): TrackerAdapter {
    val provider = plugins.provider(entry.kind) ?: throw IllegalStateException(plugins.unsupported(entry.kind))
    val context = TrackerContext(entry.dir, loopHome.resolve("locks"), onMalformed, onWarning, requiredLabels, entry.activeStates)
    return if (provider is GitHubTrackerProvider) {
        provider.create(
            entry.settings,
            context,
            gitHub,
        )
    } else {
        provider.create(entry.settings, context)
    }
}

/**
 * The pull request delivery of [workflow] (m2-25), null when its kind sets no `delivery.pull_request`. `repo` and
 * `token` default to the github tracker's, `api_url` to the tracker's, else GitHub's. The token is read from [env]
 * by its variable; of git processes only the push gets it, through a credential helper. Commits are pushed from the
 * project repository, the folder of `WORKFLOW.md`, as the worktree hooks see it, to the explicit URL of `repo` on
 * the host of `api_url`. Null too when a setting is missing, which the preflight refuses first, or when `api_url`
 * gives no URL Loop may push to. Built anew on each call: `loop run` keeps one per settings in [DeliveryExecutors].
 * Its GitHub requests go through [gitHub] (m4-48).
 */
fun deliveryFor(
    workflow: WorkflowFile,
    env: Map<String, String>,
    gitHub: GitHubClients = GitHubClients(),
): DeliveryExecutor? = DeliverySettings.of(workflow, env)?.build(env, gitHub)

/**
 * m3-15, note 12 of the triage: the pull request delivery of each workflow, built once per repository, API URL, token
 * and base and kept for the life of the run, so no HTTP client is built per poll of the CI wait. A reload with the same
 * settings keeps the same executor. [toString] never shows a token. m4-48 (bl-50): its GitHub requests, the CI wait's
 * polls included, go through the client of [gitHub] for its API URL and token, so they share one rate limit hold with
 * the run's GitHub trackers of the same origin and token, kept for the life of the run. By default its own clients over
 * [transport] and [clock].
 */
class DeliveryExecutors(
    private val env: Map<String, String>,
    transport: GitHubTransport = JdkGitHubTransport(),
    clock: Clock = Clock.System,
    private val gitHub: GitHubClients = GitHubClients(transport, clock),
) {
    private val built = ConcurrentHashMap<DeliverySettings, DeliveryExecutor>()

    /** The executor of [workflow]'s pull request delivery, the one already built for its settings; null like [deliveryFor]. */
    fun of(workflow: WorkflowFile): DeliveryExecutor? {
        val settings = DeliverySettings.of(workflow, env) ?: return null
        return built.computeIfAbsent(settings) { it.build(env, gitHub) }
    }

    override fun toString(): String = "DeliveryExecutors(${built.keys.joinToString()})"
}

/** What a pull request delivery is built from (m3-15): equal settings share one executor. [toString] hides the token. */
private class DeliverySettings(
    val owner: String,
    val name: String,
    val token: String,
    val apiUrl: URI,
    val base: String,
    val projectDir: Path,
) {
    private val key get() = listOf(owner, name, token, apiUrl.toString(), base, projectDir.toString())

    override fun equals(other: Any?): Boolean = other is DeliverySettings && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "$owner/$name@$apiUrl:$base"

    fun build(
        env: Map<String, String>,
        gitHub: GitHubClients,
    ): DeliveryExecutor =
        GitHubDelivery(
            owner = owner,
            repo = name,
            token = token,
            projectDir = projectDir,
            apiUrl = apiUrl,
            base = base,
            env = env,
            clients = gitHub,
        )

    companion object {
        fun of(
            workflow: WorkflowFile,
            env: Map<String, String>,
        ): DeliverySettings? {
            val pullRequest =
                workflow.config.kinds
                    ?.code
                    ?.delivery
                    ?.pullRequest ?: return null
            val github = workflow.trackerSettings as? GitHubTrackerSettings
            val repo = pullRequest.repo ?: github?.let { "${it.owner}/${it.repo}" } ?: return null
            val token = pullRequest.tokenEnvName?.let { env[it]?.takeIf(String::isNotBlank) } ?: github?.token ?: return null
            val apiUrl = pullRequest.apiUrl?.let(::URI) ?: github?.apiUrl ?: GitHubTrackerSettings.DEFAULT_API_URL
            val owner = repo.substringBefore('/')
            val name = repo.substringAfter('/')
            if (!GitPush.isPushUrl(GitPush.pushUrl(apiUrl, owner, name))) return null
            return DeliverySettings(owner, name, token, apiUrl, pullRequest.base, workflow.path.parent)
        }
    }
}

/**
 * m2-25: the preflight of pull request delivery, beside the loader's own checks of `repo`, `token` and `api_url`: the
 * configured remote must be a remote of the project repository, the folder of `WORKFLOW.md`. A sentence naming the
 * problem, or null when there is no `delivery.pull_request` or its remote exists. [git] runs with
 * [GitPush.minimalEnv] of [env] only.
 */
fun deliveryProblem(
    workflow: WorkflowFile,
    env: Map<String, String>,
    git: String = "git",
): String? {
    val pullRequest =
        workflow.config.kinds
            ?.code
            ?.delivery
            ?.pullRequest ?: return null
    val key = "kinds.code.delivery.pull_request"
    val repository = workflow.path.parent
    val found = remoteUrlExists(repository, pullRequest.remote, env, git)
    return if (found) null else "$key.remote is \"${pullRequest.remote}\", which is not a remote of the repository at $repository"
}

/**
 * True when `git remote get-url` [remote] succeeds in [repository], with [GitPush.minimalEnv] of [env] and no prompt;
 * git that cannot start counts as no remote.
 */
private fun remoteUrlExists(
    repository: Path,
    remote: String,
    env: Map<String, String>,
    git: String,
): Boolean =
    try {
        val process =
            ProcessBuilder(git, "remote", "get-url", "--", remote)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(GitPush.minimalEnv(env))
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
        process.outputStream.close()
        process.inputStream.readAllBytes()
        process.waitFor(REMOTE_CHECK_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: IOException) {
        false
    }

private const val REMOTE_CHECK_SECONDS = 10L

/** Where the tracker of [workflow] reads its issues, for a message: the folder, or the GitHub repository. */
fun trackerLocation(workflow: WorkflowFile): String =
    when (val settings = workflow.trackerSettings) {
        is GitHubTrackerSettings -> "the github tracker of ${settings.owner}/${settings.repo}"
        else -> "the tracker at ${workflow.trackerDir}"
    }

/** The sentence for a `tracker.kind` none of [plugins] answers to. */
fun unsupportedTracker(
    workflow: WorkflowFile,
    plugins: TrackerPlugins = TrackerPlugins.classpath,
): String = plugins.unsupported(workflow.trackerKind)

/**
 * The runner named by `runner.kind` (m3-04): the provider of [plugins] whose kind matches builds it from its own
 * section under `runners`. Every runner gets the shared agent env policy: [hostEnv] minus [removedEnvNames], plus
 * `agent.env` with `{workspace}` replaced, plus Loop's own env. [removedEnvNames] holds the names `WORKFLOW.md`
 * references, see [agentEnvRemovedNames]. [holdoutDir] is the folder an agent must never read (m2-14).
 */
fun runnerFor(
    workflow: WorkflowFile,
    plugins: RunnerPlugins,
    hostEnv: Map<String, String>,
    removedEnvNames: Set<String>,
    holdoutDir: Path? = null,
): AgentRunner {
    val provider = plugins.providerFor(workflow.runner)
    return provider.create(
        workflow.runner.section(provider.section),
        AgentEnvPolicy(hostEnv = hostEnv, removedEnvNames = removedEnvNames, addedEnv = workflow.config.agent.env),
        holdoutDir,
    )
}

/**
 * The read only reviewer of the merge risk assessment (m2-28), whatever `runner.kind` is (m4-06, bl-145), since the
 * reviewer is a concern apart from the runner that did the work: the model the workflow names in `risk.reviewer` picks
 * its runtime ([RiskReviewerRuntime], bl-214). A Codex model gets Codex's reviewer, with the settings of `runners.codex`
 * ([codexReviewerSettings]); any other model Claude Code's, with the [riskReviewerCommand]. The program is resolved as
 * [resolveCommand] does, so the reviewer starts exactly the file the startup check found; null when it cannot be found,
 * or `runners.codex` cannot be read, so the auto merge gate falls back naming the reviewer setup, and the startup warns
 * ([riskReviewerWarning]). Its env is the agent env policy and it refuses reads under [holdoutDir], like an agent.
 */
fun riskReviewerFor(
    workflow: WorkflowFile,
    hostEnv: Map<String, String>,
    removedEnvNames: Set<String>,
    holdoutDir: Path? = null,
): RiskReviewer? =
    when (riskReviewerRuntime(workflow)) {
        RiskReviewerRuntime.ClaudeCode ->
            resolveCommand(riskReviewerCommand(workflow), hostEnv)?.let { command ->
                ClaudeCodeRiskReviewer(command, hostEnv, removedEnvNames, holdoutDir)
            }
        RiskReviewerRuntime.Codex -> {
            val settings = codexReviewerSettings(workflow, holdoutDir).getOrNull()
            val argv = settings?.argv
            argv?.let { resolveCommand(it.first(), hostEnv) }?.let { program ->
                val command = CodexAppServerSettings.commandOf(listOf(program) + argv.drop(1))
                CodexRiskReviewer(settings.copy(command = command), hostEnv, removedEnvNames)
            }
        }
    }

/** bl-214: the runtime of the merge risk reviewer of [workflow], from the model of `risk.reviewer`. */
fun riskReviewerRuntime(workflow: WorkflowFile): RiskReviewerRuntime =
    RiskReviewerRuntime.of(
        workflow.config.kinds
            ?.code
            ?.delivery
            ?.risk
            ?.reviewer,
    )

/**
 * bl-214: the settings of the Codex merge risk reviewer, `runners.codex` read as the Codex runner reads it, with
 * [holdoutDir]: its `command`, `read_timeout_ms` and `strict_mcp` apply, the model and the time limit are the review's.
 * No section is the defaults, `codex app-server`. A section the Codex runner refuses is a failure with its reason; under
 * `runner.kind: codex-app-server` the loader already checked it.
 */
fun codexReviewerSettings(
    workflow: WorkflowFile,
    holdoutDir: Path? = null,
): Result<CodexAppServerSettings> =
    runCatching {
        @Suppress("UNCHECKED_CAST")
        val section = workflow.runner.sections[CodexAppServerSettings.SECTION] as? Map<String, Any?>
        CodexAppServerSettings.of(section.orEmpty(), holdoutDir).also { it.argv }
    }

/**
 * The program the merge risk reviewer runs (m4-06, bl-214). For a Claude Code reviewer: under `runner.kind:
 * claude-code` the command of its section, which the loader checked; under any other runner `runners.claude-code.command`
 * when it is set and not blank, else [DEFAULT_REVIEWER_COMMAND]. For a Codex reviewer: the first word of the command of
 * [codexReviewerSettings], or [CodexAppServerSettings.DEFAULT_COMMAND]'s when the section cannot be read.
 */
fun riskReviewerCommand(workflow: WorkflowFile): String =
    when (riskReviewerRuntime(workflow)) {
        RiskReviewerRuntime.Codex ->
            codexReviewerSettings(workflow).getOrNull()?.argv?.first()
                ?: CodexAppServerSettings.DEFAULT_COMMAND.substringBefore(' ')
        RiskReviewerRuntime.ClaudeCode ->
            if (workflow.runner.kind == ClaudeCodeSettings.KIND) {
                ClaudeCodeSettings.of(workflow.runner.section(ClaudeCodeSettings.KIND)).command
            } else {
                (workflow.runner.sections[ClaudeCodeSettings.KIND] as? Map<*, *>)
                    ?.get("command")
                    ?.let { it as? String }
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_REVIEWER_COMMAND
            }
    }

/**
 * m4-06: the startup warning of a workflow whose auto merge gate would run a merge risk reviewer, `policy: auto` with
 * `pull_request.merge` and `risk.reviewer`, while the [riskReviewerCommand] cannot be found, or, for a Codex reviewer
 * (bl-214), `runners.codex` cannot be read: every result would then wait for `loop approve`, for the reviewer setup and
 * not for the result. Null otherwise.
 */
fun riskReviewerWarning(
    workflow: WorkflowFile,
    hostEnv: Map<String, String>,
): String? {
    val delivery =
        workflow.config.kinds
            ?.code
            ?.delivery ?: return null
    if (delivery.policy != DeliveryPolicy.Auto || delivery.pullRequest?.merge == null) return null
    val model = delivery.risk.reviewer ?: return null
    val runtime = riskReviewerRuntime(workflow)
    if (runtime == RiskReviewerRuntime.Codex) {
        codexReviewerSettings(workflow).exceptionOrNull()?.let {
            return "kinds.code.delivery.risk.reviewer is $model, but runners.codex cannot be read (${it.message}), so no " +
                "merge risk reviewer can run and every result waits for loop approve"
        }
    }
    val command = riskReviewerCommand(workflow)
    if (resolveCommand(command, hostEnv) != null) return null
    val fix =
        when (runtime) {
            RiskReviewerRuntime.ClaudeCode -> "Install Claude Code or set runners.claude-code.command"
            RiskReviewerRuntime.Codex -> "Install Codex or set runners.codex.command"
        }
    return "kinds.code.delivery.risk.reviewer is $model, but its command $command cannot be found, so no merge risk " +
        "reviewer can run and every result waits for loop approve. $fix"
}

/**
 * The file [command] starts, or null when it cannot be found: a bare name is the first executable file of that name in
 * the folders of the `PATH` of [hostEnv], given as its absolute path; an absolute path is itself when it is an
 * executable file. A relative path with a slash resolves against the issue workspace, which does not exist yet, so it
 * is kept as written.
 */
internal fun resolveCommand(
    command: String,
    hostEnv: Map<String, String>,
): String? {
    val executable = { path: Path -> Files.isRegularFile(path) && Files.isExecutable(path) }
    if ('/' !in command) {
        return hostEnv["PATH"]
            .orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { dir -> runCatching { Path.of(dir, command) }.getOrNull()?.takeIf(executable) }
            ?.toAbsolutePath()
            ?.toString()
    }
    val path = runCatching { Path.of(command) }.getOrNull() ?: return null
    return command.takeIf { !path.isAbsolute || executable(path) }
}

/** The reviewer's command when the workflow names none (m4-06). */
const val DEFAULT_REVIEWER_COMMAND = "claude"

/**
 * The env names kept out of the agent env: every `$NAME` `WORKFLOW.md` references, that is
 * every name the loader resolved and every front matter string value at any depth that is
 * exactly `$NAME`, minus the kept set ([KEPT_ENV_NAMES] and every `LC_*` name), which the agent
 * needs to run. No value heuristics. The runner also always drops `ANTHROPIC_API_KEY` and
 * `ANTHROPIC_AUTH_TOKEN`. `LOOP_HOLDOUT` (m2-14) is always removed too, so a host export never
 * shows the agent or a visible check where the holdouts are; only a holdout gets it, from Loop.
 */
fun agentEnvRemovedNames(workflow: WorkflowFile): Set<String> =
    (workflow.config.envRefs.map { it.name } + workflow.config.referencedEnvNames)
        .filterNotTo(mutableSetOf()) { it in KEPT_ENV_NAMES || it.startsWith(KEPT_ENV_PREFIX) } + Holdouts.ENV

/** Names never removed from the agent env, even when `WORKFLOW.md` references them. */
private val KEPT_ENV_NAMES = setOf("HOME", "PATH", "USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "TERM")

/** The locale names (`LC_ALL`, `LC_CTYPE` and so on), never removed either. */
private const val KEPT_ENV_PREFIX = "LC_"

/**
 * The secrets Loop redacts (m1-11), env name to host env value: every `tracker.provider` value, and since m4-11 every
 * `provider` value of an extra tracker of `trackers:`, that is exactly `$NAME`, except the path keys ([PATH_KEYS]), the variable of
 * `kinds.code.delivery.pull_request.token` (m2-25), plus `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `GEMINI_API_KEY`
 * and `GOOGLE_API_KEY`. A `workspace.root` or `tracker.provider.dir` path stays visible.
 * Unset and blank values are skipped, and so are values under
 * [SecretRedactor.MIN_SECRET_LENGTH] characters, see [shortSecretNames].
 */
fun secretValues(
    workflow: WorkflowFile,
    env: Map<String, String>,
): Map<String, String> =
    secretValues(trackerProviders(workflow), env) +
        envValues(deliveryTokenNames(workflow), env).filterValues { it.length >= SecretRedactor.MIN_SECRET_LENGTH }

/** m4-11: the `provider` map of every tracker of [workflow], the primary one first. */
private fun trackerProviders(workflow: WorkflowFile): List<Map<String, ConfigValue>> = workflow.trackers.all.map { it.provider }

/** The variable of `kinds.code.delivery.pull_request.token` (m2-25), a secret like the tracker's token; empty when unset. */
private fun deliveryTokenNames(workflow: WorkflowFile): List<String> =
    listOfNotNull(
        workflow.config.kinds
            ?.code
            ?.delivery
            ?.pullRequest
            ?.tokenEnvName,
    )

/** [secretValues] from one `tracker.provider` map. */
fun secretValues(
    trackerProvider: Map<String, ConfigValue>,
    env: Map<String, String>,
): Map<String, String> = secretValues(listOf(trackerProvider), env)

/** [secretValues] from the `provider` maps of several trackers (m4-11), as [WorkflowLoader.trackerProviders] reads them. */
fun secretValues(
    trackerProviders: List<Map<String, ConfigValue>>,
    env: Map<String, String>,
): Map<String, String> = secretRefs(trackerProviders, env).filterValues { it.length >= SecretRedactor.MIN_SECRET_LENGTH }

/**
 * A [SecretRedactor] for a command about to load the `WORKFLOW.md` at [path]. It learns the
 * [secretValues] of a best effort read of its `tracker.provider` map and of each extra tracker's `provider` (m4-11), so a load or preflight
 * failure message is covered even when validation fails. When the front matter does not parse,
 * only the Anthropic and Google keys and the token patterns apply.
 */
fun startupRedactor(
    path: Path,
    loader: WorkflowLoader,
    env: Map<String, String>,
): SecretRedactor = SecretRedactor().apply { learn(secretValues(loader.trackerProviders(path), env)) }

/** The secret names whose value is under [SecretRedactor.MIN_SECRET_LENGTH] characters, so not redacted, sorted. */
fun shortSecretNames(
    workflow: WorkflowFile,
    env: Map<String, String>,
): List<String> =
    (secretRefs(trackerProviders(workflow), env) + envValues(deliveryTokenNames(workflow), env))
        .filterValues { it.length < SecretRedactor.MIN_SECRET_LENGTH }
        .keys
        .sorted()

/** What a reload from [previous] to [reloaded] cannot apply live (m1-15), one sentence each without a final period. */
fun reloadWarnings(
    previous: WorkflowFile,
    reloaded: WorkflowFile,
    env: Map<String, String>,
): List<String> {
    // The tracker adapter is built once per run; its states and labels follow the effective workflow.
    val previousGitHub = previous.trackerSettings as? GitHubTrackerSettings
    val reloadedGitHub = reloaded.trackerSettings as? GitHubTrackerSettings
    val tracker =
        if (reloaded.trackerKind != previous.trackerKind) {
            listOf(
                "tracker.kind changed to ${reloaded.trackerKind}, " +
                    "Loop keeps reading ${trackerLocation(previous)} until loop run is restarted",
            )
        } else if (reloaded.trackerKind == FILES_TRACKER && reloaded.trackerDir != previous.trackerDir) {
            listOf(
                "tracker.provider.dir changed to ${reloaded.trackerDir}, " +
                    "Loop keeps reading ${previous.trackerDir} until loop run is restarted",
            )
        } else if (previousGitHub != null && reloadedGitHub != null && previousGitHub != reloadedGitHub) {
            // The settings never print the token, so the warning names the repository only.
            listOf(
                "tracker.provider changed to ${reloadedGitHub.owner}/${reloadedGitHub.repo}, " +
                    "Loop keeps reading ${previousGitHub.owner}/${previousGitHub.repo} until loop run is restarted",
            )
        } else {
            emptyList()
        }
    // m3-05: an unknown key a reload adds is warned about once, like at startup.
    val unknown = reloaded.configWarnings.filter { it !in previous.configWarnings }
    val known = shortSecretNames(previous, env).toSet()
    val short =
        shortSecretNames(reloaded, env)
            .filter { it !in known }
            .map { "\$$it is under ${SecretRedactor.MIN_SECRET_LENGTH} characters, so Loop does not redact it" }
    return tracker + unknown + short
}

private fun secretRefs(
    trackerProviders: List<Map<String, ConfigValue>>,
    env: Map<String, String>,
): Map<String, String> {
    val names =
        trackerProviders.flatMap { provider ->
            provider
                .filterKeys { it !in PATH_KEYS }
                .values
                .flatMap { it.credentialRefs() }
        } + ANTHROPIC_KEYS + GOOGLE_KEYS
    return envValues(names.distinct(), env)
}

/**
 * The names this value references as exactly `$NAME`, at any depth of a map or list: the credential
 * rule the tool config snapshot uses to omit a field (m1-23).
 */
private fun ConfigValue.credentialRefs(): List<String> =
    when (this) {
        is ConfigValue.Str -> listOfNotNull(ENV_REF.matchEntire(value)?.groupValues?.get(1))
        is ConfigValue.ListValue -> items.flatMap { it.credentialRefs() }
        is ConfigValue.MapValue -> entries.values.flatMap { it.credentialRefs() }
        ConfigValue.Null -> emptyList()
    }

private fun envValues(
    names: List<String>,
    env: Map<String, String>,
): Map<String, String> =
    names
        .mapNotNull { name -> env[name]?.takeIf { it.isNotBlank() }?.let { name to it } }
        .toMap()

/** The Anthropic credentials the Claude Code runner drops from the agent env, redacted wherever they show up. */
private val ANTHROPIC_KEYS = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

/** The Google API keys the agy preset drops from the agent env, redacted wherever they show up. */
private val GOOGLE_KEYS = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY")

/** `tracker.provider` keys that hold a path, not a credential. */
private val PATH_KEYS = setOf("dir")

private val ENV_REF = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * m3-21: the startup warning of a workflow whose runner reports no cost, the Codex runner and the command runner among
 * them, while it sets dollar budgets: such a runner spends nothing against them, since Loop keeps no price table, so
 * they never warn nor park. Null when the runner reports a cost per [capabilities] or no dollar budget is set. Token
 * budgets still hold, as the warning says.
 */
fun dollarBudgetWarning(
    workflow: WorkflowFile,
    capabilities: RunnerCapabilities,
): String? {
    if (capabilities.cost) return null
    val budgets = workflow.config.budgets
    val set =
        listOf(
            "budgets.per_attempt_usd" to budgets.perAttemptUsd,
            "budgets.per_issue_usd" to budgets.perIssueUsd,
            "budgets.per_day_usd" to budgets.perDayUsd,
        ).filter { it.second != null }.map { it.first }
    if (set.isEmpty()) return null
    val named = if (set.size == 1) "${set.single()} is" else "${set.dropLast(1).joinToString(", ")} and ${set.last()} are"
    return "$named set, but the ${workflow.runner.kind} runner reports no cost, so dollar budgets do not hold for it: " +
        "its attempts spend nothing against them. Token budgets still hold"
}

/**
 * The startup checks of the runner named by `runner.kind` on this machine: an error that stops the run, and warnings.
 * `claude-code` checks root uid, managed settings and, since bl-210, that `<command> --version` is of the pinned major
 * version, the preflight built by [claudeCode] from `runners.claude-code.command` and [hostEnv]; `acp` with
 * `preset: agy` checks binary reachability, pinned major version `1`, and warns on login or model list issues. The
 * check of a plugin's section against its profile (m3-05) is the loader's, on every load.
 */
fun runnerPreflight(
    workflow: WorkflowFile,
    hostEnv: Map<String, String> = System.getenv(),
    claudeCode: (command: String, hostEnv: Map<String, String>) -> ClaudeCodePreflight = { command, env ->
        ClaudeCodePreflight(command, hostEnv = env)
    },
): PreflightResult =
    when (workflow.runner.kind) {
        ClaudeCodeSettings.KIND -> {
            val settings = ClaudeCodeSettings.of(workflow.runner.section(ClaudeCodeSettings.KIND))
            claudeCode(settings.command, hostEnv).check()
        }
        AcpSettings.KIND -> {
            val settings = AcpSettings.of(workflow.runner.section(AcpSettings.SECTION))
            if (settings.isAgyPreset) {
                val result = AgyPreflight(settings.command, settings.model, hostEnv).check()
                PreflightResult(error = result.error, warnings = result.warnings)
            } else {
                PreflightResult()
            }
        }
        else -> PreflightResult()
    }

/**
 * The tracker check of the preflight (plan D2), run at startup and every tick:
 * `tracker.kind` has a provider among [plugins], and every configured state is one it defines.
 * A sentence naming the problem, or null when the settings are fine.
 */
fun trackerProblem(
    workflow: WorkflowFile,
    plugins: TrackerPlugins = TrackerPlugins.classpath,
): String? {
    // The provider knows its states without building the tracker, so no HTTP client is built every tick.
    val defined = plugins.provider(workflow.trackerKind)?.definedStates ?: return unsupportedTracker(workflow, plugins)
    return listOf(
        "tracker.active_states" to workflow.activeStates,
        "tracker.terminal_states" to workflow.terminalStates,
    ).firstNotNullOfOrNull { (key, states) ->
        states.firstOrNull { it !in defined }?.let { unknown ->
            "$key has \"$unknown\", which is not a state of the ${workflow.trackerKind} tracker. " +
                "Valid states: ${defined.joinToString(", ")}"
        }
    } ?: gitHubHandoffProblem(workflow)
}

/**
 * The github tracker's handoff checks, null when they pass or for another tracker. A handoff state is made of labels
 * on an `open` issue, so a workflow must let a move to one take the issue out of the active set:
 * - bl-46: no handoff state in `tracker.active_states` or `tracker.terminal_states`. It matches no issue, and the
 *   orchestrator would not see a move to it as a handoff, so the worker would go on.
 * - bl-46: `tracker.required_labels` not empty once trimmed. A handoff move removes the required labels, and the
 *   tracker refuses it when there are none, so every handoff would fail at the drain after the work is done.
 * - m2-23: no handoff label that is also a required label, compared case insensitively, since the very move that adds
 *   it would remove it.
 */
private fun gitHubHandoffProblem(workflow: WorkflowFile): String? {
    val settings = workflow.trackerSettings as? GitHubTrackerSettings ?: return null
    val listed =
        listOf(
            "tracker.active_states" to workflow.activeStates,
            "tracker.terminal_states" to workflow.terminalStates,
        ).firstNotNullOfOrNull { (key, states) ->
            // The state check above already refused any spelling that is not exactly a defined state.
            states.firstOrNull { it in GitHubTracker.HANDOFF_STATES }?.let { state ->
                "$key has \"$state\", a handoff state of the github tracker. A handoff state matches no issue, " +
                    "and a move to it would not stop the worker, so list only open and closed there"
            }
        }
    val required =
        workflow.requiredLabels
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    return listed
        ?: if (required.isEmpty()) {
            "tracker.required_labels is empty, so every move to ${GitHubTracker.HANDOFF_STATES.joinToString(" or ")} " +
                "would be refused: the move removes the required labels to take the issue out of the active set. " +
                "Add the label that routes an issue to Loop, for example ready-for-agent"
        } else {
            handoffLabelProblem(settings, required)
        }
}

/** m2-23: the first handoff label that is also one of the [required] labels, given trimmed and lowercased. */
private fun handoffLabelProblem(
    settings: GitHubTrackerSettings,
    required: Set<String>,
): String? =
    settings.handoffLabels.entries.firstOrNull { it.value.trim().lowercase() in required }?.let { (state, label) ->
        "tracker.provider.handoff_labels.$state is \"$label\", which is also in tracker.required_labels, so a move to $state would remove it"
    }

/** An exception message as the end of a sentence, without its own final period. */
fun Throwable.sentence(): String = (message ?: this::class.simpleName ?: "unknown error").trimEnd('.')

private const val PROJECT_ID_HEX_CHARS = 16

private const val FILES_TRACKER = "files"

/** The folder of plugin JARs under `LOOP_HOME` (m3-04). */
private const val PLUGINS_DIR = "plugins"
