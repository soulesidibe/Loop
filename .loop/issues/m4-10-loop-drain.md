---
id: m4-10
title: "loop drain: finish the running attempts, start nothing new, stop"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-46, m4-09]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 110. Lane A, runs on the claude-code runner. Carries bl-110.

Area: a new `cli/src/main/kotlin/loop/cli/DrainCommand.kt` registered in `Main.kt`, the run file of `loop stop` (`StopCommand.kt`, `RunFile.kt`) as the way to reach the live run, the dispatch and retry start in the orchestrator (call sites only), `loop status`, new events, docs.

What is known:

- **bl-110.** Loop has `loop run` and `loop stop`, nothing between. On the night of 2026-11-15 (run 284454f4), to let two running issues finish while starting nothing new, the operator removed the `ready-for-agent` label of the one issue they would unblock and restored it by hand later. A zero per state cap does not work: `max_concurrent_agents_by_state` ignores values below 1. The need comes back at each session end, before a reinstall and before a planned stop.
- **Decision taken in the M4 spec:** draining is a new run state. `loop drain [WORKFLOW.md]` marks the live run as draining, through the same run file `loop stop` uses. From then on no candidate is dispatched. Running attempts, their verifications, CI waits, merge checks and merges finish. Then the run stops by itself, with exit code 0.
- **Answers to bl-110's open points, for this issue:**
  - An error or continuation retry of an issue whose attempt was running when the drain began still runs; it is the same work (that is what the night wanted, confirmed by soule on 2026-11-18). A retry of any other issue does not start.
  - Waits that need a human (`Waiting(approval)`, `needs_info`) do not hold the drain. They stay in the store and resume at the next `loop run`.
  - Machine slots (m3-16) are released as usual; nothing new claims one.
  - Events `run_draining` (when the drain began, what still runs) and `run_drained` (when the run stops). bl-109's notifications (m4-30) will fire on `run_drained`.
  - `loop status` shows the run as draining, with what still runs.
  - `loop drain` with no live run says so and exits non zero, like `loop stop`. `loop stop` during a drain still stops at once.
  - A drain waits for a running CI wait, which can last up to `ci_timeout_ms` (45 minutes by default). `loop stop` is the way out.

Lane A rule: the drain state and its rules go in a new file in `loop.core.orchestrator` (for example `Drain.kt`); the `Orchestrator.kt` edit is limited to call sites.

Out of this issue: `loop stop --drain` as an alias, a drain of one tracker only.

## Acceptance criteria

- [ ] `loop drain` marks the live run draining, after which no new candidate is dispatched and the running attempt finishes, verifies and delivers, then the run exits 0 (CLI test with the command runner).
- [ ] A retry of an issue that was running when the drain began still runs, and a due retry of another issue does not (orchestrator tests).
- [ ] An issue in `Waiting(approval)` does not hold the drain and is still waiting at the next `loop run` (test).
- [ ] `run_draining` and `run_drained` are recorded and printed by `loop trace`, and `loop status` shows the drain (tests).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.StatusCommandTest'` passes, with the draining case.
- [ ] `loop drain` with no live run exits non zero with a message, and `loop stop` during a drain stops at once (tests).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.StopCommandTest'` passes, with the stop during a drain case.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.RunDrainTest'` passes, a new test class of the drain rules.
- [ ] `docs/spec-decisions.md` and `docs/self-hosting.md` describe the drain, say that it waits for a running CI wait of up to `ci_timeout_ms` (45 minutes by default) and that `loop stop` is the way out; plan 6.3 lists the events (judgment).
- [ ] The drain logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-24 02:34Z loop: Loop opened pull request #34 for this issue: https://github.com/soulesidibe/Loop/pull/34

2026-11-24 02:44Z loop: Loop did not merge pull request #34 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1192 lines, over the limit of 1000
  Merge risk: not assessed, a condition failed.
  Once approved, Loop runs the merge check again and merges.
