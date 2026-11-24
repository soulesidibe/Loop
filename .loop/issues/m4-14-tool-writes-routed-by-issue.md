---
id: m4-14
title: "Tool writes routed to the issue's own tracker, with native_ref"
state: done
priority: 1
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

2026-11-24 20:51Z agent 0c1633af: m4-14 done, ready for review. TrackerAdapter has moveIssue and addComment overloads taking the current Issue, whose defaults call the id versions. loop.core.trackers.WriteRouting sends every write to the tracker of the issue's namespaced id. A write for a tracker the registry does not have fails with UnroutedWrite, which names the tracker id, and nothing is written. The tool config snapshot now holds the kind, states and provider of the issue's own tracker, so each agent's move is checked against its own tracker. The Orchestrator.kt diff only touches write call sites and the snapshot call. New tests: WriteRoutingTest, WriteRoutingOrchestratorTest, two tracker cases in ClaudeCodeTrackerToolsTest and CodexClientToolsRunTest (files plus GitHub on recorded answers, with a new RecordedGitHubIssue helper). The 17.5 item 16 tools 3 row is Covered. One known gap, recorded in docs/spec-decisions.md: the merge outcome comments in loop.core.merge.Merges still use the id version, because about 30 call sites hold the wait and not the issue. The router sends them to the right tracker, but they carry no native_ref. Worth a backlog item if a tracker ever needs native_ref there.

2026-11-24 20:55Z loop: Loop opened pull request #46 for this issue: https://github.com/soulesidibe/Loop/pull/46

2026-11-24 20:59Z loop: Loop did not merge pull request #46 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff adds the Issue overloads with default bodies, a WriteRouting in loop.core.trackers, a per-tracker tool snapshot and the new tests the issue asks for, and the Orchestrator.kt changes appear to touch only write call sites.
  - impact: medium, Every tracker write now goes through the new router, and the ToolConfigWriter signature and the DeliveryFacts shape changed, so the core write path and the tool snapshot are affected.
  - verification: strong, New unit, orchestrator and CLI tests cover the defaults, native_ref, routing to each tracker on recorded GitHub answers, the unrouted tracker failure and the Codex path, and the one known gap in the merge comments is documented.
  - rollback_cost: two_way, The change is code, tests and docs only, with no schema or data migration, and the new overloads are additive with defaults, so a revert undoes it.
  - error_detection: loud, A write for a tracker missing from the registry fails with UnroutedWrite naming the tracker id and writes nothing, and pull request delivery treats it as permanent.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 11 of 21 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
