---
id: m4-05
title: "The auto merge ci condition follows the kind's ci setting"
state: todo
priority: 2
labels: [deferred]
blocked_by: [m4-04]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 105. Lane A, runs on the claude-code runner. Carries bl-146. Touches CI: proven on recorded Actions answers only, since Actions refuses every job for billing since 2026-11-16.

Area: `AutoMerge.ciFailure` and `ciReason` in `spec-core/src/main/kotlin/loop/core/delivery/AutoMerge.kt` (around lines 108 to 165), the facts gathering in `spec-core/src/main/kotlin/loop/core/merge/AutoMergeGate.kt` (around lines 180 to 200), their tests (`AutoMergeTest`, `AutoMergeOrchestratorTest`), docs. No orchestrator edit is expected.

What is known:

- **bl-146.** Under `merge.ci: none` the merge waits for no run, but the auto merge gate still reads the merged head's Actions runs. With no run it fails with "no Actions run is registered on the merged head ... while the merged head holds the workflows .github/workflows/build.yml", so no result merges on its own while Actions is down, for any runner. Seen in m3-28 and again in m3-27 (bl-136, pull request #115; bl-132, pull request #117).
- **Decision taken in the M4 spec,** answering bl-146's triage question: the `ci` condition reads Actions runs only under `ci: actions`. Under `ci: none`, "green" means the merge check passed: the condition reads no runs, no base tip runs and no workflow files.
- Under `ci: none`, a mergeable state of `unstable` (failing checks GitHub reports, such as the billing refused runs of bl-98) no longer fails the condition. bl-146 does not ask for this; soule confirmed it on 2026-11-18. `dirty`, `blocked`, `behind`, an unknown mergeability and `mergeable: false` still fail it, since they are not CI.
- Under `ci: actions` nothing changes: m3-15's verdict function, `ci_no_run` and the "Actions: read" failure stay.
- Tests: existing tests that pin the old `ci: none` reading change their expectations and keep their names. The `deleted_tests` and `weaker_assertions` guards refute a removed test or a weakened assertion, so a test that no longer applies is listed under `residual_human_steps` for removal, not deleted (the lesson of m3-15).

Out of this issue: a refused run read as not started (m4-07), the auto merge gate refactor of bl-96.

## Acceptance criteria

- [ ] Under `ci: none`, the `ci` condition passes once the merge check passed, with no Actions runs, no base tip runs and no workflow files read (table test of `ciFailure`, and a gate test that asserts no runs request is sent).
- [ ] Under `ci: none`, `unstable` passes the condition, while `dirty`, `blocked`, `behind`, unknown mergeability and `mergeable: false` still fail it (table test).
- [ ] Under `ci: actions`, the condition behaves as in m3-15: green, red, pending, `ci_no_run` and a 403 naming "Actions: read" (existing tests, unchanged names).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.delivery.AutoMergeTest'` passes, with the `ci: none` table cases.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.AutoMergeOrchestratorTest'` passes, with a case where a `ci: none` result with no Actions run on the merged head auto merges on recorded answers.
- [ ] No test name disappears and no assertion is weakened; a test that no longer applies is listed under `residual_human_steps` (judgment).
- [ ] `docs/spec-decisions.md` (auto merge, `ci: none`) and `docs/self-hosting.md` ("When auto merge falls back") say what "green" means under each `ci` value (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
