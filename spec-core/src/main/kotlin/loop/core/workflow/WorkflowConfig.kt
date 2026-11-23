package loop.core.workflow

import loop.api.MergeMethod
import java.math.BigDecimal

/**
 * The typed front matter sections of `WORKFLOW.md`, each with the spec's
 * defaults (5.3). A new section is one data class here and one parse function
 * in [WorkflowLoader].
 */
data class WorkflowConfig(
    val polling: PollingConfig = PollingConfig(),
    val hooks: HooksConfig = HooksConfig(),
    val agent: AgentConfig = AgentConfig(),
    val codex: CodexConfig = CodexConfig(),
    val retries: RetriesConfig = RetriesConfig(),
    val budgets: BudgetsConfig = BudgetsConfig(),
    val context: ContextConfig = ContextConfig(),
    val observability: ObservabilityConfig = ObservabilityConfig(),
    /** The `kinds:` key (m2-03): null is Symphony mode, a value is Loop mode. */
    val kinds: KindsConfig? = null,
    /**
     * Every `tracker.provider` key as written, adapter owned keys included, for the
     * adapter to validate (spec 5.3.1). `$NAME` values are kept unresolved here.
     */
    val trackerProvider: Map<String, ConfigValue> = emptyMap(),
    /** Every `$NAME` the loader resolved, by name only, for redaction. */
    val envRefs: List<EnvRef> = emptyList(),
    /**
     * Every name a front matter string value at any depth references as exactly `$NAME`,
     * resolved by the loader or left as written (m1-23, kept out of the agent env).
     */
    val referencedEnvNames: Set<String> = emptySet(),
)

/**
 * Loop mode (m2-03, plan 6.0 and 6.2). For now one kind, `code`, matches every issue. Its verify
 * contract is a list of [checks], run host side when the agent submits its result.
 */
data class KindsConfig(
    val code: KindConfig,
)

/**
 * One kind: its verify contract and its readiness check. An empty [checks] list is valid config: nothing
 * proves the work, so nothing moves. A null [ready] runs no readiness check (m2-11). [guards] is the
 * `verify.guards` key (m2-12); its protected paths are already in each [CheckConfig.DiffPolicy] of [checks].
 * [holdout] holds the hidden checks (m2-14), run after every visible check passed, from the holdout folder
 * outside every workspace; empty when the kind declares none. [delivery] is how a verified result is delivered
 * (m2-24), `human` by default.
 */
data class KindConfig(
    val checks: List<CheckConfig>,
    val ready: ReadyConfig? = null,
    val guards: GuardsConfig? = null,
    val holdout: List<CheckConfig> = emptyList(),
    val delivery: DeliveryConfig = DeliveryConfig(),
)

/**
 * A kind's delivery (m2-24, plan 6.5, story 77): with [DeliveryPolicy.Human] a verified result waits for
 * `loop approve`, with [DeliveryPolicy.Auto] it is delivered at once.
 */
data class DeliveryConfig(
    val policy: DeliveryPolicy = DeliveryPolicy.Human,
    /** `delivery.pull_request` (m2-25): present, a verified result is delivered as a pull request; null keeps the m2-24 move. */
    val pullRequest: PullRequestConfig? = null,
    /**
     * `delivery.on_delivered.move_to` (m2-26): the terminal state an issue moves to once its pull request is merged.
     * Required with [PullRequestConfig.merge], null when absent.
     */
    val onDeliveredMoveTo: String? = null,
    /** `delivery.auto_merge` (m2-28): the opt in of `policy: auto`, the protected paths and the size limit of an auto merge. */
    val autoMerge: AutoMergeConfig = AutoMergeConfig(),
    /** `delivery.risk` (m2-28): the merge risk assessment of an auto merge, its reviewer and the paths its facts read. */
    val risk: RiskConfig = RiskConfig(),
)

/**
 * `delivery.auto_merge` (m2-28, story 100): [enabled] is the opt in every `policy: auto` on a `code` kind needs. With
 * `pull_request.merge`, an auto merge also needs a diff that touches none of the [protectedPaths], globs anchored at
 * the workspace root like `guards.protect`, and changes at most [maxDiffLines] lines, added plus deleted.
 */
