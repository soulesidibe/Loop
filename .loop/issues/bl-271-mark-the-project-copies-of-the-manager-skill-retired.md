---
id: bl-271
title: "Mark the project copies of the manager skill retired"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. From m4-44 (retire the manager skill), 2026-11-27. The global skill (`~/.claude/skills/iplmt-manager/SKILL.md`) was marked retired on that day. The project copies were left alone because editing them then was unsafe: the second pilot app had a live `loop run` and an uncommitted change to its own copy, the pilot app's checkout was on a release branch with uncommitted work, and the private copy of Loop's old repository is archived.

## What

Each project that used the manager skill holds a copy at `.claude/iplmt-manager.md`: the second pilot app, the pilot app and two other private projects (the list is in the owner's notes). Their issue skills still point at it, for example the second pilot app's `.claude/skills/iplmt/SKILL.md` line 184 reads its merge gates from it.

## Agent brief

Human lane: the copies live in other repositories. In each project, through a pull request from a worktree, never in a main checkout where Loop runs: add the same dated "Retired on 2026-11-27, use Loop" header the global skill has, and move anything the issue skills still read from it (merge gates) into the issue skill or the project's `WORKFLOW.md`. Skip the archived repository.

## Acceptance criteria

- [ ] Each live project copy carries the retired header, merged through a pull request.
- [ ] No issue skill in those projects reads the manager skill file any more.
- [ ] The archived repository is noted here as skipped.

## Comments
