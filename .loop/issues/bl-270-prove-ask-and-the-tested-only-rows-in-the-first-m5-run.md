---
id: bl-270
title: "Prove ask, loop answer, the per tracker limit and the resource tools on Codex in the first M5 run"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. From m4-44 (retire the manager skill), 2026-11-27. Four gating parts of PLAN section 16 were never used in the m4-43 wave or any M4 run and are proven by tests only: `ask` and `loop answer` (m4-31, m4-52), the per tracker limit `max_concurrent_agents_by_tracker` (m4-15) and the resource tools on the Codex runner (m4-51). The owner accepted them as tests only for the retirement, on the condition that the first M5 run proves them.

## What

Loop's event store holds no `question_asked` or `question_answered` event, no `WORKFLOW.md` (Loop, Sigma, the pilot app) sets `max_concurrent_agents_by_tracker`, and the only Codex attempt predates M4. Nothing invites an agent to ask either: Loop's `WORKFLOW.md` says nobody answers questions during a run and sends product questions to `needs_info`, and the issue skills do the same.

"The first M5 run" means the first `loop run` that works on M5 issues, on Loop or on a pilot project, before M5's exit wave.

## Agent brief

Human lane: the prompt change touches `WORKFLOW.md`, a protected path, and the proof needs a real run. Before the first M5 run, add a short rule to the prompt that lets an agent call `ask` for a product question that blocks it, and say when to prefer `needs_info`. Set `max_concurrent_agents_by_tracker` in the run's workflow so one tracker's limit is lower than `max_concurrent_agents`. Give one issue to the Codex runner with a check or step that needs a resource.

## Acceptance criteria

- [ ] The prompt of the run's `WORKFLOW.md` tells agents when to call `ask` and when to use `needs_info`.
- [ ] One `question_asked` and its `question_answered` event from a real attempt, answered with `loop answer`, the attempt continuing on the answer. Trace linked here.
- [ ] One `candidate_skipped` (or equivalent) event caused by the per tracker limit, with the workflow line that set it. Trace linked here.
- [ ] One Codex attempt with a `resource_requested` and `resource_granted` pair. Trace linked here.
- [ ] m4-45 links this issue, and PLAN section 16 drops "proven by tests only" for each part proven.

## Comments
