---
id: m4-50
title: "Resource locks in the run store, grants in queue order, no leak"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-24]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 122, locks part (PLAN 6.9 and 12). Lane A, runs on the claude-code runner. Split from m4-24, which gives the config, `ResourceProvider` and its loader. Area: the store module, `loop.core.resources`, `cli/.../AbandonedAttempts.kt` and `loop status`.

- **Locks in the run store:** a lock row per resource (kind, resource id, holder attempt and issue, granted at, and a column for the map `prepare` returned, written by m4-25) and a queue row per request (kind, resource id, attempt, requested at), in the store module (`store/src/main/kotlin/loop/store/SqliteEventStore.kt` or a sibling). Locks are keyed by kind and id, machine wide, not by project: two projects on one machine share the store, so they share the locks. Grants follow queue order, first requested first granted.
- **One API for two processes:** the lock and queue calls live in `loop.core.resources` behind the store, so both `loop run` and the short lived `loop tool mcp` process (m4-25) can take or queue a lock.
- **No leak:** every lock and queue row of an attempt is freed when the attempt ends, whatever its exit, and on startup for attempts the abandoned attempt sweep finds (`cli/.../AbandonedAttempts.kt`). A freed lock records `resource_released` with `reason`. The same startup sweep calls each provider's `recover(liveHolders)` once, with the holders of every live lock on the machine (m4-24).
- **Visible:** `loop status` lists each resource, its holder and its queue.
- The orchestrator edit is limited to the call that frees an attempt's locks at its end and the startup call. Grep `Orchestrator.kt`, never read it whole.
- Out of this issue: the tools (m4-25, m4-51), the limits and the release check (m4-26), the real providers (m4-27, m4-28).

## Acceptance criteria

- [ ] The queue grants in request order and never grants one resource twice (table test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.resources.ResourceQueueTest'` passes.
- [ ] Locks and queue rows survive a store reopen, two store handles on one file see the same lock, and two projects asking for one kind and id share one lock (store tests).
- [ ] `./gradlew --no-daemon :store:test` passes.
- [ ] An attempt that ends by success, error, stop or crash frees its locks and queue rows and records `resource_released` (CLI test with the fake runner and lock rows seeded in the store, since the tools come in m4-25).
- [ ] The startup sweep frees the locks of an abandoned attempt and calls each provider's `recover` once with the live holders (CLI test with a fake provider).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ResourceLocksTest'` passes.
- [ ] `loop status` shows each resource with its holder and queue (test).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.9 and 12 describe the lock rows, their key, the queue order and the no leak rule (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-24 after the Fable 5.2 review.

2026-11-25 05:18Z loop: Loop opened pull request #59 for this issue: https://github.com/soulesidibe/Loop/pull/59

2026-11-25 05:21Z loop: Loop did not merge pull request #59 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.60.
  - alignment: aligned, The diff covers the lock rows, queue order, no-leak frees, startup sweep with recover, status output and docs. The orchestrator edit is one constructor parameter, one field and the free call in the attempt's finally block.
  - impact: medium (floor medium: the diff changes 666 lines outside tests and docs, over half the limit of 1000), It adds a schema version 8 migration to the machine-wide shared store and a new free step in every attempt's end path and in run startup, over 600 non-test lines.
  - verification: strong, Table tests cover the queue, store tests cover reopen, shared handles, shared projects, concurrency and the v7 migration, and CLI tests cover success, failure, stop and kill -9 with a fake provider's single recover call. A status test is included.
  - rollback_cost: costly, A revert leaves the shared store migrated to schema version 8 with the new tables, and it may leave stale lock rows and resource events behind.
  - error_detection: partial, A failed free or recover only logs a warning and the run continues, and a failed event record goes to the unrecorded callback as a warning. These are logged by design, but a leaked lock stays blocked until the next startup.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 6 of 20 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
