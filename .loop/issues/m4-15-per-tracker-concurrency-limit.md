---
id: m4-15
title: "A per tracker concurrency limit, and a quieter retry requeue"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-14]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 115. Lane A, runs on the claude-code runner. Carries bl-77, findings 1, 2 and 4.

Area: `AgentConfig` in `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt` (`maxConcurrentAgents` and `maxConcurrentAgentsByState`, around lines 476 to 480) and its parsing, `SlotPlan.kt` (the slot checks around lines 75 to 83), `RetryCoordinator.requeueNoSlot` (around line 296), `loop.core.trackers`, docs.

What is known:

- **Decision for this issue,** confirmed by soule on 2026-11-18: the limit is `agent.max_concurrent_agents_by_tracker`, a map of tracker id to a positive count, beside the spec's `max_concurrent_agents` and `max_concurrent_agents_by_state`. It sits under `agent:`, beside the other concurrency limits. A tracker with no entry has no limit of its own. A dispatch needs a free slot under all three limits. Preflight refuses an unknown tracker id and a value below 1. The refusal is deliberate for a Loop key: its sibling `max_concurrent_agents_by_state` ignores bad values as the spec asks (`docs/spec-decisions.md`, around line 452), while a Loop key has no spec behavior to keep.
- **bl-77, findings 1 and 4.** While slots stay full, each waiting retry gets one `retry_scheduled` event, one WARN line, one by id tracker refresh and one store write per tick, with no bound (`RetryCoordinator.requeueNoSlot`). A per tracker limit makes more retries wait, and on GitHub each refresh spends API quota. Requeues of the same retry record the event and the WARN line once per change of reason, and refresh it by id at most once per poll interval.
- **bl-77, finding 2.** No test covers a due retry blocked by a per state cap while another state has a free slot. Add it, and the same case for a per tracker cap.

Lane A rule: the slot rule for trackers lives in `loop.core.trackers` (or `SlotPlan.kt`, which is already outside the orchestrator); the `Orchestrator.kt` edit is limited to call sites.

Out of this issue: bl-77 findings 3 and 5 (`firstDueAt` lost on restart and its early tick window).

## Acceptance criteria

- [ ] `agent.max_concurrent_agents_by_tracker` is parsed, and preflight refuses an unknown tracker id and a value below 1 (tests).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.TrackerConcurrencyConfigTest'` passes, a new test class of the key and its refusals.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.SlotPlanTest'` passes, with the per tracker cases.
- [ ] With a limit of 1 on one tracker and two of its issues eligible, one runs while another tracker's issue takes the next free slot (orchestrator test with two trackers).
- [ ] A due retry blocked by a per state cap, and one blocked by a per tracker cap, wait while another issue takes the free slot (tests).
- [ ] Repeated requeues of a waiting retry record `retry_scheduled` and the WARN line once per change of reason, and refresh it by id at most once per poll interval (test counting events and tracker calls over several ticks).
- [ ] `docs/spec-decisions.md` describes the per tracker limit and the requeue volume (judgment).
- [ ] The `Orchestrator.kt` diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-24 21:25Z loop: Loop opened pull request #48 for this issue: https://github.com/soulesidibe/Loop/pull/48

2026-11-24 21:29Z loop: Loop did not merge pull request #48 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff delivers the per tracker key with refusals, the slot rule in TrackerSlots, the quieter requeue, the tests and the docs, and Orchestrator.kt changes only call sites.
  - impact: medium, It changes the core slot planning and the retry coordinator's refresh and event recording, which every dispatch tick goes through, plus workflow config parsing.
  - verification: strong, New tests cover config refusals, the slot plan, the two tracker orchestrator case, and the requeue volume counted over many ticks, though the per state retry case rests on an existing test.
  - rollback_cost: two_way, The new queue fields live in memory only and the stored retry state format does not change, so a revert leaves nothing behind.
  - error_detection: partial, Bad config is refused loudly at preflight, but a retry judged on a cached issue copy could act on stale state for up to one poll interval without any signal.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 1 of 14 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
