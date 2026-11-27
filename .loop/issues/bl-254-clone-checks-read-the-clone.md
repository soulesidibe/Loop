---
id: bl-254
title: "Clone workspaces: the core checks and the protected roots check must read the clone, not the project"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-253]
area: [spec-core/src/main/kotlin/loop/core/verify/CoreChecks.kt, spec-core/src/main/kotlin/loop/core/verify/Checks.kt, plugin-api/src/main/kotlin/loop/api/AttemptContext.kt, plugins/runner-acp/src/main/kotlin/loop/runner/acp/ProtectedRootsCheck.kt, plugins/runner-acp/src/main/kotlin/loop/runner/acp/AcpRunner.kt, spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt, spec-core/src/test/kotlin/loop/core/verify/CoreChecksTest.kt, spec-core/src/test/kotlin/loop/core/verify/ChangedLineCoverageTest.kt, plugins/runner-acp/src/test/kotlin/loop/runner/acp/ProtectedRootsCheckTest.kt]
---

## Parent

bl-253, which adds the clone layout check and the environment pins to `WorkspaceGit` and to `WorkspaceClones.state`. This issue applies the same rule to the two git runners bl-253 leaves out. Split from bl-253 after a Fable 5.2 review of its brief. Backlog, not scheduled in a milestone; it does not gate pull request #103.

## What

The core checks' private `Git` runner (`diff_policy`, `changed_line_coverage`) and the ACP runner's `ProtectedRootsCheck.runGit` start git in a clone workspace with the minimal environment, which drops every `GIT_` variable, so git finds the repository, the work tree and the common folder from the clone's own `.git`, which the agent owns. With a hostile `commondir`, `extensions.refStorage` or `core.worktree` in that `.git` (see bl-253), these checks read the project's refs or the project's files instead of the clone's. They only read: nothing here writes the project, but a check that reads the wrong tree passes or refuses on the wrong evidence, which is a verification bypass.

### Current behavior

- `CoreChecks.diffPolicy` and `changedLineCoverage` run git through the private `Git` runner (`CoreChecks.kt` runner around 613-635), which builds its own environment and drops every `GIT_` variable. `Checks.kt` around 112-113 calls them with the workspace folder and `env`, and knows nothing of the clone mode.
- `ProtectedRootsCheck.refusal(ctx.workspace, ctx.baseRef)` (called from `AcpRunner.kt`) finds the repository with `findGitRoot`, which walks up from the workspace to the first folder holding `.git`, so with the clone's `.git` missing it lands on the project. `runGit` and `minimalGitEnv` run git from that folder.
- `loop.api.AttemptContext` carries the workspace and the base ref, not the workspace git mode, and the ACP runner plugin does not depend on spec-core.

### Desired behavior

1. **Same rule as bl-253.** When the workflow's `workspaceGit` is `Clone`, every git process the core checks' `Git` runner and `ProtectedRootsCheck.runGit` start in the clone workspace runs the bl-253 layout check first and gets the pins `GIT_DIR=<workspace>/.git`, `GIT_COMMON_DIR=<workspace>/.git` and `GIT_WORK_TREE=<workspace>`, real paths, set after any caller supplied variable, together with bl-246's `GIT_NO_LAZY_FETCH=1`. A failed check runs no git: the core checks end as `could not run` with the reason, the protected roots check refuses with it. `hooks` mode keeps today's behavior whatever its `.git` is.
2. **Mode from the workflow, never from `.git` shape.** In clone mode a `.git` that is a file, a link or missing is a refusal, and `findGitRoot` must not walk up past the workspace to the project.
3. **Shared rule in spec-core, a copy in the ACP plugin.** The core checks call bl-253's check. The ACP runner plugin does not depend on spec-core, so it carries its own copy of the layout check and the pins, as it does for the minimal environment today.
4. **The `--show-toplevel` guard** of `CoreChecks.kt` around 244 always passes once `GIT_WORK_TREE` is pinned in clone mode. Keep it for `hooks` mode.

### Key interfaces

