---
id: m4-23
title: "Kind research: report posted on the issue, required sections, sources reachable"
state: todo
priority: 1
labels: [deferred]
blocked_by: [m4-22]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 121 (PLAN 6.2). Lane A, runs on the claude-code runner. Builds on the plain workspace and `populate: empty` of m4-20 and the attach delivery of m4-22. As in m4-21, behavior follows the keys, not the kind's name: "a research kind" below means a kind that declares `deliverable.report`.

- **Empty workspace:** a research kind declares `workspace: plain` and `populate: empty` (built in m4-20), so Loop creates the folder and runs none of the workspace hooks for it: `after_create`, `before_run`, `after_run` and `before_remove` are all skipped. `LOOP_KIND` (m4-18) stays available for workflows that prefer hooks.
- **Deliverable:** one report file, `deliverable: { report: report.md }` (key settled by soule on 2026-11-18). Missing or empty is refuted with a finding.
- **Required sections:** `verify.require_sections: [Summary, Sources, ...]`, checked with the existing heading reader (`loop.core.kinds.MarkdownSections`), a finding naming each missing section.
- **Sources reachable:** the links of the report's sources section are checked by a `command` check the workflow declares, for example a script that requests each URL with a timeout. No new check type (bl-14, bl-116, bl-118 deferred). The issue ships the script as a documented example under `docs/`, not under `scripts/` (protected).
- **Delivery:** the report is posted on the issue as one comment through the attach delivery of m4-22 (`delivery.attach: true`), `policy: human` by default, so I read it before it is closed.
- Logic in `loop.core.kinds`; the orchestrator only calls it.
- Out of this issue: a review of the content (M6), `ops`.

## Acceptance criteria

- [ ] A research issue gets an empty workspace with none of the four workspace hooks run and a `tree:` fingerprint (CLI test with the fake runner writing `report.md`).
- [ ] A missing report and a report missing a required section are refuted, each finding naming what is missing (one test each).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.kinds.RequiredSectionsTest'` passes, a new test class of the section check, a pure function.
- [ ] The example sources check fails on an unreachable link and passes on a reachable one, run against a local test server (test).
- [ ] A verified report is posted on the issue and waits as `Waiting(approval)`, then `loop approve` moves it (test on the `files` tracker).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.2 describe the research kind, `deliverable.report`, the skipped hooks and the sources example (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
