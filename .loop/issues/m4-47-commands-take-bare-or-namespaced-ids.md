---
id: m4-47
title: "Commands take bare or namespaced ids, and a single tracker project shows bare ids"
state: todo
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
