---
id: bl-178
title: "Early conflict check of queued merge waits with git merge-tree"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

Under bl-172's queue, a delivery that conflicts with the current base waits its turn, then spends a merge round only to find the conflict. `git merge-tree --write-tree <base tip> <head>` tells whether a merge conflicts with no workspace write, no slot and no lock.

To weigh: after each merge, or each base fetch, run it for every queued merge wait against the fetched base, and send a conflicting one back to its agent at once with the conflict comment of m2-26, leaving the queue. Questions: whether `merge-tree` runs through `WorkspaceGit`'s isolation, its cost per tick, and whether an early send back loses the queue place for good.

## Acceptance criteria

- [ ] A decision recorded in this issue (judgment).

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort. To triage.
