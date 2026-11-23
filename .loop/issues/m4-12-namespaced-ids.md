---
id: m4-12
title: "Namespaced issue ids in the core, primary workspace keys and branches kept"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-11]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 112, first half. Lane A, runs on the claude-code runner. Every issue is named with its tracker (`gh:454`, `local:research-03`) in the store, the ledger, events and logs, so ids of two trackers never collide. The commands that take an issue id and the display are m4-47.

Area: `loop.core.trackers` (a `NamespacedId` type and the reading rule), `WorkspaceKey.of` in `spec-core/src/main/kotlin/loop/core/workspace/WorkspaceKey.kt` (it allows only letters, digits and `._-`, maps the rest to `_` and adds a hash suffix), the branch name `loop/<workspace key>` in `PullRequestDelivery.kt` (around line 292), the store (`store/.../SqliteEventStore.kt`, issue id columns), the ledger, the prompt and hook variables (`PromptVariables.kt`, `workspace/HookEnv.kt`), docs.

The rule, decided by soule on 2026-11-18 so nothing in flight changes:

- The namespaced id is `<tracker id>:<native id>`. The adapter keeps its native ids; the namespacing happens where the registry hands issues to the core. The namespaced id is what the store, the ledger, events, logs and commands carry.
- **The primary tracker's issues keep their workspace key and branch as today**, `WorkspaceKey.of(identifier)` and `loop/<key>`, so no folder or branch is renamed and nothing in flight changes. Loop's workspaces are git worktrees made by `scripts/hooks/worktree-after-create.sh` (branch `loop/$LOOP_WORKSPACE_KEY`, line 19), and `scripts/hooks/worktree-before-run.sh` checks out `loop/$LOOP_WORKSPACE_KEY` on each run (lines 15 to 18). A renamed folder would break the worktree's gitdir link, and `scripts/` is protected. `conformance/.../WorkspaceConformanceTest.kt:48` pins the key to `WorkspaceKey.of(identifier)` and stays green.
- **An extra tracker's issue** gets `WorkspaceKey.of("<tracker id>:<identifier>")` and the branch `loop/<that key>`.
- Store rows and ledger lines written before this change hold bare ids. They are read as the primary tracker's ids, never rewritten (the event store is append only).
- `issue.id`, `issue.identifier`, `LOOP_ISSUE_ID` and `LOOP_ISSUE_IDENTIFIER` stay native: the prompt in `WORKFLOW.md` (protected) prints `{{ issue.identifier }}`, and the pilot app's hooks read the `LOOP_*` names. The GitHub adapter's `id` is `454` and its `identifier` `example-app-454` (`GitHubTracker.kt:323`). The tracker id is a new prompt variable, `issue.tracker`, and a new hook variable, `LOOP_TRACKER_ID`.
- The ledger line carries the namespaced id. The separate `tracker` field that `loop report` (m4-38) groups by comes with m4-13.

Lane A rule: the type and the rule live in `loop.core.trackers`; the orchestrator, workspace and delivery edits are limited to call sites.

Out of this issue: commands taking a bare id and the display of ids (m4-47), polling several trackers (m4-13), routed writes (m4-14).

## Acceptance criteria

- [ ] Issues reach the core with namespaced ids, and events and ledger lines carry them (tests with a `files` tracker under a non default id).
- [ ] An extra tracker's issue gets the workspace key `WorkspaceKey.of("<tracker id>:<identifier>")` and the branch `loop/<that key>` (test).
- [ ] The primary tracker's issues keep `WorkspaceKey.of(identifier)` and `loop/<key>` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.NamespacedIdTest'` passes, a new test class of the type and the reading rule.
- [ ] A store and ledger written before the change are read with their bare ids as the primary tracker's, with no rewrite (test on a fixture store).
- [ ] An in flight primary workspace and its open pull request are untouched by the change: same folder, same branch, no `after_create` run again (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.WorktreeDispatchTest'` passes, with the in flight primary workspace case.
- [ ] `issue.id`, `issue.identifier`, `LOOP_ISSUE_ID` and `LOOP_ISSUE_IDENTIFIER` stay native, and `issue.tracker` and `LOOP_TRACKER_ID` hold the tracker id (tests on the prompt variables and `hookEnv`).
- [ ] `./gradlew --no-daemon :conformance:test` passes, `WorkspaceConformanceTest` unchanged.
- [ ] `docs/spec-decisions.md` states the id rule, the kept primary keys and the new variables (judgment).
- [ ] The orchestrator, workspace and delivery diffs are limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