data class AutoMergeConfig(
    val enabled: Boolean = false,
    val protectedPaths: List<String> = DEFAULT_PROTECTED_PATHS,
    val maxDiffLines: Int = DEFAULT_MAX_DIFF_LINES,
) {
    companion object {
        /**
         * What only a human merges by default: the harness, its build and its decisions, and what steers an agent or the
         * merge risk reviewer or changes how git diffs (send back 3 of PR #75): Claude Code settings and memory files,
         * the MCP config and git attributes.
         */
        val DEFAULT_PROTECTED_PATHS: List<String> =
            listOf(
                "WORKFLOW.md",
                "scripts/",
                ".github/",
                "**/*.gradle",
                "**/*.gradle.kts",
                "**/gradle.properties",
                "**/gradlew",
                "**/gradlew.bat",
                "build-logic/",
                "gradle/",
                ".loop/",
                "docs/adr/",
                "CONTEXT.md",
                "**/.claude/",
                "**/CLAUDE.md",
                "**/AGENTS.md",
                ".mcp.json",
                "**/.gitattributes",
            )
        const val DEFAULT_MAX_DIFF_LINES: Int = 400
    }
}

/**
 * `delivery.risk` (m2-28): the merge risk assessment that follows a passing merge check under `policy: auto`. The
 * [reviewer] model judges the five dimensions in a read only session of [timeoutMs] at most; code facts read the
 * [sensitivePaths] (impact) and the [oneWayPaths] (rollback cost), globs anchored like `guards.protect`.
 */
data class RiskConfig(
    val reviewer: String? = null,
    val sensitivePaths: List<String> = DEFAULT_SENSITIVE_PATHS,
    val oneWayPaths: List<String> = DEFAULT_ONE_WAY_PATHS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        /** Dependency and lock files, build and CI config, migrations and SQL. */
        val DEFAULT_SENSITIVE_PATHS: List<String> =
            listOf(
                "**/package.json",
                "**/package-lock.json",
                "**/yarn.lock",
                "**/pnpm-lock.yaml",
                "**/Gemfile",
                "**/Gemfile.lock",
                "**/Cargo.toml",
                "**/Cargo.lock",
                "**/go.mod",
                "**/go.sum",
                "**/requirements*.txt",
                "**/poetry.lock",
                "**/Podfile",
                "**/Podfile.lock",
                "**/Package.resolved",
                "**/*.gradle",
                "**/*.gradle.kts",
                "**/gradle.properties",
                "**/libs.versions.toml",
                "**/gradle/",
                "**/build-logic/",
                "**/Makefile",
                "**/Dockerfile",
                ".github/",
                "**/project.yml",
                "**/Fastfile",
                "**/migrations/**",
                "**/*.sql",
            )
        val DEFAULT_ONE_WAY_PATHS: List<String> = listOf("**/migrations/**")
        const val DEFAULT_TIMEOUT_MS: Long = 600_000
    }
}

/**
 * A kind's pull request delivery (m2-25, story 79): the verified commit is pushed to `loop/<workspace key>` on
 * [remote] from the project repository, and a pull request against [base] is opened or updated on [repo] with the
 * token of [tokenEnvName]. [repo] (`owner/name`) and [tokenEnvName] default to the tracker's own when null, which only
 * a `github` tracker allows. [apiUrl] defaults to the tracker's, else GitHub's. Only the variable's name is kept here,
 * never the token: the host reads it from its env when it delivers.
 */
data class PullRequestConfig(
    val repo: String? = null,
    val tokenEnvName: String? = null,
    val base: String = DEFAULT_BASE,
    val remote: String = DEFAULT_REMOTE,
    val apiUrl: String? = null,
    /** `pull_request.merge` (m2-26): present, an approved pull request is merge checked and merged; null keeps m2-25. */
    val merge: MergeConfig? = null,
) {
    companion object {
        const val DEFAULT_BASE: String = "main"
        const val DEFAULT_REMOTE: String = "origin"
    }
}

