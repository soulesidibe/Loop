---
id: m4-32
title: "loop send-back, which is bl-89's loop reject"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-52]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 129 (PLAN 6.6). Lane A, runs on the claude-code runner. Area: a new `SendBackCommand.kt` in `cli/`, registered in `cli/.../Main.kt`, the waits (`spec-core/.../waiting/Waits.kt`, the logic in `loop.core.waiting`), the retry path that already carries a refuted merge check's findings, `PromptVariables.kt`.

Carries bl-99. Sending m3-04 back with merge risk findings took a comment, a move to `review` (which ends the approval wait as a human change), a wait for the next tick, then a move back to `todo`. The docs say "move the issue back to todo with a comment", but a waiting issue is already `todo`. bl-99 proposed `loop send-back <id> --comment <text>`; Loop doing it on its own for a medium risk band is left for later.

Carries the manual half of bl-89. Since m3-15, a red CI ends the CI wait with a comment and an unapproved `Waiting(approval)`, and by m3-15's note 8 Loop does not dispatch the issue again. bl-89 asked for `loop reject <id> [reason]`; it is this command, one name, `send-back`. The automatic sending of a red CI is the `ci_failed` reaction (m4-35).

- `loop send-back <issue> --reason "<text>"` ends a `Waiting(approval)`, approved or not, and requeues the issue in the same workspace. The next attempt gets my reason as verify findings, the path a refuted merge check takes, with `loop.retry_kind` = `sent_back`; preflight renders it. The new value joins `PromptVariables.RETRY_KINDS`, and the list assertion of `PromptVariablesTest` gains it.
- A bare id means the primary tracker (m4-12, m4-47); a namespaced id names its tracker.
- Loop comments the reason on the issue, records `sent_back` (by, reason) and a human touch `send_back`, counted by the next ledger row.
- The pull request, when one is open, stays open; the next delivery updates it.
- **No slot:** a send back takes no `max_attempts` slot. The human is the bound. On the pilot app (`max_attempts: 2`) a slot would park the issue after one retry and one send back, and cost a `loop resume`. The slot stays for automatic reactions (m4-34). The docs say so.
- Refused inside a Loop attempt, and on an issue that is not waiting for approval (a `Waiting(merge)` in progress, a `Waiting(ask)`, which `loop answer` ends), each with its own exit code and a message naming the right command.
- A new glossary term for Send back goes under `residual_human_steps` with its exact text: `CONTEXT.md` is protected.
- Out of this issue: automatic send backs on medium risk or red CI (m4-35), reactions.

## Acceptance criteria

- [ ] `loop send-back` on a `Waiting(approval)` issue ends the wait, comments the reason, and the next attempt's prompt carries the reason as verify findings with `loop.retry_kind` = `sent_back` (CLI test with the fake runner).
- [ ] After a red CI wait recorded from Actions answers, `loop send-back` sends the issue back with the pull request left open, and the next delivery updates the same pull request (test on recorded responses).
- [ ] `sent_back` and the `send_back` human touch are recorded, the touch counted in the next ledger row, and `loop trace` prints the event (test).
- [ ] A send back takes no `max_attempts` slot: an issue with no slot left is sent back and dispatches again (test).
- [ ] A bare id finds the issue in the primary tracker and a namespaced id in its tracker (test).
- [ ] Inside an attempt, on an issue not waiting, on a `Waiting(merge)` and on a `Waiting(ask)`, the command fails with its own exit code, names the right command and changes nothing (one test each).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.SendBackCommandTest'` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.PromptVariablesTest'` passes.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] The glossary text for Send back is listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.6 describe `loop send-back` and why it takes no slot, and the "Delivery" bullet of `docs/self-hosting.md` (around line 121) names it instead of the move back to `todo` (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 16:16Z agent db8478da: The full build of turn 2 failed on two tests outside m4-32's change: WorktreeHooksTest (after_create survives two parallel workspaces, round 5, git failed to read .git/worktrees/p-2/commondir) and IosSimulatorProviderTest (a hanging wait for the boot fails once the prepare timeout passed, NullPointerException at line 300). m4-32 touches neither the worktree hooks nor plugins/resource-ios. Both pass when run alone, so they look like timing flakes under the parallel build. Worth a backlog issue each.

2026-11-25 16:20Z loop: Loop opened pull request #71 for this issue: https://github.com/soulesidibe/Loop/pull/71

2026-11-25 16:24Z loop: Loop did not merge pull request #71 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements the command, the exit codes, the no-slot rule, the new retry kind and the docs the issue asks for, with changes kept to call sites in the orchestrator.
  - impact: medium, It adds a CLI command and touches the shared wait model, the store, the dispatch hold logic and the prompt variables, but it adds an opt-in path and changes no existing flow by default.
  - verification: strong, Each acceptance criterion has its own test: CLI end-to-end tests, the refusal cases, the no-slot case, the red-CI case, and unit tests for the store and the waits.
  - rollback_cost: two_way, A revert removes the command and code. The sent_back keys in the wait data and the new events are additive and cause no schema change, so only existing events and comments remain.
  - error_detection: loud, Each refusal exits with its own code and a message, and a store failure fails the command. A comment or event write failure only warns on stderr, though the reason still reaches the agent through the store.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 15 of 23 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
