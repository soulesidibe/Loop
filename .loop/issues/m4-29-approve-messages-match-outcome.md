---
id: m4-29
title: "loop approve messages match the outcome"
state: todo
priority: 1
labels: [deferred]
blocked_by: [m4-00, m4-01, m4-47]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 126. Lane C, a CLI text fix with no channel. Area: `cli/src/main/kotlin/loop/cli/ApproveCommand.kt` and `cli/src/test/kotlin/loop/cli/ApproveCommandTest.kt`.

Premise, checked before the first edit: no Gradle edit needed. The fix stays in `cli/` and its tests.

Carries bl-69. With `delivery.pull_request.merge` set, `loop approve` printed "Loop moves it to review on its next tick", while Loop pushed, ran the merge check, merged and moved the issue to `closed`, never to review. The message reads the wait's `to` (`ApproveCommand.kt`, around lines 80 and 120), which stays `review` under merge; `approval_given` in `loop trace` shows `to=review` too.

Carries bl-66, the low findings of the reviews of #71:

- Two messages print `workflow.trackerDir` ("cannot read ... in the tracker at", "no issue ... in the tracker at", around lines 41, 54 and 74), a path that means nothing for the GitHub tracker. `ResumeCommand` uses `trackerLocation(workflow)`; approve uses it too.
- When the tracker read fails, the fallback matches the waiting records on identifier only (around line 73), so `loop approve 1` exits 2 with a misleading message while `loop approve loop-playground-1` works. The fallback matches the issue id or the identifier, as `ResumeCommand` should too if it shares the flaw.
- The bl-65 test covers only the "not waiting" outcome (exit 4) on a github workflow. A test approves a `Waiting(approval)` github issue through to `approval_given`.
- That test rewrites the fixture workflow with string replace calls on `kind: files` and `active_states`. It builds the github workflow from its own fixture instead.
- The dead `?: fail("... is not supported, use files")` branch is removed if it is still there; `ResumeCommand` uses `unsupportedTracker(workflow)`.

What the messages say:

- Under `merge`: Loop delivers the pull request, runs the merge check and merges, then moves the issue to `delivery.on_delivered.move_to`. The "already approved" message says the same. `approval_given` records the state the issue ends in.
- Without `merge`: unchanged, the wait's `to`.

Out of this issue: the namespaced ids of m4-12 (this issue keeps the identifier forms that exist when it runs), `loop send-back` (m4-32).

## Acceptance criteria

- [ ] Premise checked: no Gradle edit needed (judgment).
- [ ] Under `merge`, `loop approve` and its "already approved" message name the merge check, the merge and `on_delivered.move_to`, and `approval_given` records that state (one test each).
- [ ] Without `merge`, the messages are unchanged (existing tests pass unchanged).
- [ ] The tracker read and missing issue messages name `trackerLocation(workflow)`, tested with the GitHub tracker.
- [ ] With the tracker read failing, `loop approve` finds the waiting record by issue id and by identifier (one test each).
- [ ] A github `Waiting(approval)` issue is approved through to `approval_given`, from a github fixture with no string replace on the files fixture (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ApproveCommandTest'` passes.
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
