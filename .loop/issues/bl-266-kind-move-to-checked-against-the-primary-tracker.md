---
id: bl-266
title: "A kind's move_to can name a state per tracker, checked against each tracker the kind can match"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m5-00]
area: [spec-core/src/main/kotlin/loop/core/workflow, spec-core/src/main/kotlin/loop/core/kinds, spec-core/src/main/kotlin/loop/core/delivery, spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt, docs]
---

## Parent

None. Backlog, not scheduled in a milestone. Found on 2026-11-27 while setting up the m4-43 wave on the second pilot app: a `research` kind matching a `files` tracker (`match: { tracker: local }`) beside a GitHub primary tracker. Pulled ahead on 2026-11-28 (m4-45): it blocks m5-01, the move of Loop's own tracker to GitHub issues with the backlog kept on a `files` tracker. Rewritten after the Fable 5.2 review of the M5 switch issues.

## What

`delivery.on_delivered.move_to` (and `on_verified.move_to`) of every kind is checked against the primary tracker's terminal states (the m2-26 check, run for every kind since m4-18). With a GitHub primary (`closed`) and a `files` tracker (states `todo`, `in_progress`, `review`, `needs_info`, `done`, `canceled`), no single state is terminal in both trackers:

- `move_to: done` fails: `kinds.research.delivery.on_delivered.move_to is "done", which is not a terminal state, use one of closed.`
- `move_to: closed` with `closed` in the files tracker's `terminal_states` fails: `trackers[0].terminal_states has "closed", which is not a state of the files tracker.`

So a kind that takes issues of both trackers cannot move its delivered issues to a terminal state at all. Splitting the kind per tracker does not help Loop's own workflow either: only the `code` kind may deliver a pull request (`WorkflowFile.kt`, around line 545), and the merge check, the auto merge gate and the host read `kinds.code` alone.

## Agent brief

- **A map by tracker id.** `on_delivered.move_to` and `on_verified.move_to` accept either a string, as today, or a map from tracker id to state, such as `{ gh: closed, default: done }`. The move of an issue resolves through its own tracker: the map's entry for the issue's tracker id, or the string.
- **Checks at preflight.**
  - A map entry is checked against the terminal states of the tracker it names. An unknown tracker id in the map is refused.
  - A tracker the kind can match with no entry in the map is refused, naming the tracker.
  - A string is checked against every tracker the kind can match. A kind whose `match` names one tracker is checked against that tracker only, which keeps the second pilot app's `research` kind with `move_to: done` working. A default kind or a label or state match is checked against every tracker of the workflow.
  - Today's single tracker workflows load unchanged.
- **The moves.** The delivery applies the resolved state through the issue's own tracker, as today. An orchestrator test proves an approved `files` issue moves to `done` while a GitHub issue of the same kind moves to `closed`.
- **Docs.** `docs/spec-decisions.md` (the m4-18 kind map section and the m2-26 check) state the rule and the map.
- Out of this issue: other kinds delivering a pull request, which stays a `code` kind privilege.

## Acceptance criteria

- [ ] A workflow test: a GitHub primary `gh`, a `files` extra tracker `default`, and a `code` kind with `on_delivered.move_to: { gh: closed, default: done }` passes preflight.
- [ ] The same kind with a map missing `default`, or naming an unknown tracker id, fails preflight naming the tracker (one test each).
- [ ] A kind with `match: { tracker: local }` and `move_to: done` beside a GitHub primary passes, and the same kind with `move_to: closed` fails naming the `local` tracker's terminal states.
- [ ] A string `move_to` on a kind that can match both trackers fails, naming the tracker that lacks the state.
- [ ] A single tracker workflow with a string `move_to` loads as before (the existing workflow tests pass unchanged).
- [ ] An orchestrator test: an approved `files` issue moves to `done` and an approved GitHub issue of the same kind moves to `closed`.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.*' --tests 'loop.core.kinds.*'` passes.
- [ ] `docs/spec-decisions.md` states the rule and the map (judgment).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments

2026-11-28 soule: rewritten in m4-45 for the move of Loop's tracker to GitHub issues (a move map by tracker id instead of one kind per tracker, which cannot deliver a pull request). Blocks m5-01. Absorbed by M5 (spec "What M5 absorbs").
