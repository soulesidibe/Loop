---
id: bl-176
title: "A base move only in recheck_skip_paths costs no merge round"
state: done
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

2026-11-22 00:49Z agent bf3a8ea4: Done in 3931391. New key kinds.code.delivery.pull_request.merge.recheck_skip_paths (globs anchored like guards.protect, matched case sensitive, empty by default; a blank glob or a value that is not a list of strings is a workflow error). At the base read of phase merge, a moved tip is fetched host side and the merge goes on with no new round only when base_sha is an ancestor of the tip, every file of the base diff matches a glob, and none is in the pull request's diff against its merge base with the tip. A failed fetch or git read starts a round. A skip records merge_round_skipped (base_sha, tip, files cut at 20), and the auto merge gate still runs. Tests: MergeCheckOrchestratorTest (4 bl-176 tests), AutoMergeOrchestratorTest (1), MergeConfigTest (`bl-176, a blank recheck_skip_paths glob is refused`, `bl-176, a recheck_skip_paths that is not a list is refused`, plus the default). Human step, since WORKFLOW.md is protected: add `recheck_skip_paths: [".loop/issues/**"]` under kinds.code.delivery.pull_request.merge in Loop's own WORKFLOW.md.

2026-11-22 01:14Z loop: Loop opened pull request #13 for this issue: https://github.com/soulesidibe/Loop/pull/13

2026-11-22 05:26Z loop: Loop did not merge pull request #13: main at 973d9691be9d1b9567c6faea6d1bcbea38580913 was merged into loop/bl-176, but the checks failed on the result (a check failed). The merge commit stays on the branch. The next attempt fixes the result and submits it again.

2026-11-22 05:29Z agent f4a6856d: Fixed the failed merge check in 1a4d36c. After main 973d969 was merged in, :cli:test failed on OwnWorkflowTest `the limits are the ones of the spec`. The test still expected max_concurrent_agents 2, but 973d969 set WORKFLOW.md to 1 ("One agent at a time"). The test now expects 1. The other failure, ShowCommandTest `--prompt shows the prompt redacted with the workflow secrets, as it was written`, was a 60s timeout under load (the attempt took 106s) and has nothing to do with bl-176. Run alone, both tests pass, and ktlintCheck passes. Human step, unchanged: add `recheck_skip_paths: [".loop/issues/**"]` under kinds.code.delivery.pull_request.merge in Loop's own WORKFLOW.md.

2026-11-22 05:52Z loop: Loop updated pull request #13 for this issue: https://github.com/soulesidibe/Loop/pull/13

2026-11-22 05:53Z loop: Loop could not merge pull request #13: merging main at 381fdb851c8f45b6dbfd0f9ee904147994b9f916 into loop/bl-176 conflicts (conflict in docs/spec-decisions.md). The merge was aborted and the branch is unchanged. The next attempt merges refs/loop/base/main into its branch, resolves the conflict and submits the result again.

2026-11-22 06:21Z loop: Loop updated pull request #13 for this issue: https://github.com/soulesidibe/Loop/pull/13
