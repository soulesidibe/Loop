---
id: m4-09
title: "loop run keeps the Mac awake while attempts run"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-01]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 109. Lane C, a small separate issue in `cli`, blocked by m4-01 like every lane C issue. Carries bl-156 (the sleep assertion; the rule for a failure after a sleep is m4-08). m4-10 edits `RunCommand.kt` and `docs/self-hosting.md` after this issue and is blocked by it. Premise, checked as the first criterion: no Gradle edit needed, since the assertion is a `caffeinate` child process and needs no library.

Area: `cli/src/main/kotlin/loop/cli/RunCommand.kt` (where the run starts and stops, and its shutdown hook), a new file in `cli` (for example `KeepAwake.kt`), the `RunObserver` interface in `spec-core/.../orchestrator/RunObserver.kt` (read only: `polled` gives the running attempt count, `recorded` every event), `docs/self-hosting.md`.

What is known:

- **bl-156.** During attempt 10c2f759 of the second pilot app issue the Mac entered idle sleep on battery at 10:10Z (`pmset -g log`). The turn failed at 10:21Z with `ENOTFOUND`, and the error retry due at 10:21:28Z started at 12:48:33Z, after the wake. With `retries.max_attempts: 2` the sleep spent one of the two attempts and 2.5 hours of wall time. Nothing in `loop run` holds a sleep assertion; the operator can wrap it in `caffeinate -i`, which nobody remembers.
- **Decision taken in the M4 spec:** `loop run` holds an idle sleep assertion on macOS while at least one attempt, verification or merge check runs, and releases it when none does, so an idle overnight poll still lets the Mac sleep.
- **How:** a `RunObserver` in `cli` follows the running count and starts `caffeinate -i -w <loop pid>` when it goes above zero, then stops that child when it falls to zero. `-w` ends the assertion if `loop run` dies. On other systems, or when `caffeinate` is missing, Loop logs one line and goes on.
- No orchestrator edit: the observer is wired where `RunCommand` builds the orchestrator. If `polled` alone does not cover verifications and merge checks, read their start and end events through `recorded`.

Out of this issue: the rule for a failure after a sleep (m4-08), sleep on lid close or on a forced sleep, which no assertion prevents.

## Acceptance criteria

- [ ] Premise checked: no Gradle edit needed, the work touches only `cli` sources, tests and docs (judgment).
- [ ] The assertion starts when the first attempt starts and stops when the last attempt, verification or merge check ends (test with a fake process launcher in place of `caffeinate`).
- [ ] The `caffeinate` child gets `-i -w <loop pid>`, and it is stopped by the shutdown hook when `loop run` ends (test on the spawned command line and the shutdown path).
- [ ] On a system without `caffeinate`, `loop run` logs one line and runs as before (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.RunCommandTest'` passes, or the test class the agent adds for the observer.
- [ ] `docs/self-hosting.md` says that `loop run` keeps the Mac awake while work runs, and that a lid close still sleeps (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-23 02:46Z loop: Loop opened pull request #19 for this issue: https://github.com/soulesidibe/Loop/pull/19

2026-11-23 03:11Z loop: Loop did not merge pull request #19: main at d08267864c593417c6d07659d2073a552a959151 was merged into loop/m4-09, but the checks failed on the result (a check failed). The merge commit stays on the branch. The next attempt fixes the result and submits it again.

2026-11-23 04:46Z loop: Loop updated pull request #19 for this issue: https://github.com/soulesidibe/Loop/pull/19
