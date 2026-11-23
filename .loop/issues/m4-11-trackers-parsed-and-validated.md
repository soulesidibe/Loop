---
id: m4-11
title: "trackers: parsed and validated, the primary tracker kept"
state: todo
priority: 2
labels: [deferred]
blocked_by: [m4-10]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 111, config part. Lane A, runs on the claude-code runner. The first issue of the multi tracker registry (PLAN 6.1): it loads and checks `trackers:` and exposes the registry. Nothing polls the extra trackers yet (m4-13).

Area: a new package `loop.core.trackers` in `spec-core` (the registry and its config), the tracker parsing in `spec-core/src/main/kotlin/loop/core/workflow/WorkflowFile.kt` (around lines 170 to 180, `tracker.kind` through `TrackerCheck`) and `WorkflowFile`'s fields (`trackerKind`, `trackerDir`, `trackerSettings`, around lines 29 to 51), `TrackerCheck.kt`, `Preflight.kt`, the host's `trackerFor` in `cli/.../Host.kt` (around line 158), docs.

Decisions taken in the M4 spec and PLAN 6.1:

- The spec's `tracker:` stays the primary tracker. Its id is its `id` key, else `default`. `tracker.id` is a Loop key in the spec's block, confirmed by soule on 2026-11-18. A Loop `WORKFLOW.md` always keeps `tracker:`, so other implementations still pass preflight.
- `trackers:` is a list of extra trackers, each with `id`, `kind`, `provider`, and optional `active_states`, `terminal_states` and `required_labels` (the PLAN 6.1 example). Today `required_labels` is read only from `tracker:` (`WorkflowFile.kt` around line 288) and passed to the adapter for the handoff label removal (`Host.kt`, `trackerFor`); an extra tracker without its own would make every active issue a candidate, `ready-for-human` ones included. Each entry is checked by its own tracker plugin, as `tracker:` is today.
- Validation: ids are unique across `tracker:` and `trackers:`, match `[a-z][a-z0-9-]*`, and a kind with no tracker plugin is refused, all at preflight with the entry named. A secret of an extra tracker is redacted like the primary's (`secretValues` in `Host.kt`).
- The registry gives each tracker id its adapter, states and `required_labels`; the orchestrator still takes the primary adapter until m4-13.
- `loop run` with extra trackers logs one line that they are not polled before m4-13, so a workflow that sets them now is not silently half read.

Lane A rule: the registry and its parsing live in `loop.core.trackers`; `WorkflowFile.kt` and `Host.kt` only call it, and `Orchestrator.kt` is not edited.

Out of this issue: namespaced ids (m4-12), polling and reconciliation per tracker (m4-13), routed writes (m4-14), per tracker limits (m4-15).

## Acceptance criteria

- [ ] A workflow with `tracker:` and a `trackers:` list loads into a registry with the primary id (`id` key or `default`) and each extra tracker's kind, settings and states (tests).
- [ ] An extra tracker's `required_labels` are parsed, and the registry gives each tracker its own, the primary's from `tracker:` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.trackers.TrackerRegistryTest'` passes, a new test class of the registry and its parsing.
- [ ] Preflight refuses a duplicate id, an id that does not match `[a-z][a-z0-9-]*`, an unknown kind and an extra tracker whose plugin refuses its `provider`, naming the entry (one test each).
- [ ] A workflow with no `trackers:` loads exactly as before (test).
- [ ] `./gradlew --no-daemon :conformance:test` passes.
- [ ] An extra tracker's `$NAME` secrets are redacted like the primary's (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.SecretValuesTest'` passes, with the extra tracker case.
- [ ] `loop run` with extra trackers logs one line that they are not polled yet (CLI test).
- [ ] `docs/spec-decisions.md` describes `trackers:` and the primary id; the new term "tracker id" is listed with its proposed `CONTEXT.md` text under `residual_human_steps`, not edited (`CONTEXT.md` is protected) (judgment).
- [ ] The registry lives in `loop.core.trackers` and `Orchestrator.kt` is not edited (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
