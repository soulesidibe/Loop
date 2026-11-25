---
id: m4-25
title: "request_resource and release_resource on the Claude Code runner"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-50]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 122, tools part (PLAN 6.9 and 6.10). Lane A, runs on the claude-code runner. Area: the Loop MCP server (`mcp/src/main/kotlin/loop/mcp/LoopMcpServer.kt`, the tool names around lines 72 to 76 and the handlers around lines 29 to 41), the host side tool path in `cli/` (`AttemptTool.kt`, `ToolConfig.kt`, `ToolMcpCommand.kt`), the `loop run` tick, and `loop.core.resources` from m4-24 and m4-50. The Codex half is m4-51.

- **Answered inside the turn:** `request_resource(name)` and `release_resource(name)` are the first tools answered inside the turn, so they do not use the queue of `submit_result`, `move` and `comment`, which Loop applies when the turn ends (`ToolMcpCommand.kt`, around lines 24 to 37). The `loop tool mcp` process takes or queues the lock in the store itself (`AttemptTool.openStore`), reading the resources from the attempt's tool config snapshot (`ToolConfig.kt`), and answers `granted` or `queued` at once. It never reads `WORKFLOW.md`.
- **`prepare` runs in `loop run`:** `loop run` sees the new lock row on its next tick, runs the provider's `prepare` and writes the result on the lock row (m4-50). The agent's next call returns it, `granted, preparing` until then. So a booted simulator or a background emulator is a child of `loop run`, never of the short lived tool process.
- **The answers:** `granted` with what `prepare` returned (for example a UDID) and the resource's `can` and `cannot`, `granted, preparing`, or `queued` with the position. The agent calls again to check; the queue keeps its place. The tool description says so. A call for an unknown name, or a second request for a resource the attempt already holds, gets a structured failure and the turn goes on.
- **Release:** `release_resource` marks the lock for release in the store; `loop run` runs the provider's `release` on its next tick and grants the next in queue. Releasing a resource the attempt does not hold is a structured failure. The read only check on release is m4-26's.
- Events `resource_requested`, `resource_granted`, `resource_queued`, `resource_released`, shown by `loop trace`.
- The scripted call goes through `loop tool mcp` on stdio, as `ToolMcpCommandTest` does; no new `loop tool` command. So grants run without a model.
- The tools are advertised only when the workflow declares `resources:`.
- Out of this issue: the Codex client tools (m4-51), the limits, the read only check after release and the `resource:` key of checks (m4-26), the real providers (m4-27, m4-28).

## Acceptance criteria

- [ ] Two attempts of the fake runner request one resource through `loop tool mcp` on stdio: the first gets `granted, preparing`, then `granted` with the map once `loop run` ran `prepare`; the second gets `queued` with its position, then is granted when the first releases (CLI test).
- [ ] `prepare` and `release` run in the `loop run` process, never in `loop tool mcp` (test with a fake provider that records its process id).
- [ ] The tool config snapshot carries the resources, and `loop tool mcp` reads no `WORKFLOW.md` (test).
- [ ] An unknown name, a double request and a release of a resource not held each get a structured failure and the turn goes on (one test each).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ResourceToolsTest'` passes.
- [ ] The Claude Code MCP server advertises both tools with valid schemas only when the workflow declares `resources:`, and a call reaches the host (tests).
- [ ] `./gradlew --no-daemon :mcp:test` passes.
- [ ] The four events are recorded and printed by `loop trace` (test).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.10 describe both tools, the two processes, their answers and events (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 05:35Z soule: scope added before merge, from bl-204 finding 1 (merge risk reviews of m4-50). It is part of this issue's acceptance:
  - A lock or queue row whose project has no live run file is freed with reason `abandoned`, on the tick of `loop run` and before a `request` answers `queued`. Use `RunFiles(loopHome, projectId).peek(retry = false)` like `slotLive` in `StoreMachine.kt`: never peek the caller's own project (that drops its POSIX run lock), and treat `Unreadable` as live. No schema change.
  - A failed free at attempt end (`freeAttempt`, `Orchestrator.kt` around line 1455) is retried on the tick, or rows whose attempt is not running in this run are swept.
  - Acceptance test: project A killed while holding a lock, project B's queued request is granted without A restarting.

2026-11-25 06:13Z loop: Loop opened pull request #60 for this issue: https://github.com/soulesidibe/Loop/pull/60

2026-11-25 06:23Z loop: Loop did not merge pull request #60 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff adds both in-turn tools, the `loop run` tick, the snapshot resources, the four events, the docs and the bl-204 abandoned-holder sweep, and the Orchestrator edit is small (+8 -1).
  - impact: medium (floor medium: the diff changes 973 lines outside tests and docs, over half the limit of 1000), It adds a new in-turn tool path, a 500ms grant tick in every `loop run` and cross-project lock freeing in the shared machine store, with no schema change and nothing sensitive touched.
  - verification: strong, Every criterion has a test: two attempts over real stdio, provider pid checks, the snapshot with no WORKFLOW.md, one test for each structured failure, the four events in `loop trace`, and a kill -9 test for the bl-204 sweep, plus unit tests in spec-core, store and mcp.
  - rollback_cost: two_way, There is no schema change and the snapshot key is optional, so a revert is clean. Lock rows and events already written are only inert data.
  - error_detection: partial, Structured failures are loud, but a failed grant tick and an unrecorded event are only logged as warnings and retried, and an unreadable run file counts as live, so a stuck lock could go unnoticed for a while.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 15 of 24 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
