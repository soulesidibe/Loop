---
id: m4-19
title: "Context sources per kind"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-18]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 119 (PLAN 8.6). Lane A, runs on the claude-code runner.

Each kind declares where its context comes from, as pointers the agent reads on demand, never as inlined text:

```yaml
kinds:
  content:
    context:
      files: [docs/brand/voice.md, posts/published/]
```

- `kinds.<kind>.context.files` is a list of paths relative to the workspace. Loop renders them as a short list, counted in the prompt budget's `context_sources` slot. The slot already exists (`spec-core/.../workflow/PromptSlots.kt`, `contextSources`, counted by m3 prompt fill) and is empty today.
- A path that does not exist in the workspace at render time is listed with a note that it is missing, and a `context_source_missing` event names it. It never fails the attempt.
- The list is exposed to the template as the prompt variable `loop.context_sources` (PLAN 8.9). **Sources appear only where the template places `loop.context_sources`.** The prompt is the template (the `WORKFLOW.md` body, protected, or the kind's own file), so Loop never inserts the list by itself. A kind that declares `context.files` with a template that never uses the variable is refused by preflight. Preflight renders the variable for every kind.
- Loop's own `code` kind declares no `context.files`, so its `WORKFLOW.md` needs no human edit.
- A kind may also set its own `template` (confirmed by soule on 2026-11-18; PLAN 13, `template: .loop/templates/<name>.md`), rendered instead of the `WORKFLOW.md` body for issues of that kind. Without it the body is used, so `code` is unchanged. A content or research issue would otherwise get the code prompt. Preflight refuses a template path that does not exist.
- Logic in `loop.core.kinds` or `loop.core.workflow`; the orchestrator only passes the kind to the render.
- Out of this issue: reading the sources for the agent, lessons (M6), a per kind runner.

## Acceptance criteria

- [ ] A kind's `context.files` renders as a list where the template places `loop.context_sources`, counted in the `context_sources` slot of the prompt budget, and an empty or absent list renders nothing (tests).
- [ ] Preflight refuses a kind that declares `context.files` with a template that never uses `loop.context_sources` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.kinds.ContextSourcesTest'` passes, a new test class of the sources.
- [ ] A missing path is listed as missing and records `context_source_missing`, and the attempt goes on (test).
- [ ] A kind's `template` is rendered for its issues and the body for `code` with no template; preflight refuses a template path that does not exist (one test each).
- [ ] Preflight renders the new prompt variable for every kind (test).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md`, PLAN 8.6 and 8.9 describe `context.files`, the per kind template, the variable and the preflight refusal (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
