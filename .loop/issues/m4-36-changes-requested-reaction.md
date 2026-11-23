---
id: m4-36
title: "The changes_requested reaction with escalate_after"
state: todo
priority: 3
labels: [deferred]
blocked_by: [m4-35]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 133. Lane A, run on the claude-code runner. Not gating the exit.

Loop reads no pull request review today: `GitHubPullRequests` (`plugins/tracker-github/src/main/kotlin/loop/tracker/github/GitHubPullRequests.kt`) has `mergeability` and `actionsRuns`, and `DeliveryExecutor` (`plugin-api/src/main/kotlin/loop/api/DeliveryExecutor.kt`) mirrors them.

- **Which pull requests:** the watched set of m4-34, the waits that hold a pull request. A pull request delivered to a handoff state without `merge` (the issue in `review`, no wait) is where a reviewer most often requests changes, and it is not watched in M4.
- **Read:** one new read, the reviews of a pull request (`GET /repos/<owner>/<repo>/pulls/<n>/reviews`) and their review comments (`GET /pulls/<n>/comments`), added to `GitHubPullRequests` and to `DeliveryExecutor` with a default that fails like `mergeability` does. The latest review per reviewer counts; `CHANGES_REQUESTED` on the pull request's current head triggers the reaction. A 403 or 404 on the reviews read records `reaction_skipped` naming the "Pull requests: read" permission, and the wait goes on.
- **Action `send_to_agent`:** like m4-34, a continuation turn with `loop.retry_kind` = `reaction`, findings holding each review body and comment (path, line, text), redacted and cut at a fixed size, then verification again. One reaction per review id, so the same review never sends twice. Each reaction attempt takes a `max_attempts` slot (m4-34).
- **`escalate_after`:** a duration (PLAN 6.7 shows `4h`). When changes are still requested that long after the first reaction for that review round, Loop stops sending and tells me through the `HumanChannel` of m4-30, with the pull request URL. A new head or a new review starts a new round.
- **Poll:** the m4-34 poll, same floor, same memoized client.
- **Logic** in `loop.core.reactions`. `Orchestrator.kt` gets call sites only.

Out of this issue: replying to review threads on GitHub, resolving them, reviews on trackers other than GitHub, and pull requests of issues delivered to a handoff state without a wait.

## Acceptance criteria

- [ ] A `CHANGES_REQUESTED` review on the current head resumes the workspace with `loop.retry_kind` = `reaction` and findings holding the review body and comments, redacted and cut, then verifies again (test on recorded GitHub answers).
- [ ] The same review id never sends twice, and a review on an older head is ignored (tests).
- [ ] Past `escalate_after`, Loop stops sending and notifies through the `HumanChannel` with the pull request URL; a new head or review starts a new round (tests with a fake clock and a fake channel).
- [ ] A 403 and a 404 on the reviews read record `reaction_skipped` naming "Pull requests: read", and the wait goes on (one test each).
- [ ] The reviews read is in `DeliveryExecutor` with a failing default, and `GitHubPullRequests` reads it (test with recorded answers).
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.reactions.*'` passes.
- [ ] The logic lives in `loop.core.reactions`, and the diff of `Orchestrator.kt` holds call sites only (judgment).
- [ ] `docs/spec-decisions.md` describes `changes_requested`, the rounds, `escalate_after` and the pull requests not watched in M4 (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
