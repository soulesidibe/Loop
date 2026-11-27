---
id: bl-254
title: "Clone workspaces: the core checks and the protected roots check must read the clone, not the project"
state: todo
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
