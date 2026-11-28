---
id: bl-272
title: "The Loop variant of the issue skill must never move the issue"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. From the m4-44 check of the issue skills, 2026-11-27, against PLAN section 16: the issue skill ends with `submit_result` and never moves the issue itself.

## What

The second pilot app's `.claude/skills/iplmt-loop/SKILL.md` ends with one `mcp__loop__submit_result` (line 48) and sends product questions through `needs_info` (lines 42 and 60), as intended. But:

1. Nothing forbids `mcp__loop__move`. Line 16 names only `submit_result` and `comment`. In the m4-43 wave, issue 199 attempt a1d53ccb called `mcp__loop__move` to `review` (`issue_move_requested`, 16:22:21Z). The pilot app's runs show the same (issues 326, 481, 482).
2. Line 47 tells the agent to `git mv` the issue file to `issues/done/` and flip its badge in the same commit, so the agent marks the issue done before Loop verifies or merges anything.
3. Lines 30 to 33 still send the agent to `scripts/iplmt-simulator.sh`; only the prompt points it at `request_resource`.

The pilot app's copy has the same lines.

## Agent brief

Human lane: the skills live in the project repositories. In the second pilot app's and the pilot app's Loop variant, through a pull request from a worktree: add "Never call `mcp__loop__move`; `submit_result` ends the run and Loop moves the issue", drop the `git mv` to `issues/done/` (moving the issue file and its badge is done after the merge, by hand or by a Loop delivery step), and replace the simulator script steps with `mcp__loop__request_resource` and `release_resource`.

## Acceptance criteria

- [ ] Both Loop variants forbid `mcp__loop__move` in plain words.
- [ ] Neither moves the local issue file or flips its badge in the agent's commit.
- [ ] Both get their simulator through `request_resource`, not the script.
- [ ] A run after the change shows no `issue_move_requested` event from an agent of these projects. Trace linked here.

## Comments
