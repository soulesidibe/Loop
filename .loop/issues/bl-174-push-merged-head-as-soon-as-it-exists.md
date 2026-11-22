---
id: bl-174
title: "Push the merged head as soon as the merge commit exists, so CI runs during the merge check"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

A round costs the local merge check (2 to 29 minutes) plus the CI wait (about 13 minutes), one after the other. Push the merge commit M right after it is made, so GitHub Actions run while the local checks run, and a round costs the longer of the two. See the spec's "CI starts with the merge check" decision.

- After step 4 of phase `check` makes M, store it in the wait as `candidate_head`, then push it unforced to `loop/<key>` (`branch_pushed`). A push that fails is not fatal: the check goes on and `startCi` pushes as today.
- Nothing else in phase `check` changes. The tick still leaves a running check's wait alone (`Merges.kt`), and CI polling starts in phase `ci` once the local check passed, as today. `startCi`'s push is then a no op with no second `branch_pushed`.
- The local result decides first: a conflict or a failing recheck is handled as today. No CI verdict is applied while the fingerprint is still V.
- The lookup in `runMergeCheck` and `settleInactive` accept a pull request merged at `candidate_head` during phase `check` as a human merge at the stored head (the move plus a `human_touch` with `touch` = `merge`), not as a merge at another commit.
- `candidate_head` is a merge key: `again()`, `refused()` and a new approval drop it.
- Update the m2-26 phase `check` steps and the m3-15 "Order" paragraph of `docs/spec-decisions.md`, and note in bl-91 that the early push cancels the run of V through `build.yml`'s concurrency group.

Area: `spec-core/src/main/kotlin/loop/core/merge/Merges.kt`, `spec-core/src/main/kotlin/loop/core/waiting/Wait.kt`, `WaitKeys`.

## Acceptance criteria

- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.MergeCheckOrchestratorTest'` passes, with tests for: M pushed before the checks run; a failing recheck after the early push leaves the wait refuted as today with the branch at M; a human merge at `candidate_head` during phase `check` is delivered with a `human_touch`.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.CiWaitOrchestratorTest'` passes, with a test that phase `ci` records no second `branch_pushed` after the early push.
- [ ] A restart between the early push and the end of the check resumes the check on M with no second merge commit (judgment: name the test).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort.

2026-11-21 22:15Z agent 4943052c: bl-174 done. The merge commit M is stored as candidate_head and pushed unforced right after step 4 of phase check, before the checks run. A second key, candidate_pushed, records that the push succeeded, so the push in startCi or the merge phase records no second branch_pushed, and a failed early push is still recorded later as before. For a human: issue files are out of reach in this workspace, so please add this note to bl-91. The early push cancels the run of V through build.yml's concurrency group, and a new round's M cancels the stale M, which removes most of the double CI run. The same note is in the m3-15 Order paragraph of docs/spec-decisions.md.

2026-11-21 23:34Z loop: Loop opened pull request #12 for this issue: https://github.com/soulesidibe/Loop/pull/12

2026-11-22 00:31Z loop: Loop did not merge pull request #12 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.60.
  - alignment: aligned, The diff stores candidate_head, pushes M early and unforced, handles a human merge at it, drops the keys on again/refused/new approval, and updates the docs and tests as the issue asks.
  - impact: medium, It changes the push timing and the lookup logic of the core merge delivery flow, and it now publishes an unverified merge commit to the remote branch and the pull request before the checks pass.
  - verification: strong, New tests cover the push before the checks, a failing recheck after the early push, a failed early push, a restart that resumes on M, a human merge at candidate_head (both lookup paths), no second branch_pushed in phase ci, and key dropping; existing tests were updated to match.
  - rollback_cost: costly, A revert undoes the code, but waits already stored with candidate_head keys and branches already pushed at M remain, and the unknown keys are simply ignored by an older binary.
  - error_detection: partial, A failed early push or a failed wait write is deliberately non-fatal and only logged as a WARN, and a later push repeats it, so a failure stays quiet and does not stop the check.
  Once approved, Loop runs the merge check again and merges.

2026-11-22 03:12Z loop: Loop did not merge pull request #12: main at 973d9691be9d1b9567c6faea6d1bcbea38580913 was merged into loop/bl-174, but the checks failed on the result (a check failed). The merge commit stays on the branch. The next attempt fixes the result and submits it again.

2026-11-22 04:33Z loop: Loop updated pull request #12 for this issue: https://github.com/soulesidibe/Loop/pull/12

2026-11-22 05:27Z loop: Loop did not merge pull request #12 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.60.
  - alignment: aligned, The diff stores candidate_head, pushes M early and unforced, handles a human merge at it, drops the keys on again/refused/new approval, and updates the spec docs, as the issue asks.
  - impact: medium, It changes push timing and lookup logic in the core merge delivery flow and publishes an unverified merge commit to the remote branch before the checks pass.
  - verification: strong, New tests cover the push before the checks, a failed early push, a failing recheck, a restart resuming on M, a human merge at candidate_head via both lookup paths, no second branch_pushed in phase ci, and key dropping.
  - rollback_cost: costly, A revert removes the code, but stored waits with candidate keys and branches already pushed at M stay behind, though an older binary ignores the unknown keys.
  - error_detection: partial, A failed early push or wait write is deliberately non-fatal and only logged as a WARN, and a later push repeats it, so a failure stays quiet.
  Once approved, Loop runs the merge check again and merges.
