---
id: m4-08
title: "A lost network is runner_unavailable and uses no attempt"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-07]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 108, first half. Lane A, runs on the claude-code runner. Carries bl-123 and the failure after sleep decision of bl-156 (the sleep assertion itself is m4-09). The resume of the session after the hold is m4-46.

Area: `RunnerAccess` in `plugins/runner-claude-code/.../StreamJsonMapper.kt` (the bl-39 patterns around lines 86 to 108, `RunnerUnavailable` at around line 186), the Codex runner's turn failures (`CodexAppServerRunner.kt` around lines 630 to 636, which set no category today), the hold in `spec-core/.../orchestrator/RunnerHold.kt` and the requeue in `RetryCoordinator.kt`, recorded fixtures, docs.

What is known:

- **bl-123.** On 2026-11-16 between 12:32 and 12:48 UTC the machine lost its connection. Claude Code ended the turn with `API Error: Can't reach the API server (...) check your internet or DNS (ENOTFOUND)`. Loop recorded `turn_failed` and failed the attempt for both running issues: bl-117 after 7.3M tokens and $3.78 of work, m3-07 after $0.41. bl-39 already made auth failures a `runner_unavailable` that holds dispatch and takes no `max_attempts` slot; a network error is the same kind of failure.
- **Classification:** `ENOTFOUND`, `ECONNREFUSED`, `ETIMEDOUT`, `ECONNRESET` and the "Can't reach the API server" result are `runner_unavailable` for the Claude Code runner, and the Codex runner's equivalent transport errors for Codex. A recorded fixture of the Claude Code result line pins it.
- **After the hold, until m4-46:** the next attempt starts a fresh session with the session brief, as a rotation does. m4-46 adds the resume of a session below the soft limit.
- **bl-156, decided by soule on 2026-11-18, option (a):** the m3-27 run lost an attempt to an idle sleep: the Mac slept at 10:10Z, the turn failed at 10:21Z with `ENOTFOUND`, and the retry started at 12:48Z. A failure after a sleep surfaces as a network error, so the classification above already makes it take no slot. Nothing more is built: no sleep detection from the wall and monotonic clocks. The reason goes in `docs/spec-decisions.md`. If a recorded sleep failure is ever not network shaped, a backlog issue reopens it.

Lane A rule: the classification stays in the runner modules; the `Orchestrator.kt` edit, if any, is limited to call sites.

Out of this issue: the resume after the hold (m4-46), the sleep assertion (m4-09), sleep detection from the clocks, the wider resume question of bl-151, a failed fetch of main on the next attempt (it already falls back to the last fetched main).

## Acceptance criteria

- [ ] A recorded Claude Code `ENOTFOUND` result line and the other network patterns give `turn_failed` with category `runner_unavailable` (fixture test).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.StreamJsonMapperTest'` passes, with the network fixture cases.
- [ ] A Codex transport failure gives `runner_unavailable` (test).
- [ ] `./gradlew --no-daemon :plugins:runner-codex-app-server:test --tests 'loop.runner.codex.CodexAppServerRunnerTest'` passes, with the transport failure case.
- [ ] A network failure takes no `max_attempts` slot and holds dispatch with `runner_hold_started`, as bl-39 does for auth, and the next attempt after the hold starts fresh with the session brief (orchestrator test with the fake runner).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.OrchestratorRunnerHoldTest'` passes, with the network case.
- [ ] `docs/spec-decisions.md` records the sleep rule (a) with its reason, and `docs/spec-decisions.md` and `docs/self-hosting.md` describe network loss (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-23 23:48Z loop: Loop opened pull request #30 for this issue: https://github.com/soulesidibe/Loop/pull/30
