---
id: m4-48
title: "One GitHub client per origin and token, with one rate limit hold per run"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-13]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 113, second half. Lane A, runs on the claude-code runner. Carries bl-50. With several trackers polled on their own (m4-13), the GitHub tracker and pull request delivery must share one rate limit hold.

Area: `GitHubClient` in `plugins/tracker-github`, `trackerFor` and `deliveryFor` in `cli/.../Host.kt` (around lines 158 to 190), the memoized delivery executor in `RunCommand.kt` (m3-15 note 12), the CI wait's polls, `docs/plugins/tracker-github.md`, docs.

What is known:

- **bl-50, decision kept from its triage:** one rate limit hold per API origin and token, shared by the GitHub tracker and pull request delivery and kept for the life of the `loop run` process. Today each `GitHubClient` keeps its own `heldUntil`, and m3-15 only memoized the delivery executor, so a delivery that spends the last request does not stop the tracker's next read. Build one client per origin and token per run.
- A held delivery step stays `DeliveryError.Unavailable`, a held tracker read stays `TrackerError.RateLimited`. A clear never overwrites a newer hold (the bl-22 race). The token never appears in a log line, an event or a `toString`. The CI wait's polls go through the same hold.
- bl-22 (GitHub client hardening, deferred) waited on bl-50; this issue considers what of it the shared client touches and leaves the rest in the backlog.
- m4-16 reads blockers through this shared hold.

Lane A rule: the shared client lives in `plugins/tracker-github` and its wiring in `Host.kt` and `RunCommand.kt`; `Orchestrator.kt` is not edited.

Out of this issue: a hold persisted across a restart, the per tracker poll (m4-13), routed writes (m4-14).

## Acceptance criteria

- [ ] A tracker read answered with `x-ratelimit-remaining: 0` makes the next pull request call fail as unavailable with nothing sent, and the other way round; two tokens or two origins do not hold each other; the hold ends at the reset time (tests).
- [ ] A clear never overwrites a newer hold, and the token appears in no log line, event or `toString` (tests).
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test --tests 'loop.tracker.github.SharedRateLimitHoldTest'` passes, a new test class of the shared hold.
- [ ] A delivery step held at one tick sends nothing at the next tick while the hold lasts, and delivers after it (test).
- [ ] The CI wait's polls respect the same hold (test).
- [ ] `docs/plugins/tracker-github.md` and `docs/spec-decisions.md` describe the shared hold (judgment).
- [ ] `Orchestrator.kt` is not edited (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-13 after the Fable 5.2 review.

2026-11-24 20:17Z loop: Loop opened pull request #45 for this issue: https://github.com/soulesidibe/Loop/pull/45

2026-11-24 20:20Z loop: Loop did not merge pull request #45 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, One shared client and one hold per origin and token, wired through Host.kt and RunCommand.kt with Orchestrator.kt untouched, and both docs updated, as the issue asks.
  - impact: medium, It changes the rate limit handling of every GitHub request, trackers, delivery and the CI wait, but only in memory and inside the GitHub plugin and its CLI wiring.
  - verification: strong, Two new test classes cover both hold directions, the reset time, tokens and origins, the CI polls, the multi-tick delivery, the stale-clear race and token leakage, though I did not run them.
  - rollback_cost: two_way, The hold is in memory only and nothing is persisted, so reverting the commit undoes it fully.
  - error_detection: loud, A held call surfaces as TrackerError.RateLimited or DeliveryError.Unavailable and nothing is swallowed, though a stale clear is silently skipped by design.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 1 of 12 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
