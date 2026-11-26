---
id: bl-237
title: "Low findings of the bl-224 review, reaction marks and findings"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk review of pull request #87 (bl-224), Sonnet 5.5 then a Fable 5.2 recheck (required by the medium band, which came only from the reviewer's diff cut), on 2026-11-26. Only lows; approved.

## What

1. **A reaction retry that waits for a slot loses its findings after a restart.** `RetryQueue.requeue` replaces the entry's `reaction` error with the slot reason, so the stored kind becomes `error` and `Orchestrator.storedReactionFindings` returns null. After a restart the retry renders no findings, and for `changes_requested` the reviews already marked sent never reach the agent. Keep the kind across a slot requeue, or read the stored findings whenever the issue is marked.
2. **Crash window between `sendBack` and `reaction_triggered`.** The event is now recorded after the wait ended and the retry state was saved. A crash between them leaves no event: the mark is not restored (one counter reset), the `ci_failed` or `changes_requested` count is one short, and findings are stale or missing. Record the event first, or keep the findings and the mark in the retry state.
3. **A stale mark across a restart** after a reset that stores no event (handoff, issue absent from candidates, reconciliation terminal or not active, parked `fireDue` drop). With a stored counter above zero, failures can accumulate to an early `max_attempts` park. Documented in spec-decisions; record a trace event for those resets or store the mark in the retry state.
4. **`Waits.endUndecided` returns true when no wait is stored**, so a retry is still scheduled for an issue whose wait someone else removed. Same as before bl-224. Treat a missing record as decided or kept.
5. **Misleading label:** a wait of another reason found by `endUndecided` records `reaction_skipped` with `human_decided`. Use its own reason or only log it.
6. **Log wording and volume on a failed conditional end:** the WARN "the issue goes back to its agent" is logged before the outcome is known (wrong for decided and kept), and a store write failure keeps the wait, so the mergeability and base reads repeat every tick.
7. **Upgrade note:** `reaction_triggered` events recorded before bl-224 hold no `findings`, so an upgrade restart with a reaction retry in flight renders none and has no mark.
8. **Test gaps:** `ReactionMarksTest` builds reset events by hand (they match the real emitters today, but nothing pins that), and the approve and send-back race tests use the non atomic `FakeWaitingIssues` default; only a single threaded store test proves atomicity.

Area: `RetryQueue.kt`, `Orchestrator.kt`, `Reactions.kt`, `CiFailedReaction.kt`, `ChangesRequestedReaction.kt`, `ReactionMarks.kt`, `Waits.kt`, `ReactionMarksTest.kt`, `docs/spec-decisions.md`.

## Comments
