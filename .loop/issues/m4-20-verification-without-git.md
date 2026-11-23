---
id: m4-20
title: "Verification and fingerprint on a workspace without git, and an empty workspace"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-19]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Implementation decision of the spec (kinds), from finding M7 of the m3-29 review: an empty research workspace has no git, while verification, `diff_policy`, the restore and the pinned base assume a work tree. This lands before any non code kind. Lane A, runs on the claude-code runner.

What is known:

- The fingerprint already handles a folder that is not a git work tree: `WorkspaceFingerprint.kt` gives `tree:<hash>` of the folder (around lines 45 to 64). Its `uncommitted` and `restore` throw "is not the top of a git work tree" outside git (around lines 74 to 103), and the orchestrator calls both when the kind delivers a pull request (`Orchestrator.kt`, around lines 3869, 3945 and 3967).
- `diff_policy` outside a git work tree sets `startError` (`verify/CoreChecks.kt`, around lines 14 to 20 and 240 to 246), so the verdict is `unverified`. Correct for `code`, but a kind with no git must never declare it by mistake and park every issue.
- `pinnedBase` reads `workflow.config.kinds?.code?.delivery?.pullRequest?.base` (`Orchestrator.kt`, around line 3907), the code kind whatever the issue's kind (bl-67 notes the same). It must read the issue's own kind (m4-18).
- `ChangedLineCoverage.kt`, `Holdouts.kt` and `VerifyFindings.kt` also call git. Each is checked: on a workspace without git it either works on the `tree:` fingerprint or says it does not apply, never throws.

Decisions for the issue:

- A kind declares its workspace as `workspace: git` (default, today's behavior) or `workspace: plain` (confirmed by soule on 2026-11-18). Preflight refuses `diff_policy`, the git deny guards and `delivery.pull_request` on a `plain` kind, so the mistake is caught before dispatch, not as a park.
- **The declaration decides, never detection.** Population is by the workflow's global hooks (`WORKFLOW.md` lines 14 to 16, scripts under protected `scripts/`), not per kind, so a `plain` kind in a project with worktree hooks can still get a git work tree. The fingerprint and the skipped git calls follow the declaration: a `plain` kind is fingerprinted `tree:` even inside a work tree, and a `git` kind in a folder that is not a work tree stays `unverified` as today.
- **`populate: empty`,** moved here from m4-23 so a plain workspace can exist outside tests: a kind that declares it gets a folder Loop creates, and Loop runs none of the workspace hooks for it, `after_create`, `before_run`, `after_run` and `before_remove` alone. The global hooks assume a worktree, and `before_remove` would fail on a plain folder. `LOOP_KIND` (m4-18) stays available for workflows that prefer hooks. Preflight refuses `populate: empty` on a `git` kind.
- On a `plain` kind, the verify path skips `uncommitted` and `restore`, and the verdict, the evidence and the waits carry the `tree:` fingerprint. A verified result's fingerprint is compared again before delivery, as for git.
- The tests use workspaces with and without git, as the spec's testing decisions ask.
- Out of this issue: the content and research deliveries (m4-22, m4-23).

## Acceptance criteria

- [ ] A `command` check verifies a `plain` workspace, and the verdict and the evidence carry a `tree:` fingerprint (CLI test on a temporary project whose workspace is not a git repository).
- [ ] A change to a file after verification changes the `tree:` fingerprint, and delivery refuses the stale verdict as it does for git (test).
- [ ] Preflight refuses `diff_policy`, the git deny guards and `delivery.pull_request` on a `plain` kind, one test each.
- [ ] `pinnedBase` reads the issue's own kind, and a `plain` kind fetches no base (test).
- [ ] A `plain` kind inside a git work tree is fingerprinted `tree:`, and a `git` kind in a folder that is not a work tree stays `unverified` (one test each).
- [ ] A `populate: empty` kind gets an empty folder, runs none of `after_create`, `before_run`, `after_run` and `before_remove`, and preflight refuses it on a `git` kind (tests).
- [ ] On a `plain` workspace, `ChangedLineCoverage`, `Holdouts` and the verify findings neither throw nor call `uncommitted` or `restore` (tests).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.verify.PlainWorkspaceVerifyTest'` passes, a new test class of the cases above.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.verify.WorkspaceGitTest'` passes unchanged, a `git` kind behaving as before.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` describes `workspace: plain`, the declaration rule, `populate: empty` and what verification does without git (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
