---
id: m4-25
title: "request_resource and release_resource on the Claude Code runner"
state: todo
priority: 1
labels: [deferred]
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
