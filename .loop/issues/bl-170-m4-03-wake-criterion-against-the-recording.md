---
id: bl-170
title: "m4-03's wake turn criterion does not match the recorded attempt 10c2f759"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

[Loop, M4](../specs/loop-m4.md). Backlog, found by the Fable 5.2 reviews of m4-00 steps 5 to 7.

## What

m4-03's second criterion says that, replayed on `wake_turns_10c2f759.jsonl`, turns 2 to 5 are wake turns and the attempt has used 2 of 10 turns after turn 6. The recording does not support it. `wake_turns_10c2f759.turns.json`, next to the fixture, gives each line's arrival time and where each of Loop's six inputs falls, from the attempt log and the ledger.

- The six turns end at fixture lines 190, 250, 279, 323, 330 and 365. Only turns 2 and 4 read a wake first: an `init` at lines 199 and 291, after a `task_notification`, each with its own `result` (lines 209 and 302, `origin.kind` `task-notification`). Turns 3 and 5 are plain Loop inputs. Read honestly, the attempt has used 4 of 10 turns after turn 6, not 2.
- Loop wrote each input about half a second after the previous `result`, before the wake `init` arrived (3.6 seconds later in turn 2, 10.7 seconds later in turn 4). Both wakes were caught only because a subagent `assistant` line (lines 191 and 280, a few milliseconds after Loop's input) set `waking`, the bl-122 behaviour m4-03 removes. Under m4-03's own rule and the real timing, maybe no turn of this attempt is a wake turn.

Decide before m4-03 is dispatched: what a wake turn is when the wake arrives after Loop's input, and rewrite the criterion to the counts the recording gives.

## Acceptance criteria

- [ ] m4-03's wake rule and its replay criterion agree with `wake_turns_10c2f759.turns.json` (judgment).

## Comments

2026-11-21 soule: written in m4-00, from the Kotlin review of steps 5 to 7.
