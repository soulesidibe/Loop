---
id: spec-serialized-delivery
title: Serialized delivery, one merge at a time per project with no wasted merge round
labels: [needs-triage]
---

## Problem statement

After approval, Loop delivers a verified pull request through the merge check (m2-26), the CI wait (m3-15) and, under `policy: auto`, the auto merge gate (m2-28). Only one merge check runs at a time per project, but CI waits run in parallel, and after a green CI wait Loop reads the base again: if another pull request merged meanwhile, the merged head is stale and the merge check runs again, at most 3 rounds, then the delivery falls back to an unapproved `Waiting(approval)`.

With n approved deliveries, every merge invalidates every other pending one. Measured on 2026-11-21:

- A simulation with a 10 minute merge check and 13 minute CI: 5 deliveries need 11 merge checks and 11 CI runs and take 141 minutes, where a one at a time queue needs 5 of each and 115 minutes. From 4 deliveries up, one falls back to approval.
- The live run: 8 deliveries merged between 12:45 and 17:43, and bl-104 and bl-147 fell back to approval once each after 3 rounds.
- A delivery green on CI waited for the one merge check slot behind another delivery's build: bl-157 went green at 14:13 and merged at 14:23, bl-149 waited from 15:00 to 15:16.
- Every human push to main, docs or issue files included, cost every pending delivery a round.

Overnight, with no human to approve again, a fallback stops a delivery until morning.

## Solution

Loop delivers one pull request at a time per project, from the merge check to the merge, in the order the deliveries started. A merge never makes another delivery's check stale, because the next delivery's merge check starts only after the previous one merged or left the queue. Loop pushes the merged head as soon as the merge commit exists, so its CI runs while the local checks run, and a round costs the longer of the two instead of their sum. The final merge holds no agent slot, so a green delivery never waits for an attempt or a build. A human push that only touches listed paths, outside the pull request's diff, costs no round.

## Decisions

### The queue: the oldest merge wait goes first

- No lock state is stored. The delivery at the head of the queue is the merge wait with the oldest `since`, ties broken by issue id, sorted explicitly (`Waits.current` returns map order). Only the head advances: its merge check, its CI wait, its gate and its merge. Every other merge wait stays in phase `check` and does nothing.
- `since` is set when the approved delivery opens the pull request and the merge wait starts (`Orchestrator.kt`, `wait.merging(..., clock.now())`). It survives `again()`, a new round. A refusal or a fallback (`refused()`) ends the merge wait, so a later `loop approve` starts a new merge wait at the back of the queue.
- The head leaves the queue when it merges, when it is refused or falls back to approval, when a human moves the issue out of the active states, or when its workflow no longer merges.
- At upgrade, merge waits found in phase `ci` or `merge` behind the head go back to phase `check` at their round, as `again()` does, so only one holds the queue.
- The queue is per project: two projects delivering to the same repository and base still invalidate each other, as today.
- `loop status` shows each merge wait's place in the queue.

### The merge phase holds no agent slot

The merge phase (the push, the head wait, the base read, the issue read, the auto merge gate with its read only reviewer, the merge) is network work plus one read only reviewer session. It no longer needs `SlotPlan.mergeCheckFits` or an agent slot, so a head whose CI turned green merges at the next tick even when attempts hold every agent slot. Only phase `check`, which builds in the workspace, holds an agent slot. The machine slot of m3-16 is still claimed for a phase that runs a reviewer, as for any model session.

### CI starts with the merge check

- Right after the merge commit M exists (step 4 of phase `check`), Loop stores M as the wait's `candidate_head`, then pushes it unforced to `loop/<key>`. GitHub Actions start on M while the local checks run. Nothing else changes in phase `check`: the tick still leaves a running check's wait alone, and CI polling starts only when the local check has passed, in phase `ci` as today. The first poll then often finds the runs already finished.
- The local result decides first. A failing recheck or a conflict is handled as today, whatever CI says. A red CI is applied only after the local check passed and the fingerprint became M, so a refusal can never leave the approval at V while the branch and the remote are at M.
- A human merge on GitHub at the pushed `candidate_head` during phase `check` counts as a human merge at the stored head (the lookup and `settleInactive`), not as a merge at another commit.
- `build.yml`'s concurrency group cancels the run of an earlier push of the same branch, so pushing M cancels the run of V, and a new round's M cancels the stale M. This removes most of bl-91's double CI run.
- Without `ci: actions`, the early push changes nothing but its timing.

