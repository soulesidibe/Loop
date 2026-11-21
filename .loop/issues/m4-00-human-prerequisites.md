---
id: m4-00
title: "Human prerequisites: parallel cli tests, CI timeout, ci: actions back in the live checkout, the workspace_reused prompt step, two test recordings"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: []
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Lane: human. Every step edits a protected path (Gradle files, `WORKFLOW.md`) or the CI workflow, or needs what an agent cannot reach (`gh`, `~/.loop/attempts/`), so soule does it on main before the M4 chain starts. Carries bl-142 and the human step of bl-129.

Why first: `:cli:test` takes 22.1 minutes in one fork and reruns on any core, plugin or `plugin-api` fixture change (bl-142). Every lane A issue of M4 pays it at verification and again at each merge check when main moved.

Steps, in this order:

1. **Check bl-07 first.** `loop run` already stalled about 60 seconds in cli tests under parallel Gradle load (bl-07, CPU starvation of the niced test JVMs suspected, never diagnosed). Before raising the forks, run `:cli:test` with the forks at least five times and watch for `loop run --once did not exit in 60s`. If a stall shows, apply bl-07's diagnostic first (the safepoint `JAVA_OPTS` in the fixture, `ni,%cpu,rss` in `LoopFixture.dumpHang`) and follow its triage decisions. Watch the tests that share a machine wide resource, `MachineLimitsCommandTest` first (the machine cap of m3-16): if one does not tolerate forks, give it its own `LOOP_HOME` or move it to a serial task.
2. **Parallel forks.** In `cli/build.gradle.kts`, `tasks.test`, set `maxParallelForks` (for example half the cores, at least 1). Record the wall time of `:cli:test` before and after. Splitting the end to end tests into their own task is the fallback if forks do not help.
3. **CI timeout.** `.github/workflows/build.yml` has `timeout-minutes: 20`, below the 22 minute local run. Raise it to 45, and check it on the first Actions run of the public repository.
4. **`ci: actions` back in the live checkout.** Actions refused every job for billing from 2026-11-16, so the live shared checkout ran `WORKFLOW.md` with `ci: none` uncommitted. Since the repository went public its Actions minutes are free, so the committed `ci: actions` (around line 90) stays. Drop the uncommitted change from the live checkout once a first Actions run of the public repository is green.
5. **bl-129's prompt step.** `WORKFLOW.md` line 106 reads `{% if attempt %}`. Apply the agent's proposed text from `residual_human_steps` in `~/.loop/attempts/6808d62a9d73e805/f441617d-2299-45ba-80ed-7d6aa725dd18.result-1.json`: the condition `{% if attempt or loop.workspace_reused %}`, the reworded earlier work block ("if it exists"), the same edit in the golden copy `spec-core/src/test/resources/prompt/own-template.md`, and an `OwnWorkflowTest` case.
6. **Record run 43779352010 for m4-07.** The agent's generated settings deny `gh` (`ClaudeSettingsFile.kt:37`), so m4-07 cannot make this recording. The run belongs to the archived private repository, so run `gh api repos/soulesidibe/Loop-private/actions/runs/43779352010` and `gh api repos/soulesidibe/Loop-private/actions/runs/43779352010/jobs`, redact both answers like the other recordings, and commit them as `actions-run-43779352010.json` and `actions-run-43779352010-jobs.json` under `plugins/tracker-github/src/test/resources/github/recorded/`, with a line on how they were made in that folder's `README.md`.
7. **Copy the wake turn lines for m4-03.** The attempt files live under `~/.loop/attempts/`, outside every workspace, so m4-03 cannot read them. Copy the runner lines of attempt 10c2f759 (the second pilot app issue), redacted, into `plugins/runner-claude-code/src/test/resources/streams/wake_turns_10c2f759.jsonl`, and say in the commit message how it was redacted.

Out of this issue: bl-129's low findings (`workspaceReused = false` defaults in `PromptSlots.kt:60` and `PromptVariables.kt:45`, the missing retry and failed `after_create` tests), and a full bl-07 fix when no stall shows.

## Acceptance criteria

- [ ] bl-07 was checked before the forks were raised: at least five forked runs of `:cli:test`, or the diagnostic run, with the result in a comment on this issue (judgment).
- [ ] `grep -q maxParallelForks cli/build.gradle.kts` passes.
- [ ] Three runs in a row of `./gradlew --no-daemon :cli:test --rerun` are green, with the wall time before and after the forks in a comment (judgment).
- [ ] `grep -q 'timeout-minutes: 45' .github/workflows/build.yml` passes, and the first Actions run after it finishes inside the limit (judgment).
- [ ] `grep -qE '^ +ci: actions' WORKFLOW.md` passes, and the live shared checkout has no uncommitted `WORKFLOW.md` change.
- [x] `grep -q 'loop.workspace_reused' WORKFLOW.md && grep -q 'loop.workspace_reused' spec-core/src/test/resources/prompt/own-template.md` passes.
- [x] `./gradlew --no-daemon :cli:test --tests 'loop.cli.OwnWorkflowTest'` passes, with the `workspace_reused` case.
- [x] `test -s plugins/tracker-github/src/test/resources/github/recorded/actions-run-43779352010.json && test -s plugins/tracker-github/src/test/resources/github/recorded/actions-run-43779352010-jobs.json` passes.
- [x] `test -s plugins/runner-claude-code/src/test/resources/streams/wake_turns_10c2f759.jsonl` passes.
- [x] Both recordings are redacted, and how each was made is written down, in the recordings' `README.md` for the run and in the commit message for the runner lines (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-21 soule: steps 5 to 7 are done, steps 1 to 4 remain.
  Step 5: the earlier work block of `WORKFLOW.md` and `own-template.md` opens on `{% if attempt or loop.workspace_reused %}` with the text bl-129 proposed, and `OwnWorkflowTest` has the reused workspace case. The golden output is unchanged.
  Step 6: run 43779352010, its jobs and the runs listing of its head are recorded with `gh api -i` from `soulesidibe/Loop-private`, emails replaced by ones at `example.com`, as the recordings' `README.md` says.
  Step 7: `wake_turns_10c2f759.jsonl` holds the 365 runner lines of the attempt, redacted by an allowlist as its commit message says, and `wake_turns_10c2f759.turns.json` gives each line's arrival time and where Loop's six inputs fall.
  The Fable 5.2 reviews found that m4-03's replay criterion does not match the recording (bl-170) and that the block's heading is wrong in a reused workspace (bl-171).
  `./gradlew --no-daemon build` passed on the branch before the merge of main; the full build criterion stays open for the steps that remain.
