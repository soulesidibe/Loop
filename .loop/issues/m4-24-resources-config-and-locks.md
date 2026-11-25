---
id: m4-24
title: "Resources config, the ResourceProvider interface and its loader"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-23, m4-01]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 122, config and provider part (PLAN 6.9 and 12). Lane A, runs on the claude-code runner. Split from the tools (m4-25) and the limits (m4-26) by finding H1 of the m3-29 review, then split again after the Fable 5.2 review: the locks and the queue are m4-50, the Codex half of the tools is m4-51. On the exit path: two issues of the pilot app run in parallel, each agent on its own simulator, and each check on its own simulator too (m4-26).

- **Config:** `resources:` is a list. Each entry has `kind` (`android-device`, `avd`, `ios-simulator`, `mac-app`, `display` or another string), `id`, `can` and `cannot` (free text lists shown to the agent), `restore` (a list of shell commands) and `read_only_check` (a shell command). Preflight refuses a duplicate id and a missing kind. A kind with no provider is a plain lock: `prepare` returns nothing. Preflight logs one line naming such a kind, so a typo shows.
- **The provider interface:** m4-01 creates `ResourceProvider` empty in `plugin-api`. This issue gives it its methods, the smallest set the providers of m4-27 and m4-28 need: the `kinds` it serves (a set, since m4-28 serves `android-device` and `avd`), `prepare` (make the resource usable, return what the agent needs, for example a UDID or a serial, as a small map), `release`, its read only check, and `recover(liveHolders)`, called once at startup with the holders of every live lock on the machine, so a provider can remove what a crashed run left. The startup call itself is m4-50's.
- **The contract:** a `ResourceProviderContract` joins the contract fixtures in `plugin-api/src/testFixtures/kotlin/loop/api/`, next to `TrackerAdapterContract` and `AgentRunnerContract`, and runs against a fake provider in `plugin-api`'s own tests. It covers `prepare`, `release`, the read only check and `recover`.
- **The loader:** a `ResourcePlugins.kt` in `cli`, like `RunnerPlugins.kt` and `TrackerPlugins.kt`, finds providers by ServiceLoader on `loop.api.ResourceProvider` (m3-04, m3-05).
- Logic in a new package `loop.core.resources`. This issue does not edit `Orchestrator.kt`.
- A new glossary term for Resource goes under `residual_human_steps` with its exact text: `CONTEXT.md` is protected.
- Out of this issue: the lock and queue rows and the no leak rule (m4-50), the tools that request and release (m4-25, m4-51), the limits, the read only check after release and the `resource:` key of checks (m4-26), the real providers (m4-27, m4-28).

## Acceptance criteria

- [ ] `resources:` parses with every field, preflight refuses a duplicate id and a missing kind, and a kind with no provider loads as a plain lock with one log line (one test each in `WorkflowFileTest`).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.WorkflowFileTest'` passes.
- [ ] `ResourceProviderContract` covers `prepare`, `release`, the read only check and `recover`, and runs against a fake provider (judgment).
- [ ] `./gradlew --no-daemon :plugin-api:test` passes.
- [ ] A fake provider registered through a test `META-INF/services` file is loaded by `ResourcePlugins.kt` and serves its kinds (CLI test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ResourcePluginsTest'` passes.
- [ ] `Orchestrator.kt` is not edited (judgment).
- [ ] The glossary text for Resource is listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.9 and 12 describe `resources:`, the plain lock and `ResourceProvider` (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 04:44Z loop: Loop opened pull request #58 for this issue: https://github.com/soulesidibe/Loop/pull/58
