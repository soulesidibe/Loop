package loop.core.workflow

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import loop.api.MergeMethod
import loop.api.PluginProfile
import loop.api.ProfileProblem
import loop.core.delivery.AttachDelivery
import loop.core.kinds.KindWorkspaces
import loop.core.trackers.TrackerEntry
import loop.core.trackers.TrackerParser
import loop.core.trackers.TrackersConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path

/**
 * A loaded `WORKFLOW.md`: the tracker, workspace and runner keys read in m1-02,
 * the typed sections in [config], and the prompt body.
 */
data class WorkflowFile(
    /** Absolute path of the `WORKFLOW.md` that was read. */
    val path: Path,
    val trackerKind: String,
    val trackerDir: Path,
    val activeStates: List<String>,
    val terminalStates: List<String>,
    val requiredLabels: List<String>,
    val workspaceRoot: Path,
    val runner: RunnerSettings,
    val pollingIntervalMs: Long,
    val promptTemplate: String,
    /** The typed sections with spec defaults (m1-03). */
    val config: WorkflowConfig = WorkflowConfig(),
    /**
     * SHA-256 hex of the file text, [workflowHash] of [sourceText]: the config version an Attempt ran with,
     * and how a reload is detected (m1-15). Empty when not read from a file.
     */
    val sourceHash: String = "",
    /** The file text as read, for the diff of a reload (m1-15). Empty when not read from a file. */
    val sourceText: String = "",
    /**
     * What the [TrackerProvider] of [trackerKind] read from `tracker.provider` (bl-23, m3-05), such
     * as the GitHub tracker's settings; null for the `files` tracker, which uses [trackerDir].
     */
    val trackerSettings: Any? = null,
    /**
     * m3-05: one warning per key of a selected plugin's section that its profile does not list, `tracker.provider`
     * and `runners.<section>`, without a final period. Unknown keys stay allowed, for forward compatibility.
     */
    val configWarnings: List<String> = emptyList(),
    /**
     * bl-70: the secrets a loader built with `deferSecrets` could not read, in the order the load met them. Always
     * empty for a loader that requires secrets, which fails on the first one instead.
     */
    val deferredSecrets: List<DeferredSecret> = emptyList(),
    /** m4-11: the primary tracker's id, `tracker.id` or [TrackersConfig.DEFAULT_ID]. */
    val trackerId: String = TrackersConfig.DEFAULT_ID,
    /** m4-11: the extra trackers of `trackers:`, in the order written, none when it is absent. Polled since m4-13. */
    val extraTrackers: List<TrackerEntry> = emptyList(),
    /**
     * m4-42: the sections of `exporters:` as written, by exporter key, each turning one exporter plugin on. Empty when
     * it is absent: nothing is exported. The host checks each section against its plugin in the preflight.
     */
    val exporters: Map<String, Any?> = emptyMap(),
) {
    /** m4-11: every tracker of the workflow, the primary one built from the `tracker*` fields above. */
    val trackers: TrackersConfig
        get() =
            TrackersConfig(
                primary =
                    TrackerEntry(
                        id = trackerId,
                        kind = trackerKind,
                        dir = trackerDir,
                        settings = trackerSettings,
                        activeStates = activeStates,
                        terminalStates = terminalStates,
                        requiredLabels = requiredLabels,
                        provider = config.trackerProvider,
                    ),
                extras = extraTrackers,
            )
}

/**
 * bl-70: a secret, the `$NAME` value at [keyPath], whose variable is unset or, when [empty], blank, which a load with
 * deferred secrets named instead of failing. [sentence] names the key and the variable, never a value.
 */
data class DeferredSecret(
    val keyPath: String,
    val name: String,
    val empty: Boolean,
) {
    val sentence: String get() = "$keyPath uses \$$name, which is ${if (empty) "empty" else "not set"}"
}

/** The text of a `WORKFLOW.md` as read, before any parsing, and the real path of the file. */
data class WorkflowSource(
    val file: Path,
    val text: String,
) {
    val hash: String get() = workflowHash(text)
}

