---
id: m4-35
title: "The ci_failed reaction, proven on recorded Actions answers"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-34]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 132. Lane A, run on the claude-code runner. Not gating the exit.

Carries bl-89, "A red CI cannot be sent back to the agent with its findings". What is known from it: since m3-15, a red Actions run on the merged head gives `delivery_failed`, a comment naming the failed runs, the pull request left open and an unapproved `Waiting(approval)`, with no dispatch (m3-15, note 8). bl-89 proposed either a `loop reject` command, now `loop send-back` (m4-32), or an opt in setting that sends a red CI back on its own, bounded. This issue is the second direction.

- **Config:** `reactions.ci_failed: { action: send_to_agent, retries: 2 }`, parsed by m4-34. With it, a red verdict from `CiWait.verdict` (`spec-core/src/main/kotlin/loop/core/delivery/CiWait.kt`) on a watched pull request (m4-34) resumes the issue's workspace with a continuation turn, `loop.retry_kind` = `reaction`, then verifies again.
- **Findings:** the failed runs and their URLs (the lines of `CiWait.failedRunLines`), and the failed job and step names from `GET /repos/<repo>/actions/runs/<id>/jobs`, the read m4-07 records. The agent cannot open the run URLs, the reason m3-15 chose no dispatch, so the job and step names are what it can act on.
- **Bound:** `retries` counts reactions of this kind per issue, kept in the run store across a restart. Each reaction attempt also takes a `max_attempts` slot (m4-34). Past `retries`, Loop falls back to today's path (`delivery_failed`, comment, unapproved `Waiting(approval)`) and says the retries are spent.
- **A not started run is not red:** a run GitHub refused to start (billing, 0 steps) ends the CI wait with `ci_finished outcome=not_started` since m4-07. The reaction reads only a red verdict, so `not_started` never triggers `ci_failed`, whatever `retries` holds. Proven on recorded run 43779352010.
- **Under `ci: none`:** no Actions run is read, so the reaction never fires; preflight logs one warning naming it.
- **Proof:** GitHub Actions refuses every job for billing since 2026-11-16, so every test runs on recorded Actions answers, never a live run.
- **Logic** in `loop.core.reactions`. `Orchestrator.kt` gets call sites only.

Out of this issue: reading CI logs (the agent cannot), an automatic rerun on GitHub, a timeout or a failing no run rule (they keep today's path).

## Acceptance criteria

- [ ] With `ci_failed: { action: send_to_agent, retries: 2 }`, a red verdict resumes the workspace with `loop.retry_kind` = `reaction` and findings naming the failed runs, their URLs and the failed job and step names, then verifies again (test on recorded Actions answers).
- [ ] After `retries` reactions for one issue, a red verdict gives today's `delivery_failed` path and a comment saying the retries are spent; the count survives a restart (tests).
- [ ] Recorded run 43779352010 (billing refusal, 0 steps), read as `ci_finished outcome=not_started`, never triggers `ci_failed` (test).
- [ ] Without the reaction, a red verdict keeps today's path unchanged (test).
- [ ] Under `ci: none`, the reaction never fires and preflight logs one warning naming it (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.reactions.*'` passes.
- [ ] The logic lives in `loop.core.reactions`, and the diff of `Orchestrator.kt` holds call sites only (judgment).
- [ ] `docs/spec-decisions.md` describes `ci_failed`, its bound and the `not_started` rule, replacing note 8 of the CI wait where the reaction is set (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
