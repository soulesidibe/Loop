---
id: bl-259
title: "Low findings of the bl-250 review, conditional saves of approval waits"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #106 (bl-250), Sonnet 5.5 reading the whole diff and a Fable 5.2 recheck that built the branch and mutated the guards, on 2026-11-27. Both found only lows; approved and merged. The recheck's reproduced loss of a send back during the approval notice is pre-existing and was filed on its own as bl-257.

## What

1. **`Handoff.proceed` adopts the re-read row off the orchestrator's coroutine.** `waits.stillApproved` runs inside `withContext(io)`, and on a changed row `adopt` mutates the `Waits` maps, which are not thread safe. Do the store read in `proceed` and adopt after `deliver` returns, or hop back first.
2. **`endDelivered` falls back to an unconditional end whenever the conditional end loses,** also when the row is no longer a sent back approval (another process wrote a different wait), and deletes it. Restrict the fallback to the sent back case.
3. **Two Loop processes on one project:** process B finds the row gone at the handoff check, returns `Withdrawn`, releases the hold, and may dispatch the just delivered issue from its stale candidate list.
4. **Test gap:** removing the re-read before the push (`stillApproved(wait, "push its pull request")`) fails no test. Add a hook test with a send back after the tick's read on a pull request workflow.
5. **Docs:** the late move window is named, but not the re-read to push, PR open and move windows, where a send back can still push a branch or open a pull request (the wait stays sent back and the next delivery updates the same pull request). One sentence in `docs/spec-decisions.md`.
6. **On `Withdrawn` or a lost merge wait write, the commented URL is not stored,** so the next delivery may post a second pull request comment.
7. **`Verification.kt` handles `Withdrawn` as `requestApproval`.** Dead today; a future handoff with a check there would silently ask for approval.
8. **`syncApprovals` ends a wait unconditionally** when its issue is gone or the wait has no target; a send back landing then is dropped. Effect nil in practice (the issue is no longer active). Overlaps bl-252's area.
9. **`FakeWaitingIssues` uses the non atomic default** of `replaceApproval`. The SQLite test covers real atomicity.

Area: `spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt` (`endDelivered`, `deliverPullRequest`), `loop/core/waiting/Waits.kt`, `loop/core/delivery/PullRequestDelivery.kt`, `loop/core/verify/Verification.kt`, `docs/spec-decisions.md`.

## Comments
