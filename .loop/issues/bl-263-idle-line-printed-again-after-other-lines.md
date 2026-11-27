---
id: bl-263
title: "An idle run prints its idle line again after any other line of the human log"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Seen on 2026-11-27: after both runs went idle, `loop log` ended on "WORKFLOW.md reloaded" (Loop) and "moved to closed after its checks passed" (ayyam), so an idle run looked unfinished.

## What

`HumanLog.polled` (spec-core/src/main/kotlin/loop/core/log/HumanLog.kt, around line 116) writes "nothing to do, waiting for eligible issues" only when `idle` turns true. Only a poll with eligible issues or running attempts sets `idle` back to false. Any other line written while idle (a workflow reload, a delivery of an approved wait, a merge, a move) becomes the last line, and the idle line never comes back until the run gets work. The machine log (`event=poll outcome=idle`) is right; only the human log misleads.

## Agent brief

Reset the idle mark whenever the human log writes any line other than the idle line itself, so the next idle poll prints the idle line again. Keep the line printed once per idle stretch: no repeat on every poll while nothing else was written.

## Acceptance criteria

- [ ] A test: an idle run prints the idle line once; a later line (a workflow reload, then a delivery of an approved wait) followed by an idle poll prints the idle line again, as the last line.
- [ ] A test: consecutive idle polls with nothing else written print the idle line only once.
- [ ] The eligible and running cases are unchanged (existing HumanLog tests pass).
- [ ] `./gradlew :spec-core:test --tests "loop.core.log.*"` passes.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