### An Actions outage pauses the queue

When the head's CI wait ends in `timeout` (pending runs past `ci_timeout_ms`), it falls back to approval as today, and the queue then pauses: no next delivery starts its merge check until a human acts (`loop approve` on any delivery, or a restart), so an Actions incident does not burn 45 minutes on each queued delivery in turn. A red run or a failing no run rule does not pause the queue. The pause is recorded as an event and shown by `loop status`.

### A base move in listed paths costs no round

- New key `delivery.pull_request.merge.recheck_skip_paths`, a list of globs anchored like `guards.protect`, empty by default.
- When the base moved after the check, the merge goes ahead with no new round only if all of these hold: the base the check merged is an ancestor of the new tip, every file that changed between the two matches `recheck_skip_paths`, and none of those files is in the pull request's own diff against its merge base. Otherwise a new round starts as today.
- Docs are never a good candidate, since tests read them. Loop's own `WORKFLOW.md` lists `.loop/issues/**` only.
- A skipped round is recorded with the moved files, and the auto merge gate still runs on the merged head.

### A queued conflict is found before its turn (bl-178)

- Every tick, each merge wait behind the head, its issue active, is checked with `git merge-tree --write-tree` of the base tip the merge waits fetched last (the head's merge check, the round skip of bl-176) and the head of the wait's fingerprint. No new fetch is made: the next head's merge check after a merge is the fetch after a merge lands.
- It runs in the issue's own workspace through `WorkspaceGit`, with the pins and the env of the merge check's `git merge`, read only: no work tree, index or ref is written, and no slot or lock is held.
- A conflict is refused at once like a conflict of the merge check: the m2-26 conflict comment naming the paths, the findings for the next attempt, the wait ends and the issue goes back to its agent. It leaves the queue, and its next delivery queues anew at the back. Event `merge_conflict_early` (`issue`, `base`, `paths`), shown by `loop trace` and the human log.
- A clean result changes nothing. A failure of the call (a missing object, a git older than 2.38) logs a warning and changes nothing: the merge check finds the conflict in its turn.
- At most once per wait per head and base tip, a failed call included, held in memory. The head of the queue is never checked early, and the merge check is unchanged.

### What stays the same

- Verification: the merged head is still checked locally with every check, holdout and guard, and CI and the gate run on that same head.
- Runners and trackers: Claude Code and Codex alike, the `files` and GitHub trackers alike. The queue lives in the orchestrator and the waits.
- `max_concurrent_agents`: the merge check still takes one agent slot while it builds. A serialized round of about 15 to 20 minutes gives 3 to 4 merges an hour, and the live run produced 1.6 an hour with 2 agents.

## Rejected

- **Serializing only the merge check, as today.** It is the cause.
- **A speculative merge train (Zuul) and batching with bisection (bors).** Both put merge commits of work not yet merged into issue workspaces, which clashes with pushes that are never forced and with the merge commit staying on the branch after a failure. Batching also never checks the states of main between merges and blurs which agent a failure belongs to. Batching is the next step once the queue wait passes about two rounds, at 4 agents or more.
- **GitHub's merge queue.** Not available on a repository owned by a user account. It also builds the landing commit outside Loop's workspace, where holdouts, `diff_policy` and the gate never run.
- **A local merge check that leaves the visible checks to CI (`hidden_only`).** CI counts a `skipped` run as passing and names no required workflow, so it can read green with nothing built. CI does not run acceptance commands or file checks. A red CI goes to a human with no findings for the agent. Loop's own kind has no holdout. A narrower per check opt in is bl-177.
- **Tree equality with `git merge-tree` to judge staleness.** Any content change on the base changes the tree. It is useful instead for an early conflict check, bl-178.

## Issues

- bl-172: the queue, oldest merge wait first.
- bl-173: the merge phase holds no agent slot.
- bl-174: the merged head is pushed as soon as it exists.
- bl-175: an Actions timeout pauses the queue.
- bl-176: a base move in `recheck_skip_paths` costs no round.
- bl-177, bl-178, bl-179: follow ups to triage.

## Out of scope

Batching, a speculative train, GitHub's merge queue, commit statuses (bl-60), a red CI sent back to the agent with findings.
