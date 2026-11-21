---
id: bl-171
title: "The earlier work block's heading is wrong in a reused workspace"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

[Loop, M4](../specs/loop-m4.md). Backlog, found by the Fable 5.2 reviews of m4-00 steps 5 to 7.

## What

Since m4-00 step 5, the block of `WORKFLOW.md` that opens on `{% if attempt or loop.workspace_reused %}` also shows on a first dispatch into a workspace an earlier attempt left (`attempt` null), for example after a restart or a return from review. Its heading, "This is not the first attempt", is then not true. Its first sentence, "already worked on this issue in this workspace", is wrong the other way round, on a retry in a recreated workspace.

A heading such as "Earlier work exists" and "already worked on this issue" alone would fit both. The edit touches a protected path, its golden copy `spec-core/src/test/resources/prompt/own-template.md`, the assertions of `OwnWorkflowTest` that read the heading, and `docs/spec-decisions.md` where it quotes the block.

## Acceptance criteria

- [ ] The heading and the first sentence are true for a retry and for a reused workspace (judgment).

## Comments

2026-11-21 soule: written in m4-00, from the product review of steps 5 to 7.
