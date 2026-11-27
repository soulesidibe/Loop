---
id: bl-265
title: "A standing change request by a trusted reviewer blocks the auto merge on any head"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: []
area: [spec-core/src/main/kotlin/loop/core/merge/AutoMergeGate.kt, plugins/tracker-github/src/main/kotlin/loop/tracker/github/GitHubPullRequests.kt]
---

## Parent

bl-228 item 6, a design question decided by the owner on 2026-11-27: veto. Found in the merge risk reviews of pull request #78 (m4-36).

## What

The `changes_requested` reaction (m4-34, m4-36) treats a change request as answered once the agent pushes a new head. GitHub keeps the request in force until the reviewer reviews again (approves, dismisses, or comments with a new review). `AutoMergeGate.evaluate` (spec-core/src/main/kotlin/loop/core/merge/AutoMergeGate.kt, around line 112) checks CI, mergeability and the merge risk band, but no review state. So under `delivery.merge: auto` Loop can merge a pull request while a trusted reviewer's change request is still standing.

Decision: a standing change request by a trusted reviewer vetoes the auto merge on any head. The pull request falls back to an approval wait, the same path as a medium band (`auto_merge_fallback`), so a human approves or sends back.

## Agent brief

- In `AutoMergeGate`, before the merge, read the pull request's reviews and take each reviewer's latest review, as the reaction does. If any reviewer whose `association` is in `reactions.changes_requested.trusted_associations` (default `OWNER`, `MEMBER`, `COLLABORATOR`) has `CHANGES_REQUESTED` as their latest state, on any commit, fall back with a reason naming the reviewer and the review, for example `standing change request by <login> on <short sha>`.
- A later review by the same reviewer that approves, dismisses or comments clears it, as GitHub does. A dismissed review never counts. An untrusted reviewer's change request never blocks, as in bl-157 and m4-36.
- Apply the trusted list even when `reactions.changes_requested` is absent: use the default list then, so the veto does not depend on the reaction being configured.
- Fail closed: if the reviews cannot be read, fall back to an approval wait with the read error as the reason, never merge.
- Record it in the trace like the other fallbacks (`auto_merge_fallback reason=...`) and in the issue comment Loop writes.
- Trackers without pull request reviews (files tracker with local delivery) are unaffected.

## Acceptance criteria

- [ ] A test: a trusted reviewer's change request on an older head, agent pushed a new head, CI green, band low: no merge, `auto_merge_fallback` with the reviewer and the review's commit in the reason, and the issue waits for approval.
- [ ] A test: the same reviewer later approves (or the review is dismissed): the gate merges.
- [ ] A test: an untrusted reviewer's change request (association outside the list) does not block.
- [ ] A test: the reviews read fails: no merge, fallback naming the read error.
- [ ] A test: with no `reactions.changes_requested` block, the default trusted list still applies.
- [ ] Existing AutoMergeGate and ChangesRequestedReaction tests pass unchanged.
- [ ] `docs/spec-decisions.md` (the m4-36 section) and the auto merge docs describe the veto and the fallback reason.
- [ ] `./gradlew :spec-core:test --tests "loop.core.merge.*"` and the tracker-github tests pass.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Out of scope

- bl-228 items 1 to 5 (comment order, truncation, parked reaction counting, comment read failure, map pruning).
- Changing how the reaction itself sends a review to the agent.

## Comments