/**
 * A kind's merge of its pull request (m2-26): merged with [method] once the merge check passed, after the CI wait of
 * [ci]. A rebase is never a method, since it rewrites the commits and what merges is no longer what was checked. With
 * [CiMode.Actions] (m3-15) the merge waits for the Actions runs of the merged head: at most [ciTimeoutMs] of answered
 * polls while runs are pending, and [ciNoRun] decides once no run showed up after [ciNoRunGraceMs]. A base that moved
 * after the check only in files that match [recheckSkipPaths] (bl-176), globs anchored at the workspace root like
 * `guards.protect` and empty by default, costs no new round when the pull request changed none of those files.
 */
data class MergeConfig(
    val method: MergeMethod = MergeMethod.Merge,
    val ci: CiMode,
    val ciTimeoutMs: Long = DEFAULT_CI_TIMEOUT_MS,
    val ciNoRun: CiNoRun = CiNoRun.Fail,
    val ciNoRunGraceMs: Long = DEFAULT_CI_NO_RUN_GRACE_MS,
    val recheckSkipPaths: List<String> = emptyList(),
) {
    companion object {
        /** 45 minutes of answered polls while runs are pending. */
        const val DEFAULT_CI_TIMEOUT_MS: Long = 45 * 60_000L

        /** 5 minutes of answered polls with no run before the no run rule decides. */
        const val DEFAULT_CI_NO_RUN_GRACE_MS: Long = 5 * 60_000L
    }
}

/** What a merge waits for on the provider's CI (m2-26): nothing, or the GitHub Actions runs of the merged head (m3-15). */
enum class CiMode(
    val wire: String,
) {
    /** No CI run is waited for: the merge check of Loop is the only gate. */
    None("none"),

    /** The Actions runs of the merged head must be green before the merge (m3-15). */
    Actions("actions"),
}

/**
 * What a merged head with no Actions run at all gets once the grace is over (m3-15): a workflow with `paths-ignore` may
 * start no run for a docs only change, so the operator says whether that passes.
 */
enum class CiNoRun(
    val wire: String,
) {
    /** No run fails the CI wait, the default. */
    Fail("fail"),

    /** No run passes the CI wait. */
    Pass("pass"),
}

/** How a verified result is delivered, by its `delivery.policy` name. */
enum class DeliveryPolicy(
    val wire: String,
) {
    /** It waits as `Waiting(approval)` until `loop approve`, then it is delivered. The default. */
    Human("human"),

    /**
     * It is delivered at once: on the files tracker, the move to the handoff state (m2-03). Needs the opt in
     * `delivery.auto_merge.enabled` (m2-28); with `pull_request.merge` it merges only when the auto merge conditions
     * hold and the merge risk band is low, else it falls back to [Human].
     */
    Auto("auto"),
}

/**
 * A kind's guards (m2-12 and m2-13, plan 6.4): the [protect]ed path globs, anchored at the workspace root, that an
 * attempt's diff against its merge base with [base] must not touch, and the [deny] guards that refute the shortcuts
 * found in that diff, in the test files of the [languages].
 */
data class GuardsConfig(
    val protect: List<String>,
    val base: String = DEFAULT_BASE,
    val deny: List<DenyGuard> = emptyList(),
    val languages: List<TestLanguage> = TestLanguage.BUILT_IN,
) {
    companion object {
        const val DEFAULT_BASE: String = "main"
    }
}

