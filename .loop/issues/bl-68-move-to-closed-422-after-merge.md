---
id: bl-68
title: "The move to closed after a merge answers 422 once"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found in the bl-53 playground run, 2026-11-14.

## What

After `pull_request_merged`, the move of the issue to `on_delivered.move_to: closed` failed on its first try both times in bl-53: `delivery outcome=failed reason="cannot move the merged issue: The tracker answered 422: Validation Failed"`, about one second after GitHub merged the pull request. The pull request body ends `Closes #n`, so GitHub was closing the issue at the same moment. The next tick, 3 to 6 seconds later, moved it (`to=closed merged=true`) and removed the workspace, as m2-26 specifies for a failed move.

So nothing was lost, but every merge on GitHub may log a failed delivery, and m2-26's spec says the move to `closed` there is "skipped or a harmless PATCH". On the pilot app this would show as a warning on every delivered issue.

Seen on issues #1 (run b64d1735, attempt 505df454, pull request #4) and #5 (attempt f5c9705f, pull request #6) of the playground. The response body beyond "Validation Failed" was not kept, so the cause is a guess.

Ideas: read the issue before the PATCH and skip it when it is already closed, or treat a 422 on a move to `closed` as done when a read shows the issue closed. Keep the error body in the reason.

## Acceptance criteria

- [ ] The cause of the 422 is known (the error body, or a reproduction).
- [ ] A merge that closes the issue through `Closes #n` moves it without a failed delivery in the log.

## Comments

2026-11-14 filed from the bl-53 run.

2026-11-18 soule: triaged ready-for-agent in m3-29, no blocker. Its criteria stand.
