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
import loop.api.TrackerConfigException
import loop.api.TrackerProvider
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
)

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
     * Best effort: the `tracker.provider` map of the front matter at [path], as written, with
     * none of [load]'s validation, so the secrets it names can be learned before a load or a
     * preflight that may fail (m1-11). Never throws: an unreadable file, front matter that is not
     * yaml or a `tracker.provider` that is not a map gives an empty map.
     */
    fun trackerProvider(path: Path): Map<String, ConfigValue> =
        try {
            val (_, text) = readFile(path)
            parseFrontMatter(split(text).first)
                .node("tracker")
                .let { it as? YamlMap }
                .node("provider")
                .let { it as? YamlMap }
                ?.entries
                ?.entries
                ?.associate { it.key.content to it.value.toConfigValue() }
                .orEmpty()
        } catch (e: Exception) {
            emptyMap()
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

        val tracker = config.child("tracker")
        val trackerKind =
            tracker?.string("tracker.kind", "kind")?.takeIf { it.isNotBlank() }
                ?: throw WorkflowError.InvalidConfig("tracker.kind is required")
        val registered: TrackerProvider? =
            if (trackerCheck != null) {
                trackerCheck.select(trackerKind).getOrElse { throw WorkflowError.InvalidConfig(it.message ?: "tracker.kind is invalid") }
            } else if (trackerKind == FILES_TRACKER) {
                null
            } else {
                throw WorkflowError.InvalidConfig(TrackerCheck.unsupported(trackerKind, listOf(FILES_TRACKER)))
            }
        val provider = tracker.child("provider", "tracker.provider")
        val configWarnings = mutableListOf<String>()
        val trackerSettings =
            registered?.let { kind ->
                val plain =
                    provider
                        ?.entries
                        ?.entries
                        ?.associate { it.key.content to it.value.toPlain() }
                        .orEmpty()
                val trackerEnv = secretEnv(kind.profile, "tracker.provider", plain, deferred)
                configWarnings +=
                    checkProfile(kind.profile, "tracker.provider", plain, "the ${kind.kind} tracker", trackerEnv) { problem ->
                        // bl-104: a secret out of its limits is there and resolved, so it is invalid, not missing.
                        val missing = problem.secret && problem !is ProfileProblem.OutOfLimits
                        WorkflowError.TrackerConfig(if (missing) MISSING_TRACKER_SECRET else INVALID_TRACKER_CONFIG, problem.message)
                    }
                try {
                    // bl-70: the plugin checks the rest of its section with a stand in, whose settings are dropped.
                    kind.parse(plain, trackerEnv).takeIf { trackerEnv === env }
                } catch (e: TrackerConfigException) {
                    throw WorkflowError.TrackerConfig(e.category, e.message.orEmpty())
                } catch (e: WorkflowError) {
                    throw e
                } catch (e: Exception) {
                    throw WorkflowError.InvalidConfig(e.message ?: "tracker.provider is invalid")
                }
            }
        val trackerDir =
            resolve(
                folder,
                provider?.string("tracker.provider.dir", "dir")?.let { expandEnv("tracker.provider.dir", it, envRefs) }
                    ?: DEFAULT_TRACKER_DIR,
            )

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
                checkProfile(profile, key, map, "the ${runner.kind} runner", secretEnv(profile, key, map, deferred)) { problem ->
                    if (problem is ProfileProblem.UnresolvedSecret) {
                        WorkflowError.MissingEnvVar(problem.envName, problem.key, problem.empty)
                    } else {
                        WorkflowError.InvalidConfig(problem.message)
                    }
                }
        }
        runnerCheck?.problem(runner)?.let { throw WorkflowError.InvalidConfig(it) }

        val polling = parsePolling(config.child("polling"))
        val hooks = parseHooks(config.child("hooks"))
        val agent = parseAgent(config.child("agent"))
        val codex = parseCodex(config.child("codex"))
        val retries = parseRetries(config.child("retries"))
        val budgets = parseBudgets(config.child("budgets"))
        val context = parseContext(config.child("context"))
        val observability = parseObservability(config.child("observability"))
        val kinds = parseKinds(config, trackerKind, deferred)

        val workspaceRoot =
            config
                .child("workspace")
                ?.string("workspace.root", "root")
                ?.let { resolve(folder, expandEnv("workspace.root", it, envRefs)) }
                ?: systemTemp.resolve(DEFAULT_WORKSPACES_DIR).toAbsolutePath().normalize()

        val terminalStates =
            tracker.strings("tracker.terminal_states", "terminal_states")
                ?: registered?.defaultTerminalStates
                ?: DEFAULT_TERMINAL_STATES
        kinds?.code?.delivery?.onDeliveredMoveTo?.let { moveTo ->
            // m2-26: a merged pull request is done, so its issue leaves the tracker's work for good.
            if (terminalStates.none { it.trim().equals(moveTo, ignoreCase = true) }) {
                throw WorkflowError.InvalidConfig(
                    "kinds.code.delivery.on_delivered.move_to is \"$moveTo\", which is not a terminal state, " +
                        "use one of ${terminalStates.joinToString(", ")}",
                )
            }
        }
        return WorkflowFile(
            path = file,
            trackerKind = trackerKind,
            trackerDir = trackerDir,
            activeStates =
                tracker.strings("tracker.active_states", "active_states")
                    ?: registered?.defaultActiveStates
                    ?: DEFAULT_ACTIVE_STATES,
            terminalStates = terminalStates,
            requiredLabels = tracker.strings("tracker.required_labels", "required_labels") ?: emptyList(),
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
                    trackerProvider =
                        provider
                            ?.entries
                            ?.entries
                            ?.associate { it.key.content to it.value.toConfigValue() }
                            .orEmpty(),
                    envRefs = envRefs.toList(),
                    referencedEnvNames = config?.referencedEnvNames().orEmpty(),
                ),
            sourceHash = workflowHash(text),
            sourceText = text,
            trackerSettings = trackerSettings,
            configWarnings = configWarnings.toList(),
            deferredSecrets = deferred.toList(),
        )
    }

    /**
     * bl-70: the env to check [section], the plugin section at [key], with. It is [env] itself unless [deferSecrets]
     * is set and a secret of [profile] written `$NAME` has its variable unset or blank: each such secret is added to
     * [deferred], and the returned copy of [env] maps its variable to [DEFERRED_SECRET], a stand in that is never a
     * credential, so the rest of the section is still checked.
     */
    private fun secretEnv(
        profile: PluginProfile,
        key: String,
        section: Map<String, Any?>,
        deferred: MutableList<DeferredSecret>,
    ): Map<String, String> {
        if (!deferSecrets) return env
        val missing =
            profile.secrets.sorted().mapNotNull { secret ->
                val name =
                    (section[secret] as? String)
                        ?.trim()
                        ?.let { ENV_REF.matchEntire(it) }
                        ?.groupValues
                        ?.get(1)
                val value = name?.let { env[it] }
                if (name == null || !value.isNullOrBlank()) null else DeferredSecret("$key.$secret", name, empty = value != null)
            }
        if (missing.isEmpty()) return env
        deferred += missing
        return env + missing.associate { it.name to DEFERRED_SECRET }
    }

    /**
     * m3-05: checks [section], the plugin section at [key] as written, against [profile], throwing the error [failure]
     * makes of the first problem. Returns one warning per key the profile does not list, naming [plugin].
     */
    private fun checkProfile(
        profile: PluginProfile,
        key: String,
        section: Map<String, Any?>,
        plugin: String,
        checkEnv: Map<String, String>,
        failure: (ProfileProblem) -> WorkflowError,
    ): List<String> {
        val report = profile.check(key, section, checkEnv)
        report.problem?.let { throw failure(it) }
        return report.unknownKeys.map { "$it is not a key of $plugin, Loop ignores it" }
    }

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

    private fun parseAgent(agent: YamlMap?): AgentConfig {
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
     * m2-03: `kinds:`. Absent is Symphony mode. Present, it must hold a `code` kind with an explicit
     * `verify.checks` list, so an empty or misspelled key never switches verification off silently.
     */
    private fun parseKinds(
        config: YamlMap?,
        trackerKind: String,
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
        names.firstOrNull { it != "code" }?.let { throw WorkflowError.InvalidConfig("kinds.$it is not supported, use code") }
        if ("code" !in names) throw WorkflowError.InvalidConfig("kinds must be a map with a code kind")
        val verify =
            kinds.child("code", "kinds.code").child("verify", "kinds.code.verify")
                ?: throw WorkflowError.InvalidConfig("kinds.code.verify is required")
        val guards = parseGuards(verify)
        val listed =
            when (
                val node =
                    verify.entries.entries
                        .firstOrNull { it.key.content == "checks" }
                        ?.value
            ) {
                null, is YamlNull -> throw WorkflowError.InvalidConfig("kinds.code.verify.checks is required")
                is YamlList ->
                    node.items
                        .mapIndexed { index, item -> parseCheck("kinds.code.verify.checks[$index]", item, guards) }
                        .alsoOnce()
                else -> throw WorkflowError.InvalidConfig("kinds.code.verify.checks must be a list")
            }
        // m2-12: a guard is never silently off, so guards without a diff_policy check add one, last, so the listed
        // checks keep their numbers. An empty list stays empty: a guard alone proves no work.
        val checks =
            if (guards != null && listed.isNotEmpty() && listed.none { it is CheckConfig.DiffPolicy }) {
                listed + CheckConfig.DiffPolicy(guards.protect, guards.base, guards.deny, guards.languages)
            } else {
                listed
            }
        val code = kinds.child("code", "kinds.code")
        val ready = parseReady(code)
        val holdout = parseHoldout(verify, guards)
        return KindsConfig(KindConfig(checks, ready, guards, holdout, parseDelivery(code, trackerKind, deferred)))
    }

    /**
     * m2-14: `kinds.code.verify.holdout`, the hidden checks. Absent is none. Present, it must be a list of checks
     * of the same shape as `checks`, but never `acceptance_commands` (m2-09); `holdout: ~` is an error too, so an
     * empty key never drops them silently.
     */
    private fun parseHoldout(
        verify: YamlMap,
        guards: GuardsConfig?,
    ): List<CheckConfig> {
        val name = "kinds.code.verify.holdout"
        val entry = verify.entries.entries.firstOrNull { it.key.content == "holdout" } ?: return emptyList()
        val list = entry.value as? YamlList ?: throw WorkflowError.InvalidConfig("$name must be a list")
        return list.items.mapIndexed { index, item ->
            parseCheck("$name[$index]", item, guards).also {
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
     * m2-12 and m2-13: `kinds.code.verify.guards`. Absent guards nothing. Present, it needs `protect`, at least one non
     * blank glob with a leading `/` dropped since every glob is anchored at the workspace root, unless it lists `deny`
     * guards, which make `protect` optional. The optional `base`, `main` by default, must not be blank, and the
     * optional `languages` add test patterns to the built in ones, or replace one by name.
     */
    private fun parseGuards(verify: YamlMap): GuardsConfig? {
        val guards = verify.child("guards", "kinds.code.verify.guards") ?: return null
        val deny = parseDeny(guards)
        val name = "kinds.code.verify.guards.protect"
        val protect =
            guards.strings(name, "protect")
                ?: if (deny.isEmpty()) throw WorkflowError.InvalidConfig("$name is required") else emptyList()
        if (protect.isEmpty() && deny.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one path")
        if (protect.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank path")
        val base = guards.string("kinds.code.verify.guards.base", "base") ?: GuardsConfig.DEFAULT_BASE
        if (base.isBlank()) throw WorkflowError.InvalidConfig("kinds.code.verify.guards.base must not be blank")
        return GuardsConfig(protect.map { it.trim().removePrefix("/") }, base.trim(), deny, parseLanguages(guards))
    }

    /** m2-13: `guards.deny`, absent or a non empty list of known guards, each kept once in the listed order. */
    private fun parseDeny(guards: YamlMap): List<DenyGuard> {
        val name = "kinds.code.verify.guards.deny"
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
    private fun parseLanguages(guards: YamlMap): List<TestLanguage> {
        val name = "kinds.code.verify.guards.languages"
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
     * m2-11: `kinds.code.ready`. Absent runs no readiness check. Present, it must be a map with a non empty
     * `require_sections` list of non blank names, so a misspelled or empty key never switches it off silently.
     */
    private fun parseReady(code: YamlMap?): ReadyConfig? {
        val entry = code?.entries?.entries?.firstOrNull { it.key.content == "ready" } ?: return null
        val ready = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("kinds.code.ready must be a map")
        val name = "kinds.code.ready.require_sections"
        val sections = ready.strings(name, "require_sections") ?: throw WorkflowError.InvalidConfig("$name is required")
        if (sections.isEmpty()) throw WorkflowError.InvalidConfig("$name must list at least one section")
        if (sections.any { it.isBlank() }) throw WorkflowError.InvalidConfig("$name must not hold a blank name")
        return ReadyConfig(sections.map { it.trim() })
    }

    /**
     * m2-24: `kinds.code.delivery`. Absent, or without `policy`, is `human`. Present, it must be a map and its
     * `policy` `human` or `auto`, so a misspelled or empty value never switches the approval off silently.
     */
    private fun parseDelivery(
        code: YamlMap?,
        trackerKind: String,
        deferred: MutableList<DeferredSecret>,
    ): DeliveryConfig {
        val entry = code?.entries?.entries?.firstOrNull { it.key.content == "delivery" } ?: return DeliveryConfig()
        val delivery = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("kinds.code.delivery must be a map")
        val pullRequest = parsePullRequest(delivery, trackerKind, deferred)
        val moveTo = parseOnDelivered(delivery)
        val name = "kinds.code.delivery.policy"
        val policy =
            delivery.string(name, "policy")?.let { written ->
                DeliveryPolicy.entries.firstOrNull { it.wire == written.trim().lowercase() }
                    ?: throw WorkflowError.InvalidConfig("$name must be human or auto, got \"$written\"")
            } ?: DeliveryPolicy.Human
        val autoMerge = parseAutoMerge(delivery)
        val risk = parseRisk(delivery)
        // m2-28: off unless the project opts in, so `auto` alone never delivers without a human.
        if (policy == DeliveryPolicy.Auto && !autoMerge.enabled) {
            throw WorkflowError.InvalidConfig(
                "kinds.code.delivery.policy is auto, which needs the opt in kinds.code.delivery.auto_merge.enabled: true",
            )
        }
        if (pullRequest?.merge != null) {
            if (moveTo == null) {
                throw WorkflowError.InvalidConfig(
                    "kinds.code.delivery.on_delivered.move_to is required with kinds.code.delivery.pull_request.merge",
                )
            }
            // m2-28: an auto merge needs a low merge risk band, which needs a reviewer.
            if (policy == DeliveryPolicy.Auto && risk.reviewer == null) {
                throw WorkflowError.InvalidConfig(
                    "kinds.code.delivery.risk.reviewer is required with policy auto and kinds.code.delivery.pull_request.merge",
                )
            }
        }
        return DeliveryConfig(policy, pullRequest, moveTo, autoMerge, risk)
    }

    /**
     * m2-28: `kinds.code.delivery.auto_merge`, off when absent. Present, a map whose `enabled` is `true` or `false`,
     * whose optional `protected_paths` replace the default globs, and whose optional `max_diff_lines` is positive.
     */
    private fun parseAutoMerge(delivery: YamlMap): AutoMergeConfig {
        val prefix = "kinds.code.delivery.auto_merge"
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
        )
    }

    /**
     * m2-28: `kinds.code.delivery.risk`, the defaults when absent. Present, a map with an optional non blank `reviewer`
     * model, optional `sensitive_paths` and `one_way_paths` globs replacing the defaults, and a positive `timeout_ms`.
     */
    private fun parseRisk(delivery: YamlMap): RiskConfig {
        val prefix = "kinds.code.delivery.risk"
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

    /** m2-26: `kinds.code.delivery.on_delivered.move_to`, null when absent; present, a map with a non blank `move_to`. */
    private fun parseOnDelivered(delivery: YamlMap): String? {
        val prefix = "kinds.code.delivery.on_delivered"
        val entry = delivery.entries.entries.firstOrNull { it.key.content == "on_delivered" } ?: return null
        val onDelivered = entry.value as? YamlMap ?: throw WorkflowError.InvalidConfig("$prefix must be a map")
        val moveTo = onDelivered.string("$prefix.move_to", "move_to") ?: return null
        return moveTo.trim().takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$prefix.move_to must not be blank")
    }

    /**
     * m2-26: `kinds.code.delivery.pull_request.merge`, null when absent. Present, a map whose `method` is `merge`, the
     * default, or `squash`, never `rebase`, and whose `ci` is required, `none` or, since m3-15, `actions`. With
     * `actions`, `ci_timeout_ms` and `ci_no_run_grace_ms` are positive, the grace below the timeout, and `ci_no_run` is
     * `fail`, the default, or `pass`. Under `none` no `ci_*` key is allowed, so a key that would change nothing is never
     * read as a CI wait. The optional `recheck_skip_paths` (bl-176) is a list of path globs with no blank one, anchored like
     * `guards.protect`, empty when absent.
     */
    private fun parseMerge(pullRequest: YamlMap): MergeConfig? {
        val prefix = "kinds.code.delivery.pull_request.merge"
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
     * m2-25: `kinds.code.delivery.pull_request`. Absent keeps the m2-24 move. Present, it must be a map. `repo` is
     * `owner/name` and `token` a `$NAME` reference whose variable is set, both required on the `files` tracker; on
     * another tracker they default to its own. `base`, `remote` and `api_url` are optional. Only the token's variable
     * name is kept, never its value.
     */
    private fun parsePullRequest(
        delivery: YamlMap,
        trackerKind: String,
        deferred: MutableList<DeferredSecret>,
    ): PullRequestConfig? {
        val prefix = "kinds.code.delivery.pull_request"
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
            merge = parseMerge(pullRequest),
        )
    }

    private fun parseCheck(
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
                guards ?: throw WorkflowError.InvalidConfig("$name diff_policy needs kinds.code.verify.guards.protect")
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
    private fun List<CheckConfig>.alsoOnce(): List<CheckConfig> {
        val repeat = withIndex().filter { it.value is CheckConfig.AcceptanceCommands }.drop(1).firstOrNull()
        if (repeat != null) {
            throw WorkflowError.InvalidConfig("kinds.code.verify.checks[${repeat.index}] repeats $ACCEPTANCE_COMMANDS, declare it once")
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

        /** The spec 11.4 categories of a `tracker.provider` that does not follow its tracker's profile (m3-05). */
        const val INVALID_TRACKER_CONFIG = "invalid_tracker_config"
        const val MISSING_TRACKER_SECRET = "missing_tracker_secret"
        const val DEFAULT_TRACKER_DIR = ".loop/issues"
        const val DEFAULT_WORKSPACES_DIR = "loop_workspaces"
        const val ACCEPTANCE_COMMANDS = "acceptance_commands"
        const val CHANGED_LINE_COVERAGE = "changed_line_coverage"

        /** bl-70: what an unset secret reads as while a load with deferred secrets checks the rest of its section. */
        const val DEFERRED_SECRET = "loop-deferred-secret"
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
        const val NON_NEGATIVE = "a non-negative integer"
        const val INTEGER = "an integer"
        const val FRACTION = "a fraction above 0 and at most 1"
        const val PERCENT = "a number from 0 to 100"
        const val DOLLARS = "a positive number of dollars"
        val DECIMAL = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
        val WINDOW_KEY = Regex("""^[^/\s]+/\S+$""")
        val DEFAULT_ACTIVE_STATES = listOf("todo", "in_progress")
        val DEFAULT_TERMINAL_STATES = listOf("done", "canceled")
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
