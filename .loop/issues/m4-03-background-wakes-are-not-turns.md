---
id: m4-03
title: "Background wakes never count as turns, and the idle channel is bounded"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-02]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 103. Lane A, runs on the claude-code runner. Carries bl-155, bl-114 and the wake findings of bl-122.

Area: `plugins/runner-claude-code/src/main/kotlin/loop/runner/claudecode/ClaudeCodeRunner.kt` (wake detection around lines 660 to 701, the `waking` flag around line 813, the idle channels at lines 553 and 554, both `Channel.UNLIMITED`), the turn result in `plugin-api/.../RunnerEvent.kt`, the turn count in `Orchestrator.kt` (the turn loop around lines 2741 to 2830 and `endReason` around line 3033), the ledger turn lines, `AgentConfig` for the new key, docs.

What is known:

- **bl-155.** The pilot app agent (the Loop variant of its issue skill) ends its turn while a subagent or a benchmark works in the background. The next turn reads the wake first (m3-07), so each wake takes a turn. Attempt 10c2f759 of the second pilot app issue used 6 of its 10 turns in 12 minutes: turns 2 to 5 lasted 10 to 50 seconds and held one subagent report each.
- **Decision taken in the M4 spec:** a wake never counts against `agent.max_turns`. Rule for this issue (decided by soule in bl-170 on 2026-11-21, option E): a turn that reads the `task_notification` line (`type` `system`, `subtype` `task_notification`) of a background task run started in an earlier turn (its `task_started` line, matched by `task_id` and `run_id`, read in an earlier turn of the same attempt) is a wake turn. Match the run, not the task: a restarted task keeps its `task_id` with a new `run_id`, as task `id_089` does in the recording. Where the turn reads it does not matter: drained while idle before Loop's input, inside a wake's own `init` and `result`, or folded by Claude Code into the turn's answer. The rule does not depend on input timing or on the `origin` field, and it does not change where a turn ends. A wake turn stays free even when it then does real work: the turn timeout bounds its length and `agent.max_wake_turns` bounds their number. Two notifications in one turn make one wake turn. A notification of a run started in the same turn does not make a wake turn. A wake turn's usage counts, its ledger line says `wake`, and the soft limit is checked at its end like any turn. It does not count against `agent.max_turns`.
- **Proof on the failure seen.** The criteria test the synthetic `background_wake` fixture and the recorded lines of attempt 10c2f759 (the second pilot app issue), which soule copies into `plugins/runner-claude-code/src/test/resources/streams/wake_turns_10c2f759.jsonl` in m4-00 (step 7) before this issue is dispatched. `wake_turns_10c2f759.turns.json` next to it gives each turn's start, its first line and its last line, and each line's read time. Replayed, turns 2, 3, 4 and 6 are wake turns: they read the notifications at lines 197, 260, 289 and 350, of runs started in turns 1, 2, 3 and 4. Turns 1 and 5 are ordinary, so the attempt has used 2 of 10 turns after turn 6. Turns 2 and 4 also hold a wake's own `init` and `result` (lines 199 to 209 and 291 to 302), each `result` with `origin.kind` `task-notification`, which is Claude Code's own wake marker.
- **The cap bl-155 asked about:** wake turns get their own cap, `agent.max_wake_turns` (default 30, confirmed by soule on 2026-11-18; `agent.env` is the precedent for a Loop key under `agent:`), so a chatty background task cannot keep an attempt alive forever. Reaching it ends the attempt with exit reason `max_wake_turns`, like `max_turns`. Budgets still apply.
- **bl-114.** Its option "let `stop()` report usage read while idle" landed in bl-132 (`AgentSession.stop(usage)`, `AgentSession.kt:34`); the wake after the last turn is m4-04. Its option "keep a turn open while a background task is pending" is not taken: one turn would then last as long as the subagent and defeat the turn timeout.
- **bl-122, wake part.** A subagent `assistant` line read while idle also sets `waking` (around line 587 at the time of the review); with wake turns now free, it must not. Without that trigger a wake's `result` can end a turn early, the known limit of `docs/spec-decisions.md` (Lines written while idle); a `result` with `origin.kind` `task-notification` marks a wake run and may be used to keep it from ending the turn, while a result after an interrupt request always ends it. The idle events channel is unlimited and unread while the process is idle, for example during a long verification. Bound it, keeping every usage and `result` line the next turn needs. The test `a resume after a turn woken by a background task adds to the summed usage` in `ClaudeCodeRunnerTest.kt` asserts only the token sum (13_640_165): assert each usage field and the cost again.

