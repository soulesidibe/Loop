---
id: m4-41
title: "loop replay runs verification again on the recorded fingerprint"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-54, m4-40]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 138, the second half of `loop replay` in PLAN 10.4. Lane A, run on the claude-code runner. Not gating the exit. The last issue of the lane A chain. It calls the verification m4-54 extracted to `loop.core.verify`, and edits no orchestrator code.

- **Command:** `loop replay <attempt> --verify [--workflow WORKFLOW.md]`, beside the `--prompt` mode of m4-40.
- **The recorded state:** the `fingerprint` of the attempt's last `verdict` event (`spec-core/src/main/kotlin/loop/core/events/VerifyEvents.kt`), in the form of `WorkspaceFingerprinter` (`spec-core/src/main/kotlin/loop/core/verify/WorkspaceFingerprint.kt`): `git:<HEAD>:<diff>:<untracked>` or `tree:<hash>`.
- **Where it runs:**
  - The kept workspace, when its fingerprint still equals the recorded one. Checks run on a copy, never in place, so the workspace is left as it was.
  - Otherwise, for a `git:` fingerprint with no uncommitted part, a temporary git worktree at `<HEAD>` from the project repository, removed afterwards.
  - Otherwise, the workspace is gone or changed and cannot be rebuilt: the command says so, names the recorded and the current fingerprint, exit 4. Never a verdict on another state.
- **Checks:** the checks of the current `WORKFLOW.md` for the issue's kind, through the verification in `loop.core.verify` (m4-54) and the same check runner the orchestrator uses (`spec-core/src/main/kotlin/loop/core/verify/Checks.kt`).
- **Output:** each check with its result, the new verdict next to the recorded one. Nothing is written to the tracker, the issue or the attempt's records; a `replay_verdict` event records the replay with both fingerprints.
- **Logic** in a new package `loop.core.replay`, which only calls `loop.core.verify`. `Orchestrator.kt` is not edited.

Out of this issue: the extraction of the verification (m4-54), replaying holdouts the attempt never saw, replaying the merge check, and rebuilding a workspace with uncommitted changes.

## Acceptance criteria

- [ ] A kept workspace whose fingerprint matches is verified on a copy, and the new verdict prints next to the recorded one; the workspace is unchanged afterwards (test).
- [ ] A gone workspace with a clean `git:` fingerprint is verified on a temporary worktree at `<HEAD>`, removed afterwards (test).
- [ ] A changed or gone workspace that cannot be rebuilt exits 4 naming both fingerprints, with no verdict (test).
- [ ] A `tree:` fingerprint whose folder is gone exits 4 the same way (test).
- [ ] The replay writes nothing to the tracker or the attempt's records, and records one `replay_verdict` event (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ReplayCommandTest'` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.replay.*'` passes.
- [ ] The logic lives in `loop.core.replay`, it calls the verification of `loop.core.verify`, and `Orchestrator.kt` is not edited (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 22:44Z loop: Loop opened pull request #83 for this issue: https://github.com/soulesidibe/Loop/pull/83

2026-11-25 22:48Z loop: Loop did not merge pull request #83 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.60.
  - alignment: aligned, The diff implements --verify as specified in a new loop.core.replay package, with three placements, exit 4 naming both fingerprints, and a replay_verdict event, and it doesn't edit the orchestrator.
  - impact: medium (floor medium: the diff changes 753 lines outside tests and docs, over half the limit of 1000), It adds a new read-mostly CLI mode and a new event type in a registry, but it runs check commands and creates and removes git worktrees and temp copies, and it touches 753 non-test lines.
  - verification: strong, CLI tests cover the copy, worktree, changed and tree-gone cases, no verdict and mode conflicts, and compare the store, issue and workspace before and after, and unit tests cover sites, verdict and replay.
  - rollback_cost: costly, A revert is clean in code, but replay_verdict events already written to the machine store stay behind, and the old code may not know that event kind.
  - error_detection: partial, Placement failures and store errors exit loudly, but cleanup failures are swallowed with runCatching, which can leave stray worktrees or temp folders, and a failed copy falls back to a rebuild quietly.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 4 of 12 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
