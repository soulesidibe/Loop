---
id: m4-41
title: "loop replay runs verification again on the recorded fingerprint"
state: done
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

2026-11-26 05:13Z soule: sent back before merge, after the merge risk reviews of pull request #83 (Sonnet 5.5 then Fable 5.2, both medium). Fix these on the same branch, then submit again:
  - Medium, a signal skips all cleanup and leaves the check running. `ReplayCommand.replayVerification` cleans only through `site.use { }`, a finally, and no shutdown hook is installed (only `RunCommand.untilSignal` has one), so on SIGINT or SIGTERM the JVM halts without it. The check runs through `SessionProcess` (`perl ... setsid`), in its own session, so Ctrl-C never reaches it and its timeout lived in the JVM: a `./gradlew --no-daemon build` keeps running with no limit, the temp copy stays, and a detached `loop-replay-*/workspace` worktree stays registered in the operator's `.git/worktrees`. Fix: run the placement and the checks under a cancelable job with a shutdown hook, as `untilSignal` does: a `Job` and a latch, a hook thread that cancels the job and waits for the latch (about 10 s), `runBlocking(job)`, the latch counted down in a finally after `site.use` closed, the hook removed there. Register it before `ReplaySites().place(...)` so a signal during the copy is covered. Record no `replay_verdict` on a cancel. Test: cancel during a `sleep` check, assert the site folder and its worktree registration are gone and no event is recorded.
  - Low, a check with `resource:` runs with no lock and no `LOOP_RESOURCE_<KEY>` (`ReplayCommand.checkRunner` calls `CoreCheckRunner` directly). Do not run it: return a `CheckResult` with `startError` "a replay holds no <kind> resource, the check did not run", so the verdict is `unverified`. Update the KDoc and spec-decisions, add one test.
  - Low, absolute symlinks into the workspace are copied as links (`ReplaySites.copyTree`, `NOFOLLOW_LINKS`), so a check can write through them into the kept workspace. While copying, collect links whose target is absolute and inside the source; refuse the copy with that reason (falling to the rebuild when the fingerprint is clean) or print a header line naming them.
  - Low, an unpinned diff base falls back to the moved `guards.base` with no note: `run` drops the in memory `diff_base_unpinned` event. Carry a flag on `ReplayedVerdict` and print "No base was recorded for this verification, diff_policy read guards.base as it is now."
  - Low, cleanup failures are silent: `removeSite` ignores the exit code of `git worktree remove`. Report what could not be removed on stderr with `git worktree prune` as the remedy. Create the temp parent after `workspace.toRealPath()`, so a throw there leaves no empty folder.
  - Low, a failing check shows only its exit code. After `verify()`, print the redacted `verification.findings` under the failed check, never for a hidden check.
  - Low, the header can say "The workspace changed since the verdict" when the workspace matched but the copy failed and the placement fell to the rebuild. Carry the placement's `state` on `ReplaySite.Worktree` and print it.
  - Low, a rebuilt worktree holds the commit only. Print one header line: "A rebuilt worktree holds the commit only: no ignored file and no workspace hook setup."
  - Optional: give a replay its own `LOOP_RUN_ID` (for example `replay`) so a check can tell it is a replay.

2026-11-26 05:25Z agent f19affc9: Fixed the findings of the merge risk reviews of pull request #83 on the same branch, after merging main at fa0b30b42c49.
  - Medium, signals: the placement and the checks now run under a cancelable job with a shutdown hook, registered before the placement. A signal stops the check's process group, removes the site and its worktree registration, and records no replay_verdict. A launcher test sends SIGTERM during a sleep check and checks all of this. It fails without the hook.
  - A resource check does not run: "a replay holds no <kind> resource, the check did not run", so the verdict is unverified.
  - A workspace with absolute links into itself is not copied. The links are named, and a clean git fingerprint falls back to the rebuild.
  - When no base was recorded, the output says diff_policy read guards.base as it is now.
  - When cleanup fails, stderr names what was left and gives git worktree prune as the remedy. The temp folder is now created after the workspace path is resolved.
  - The findings of a failed check print under it, never for a hidden check.
  - The header says why a rebuilt worktree was used, and adds that a rebuilt worktree holds the commit only.
  - LOOP_RUN_ID is replay.
  Focused tests: 37 core and 16 replay CLI tests pass, and ktlintCheck passes.

2026-11-26 05:34Z loop: Loop updated pull request #83 for this issue: https://github.com/soulesidibe/Loop/pull/83

2026-11-26 05:37Z loop: Loop did not merge pull request #83 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.47.
  - alignment: aligned, The diff adds --verify in a new loop.core.replay package with the three placements, exit 4 naming both fingerprints and a replay_verdict event, and it leaves Orchestrator.kt alone.
  - impact: medium (floor medium: the diff changes 869 lines outside tests and docs, over half the limit of 1000), It adds a CLI mode that runs check commands and creates and removes git worktrees and temp copies, and it adds a new event kind to the registry, over 800 non-test lines.
  - verification: strong, CLI tests cover the copy, worktree, changed, tree-gone, signal, resource and no-verdict cases and compare the store, issue and workspace before and after, and core unit tests cover sites, verdict and replay.
  - rollback_cost: costly, A revert is clean in code, but replay_verdict events already written to the machine store stay behind, and older code may not know that event kind.
  - error_detection: loud, Placement refusals, store errors and signals exit with a named reason, and cleanup failures now go to stderr with git worktree prune as the remedy, though the shutdown hook waits at most 10 seconds.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 5 of 12 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
