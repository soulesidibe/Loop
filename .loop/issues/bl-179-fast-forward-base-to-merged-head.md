---
id: bl-179
title: "Fast forward the base to the merged head instead of the API merge"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

The m2-26 merge leaves a documented window: GitHub's sha guard covers only the pull request's head, so a commit landing on the base between Loop's base read and the merge is merged untested. Pushing the merged head M to the base as an unforced fast forward is an atomic compare and swap: it lands only when the base is still the one M was built on, and the tree that lands is exactly the one checked.

Costs to weigh: the token needs to push to the base (branch rules, a worker token that cannot merge in M5), the history loses the merge commit GitHub makes (first parent order), `method: squash` cannot work that way, and GitHub must still mark the pull request merged (it does when the base contains its head).

## Acceptance criteria

- [ ] A decision recorded in this issue (judgment).

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort. To triage.
