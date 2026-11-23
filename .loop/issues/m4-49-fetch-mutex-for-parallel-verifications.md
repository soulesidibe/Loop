---
id: m4-49
title: "A fetch mutex per repository for parallel verifications"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-01]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 117, fetch part. Lane C, a small separate issue in `plugins/tracker-github` only. Carries bl-67 (the fetch part). Premise, checked as the first criterion: no Gradle edit needed, since the mutex needs no new library. The exit (m4-43) runs two code issues in parallel on the pilot app, so two verifications must never collide on a fetch. m4-43 is blocked by this issue.

Area: `GitPush.fetch` in `plugins/tracker-github/src/main/kotlin/loop/tracker/github/GitPush.kt` (around line 98), its test `GitPushTest`, docs.

What is known:

- **bl-67, fetch part.** `GitPush.fetch` has no mutex: two verifications at once fetch and write the same ref, and a lock collision degrades one to `diff_base_unpinned`, which blocks auto merge. One mutex per repository serializes base fetches.
- Repositories are told apart by their normalized path, so two workspaces of one repository share a mutex and two repositories do not wait on each other.

Out of this issue: the area lock (m4-17), the other bl-67 findings (the agent triggerable `update-ref` lock, unpushed commits on local main, fetch noise, `pinnedBase` reading only the code kind), which stay closed as accepted lows.

## Acceptance criteria

- [ ] Premise checked: no Gradle edit needed, the work touches only `plugins/tracker-github` sources, tests and docs (judgment).
- [ ] Two concurrent `GitPush.fetch` calls on one repository run one after the other, and neither verification records `diff_base_unpinned` (test).
- [ ] Fetches of two different repositories do not wait on each other (test).
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test --tests 'loop.tracker.github.GitPushTest'` passes, with the two concurrency cases.
- [ ] `docs/plugins/tracker-github.md` mentions the fetch mutex (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-17 after the Fable 5.2 review.

2026-11-23 05:33Z loop: Loop opened pull request #21 for this issue: https://github.com/soulesidibe/Loop/pull/21
