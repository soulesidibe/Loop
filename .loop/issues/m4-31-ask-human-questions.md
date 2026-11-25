---
id: m4-31
title: "ask for human questions: the tool and Waiting(ask)"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-30]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 128, ask part (PLAN 6.6 and 6.10). Lane A, runs on the claude-code runner. Built in M4 by the m3-29 decision, since the manager skill's `SendMessage` conventions map to Loop tools (PLAN 16, finding H5 of the m3-29 review). Split after the Fable 5.2 review: `loop answer` is m4-52. Area: the Loop MCP server (`mcp/.../LoopMcpServer.kt`), the Codex client tools (`plugins/runner-codex-app-server/.../LoopClientTools.kt`), `WaitReason` (`plugin-api/.../ProjectState.kt`, around line 208), a new wait in `loop.core.waiting`, and the channel of m4-30.

- **The tool:** `ask(kind, question, options, recommendation)` reaches both runners. In M4 every question goes to the human: routing engineering, design and product questions to manager agents is M6. `kind` is recorded with the question. The tool description says `ask` is only for a question a human must answer, and that technical choices are the agent's own, since an ask takes no slot and nothing else bounds it.
- **The wait:** the call is queued like `comment` and applied host side when the turn ends; it answers `pending` and tells the agent to end its turn. Loop posts the question on the issue as a comment (with the options and the recommendation), ends the attempt with no verification, keeps the workspace, and the issue waits as `Waiting(ask)`, a new `WaitReason`. The ask notifies through the channel of m4-30 (`question`).
- **Not a failure:** an ask takes no `max_attempts` slot and does not count toward `max_continuations`.
- **Other exits:** a human move of the issue ends the wait, as for an approval. A second `ask` in one attempt before the turn ends is a structured failure.
- The wait logic lives in `loop.core.waiting`.
- The scripted call goes through `loop tool mcp` on stdio, as `ToolMcpCommandTest` does; no new `loop tool` command.
- A new glossary term for `Waiting(ask)` goes under `residual_human_steps` with its exact text: `CONTEXT.md` is protected.
- Out of this issue: `loop answer` and the prompt that carries the answer (m4-52), answers by agents (M6), answers through Telegram or Slack, `loop send-back` (m4-32).

## Acceptance criteria

- [ ] A fake runner `ask`, sent through `loop tool mcp` on stdio, ends the attempt with no verification, comments the question on the issue, notifies once, and the issue waits as `Waiting(ask)` shown by `loop status` (CLI test).
- [ ] An ask takes no `max_attempts` slot and does not count toward `max_continuations` (test).
- [ ] A human move ends the wait, and a second `ask` in one attempt is a structured failure (one test each).
- [ ] `question_asked` is recorded and printed by `loop trace` (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.AskToolTest'` passes.
- [ ] Both runners advertise `ask` with a valid schema, and its description says it is only for a question a human must answer and that technical choices are the agent's own (tests).
- [ ] `./gradlew --no-daemon :mcp:test && ./gradlew --no-daemon :plugins:runner-codex-app-server:test` passes.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] The glossary text for `Waiting(ask)` is listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md`, PLAN 6.6 and 6.10 describe `ask` in M4 and `Waiting(ask)` (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 14:40Z loop: Loop opened pull request #69 for this issue: https://github.com/soulesidibe/Loop/pull/69

2026-11-25 14:48Z loop: Loop did not merge pull request #69 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements the ask tool on both runners, Waiting(ask), the comment, the notice, the no-slot rule, the docs and the glossary text in the result, with no visible drive-by change.
  - impact: medium (floor medium: the diff changes 645 lines outside tests and docs, over half the limit of 1000), It adds a new wait reason and attempt exit reason, and touches the orchestrator, the retry and budget rules and both runner tool sets, 645 non-test lines in all.
  - verification: strong, End-to-end CLI tests cover each acceptance criterion, including the counters, a human move, a second ask and the trace event, and unit tests cover the MCP server, the Codex tools, Asks and Waits.
  - rollback_cost: two_way, A revert removes the code. Rows with reason ask in waiting_issues and the comments already posted would stay, but the wait ends when a human moves the issue, so the cost is small.
  - error_detection: loud, Invalid arguments and a second ask return structured tool errors, a failed comment post records tool_request_failed, and dropped requests record events. A queue that cannot be read counts as no ask queued, which is a small fallback.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 22 of 31 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
