---
id: m4-36
title: "The changes_requested reaction with escalate_after"
state: done
priority: 1
labels: [ready-for-agent]
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

2026-11-25 20:03Z loop: Loop opened pull request #78 for this issue: https://github.com/soulesidibe/Loop/pull/78

2026-11-25 20:04Z loop: Loop could not merge pull request #78: merging main at a89288df5f24ec7ac604540e44baaa4fe7c5ed0f into loop/m4-36 conflicts (conflict in docs/spec-decisions.md). The merge was aborted and the branch is unchanged. The next attempt merges refs/loop/base/main into its branch, resolves the conflict and submits the result again.

2026-11-25 20:11Z loop: Loop updated pull request #78 for this issue: https://github.com/soulesidibe/Loop/pull/78

2026-11-25 20:15Z loop: Loop did not merge pull request #78 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements the reviews read, the DeliveryExecutor default, the reaction logic in loop.core.reactions, the HumanChannel escalation and the docs, with Orchestrator.kt touched by only 5+/2- lines.
  - impact: medium (floor medium: the diff changes 791 lines outside tests and docs, over half the limit of 1000), It adds a new polled reaction that resumes agents and notifies humans, and extends the public plugin API and HumanEvent, with 791 non-test lines changed.
  - verification: strong, A 693-line orchestrator test covers triggers, dedup, older heads, escalation rounds, 403 and 404 and retries, and the GitHub reads are tested, though the recorded answers are hand written.
  - rollback_cost: two_way, A revert removes the code and the new events stay in the store as unread data, with no schema or migration change.
  - error_detection: loud, A refused reviews read records reaction_skipped with the permission named, an unreadable run store sends nothing, and an escalation notifies the human, though a 5xx or unreachable API is only logged at warn.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 11 of 22 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-25 20:25Z soule: sent back before merge, after the merge risk reviews of pull request #78 (Sonnet 5.5 then Fable 5.2, both medium). Fix these on the same branch, then submit again:
  - Medium, any GitHub user's review reaches the agent. `GitHubPullRequests.readReview` (around lines 274 to 285) drops `author_association`, and `ChangesRequestedReaction.standing` (around lines 247 to 255) and `findings` (around lines 310 to 348) never filter, so on a public repository a stranger's Request changes review becomes `loop.verify_findings`, followed by Loop's own "Address each review and comment in the workspace and commit." Under `auto` with merge the reaction also ends a human gated wait and the next delivery goes back to the auto merge gate. Each new review takes a `max_attempts` slot, so a stranger can park an issue. The GitHub tracker already filters by `trustedAssociations` (bl-157); the reaction bypasses it. Fix: add `association: String?` to `PullRequestReview` and read `author_association`; add `reactions.changes_requested.trusted_associations`, default `OWNER, MEMBER, COLLABORATOR`, validated like `GitHubTrackerSettings.trustedAssociations`; in `standing` drop untrusted reviews (a null association is untrusted) before the `groupBy`, so they neither trigger, count nor escalate; tests with a `NONE` and a `CONTRIBUTOR` review (nothing triggered, recorded or slotted) and a stranger's reply not in the findings; a trust paragraph in spec-decisions.
  - Low, the escalation and "never sends twice" tests reach the second watched wait through `fallBackAgain()`, which writes the pull request onto the wait by hand, a state the human policy never produces. Reach it through a real path (policy `auto` with `pull_request.merge` and a gate that falls back, or a second `delivery_failed` after `loop approve`) and remove `fallBackAgain()`. Add to spec-decisions under `escalate_after`: it fires only when the next wait holds the pull request and the head did not move, never under `human` after a reaction attempt. Do not carry the pull request onto the `human` wait here.
  - Low, `reaction_triggered` and the `sent` mark are written before `onReaction` can park the issue on its last slot (around lines 117 to 131), so after `loop resume` the review never reaches the agent. Have `sendBack` return whether a retry was scheduled, and on false keep the review out of `sent` and record the event without its ids.
  - Low, a round past `retries` is never told to the human: `escalateIfDue` runs only when `unsent` is empty (around lines 96 to 99). On `retries_spent`, escalate once per round when `escalate_after` is set.
  - Low, read the comments only when `standing` is not empty, so a poll with no change request makes two GitHub reads, not three.
  - Low, reviews are read as one page of 100, oldest first, so the newest reviews are missed on a long thread. Follow the `Link` header for reviews, and request comments newest first.
  - Low, one malformed comment (no `body` or `path`) makes `reviews` throw `Malformed` for the whole read; skip the bad comment instead.
  - Docs only: a new head silences a change request although GitHub keeps it in force until the reviewer reviews again, and `AutoMergeGate` has no review condition, so under `auto` a merge can happen with the request still open; say so in spec-decisions. Also, "verified, waits for approval again" is true only under `human`; under `auto` it goes to the auto merge.
  - Not in this pass (backlog, shared with bl-224): the wait ended from the tick snapshot over a concurrent `loop approve` (bl-224 finding 3), and findings kept in memory while the review is stored as sent, so a restart loses it for good (bl-224 finding 6).

2026-11-25 20:43Z loop: Loop updated pull request #78 for this issue: https://github.com/soulesidibe/Loop/pull/78

2026-11-25 20:47Z loop: Loop did not merge pull request #78 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1006 lines outside tests and docs, over the limit of 1000, 2104 lines in all
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements the reviews read, the DeliveryExecutor default, the reaction logic in loop.core.reactions, the HumanChannel escalation, trusted associations and the docs the send-back asked for, with Orchestrator.kt touched by only a few call-site lines.
  - impact: medium (floor medium: the diff changes 1006 lines outside tests and docs, over half the limit of 1000), It adds a polled reaction that resumes agents and notifies humans, and extends the public plugin API and HumanEvent, with 1006 non-test lines changed, over the floor.
  - verification: strong, An 884-line orchestrator test covers triggers, dedup, older heads, trust filtering, escalation rounds, 403 and 404, retries and the last slot, and the GitHub reads are tested, though the recorded answers are hand written.
  - rollback_cost: two_way, A revert removes the code, and the new event fields and kinds stay in the store as unread data, with no schema or migration change.
  - error_detection: loud, A refused reviews read records reaction_skipped with the permission named, an unreadable run store sends nothing, and an escalation or spent retries notify the human, though a 5xx or unreachable API is only logged at warn.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 19 of 26 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
