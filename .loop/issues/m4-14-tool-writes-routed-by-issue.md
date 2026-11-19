---
id: m4-14
title: "Tool writes routed to the issue's own tracker, with native_ref"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-48]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 114. Lane A, runs on the claude-code runner. Carries bl-153, the one Partial row of the conformance matrix (17.5 item 16, tools 3, in `docs/spec-decisions.md`).

Area: `TrackerAdapter.moveIssue` and `addComment` in `plugin-api/src/main/kotlin/loop/api/TrackerAdapter.kt` (around lines 22 to 40), both tracker plugins, the host side tool drain in `Orchestrator.kt` (the move around line 1869, the comment around line 2130) and the other write call sites (around lines 730, 4169, 4208 and 4594), `loop.core.trackers` (routing by issue), the CLI tool commands (`ToolMoveCommand.kt`, `ToolCommentCommand.kt`, `TrackerTools.kt`), the conformance matrix.

What is known:

- **bl-153.** The spec asks that the current normalized issue and its `native_ref` be available as internal tool context (SPEC 17.5), and section 11 says the runtime SHOULD pass the current issue to the adapter. Loop runs the agent's `move` and `comment` host side, bound to the attempt's issue, which holds the whole `Issue` with `nativeRef`, but the adapter writes take the id alone. No M3 tracker needed more; a tracker whose writes need a provider id beyond `issue.id` (a GitHub Projects item id, a Linear issue uuid) would.
- **Decision for this issue:** the adapter's writes gain overloads that take the current `Issue`, with default bodies that call the id versions, so a plugin that does not override them keeps working. The core always calls the `Issue` versions.
- **Routing:** with several trackers (m4-13), every write goes to the tracker of the issue's namespaced id, never to the primary by default. The Claude Code tool commands and the Codex client tools `move` and `comment` are routed the same way. A write for an issue whose tracker is not in the registry fails with a message naming the tracker id, and records nothing on another tracker.

Lane A rule: routing lives in `loop.core.trackers`; the `Orchestrator.kt` edit is limited to the write call sites.

Out of this issue: `file_followup` (m4-33), label writes of the `files` tracker (bl-16, m4-33).

## Acceptance criteria

- [ ] `TrackerAdapter` has `moveIssue` and `addComment` overloads taking the current `Issue`, whose default bodies call the id versions (test with an adapter that overrides only the id versions).
- [ ] An adapter's tool write can read the current issue's `native_ref` (test, in the `TrackerAdapterContract` suite or beside it).
- [ ] With a `files` tracker and a GitHub tracker on recorded answers, an agent's `move` and `comment` on each issue reach that issue's own tracker and never the other (CLI test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ClaudeCodeTrackerToolsTest'` passes, with the two tracker case.
- [ ] The Codex client tools `move` and `comment` are routed the same way (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.CodexClientToolsRunTest'` passes, with the two tracker case.
- [ ] Loop's own writes (comments on delivery, moves on verified and delivered) are routed the same way (test).
- [ ] A write for an issue whose tracker is not in the registry fails naming the tracker id, with no write elsewhere (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.WriteRoutingTest'` passes, a new test class of the routing.
- [ ] The 17.5 item 16 tools 3 row of the conformance matrix in `docs/spec-decisions.md` is Covered, naming the tests (judgment).
- [ ] `./gradlew --no-daemon :conformance:test` passes.
- [ ] Routing lives in `loop.core.trackers` and the `Orchestrator.kt` diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
