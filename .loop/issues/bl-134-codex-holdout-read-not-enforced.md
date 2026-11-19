---
id: bl-134
title: "The Codex runner does not enforce the holdout read deny under Loop mode"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: []
---

## Parent

None. Backlog. Found by the merge risk review of pull request #104 (m3-20) on 2026-11-16 and accepted by the maintainer as a known gap for m3-20, to be closed before a Codex agent runs a real Loop issue (m3-28 at the latest).

## What happens

m3-20 lifts the Codex provider's refusal to start under Loop mode. Its deny on reading the holdout folder is declared but not enforced: under the `untrusted` approval policy, Codex runs reads it classes as known safe (`cat`, `rg`, `sed -n`) without sending an approval request, so Loop's guard never sees them (`docs/spec-decisions.md` around line 2248). Writes into the holdout folder are refused, with symlinks resolved (`Guards.kt:134`).

## Direction

Find a way the pinned codex can enforce it: a sandbox setting that keeps the holdout folder out of the readable roots, an exec policy rule Loop writes and the agent cannot edit, or keeping the holdout files out of reach of the agent's user. If none works, keep the provider refusing to start under Loop mode whenever the contract has holdout checks. Prove it with a real Codex turn that tries `cat` on a holdout file.

## Comments

2026-11-18 soule: triaged ready-for-human in m3-29. It is a time boxed spike, and its deliverable is a written decision: a way the pinned codex enforces the holdout read deny, or keeping the provider refusing to start under Loop mode with holdout checks.

2026-11-18 soule: the Fable recheck of m3-29 found this issue named nowhere in the specs, PLAN or an M4 issue, while M4 lets Codex results auto merge (m4-06). It is now named in the M1 to M3 spec (the Codex runner section), in the M4 spec (Reliability) and in PLAN section 19. Until this spike is decided, a Codex workflow with holdout checks does not auto merge. The `blocked_by: [m3-20]` line is dropped, since m3-20 is done.
