---
id: m4-52
title: "loop answer ends a Waiting(ask)"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-31]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 128, answer part (PLAN 6.6). Lane A, runs on the claude-code runner. Split from m4-31, which builds `ask` and `Waiting(ask)`. Area: a new `AnswerCommand.kt` in `cli/`, registered in `cli/.../Main.kt`, the waits in `loop.core.waiting`, `PromptVariables.kt`.

- **The answer:** `loop answer <issue> "<text>"` ends the wait, records `question_answered` and a human touch, comments the answer on the issue, and the issue dispatches again in the same workspace.
- **The prompt:** the next attempt's first prompt carries the question and the answer as a prompt variable, with `loop.retry_kind` = `answer`; preflight renders it. The new value joins `PromptVariables.RETRY_KINDS`, and the list assertion of `PromptVariablesTest` gains it.
- **Ids:** a bare id means the primary tracker (m4-12, m4-47); a namespaced id names its tracker.
- **Refusals:** refused inside a Loop attempt, like `loop approve`, on an issue not waiting for an answer, and on an unknown issue, each with its own exit code, changing nothing.
- The wait logic lives in `loop.core.waiting`.
- Out of this issue: answers by agents (M6), answers through Telegram or Slack, `loop send-back` (m4-32).

## Acceptance criteria

- [ ] `loop answer` ends the wait, and the next attempt's first prompt carries the question, the answer and `loop.retry_kind` = `answer` (CLI test with the fake runner).
- [ ] Inside an attempt, on an issue not waiting and on an unknown issue, `loop answer` fails with its own exit code and changes nothing (one test each).
- [ ] A bare id finds the issue in the primary tracker and a namespaced id in its tracker (test).
- [ ] `question_answered` and the human touch are recorded, and `loop trace` prints the event (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.AnswerCommandTest'` passes.
- [ ] `PromptVariables.RETRY_KINDS` holds `answer`, and the list assertion of `PromptVariablesTest` gains it (judgment).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.PromptVariablesTest'` passes.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.6 describe `loop answer`, its exit codes and the `answer` retry kind (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-31 after the Fable 5.2 review.

2026-11-25 15:29Z loop: Loop opened pull request #70 for this issue: https://github.com/soulesidibe/Loop/pull/70

2026-11-25 15:34Z loop: Loop did not merge pull request #70 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff implements loop answer, the answer retry kind, the exit codes and the docs as the issue asks, and the orchestrator edit stays at call sites. The extra store, plugin-api and Wait changes are needed to keep the answer across processes.
  - impact: medium, It adds a command and changes the dispatch hold, the prompt render, the wait lifecycle and the store write path, but it touches no sensitive or one-way paths.
  - verification: strong, An end-to-end CLI round trip covers each acceptance case (inside an attempt, not waiting, unknown issue, bare and namespaced ids, trace output, empty and second answers). Unit tests cover Answers, Waits, the store and PromptVariables.
  - rollback_cost: two_way, A revert removes the command. Answered waits and the new events stay in the store as extra data keys and an unknown event kind, which older code ignores, and there is no schema change.
  - error_detection: partial, Refusals and store failures exit loudly with their own codes. Failures to record events or post the comment only produce a stderr warning and still exit 0.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 13 of 26 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
