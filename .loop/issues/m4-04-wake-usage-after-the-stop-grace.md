---
id: m4-04
title: "The usage of a wake read at or after the stop is counted"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-03]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 104. Lane A, runs on the claude-code runner. Carries bl-152, findings 1 to 4 (lines below were read at head 880e3df, check them at the current head).

Area: the stop grace in `ClaudeCodeRunner.kt` (5 seconds, around line 323 at that head), the orchestrator's `stop` helper in `Orchestrator.kt` (around lines 3270 to 3290 today, `session.stop(usage)` when a callback is given), the attempt end stop, `SpendBudgets.kt`, the ledger (`LedgerMeter.kt`, `JsonlTurnLedger`), the events.

What is known, from the reviews of pull request #117 (bl-132):

1. A wake's usage counts only if its `result` line arrives within the stop grace. A wake in the middle of a model call when a rotation stops the session is killed, and its usage is lost with no trace event, unlike `interrupt_grace_expired`. Record `stop_grace_expired` with what was read, and count the usage the wake's lines already reported.
2. The stop at the attempt's end still calls `stop()` without the usage callback. A wake that ended while idle after the last turn (review, max turns, failure) is read and discarded, so its spend never reaches the budgets or the ledger. Every stop passes the callback.
3. No test covers a budget passed by usage read at the stop. On that path, and on a stop or render failure, the wake's usage is in the attempt totals but in no turn line, so the ledger's turn lines sum to less than the attempt total. Write that usage to the ledger (a `stop` line, or the last turn line) so the turn lines sum to the attempt total.
4. When the stop throws after reporting usage, a budget breach set by that usage is ignored and the attempt ends as a `session_stop_failed` error retry. Decision: the budget breach wins, the attempt ends on the budget, and `session_stop_failed` is still recorded.

Lane A rule: the stop usage logic goes in a new file in `loop.core.orchestrator` (for example `StopUsage.kt`, beside `SessionSpend.kt`); the `Orchestrator.kt` edit is limited to call sites.

Out of this issue: the rest of bl-152 (the rotated issue re-read outside a guard, the re-read fallback copy, the `completeIssue` KDoc and the spec decisions docs about `stop(usage)`, `AgentRunnerContract` and `stop(usage)`), which stays in the backlog after m4-04.

## Acceptance criteria

- [ ] A wake still running when the stop grace ends records `stop_grace_expired`, and the usage its lines already reported counts in the attempt totals (runner and orchestrator tests with a scripted wake).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.ClaudeCodeRunnerTest'` passes, with the new stop grace case.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.StopUsageTest'` passes, a new test class of the stop usage logic.
- [ ] Every stop, the attempt end included, passes the usage callback, so a wake that ended while idle after the last turn reaches the budgets and the ledger (test for a review end and a max turns end).
- [ ] A budget passed by usage read at the stop ends the attempt on the budget (test).
- [ ] When the stop throws after reporting usage that breaches a budget, the attempt ends on the budget and `session_stop_failed` is still recorded (test).
- [ ] The ledger's turn lines of an attempt sum to its total, stop usage included (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.TraceCommandTest'` passes, with a case printing `stop_grace_expired`.
- [ ] `docs/spec-decisions.md` describes usage at the stop (judgment).
- [ ] The stop usage logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