Lane A rule: the counting logic goes in a new file in `loop.core.orchestrator` (for example `TurnCount.kt`); the `Orchestrator.kt` edit is limited to call sites. The fake runner gains scripted wakes (testing decisions of the spec).

If this issue proves too large for one attempt, the bounded idle channel (bl-122) is the part that moves to its own issue.

Out of this issue: usage after the stop grace (m4-04), the other bl-122 findings (a turn cancelled during the wake wait, `leftGroups` synchronisation, pid reuse at retire), wakes for the Codex runner.

## Acceptance criteria

- [ ] The Claude Code runner marks a turn that reads the `task_notification` of a background task run started in an earlier turn as a wake turn, whether the line is read while idle, inside a wake, or folded into the turn's answer, and a notification of a run started in the same turn does not, a restarted task with a new `run_id` included; a subagent line read while idle does not set `waking` (runner tests on the `background_wake` fixture, given the `task_started` and `task_notification` lines Claude Code writes, and a subagent line).
- [ ] Replayed on the recorded runner lines of attempt 10c2f759 (the second pilot app issue), turn by turn as `wake_turns_10c2f759.turns.json` delimits them, turns 2, 3, 4 and 6 are wake turns and turns 1 and 5 are not (fixture test on `wake_turns_10c2f759.jsonl`).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.WakeTurnTest'` passes, a new test class holding the two cases above.
- [ ] Wake turns do not count against `agent.max_turns`; the attempt ends with `max_wake_turns` once `agent.max_wake_turns` wake turns ran (orchestrator tests with scripted wakes in the fake runner).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.TurnCountTest'` passes, a new test class of the counting logic, with the six turns of attempt 10c2f759 marked as above leaving 2 of 10 turns used after turn 6.
- [ ] A wake turn's usage counts in the attempt totals and budgets, its ledger line carries `wake`, and the soft limit rotation can fire at its end (tests).
- [ ] The idle events channel is bounded, and a chatty idle process keeps every usage and `result` line (runner test).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.ClaudeCodeRunnerTest'` passes, with the restored usage field and cost assertions.
- [ ] Preflight refuses an `agent.max_wake_turns` below 1 (test), and `docs/spec-decisions.md` and the plan describe wake turns and the new key (judgment).
- [ ] The counting logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-21 soule: the wake turn rule and its replay criteria follow the bl-170 decision (option E): a turn that reads the notification of a background task run started in an earlier turn is a wake turn, wherever it reads it. On attempt 10c2f759 that frees turns 2, 3, 4 and 6.

2026-11-23 03:44Z agent 2e4b349f: CONTEXT.md is a protected path, so the glossary entry for wake turn was taken out of this branch (5b27df1). Proposed text, for a human to add if wanted: "**Wake turn**: A turn that reads the notification of a background task run (a subagent, a long command) that an earlier turn of the same agent session started. It does not count against `agent.max_turns`, only against `agent.max_wake_turns`; its usage counts like any turn's. _Avoid_: Wake (the run the runtime starts on its own after the notification, which a wake turn may or may not hold)". The plan and docs/spec-decisions.md (Wake turns, extension, m4-03) describe wake turns and the new key.

2026-11-23 03:58Z loop: Loop opened pull request #20 for this issue: https://github.com/soulesidibe/Loop/pull/20

2026-11-23 04:08Z loop: Loop did not merge pull request #20 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1128 lines, over the limit of 1000
  Merge risk: not assessed, a condition failed.
  Once approved, Loop runs the merge check again and merges.

2026-11-23 04:56Z loop: Loop did not merge pull request #20, ci: Actions runs did not pass: build (failure). The pull request stays open and the result waits for a new approval.
  Failed runs:
  - build (failure): https://github.com/soulesidibe/Loop/actions/runs/45113835331
  Rerun the runs on GitHub or fix the branch, then approve again with loop approve.
