---
id: m4-23
title: "Kind research: report posted on the issue, required sections, sources reachable"
state: done
priority: 1
labels: [ready-for-agent]
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

2026-11-25 04:13Z loop: Loop opened pull request #57 for this issue: https://github.com/soulesidibe/Loop/pull/57

2026-11-25 04:17Z loop: Loop did not merge pull request #57 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff covers each acceptance item (report deliverable, require_sections, sources example under docs/, attach delivery, docs), and the orchestrator edit is limited to call sites. The Readiness refactor is a small shared-code change that serves the issue.
  - impact: medium, It touches the shared verification path, the workflow loader, the verdict event and the readiness check, but the new behavior only applies to kinds that declare deliverable.report.
  - verification: strong, Pure-function tests, verify tests, an end-to-end CLI run on the files tracker with approve, and a script test against a local server cover each acceptance criterion and the edge cases (empty, non-UTF-8, missing, closed port, 404).
  - rollback_cost: two_way, The change is additive code, config keys and docs, with no migrations or persisted schema change. The only new stored data is an optional event field (report_findings), so a revert is clean.
  - error_detection: loud, Every report failure refutes with a named finding, loader errors are explicit, an unreadable report leaves the result unverified, and the sources script exits 1 and prints each bad link.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 4 of 17 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
