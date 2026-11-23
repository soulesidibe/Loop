package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.MergeMethod
import loop.core.template.TemplateEngine
import loop.core.workflow.AutoMergeConfig
import loop.core.workflow.CheckConfig
import loop.core.workflow.CiMode
import loop.core.workflow.DeliveryPolicy
import loop.core.workflow.DenyGuard
import loop.core.workflow.MergeConfig
import loop.core.workflow.Preflight
import loop.core.workflow.PreflightResult
import loop.core.workflow.PromptVariables
import loop.core.workflow.WorkflowFile
import loop.core.workflow.WorkflowLoader
import loop.runner.claudecode.ClaudeCodeSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Loop's own `WORKFLOW.md` at the repository root (m1-12, plan D5 and D6), the one a self hosted run reads. */
class OwnWorkflowTest {
    @TempDir
    lateinit var home: Path

    private val workflowPath: Path =
        Path(System.getProperty("loop.rootWorkflow") ?: fail("system property loop.rootWorkflow is not set")).toRealPath()

    private val repoRoot: Path = workflowPath.parent

    /**
     * The real preflight of `loop run`, with an env that holds only the delivery token (m3-01): any other `$NAME`
     * outside the hooks would fail it.
     */
    private fun preflight(env: Map<String, String> = mapOf(TOKEN to "test-token")): PreflightResult {
        val loader = WorkflowLoader(env = env, home = home)
        val check = Preflight(loader, workflowPath, TemplateEngine(), { trackerProblem(it) })
        return runBlocking { check.check() }
    }

    private fun loaded(): WorkflowFile = assertIs<PreflightResult.Ok>(preflight(), "the root WORKFLOW.md fails preflight").workflow

    @Test
    fun `the root WORKFLOW_md passes preflight`() {
        val result = preflight()

        assertIs<PreflightResult.Ok>(result, result.toString())
    }

    @Test
    fun `m3-01 the root WORKFLOW_md fails preflight without the delivery token`() {
        val result = preflight(env = emptyMap())

        assertIs<PreflightResult.Failed>(result, result.toString())
        assertContains(result.toString(), TOKEN)
    }

    @Test
    fun `m3-01 the code kind verifies with the build, the acceptance commands and the guards`() {
        val kind = loaded().config.kinds?.code ?: fail("the root WORKFLOW.md has no kinds.code")

        assertEquals(listOf("Acceptance criteria"), kind.ready?.requireSections)
        val checks = kind.checks
        assertEquals(3, checks.size, checks.toString())
        val policy = assertIs<CheckConfig.DiffPolicy>(checks[0])
        assertEquals(CheckConfig.Command(run = "./gradlew --no-daemon build"), checks[1])
        assertIs<CheckConfig.AcceptanceCommands>(checks[2])
        assertEquals(emptyList(), kind.holdout)

        val protect =
            listOf(
                "scripts/",
                ".github/",
                "WORKFLOW.md",
                "*.gradle.kts",
                "*/*.gradle.kts",
                "plugins/*/*.gradle.kts",
                "gradle/",
                "build-logic/src/",
                "gradle.properties",
                "gradlew",
                "gradlew.bat",
                ".loop/specs/",
                "docs/adr/",
                "CONTEXT.md",
            )
        val deny = listOf(DenyGuard.DELETED_TESTS, DenyGuard.ADDED_SKIPS, DenyGuard.WEAKER_ASSERTIONS)
        assertEquals(protect, kind.guards?.protect)
        assertEquals(deny, kind.guards?.deny)
        assertEquals("main", kind.guards?.base)
        assertEquals(protect, policy.protect)
        assertEquals(deny, policy.deny)
        // The worktree hook keeps .loop/issues/ out of every workspace under skip-worktree: protecting it would make
        // every diff_policy check unverified (docs/spec-decisions.md, deny guards).
        assertFalse(protect.any { it == ".loop/" || it == ".loop" || it.startsWith(".loop/issues") }, protect.toString())
    }

