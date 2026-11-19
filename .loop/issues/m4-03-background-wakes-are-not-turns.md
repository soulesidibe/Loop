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
- **Decision taken in the M4 spec:** a wake never counts against `agent.max_turns`. Rule for this issue: the runner marks a turn as a wake turn when it read a wake (an `init` or `assistant` line written while the process was idle, then its `result`) before Loop's input. In the runner that turn is the wake read plus Loop's own input answered (`readIdle`, `ClaudeCodeRunner.kt` around lines 481 to 506). A wake turn stays free even when Loop's input then led to tool calls: the turn timeout bounds its length and `agent.max_wake_turns` bounds their number. A turn started before a wake arrives is an ordinary turn and counts. A wake turn's usage counts, its ledger line says `wake`, and the soft limit is checked at its end like any turn. It does not count against `agent.max_turns`.
- **Proof on the failure seen.** The criteria test the synthetic `background_wake` fixture and the recorded lines of attempt 10c2f759 (the second pilot app issue), which soule copies into `plugins/runner-claude-code/src/test/resources/streams/wake_turns_10c2f759.jsonl` in m4-00 (step 7) before this issue is dispatched. Replayed, turns 2 to 5 must be wake turns.
- **The cap bl-155 asked about:** wake turns get their own cap, `agent.max_wake_turns` (default 30, confirmed by soule on 2026-11-18; `agent.env` is the precedent for a Loop key under `agent:`), so a chatty background task cannot keep an attempt alive forever. Reaching it ends the attempt with exit reason `max_wake_turns`, like `max_turns`. Budgets still apply.
- **bl-114.** Its option "let `stop()` report usage read while idle" landed in bl-132 (`AgentSession.stop(usage)`, `AgentSession.kt:34`); the wake after the last turn is m4-04. Its option "keep a turn open while a background task is pending" is not taken: one turn would then last as long as the subagent and defeat the turn timeout.
- **bl-122, wake part.** A subagent `assistant` line read while idle also sets `waking` (around line 587 at the time of the review); with wake turns now free, it must not. The idle events channel is unlimited and unread while the process is idle, for example during a long verification. Bound it, keeping every usage and `result` line the next turn needs. The test `a resume after a turn woken by a background task adds to the summed usage` in `ClaudeCodeRunnerTest.kt` asserts only the token sum (13_640_165): assert each usage field and the cost again.

Lane A rule: the counting logic goes in a new file in `loop.core.orchestrator` (for example `TurnCount.kt`); the `Orchestrator.kt` edit is limited to call sites. The fake runner gains scripted wakes (testing decisions of the spec).

If this issue proves too large for one attempt, the bounded idle channel (bl-122) is the part that moves to its own issue.

Out of this issue: usage after the stop grace (m4-04), the other bl-122 findings (a turn cancelled during the wake wait, `leftGroups` synchronisation, pid reuse at retire), wakes for the Codex runner.

## Acceptance criteria

- [ ] The Claude Code runner marks a turn that read a wake before Loop's input as a wake turn, tool calls after Loop's input included, and a subagent line read while idle does not set `waking` (runner tests on the `background_wake` fixture and a subagent line).
- [ ] Replayed on the recorded runner lines of attempt 10c2f759 (the second pilot app issue), turns 2 to 5 are wake turns and the attempt has used 2 of 10 turns after turn 6 (fixture test on `wake_turns_10c2f759.jsonl`).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.WakeTurnTest'` passes, a new test class holding the two cases above.
- [ ] Wake turns do not count against `agent.max_turns`; the attempt ends with `max_wake_turns` once `agent.max_wake_turns` wake turns ran (orchestrator tests with scripted wakes in the fake runner).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.TurnCountTest'` passes, a new test class of the counting logic.
- [ ] A wake turn's usage counts in the attempt totals and budgets, its ledger line carries `wake`, and the soft limit rotation can fire at its end (tests).
- [ ] The idle events channel is bounded, and a chatty idle process keeps every usage and `result` line (runner test).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.ClaudeCodeRunnerTest'` passes, with the restored usage field and cost assertions.
- [ ] Preflight refuses an `agent.max_wake_turns` below 1 (test), and `docs/spec-decisions.md` and the plan describe wake turns and the new key (judgment).
- [ ] The counting logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
