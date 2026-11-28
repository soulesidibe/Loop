---
id: bl-273
title: "loop resume reads the issue from its own tracker, not only the primary"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m5-00]
area: [cli/src/main/kotlin/loop/cli/ResumeCommand.kt, cli/src/main/kotlin/loop/cli/OpenProject.kt, cli/src/test/kotlin/loop/cli, docs]
---

## Parent

None. Backlog, not scheduled in a milestone. Found on 2026-11-28 by the Fable 5.2 review of the M5 switch issues (m4-45). It blocks m5-01, which makes GitHub Loop's primary tracker and keeps the backlog on a `files` tracker as an extra one.

## What

`loop resume <id>` reads the issue through `project.tracker()` (`cli/src/main/kotlin/loop/cli/ResumeCommand.kt`, around line 56). That method builds the primary tracker only (`OpenProject.tracker`, `cli/src/main/kotlin/loop/cli/OpenProject.kt`, around line 98), and the `NamespacedTracker` it returns drops the ids of other trackers (`spec-core/src/main/kotlin/loop/core/trackers/NamespacedTracker.kt`). So for an issue of an extra tracker, the lookup finds nothing, and `acceptChangedCommands` (around line 160) prints "Cannot read the acceptance criteria" and accepts nothing.

After m5-01, every backlog issue parked for `acceptance commands changed` would park again at each verification, forever.

## Agent brief

`loop resume` reads the issue from the tracker its namespaced id names, through the project's tracker registry (`OpenProject.trackers`, `trackerRegistryFor`). A bare id stays the primary's (m4-47). The unpark and the acceptance of a changed list then work for an issue of any tracker of the workflow. Check the other one off commands that read an issue through `project.tracker()` (for example `loop approve`, `loop send-back`, `loop answer`, `loop show`) and list in a comment any that has the same gap. Fix them here only if the fix is the same call.

## Acceptance criteria

- [ ] A `cli` test with a primary tracker and an extra `files` tracker: an extra tracker issue parked for `acceptance commands changed` is unparked by `loop resume <tracker>:<id>`, and the new list is accepted and printed (test in `ResumeCommandTest`).
- [ ] The same for a primary tracker issue, by bare id and by namespaced id (test).
- [ ] An id naming a tracker that is not in the workflow is refused with nothing changed (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ResumeCommandTest'` passes.
- [ ] A comment lists the other one off commands checked, with any left with the same gap (judgment).
- [ ] `docs/spec-decisions.md` (the m4-47 commands section) says `loop resume` reads every tracker (judgment).
- [ ] `./gradlew --no-daemon build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments

2026-11-28 soule: written in m4-45, from the Fable 5.2 review of the M5 switch issues. Blocks m5-01. Absorbed by M5 (spec "What M5 absorbs").
