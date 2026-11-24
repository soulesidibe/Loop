---
id: m4-16
title: "Cross tracker blockers from a Blocked by line in the body"
state: todo
priority: 1
labels: [deferred]
blocked_by: [m4-15]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 116. Lane A, runs on the claude-code runner. PLAN 6.1: native `blocked_by` within a tracker; across trackers, a line in the body such as `Blocked by lin:MKT-12`.

Area: `loop.core.trackers` (the body line parser and the blocker check), the eligibility check of the dispatch in the orchestrator (call site only, beside the native `blockedBy` of `plugin-api/.../Issue.kt`), the by id reads of the registry, `candidate_skipped` reasons, docs.

Decisions for this issue:

- A blocker line is a column 0 line of the issue description that reads `Blocked by ` followed only by one or more `<word>:<id>` items, separated by commas, whatever the tracker ids. Any other `Blocked by` line is prose and ignored: Loop's own issues hold lines such as "Blocked by bl-54 so the two never run side by side" (`bl-61`, `bl-48`), which must not change anything. Lines in fenced code blocks are ignored.
- An id whose tracker is not in the registry keeps the issue blocked, fail closed, with reason `blocked_cross_tracker` naming the unknown tracker (decided by soule on 2026-11-18). A typo or a tracker removed from the workflow (`Blocked by lin:MKT-12` with no `lin`) must never unblock an issue in silence.
- The issue is blocked while any named blocker is not in a terminal state of its own tracker. The dispatch records `candidate_skipped` with reason `blocked_cross_tracker`, naming the blocker, once per change as for other skips.
- A blocker that cannot be read (its tracker fails, or the id is not found) keeps the issue blocked, fail closed, with the reason in the skip.
- Blocker reads are by id, at most once per tick per blocker, through the registry and the shared hold of m4-48.
- Within a tracker, the native `blocked_by` is unchanged.

Lane A rule: the parser and the check live in `loop.core.trackers`; the `Orchestrator.kt` edit is limited to one call site in the eligibility check.

Out of this issue: blocker lines in the GitHub tracker's native fields, a Linear tracker (M7).

## Acceptance criteria

- [ ] The parser reads `Blocked by gh:12` and `Blocked by gh:12, local:m4-03`, and ignores prose `Blocked by` lines and lines in fenced code blocks (table test).
- [ ] An id whose tracker is not in the registry keeps the issue blocked with `candidate_skipped reason=blocked_cross_tracker` naming the unknown tracker (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.CrossTrackerBlockersTest'` passes, a new test class of the parser and the check.
- [ ] A `files` issue blocked by an open GitHub issue on recorded answers is skipped with `candidate_skipped reason=blocked_cross_tracker`, and dispatched once the blocker reads closed (CLI or orchestrator test with two trackers).
- [ ] A blocker that cannot be read keeps the issue blocked, with the reason in the skip (test).
- [ ] Each blocker is read at most once per tick, whatever the number of issues naming it (test counting tracker calls).
- [ ] The prose `Blocked by` lines of Loop's own issues change no eligibility (test on `bl-61`'s line).
- [ ] `docs/spec-decisions.md` and `docs/agents/issue-tracker.md` show the line format and the fail closed rule for an unknown tracker (judgment).
- [ ] The check lives in `loop.core.trackers` and the `Orchestrator.kt` diff is one call site (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
