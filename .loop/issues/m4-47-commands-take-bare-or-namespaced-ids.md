---
id: m4-47
title: "Commands take bare or namespaced ids, and a single tracker project shows bare ids"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-12]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 112, second half. Lane A, runs on the claude-code runner. m4-12 carries the namespaced id `<tracker id>:<native id>` in the store, the ledger, events and logs. This issue makes the commands and the agent tools take it, and decides how ids are shown.

Area: the `loop` commands that take an issue id (`ShowCommand.kt`, `TraceCommand.kt`, `ApproveCommand.kt`, `ResumeCommand.kt`, `ToolMoveCommand.kt`, `ToolCommentCommand.kt` in `cli/src/main/kotlin/loop/cli/`), the id parsing in `loop.core.trackers` (the `NamespacedId` type of m4-12), the log lines and the output of `loop trace`, `loop show` and `loop status`, docs.

What is known:

- A bare id typed in a command or written by an agent tool means the primary tracker, so `loop approve m4-12` and `loop tool move` keep working. A namespaced id names its tracker; one whose tracker is not in the registry is refused with a message naming the tracker id.
- **Display, decided:** a project with no `trackers:` shows bare ids everywhere, so a single tracker project reads as today. A project with `trackers:` shows the namespaced id, the primary's included. The stored form always carries the tracker id (m4-12).
- m4-29 edits `ApproveCommand.kt` and its tests after this issue, and is blocked by it.

Lane A rule: the parsing and the display rule live in `loop.core.trackers`; the command edits are limited to calling them, and `Orchestrator.kt` is not edited beyond log call sites.

Out of this issue: the type and the store (m4-12), polling several trackers (m4-13), routed writes (m4-14).

## Acceptance criteria

- [ ] A bare id in `loop show`, `loop trace`, `loop approve`, `loop resume` and the tool commands means the primary tracker, and a namespaced id names its tracker (CLI tests).
- [ ] A namespaced id whose tracker is not in the registry is refused, naming the tracker id (test).
- [ ] A project with no `trackers:` shows bare ids in logs, `loop trace`, `loop show` and `loop status`, and a project with `trackers:` shows namespaced ids (tests).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.IssueIdDisplayTest'` passes, a new test class of the parsing and the display rule.
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.TraceCommandTest'` passes, with the bare and namespaced id cases.
- [ ] `docs/spec-decisions.md` and `docs/self-hosting.md` state how commands read ids and how ids are shown (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-12 after the Fable 5.2 review.

2026-11-24 17:24Z loop: Loop opened pull request #41 for this issue: https://github.com/soulesidibe/Loop/pull/41

2026-11-24 17:28Z loop: Loop did not merge pull request #41 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, Changes cover bare/namespaced parsing, refusal of unknown trackers, display rule and docs as the issue asks; the Orchestrator is untouched and the edits are call sites into loop.core.trackers.
  - impact: medium, It changes how ids are read and shown in six commands, the logs and the tool snapshot format, but it adds no schema change or write path.
  - verification: strong, New CLI tests cover bare and namespaced ids, an unknown tracker, with and without trackers display, and the agent tools. The spec-core display test and updated log tests are included. Two paths are not covered: show and status with extra tracker issues are only lightly exercised, and old snapshots a
  - rollback_cost: two_way, The store still holds namespaced ids as before and the tool-config snapshot keys are optional, so a revert leaves no incompatible state.
  - error_detection: loud, An unknown tracker is refused with a named message and exit code 2, and the tool refusals are loud too. Falling back to the old matching when the snapshot has no tracker ids is quiet but deliberate.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 7 of 23 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