- `Checks.kt` around 112-113 must pass clone mode to `CoreChecks`.
- The core checks' private `Git` runner.
- For the ACP runner, add a defaulted field to `loop.api.AttemptContext` (plugin-api, not protected) or an equal explicit input. The orchestrator builds the context (`Orchestrator.kt` around 3038); the risk reviewers and the contract fixture build it too and keep the default.
- `ProtectedRootsCheck.refusal`, `runGit`, `minimalGitEnv`, `findGitRoot`; `AcpRunner` call sites of `refusal`.
- Tests to extend: `CoreChecksTest`, `ChangedLineCoverageTest`, `ProtectedRootsCheckTest`. Grep large files, never read them whole.

### Test recipes

Reuse bl-253's recipes (hostile `commondir`, links in `.git`, hostile `extensions.refStorage`, hostile `core.worktree`, replaced `.git`, unchanged project), each proven live first.

## Acceptance criteria

- [ ] With a clone that passed reuse and then got the hostile `commondir`, the core checks' `diff_policy` and `changed_line_coverage` end as `could not run` with a reason naming the `commondir` file, and `ProtectedRootsCheck.refusal` refuses with it; the project is unchanged.
- [ ] The same with the hostile `extensions.refStorage` set late: both core checks end as `could not run` naming the key, the protected roots check refuses, no project ref changes.
- [ ] A clone whose `.git` is replaced by a `gitdir:` file naming the project's `.git`, and in a second case removed: both core checks end as `could not run`, the protected roots check refuses, and `findGitRoot` does not reach the project.
- [ ] A real git test, for both `core.worktree` variants (each proven live first): `diff_policy`, `changed_line_coverage` and `ProtectedRootsCheck.refusal` read the clone, `diff_policy` sees the clone's untracked files, not the project's, and the project's file listing with content hashes, its `.git/index` hash and `git status --porcelain` are unchanged.
- [ ] A unit test shows, for a clone workspace in clone mode, `GIT_DIR`, `GIT_COMMON_DIR` (both the clone's `.git`) and `GIT_WORK_TREE` (the clone) in the environment of every process the core checks' `Git` runner and `ProtectedRootsCheck.runGit` start, also when a caller passes other values for them, with bl-246's `GIT_NO_LAZY_FETCH=1` still present.
- [ ] A `hooks` mode workspace gets neither the check nor the pins in either runner, and an `AttemptContext` built without the new field behaves as today, shown by tests.
- [ ] `./gradlew :spec-core:test --tests "loop.core.verify.*"` passes.
- [ ] `./gradlew :plugins:runner-acp:test --tests "loop.runner.acp.ProtectedRootsCheckTest"` passes.
- [ ] `./gradlew build` passes.
- [ ] The diff touches none of the protected paths: `scripts/`, `.github/`, `WORKFLOW.md`, `CONTEXT.md`, `*.gradle.kts`, `*/*.gradle.kts`, `plugins/*/*.gradle.kts`, `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew*`, `.loop/specs/`, `docs/adr/`. A change one of them needs is listed under `residual_human_steps` with its exact proposed text.
- [ ] The proof of each live setup and of each guarded run is posted as an issue comment with `loop tool comment`, not in the pull request body, which Loop writes.
- [ ] `docs/workspaces.md` (and the clone section of `docs/spec-decisions.md` if it names the rule) says the core checks and the protected roots check follow the same clone rule as `WorkspaceGit`.

## Out of scope

- `WorkspaceGit`, `WorkspaceClones.state` and the docs of the rule: bl-253.
- Pins in `hooks` mode.
- A race with an agent process still running while Loop checks and then runs git: the M5 sandbox closes it.
- Any protected path: `scripts/`, `.github/`, `WORKFLOW.md`, `CONTEXT.md`, the Gradle files, `.loop/specs/`, `docs/adr/`.

Tool calls are limited to about 10 minutes: run the module test tasks above one at a time, and the full build last.

## Comments

2026-11-26 triage:

> *This was generated by AI during triage.*

Bug, ready-for-agent, priority 2, blocked by bl-253 so it reuses that change's layout check and mode plumbing. Split from bl-253 after a Fable 5.2 review of the brief on 2026-11-26: these two runners only read, so a hostile clone `.git` here bypasses verification but writes nothing in the project, and the fix needs a new input across the plugin API. It does not gate pull request #103. The replays behind it are in bl-253's triage comment: `ls-files --others --exclude-standard`, as `diff_policy` runs it (`CoreChecks.kt:127`), listed the project's untracked files under a hostile `core.worktree`, and `ProtectedRootsCheck` runs from the workspace folder (`ProtectedRootsCheck.kt:492-493`) and is affected alike.

2026-11-27 03:07Z agent 816e0dc8: bl-254 proofs, from the PROOF lines of `CoreChecksCloneTest` and the bl-254 tests of `ProtectedRootsCheckTest`. Real git 2.54, no user or system config. Each recipe was first run live with git started exactly as the check starts it today, then guarded on a fresh fixture.
  Live setups (today's behavior, no layout check, no pins):
  - Hostile commondir: the core checks' git in the clone read the project's moved `main`, and so did the protected roots check's `runGit`.
  - Hostile extensions.refStorage (`files://<project>/.git`): both runners read the project's `main`. In the red run before the fix, `diff_policy` reported the project's own commit as `added later.txt in HEAD`.
  - `.git` replaced by `gitdir: <project>/.git`: `rev-parse --absolute-git-dir` printed the project's `.git` in both runners.
  - `.git` removed, workspace root inside the project: the core checks' git walked up to the project's repository. `ProtectedRootsCheck.refusal` in hooks mode walked up through `findGitRoot` and judged a file only the project has: `protected_workspace_root: added or modified workspace customization file .agents/mcp_config.json`.
  - `core.worktree` set in `config` and set in `config.worktree`: `ls-files --others --exclude-standard` in the clone listed the project's files `[later.txt, untracked.txt]`, in both runners.
  Guarded runs (clone mode):
  - commondir: `diff_policy` and `changed_line_coverage` could not run with `Loop runs no git in the clone <ws>: <ws>/.git/commondir is there, which points git at another repository's refs and config`. The protected roots check refused with `protected_workspace_root: Loop runs no git in the clone <ws>: <ws>/.git/commondir is there, ...`. The project's refs, files, index and status were unchanged.
  - refStorage: both checks could not run, and the protected roots check refused, all with `extensions.refStorage in <ws>/.git/config is "files://<project>/.git", which sends the clone's refs to another path`. No project ref changed.
  - gitdir file: both checks and the protected roots check gave `<ws>/.git is not a folder, the workspace is a worktree or a submodule, not a clone`.
  - missing .git: all three gave `<ws>/.git is missing, the clone has no repository of its own`. The protected roots check did not walk up, and its reason does not name the project's file.
  - core.worktree, both variants: `diff_policy` reported `added clone-only.txt` and did not name the project's `untracked.txt` or `later.txt`. `changed_line_coverage` read the clone's `src/app/Calc.kt` (`1 of 1 changed lines covered, 100%`). The protected roots check passed the clean clone with `GIT_WORK_TREE` at the clone and refused the clone's edit of `.agents/hooks.json`. The project's file listing with content hashes, its `.git/index` hash and `git status --porcelain` were unchanged.
  - Pins: the core checks started 10 git processes and the protected roots check 6. In every one, `GIT_DIR` and `GIT_COMMON_DIR` were the clone's real `.git` and `GIT_WORK_TREE` was the clone, even though the caller passed the project's paths for all three, and `GIT_NO_LAZY_FETCH=1` was still set although the caller passed `0`.
  - Hooks mode, and an `AttemptContext` built without the new field (`cloneWorkspace` is false): neither runner ran the layout check or added the pins, and a nested workspace still walked up to its repository as before.
  Test runs: `:spec-core:test --tests "loop.core.verify.*"` ran 226 tests with 0 failures, and `:plugins:runner-acp:test --tests "loop.runner.acp.ProtectedRootsCheckTest"` ran 19 with 0 failures. `ktlintCheck` passes. No protected path was touched.

2026-11-27 03:12Z loop: Loop opened pull request #110 for this issue: https://github.com/soulesidibe/Loop/pull/110

2026-11-27 03:21Z loop: Loop did not merge pull request #110 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements the clone rule in both runners, adds the defaulted AttemptContext field, and updates the docs, with no protected paths touched.
  - impact: medium, It changes the git environment of every check in clone mode and adds a field to the plugin API, but hooks mode keeps its behavior through defaults.
  - verification: strong, New tests prove each hostile recipe live and then guarded, check the pins in every process, cover hooks mode and the default context, and leave the project unchanged; the issue's test runs passed.
  - rollback_cost: two_way, The change only adds read-side checks, pins and a defaulted field, writes no persistent state, and a revert undoes it.
  - error_detection: loud, A layout refusal runs no git and surfaces as a could-not-run result or a protected_workspace_root refusal naming the path; only the copied check could drift from the spec-core original.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 5 of 13 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-27 03:32Z soulesidibe: Sent back by soulesidibe: Merge risk review of PR #110 (Sonnet 5.5 full diff, Fable 5.2 recheck with real git). One medium, so it goes back with every finding, lows included.
  MEDIUM 1. The Gemini merge risk reviewer runs the protected roots check in a clone workspace with the default context. GeminiRiskReviewer.kt:137-145 builds AttemptContext with no baseRef and no cloneWorkspace; AcpRunner.kt:202 then calls ProtectedRootsCheck.refusal(ctx.workspace, null, clone = false). The workspace comes from AutoMergeGate.kt:284 (RiskReviewRequest(workspace, ...)), the issue's clone. RiskReviewRequest (plugin-api RiskReviewer.kt:16) carries neither the project base commit nor the mode. So in a clone this check gets no layout check, no pins, walks up with findGitRoot, and resolves its base from main/master/origin/HEAD of the clone, refs the agent owns. Reproduced: agent commits X adding .agents/hooks.json, commit Y removes it from the tree but leaves it on disk, .agents/ in .git/info/exclude, git branch -f main X. Reviewer call refusal(ws, null, clone=false) = null (passes); refusal(ws, null, clone=true) = null too (the base must be a project commit); the orchestrator call refusal(ws, base, clone=true) refuses with protected_workspace_root. The docs paragraph you added (docs/workspaces.md around line 141, and the threat model line) and criterion 5 ("every process ProtectedRootsCheck.runGit starts" in a clone) say the opposite of what the reviewer path does. Fix: give RiskReviewRequest the project base commit and the clone flag (AutoMergeGate passes them from what the orchestrator already has), and have GeminiRiskReviewer.context set baseRef and cloneWorkspace. This overrides the brief's "risk reviewers keep the default" for GeminiRiskReviewer; Codex keeps its default if it runs no protected roots check, say so in docs. Add a test that drives the reviewer path in a clone with the recipe above and shows the refusal. Keep the docs accurate to the code.
  LOWS, fix with it:
  2. Test gap: no test shows Orchestrator.kt:3068 sets AttemptContext.cloneWorkspace for a clone, or that AcpRunner.kt:202 passes it to refusal; same for the CLI wiring of CoreCheckRunner(clones=...) in RunCommand.kt:344 and ReplayCommand.kt:282. Dropping a wire fails open with every test green. Add wiring tests.
  3. CloneLayoutCopy.kt and clonePins are hand copies of spec-core's CloneLayout and pins with no parity test. Add one test that runs both over the same hostile layouts and expects the same refusals.
  4. CloneScope.covers (CloneLayout.kt:23-28, return real(dir) != real(project)) turns the clone rule off when the workspace folder is a symbolic link to the project, so Git.open skips the layout check and runs unpinned in the project. Reproduced: covers=false, diff_policy passed with 0 changed paths while the clone held an edited scripts/run.sh. Decide the mode from the unresolved path's parent and refuse when a covered name resolves to the project. Test it for CoreChecks and WorkspaceGit.
  5. ProtectedRootsCheck resolveBaseCommit (around 465-486) compares against merge-base <base> HEAD: with HEAD moved to an older ancestor A of the pinned base, an older operator committed .agents file passes. Compare against the pinned base itself when one is given, or explain in docs why merge-base is right.

2026-11-27 03:53Z agent fd8e0ab0: bl-254 send-back of pull request #110, fixed. The proofs come from the PROOF lines of the tests, with real git and no user or system config.
  1. Medium, the Gemini reviewer path. `RiskReviewRequest` now carries `baseRef` and `cloneWorkspace`, both defaulted. `AutoMergeGate` passes the base commit of the wait and the workflow's clone mode, and `GeminiRiskReviewer` sets both in its `AttemptContext`. In clone mode with no base, `ProtectedRootsCheck` refuses: `protected_workspace_root: no project base commit was given for the clone workspace, and a clone never takes its base from its own refs`. I replayed the review recipe: commit X adds `.agents/mcp_config.json`, commit Y removes it from the tree but keeps it on disk under `.git/info/exclude`, then `git branch -f main X`.
    - Live, the reviewer's call before the fix (no base, hooks mode): `null`, so the check passed.
    - Through the reviewer, with a request built without the base or the mode: the review answered, which is today's behavior.
    - Through the reviewer, with the project's base and clone mode: `agy ended in error: protected_workspace_root: added or modified workspace customization file .agents/mcp_config.json`.
    - The Codex and Claude Code reviewers keep the defaults. Their runners start no git of their own in the Workspace. The docs say so.
  2. Low, wiring tests. `BaseFetchOrchestratorTest` shows the orchestrator sets `cloneWorkspace` for a clone and not in hooks mode. `AcpGuardsRunnerTest` shows the runner passes it to the check. `CoreCheckRunnersTest` shows `loop run` and `loop replay` get the workflow's clone scope. `MergesTest` shows the gate's request carries the base and the mode. Each test failed before its wire existed.
  3. Low, parity. `CloneLayoutContract`, in the plugin API's test fixtures, runs the same hostile layouts and pins over spec-core's `CloneLayout` and the ACP copy, and expects the same reasons (`CloneLayoutParityTest`, `CloneLayoutCopyParityTest`, 16 cases).
  4. Low, a Workspace linked to the project. `CloneScope.covers` now decides on the unresolved path: a folder right under `workspace.root` whose own name is not the project. A covered Workspace that resolves to the project is refused with `the workspace <ws> resolves to the project folder <project>`. Before the fix, `diff_policy` passed with 0 changed paths while the clone held an edited protected file. Now `diff_policy` and `changed_line_coverage` could not run with that reason, and `WorkspaceGit` refuses too.
  5. Low, merge base. I kept `merge-base <base> HEAD` and documented why. Loop does not move the branch to the base before the first turn. Comparing with the base itself would refuse a branch that is behind for a file only the operator changed. The merge base is an ancestor of the project's commit, so moving `HEAD` back gives at most an older version the operator committed.
  Test runs: spec-core `loop.core.verify.*` plus `BaseFetchOrchestratorTest` and `MergesTest`, 279 tests, 0 failures. runner-acp `ProtectedRootsCheckTest` 20, `GeminiRiskReviewerTest` 18, `AcpGuardsRunnerTest` 6, `CloneLayoutCopyParityTest` 16, 0 failures. `CoreCheckRunnersTest` passes and `ktlintCheck` passes. No protected path was touched.

2026-11-27 03:59Z loop: Loop updated pull request #110 for this issue: https://github.com/soulesidibe/Loop/pull/110

2026-11-27 04:04Z loop: Loop did not merge pull request #110 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff applies the bl-253 clone rule to both runners, adds the defaulted AttemptContext field, and updates the docs, with no protected path touched. The Gemini reviewer plumbing goes beyond the brief, but a prior review asked for it.
  - impact: medium (floor medium: the diff changes 634 lines outside tests and docs, over half the limit of 1000), It changes the git environment and refusal behavior of every core check and protected roots check in clone mode and adds two defaulted fields to the plugin API. 634 lines of non-test code change, and hooks mode keeps its behavior through the defaults.
  - verification: strong, Each hostile layout is proven live and then guarded with real git. Wiring tests cover the orchestrator, the runner, the CLI and the merge gate. A shared parity contract runs over spec-core's layout check and the ACP copy, and the hooks-mode and default-context cases are tested. The diff was cut, so
  - rollback_cost: two_way, The change adds read-side checks, environment pins and defaulted fields, and writes no persistent state, so a revert undoes it.
  - error_detection: loud, A layout refusal runs no git and shows up as a could-not-run result or a protected_workspace_root refusal naming the path. A clone with no base also refuses. One risk is that the ACP copy drifts from the spec-core original, which the parity test guards against. Another is a wire dropped from a defau
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 19 of 29 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
