---
id: bl-247
title: "Low findings of the bl-207 review, Gradle test inputs"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk review (Sonnet 5.5) of pull request #101 (bl-207), on 2026-11-26. No medium or high; merged.

## What

1. **The new comment says the anchored patterns skip `build/`, `workspaces/` and `.claude/worktrees/`, which is wrong.** Gradle `fileTree` include patterns filter files but do not prune folders, so each `:conformance:test` run walks the whole repository root (about 60k extra file checks on a local checkout with worktrees, none on CI). No correctness risk, nothing there can match. Fix: scope the trees to the module folders, or `exclude` those folders, and correct the comment.
2. **Any module's test edit now reruns `:conformance:test`.** The intended trade-off of the brief, noted for the record; cost is small.
3. **`.gitignore` sources outside the repository are not inputs.** `git check-ignore` also reads `.git/info/exclude` and the global excludes file, which Gradle cannot track. The comment notes nested `.gitignore` files; mention these two as well.
4. **Pre-existing: `loop.rootWorkflow` is an absolute path system property**, so it is a task input that limits build cache relocation across checkouts. Not added by #101.

Area: `conformance/build.gradle.kts`, `cli/build.gradle.kts`.

## Comments
