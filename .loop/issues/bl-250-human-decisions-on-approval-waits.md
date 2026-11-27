---
id: bl-250
title: "Loop's own writes never overwrite a human decision on an approval wait"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: []
area: [spec-core/src/main/kotlin/loop/core/orchestrator, spec-core/src/main/kotlin/loop/core/waiting, spec-core/src/main/kotlin/loop/core/delivery/PullRequestDelivery.kt, spec-core/src/main/kotlin/loop/core/channel/HumanNotices.kt, plugin-api/src/main/kotlin/loop/api/ProjectState.kt, store/src/main/kotlin/loop/store/SqliteEventStore.kt, docs/spec-decisions.md]
---

## Parent

None. Backlog, not scheduled in a milestone. It takes bl-222 finding 2 (Loop's own saves of an approval wait overwrite a `loop send-back`), cured by saves of the wait row that are conditional on what the store holds now. The medium of bl-42 (no reject path for a waiting issue) uses the same conditional primitive and is filed as bl-252, blocked by this issue. bl-222 finding 1 is not here: bl-221 fixed it (see the triage comment).

## What

**Category:** bug

**Summary:** Loop's own writes of an approval wait never overwrite a `loop send-back` committed since Loop read the wait, and a sent back wait is never delivered or merged.

**Current behavior:**

- Each tick reads the waits once, then works from that copy in memory. A `loop send-back` commits in one store transaction and prints `Sent back`. But several of Loop's writes later in the same tick save the wait from its copy, unconditionally, through `Waits.save` (an upsert) or `Waits.end` (a delete):
  - The delivery of an approved wait (`deliverApproved`, `deliverPullRequest`): the tracker move or the pull request delivery runs, then the wait ends, or, under `pull_request.merge`, `startMergeWait` replaces the row with a `Waiting(merge)` that keeps the approval. A send back committed during the delivery is erased, and with `merge` the issue goes on to merge.
  - `clearApproval` after a permanent delivery failure, through the `delivery_failed` notice save (`HumanNotices.waiting`): it writes `wait.unapproved()` of the copy, which has no `sent_back_*` keys, so the send back is lost and the issue waits for a new approval.
  - The retry path of a pull request delivery that stores the commented URL (`Wait.Approval.commented`): it writes the copy back, still approved, so the next tick delivers it.
- bl-224 already made the reaction path safe with `WaitingIssues.endUndecidedApproval` and `Waits.endUndecided`, one transaction that deletes only an undecided approval wait. The delivery and notice paths above were left out.

**Desired behavior:**

1. **Conditional saves of an approval wait.** The store gains one primitive that replaces or removes the approval wait of an issue only while the stored row is still the wait Loop read: same reason, same `since` and attempt id, and the same decision (`approved_at` and `sent_back_at` unchanged). It runs in one write transaction in `SqliteEventStore`; the `WaitingIssues` default reads then writes, like `approve` and `sendBack`. It returns the row as the store holds it, so the caller knows whether it won. `endUndecidedApproval` may be folded into it or kept.
2. **Every write Loop makes to an existing approval wait goes through it:** the end after a delivery, the merge wait that replaces an approved wait, `clearApproval` and its notice save, and the save of the commented URL. When the write loses because a human decided since the read, Loop keeps the stored row, adopts it in memory as `Waits.endUndecided` does, and logs the outcome at info. The first save of a new approval wait (from a verify wait, which neither `loop approve` nor `loop send-back` accepts) may stay a plain save; say why in a comment.
3. **A re-read before every external step of a delivery.** Right before the tracker move of a plain delivery, right before the pull request delivery starts, and right before the handoff move inside `PullRequestDelivery`, through a check passed with the `Handoff`, Loop checks through the store that the wait is still approved and not sent back. A wait sent back since the read is not delivered: nothing is moved or pushed (or, at the handoff check, nothing is moved), and the issue dispatches with the reason as after any send back (m4-32, bl-221).
4. **A send back that lands during a pull request delivery wins.** If the send back commits after the push or the comment but before the merge wait is written, the conditional write fails, no merge wait starts, nothing merges, the pull request stays open for the next delivery to update (as m4-32 says), and the issue dispatches with the reason.
5. **A send back that lands after the final move is too late.** When the tracker move of a delivery already happened, the delivery stands: Loop ends the wait anyway, the one unconditional write this issue keeps, and logs at warn that the issue was sent back after its delivery moved it to `<to>`, so the send back did not apply. This window is the time of one tracker call; document it.

**Key interfaces:**

- `WaitingIssues` (the store contract) and `SqliteEventStore`: the new conditional replace or remove, next to `approve`, `sendBack` and `endUndecidedApproval`.
- `Waits` (`save`, `end`, `endUndecided`): a method over the new primitive that keeps memory in step with the store.
- The orchestrator's approval sync and delivery (`syncApprovals`, `deliverApproved`, `deliverPullRequest`, `startMergeWait`, `clearApproval`, `storeWait`). `Orchestrator.kt` is very large: grep it, never read it whole.
- `PullRequestDelivery.deliver` and its `Handoff`: without `merge`, one call pushes, opens, comments and moves, so the re-read before the move is a check the `Handoff` carries, run right before `move`.
- `HumanNotices.waiting`, whose `save` callback is where `clearApproval` writes.
- Deterministic tests use a hook in the fakes, never timing: the reaction tests of bl-224 already commit a `loop approve` or a `loop send-back` from inside a fake GitHub read (`duringCommentRead` in `ChangesRequestedReactionTest`). Do the same from the fake tracker's move and the fake pull request delivery steps, including one after the push and before the handoff move. bl-237 finding 8 notes that `FakeWaitingIssues` is not atomic, so prove the transaction in a store test too.

## Acceptance criteria

- [ ] A store test in `store` for the conditional primitive on `SqliteEventStore`: an unchanged row is replaced and removed; a row sent back or approved since the read is kept as it is and returned; a missing row writes nothing; other waits are unchanged.
- [ ] An orchestrator test, plain delivery: a hook commits `loop send-back` after the tick read the approved wait and before the tracker move. The issue is not moved, no `issue_moved` is recorded, the row stays sent back with its reason, and the next dispatch renders the reason with retry kind `sent_back`.
- [ ] An orchestrator test, pull request delivery under `merge`: a hook in the fake pull request delivery commits `loop send-back` after the push and before the merge wait is written. No `Waiting(merge)` is stored, no merge is called on any later tick, the pull request stays open, the row stays sent back, and the issue dispatches with the reason.
- [ ] An orchestrator test, permanent delivery failure: a hook commits `loop send-back` during the delivery that then fails for good. `clearApproval` does not overwrite the row: it stays sent back with its reason and the issue dispatches with it.
- [ ] An orchestrator test, retry with a commented URL: a send back committed during the delivery is kept, and the next tick delivers nothing.
- [ ] An orchestrator test, pull request delivery without `merge`: a test hook after the push and before the handoff move commits `loop send-back`. The issue is not moved, no `issue_moved` is recorded, and the row stays sent back with its reason.
- [ ] An orchestrator test: a send back committed inside the tracker move, after the move applied, leaves the delivery standing, ends the wait and logs the warn line naming the late send back.
- [ ] `./gradlew --no-daemon :store:test` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests "loop.core.orchestrator.*"` passes.
- [ ] `./gradlew --no-daemon build` passes.
- [ ] `docs/spec-decisions.md` states, under delivery policy and approval, the conditional saves; the `loop send-back` section (m4-32) says a send back committed during a delivery wins unless the final move already happened.

## Out of scope

- The reject rule on a human move of an approval wait and the `loop approve` refusal of a moved issue (bl-252).
- bl-222 finding 3 (the `sent_back` block in `WORKFLOW.md`) and findings 4 to 7; bl-42's low items (the second result in one drain, delivery atomicity after a crash, the failed store write that leaves `verify`, the `claimsAnyId` case, the per tick log of a malformed file); the human touch accounting of bl-64.
- Moving an issue back after a late send back.
- Label changes on a GitHub issue that do not change its state as Loop reads it.
- Protected paths, which the diff must not touch: `scripts/`, `.github/`, `WORKFLOW.md`, `*.gradle.kts`, `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew`, `gradlew.bat`, `.loop/specs/`, `docs/adr/`, `CONTEXT.md`. No residual human step is expected.

## Comments

2026-11-26 triage:

> *This was generated by AI during triage.*

Filed from bl-222 finding 2 and the medium of bl-42, re-located on main at 785c1ab.

- bl-222 finding 1 is fixed and dropped. bl-221 folded it in: `ApproveCommand.checkApproval` refuses a sent back wait with exit 4 before the store write, and checks the row the store's approve returns again, so a send back committed in between also exits 4. The test `bl-221 loop approve after loop send-back is refused with exit 4, records nothing and changes nothing` in `SendBackCommandTest` covers it, and `docs/spec-decisions.md` lists the row in the exit table.
- bl-222 finding 2 still holds for the delivery and notice paths. bl-224 fixed only the reaction path (`endUndecidedApproval`, `reaction_skipped` with `human_decided`). `deliverApproved`, `deliverPullRequest`, `startMergeWait`, `clearApproval` and `storeWait` still write the tick's copy through `Waits.save` or `Waits.end`. The notice save at the start of an approval wait is not exposed, since both commands refuse a verify wait. The merge waits' own saves (`Merges`) are not exposed either, since `loop send-back` exits 7 and `loop approve` exits 4 on a `Waiting(merge)`.
- bl-42's medium still holds: `Wait.Approval` stores no state and `syncApprovals` ends a wait only when its issue is gone or out of the active states. m4-32 added the send back but no reject on a move; m4-31 built the rule for the ask wait only, which bl-252 reuses.
- First filed as one issue with the reject rule, since both use the conditional primitive and the same interleaving test hooks. Split after review: the reject rule and the `loop approve` refusal are bl-252, blocked by this issue.

Revised after a Fable 5.2 review of the brief on 2026-11-26.

2026-11-27 00:32Z loop: Loop opened pull request #106 for this issue: https://github.com/soulesidibe/Loop/pull/106

2026-11-27 00:39Z loop: Loop did not merge pull request #106 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff implements the conditional replace primitive, the re-reads before each external step, the send back winning during a delivery, and the late-send-back warn path. It also touches Verification.kt, which the issue does not mention, but only to cover the new `Withdrawn` outcome.
  - impact: medium, It changes the delivery and approval-wait write paths in the orchestrator, adds a store contract method, and adds a new `DeliveryOutcome` variant, but the change is limited to the approval-wait flow.
  - verification: strong, Each interleaving in the acceptance criteria has a deterministic hook test, and there are separate store, Waits, HumanNotices and PullRequestDelivery tests. I could not see whether the build and gradle tests pass.
  - rollback_cost: two_way, It adds no schema change or persisted format, so a revert restores the old behavior.
  - error_detection: partial, Lost writes are logged at info or warn, but a failed conditional write is logged and then treated as written, and a failed re-read only skips the delivery with a warn log.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 2 of 14 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