    @Test
    fun `m3-01 a verified result is delivered as a pull request and merged under conditional auto merge`() {
        val delivery =
            loaded()
                .config.kinds
                ?.code
                ?.delivery ?: fail("the root WORKFLOW.md has no kinds.code")

        assertEquals(DeliveryPolicy.Auto, delivery.policy)
        assertTrue(delivery.autoMerge.enabled)
        assertEquals(1000, delivery.autoMerge.maxDiffLines)
        assertEquals(AutoMergeConfig.DEFAULT_PROTECTED_PATHS, delivery.autoMerge.protectedPaths)
        assertEquals("claude-sonnet-5-5", delivery.risk.reviewer)
        val pullRequest = delivery.pullRequest ?: fail("no pull_request")
        assertEquals("soulesidibe/Loop", pullRequest.repo)
        assertEquals(TOKEN, pullRequest.tokenEnvName)
        assertEquals("main", pullRequest.base)
        assertEquals("origin", pullRequest.remote)
        assertEquals(MergeConfig(method = MergeMethod.Merge, ci = CiMode.Actions), pullRequest.merge)
        assertEquals("done", delivery.onDeliveredMoveTo)
    }

    @Test
    fun `git ignores the result file and nothing else under the loop folder (m2-15)`() {
        fun ignored(path: String): Boolean {
            val builder = ProcessBuilder("git", "-C", repoRoot.toString(), "check-ignore", "-q", "--no-index", path)
            builder.environment().keys.removeIf { it.startsWith("GIT_") }
            val process = builder.redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            return when (val code = process.waitFor()) {
                0 -> true
                1 -> false
                else -> fail("git check-ignore exited with code $code")
            }
        }

        assertTrue(ignored(".loop/result.json"), "an agent's git add -A never commits a result")
        assertFalse(ignored(".loop/specs/loop-m1-to-m3.md"))
        assertFalse(ignored(".loop/issues/m2-15.md"))
    }

    @Test
    fun `the tracker dispatches only todo issues labelled ready-for-agent`() {
        val workflow = loaded()

        assertEquals("files", workflow.trackerKind)
        assertEquals(repoRoot.resolve(".loop/issues"), workflow.trackerDir)
        assertEquals(listOf("todo"), workflow.activeStates)
        assertEquals(listOf("done", "canceled"), workflow.terminalStates)
        assertEquals(listOf("ready-for-agent"), workflow.requiredLabels)
        assertEquals(30_000L, workflow.pollingIntervalMs)
        assertEquals(repoRoot.resolve("workspaces"), workflow.workspaceRoot)
    }

    @Test
    fun `the limits are the ones of the spec`() {
        val config = loaded().config

        assertEquals(3, config.agent.maxConcurrentAgents)
        assertEquals(10, config.agent.maxTurns)
        assertEquals(5, config.retries.maxAttempts)
        assertEquals(3, config.retries.maxContinuations)
        // Thresholds report every 10M tokens and no ceiling parks Loop's own attempts (m1-38).
        assertNull(config.budgets.perAttemptTokens)
        assertEquals(10_000_000L, config.budgets.tokenThresholdEvery)
        assertEquals(1_200_000L, config.codex.stallTimeoutMs)
    }

    @Test
    fun `each attempt gets its own Gradle daemon pool, the caches stay in the shared Gradle home`() {
        val config = loaded().config

        // bl-01: the end of an attempt kills the daemons it started, so no other build may share them.
        assertEquals(
            mapOf("GRADLE_OPTS" to "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons"),
            config.agent.env,
        )
    }

    @Test
    fun `the runner is claude code with the default model`() {
        // m3-04: the claude-code section is read by its plugin.
        val runner = loaded().runner
        val settings = ClaudeCodeSettings.of(runner.section("claude-code"))

        assertEquals("claude-code", runner.kind)
        assertEquals("claude", settings.command)
        assertNull(settings.model)
        assertEquals(3_600_000L, settings.turnTimeout.inWholeMilliseconds)
        assertEquals(120_000L, settings.readTimeout.inWholeMilliseconds)
    }