/** A `guards.deny` entry (m2-13): a shortcut that refutes the diff it is found in. */
enum class DenyGuard(
    val key: String,
) {
    DELETED_TESTS("deleted_tests"),
    ADDED_SKIPS("added_skips"),
    WEAKER_ASSERTIONS("weaker_assertions"),
    ;

    companion object {
        fun of(key: String): DenyGuard? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How the deny guards read the tests of one language (m2-13): its test [files] as anchored globs, like
 * `guards.protect`, and the Java regular expressions, `^` and `$` matching at each line, that find a [test]
 * (group 1, when the pattern has one, is the test's name), a [skip] and an [assertion]. A null pattern turns off
 * the guards that need it. Lines that start with [lineComment], and [blockComment] spans that start a line, are
 * left out first, so commenting out a test or an assertion counts as removing it.
 */
data class TestLanguage(
    val name: String,
    val files: List<String>,
    val test: String? = null,
    val skip: String? = null,
    val assertion: String? = null,
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
) {
    companion object {
        /**
         * An annotation's arguments, one level of parentheses nested. Every repeated group of the built in patterns
         * is possessive: Java runs a possessive group loop without recursion, so a long input cannot overflow the
         * stack, and nothing is given back to try again.
         */
        private const val ARGS = """(?:\((?:[^()]++|\([^()]*+\))*+\))?+"""

        /** Kotlin with JUnit 4, JUnit 5 and kotlin.test. */
        val KOTLIN =
            TestLanguage(
                name = "kotlin",
                files = listOf("**/src/test/**/*.kt", "**/src/*Test/**/*.kt", "**/*Test.kt", "**/*Tests.kt"),
                test =
                    """@(?:[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b$ARGS\s*+""" +
                        """(?:@[\w.:]++$ARGS\s*+|(?:public|internal|private|protected|override|open|final|suspend)\s++)*+""" +
                        """fun\s+(`[^`\n]+`|\w+)""",
                skip = """@(?:[\w.]+\.)?(?:Disabled\w*|Ignore)\b""",
                assertion = """\b(?:assert\w*|fail|(?:co)?verify\w*)\s*+(?:<(?:[^<>\n]++|<[^<>\n]*+>)*+>\s*+)?+[({]|\bshould[A-Z]\w*+""",
                lineComment = "//",
                blockComment = "/*" to "*/",
            )

        /** Swift with XCTest and Swift Testing. */
        val SWIFT =
            TestLanguage(
                name = "swift",
                files = listOf("**/*Tests.swift", "**/*Test.swift", "**/*Tests/**/*.swift"),
                test =
                    """@Test\b$ARGS\s*+(?:@\w++$ARGS\s*+|(?:public|private|internal|fileprivate|static|final|override|""" +
                        """open|nonisolated|mutating)\s++)*+func\s+(\w+)|\bfunc\s+(test\w*+)\s*+\(""",
                skip = """\bXCTSkip\w*\b|@(?:Test|Suite)\s*\([^\n]*\.disabled\b""",
                assertion = """\bXCT(?:Assert\w*|Fail|Unwrap)\s*\(|#expect\b|#require\b""",
                lineComment = "//",
                blockComment = "/*" to "*/",
            )

        /** The languages every deny guard knows; `guards.languages` adds others or replaces one by name. */
        val BUILT_IN: List<TestLanguage> = listOf(KOTLIN, SWIFT)
    }
}

/**
 * A kind's readiness check (m2-11, plan 6.2): the description sections an issue needs before it is
 * dispatched, by heading text, at least one.
 */
data class ReadyConfig(
    val requireSections: List<String>,
)

/**
 * One check of a verify contract (plan 6.4), one of the core types `command` (m2-03), `file_exists`,
 * `file_matches` and `diff_policy` (m2-12), `changed_line_coverage` (bl-117), or `acceptance_commands` (m2-09),
 * which expands to command checks at verification. [label] is how its events, evidence and findings show it.
 */
sealed interface CheckConfig {
    val label: String

    /** `sh -lc <run>` in the workspace; it passes on exit 0 within [timeoutMs]. */
    data class Command(
        val run: String,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : CheckConfig {
        override val label: String get() = run
    }

    /** Passes when [path], relative to the workspace and inside it, is a file or a folder. */
    data class FileExists(
        val path: String,
    ) : CheckConfig {
        override val label: String get() = "file_exists $path"
    }

    /** Passes when the regular expression [pattern] is found in the file at [path], `^` and `$` matching at each line. */
    data class FileMatches(
        val path: String,
        val pattern: String,
    ) : CheckConfig {
        override val label: String get() = "file_matches $path /$pattern/"
    }

    /**
     * Passes when the workspace's diff against its merge base with [base] touches none of the [protect]ed path globs
     * (m2-12) and breaks none of the [deny] guards in the test files of the [languages] (m2-13).
     */
    data class DiffPolicy(
        val protect: List<String>,
        val base: String = GuardsConfig.DEFAULT_BASE,
        val deny: List<DenyGuard> = emptyList(),
        val languages: List<TestLanguage> = TestLanguage.BUILT_IN,
    ) : CheckConfig {
        override val label: String
            get() =
                "diff_policy base=$base" +
                    (if (protect.isEmpty()) "" else " protect=${protect.joinToString(",")}") +
                    (if (deny.isEmpty()) "" else " deny=${deny.joinToString(",") { it.key }}")
    }

    /**
     * Passes when the lines the workspace's diff against its merge base with [base] adds or changes are covered at
     * least [minPercent] percent, by the JaCoCo XML reports the [reports] globs find in the workspace (bl-117). Only
     * the lines a report lists with instructions count, so it also passes when no changed line counts. No report, or
     * one that cannot be parsed, means it could not run.
     */
    data class ChangedLineCoverage(
        val reports: List<String>,
        val minPercent: Double,
        val base: String = GuardsConfig.DEFAULT_BASE,
    ) : CheckConfig {
        override val label: String
            get() = "changed_line_coverage base=$base min_percent=${percent(minPercent)} reports=${reports.joinToString(",")}"
    }

    /**
     * The commands of the issue's acceptance criteria (m2-09): at verification each one becomes a [Command]
     * check with [timeoutMs], in criteria order, in its place in the check list. The criteria without a
     * command are judgment calls, listed in the evidence and never passed.
     */
    data class AcceptanceCommands(
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : CheckConfig {
        override val label: String get() = "acceptance_commands"
    }

    companion object {
        /** One hour: a check is the only limit on itself, stall detection only watches runner events. */
        const val DEFAULT_TIMEOUT_MS: Long = 3_600_000L

        /** A percentage as written for people: `80` for a whole number, else its shortest decimal form, `62.5`. */
        fun percent(value: Double): String =
            BigDecimal
                .valueOf(value)
                .stripTrailingZeros()
                .toPlainString()
    }
}

/** A resolved `$NAME` reference: the variable and the key that used it. Never its value. */
data class EnvRef(
    val name: String,
    val keyPath: String,
)

/**
 * Plan 10.3 (m2-18): attempt files older than [retentionDays] are pruned when `loop run` starts. The
 * plan's machine level `store` and `otlp` keys are not read: `LOOP_HOME` sets the store, and OTLP is M3.
 */
data class ObservabilityConfig(
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    companion object {
        const val DEFAULT_RETENTION_DAYS: Int = 90
    }
}

/** Spec 5.3.2. */
data class PollingConfig(
    val intervalMs: Long = 30_000L,
)

/**
 * Spec 5.3.4. Parsed and validated here, run by the workspace hooks. [onTokenThreshold] (m1-38) and
 * [onBudgetWarning] (m2-20) are Loop's own hooks, run on the host once per `token_threshold` or
 * `budget_warning` event; they never fail or stop the attempt.
 */
data class HooksConfig(
    val afterCreate: String? = null,
    val beforeRun: String? = null,
    val afterRun: String? = null,
    val beforeRemove: String? = null,
    val timeoutMs: Long = 60_000L,
    val onTokenThreshold: String? = null,
    val onBudgetWarning: String? = null,
)

/** Spec 5.3.5, plus Loop's `agent.env` (bl-01) and `agent.max_wake_turns` (m4-03). */
data class AgentConfig(
    val maxConcurrentAgents: Int = 10,
    val maxTurns: Int = 20,
    val maxRetryBackoffMs: Long = 300_000L,
    /** Keys are trimmed and lowercased states; invalid entries are dropped. */
    val maxConcurrentAgentsByState: Map<String, Int> = emptyMap(),
    /**
     * `agent.env`: names added to the agent env of every attempt, values as written. Each `{workspace}` is
     * replaced by the attempt's workspace path when the runner builds the env, nothing else is expanded.
     */
    val env: Map<String, String> = emptyMap(),
    /**
     * `agent.max_wake_turns`: the wake turns an attempt may run, turns that read the notification of a background task
     * run an earlier turn started. They never count against [maxTurns]; reaching this cap ends the attempt with
     * `max_wake_turns`, so a chatty background task cannot keep it alive forever.
     */
    val maxWakeTurns: Int = 30,
)

/**
 * Loop's spend budgets (m2-20) and token thresholds (m1-38). Each budget is opt in: null, the default, is no
 * budget. Tokens are the absolute total, cache reads included (m1-08), dollars the runner's `total_cost_usd`.
 * [perAttemptTokens] and [perAttemptUsd] measure one attempt, [perIssueTokens] and [perIssueUsd] every attempt of
 * the issue, [perDayTokens] and [perDayUsd] every attempt of the project in the last 24 hours. At 80 percent of a
 * budget the attempt warns, past it the attempt stops and the issue is parked with `budget`. Every
 * [tokenThresholdEvery] tokens the attempt records a `token_threshold` event and goes on; 0 turns thresholds off.
 */
data class BudgetsConfig(
    val perAttemptTokens: Long? = null,
    val tokenThresholdEvery: Long = 10_000_000L,
    val perAttemptUsd: Double? = null,
    val perIssueTokens: Long? = null,
    val perIssueUsd: Double? = null,
    val perDayTokens: Long? = null,
    val perDayUsd: Double? = null,
)

/**
 * Loop's context limits (plan 8.2 and 8.4), parsed and validated from m1-17 and enforced from M3. The
 * limits are fractions of a model's window: [softLimit] rotates the session at the next turn boundary,
 * [hardLimit] stops the turn and rotates, [promptLimit] caps the rendered prompt alone, with
 * `prompt_limit <= soft_limit <= hard_limit`. [toolOutputTokens] caps a tool output shown to the model,
 * [maxRotations] the rotations of one attempt. [windows] maps `provider/model` to its window in tokens:
 * [DEFAULT_WINDOWS] with the configured entries added or overriding them.
 */
data class ContextConfig(
    val softLimit: Double = DEFAULT_SOFT_LIMIT,
    val hardLimit: Double = DEFAULT_HARD_LIMIT,
    val promptLimit: Double = 0.05,
    val toolOutputTokens: Int = 2000,
    val maxRotations: Int = 5,
    val windows: Map<String, Long> = DEFAULT_WINDOWS,
) {
    companion object {
        /** The soft limit when the workflow sets none, the early rotation at a turn's end. */
        const val DEFAULT_SOFT_LIMIT = 0.20

        /**
         * The hard limit when the workflow sets none (m4-02, bl-144): 0.21, not 0.25. The hard limit fires on the first
         * report at or above it, so a session peaks above it by up to one request; 0.21 leaves room for that jump under
         * the 25 percent rule.
         */
        const val DEFAULT_HARD_LIMIT = 0.21

        /**
         * The windows Loop ships: the plan's Claude window (8.2), and the window of the pinned Codex model, the
         * `modelContextWindow` codex reported in the M3 exit run (m4-02).
         */
        val DEFAULT_WINDOWS: Map<String, Long> =
            mapOf(
                "anthropic/claude-opus-5-5" to 1_000_000L,
                "openai/gpt-6.1-sol" to 475_000L,
            )
    }
}

/**
 * Loop's attempt caps (spec decisions answer 5). After [maxAttempts] consecutive
 * failed attempts the issue is parked. After [maxContinuations] consecutive normal
 * exits that change neither the issue state nor its labels, the issue is parked too.
 */
data class RetriesConfig(
    val maxAttempts: Int = 5,
    val maxContinuations: Int = 3,
)

/**
 * Spec 5.3.6. [command] is kept as the raw shell string, the policy values
 * pass through untouched.
 */
data class CodexConfig(
    val command: String = "codex app-server",
    val approvalPolicy: ConfigValue? = null,
    val threadSandbox: ConfigValue? = null,
    val turnSandboxPolicy: ConfigValue? = null,
    val turnTimeoutMs: Long = 3_600_000L,
    val readTimeoutMs: Long = 5_000L,
    /** Zero or negative disables stall detection. */
    val stallTimeoutMs: Long = 300_000L,
)

/** A front matter value with no kaml types, for adapter owned and pass-through keys. */
sealed interface ConfigValue {
    data class Str(
        val value: String,
    ) : ConfigValue

    data class ListValue(
        val items: List<ConfigValue>,
    ) : ConfigValue

    data class MapValue(
        val entries: Map<String, ConfigValue>,
    ) : ConfigValue

    data object Null : ConfigValue
}
