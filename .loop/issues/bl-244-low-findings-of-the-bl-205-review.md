---
id: bl-244
title: "Low findings of the bl-205 review, the Gemini risk reviewer"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #91 (bl-205), Sonnet 5.5 and Fable 5.2, on 2026-11-26. Attempt 2 was sent back because its head conflicted with main after bl-230 (with lows); attempt 3 fixed all of it, and its reviews found only the lows below; approved.

## What

1. **A draft verdict before a cut turn is accepted.** The answer falls back to the last non blank earlier message when the final one is blank, and the runner maps `max_tokens` and `max_turn_requests` to `TurnCompleted`. A complete verdict JSON written mid turn, then tool calls, then a cut with no final text, is taken as the answer. Same rule as the Codex reviewer; consider failing when the stop reason is a cut, or taking only a message after the last tool call.
2. **`think` with an unknown tool name is allowed in read only mode** (older than bl-205, same as the worker policy). Allow `think` only with no name, or only for known think tools, under `readOnly`.
3. **Chunks with no `messageId` never split.** Two id less messages with no tool call between them concatenate (fails closed through a parse failure), and a change from an id to no id does not end a message, while the class doc and docs say "a chunk with another messageId ends it". Fix the rule or the wording.
4. **No test for the no terminal event path,** now reported as its own `Crashed`.

Area: `plugins/runner-acp` (`GeminiRiskReviewer.kt`, `AcpPermissionPolicy.kt`), `docs/plugins/runner-acp.md`.

## Comments
