---
id: m4-51
title: "request_resource and release_resource on the Codex runner"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-25]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 122, the Codex half of the tools (PLAN 6.10). Lane A, runs on the claude-code runner. Split from m4-25, which builds the tools, their answers and the host side path. Area: the Codex client tools (`plugins/runner-codex-app-server/src/main/kotlin/loop/runner/codex/LoopClientTools.kt`, names around lines 80 to 82).

- The Codex thread start carries `request_resource` and `release_resource` as client tools, only when the workflow declares `resources:`.
- The Codex client tools hand their calls to the same `loop tool mcp` (`LoopClientTools.kt`, around line 25), so the answers are those of m4-25: `granted`, `granted, preparing`, `queued` with the position, or a structured failure. Nothing is answered in the Codex plugin itself.
- The tool descriptions are the same text as on the Claude Code runner.
- Out of this issue: the answers and the host side path (m4-25), the limits and the release check (m4-26).

## Acceptance criteria

- [ ] The Codex thread start carries both client tools when the workflow declares `resources:`, and not without it (tests against the fake server).
- [ ] A call is answered with the m4-25 answer, and a queued answer and a structured failure reach the Codex turn unchanged (tests against the fake server).
- [ ] `./gradlew --no-daemon :plugins:runner-codex-app-server:test` passes.
- [ ] PLAN 6.10 says both runners carry the tools (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-25 after the Fable 5.2 review.