    @Test
    fun `the hooks are the documented worktree scripts, written literally`() {
        val config = loaded().config
        val script = { name: String -> "sh \"\$(dirname \"\$LOOP_WORKFLOW\")/scripts/hooks/$name.sh\"" }

        assertEquals(script("worktree-after-create"), config.hooks.afterCreate?.trim())
        assertEquals(script("worktree-before-run"), config.hooks.beforeRun?.trim())
        assertEquals(script("worktree-before-remove"), config.hooks.beforeRemove?.trim())
        assertNull(config.hooks.afterRun)
        assertEquals(60_000L, config.hooks.timeoutMs)
        // No path value is a bare $NAME, so the loader resolves nothing. The delivery token is the only bare $NAME
        // (m3-01), kept as a name, so it is the only one kept out of the agent env.
        assertEquals(emptyList(), config.envRefs)
        assertEquals(setOf(TOKEN), config.referencedEnvNames)
    }

    @Test
    fun `the prompt renders for a first and a later attempt`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val issue = PromptVariables.SAMPLE_ISSUE

        val first = template.render(PromptVariables.of(issue, null)).getOrThrow()
        val retry = template.render(PromptVariables.of(issue, 1)).getOrThrow()

        listOf(first, retry).forEach { prompt ->
            assertContains(prompt, "loop/${issue.identifier}")
            assertContains(prompt, ".scratch/${issue.identifier}/progress.md")
            assertContains(prompt, "loop tool move review")
            assertContains(prompt, "loop tool move needs_info")
        }
        assertTrue(EARLIER_WORK_HEADING !in first, first)
        assertTrue(EARLIER_WORK_SENTENCE !in first, first)
        assertContains(retry, "# $EARLIER_WORK_HEADING")
        assertContains(retry, EARLIER_WORK_SENTENCE)
    }

    @Test
    fun `bl-129 a first attempt in a reused workspace is told to build on the earlier work`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val issue = PromptVariables.SAMPLE_ISSUE

        val reused = template.render(PromptVariables.of(issue, null, workspaceReused = true)).getOrThrow()
        val fresh = template.render(PromptVariables.of(issue, null, workspaceReused = false)).getOrThrow()

        val both = template.render(PromptVariables.of(issue, 1, workspaceReused = true)).getOrThrow()

        listOf(reused, both).forEach { prompt ->
            assertEquals(1, prompt.lines().count { it == "# $EARLIER_WORK_HEADING" }, prompt)
            assertContains(prompt, EARLIER_WORK_SENTENCE)
            assertContains(prompt, "Read your progress file if it exists")
        }
        assertTrue(EARLIER_WORK_HEADING !in fresh, fresh)
        assertTrue(EARLIER_WORK_SENTENCE !in fresh, fresh)
    }

    @Test
    fun `m2-17 the prompt names the MCP tracker tools, with loop tool as the fallback`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val prompt = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, null)).getOrThrow()

        listOf("`mcp__loop__move`", "`mcp__loop__comment`", "when they are available").forEach { assertContains(prompt, it) }
        // An installed build may not serve the tools yet: every line that names one names the CLI too, never MCP only.
        val mcpLines = prompt.lines().filter { "mcp__loop__" in it }
        assertTrue(mcpLines.size >= 3, prompt)
        mcpLines.forEach { assertContains(it, "loop tool ") }
        assertContains(prompt, "`mcp__loop__move` with state `review`, else `loop tool move review`")
        assertContains(prompt, "`mcp__loop__move` with state `needs_info`, else `loop tool move needs_info`")
        assertContains(prompt, "`mcp__loop__comment`, else `loop tool comment`")
    }

    @Test
    fun `bl-06 the prompt renders the issue description, so the answers under comments reach the agent`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val answer = "2026-11-08 soule: Yes, include the archived items."
        val issue = PromptVariables.SAMPLE_ISSUE.copy(description = "## What\n\nExport items.\n\n## Comments\n\n$answer")

        val prompt = template.render(PromptVariables.of(issue, 1)).getOrThrow()

        assertTrue(template.uses("issue", "description"), "the root WORKFLOW.md never uses issue.description")
        assertContains(prompt, "## Comments\n\n$answer")
    }

    @Test
    fun `every dispatch is told to merge origin main when its branch is behind`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val issue = PromptVariables.SAMPLE_ISSUE

        // A null attempt is a first dispatch, including a fresh one after a move from review back to todo.
        val first = template.render(PromptVariables.of(issue, null)).getOrThrow()
        val retry = template.render(PromptVariables.of(issue, 1)).getOrThrow()

        // m1-42: the hook never syncs with main, the agent does, so it can resolve conflicts and prove the build.
        // bl-59: Loop fetches the base into refs/loop/base/main before each run, so the agent syncs with it.
        // bl-02: the sync sits in "Your workspace", so it renders on every dispatch, not only on a retry.
        val sync =
            listOf(
                "`git merge-base --is-ancestor refs/loop/base/main HEAD`",
                "On a fresh branch it passes and there is nothing to do.",
                "`git merge refs/loop/base/main`",
                "never fetch or pull",
                "keeping the intent of both sides",
                "commit the merge with a message that starts with `${issue.identifier}: `",
                "run the focused tests of the code the merge touched before anything else",
            )
        listOf(first, retry).forEach { prompt ->
            sync.forEach { assertContains(prompt, it) }
            assertTrue(prompt.indexOf("# Your workspace") < prompt.indexOf("git merge-base"), prompt)
            assertTrue(prompt.indexOf("git merge-base") < prompt.indexOf("# How to work"), prompt)
            assertEquals(1, prompt.split("git merge refs/loop/base/main").size - 1, prompt)
        }
    }

    @Test
    fun `the prompt keeps every agent command under the tool limit and leaves the full build to verification`() {
        val template = TemplateEngine().parse(loaded().promptTemplate, PromptVariables.schema).getOrThrow()
        val prompt = template.render(PromptVariables.of(PromptVariables.SAMPLE_ISSUE, null)).getOrThrow()

        // bl-95: a full build takes about 15 minutes, over the 10 minute limit of one agent command. Agents that started
        // it in the background and ended their turn went silent until the stall timeout, 11 stalls in one night.
        listOf(
            "No command may run longer than 10 minutes",
            "never end your turn while a command still runs",
            "Never run the full `./gradlew build`",
            "Loop runs it itself to verify your result",
            "`./gradlew ktlintCheck`",
            "\"verified by Loop\"",
        ).forEach { assertContains(prompt, it) }
        assertTrue("Run `./gradlew build` and save" !in prompt, prompt)
        assertTrue("rerun the full `./gradlew build`" !in prompt, prompt)
        // The stall timeout stays above the longest silent tool call, so a 10 minute command never trips it.
        assertTrue(loaded().config.codex.stallTimeoutMs > 10 * 60_000L)
    }

    @Test
    fun `the template copy of the golden prompt test is the root WORKFLOW_md prompt`() {
        val copy = repoRoot.resolve("spec-core/src/test/resources/prompt/own-template.md").toFile().readText()

        // The loader trims the prompt body, the copy keeps its final newline.
        assertEquals(loaded().promptTemplate.trim(), copy.trim())
    }

    private companion object {
        const val TOKEN = "LOOP_PR_TOKEN"
        const val EARLIER_WORK_HEADING = "Earlier work on this issue"
        const val EARLIER_WORK_SENTENCE =
            "An earlier attempt may have left work on this issue in this workspace or on the current branch."
    }
}
