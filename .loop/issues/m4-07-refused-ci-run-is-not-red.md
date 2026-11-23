---
id: m4-07
title: "A CI run GitHub refused to start is not started, not red"
state: todo
priority: 2
labels: [deferred]
blocked_by: [m4-06]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 107. Lane A, runs on the claude-code runner. Carries bl-98. Touches CI: proven on the recorded answers of run 43779352010 only, since Actions refuses every job for billing since 2026-11-16.

Area: `CiWait.verdict` in `spec-core/src/main/kotlin/loop/core/delivery/CiWait.kt` (around lines 95 to 115) and its comment lines, the runs reader in `plugins/tracker-github` (a jobs read for failed runs), the recorded answers in `plugins/tracker-github/src/test/resources/github/recorded/`, the `ci_finished` outcomes, the CI wait call site in the orchestrator, docs.

What is known:

- **bl-98.** At 00:17 on 2026-11-16, bl-85's merged head got run 43779352010, which GitHub refused to start for a billing reason (a job with 0 steps). Loop recorded `ci_finished outcome=red` and `delivery_failed step=ci`, and put the issue back in the approval wait as if the code were broken. All 18 billing refusals of that night look the same: conclusion `failure`, a job with 0 steps.
- **Detection:** a completed run whose every job has zero steps is "not started". The runs listing does not show steps, so the reader fetches `GET /repos/<repo>/actions/runs/<id>/jobs` only for failed runs, at most 10 per poll.
- **Decision for this issue** (confirmed by soule on 2026-11-18), answering bl-98's "what `ci: actions` and `ci: none` do when Actions is unavailable":
  - Under `ci: actions`, a not started run ends the wait with `ci_finished outcome=not_started`, `delivery_failed` and an unapproved `Waiting(approval)`, like red mechanically, but the comment and the log say that GitHub did not start the run (billing or limits) and that the code was not judged. It never counts as no run, so `ci_no_run: pass` cannot merge work nobody built.
  - Under `ci: none`, m4-05 already reads no runs, so nothing changes.
  - `not_started` is a distinct outcome everywhere it is read. No later consumer may treat it as red: no reaction fires on it, m4-35's `ci_failed` first, so a billing refusal never goes back to the agent as broken code.
- **Recorded answers, a human step:** the agent's generated settings deny `gh` (`ClaudeSettingsFile.kt:37`), so soule records the run and its jobs in m4-00 (step 6) before this issue is dispatched: `actions-run-43779352010.json` and `actions-run-43779352010-jobs.json` under `plugins/tracker-github/src/test/resources/github/recorded/`. The tests read those files. If they are missing, stop and move the issue to `needs_info`; never build them by hand.

Out of this issue: a rerun of refused runs, commit statuses (bl-60), the double CI run (bl-91).

## Acceptance criteria

- [ ] The tests read soule's recordings of run 43779352010 and its jobs from m4-00, unchanged (judgment).
- [ ] A completed run whose every job has zero steps is `not_started`, a failed run with steps stays red, and the jobs are read only for failed runs, at most 10 per poll (table test of the verdict).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.delivery.CiWaitTest'` passes, with the `not_started` table cases.
- [ ] Under `ci: actions`, the recorded refused run ends the wait with `ci_finished outcome=not_started`, `delivery_failed` and an unapproved `Waiting(approval)`, and the comment says GitHub did not start the run and the code was not judged (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.CiWaitOrchestratorTest'` passes, with the recorded refused run case.
- [ ] `not_started` is its own value wherever the CI outcome is read, never folded into red (test that the outcome type keeps it distinct, and judgment for the readers).
- [ ] A not started run never counts as no run, under `ci_no_run: pass` too (test).
- [ ] A 403 or 404 on the jobs read gives `delivery_failed` naming "Actions: read", as m3-15 does for the runs listing (test).
- [ ] `loop trace` prints the `not_started` outcome, and `docs/spec-decisions.md` (CI wait) lists it (judgment for the docs).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
