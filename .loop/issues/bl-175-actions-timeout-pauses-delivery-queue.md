---
id: bl-175
title: "An Actions timeout pauses the delivery queue"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

Under bl-172's queue, an Actions incident would make each queued delivery wait up to `ci_timeout_ms` (45 minutes by default) in turn, then fall back to approval, one after another overnight. See the spec's "An Actions outage pauses the queue" decision.

- When the head's CI wait ends with `ci_finished` `outcome` = `timeout`, it falls back as today, then the queue of its project pauses: no merge wait starts its merge check.
- `red`, `no_run_fail` and any other refusal do not pause.
- The pause ends on `loop approve` of any issue of the project, or on a restart. It is held in memory only, like the runner hold (bl-39).
- Events `delivery_queue_paused` (`issue`, `reason`) and `delivery_queue_resumed` (`by` = `approve` or `restart`), shown by `loop trace`, the human log and `loop status`.
- Document it in the m3-15 section of `docs/spec-decisions.md`.

Area: `Orchestrator.kt` (grep, never read it whole), `spec-core/src/main/kotlin/loop/core/merge/Merges.kt`, the event and log texts.

## Acceptance criteria

- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.CiWaitOrchestratorTest'` passes, with tests for: a timeout pauses the next delivery; a red CI does not; `loop approve` resumes the queue.
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort.
