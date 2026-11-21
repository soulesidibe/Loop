---
id: bl-172
title: "Delivery queue: only the oldest merge wait advances"
state: todo
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
