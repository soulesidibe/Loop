---
id: bl-174
title: "Push the merged head as soon as the merge commit exists, so CI runs during the merge check"
state: todo
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
