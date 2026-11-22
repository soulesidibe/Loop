---
id: bl-172
title: "Delivery queue: only the oldest merge wait advances"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: []
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

Today only the merge check is serialized per project, while CI waits and merges of several deliveries run in parallel, so every merge makes the other pending merge checks stale (a new round, at most 3, then a fallback to approval). Make the whole delivery one at a time per project, see the spec's "The queue" decision.

- The head of the queue is the merge wait with the oldest `since`, ties broken by issue id, sorted explicitly (`Waits.current` returns map order). No lock state is stored.
- Only the head advances: its merge check, its CI poll (`Merges.advance`), its merge phase. The other merge waits stay in phase `check` and do nothing, and take no slot.
- `since` is set when the merge wait starts (`Orchestrator.kt`, `wait.merging(..., clock.now())`) and survives `again()`. A refusal or a fallback (`refused()`) ends the merge wait, so a later approval goes to the back.
- At startup, merge waits behind the head found in phase `ci` or `merge` go back to phase `check` at their round, as `again()` does, with an event naming them.
- `loop status` shows each merge wait's place in the queue (`queue=1`, `queue=2`).
- Update the m2-26 "Slot" and "Slot plan (bl-86)" paragraphs of `docs/spec-decisions.md` (the "unspecified order" of bl-82 finding 1 is gone) and the "Pull request delivery" paragraph of `docs/PLAN.md`.

Area: `spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt` (grep the merge wait loop around `merges.advance`, never read it whole), `spec-core/src/main/kotlin/loop/core/merge/Merges.kt`, `spec-core/src/main/kotlin/loop/core/waiting/`, `loop status`. Out of this issue: the merge phase's slot (bl-173), the early push (bl-174).

## Acceptance criteria

- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.MergeCheckOrchestratorTest'` passes, with tests for: two approved deliveries, the second starts its merge check only after the first merged, with one round each; a head that falls back lets the next one start, and its new approval goes behind it; equal `since` ordered by issue id.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.CiWaitOrchestratorTest'` passes, with a test that a merge wait behind the head is never polled.
- [ ] A test covers the startup demotion of a second wait found in phase `ci` (judgment: name the test in the result).
- [ ] `loop status` prints the queue place of each merge wait, with a test (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort.

2026-11-21 20:51Z agent 659803b9: Done on loop/bl-172 in three commits (c0549a0, df743ba, 024b3d8).
  - Queue order: `loop.core.waiting.MergeQueue`, the oldest `since` first, then issue id. The orchestrator and `loop status` both use it.
  - `syncMerges`: only the head moves forward (its merge check, its CI poll, its merge). A wait whose issue left the active states is settled wherever it is in the queue and never holds it. A wait whose check is running, whose attempt is running or whose issue cannot be read holds the queue for that tick.
  - Startup: `requeueBehindHead` puts waits behind the head that are in phase ci or merge back to phase check at their round, using `again()`. Each one records a new `merge_requeued` event (phase, round, head_issue).
  - `loop status` prints `queue=<n>` on each merge wait.
  - Tests: four in MergeCheckOrchestratorTest (one at a time with one round each, a fallback goes behind, ties by issue id, older since first). Two in CiWaitOrchestratorTest: the waits behind the head are never polled, and the startup demotion test `at startup merge waits behind the head found in phase ci or merge go back to phase check at their round, with merge_requeued`. Also the bl-172 tests in RuntimeSnapshotTest and StatusCommandTest.
  - Docs: the m2-26 "Slot" and "Slot plan (bl-86)" paragraphs, the PLAN pull request delivery paragraph, and a new "Delivery queue" glossary entry.
  - The focused tests and ktlintCheck pass. The full build is left to Loop's verification. `mergeCheckFits` is unchanged, since that work belongs to bl-173.

2026-11-21 21:22Z loop: Loop opened pull request #9 for this issue: https://github.com/soulesidibe/Loop/pull/9
