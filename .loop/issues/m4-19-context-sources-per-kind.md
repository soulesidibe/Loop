---
id: m4-19
title: "Context sources per kind"
state: done
priority: 1
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

2026-11-25 01:04Z agent 23192414: m4-19 done in e19b346 and ea60245. Each kind's context.files renders as a "- <path>" list where the template places loop.context_sources (null when none), counted in the context_sources slot and cut first by the fill. A missing path is listed as "(missing from the workspace)" and recorded as context_source_missing, and the attempt goes on. kinds.<kind>.template (relative to the WORKFLOW.md folder) is rendered instead of the body. Preflight refuses a missing template path and a kind with context.files whose template never uses the variable, and renders every kind's template with the variable null and set. The logic is in loop.core.kinds.KindPrompt and ContextSources; Orchestrator.kt only passes the kind and the workspace. One limit to triage: a kind's template file is read with WORKFLOW.md, so an edit of the template file alone reaches attempts only at the next WORKFLOW.md reload or restart, because reload is keyed on the WORKFLOW.md hash. It is documented in spec-decisions and is a candidate backlog item. Tests: loop.core.kinds.ContextSourcesTest (12), ContextSourcesOrchestratorTest (2), and a new PromptFillTest case. All loop.core.* tests, the cli event, trace, kinds and approve tests, and ktlintCheck pass.

2026-11-25 01:09Z loop: Loop opened pull request #53 for this issue: https://github.com/soulesidibe/Loop/pull/53

2026-11-25 01:12Z loop: Loop did not merge pull request #53 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.27.
  - alignment: aligned, The diff implements context.files, the per-kind template, the variable, the missing-path event and the preflight refusals as the issue asks. Orchestrator edits are call sites only.
  - impact: medium, It changes the prompt render, preflight, slot measurement and workflow loading for every kind, but code kinds without the new fields render as before.
  - verification: strong, A new ContextSourcesTest class (12 tests), an orchestrator test class and a PromptFillTest case cover each acceptance criterion, including the missing-path, template and preflight cases.
  - rollback_cost: two_way, The change is code and docs only, with no migration or persisted-format change. The new event type and the template_version hash are additive, so a revert is clean.
  - error_detection: loud, Bad context paths, a missing template and unused sources fail preflight with invalid_config. A missing source records a context_source_missing event and is marked in the prompt.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 4 of 16 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
