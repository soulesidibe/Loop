---
id: m4-02
title: "Default limits keep the 25 percent rule, and a model with no window is visible"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-00]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Stories 101 and 102. Lane A, runs on the claude-code runner (no `runner-codex` label). Carries bl-144, the window findings of bl-138 and bl-125.

Area: `ContextConfig` in `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt` (around lines 506 to 525, the defaults and `DEFAULT_WINDOWS`), the limit check in `OccupancyMeter.kt`, the unknown window warning in `Orchestrator.kt` (the `unknownWindows` set around line 512 and its use around lines 3215 to 3230), `loop status` (`cli/.../StatusCommand.kt`, `SessionOccupancy.kt`), `loop trace`, docs.

What is known:

- **bl-144, decision taken in the M4 spec.** The hard limit fires on the first `context_usage` at or above it (`OccupancyMeter.kt:62`), so with the default 0.25 a session always peaks at or above 25 percent. Rebuilt from 171 attempts, the jump between two main session requests is about 1.3k tokens at the median, 9.7k at the 99th percentile, 27.1k at most. The default `hard_limit` becomes 0.21 (confirmed by soule on 2026-11-18), the value the M3 exit runs used; on a 1M window it leaves about 40k tokens of room for one jump. The default `soft_limit` stays 0.20, so `soft_limit <= hard_limit` still holds. Record the reasoning and the figures in `docs/spec-decisions.md`, and state in plan 8.2 that the rule holds when one request adds less than the gap (on a 475,000 window the gap is about 19k, below the largest jump seen).
- **bl-125.** A model that matches no `context.windows` key gives tokens with no fraction, with one WARN line per process (the set is never cleared) and no event, so `loop trace` and `loop show` cannot tell why the fraction is missing.
- **bl-138, window part.** No Codex model has a window entry, so rotation never fires for Codex unless the workflow names it, and a rerouted model with no entry silently stops it (`plugins/runner-codex-app-server/.../Telemetry.kt:130`). The Codex exit run used `openai/gpt-6.1-sol: 475000` (`docs/spec-decisions.md`, Codex exit run).
- In Loop mode with a runner that declares occupancy, a session whose model has no window records `context_window_unknown` (model, runner) once per attempt, and `loop status` shows the session as having no window.
- `DEFAULT_WINDOWS` ships `openai/gpt-6.1-sol` at 475000 beside `anthropic/claude-opus-5-5`.
- **The soft default, decided by soule on 2026-11-18:** `soft_limit` stays 0.20, the M3 exit pair with `hard_limit` 0.21. The cost is known and accepted: on the 475,000 window the gap between the two is 4,750 tokens, below the 9.7k jump of the 99th percentile, so some Codex rotations become hard interrupts in the middle of a turn instead of soft rotations at a turn's end.
- **A workflow that passes today is refused after.** A workflow that sets `soft_limit` between 0.21 and 0.25 and no `hard_limit` passes preflight today and is refused once the default hard limit is 0.21. The refusal names the default hard limit and says to set `hard_limit` or lower `soft_limit`. `docs/spec-decisions.md` gets a release note line for it.

Lane A rule: the logic goes in a new file beside `OccupancyMeter.kt` (for example `ContextWindows.kt`); the `Orchestrator.kt` edit is limited to call sites. Loop's own `WORKFLOW.md` already sets `hard_limit: 0.21`, so no protected file changes.

Out of this issue: the other bl-138 findings (token estimate, reviewer window, Codex `stop()`, interrupt during a tool call, `MODEL_REROUTED`, `CodexRotationRunTest`), reading `modelContextWindow` from the Codex runner instead of a shipped entry.

## Acceptance criteria

- [ ] `ContextConfig()` defaults to `hardLimit` 0.21 and `softLimit` 0.20, and preflight still refuses `soft_limit` above `hard_limit` (tests).
- [ ] A workflow with `soft_limit: 0.23` and no `hard_limit` is refused by preflight with a message that names the default hard limit 0.21 and says to set `hard_limit` or lower `soft_limit` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.ContextConfigTest'` passes, with the new default and refusal cases.
- [ ] A session whose model has no window records `context_window_unknown` with the model and the runner once per attempt, not once per process, and `loop trace` prints it (orchestrator test with the fake runner, two attempts).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.ContextWindowsTest'` passes, a new test class of the window logic.
- [ ] `loop status` shows a running session with no window as such, not as a blank fraction (CLI test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.StatusCommandTest'` passes, with the no window case.
- [ ] `DEFAULT_WINDOWS` holds `openai/gpt-6.1-sol` at 475000, and a Codex session on that model with no `context.windows` in the workflow gets a fraction and rotates at the soft limit (test).
- [ ] `docs/spec-decisions.md` records the bl-144 decision with its figures, the 4,750 token gap on the 475,000 window and a release note line for the newly refused `soft_limit` values; plan 8.2 states the rule with the one request gap; the example of `docs/PLAN.md` section 13 no longer shows `hard_limit: 0.25` (judgment).
- [ ] The window logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-23 02:14Z loop: Loop opened pull request #18 for this issue: https://github.com/soulesidibe/Loop/pull/18
