---
id: bl-252
title: "A human move of a waiting issue rejects its approval wait, and loop approve refuses it"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-250]
area: [spec-core/src/main/kotlin/loop/core/orchestrator, spec-core/src/main/kotlin/loop/core/waiting, spec-core/src/main/kotlin/loop/core/verify/Verification.kt, cli/src/main/kotlin/loop/cli/ApproveCommand.kt, docs/spec-decisions.md, docs/PLAN.md]
---

## Parent

bl-250. It takes the medium of bl-42 (no reject path for a waiting issue), split from bl-250 after review. It ends the wait through the conditional primitive bl-250 adds to the store, so it starts once bl-250 is merged.

## What

**Category:** bug

**Summary:** a human move of a waiting issue to another active state rejects its approval wait, and `loop approve` refuses an issue moved that way.

**Current behavior:**

- An approval wait does not store the issue's state when it starts. A human who moves the waiting issue from `todo` to `in_progress`, or to any other active state, to say "not this result" changes nothing: the issue stays waiting and is never dispatched, and a later `loop approve` (or an approval given before the move) delivers the old result against that intent. The only way to reject today is a move out of the active states and back, on separate ticks. The ask wait already has this rule (`WaitKeys.STATE`, `Wait.Ask.humanMoved`, m4-31).
- `syncApprovals` ends an approval wait only when its issue is gone or out of the active states.

**Desired behavior:**

1. **The reject rule.** A new approval wait stores the issue's state at that moment under `WaitKeys.STATE`, as the ask wait does. On each tick, an approval wait whose issue reads active, in a state other than the stored one, ends as a human move: the wait is removed through bl-250's conditional primitive, a `human_touch` with `touch` = `move`, `from_state` and `to_state` is recorded, the log says a human moved the issue, and the issue is dispatched again like any other candidate. An approval already given is void and nothing is delivered. States compare trimmed and case folded, as `Wait.Ask.humanMoved` does; share that rule rather than copy it.
2. **What the reject rule leaves alone.** A wait with no stored state (written by an older build) behaves as today. A sent back wait follows bl-221's path unchanged, and so does any wait whose issue is running. The check runs on the per tick read, so a wait restored after a restart follows it too.
3. **`loop approve` refuses a moved issue.** When the tracker shows the issue in a state other than the wait's stored state, `loop approve` exits 4 with `loop approve: <issue> was moved by a human from <stored> to <now> since it waited for approval, Loop dispatches it again. Nothing changed.`, records no `approval_given` and no `human_touch`, and leaves the row as it is. Once the tick ended the wait, `loop approve` answers as for any issue that does not wait (exit 4, today's message). `loop send-back` is unchanged: a send back is an explicit request to dispatch again with a reason.

**Key interfaces:**

- `Wait.Approval` (a `state` property and a moved check shared with `Wait.Ask`), `Wait.approval(...)` and the `Verification` code that starts the approval wait (`Verification.kt`).
- The orchestrator's approval sync (`syncApprovals`). `Orchestrator.kt` is very large: grep it, never read it whole.
- bl-250's conditional replace or remove on `WaitingIssues` and its `Waits` method.
- `ApproveCommand.checkApproval` and the issue it already reads from the tracker.
- `HumanTouch.MOVE` and the `from_state` and `to_state` keys of `human_touch`.

## Acceptance criteria

- [ ] An orchestrator test: a waiting issue moved by a human from `todo` to `in_progress` ends the wait with one `human_touch` of `touch` = `move`, `from_state` = `todo` and `to_state` = `in_progress`, and the issue is dispatched again.
- [ ] An orchestrator test: `loop approve` (the store's approve) then a move to another active state, before the delivering tick, delivers nothing: no move, no push, the wait ends as a human move.
- [ ] Orchestrator tests: a wait stored without a state keeps waiting after such a move; a sent back wait and a wait whose issue is running are not ended by the rule; a new orchestrator over the same store applies the rule to a restored wait.
- [ ] A CLI test in `ApproveCommandTest`: after a human move to another active state, with no Loop running, `loop approve` exits 4 with the message above, records no `approval_given` and no `human_touch`, and the row is unchanged.
- [ ] `./gradlew --no-daemon :spec-core:test --tests "loop.core.orchestrator.*"` passes.
- [ ] `./gradlew --no-daemon :cli:test --tests "loop.cli.ApproveCommandTest"` passes.
- [ ] `./gradlew --no-daemon build` passes.
- [ ] `docs/spec-decisions.md` states, under delivery policy and approval, the reject rule, and the `loop approve` exit table gains the moved issue row. `docs/PLAN.md` 6.6 and the `loop approve` command line mention the refusal.

## Out of scope

- The conditional saves of an approval wait and the re-reads before a delivery (bl-250).
- The reject rule for a `Waiting(merge)` in its check, CI or merge phase. The merge wait copies the approval wait's keys, so the stored state reaches it for free; acting on it stays a note in bl-42.
- Closing or commenting the pull request of a rejected wait.
- Label changes on a GitHub issue that do not change its state as Loop reads it.
- bl-42's low items and the human touch accounting of bl-64.
- Protected paths, which the diff must not touch: `scripts/`, `.github/`, `WORKFLOW.md`, `*.gradle.kts`, `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew`, `gradlew.bat`, `.loop/specs/`, `docs/adr/`, `CONTEXT.md`. No residual human step is expected.

## Comments

2026-11-26 triage:

> *This was generated by AI during triage.*

Bug, ready-for-agent, priority 2, blocked by bl-250. Split from bl-250 (items 6 to 8 of its first draft) after a Fable 5.2 review of the brief on 2026-11-26, so each pull request stays one mechanism. bl-42's medium still holds on main at 785c1ab: `Wait.Approval` stores no state and `syncApprovals` ends a wait only when its issue is gone or out of the active states. m4-32 added the send back but no reject on a move; m4-31 built the rule for the ask wait only, which this issue reuses.

2026-11-27 01:54Z loop: Loop opened pull request #108 for this issue: https://github.com/soulesidibe/Loop/pull/108
