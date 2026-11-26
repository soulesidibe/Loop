---
id: bl-248
title: "Low findings of the bl-239 review, worktree hook lock"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #102 (bl-239), Sonnet 5.5 and Fable 5.2 rechecks, on 2026-11-26. Round 1 was sent back for two mediums (non atomic stale lock takeover, before_remove deleting files before git agreed to remove the worktree); round 2 fixed them, confirmed by a concurrency harness, and its reviews found only the lows below; merged. Items 1 and 2 first.

## What

1. **No time bound when the lock folder can never be made.** `scripts/hooks/worktree-lock.sh`, the wait loop of `loop_lock_take`: the deadline check and the sleep both require the lock folder to exist. With a read-only common git folder or a full disk, mkdir fails forever and the hook spins with no message until Loop's 60 s hook timeout kills it with empty output. Fix: at the deadline exit whatever the folder state, with "cannot create the lock", and always sleep.
2. **The takeover test passes with the fix broken.** In `WorktreeHooksTest`, "eight hooks take over a dead process's lock together" passed with the recheck under the guard removed, and with guard and recheck removed: the PID read back turns double holders into silent retries, and the test only sees failed hooks. The harness showed 5 and 7 double holds per 100 rounds on those mutants. Fix: a shell level test with a critical section marker (`mkdir crit` under the lock) over enough rounds.
3. **A waiter can delete a live takeover guard after a dead one.** `loop_lock_takeover`: two waiters read the same dead guard PID, one clears it, a third makes a fresh guard, the second's `rm -rf` deletes it (23 in 400 rounds, no double hold seen). Needs a hook killed inside a takeover. Fix: clear a guard by `mv` aside after reading its PID again, and remove the own guard only if it still holds `$$`.
4. **stderr noise from failed redirections.** `echo "$$" >file 2>/dev/null` still prints "No such file or directory", since the redirection fails before `2>` applies. Fix: `{ echo "$$" >file; } 2>/dev/null`.
5. **The PID read back is not atomic.** If a fresh lock is wrongly moved aside (only through the 2 minute rule on a live holder, for example after a laptop sleep, or inode reuse), two hooks can each write their PID into a third hook's folder and both read their own back. Fix: write the PID in a private folder before an atomic `mv` to the lock path, or accept.
6. **`loop_lock_guard_since` is not tied to one guard.** It is set when the guard is already gone and not reset when no takeover is needed, so a waiter can clear a fresh pidless guard 5 s later. Harmless without a truly stale lock. Fix: track the guard's inode, as for the lock.
7. **The 2 minute rule can still race a live holder that releases** between the recheck and the `mv`. Needs a holder over 2 minutes old.
8. **A lock timeout after the early delete of before_remove** leaves an emptied, still registered worktree. Harmless in Loop (`WorkspaceManager.remove` deletes the folder whatever the hook result), matters only when the hook is run by hand.
9. **No test for before_remove on a worktree locked with `git worktree lock`**, the second branch of the early delete guard.
10. **Git outside the hooks takes no lock.** `loop replay --verify` (`spec-core/src/main/kotlin/loop/core/replay/ReplaySites.kt`) runs `worktree add` and `worktree remove` from Kotlin, and an agent can run git in its own Workspace; either can still read a half written `commondir` while a hook runs `worktree add`. Documented in `docs/workspaces.md`. Fix: have `ReplaySites` take the same lock, or accept for agents.

Area: `scripts/hooks/worktree-lock.sh` (protected, human lane), `scripts/hooks/worktree-before-remove.sh`, `cli/src/test/kotlin/loop/cli/WorktreeHooksTest.kt`, `ReplaySites.kt`, `docs/workspaces.md`.

## Comments
