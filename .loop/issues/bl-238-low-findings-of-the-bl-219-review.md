---
id: bl-238
title: "Low findings of the bl-219 review, the guarded free retry"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #85 (bl-219), Sonnet 5.5 and Fable 5.2, on 2026-11-26. Attempt 1 was sent back for a medium (a late grant freed during its prepare); attempt 2 fixed it with a conditional free and a retry, and its reviews found only the lows below; approved.

## What

1. **A prepare can start in the gap between two rounds after a failed outcome write.** Between rounds the guards are free and the attempt is not in `unfreed`, so a tick can start a prepare on a lock already given back in round 1 when its row is still unprepared (the prepare ran in this process but its outcome write failed). Round 2 finds the key in `givenBack` and frees without a second release, leaving the device prepared until `recover`. Needs a failed store write, a late grant and a tick in the gap. Fix: skip any key in `givenBack` in the tick's unprepared filter.
2. **A read only check skipped for a lock prepared in the gap.** A lock not prepared at round 1 gets `endChecked[key] = null` memoized; if a tick prepares it before round 2, it is released correctly but its declared read only check is skipped. No real exposure, since the agent never used it.
3. **An end can last one more provider timeout per late grant,** since it waits for the late prepare and then releases. By design; one sentence in the docs.
4. **`Thread.sleep(300/500)` in the `grantedWhileGivingBack` tests** back only the "has not completed" assertions, so they can pass vacuously on a slow machine and slow the suite. Use a bounded poll.
5. **Wording:** `Orchestrator.kt` around 1609 falls back to `freeAttempt` with no expected keys "when there are no grants". A lock row can still exist there (granted through `loop tool mcp`), only no tick or prepare in that process. Reword the comment.
6. **`inBackground` takes `tryLock` before `launch`** (bl-219 finding 11, out of scope there): if the scope is canceled in that instant, the key stays in `LockGuards`. Only at `close()`.

Area: `ResourceGrants.kt`, `ResourceGrantsTest.kt`, `Orchestrator.kt`, `docs/spec-decisions.md`.

## Comments