/** SHA-256 hex of a `WORKFLOW.md` text: its config version (m1-15). */
fun workflowHash(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Reads a `WORKFLOW.md` per spec 5.2: optional front matter between `---`
 * lines, the rest is the trimmed prompt body. Unknown keys are ignored.
 * Relative paths resolve against the folder of the file. A path value that is
 * exactly `$NAME` reads `NAME` from [env] first (spec 6.1, plan D3).
 * `tracker.kind` selects a tracker plugin through [trackerCheck], which reads its own `tracker.provider` (bl-23, m3-05).
 * `trackers:` lists extra trackers, each read the same way by [TrackerParser] (m4-11).
 * `runner.kind` is required, and [runnerCheck] tells whether a runner plugin of that kind accepts its section (m3-04).
 * Each selected plugin's section is first checked against the plugin's profile (m3-05), see [checkProfile].
 * With [deferSecrets] (bl-70), an unset secret is named in [WorkflowFile.deferredSecrets] instead of failing the load.
 */
class WorkflowLoader(
    /** The raw process env, blank values included. */
    private val env: Map<String, String>,
    private val systemTemp: Path = Path(System.getProperty("java.io.tmpdir")),
    private val home: Path = Path(System.getProperty("user.home")),
    /**
     * The tracker plugins the host found (m3-05), `files` among them. Null, for spec-core and conformance tests that
     * see no tracker plugin, accepts only `files`, with its default states and no settings.
     */
    private val trackerCheck: TrackerCheck? = null,
    /** The runner kinds the host found (m3-04); null accepts any `runner.kind` and leaves its section unread. */
    private val runnerCheck: RunnerCheck? = null,
    /**
     * bl-70: true for the commands that only read the store and the log. A secret whose `$NAME` is unset or blank,
     * of the tracker, the runner or the pull request delivery, is then named in [WorkflowFile.deferredSecrets]
     * instead of failing the load, and every other check runs as usual. The tracker plugin never sees a missing
     * token: [WorkflowFile.trackerSettings] is null when its secret was deferred. A `$NAME` path is not a secret and
     * still fails.
     */
    private val deferSecrets: Boolean = false,
) {
    fun load(path: Path): Result<WorkflowFile> = readSource(path).fold(onSuccess = ::parse, onFailure = { Result.failure(it) })

    /** The text of the file at [path], with no parsing; fails with [WorkflowError.MissingFile]. */
    fun readSource(path: Path): Result<WorkflowSource> =
        try {
            val (file, text) = readFile(path)
            Result.success(WorkflowSource(file, text))
        } catch (e: WorkflowError) {
            Result.failure(e)
        }

    /** The workflow of a text [readSource] read, so a caller keeps the exact text it validated (m1-15). */
    fun parse(source: WorkflowSource): Result<WorkflowFile> =
        try {
            Result.success(read(source.file, source.text))
        } catch (e: WorkflowError) {
            Result.failure(e)
        }

    /**
     * Best effort: the `tracker.provider` map of the front matter at [path], then the `provider` map of each entry of
     * `trackers:` that has one (m4-11), as written, with none of [load]'s validation, so the secrets they name can be
     * learned before a load or a preflight that may fail (m1-11). Never throws and never empty: an unreadable file,
     * front matter that is not yaml or a `tracker.provider` that is not a map gives an empty map first.
     */
    fun trackerProviders(path: Path): List<Map<String, ConfigValue>> =
        try {
            val (_, text) = readFile(path)
            val front = parseFrontMatter(split(text).first)
            TrackerParser.providersOf(front.node("tracker")?.toPlain(), front.node("trackers")?.toPlain())
        } catch (e: Exception) {
            listOf(emptyMap())
        }

    private fun read(
        file: Path,
        text: String,
    ): WorkflowFile {
        val (frontMatter, body) = split(text)
        val config = parseFrontMatter(frontMatter)
        val folder = file.parent
        val envRefs = mutableListOf<EnvRef>()
        val deferred = mutableListOf<DeferredSecret>()

        val configWarnings = mutableListOf<String>()
        // m4-11: the primary tracker, then the extra trackers of trackers:, each read by its own tracker plugin.
        val parser =
            TrackerParser(env, trackerCheck, deferSecrets, configWarnings, deferred) { keyPath, value ->
                resolve(folder, value?.let { expandEnv(keyPath, it, envRefs) } ?: DEFAULT_TRACKER_DIR)
            }
        val tracker = parser.primary(config.child("tracker")?.toPlainMap())
        val extraTrackers = parser.extras(config.node("trackers")?.toPlain(), tracker.id)

        val runnerKind =
            config.child("runner")?.string("runner.kind", "kind")
                ?: throw WorkflowError.InvalidConfig("runner.kind is required")
        val runner =
            RunnerSettings(
                kind = runnerKind,
                sections =
                    config
                        .child("runners")
                        ?.entries
                        ?.entries
                        ?.associate { it.key.content to it.value.toPlain() }
                        .orEmpty(),
            )
        runnerCheck?.profile(runner.kind)?.let { (section, profile) ->
            val key = "runners.$section"
            val written = runner.sections[section] ?: emptyMap<String, Any?>()
            val map =
                (written as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
                    ?: throw WorkflowError.InvalidConfig("$key must be a map")
            configWarnings +=
                checkPluginProfile(profile, key, map, "the ${runner.kind} runner", secretEnv(profile, key, map, deferred)) { problem ->
                    if (problem is ProfileProblem.UnresolvedSecret) {
                        WorkflowError.MissingEnvVar(problem.envName, problem.key, problem.empty)
                    } else {
                        WorkflowError.InvalidConfig(problem.message)
                    }
                }
        }
        runnerCheck?.problem(runner)?.let { throw WorkflowError.InvalidConfig(it) }

        val exporters =
            config
                .child("exporters")
                ?.entries
                ?.entries
                ?.associate { it.key.content to it.value.toPlain() }
                .orEmpty()

        val polling = parsePolling(config.child("polling"))
        val hooks = parseHooks(config.child("hooks"))
        val agent = parseAgent(config.child("agent"), listOf(tracker.id) + extraTrackers.map { it.id })
        val codex = parseCodex(config.child("codex"))
        val retries = parseRetries(config.child("retries"))
        val budgets = parseBudgets(config.child("budgets"))
        val context = parseContext(config.child("context"))
        val observability = parseObservability(config.child("observability"))
        val kinds = parseKinds(config, folder, tracker.kind, listOf(tracker.id) + extraTrackers.map { it.id }, deferred)

        val workspaceRoot =
            config
                .child("workspace")
                ?.string("workspace.root", "root")
                ?.let { resolve(folder, expandEnv("workspace.root", it, envRefs)) }
                ?: systemTemp.resolve(DEFAULT_WORKSPACES_DIR).toAbsolutePath().normalize()

        val terminalStates = tracker.terminalStates
        // m2-26: a merged pull request is done, so its issue leaves the tracker's work for good. Checked for every kind
        // since m4-18.
        kinds?.named?.forEach { (name, kind) ->
            val moveTo = kind.delivery.onDeliveredMoveTo ?: return@forEach
            if (terminalStates.none { it.trim().equals(moveTo, ignoreCase = true) }) {
                throw WorkflowError.InvalidConfig(
                    "kinds.$name.delivery.on_delivered.move_to is \"$moveTo\", which is not a terminal state, " +
                        "use one of ${terminalStates.joinToString(", ")}",
                )
            }
        }
        return WorkflowFile(
            path = file,
            trackerKind = tracker.kind,
            trackerDir = tracker.dir,
            activeStates = tracker.activeStates,
            terminalStates = terminalStates,
            requiredLabels = tracker.requiredLabels,
            workspaceRoot = workspaceRoot,
            runner = runner,
            pollingIntervalMs = polling.intervalMs,
            promptTemplate = body,
            config =
                WorkflowConfig(
                    polling = polling,
                    hooks = hooks,
                    agent = agent,
                    codex = codex,
                    retries = retries,
                    budgets = budgets,
                    context = context,
                    observability = observability,
                    kinds = kinds,
                    trackerProvider = tracker.provider,
                    envRefs = envRefs.toList(),
                    referencedEnvNames = config?.referencedEnvNames().orEmpty(),
                ),
            sourceHash = workflowHash(text),
            sourceText = text,
            trackerSettings = tracker.settings,
            configWarnings = configWarnings.toList(),
            deferredSecrets = deferred.toList(),
            trackerId = tracker.id,
            extraTrackers = extraTrackers,
            exporters = exporters,
        )
    }

    /** bl-70: the env to check a plugin [section] at [key] with, see [secretCheckEnv]. */
    private fun secretEnv(
        profile: PluginProfile,
        key: String,
        section: Map<String, Any?>,
        deferred: MutableList<DeferredSecret>,
    ): Map<String, String> = secretCheckEnv(env, deferSecrets, profile, key, section, deferred)

    /**
     * Plan D3: only a whole `$NAME` value is a reference; anything else is kept
     * as written. Unset or blank fails, naming the variable, never its value.
     */
    private fun expandEnv(
        keyPath: String,
        value: String,
        refs: MutableList<EnvRef>,
    ): String {
        val name = ENV_REF.matchEntire(value)?.groupValues?.get(1) ?: return value
        val resolved = env[name] ?: throw WorkflowError.MissingEnvVar(name, keyPath, empty = false)
        if (resolved.isBlank()) throw WorkflowError.MissingEnvVar(name, keyPath, empty = true)
        refs += EnvRef(name, keyPath)
        return resolved
    }

    private fun parsePolling(polling: YamlMap?): PollingConfig {
        val defaults = PollingConfig()
        return PollingConfig(
            intervalMs = polling.positiveLong("polling.interval_ms", "interval_ms") ?: defaults.intervalMs,
        )
    }

    private fun parseHooks(hooks: YamlMap?): HooksConfig {
        val defaults = HooksConfig()
        return HooksConfig(
            afterCreate = hooks.string("hooks.after_create", "after_create"),
            beforeRun = hooks.string("hooks.before_run", "before_run"),
            afterRun = hooks.string("hooks.after_run", "after_run"),
            beforeRemove = hooks.string("hooks.before_remove", "before_remove"),
            timeoutMs = hooks.positiveLong("hooks.timeout_ms", "timeout_ms") ?: defaults.timeoutMs,
            onTokenThreshold = hooks.string("hooks.on_token_threshold", "on_token_threshold"),
            onBudgetWarning = hooks.string("hooks.on_budget_warning", "on_budget_warning"),
        )
    }

    /** [trackerIds] are the ids of the workflow's trackers, the only keys `max_concurrent_agents_by_tracker` takes (m4-15). */
    private fun parseAgent(
        agent: YamlMap?,
        trackerIds: List<String>,
    ): AgentConfig {
        val defaults = AgentConfig()
        return AgentConfig(
            maxConcurrentAgents =
                agent.positiveInt("agent.max_concurrent_agents", "max_concurrent_agents")
                    ?: defaults.maxConcurrentAgents,
            maxTurns = agent.positiveInt("agent.max_turns", "max_turns") ?: defaults.maxTurns,
            maxWakeTurns = agent.positiveInt("agent.max_wake_turns", "max_wake_turns") ?: defaults.maxWakeTurns,
            maxRetryBackoffMs =
                agent.positiveLong("agent.max_retry_backoff_ms", "max_retry_backoff_ms")
                    ?: defaults.maxRetryBackoffMs,
            maxConcurrentAgentsByState =
                agent.child("max_concurrent_agents_by_state", "agent.max_concurrent_agents_by_state").perState(),
            maxConcurrentAgentsByTracker = agent.perTracker(trackerIds),
            env = agent.child("env", "agent.env").agentEnv(),
        )
    }

    /**
     * bl-01: `agent.env`, env var names to literal values. A value that is exactly `$NAME` would read the
     * host env, so it fails here, and so does a name Loop sets itself or always removes. `{workspace}` is
     * kept as written, the runner replaces it.
     */
    private fun YamlMap?.agentEnv(): Map<String, String> =
        this
            ?.entries
            ?.entries
            ?.associate { (key, value) ->
                val name = key.content
                val keyPath = "agent.env.$name"
                when {
                    !ENV_NAME.matches(name) ->
                        throw WorkflowError.InvalidConfig("agent.env key \"$name\" must be an environment variable name")
                    name == "PATH" || name.startsWith("LOOP_") ->
                        throw WorkflowError.InvalidConfig("$keyPath is set by Loop and cannot be overridden")
                    name in AGENT_ENV_NEVER_PASSED ->
                        throw WorkflowError.InvalidConfig("$keyPath is never passed to the agent")
                }
                val text = (value as? YamlScalar)?.content ?: throw WorkflowError.InvalidConfig("$keyPath must be a string")
                ENV_REF.matchEntire(text)?.let { ref ->
                    val host = ref.groupValues[1]
                    throw WorkflowError.InvalidConfig("$keyPath must be a literal value, \$$host would read the host variable $host")
                }
                name to text
            }.orEmpty()

    /**
     * m2-03: `kinds:`. Absent is Symphony mode. Present, it must hold a `code` kind, and every kind an explicit
     * `verify.checks` list, so an empty or misspelled key never switches verification off silently. Since m4-18 it is
     * a map of named kinds, read in the order written, each with the slots of `code` and a `match` ([parseMatch]).
     * Exactly one kind is the default: `code` when it has no `match`, else the kind with `match: { default: true }`.
     */
    private fun parseKinds(
        config: YamlMap?,
        folder: Path,
        trackerKind: String,
        trackerIds: List<String>,
        deferred: MutableList<DeferredSecret>,
    ): KindsConfig? {
        val entry = config?.entries?.entries?.firstOrNull { it.key.content == "kinds" } ?: return null
        val kinds =
            when (val node = entry.value) {
                is YamlNull -> null
                is YamlMap -> node
                else -> throw WorkflowError.InvalidConfig("kinds must be a map")
            }
        val names =
            kinds
                ?.entries
                ?.keys
                ?.map { it.content }
                .orEmpty()
        if (KindsConfig.CODE !in names) throw WorkflowError.InvalidConfig("kinds must be a map with a code kind")
        names.firstOrNull { !KIND_NAME.matches(it) }?.let {
            throw WorkflowError.InvalidConfig("kinds.$it is not a kind name, use lowercase letters, digits and -, starting with a letter")
        }
        val named = names.associateWith { name -> parseKind(name, kinds, folder, trackerKind, trackerIds, deferred) }
        val defaults = named.filterValues { it.match.default }.keys.toList()
        when {
            defaults.isEmpty() -> throw WorkflowError.InvalidConfig("kinds has no default kind, give one kind match: { default: true }")
            defaults.size > 1 -> throw WorkflowError.InvalidConfig(
                "${defaults.joinToString(" and ") { "kinds.$it" }} are both the default kind, keep one match: { default: true }, " +
                    "code is the default when it has no match",
            )
        }
        return KindsConfig(named)
    }

    /** m4-18: the kind [name] of [kinds], read like `kinds.code` always was, under its own name. */
    private fun parseKind(
        name: String,
        kinds: YamlMap?,
        folder: Path,
        trackerKind: String,
        trackerIds: List<String>,
        deferred: MutableList<DeferredSecret>,
    ): KindConfig {
        val kindKey = "kinds.$name"
        val kind = kinds.child(name, kindKey)
        val verify = kind.child("verify", "$kindKey.verify") ?: throw WorkflowError.InvalidConfig("$kindKey.verify is required")
        val guards = parseGuards(kindKey, verify)
        val listed =
            when (
                val node =
                    verify.entries.entries
                        .firstOrNull { it.key.content == "checks" }
                        ?.value
            ) {
                null, is YamlNull -> throw WorkflowError.InvalidConfig("$kindKey.verify.checks is required")
                is YamlList ->
                    node.items
                        .mapIndexed { index, item -> parseCheck(kindKey, "$kindKey.verify.checks[$index]", item, guards) }
                        .alsoOnce(kindKey)
                else -> throw WorkflowError.InvalidConfig("$kindKey.verify.checks must be a list")
            }
        // m2-12: a guard is never silently off, so guards without a diff_policy check add one, last, so the listed
        // checks keep their numbers. An empty list stays empty: a guard alone proves no work.
        val checks =
            if (guards != null && listed.isNotEmpty() && listed.none { it is CheckConfig.DiffPolicy }) {
                listed + CheckConfig.DiffPolicy(guards.protect, guards.base, guards.deny, guards.languages)
            } else {
                listed
            }
        val ready = parseReady(kindKey, kind)
        val holdout = parseHoldout(kindKey, verify, guards)
        val workspace = parseChoice(kindKey, kind, "workspace", KindWorkspace.entries, KindWorkspace::wire) ?: KindWorkspace.Git
        val populate = parseChoice(kindKey, kind, "populate", KindPopulate.entries, KindPopulate::wire) ?: KindPopulate.Hooks
        // m4-20: a plain kind's git only settings are refused here, before dispatch, never found as a park.
        val pullRequest = kind.child("delivery", "$kindKey.delivery").node("pull_request") != null
        KindWorkspaces.refusal(kindKey, workspace, populate, listed, holdout, guards, pullRequest)?.let {
            throw WorkflowError.InvalidConfig(it)
        }
        // m4-18: the merge check, the auto merge and the pinned diff base read the code kind alone until each kind
        // takes them up (m4-20 to m4-23), so no other kind may deliver a pull request yet.
        if (name != KindsConfig.CODE && kind.child("delivery", "$kindKey.delivery").node("pull_request") != null) {
            throw WorkflowError.InvalidConfig(
                "$kindKey.delivery.pull_request is not supported yet, only the code kind delivers a pull request",
            )
        }
        val deliverable = parseDeliverable(kindKey, kind)
        val requireSections = parseRequireSections(kindKey, verify, deliverable)
        val delivery = parseDelivery(kindKey, kind, trackerKind, deferred, deliverable)
        val match = parseMatch(kindKey, kind, trackerIds) ?: if (name == KindsConfig.CODE) KindMatch.DEFAULT else null
        return KindConfig(
            checks,
            ready,
            guards,
            holdout,
            delivery,
            match ?: throw WorkflowError.InvalidConfig("$kindKey.match is required, only the code kind is the default without one"),
            context = parseContextSources(kindKey, kind),
            template = parseKindTemplate(kindKey, kind, folder),
            workspace = workspace,
            populate = populate,
            deliverable = deliverable,
            requireSections = requireSections,
        )
    }

    /**
     * m4-21: `kinds.<kind>.deliverable`, none when absent. Present, a map whose only key is `files`, a list of at least
     * one non blank glob relative to the workspace: never absolute, never through `..`. m4-23: or whose only key is
     * `report`, one path relative to the workspace, which names a file: no `*` or `?`, no trailing `/`. Any other key is
     * refused, so a misspelled key never drops the deliverable silently.
     */
    private fun parseDeliverable(
        kindKey: String,
        kind: YamlMap?,
    ): KindDeliverable {
        val name = "$kindKey.deliverable"
        val deliverable = kind.child("deliverable", name) ?: return KindDeliverable()
        val keys = deliverable.entries.keys.map { it.content }
        keys
            .firstOrNull { it != "files" && it != "report" }
            ?.let { key -> throw WorkflowError.InvalidConfig("$name.$key is not supported, use files or report") }
        if ("files" in keys && "report" in keys) throw WorkflowError.InvalidConfig("$name holds files and report, use one of them")
        if ("report" in keys) return KindDeliverable(report = parseReport(name, deliverable))
        val files = deliverable.strings("$name.files", "files").orEmpty()
        if (files.isEmpty()) throw WorkflowError.InvalidConfig("$name.files must hold at least one glob")
        if (files.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name.files must not hold a blank glob")
        val globs = files.map { it.trim() }
        globs.forEachIndexed { index, glob ->
            if (glob.startsWith("/") || Path(glob).isAbsolute || glob.split('/', '\\').any { it == ".." }) {
                throw WorkflowError.InvalidConfig("$name.files[$index] is $glob, use a glob relative to the workspace")
            }
        }
        return KindDeliverable(globs)
    }

    /** m4-23: `deliverable.report` of the deliverable map named [name], one path to a file relative to the workspace. */
    private fun parseReport(
        name: String,
        deliverable: YamlMap,
    ): String {
        val key = "$name.report"
        val report = deliverable.string(key, "report")?.trim()
        if (report.isNullOrEmpty()) throw WorkflowError.InvalidConfig("$key must not be blank")
        if (report.startsWith("/") || Path(report).isAbsolute || report.split('/', '\\').any { it == ".." }) {
            throw WorkflowError.InvalidConfig("$key is $report, use a path relative to the workspace")
        }
        if ('*' in report || '?' in report) throw WorkflowError.InvalidConfig("$key is $report, name one file, without * or ?")
        if (report.endsWith("/")) throw WorkflowError.InvalidConfig("$key is $report, name one file, not a folder")
        return report
    }

    /**
     * m4-23: `kinds.<kind>.verify.require_sections`, none when absent. Present, a non empty list of non blank section
     * names, and only on a kind whose [deliverable] is a report, the text they are read in.
     */
    private fun parseRequireSections(
        kindKey: String,
        verify: YamlMap,
        deliverable: KindDeliverable,
    ): List<String> {
        val name = "$kindKey.verify.require_sections"
        val sections = verify.strings(name, "require_sections") ?: return emptyList()
        if (deliverable.report == null) {
            throw WorkflowError.InvalidConfig("$name needs $kindKey.deliverable.report, the report it reads")
        }
        if (sections.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one section")
        if (sections.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank name")
        return sections.map { it.trim() }
    }

    /**
     * m4-20: `kinds.<kind>.<key>`, null when absent; present, one of the [choices] by its [wire] name, any other value
     * refused, so a misspelled value never falls back to the default silently.
     */
    private fun <T> parseChoice(
        kindKey: String,
        kind: YamlMap?,
        key: String,
        choices: List<T>,
        wire: (T) -> String,
    ): T? {
        val name = "$kindKey.$key"
        val written = kind.string(name, key) ?: return null
        return choices.firstOrNull { wire(it) == written.trim() }
            ?: throw WorkflowError.InvalidConfig("$name must be ${choices.joinToString(" or ") { wire(it) }}, got \"$written\"")
    }

    /**
     * m4-19: `kinds.<kind>.context`, no sources when absent. Present, a map whose only key is `files`, a list of non
     * blank paths relative to the workspace: never absolute, never through `..`, so a pointer stays in the workspace. Any
     * other key is refused, so a misspelled key never drops the sources silently.
     */
    private fun parseContextSources(
        kindKey: String,
        kind: YamlMap?,
    ): KindContext {
        val name = "$kindKey.context"
        val context = kind.child("context", name) ?: return KindContext()
        context.entries.keys
            .map { it.content }
            .firstOrNull { it != "files" }
            ?.let { throw WorkflowError.InvalidConfig("$name.$it is not supported, use files") }
        val files = context.strings("$name.files", "files") ?: return KindContext()
        if (files.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name.files must not hold a blank path")
        val paths = files.map { it.trim() }
        paths.forEachIndexed { index, path ->
            if (path.startsWith("/") || Path(path).isAbsolute || path.split('/', '\\').any { it == ".." }) {
                throw WorkflowError.InvalidConfig("$name.files[$index] is $path, use a path relative to the workspace")
            }
        }
        return KindContext(paths)
    }

    /**
     * m4-19: `kinds.<kind>.template`, null when absent, so the kind renders the `WORKFLOW.md` body. Present, a non blank
     * path relative to the folder of `WORKFLOW.md` (or absolute, or under `~`), read now, with the workflow, and trimmed
     * like the body. A path that does not exist or cannot be read is refused.
     */
    private fun parseKindTemplate(
        kindKey: String,
        kind: YamlMap?,
        folder: Path,
    ): KindTemplate? {
        val name = "$kindKey.template"
        val written = kind.string(name, "template") ?: return null
        val path = written.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$name must not be blank")
        val file = resolve(folder, path)
        if (!Files.isRegularFile(file)) throw WorkflowError.InvalidConfig("$name is $path, which does not exist")
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw WorkflowError.InvalidConfig("$name is $path, which cannot be read: ${e.message}")
            }
        return KindTemplate(path, text.trim())
    }

    /**
     * m4-18: `kinds.<kind>.match`, null when absent. Present, a map of the keys [MATCH_KEYS], at least one set:
     * `default: true` alone, or any of `label`, `tracker`, a tracker id of [trackerIds], and `state`, each a non blank
     * string. Any other key, a front matter field included, is refused, so a match never holds by a key Loop ignores.
     */
    private fun parseMatch(
        kindKey: String,
        kind: YamlMap?,
        trackerIds: List<String>,
    ): KindMatch? {
        val name = "$kindKey.match"
        val entry = kind?.entries?.entries?.firstOrNull { it.key.content == "match" } ?: return null
        val match = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$name must be a map")
        match.entries.keys
            .map { it.content }
            .firstOrNull { it !in MATCH_KEYS }
            ?.let { throw WorkflowError.InvalidConfig("$name.$it is not supported, use default, label, tracker or state") }
        val default =
            match.string("$name.default", "default")?.let { written ->
                when (written.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw WorkflowError.InvalidConfig("$name.default must be true or false, got \"$written\"")
                }
            } ?: false

        fun nonBlank(key: String): String? =
            match.string("$name.$key", key)?.let { written ->
                written.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$name.$key must not be blank")
            }
        val label = nonBlank("label")?.lowercase()
        val tracker = nonBlank("tracker")
        val state = nonBlank("state")
        if (tracker != null && tracker !in trackerIds) {
            throw WorkflowError.InvalidConfig("$name.tracker \"$tracker\" is not a tracker id, use one of ${trackerIds.joinToString(", ")}")
        }
        val keys = listOfNotNull(label, tracker, state)
        if (default && keys.isNotEmpty()) throw WorkflowError.InvalidConfig("$name.default is alone in its match")
        if (!default && keys.isEmpty()) throw WorkflowError.InvalidConfig("$name must hold default, label, tracker or state")
        return if (default) KindMatch.DEFAULT else KindMatch(label = label, tracker = tracker, state = state)
    }

    /**
     * m2-14: `kinds.<kind>.verify.holdout`, the hidden checks. Absent is none. Present, it must be a list of checks
     * of the same shape as `checks`, but never `acceptance_commands` (m2-09); `holdout: ~` is an error too, so an
     * empty key never drops them silently.
     */
    private fun parseHoldout(
        kindKey: String,
        verify: YamlMap,
        guards: GuardsConfig?,
    ): List<CheckConfig> {
        val name = "$kindKey.verify.holdout"
        val entry = verify.entries.entries.firstOrNull { it.key.content == "holdout" } ?: return emptyList()
        val list = entry.value as? YamlList ?: throw WorkflowError.InvalidConfig("$name must be a list")
        return list.items.mapIndexed { index, item ->
            parseCheck(kindKey, "$name[$index]", item, guards).also {
                // m2-09: the issue shows its acceptance commands to the agent, so they can never be a hidden check.
                if (it is CheckConfig.AcceptanceCommands) {
                    throw WorkflowError.InvalidConfig(
                        "$name[$index] cannot be $ACCEPTANCE_COMMANDS, the issue shows its commands, list it in checks",
                    )
                }
            }
        }
    }

    /**
     * m2-12 and m2-13: `kinds.<kind>.verify.guards`. Absent guards nothing. Present, it needs `protect`, at least one non
     * blank glob with a leading `/` dropped since every glob is anchored at the workspace root, unless it lists `deny`
     * guards, which make `protect` optional. The optional `base`, `main` by default, must not be blank, and the
     * optional `languages` add test patterns to the built in ones, or replace one by name.
     */
    private fun parseGuards(
        kindKey: String,
        verify: YamlMap,
    ): GuardsConfig? {
        val guards = verify.child("guards", "$kindKey.verify.guards") ?: return null
        val deny = parseDeny(kindKey, guards)
        val name = "$kindKey.verify.guards.protect"
        val protect =
            guards.strings(name, "protect")
                ?: if (deny.isEmpty()) throw WorkflowError.InvalidConfig("$name is required") else emptyList()
        if (protect.isEmpty() && deny.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one path")
        if (protect.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank path")
        val base = guards.string("$kindKey.verify.guards.base", "base") ?: GuardsConfig.DEFAULT_BASE
        if (base.isBlank()) throw WorkflowError.InvalidConfig("$kindKey.verify.guards.base must not be blank")
        return GuardsConfig(protect.map { it.trim().removePrefix("/") }, base.trim(), deny, parseLanguages(kindKey, guards))
    }

    /** m2-13: `guards.deny`, absent or a non empty list of known guards, each kept once in the listed order. */
    private fun parseDeny(
        kindKey: String,
        guards: YamlMap,
    ): List<DenyGuard> {
        val name = "$kindKey.verify.guards.deny"
        val keys = guards.strings(name, "deny") ?: return emptyList()
        if (keys.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one guard")
        return keys
            .map { key ->
                DenyGuard.of(key.trim())
                    ?: throw WorkflowError.InvalidConfig(
                        "$name \"$key\" is not supported, use deleted_tests, added_skips or weaker_assertions",
                    )
            }.distinct()
    }

    /**
     * m2-13: `guards.languages`, the built in test patterns with each listed language replacing the built in one of
     * its name, in place, or added after them.
     */
    private fun parseLanguages(
        kindKey: String,
        guards: YamlMap,
    ): List<TestLanguage> {
        val name = "$kindKey.verify.guards.languages"
        val listed =
            when (val node = guards.node("languages")) {
                null -> return TestLanguage.BUILT_IN
                is YamlList -> node.items.mapIndexed { index, item -> parseLanguage("$name[$index]", item) }
                else -> throw WorkflowError.InvalidConfig("$name must be a list")
            }
        listed.forEachIndexed { index, language ->
            if (listed.take(index).any { it.name == language.name }) {
                throw WorkflowError.InvalidConfig("$name[$index].name ${language.name} is listed twice")
            }
        }
        val byName = listed.associateBy { it.name }
        return TestLanguage.BUILT_IN.map { byName[it.name] ?: it } +
            listed.filter { added -> TestLanguage.BUILT_IN.none { it.name == added.name } }
    }

    private fun parseLanguage(
        name: String,
        node: YamlNode,
    ): TestLanguage {
        val language = node as? YamlMap ?: throw WorkflowError.InvalidConfig("$name must be a map")
        val languageName =
            language.string("$name.name", "name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw WorkflowError.InvalidConfig("$name.name is required")
        val files = language.strings("$name.files", "files") ?: throw WorkflowError.InvalidConfig("$name.files is required")
        if (files.isEmpty()) throw WorkflowError.InvalidConfig("$name.files must list at least one path")
        if (files.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name.files must not hold a blank path")
        val patterns = listOf("test", "skip", "assertion").map { key -> language.pattern("$name.$key", key) }
        if (patterns.all { it == null }) throw WorkflowError.InvalidConfig("$name needs at least one of test, skip and assertion")
        val lineComment =
            language.string("$name.line_comment", "line_comment")?.also {
                if (it.isBlank()) throw WorkflowError.InvalidConfig("$name.line_comment must not be blank")
            }
        val blockComment =
            language.strings("$name.block_comment", "block_comment")?.let { markers ->
                if (markers.size != 2 || markers.any { it.isBlank() }) {
                    throw WorkflowError.InvalidConfig("$name.block_comment must list a start and an end marker")
                }
                markers[0] to markers[1]
            }
        return TestLanguage(
            name = languageName,
            files = files.map { it.trim().removePrefix("/") },
            test = patterns[0],
            skip = patterns[1],
            assertion = patterns[2],
            lineComment = lineComment,
            blockComment = blockComment,
        )
    }

    /** An optional regular expression, Java syntax, that must compile and must not be empty. */
    private fun YamlMap.pattern(
        name: String,
        key: String,
    ): String? {
        val pattern = string(name, key) ?: return null
        if (pattern.isEmpty()) throw WorkflowError.InvalidConfig("$name must not be empty")
        try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            throw WorkflowError.InvalidConfig("$name is not a valid regular expression: ${e.message?.lineSequence()?.first()}")
        }
        return pattern
    }

    /**
     * m2-11: `kinds.<kind>.ready`. Absent runs no readiness check. Present, it must be a map with a non empty
     * `require_sections` list of non blank names, so a misspelled or empty key never switches it off silently.
     */
    private fun parseReady(
        kindKey: String,
        code: YamlMap?,
    ): ReadyConfig? {
        val entry = code?.entries?.entries?.firstOrNull { it.key.content == "ready" } ?: return null
        val ready = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$kindKey.ready must be a map")
        val name = "$kindKey.ready.require_sections"
        val sections = ready.strings(name, "require_sections") ?: throw WorkflowError.InvalidConfig("$name is required")
        if (sections.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one section")
        if (sections.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank name")
        return ReadyConfig(sections.map { it.trim() })
    }

    /**
     * m2-24: `kinds.<kind>.delivery`. Absent, or without `policy`, is `human`. Present, it must be a map and its
     * `policy` `human` or `auto`, so a misspelled or empty value never switches the approval off silently.
     */
    private fun parseDelivery(
        kindKey: String,
        code: YamlMap?,
        trackerKind: String,
        deferred: MutableList<DeferredSecret>,
        deliverable: KindDeliverable,
    ): DeliveryConfig {
        val entry = code?.entries?.entries?.firstOrNull { it.key.content == "delivery" } ?: return DeliveryConfig()
        val delivery = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$kindKey.delivery must be a map")
        val pullRequest = parsePullRequest(kindKey, delivery, trackerKind, deferred)
        val moveTo = parseOnDelivered(kindKey, delivery)
        val name = "$kindKey.delivery.policy"
        val policy =
            delivery.string(name, "policy")?.let { written ->
                DeliveryPolicy.entries.firstOrNull { it.wire == written.trim().lowercase() }
                    ?: throw WorkflowError.InvalidConfig("$name must be human or auto, got \"$written\"")
            } ?: DeliveryPolicy.Human
        val autoMerge = parseAutoMerge(kindKey, delivery)
        val risk = parseRisk(kindKey, delivery)
        // m4-22: the attach delivery and its own opt in, checked before the auto_merge opt in, so the message names the
        // real reason (m4-21).
        val attach = delivery.boolean("$kindKey.delivery.attach", "attach") ?: false
        val attachAuto =
            delivery.mapEntry("attach_auto", "$kindKey.delivery.attach_auto")?.boolean("$kindKey.delivery.attach_auto.enabled", "enabled")
                ?: false
        AttachDelivery
            .refusal(kindKey, deliverable.globs, attach, attachAuto, pullRequest != null, policy == DeliveryPolicy.Auto, deliverable.report != null)
            ?.let { throw WorkflowError.InvalidConfig(it) }
        // m2-28: off unless the project opts in, so `auto` alone never delivers without a human. m4-22: an attach kind
        // opts in with attach_auto, which the refusal above already required.
        if (policy == DeliveryPolicy.Auto && !autoMerge.enabled && !attach) {
            throw WorkflowError.InvalidConfig(
                "$kindKey.delivery.policy is auto, which needs the opt in $kindKey.delivery.auto_merge.enabled: true",
            )
        }
        if (pullRequest?.merge != null) {
            if (moveTo == null) {
                throw WorkflowError.InvalidConfig(
                    "$kindKey.delivery.on_delivered.move_to is required with $kindKey.delivery.pull_request.merge",
                )
            }
            // m2-28: an auto merge needs a low merge risk band, which needs a reviewer.
            if (policy == DeliveryPolicy.Auto && risk.reviewer == null) {
                throw WorkflowError.InvalidConfig(
                    "$kindKey.delivery.risk.reviewer is required with policy auto and $kindKey.delivery.pull_request.merge",
                )
            }
        }
        return DeliveryConfig(policy, pullRequest, moveTo, autoMerge, risk, attach, attachAuto)
    }

    /** m4-22: the boolean under [key], null when absent; present, `true` or `false`, so a misspelled value is refused. */
    private fun YamlMap.boolean(
        name: String,
        key: String,
    ): Boolean? =
        string(name, key)?.let { written ->
            when (written.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw WorkflowError.InvalidConfig("$name must be true or false, got \"$written\"")
            }
        }

    /**
     * m2-28: `kinds.<kind>.delivery.auto_merge`, off when absent. Present, a map whose `enabled` is `true` or `false`,
     * whose optional `protected_paths` replace the default globs, whose optional `max_diff_lines` is positive, and whose
     * optional `doc_paths` replace the default doc globs (bl-96).
     */
    private fun parseAutoMerge(
        kindKey: String,
        delivery: YamlMap,
    ): AutoMergeConfig {
        val prefix = "$kindKey.delivery.auto_merge"
        val autoMerge = delivery.mapEntry("auto_merge", prefix) ?: return AutoMergeConfig()
        val enabled =
            autoMerge.string("$prefix.enabled", "enabled")?.let { written ->
                when (written.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw WorkflowError.InvalidConfig("$prefix.enabled must be true or false, got \"$written\"")
                }
            } ?: false
        return AutoMergeConfig(
            enabled = enabled,
            protectedPaths = autoMerge.paths("$prefix.protected_paths", "protected_paths") ?: AutoMergeConfig.DEFAULT_PROTECTED_PATHS,
            maxDiffLines = autoMerge.positiveInt("$prefix.max_diff_lines", "max_diff_lines") ?: AutoMergeConfig.DEFAULT_MAX_DIFF_LINES,
            docPaths = autoMerge.paths("$prefix.doc_paths", "doc_paths") ?: AutoMergeConfig.DEFAULT_DOC_PATHS,
        )
    }

    /**
     * m2-28: `kinds.<kind>.delivery.risk`, the defaults when absent. Present, a map with an optional non blank `reviewer`
     * model, optional `sensitive_paths` and `one_way_paths` globs replacing the defaults, and a positive `timeout_ms`.
     */
    private fun parseRisk(
        kindKey: String,
        delivery: YamlMap,
    ): RiskConfig {
        val prefix = "$kindKey.delivery.risk"
        val risk = delivery.mapEntry("risk", prefix) ?: return RiskConfig()
        val reviewer =
            risk.string("$prefix.reviewer", "reviewer")?.let { written ->
                written.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$prefix.reviewer must not be blank")
            }
        return RiskConfig(
            reviewer = reviewer,
            sensitivePaths = risk.paths("$prefix.sensitive_paths", "sensitive_paths") ?: RiskConfig.DEFAULT_SENSITIVE_PATHS,
            oneWayPaths = risk.paths("$prefix.one_way_paths", "one_way_paths") ?: RiskConfig.DEFAULT_ONE_WAY_PATHS,
            timeoutMs = risk.positiveLong("$prefix.timeout_ms", "timeout_ms") ?: RiskConfig.DEFAULT_TIMEOUT_MS,
        )
    }

    /** m2-28: the map under [key], null when absent; present, `~` included, it must be a map. */
    private fun YamlMap.mapEntry(
        key: String,
        name: String,
    ): YamlMap? {
        val entry = entries.entries.firstOrNull { it.key.content == key } ?: return null
        return entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$name must be a map")
    }

    /** m2-28: a list of path globs, null when absent, each trimmed and anchored at the workspace root like `guards.protect`. */
    private fun YamlMap.paths(
        name: String,
        key: String,
    ): List<String>? {
        val globs = strings(name, key) ?: return null
        if (globs.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank path")
        return globs.map { it.trim().removePrefix("/") }
    }

    /** m2-26: `kinds.<kind>.delivery.on_delivered.move_to`, null when absent; present, a map with a non blank `move_to`. */
    private fun parseOnDelivered(
        kindKey: String,
        delivery: YamlMap,
    ): String? {
        val prefix = "$kindKey.delivery.on_delivered"
        val entry = delivery.entries.entries.firstOrNull { it.key.content == "on_delivered" } ?: return null
        val onDelivered = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$prefix must be a map")
        val moveTo = onDelivered.string("$prefix.move_to", "move_to") ?: return null
        return moveTo.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$prefix.move_to must not be blank")
    }

    /**
     * m2-26: `kinds.<kind>.delivery.pull_request.merge`, null when absent. Present, a map whose `method` is `merge`, the
     * default, or `squash`, never `rebase`, and whose `ci` is required, `none` or, since m3-15, `actions`. With
     * `actions`, `ci_timeout_ms` and `ci_no_run_grace_ms` are positive, the grace below the timeout, and `ci_no_run` is
     * `fail`, the default, or `pass`. Under `none` no `ci_*` key is allowed, so a key that would change nothing is never
     * read as a CI wait. The optional `recheck_skip_paths` (bl-176) is a list of path globs with no blank one, anchored like
     * `guards.protect`, empty when absent.
     */
    private fun parseMerge(
        kindKey: String,
        pullRequest: YamlMap,
    ): MergeConfig? {
        val prefix = "$kindKey.delivery.pull_request.merge"
        val entry = pullRequest.entries.entries.firstOrNull { it.key.content == "merge" } ?: return null
        val merge = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$prefix must be a map")
        val method =
            merge.string("$prefix.method", "method")?.let { written ->
                MergeMethod.entries.firstOrNull { it.wire == written.trim().lowercase() }
                    ?: throw WorkflowError.InvalidConfig("$prefix.method must be merge or squash, got \"$written\"")
            } ?: MergeMethod.Merge
        val ci =
            merge.string("$prefix.ci", "ci")?.let { written ->
                CiMode.entries.firstOrNull { it.wire == written.trim().lowercase() }
                    ?: throw WorkflowError.InvalidConfig("$prefix.ci must be none or actions, got \"$written\"")
            } ?: throw WorkflowError.InvalidConfig("$prefix.ci is required, none or actions")
        val skipPaths = merge.paths("$prefix.recheck_skip_paths", "recheck_skip_paths").orEmpty()
        if (ci == CiMode.None) {
            val written = CI_KEYS.filter { key -> merge.entries.keys.any { it.content == key } }
            if (written.isNotEmpty()) {
                throw WorkflowError.InvalidConfig("$prefix.${written.joinToString(", ")} need ci: actions, ci is none")
            }
            return MergeConfig(method, ci, recheckSkipPaths = skipPaths)
        }
        val timeout = merge.positiveLong("$prefix.ci_timeout_ms", "ci_timeout_ms") ?: MergeConfig.DEFAULT_CI_TIMEOUT_MS
        val grace = merge.positiveLong("$prefix.ci_no_run_grace_ms", "ci_no_run_grace_ms") ?: MergeConfig.DEFAULT_CI_NO_RUN_GRACE_MS
        val noRun =
            merge.string("$prefix.ci_no_run", "ci_no_run")?.let { written ->
                CiNoRun.entries.firstOrNull { it.wire == written.trim().lowercase() }
                    ?: throw WorkflowError.InvalidConfig("$prefix.ci_no_run must be fail or pass, got \"$written\"")
            } ?: CiNoRun.Fail
        if (grace >= timeout) {
            throw WorkflowError.InvalidConfig("$prefix.ci_no_run_grace_ms ($grace) must be below $prefix.ci_timeout_ms ($timeout)")
        }
        return MergeConfig(method, ci, timeout, noRun, grace, skipPaths)
    }

    /**
     * m2-25: `kinds.<kind>.delivery.pull_request`. Absent keeps the m2-24 move. Present, it must be a map. `repo` is
     * `owner/name` and `token` a `$NAME` reference whose variable is set, both required on the `files` tracker; on
     * another tracker they default to its own. `base`, `remote` and `api_url` are optional. Only the token's variable
     * name is kept, never its value.
     */
    private fun parsePullRequest(
        kindKey: String,
        delivery: YamlMap,
        trackerKind: String,
        deferred: MutableList<DeferredSecret>,
    ): PullRequestConfig? {
        val prefix = "$kindKey.delivery.pull_request"
        val entry = delivery.entries.entries.firstOrNull { it.key.content == "pull_request" } ?: return null
        val pullRequest = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$prefix must be a map")
        val files = trackerKind == FILES_TRACKER
        val repo =
            pullRequest.string("$prefix.repo", "repo")?.trim()?.let { written ->
                written.takeIf { REPO.matches(it) }
                    ?: throw WorkflowError.InvalidConfig("$prefix.repo must be owner/name, got \"$written\"")
            }
        if (repo == null && files) throw WorkflowError.InvalidConfig("$prefix.repo is required with the files tracker")
        val tokenName =
            pullRequest.string("$prefix.token", "token")?.trim()?.let { written ->
                val name =
                    ENV_REF.matchEntire(written)?.groupValues?.get(1)
                        ?: throw WorkflowError.InvalidConfig("$prefix.token must be a \$NAME reference, never the token itself")
                val value = env[name]
                if (value.isNullOrBlank()) {
                    val missing = DeferredSecret("$prefix.token", name, empty = value != null)
                    if (!deferSecrets) throw WorkflowError.MissingEnvVar(name, missing.keyPath, missing.empty)
                    deferred += missing
                }
                name
            }
        if (tokenName == null && files) throw WorkflowError.InvalidConfig("$prefix.token is required with the files tracker")

        fun nonBlank(key: String): String? =
            pullRequest.string("$prefix.$key", key)?.let { written ->
                written.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$prefix.$key must not be blank")
            }
        val apiUrl =
            pullRequest.string("$prefix.api_url", "api_url")?.trim()?.let { written ->
                if (!HTTP_URL.matches(written)) {
                    throw WorkflowError.InvalidConfig("$prefix.api_url must be an http or https address, got \"$written\"")
                }
                // m2-25: the token goes to this address, so http only for a fake server on this machine.
                if (!written.startsWith("https://", ignoreCase = true) && !isLoopback(written)) {
                    throw WorkflowError.InvalidConfig(
                        "$prefix.api_url must be an https address, http is allowed only for a loopback host, got \"$written\"",
                    )
                }
                written
            }
        return PullRequestConfig(
            repo = repo,
            tokenEnvName = tokenName,
            base = nonBlank("base") ?: PullRequestConfig.DEFAULT_BASE,
            remote = nonBlank("remote") ?: PullRequestConfig.DEFAULT_REMOTE,
            apiUrl = apiUrl,
            merge = parseMerge(kindKey, pullRequest),
        )
    }

    private fun parseCheck(
        kindKey: String,
        name: String,
        node: YamlNode,
        guards: GuardsConfig?,
    ): CheckConfig {
        val check = node as? YamlMap ?: throw WorkflowError.InvalidConfig("$name must be a map")
        val type = check.string("$name.type", "type") ?: throw WorkflowError.InvalidConfig("$name.type is required")
        return when (type) {
            "command" -> {
                val run =
                    check.string("$name.run", "run")?.takeIf { it.isNotBlank() }
                        ?: throw WorkflowError.InvalidConfig("$name.run is required")
                val timeoutMs = check.positiveLong("$name.timeout_ms", "timeout_ms") ?: CheckConfig.DEFAULT_TIMEOUT_MS
                CheckConfig.Command(run, timeoutMs)
            }
            "file_exists" -> CheckConfig.FileExists(checkPath(name, check))
            "file_matches" -> {
                val path = checkPath(name, check)
                val pattern =
                    check.string("$name.pattern", "pattern")?.takeIf { it.isNotEmpty() }
                        ?: throw WorkflowError.InvalidConfig("$name.pattern is required")
                try {
                    Regex(pattern)
                } catch (e: IllegalArgumentException) {
                    throw WorkflowError.InvalidConfig(
                        "$name.pattern is not a valid regular expression: ${e.message?.lineSequence()?.first()}",
                    )
                }
                CheckConfig.FileMatches(path, pattern)
            }
            "diff_policy" -> {
                guards ?: throw WorkflowError.InvalidConfig("$name diff_policy needs $kindKey.verify.guards.protect")
                CheckConfig.DiffPolicy(guards.protect, guards.base, guards.deny, guards.languages)
            }
            CHANGED_LINE_COVERAGE -> parseChangedLineCoverage(name, check, guards)
            // m2-09: the commands come from the issue, so a `run` here would never run.
            ACCEPTANCE_COMMANDS -> {
                if (check.node("run") != null) {
                    throw WorkflowError.InvalidConfig("$name.run is not used by $ACCEPTANCE_COMMANDS, the commands come from the issue")
                }
                val timeoutMs = check.positiveLong("$name.timeout_ms", "timeout_ms") ?: CheckConfig.DEFAULT_TIMEOUT_MS
                CheckConfig.AcceptanceCommands(timeoutMs)
            }
            else -> throw WorkflowError.InvalidConfig(
                "$name.type \"$type\" is not supported, " +
                    "use command, file_exists, file_matches, diff_policy, $CHANGED_LINE_COVERAGE or $ACCEPTANCE_COMMANDS",
            )
        }
    }

    /**
     * bl-117: `changed_line_coverage`, with a non empty `reports` list of globs relative to the workspace, a required
     * `min_percent` from 0 to 100, and an optional non blank `base` that defaults like the `diff_policy` base, to
     * `guards.base` and else `main`.
     */
    private fun parseChangedLineCoverage(
        name: String,
        check: YamlMap,
        guards: GuardsConfig?,
    ): CheckConfig.ChangedLineCoverage {
        val reports = check.strings("$name.reports", "reports") ?: throw WorkflowError.InvalidConfig("$name.reports is required")
        if (reports.isEmpty()) throw WorkflowError.InvalidConfig("$name.reports must list at least one report")
        val globs =
            reports.map { written ->
                val glob = written.trim()
                if (glob.isEmpty()) throw WorkflowError.InvalidConfig("$name.reports must not hold a blank path")
                if (glob.startsWith("/")) throw WorkflowError.InvalidConfig("$name.reports must be relative to the workspace, got $glob")
                val parts = glob.split('/').filter { it.isNotEmpty() && it != "." }
                if (".." in parts) throw WorkflowError.InvalidConfig("$name.reports must stay inside the workspace, got $glob")
                if (parts.isEmpty()) throw WorkflowError.InvalidConfig("$name.reports must name a file inside the workspace, got $glob")
                parts.joinToString("/")
            }
        val text =
            check.scalar("$name.min_percent", "min_percent", PERCENT)
                ?: throw WorkflowError.InvalidConfig("$name.min_percent is required")
        val minPercent =
            text
                .trim()
                .takeIf { DECIMAL.matches(it) }
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }
                ?: throw WorkflowError.InvalidConfig(
                    "$name.min_percent must be $PERCENT, got " + if (DECIMAL.matches(text.trim())) text.trim() else "\"$text\"",
                )
        val base = check.string("$name.base", "base") ?: guards?.base ?: GuardsConfig.DEFAULT_BASE
        if (base.isBlank()) throw WorkflowError.InvalidConfig("$name.base must not be blank")
        return CheckConfig.ChangedLineCoverage(globs, minPercent, base.trim())
    }

    /** m2-09: `acceptance_commands` once at most, so the issue's commands never run twice in one verification. */
    private fun List<CheckConfig>.alsoOnce(kindKey: String): List<CheckConfig> {
        val repeat = withIndex().filter { it.value is CheckConfig.AcceptanceCommands }.drop(1).firstOrNull()
        if (repeat != null) {
            throw WorkflowError.InvalidConfig("$kindKey.verify.checks[${repeat.index}] repeats $ACCEPTANCE_COMMANDS, declare it once")
        }
        return this
    }

    /** m2-12: the `path` of a file check, relative to the workspace with no `..`, so it can only name a path inside it. */
    private fun checkPath(
        name: String,
        check: YamlMap,
    ): String {
        val path =
            check.string("$name.path", "path")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw WorkflowError.InvalidConfig("$name.path is required")
        if (path.startsWith("/")) throw WorkflowError.InvalidConfig("$name.path must be relative to the workspace, got $path")
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in parts) throw WorkflowError.InvalidConfig("$name.path must stay inside the workspace, got $path")
        if (parts.isEmpty()) throw WorkflowError.InvalidConfig("$name.path must name a path inside the workspace, got $path")
        return parts.joinToString("/")
    }

    private fun parseCodex(codex: YamlMap?): CodexConfig {
        val defaults = CodexConfig()
        return CodexConfig(
            command = codex.string("codex.command", "command") ?: defaults.command,
            approvalPolicy = codex.node("approval_policy")?.toConfigValue(),
            threadSandbox = codex.node("thread_sandbox")?.toConfigValue(),
            turnSandboxPolicy = codex.node("turn_sandbox_policy")?.toConfigValue(),
            turnTimeoutMs = codex.positiveLong("codex.turn_timeout_ms", "turn_timeout_ms") ?: defaults.turnTimeoutMs,
            readTimeoutMs = codex.positiveLong("codex.read_timeout_ms", "read_timeout_ms") ?: defaults.readTimeoutMs,
            stallTimeoutMs = codex.integer("codex.stall_timeout_ms", "stall_timeout_ms") ?: defaults.stallTimeoutMs,
        )
    }

    private fun parseRetries(retries: YamlMap?): RetriesConfig {
        val defaults = RetriesConfig()
        return RetriesConfig(
            maxAttempts = retries.positiveInt("retries.max_attempts", "max_attempts") ?: defaults.maxAttempts,
            maxContinuations =
                retries.positiveInt("retries.max_continuations", "max_continuations") ?: defaults.maxContinuations,
        )
    }

    private fun parseBudgets(budgets: YamlMap?): BudgetsConfig {
        val defaults = BudgetsConfig()
        return BudgetsConfig(
            perAttemptTokens =
                budgets.positiveLong("budgets.per_attempt_tokens", "per_attempt_tokens") ?: defaults.perAttemptTokens,
            tokenThresholdEvery =
                budgets.nonNegativeLong("budgets.token_threshold_every", "token_threshold_every") ?: defaults.tokenThresholdEvery,
            perAttemptUsd = budgets.dollars("budgets.per_attempt_usd", "per_attempt_usd"),
            perIssueTokens = budgets.positiveLong("budgets.per_issue_tokens", "per_issue_tokens"),
            perIssueUsd = budgets.dollars("budgets.per_issue_usd", "per_issue_usd"),
            perDayTokens = budgets.positiveLong("budgets.per_day_tokens", "per_day_tokens"),
            perDayUsd = budgets.dollars("budgets.per_day_usd", "per_day_usd"),
        )
    }

    /** A dollar budget (m2-20): a positive decimal number. */
    private fun YamlMap?.dollars(
        name: String,
        key: String,
    ): Double? {
        val text = scalar(name, key, DOLLARS) ?: return null
        val trimmed = text.trim()
        val value = trimmed.takeIf { DECIMAL.matches(it) }?.toDoubleOrNull()
        if (value != null && value > 0 && value.isFinite()) return value
        val shown = if (DECIMAL.matches(trimmed)) trimmed else "\"$text\""
        throw WorkflowError.InvalidConfig("$name must be $DOLLARS, got $shown")
    }

    private fun parseObservability(observability: YamlMap?): ObservabilityConfig {
        val defaults = ObservabilityConfig()
        return ObservabilityConfig(
            retentionDays =
                observability.positiveInt("observability.retention_days", "retention_days") ?: defaults.retentionDays,
        )
    }

    /**
     * Plan 8.2 and 8.4 (m1-17): each limit a fraction in (0, 1] with `prompt_limit <= soft_limit <= hard_limit`,
     * the counts positive integers, and `windows` a map of `provider/model` to a positive window in tokens,
     * merged over [ContextConfig.DEFAULT_WINDOWS].
     */
    private fun parseContext(context: YamlMap?): ContextConfig {
        val defaults = ContextConfig()
        val soft = context.fraction("context.soft_limit", "soft_limit") ?: defaults.softLimit
        val configuredHard = context.fraction("context.hard_limit", "hard_limit")
        val hard = configuredHard ?: defaults.hardLimit
        val prompt = context.fraction("context.prompt_limit", "prompt_limit") ?: defaults.promptLimit
        if (soft > hard && configuredHard == null) {
            // m4-02: a soft limit that passed under the old default hard limit of 0.25 says what changed.
            throw WorkflowError.InvalidConfig(
                "context.soft_limit ($soft) must not be above the default context.hard_limit ($hard): " +
                    "set context.hard_limit or lower context.soft_limit",
            )
        }
        if (soft > hard) {
            throw WorkflowError.InvalidConfig("context.soft_limit ($soft) must not be above context.hard_limit ($hard)")
        }
        if (prompt > soft) {
            throw WorkflowError.InvalidConfig("context.prompt_limit ($prompt) must not be above context.soft_limit ($soft)")
        }
        return ContextConfig(
            softLimit = soft,
            hardLimit = hard,
            promptLimit = prompt,
            toolOutputTokens =
                context.positiveInt("context.tool_output_tokens", "tool_output_tokens") ?: defaults.toolOutputTokens,
            maxRotations = context.positiveInt("context.max_rotations", "max_rotations") ?: defaults.maxRotations,
            windows = defaults.windows + context.child("windows", "context.windows").windows(),
        )
    }

    private fun YamlMap?.windows(): Map<String, Long> =
        this
            ?.entries
            ?.entries
            ?.associate { (key, _) ->
                val model = key.content
                if (!WINDOW_KEY.matches(model)) {
                    throw WorkflowError.InvalidConfig(
                        "context.windows key \"$model\" must be provider/model, for example anthropic/claude-opus-5-5",
                    )
                }
                val name = "context.windows.$model"
                model to (positiveLong(name, model) ?: throw WorkflowError.InvalidConfig("$name must be $POSITIVE, got nothing"))
            }.orEmpty()

    private fun YamlMap?.fraction(
        name: String,
        key: String,
    ): Double? {
        val text = scalar(name, key, FRACTION) ?: return null
        val value = text.trim().takeIf { DECIMAL.matches(it) }?.toDoubleOrNull()
        if (value != null && value > 0 && value <= 1) return value
        val shown = if (DECIMAL.matches(text.trim())) text.trim() else "\"$text\""
        throw WorkflowError.InvalidConfig("$name must be $FRACTION, got $shown")
    }

    /**
     * m4-15: `agent.max_concurrent_agents_by_tracker`, tracker id to a positive count. A Loop key keeps no spec behavior,
     * so unlike [perState] it refuses a key that is not one of [trackerIds] and a value below 1, rather than ignore them.
     */
    private fun YamlMap?.perTracker(trackerIds: List<String>): Map<String, Int> {
        val name = "agent.max_concurrent_agents_by_tracker"
        val limits =
            when (val node = node("max_concurrent_agents_by_tracker")) {
                null -> return emptyMap()
                is YamlMap -> node
                else -> throw WorkflowError.InvalidConfig("$name must be a map of tracker id to $POSITIVE")
            }
        return limits.entries.keys.associate { key ->
            val id = key.content
            if (id !in trackerIds) {
                throw WorkflowError.InvalidConfig(
                    "$name.$id names no tracker of this workflow, use one of ${trackerIds.joinToString(", ")}",
                )
            }
            id to checkNotNull(limits.positiveInt("$name.$id", id))
        }
    }

    /** Spec 5.3.5: keys trimmed and lowercased, blank keys and non-positive or non-numeric entries ignored. */
    private fun YamlMap?.perState(): Map<String, Int> =
        this
            ?.entries
            ?.entries
            ?.mapNotNull { (key, value) ->
                val limit = (value as? YamlScalar)?.content?.toIntOrNull()?.takeIf { it > 0 }
                val state =
                    key.content
                        .trim()
                        .lowercase()
                        .takeIf { it.isNotEmpty() }
                if (limit != null && state != null) state to limit else null
            }?.toMap()
            .orEmpty()

    private fun YamlMap?.scalar(
        name: String,
        key: String,
        expected: String,
    ): String? =
        when (val node = node(key)) {
            null -> null
            is YamlScalar -> node.content
            else -> throw WorkflowError.InvalidConfig("$name must be $expected")
        }

    private fun YamlMap?.positiveLong(
        name: String,
        key: String,
    ): Long? {
        val text = scalar(name, key, POSITIVE) ?: return null
        return text.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid(name, POSITIVE, text)
    }

    private fun YamlMap?.nonNegativeLong(
        name: String,
        key: String,
    ): Long? {
        val text = scalar(name, key, NON_NEGATIVE) ?: return null
        return text.toLongOrNull()?.takeIf { it >= 0 } ?: throw invalid(name, NON_NEGATIVE, text)
    }

    private fun YamlMap?.positiveInt(
        name: String,
        key: String,
    ): Int? {
        val text = scalar(name, key, POSITIVE) ?: return null
        return text.toIntOrNull()?.takeIf { it > 0 } ?: throw invalid(name, POSITIVE, text)
    }

    private fun YamlMap?.integer(
        name: String,
        key: String,
    ): Long? {
        val text = scalar(name, key, INTEGER) ?: return null
        return text.toLongOrNull() ?: throw invalid(name, INTEGER, text)
    }

    private fun invalid(
        name: String,
        expected: String,
        text: String,
    ): WorkflowError.InvalidConfig {
        val shown = if (text.toLongOrNull() != null) text else "\"$text\""
        return WorkflowError.InvalidConfig("$name must be $expected, got $shown")
    }

    private fun YamlNode.toConfigValue(): ConfigValue =
        when (this) {
            is YamlNull -> ConfigValue.Null
            is YamlScalar -> ConfigValue.Str(content)
            is YamlList -> ConfigValue.ListValue(items.map { it.toConfigValue() })
            is YamlMap -> ConfigValue.MapValue(entries.entries.associate { it.key.content to it.value.toConfigValue() })
            is YamlTaggedNode -> innerNode.toConfigValue()
        }

    /** The value as a plugin reads it: a string, a list, a map or null, as written. */
    private fun YamlNode.toPlain(): Any? =
        when (this) {
            is YamlNull -> null
            is YamlScalar -> content
            is YamlList -> items.map { it.toPlain() }
            is YamlMap -> entries.entries.associate { it.key.content to it.value.toPlain() }
            is YamlTaggedNode -> innerNode.toPlain()
        }

    private fun YamlMap.toPlainMap(): Map<String, Any?> = entries.entries.associate { it.key.content to it.value.toPlain() }

    /** Every scalar value under this node, at any depth, that is exactly `$NAME` (the [expandEnv] syntax), by name. */
    private fun YamlNode.referencedEnvNames(): Set<String> =
        when (this) {
            is YamlNull -> emptySet()
            is YamlScalar -> ENV_REF.matchEntire(content)?.let { setOf(it.groupValues[1]) }.orEmpty()
            is YamlList -> items.flatMapTo(mutableSetOf()) { it.referencedEnvNames() }
            is YamlMap -> entries.values.flatMapTo(mutableSetOf()) { it.referencedEnvNames() }
            is YamlTaggedNode -> innerNode.referencedEnvNames()
        }

    private fun readFile(path: Path): Pair<Path, String> =
        try {
            val file = path.toRealPath()
            file to Files.readString(file)
        } catch (e: NoSuchFileException) {
            throw WorkflowError.MissingFile("no workflow file at $path")
        } catch (e: IOException) {
            throw WorkflowError.MissingFile("cannot read the workflow file at $path: ${e.message}")
        }

    private fun split(text: String): Pair<String, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != FENCE) return "" to text.trim()
        val end = lines.drop(1).indexOfFirst { it.trim() == FENCE }
        if (end < 0) throw WorkflowError.Parse("front matter has no closing ---")
        val frontMatter = lines.subList(1, end + 1).joinToString("\n")
        val body = lines.drop(end + 2).joinToString("\n").trim()
        return frontMatter to body
    }

    private fun parseFrontMatter(frontMatter: String): YamlMap? {
        if (frontMatter.isBlank()) return null
        val node =
            try {
                Yaml.default.parseToYamlNode(frontMatter)
            } catch (e: RuntimeException) {
                throw WorkflowError.Parse("front matter is not valid yaml: ${e.message}", e)
            }
        return when (node) {
            is YamlMap -> node
            is YamlNull -> null
            else -> throw WorkflowError.FrontMatterNotAMap("front matter must be a map")
        }
    }

    private fun resolve(
        folder: Path,
        value: String,
    ): Path {
        val expanded =
            when {
                value == "~" -> home
                value.startsWith("~/") -> home.resolve(value.removePrefix("~/"))
                else -> Path(value)
            }
        return folder.resolve(expanded).toAbsolutePath().normalize()
    }

    private fun YamlMap?.node(key: String): YamlNode? =
        this
            ?.entries
            ?.entries
            ?.firstOrNull { it.key.content == key }
            ?.value
            ?.takeUnless { it is YamlNull }

    private fun YamlMap?.child(
        key: String,
        name: String = key,
    ): YamlMap? =
        when (val node = node(key)) {
            null -> null
            is YamlMap -> node
            else -> throw WorkflowError.InvalidConfig("$name must be a map")
        }

    private fun YamlMap?.string(
        name: String,
        key: String,
    ): String? =
        when (val node = node(key)) {
            null -> null
            is YamlScalar -> node.content
            else -> throw WorkflowError.InvalidConfig("$name must be a string")
        }

    private fun YamlMap?.strings(
        name: String,
        key: String,
    ): List<String>? =
        when (val node = node(key)) {
            null -> null
            is YamlList ->
                node.items.map {
                    (it as? YamlScalar)?.content ?: throw WorkflowError.InvalidConfig("$name must be a list of strings")
                }
            else -> throw WorkflowError.InvalidConfig("$name must be a list of strings")
        }

    private companion object {
        const val FENCE = "---"
        const val FILES_TRACKER = "files"
        const val DEFAULT_TRACKER_DIR = ".loop/issues"
        const val DEFAULT_WORKSPACES_DIR = "loop_workspaces"
        const val ACCEPTANCE_COMMANDS = "acceptance_commands"
        const val CHANGED_LINE_COVERAGE = "changed_line_coverage"
        val ENV_REF = Regex("""^\$([A-Za-z_][A-Za-z0-9_]*)$""")

        /** A GitHub repository as `owner/name` (m2-25). */
        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        /** An http or https address with a host (m2-25). */
        private val HTTP_URL = Regex("https?://[^\\s/]+(/\\S*)?", RegexOption.IGNORE_CASE)

        /**
         * True when the host of the http [url] is a loopback host (m2-25): `localhost`, an address of `127.0.0.0/8` or
         * `::1`, with an optional port. Only a fake server of a test listens there, and nothing sent to it leaves the
         * machine.
         */
        internal fun isLoopback(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/').lowercase()
            return LOOPBACK.matches(host)
        }

        private val LOOPBACK = Regex("(localhost|127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}|\\[::1])(:[0-9]{1,5})?")
        val ENV_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")
        val AGENT_ENV_NEVER_PASSED = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")
        const val POSITIVE = "a positive integer"

        /** m3-15: the keys of the CI wait under `merge`, in the order a refusal names them. */
        private val CI_KEYS = listOf("ci_timeout_ms", "ci_no_run", "ci_no_run_grace_ms")

        /** m4-18: the keys of a kind's `match`. */
        private val MATCH_KEYS = setOf("default", "label", "tracker", "state")

        /** m4-18: what a kind name must match, like a tracker id, since it reaches `LOOP_KIND` and the ledger. */
        private val KIND_NAME = Regex("[a-z][a-z0-9-]*")
        const val NON_NEGATIVE = "a non-negative integer"
        const val INTEGER = "an integer"
        const val FRACTION = "a fraction above 0 and at most 1"
        const val PERCENT = "a number from 0 to 100"
        const val DOLLARS = "a positive number of dollars"
        val DECIMAL = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
        val WINDOW_KEY = Regex("""^[^/\s]+/\S+$""")
    }
}

/** Maps to the spec's workflow errors (5.5) and startup validation (6.3). */
sealed class WorkflowError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** missing_workflow_file */
    class MissingFile(
        message: String,
    ) : WorkflowError(message)

    /** workflow_parse_error */
    class Parse(
        message: String,
        cause: Throwable? = null,
    ) : WorkflowError(message, cause)

    /** workflow_front_matter_not_a_map */
    class FrontMatterNotAMap(
        message: String,
    ) : WorkflowError(message)

    /** A key is missing or has an unsupported value. */
    class InvalidConfig(
        message: String,
    ) : WorkflowError(message)

    /**
     * A `tracker.provider` setting its [TrackerProvider] cannot use (bl-23, m3-05), with its spec 11.4
     * [category], such as `invalid_tracker_config` or `missing_tracker_secret`.
     */
    class TrackerConfig(
        val category: String,
        message: String,
    ) : WorkflowError(message)

    /** A `$NAME` path value whose variable is unset or blank (plan D3). */
    class MissingEnvVar(
        val name: String,
        val keyPath: String,
        val empty: Boolean,
    ) : WorkflowError("$keyPath uses \$$name, which is ${if (empty) "empty" else "not set"}")
}
