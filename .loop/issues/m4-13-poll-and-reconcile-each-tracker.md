---
id: m4-13
title: "Each tracker polled and reconciled on its own"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-47]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 113, first half. Lane A, runs on the claude-code runner. The orchestrator stops taking one `private val tracker: TrackerAdapter` (`Orchestrator.kt:284`) and reads the registry of m4-11. The shared GitHub rate limit hold of bl-50 is m4-48.

Area: `loop.core.trackers` (poll, reconcile and merge per tracker), the orchestrator's reads (`dispatch` around line 556 with `fetchIssuesByStates` at line 562, the terminal cleanup around line 1014, `reconcile` around line 1028, and the by id reads), `RetryCoordinator.fetchByIds` (around line 691), `trackerFor` in `cli/.../Host.kt` (around lines 158 to 190), `RunCommand.kt` (where the run builds its trackers), the ledger rows.

What is known:

- Each tracker is polled with its own active states and reconciled with its own terminal states. The candidates of all trackers are merged and sorted by the spec's rule. A failing tracker records its failure and is skipped for that tick; the others go on.
- **Each tracker's own `required_labels`** (m4-11): its candidates are filtered by its own labels, and a handoff move on an issue removes that issue's tracker's labels, never the primary's. Without it, in the exit on the pilot app (GitHub primary, `files` extra), every `todo` file issue would be a candidate, `ready-for-human` ones included.
- **Ledger rows hold the tracker id** as a separate `tracker` field, beside the namespaced issue id of m4-12, so `loop report` (m4-38) can group by tracker (M2 of the review behind the spec).

Lane A rule: the per tracker loop lives in `loop.core.trackers`; the `Orchestrator.kt` edit is limited to replacing the single adapter's call sites with registry calls.

Out of this issue: the shared GitHub client and its rate limit hold (m4-48), routed writes (m4-14), per tracker limits (m4-15), cross tracker blockers (m4-16).

## Acceptance criteria

- [ ] With a `files` tracker and a GitHub tracker on recorded answers in one temporary project, candidates of both are dispatched in the spec's sort order (CLI test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.MultiTrackerRunCommandTest'` passes, a new test class for two trackers.
- [ ] A tracker that fails its poll records the failure and the other tracker's candidates still dispatch and reconcile (test).
- [ ] Each tracker is reconciled with its own terminal states (test).
- [ ] Each tracker's candidates are filtered by its own `required_labels`, and a handoff move removes that tracker's labels only (test with two trackers).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.TrackerPollTest'` passes, a new test class of the per tracker loop.
- [ ] Ledger rows carry the tracker id in a separate `tracker` field (test).
- [ ] `docs/spec-decisions.md` describes the per tracker poll, the per tracker labels and the ledger field (judgment).
- [ ] The poll logic lives in `loop.core.trackers` and the `Orchestrator.kt` diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-24 19:01Z loop: Loop opened pull request #42 for this issue: https://github.com/soulesidibe/Loop/pull/42

2026-11-24 19:05Z loop: Loop did not merge pull request #42 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.73, at its worst verdict: impact.
  - alignment: aligned, The diff polls, reconciles and filters each tracker on its own, and it adds the ledger tracker field, a TrackerPoll class and the decisions doc. The Orchestrator edit is mostly call sites, though some are per-tracker state lookups.
  - impact: high, It changes the core poll, reconcile, retry, eligibility and handoff paths of the orchestrator for every project, and it changes what the ledger's tracker field holds.
  - verification: strong, It adds TrackerPollTest, MultiTrackerOrchestratorTest and a CLI test on recorded GitHub answers. They cover merged order, own labels, a failing tracker, per-tracker terminal states, and the ledger field.
  - rollback_cost: costly, A revert leaves ledger lines and rows already written with a tracker id where the kind used to be, which mixes two meanings in the same field. The store schema is unchanged.
  - error_detection: loud, A tracker that fails is logged at error or warn level with its tracker id, and a test covers it. Its issues are skipped for that tick and their state is kept, so they are not dropped silently.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 10 of 21 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-24 19:12Z loop: Loop could not merge pull request #42: merging main at f25ee9cedcc50d74794d5fdebd8d5b89ac8ae843 into loop/m4-13 conflicts (conflict in docs/spec-decisions.md). The merge was aborted and the branch is unchanged. The next attempt merges refs/loop/base/main into its branch, resolves the conflict and submits the result again.

2026-11-24 19:15Z loop: Loop updated pull request #42 for this issue: https://github.com/soulesidibe/Loop/pull/42

2026-11-24 19:22Z loop: Loop did not merge pull request #42 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.73, at its worst verdict: impact.
  - alignment: aligned, The diff polls, reconciles and filters each tracker on its own, adds the ledger tracker field, a TrackerPoll class and the decisions doc, with Orchestrator edits mostly replacing call sites.
  - impact: high, It changes the core poll, reconcile, retry, eligibility and handoff paths of the orchestrator for every project, and the meaning of the ledger tracker field.
  - verification: strong, New TrackerPollTest, MultiTrackerOrchestratorTest and a CLI test on recorded GitHub answers cover merged order, own labels, a failing tracker, per-tracker terminal states and the ledger field.
  - rollback_cost: costly, A revert leaves ledger lines and rows written with a tracker id where the kind used to be, so one field then holds two meanings; the store schema is unchanged.
  - error_detection: loud, A failing tracker is logged at error or warn level with its tracker id and covered by a test, and its issues keep their state for that tick instead of being dropped.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 11 of 21 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
