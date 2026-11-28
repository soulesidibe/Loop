---
id: bl-266
title: "Preflight checks a kind's move_to against the primary tracker, not the kind's own tracker"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found on 2026-11-27 while setting up the m4-43 wave on the second pilot app: a `research` kind matching a `files` tracker (`match: { tracker: local }`) beside a GitHub primary tracker.

## What

`delivery.on_delivered.move_to` of every kind is checked against the primary tracker's terminal states (the m2-26 check, run for every kind since m4-18). With a GitHub primary (`closed`) and a kind that only matches a `files` tracker (states `todo`, `in_progress`, `review`, `needs_info`, `done`, `canceled`):

- `move_to: done` fails: `kinds.research.delivery.on_delivered.move_to is "done", which is not a terminal state, use one of closed.`
- `move_to: closed` with `closed` in the files tracker's `terminal_states` fails: `trackers[0].terminal_states has "closed", which is not a state of the files tracker.`

So such a kind cannot move its delivered issues to a terminal state at all. The second pilot app's workflow dropped `on_delivered` for the kind, so approved reports land in `review` and a human closes them.

## Agent brief

Check a kind's `on_delivered.move_to` (and `on_verified.move_to`) against the terminal states of the tracker the kind matches when its `match` names a `tracker`; keep checking against the primary when it does not (the default kind, a label or state match, which can match issues of any tracker, then against every tracker the kind can match). Apply the move through the issue's own tracker as today.

## Acceptance criteria

- [ ] A workflow test: a GitHub primary, a `files` extra tracker `local`, and a kind with `match: { tracker: local }` and `on_delivered.move_to: done` passes preflight.
- [ ] The same kind with `move_to: closed` fails, naming the `local` tracker's terminal states.
- [ ] A kind without a `tracker` match keeps today's check, and a kind that can match issues of several trackers needs a state terminal in each, with a message naming the tracker that lacks it.
- [ ] An orchestrator test: an approved issue of that kind moves to `done` in the files tracker.
- [ ] `docs/spec-decisions.md` (the m4-18 section) states the rule.
- [ ] `./gradlew :spec-core:test --tests "loop.core.workflow.*" --tests "loop.core.kinds.*"` passes.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
