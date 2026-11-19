---
id: bl-151
title: "Carry the last session into the next one: resume after a stop, a transcript digest in the brief"
state: todo
priority: 3
labels: [deferred]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Raised by soule during m3-27 on 2026-11-18, after `loop stop` canceled attempt 2 of bl-132 mid turn at about 65k tokens of occupancy. To be grilled before M4 planning (m3-29).

## What to look at

Loop records everything the agent wrote, yet the next session starts from the session brief and the agent's progress file only. That is by design: a rotation exists to drop context, so it never respawns with `--resume` (plan 8.4, m3-11), and the brief is built from facts Loop can check, never from what the agent claimed (m3-10). A summary written by a model would cost a call, can fail, and carries claims the verify model never trusts.

A stop is not a rotation, though: the session was not full. Two options, not exclusive:

- **A. Resume the runtime session after a stop or an error retry.** Keep the `runtime_session_id` of a session that ended on `stop_requested` (or an error) below the soft limit, and open the next attempt with Claude Code's `--resume` on it, in the same workspace. The same conversation at no extra cost. Open questions: the transcript lives in Claude Code's own store, outside Loop; the runner capability it needs (Codex has thread resume); what happens when the workspace changed in between (a human edit, a base merge); and how occupancy and the 25 percent rule carry over.
- **B. A mechanical transcript digest as a new part of `loop.session_brief`.** From the stored `runner_output` events of the last session, extract its own text messages and the list of its tool calls (files read, commands run and their exit), bounded and newest first like the other parts, with no model call. Helps after a rotation too, where `--resume` stays out. Open questions: `runner_output` lines are cut at about 2,000 characters today (about a quarter of the stored assistant lines, see bl-107), and the digest must not crowd the prompt slots of plan 8.5.

## Comments

2026-11-18 soule: filed from the m3-27 session, to grill later.

2026-11-18 soule: deferred in m3-29 triage, as the outline assumes. No M4 slice carries it. Grill it after M4.
