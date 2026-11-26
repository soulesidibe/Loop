---
id: bl-239
title: "WorktreeHooksTest parallel after_create test fails intermittently on a commondir read"
state: todo
priority: null
labels: [needs-triage]
blocked_by: []
---

The full build of bl-235 on 2026-11-26 failed once on `loop.cli.WorktreeHooksTest` test `after_create survives two parallel workspaces on a fresh repository`. Round 8, workspace p-2: the hook after_create failed with exit code 128, `fatal: failed to read .git/worktrees/p-1/commondir: Undefined error: 0`. Git seems to read the metadata of the worktree p-1 while the other hook is still creating it. Three reruns of `./gradlew :cli:test --tests 'loop.cli.WorktreeHooksTest' --rerun` right after passed. The bl-235 diff touches only the Codex runner and its docs, not `scripts/hooks/` or this test. The race is either in the documented hooks under `scripts/hooks/` (a protected path) or in git itself on macOS, and it can fail a merge check at random.

Follow up of bl-235 (default:bl-235), filed by Loop from attempt 896ccc84-910b-4343-ace4-3a317a82219f.
