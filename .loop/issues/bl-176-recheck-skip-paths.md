---
id: bl-176
title: "A base move only in recheck_skip_paths costs no merge round"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

A human push of issue files to main (for example dd8a91d) makes the delivery in flight run its merge check again, a round of 15 minutes or more against a limit of 3. See the spec's "A base move in listed paths costs no round" decision.

- New key `kinds.<kind>.delivery.pull_request.merge.recheck_skip_paths`: a list of globs anchored at the workspace root like `guards.protect`, no blank glob, empty by default. A workflow error otherwise, refused by the preflight.
- At the base read of phase `merge`, when the tip moved since the check, Loop fetches the new tip host side as the merge check does, then merges with no new round only if: the base the check merged (`base_sha`) is an ancestor of the new tip, every file in `git diff --name-only --no-renames <base_sha> <tip>` matches a glob, and none of those files is in the pull request's diff against its merge base. Otherwise a new round starts as today. A fetch that fails starts a new round.
- A skipped round records `merge_round_skipped` with `base_sha`, `tip` and `files` (cut at 20). The auto merge gate still runs.
- Loop's own `WORKFLOW.md` sets `recheck_skip_paths: [".loop/issues/**"]`. `WORKFLOW.md` is a protected path, so that line is the human's part: the agent documents it in its result and does not edit `WORKFLOW.md`.
- Document it in the m2-26 phase `merge` paragraph of `docs/spec-decisions.md`.

Area: `spec-core/src/main/kotlin/loop/core/merge/Merges.kt`, the workflow config loader and its errors, `GitHubDelivery.kt` if the fetch needs it.

## Acceptance criteria

- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.MergeCheckOrchestratorTest'` passes, with tests for: a move only in listed paths merges with no new round; a listed path that the pull request also changed starts a round; a tip that does not descend from `base_sha` starts a round.
- [ ] The config errors for a blank glob and a non list value are tested (judgment: name the tests).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort.
