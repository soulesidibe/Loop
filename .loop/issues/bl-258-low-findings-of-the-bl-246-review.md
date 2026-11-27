---
id: bl-258
title: "Low findings of the bl-246 review, no lazy fetch and checkout -B"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #104 (bl-246), Sonnet 5.5 and a Fable 5.2 recheck that replayed the guard on git 2.54, on 2026-11-27. Both found only lows; approved and merged. The recheck also reported a signature program run through the session brief's `git log`; checked against Loop's real pins, it was a false positive (its replay left out `gpg.ssh.program=` and `gpg.x509.program=`, which `WorkspaceGit.MORE_PINS` already sets), so it is not listed.

## What

1. **The import refusal misses a branch under rebase or bisect.** A worktree in the middle of a rebase of `loop/<key>` has a detached `HEAD`, so `%(worktreepath)` is empty and the import moves the ref. `git branch -f` refuses that case. Reproduced.
2. **A stale worktree entry gives a refusal with the wrong remedy.** A worktree folder deleted without `git worktree prune` is still named, and the message says to switch it. Reproduced. Relevant when a project moves from worktree mode to clone mode.
3. **Check then write race in the import.** A worktree that checks the branch out between `for-each-ref` and `update-ref` is not seen. Operator timing only.
4. **The git 2.45 check runs only in `create` and `reuse`.** `CloneDelivery.import` and other reads from a process that never prepared the workspace run unchecked, so on an older git they fail open.
5. **`remoteUrlExists` in `cli/.../Host.kt`** runs `git remote get-url` in the project with no pins and no `GIT_NO_LAZY_FETCH`. No marker in the replay; add the variable for consistency.
6. **Test gap.** The `GitPush` push side `rev-parse` and `cat-file -t` are covered only by the environment test, not shown live unguarded.
7. **`checkedOutWarning` swallows errors** with `runCatching`, so a git failure means no warning. The import still refuses.

Area: `spec-core/src/main/kotlin/loop/core/workspace/WorkspaceClones.kt`, `CloneDelivery`, `cli/src/main/kotlin/loop/cli/Host.kt`, `GitPush.kt` tests.

## Comments
