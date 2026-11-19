---
id: m4-17
title: "Minimal area lock, from front matter and a GitHub Area line"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-16]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 117. Lane A, runs on the claude-code runner. Carries bl-10 (the minimal lock). The fetch mutex of bl-67 is m4-49. The exit (m4-43) runs two code issues in parallel on the pilot app, so two issues of one area must never run at once.

Area: the `Issue` model in `plugin-api/src/main/kotlin/loop/api/Issue.kt` (a new `area` field, default empty), the `files` tracker's front matter reading (`plugins/tracker-files/.../FilesTracker.kt`), the GitHub tracker's body reading (`plugins/tracker-github/.../GitHubTracker.kt`), a new file in `loop.core.trackers` for the overlap rule, the dispatch's skip (`candidate_skipped`, recorded around line 789 of `Orchestrator.kt`), docs.

What is known:

- **bl-10.** Claims stop one issue running twice, but nothing stops two issues that edit the same files. m1-17 and m1-34 conflicted when they merged out of order, and run 4672d0bf lost attempts to `Orchestrator.kt` conflicts. Principle 2: locks are deterministic code, no model.
- **Decision taken in the M4 spec:** an optional `area` front matter field; a candidate whose area is busy is skipped with `candidate_skipped reason=area_busy`. bl-10's triage decisions of 2026-11-13 were reopened on 2026-11-14. soule confirmed the four below on 2026-11-18 as the minimal lock of M4, and anything wider stays for M6 triage:
  - `area` is a list of repository relative paths (a folder or a file), no globs. Two areas overlap when an entry equals another or is a parent folder of it, on whole path segments after normalizing (`spec-core` is not a parent of `spec-core-x`).
  - Overlap is computed on declared areas only, never on files an attempt changed so far.
  - The area is busy while its issue is claimed: running, waiting while verification runs, or queued for a retry. It frees when the claim is released; conflicts after that are the merge check's job.
  - No reordering: a skipped candidate keeps its place and is dispatched on the first tick its area is free.
  - An issue with no `area` never blocks and is never blocked.
- **GitHub, needed by the exit on the pilot app, confirmed by soule on 2026-11-18:** a tracker without front matter carries the area as a column 0 `Area:` line in the body, comma separated paths, read by the GitHub tracker into the same field. A malformed value is a malformed issue on `files` and is ignored with a warning on GitHub.
- `CONTEXT.md` is protected: the Area entry goes under `residual_human_steps` with its proposed text.

Out of this issue: filling `area` on existing issues (the operator's call), areas from M6 triage, the fetch mutex (m4-49).

## Acceptance criteria

- [ ] The `files` tracker reads `area` into the issue model, a malformed value making a malformed issue, and the GitHub tracker reads an `Area:` body line, ignoring a malformed one with a warning (tests).
- [ ] `./gradlew --no-daemon :plugins:tracker-files:test :plugins:tracker-github:test` passes.
- [ ] Overlap is equality or a parent folder on whole segments after normalizing (unit table test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.AreaOverlapTest'` passes, a new test class of the overlap rule.
- [ ] A candidate whose area overlaps a claimed issue's area is skipped with `candidate_skipped reason=area_busy`, naming the holder, and an issue with no `area` never blocks and is never blocked (orchestrator tests with fakes).
- [ ] The area stays busy while the holder runs, verifies or waits for a retry, and frees when the claim is released; the skipped candidate dispatches on the first free tick in its normal order (tests).
- [ ] `docs/spec-decisions.md` describes the lock and `docs/agents/issue-tracker.md` shows the field; the Area entry for `CONTEXT.md` is under `residual_human_steps` (judgment).
- [ ] The overlap rule lives outside `Orchestrator.kt`, whose diff is limited to the skip call site (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
