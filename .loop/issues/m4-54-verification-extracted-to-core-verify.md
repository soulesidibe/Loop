---
id: m4-54
title: "Verification extracted from the orchestrator into loop.core.verify"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-37]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Part of story 138 (PLAN 10.4). Lane A, run on the claude-code runner. Not gating the exit. Split from m4-41 after the Fable 5.2 review: `loop replay --verify` needs to call the verification, and a refactor of the orchestrator does not belong in the replay issue or in `loop.core.replay`.

- **What moves:** `Verification` is a `private inner class` of `Orchestrator` (`spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt`, around line 3581), with three call sites (around lines 2112, 4782 and 4916). An inner class reads orchestrator state, so this is not a plain move: the state it reads becomes explicit parameters or a small interface, and the class moves to `loop.core.verify` where both the orchestrator and `loop replay` (m4-41) can call it.
- **Behavior unchanged:** the verification, the merge check and the auto merge behave as before. No test file is edited; the existing tests are the proof.
- `Orchestrator.kt` (5,327 lines) is edited only to remove the class and call the extracted one at its three call sites. Grep it, never read it whole.
- Out of this issue: the replay command (m4-41), any change to what verification does.

## Acceptance criteria

- [ ] `Verification` lives in `loop.core.verify`, and the orchestrator calls it at its three call sites with no other change (judgment).
- [ ] The existing verification, merge check and auto merge tests pass unchanged, with no test file edited (judgment).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.verify.*'` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.*'` passes.
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-41 after the Fable 5.2 review.
