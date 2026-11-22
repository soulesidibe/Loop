---
id: bl-173
title: "The merge phase of a delivery holds no agent slot"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

A delivery whose CI turned green still needs `SlotPlan.mergeCheckFits` (no running merge check and a free agent slot) before its merge phase runs (`Orchestrator.kt`, around `merges.advance`). On 2026-11-21 bl-157 waited 10 minutes and bl-149 16 minutes that way behind another delivery's build. Under bl-172's queue it is worse: the head could wait up to an attempt's turn timeout for an agent slot while holding the queue.

- Phase `merge` (the push, the head wait, the base read, the issue read, the auto merge gate, the merge) no longer checks `mergeCheckFits` and holds no agent slot. Phase `check` keeps both.
- The machine slot of m3-16 is still claimed for a merge phase that runs the merge risk reviewer; one that runs no reviewer claims none.
- `SlotUse.mergeChecks` counts only running phase `check` work.
- Update the m2-26 "Slot" and "Slot plan (bl-86)" paragraphs of `docs/spec-decisions.md`.

Area: `Orchestrator.kt` (grep, never read it whole), `spec-core/src/main/kotlin/loop/core/orchestrator/SlotPlan.kt`.

## Acceptance criteria

- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.SlotPlanTest'` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.CiWaitOrchestratorTest'` passes, with a test where every agent slot runs an attempt and a green CI wait still merges at the next tick.
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort.

2026-11-21 22:45Z loop: Loop opened pull request #10 for this issue: https://github.com/soulesidibe/Loop/pull/10
