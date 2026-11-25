---
id: m4-17
title: "Minimal area lock, from front matter and a GitHub Area line"
state: done
priority: 1
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

2026-11-24 22:47Z loop: Loop opened pull request #50 for this issue: https://github.com/soulesidibe/Loop/pull/50

2026-11-24 22:51Z loop: Loop did not merge pull request #50, ci: Actions runs did not pass: build (failure). The pull request stays open and the result waits for a new approval.
  Failed runs:
  - build (failure): https://github.com/soulesidibe/Loop/actions/runs/45504270631
  Rerun the runs on GitHub or fix the branch, then approve again with loop approve.

2026-11-24 23:15Z soule: sent back before merge, after the merge risk reviews of pull request #50 (Sonnet 5.5 low 1.27, Fable 5.2 medium 1.67). Fix these on the same branch, then submit again:
  - **Medium. A due retry skipped as `area_busy` makes the tick loop spin.** It stays in the queue with its due time in the past (`fireDue` keeps it "unchanged in the queue"), is filtered out at `Orchestrator.kt` around line 709 and never goes through `requeueNoSlot`, which moves the due time for no slot and budget skips. `sleepUntilNextPoll` then sleeps 1 ms (`Orchestrator.kt` around line 1044, `RetryQueue.nextDueAt` around line 333), so Loop polls the tracker back to back until the other holder ends, burning the GitHub rate limit shared with delivery. Requeue an area skipped due retry like a no slot one, and add an orchestrator test: two holders with overlapping areas, the due retry of one is skipped, the next poll waits the normal interval and the retry runs once the area frees. Check whether a due retry held by a cross tracker blocker (m4-16) has the same gap and fix it the same way if so.
  - **Low. A candidate admitted but given no slot still blocks later overlapping candidates**, with `area_busy` naming an issue that is not running (`AreaLock.kt` around lines 66 to 86). Only issues that get a slot, or are already holders, should block.
  - **Low. Area matching fails open on case and form.** `Spec-Core` and `spec-core`, a unicode variant, spaces inside a segment: normalize entries (case fold per the repository's case sensitivity at least for the comparison, NFC) and cap the number of entries per issue.
  - **Low. `warnedAreas` grows with each body edit** (`GitHubTracker.kt` around line 388), and an untrusted body's malformed line is quoted in an operator warning. Bound the set and do not quote untrusted text.
  - **Low. An `Area:` line inside an HTML comment counts.** Skip comments, like fences.
  - **Low. Prose `Area:` lines.** Loop's own issue style has a column 0 `Area:` prose line (46 files in `.loop/issues`). On GitHub such a line is either malformed, which drops the whole area with a warning, or parsed into bogus entries with no warning. Document the expected form in `docs/spec-decisions.md` and make prose that is not a path list a malformed line with one warning.
  - **Low. One malformed `Area:` line drops every `Area:` line of the body** (`GitHubArea.kt` around lines 44 and 45). Keep it if the spec requires it, but say so in the warning.
  - **Low. Tests.** Add an orchestrator level test for "a skipped due retry stays queued" (only unit tested in `AreaLockTest`).

2026-11-24 23:32Z loop: Loop updated pull request #50 for this issue: https://github.com/soulesidibe/Loop/pull/50

2026-11-24 23:36Z loop: Loop did not merge pull request #50, ci: Actions runs did not pass: build (failure). The pull request stays open and the result waits for a new approval.
  Failed runs:
  - build (failure): https://github.com/soulesidibe/Loop/actions/runs/45510831282
  Rerun the runs on GitHub or fix the branch, then approve again with loop approve.
