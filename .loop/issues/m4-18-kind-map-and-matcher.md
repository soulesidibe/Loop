---
id: m4-18
title: "Kind map and matcher, code unchanged, ledger rows hold the kind"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-17]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 118 (PLAN 6.2). Lane A, runs on the claude-code runner. First issue of the kinds family, on the exit path: the exit needs one content or research issue next to the code issues.

Today `KindsConfig` holds one field, `code` (`spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`, around line 40), and the parser refuses any other kind name (`WorkflowFile.kt`, around line 469, "kinds.$it is not supported, use code"). About 48 main source lines read `kinds.code` or `kinds?.code`, and about 126 test lines build `KindsConfig(` or read `.code` (in `spec-core`, `cli` and `conformance`). The main lines are most of them in `WorkflowFile.kt`, the rest in `cli/.../Host.kt`, `merge/AutoMergeGate.kt`, `merge/Merges.kt`, `verify/Holdouts.kt`, `workflow/AcceptanceCommandsTrust.kt` and some 16 places in `Orchestrator.kt`. The ledger hard codes the kind: `LedgerMeter.kt`, around lines 128 to 141, writes `"code"` whenever `kinds:` is present.

- `kinds:` becomes a map of named kinds. Each kind holds the slots `code` holds today (`verify`, `ready`, `delivery`) and a `match` entry, as in PLAN 13. The match keys built in M4 are `default` (`match: { default: true }`), `label` (`match: { label: content }`), `tracker` (a tracker id of the registry of m4-11) and `state`. A match on a front matter field waits for a field map in the issue model: `Issue` (`plugin-api/.../Issue.kt`) has none.
- The matcher is a pure function in `loop.core.kinds` (the package already holds `Readiness.kt` and `MarkdownSections.kt`): an issue gets the first kind whose match holds, in the order the kinds are written, and the default kind otherwise. Preflight refuses two defaults, a kind with no match and no default, and an unknown match key.
- `code` is unchanged: a workflow whose only kind is `code` with no `match` behaves exactly as today, `code` being the default. Existing workflows need no edit. `KindsConfig.code` stays as an accessor of the kind named `code`, so existing call sites and tests compile unchanged.
- The resolved kind of each issue reaches the attempt: the orchestrator reads the issue's kind where it reads `kinds.code` today, through one lookup. Code only paths (auto merge, merge check, `pinnedBase`) keep reading the code kind's settings in this issue; an issue of another kind takes them up when its kind lands (m4-20 to m4-23).
- `LOOP_KIND` joins the `LOOP_*` variables of every hook (`workspace/HookEnv.kt`), so a hook can branch on the kind.
- Ledger lines and rows hold the matched kind, not the constant (needed by `loop report` in m4-38).
- Out of this issue: a match on a front matter field, context sources and per kind templates (m4-19), verification without git (m4-20), the `content` and `research` kinds themselves (m4-21 to m4-23), a per kind runner.
- Decided by soule on 2026-11-18: no match on a front matter field in M4.

## Acceptance criteria

- [ ] A table test of the matcher covers default, label, tracker id and state matches, first match wins, and no match falling to the default (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.kinds.KindMatcherTest'` passes, a new test class of the matcher.
- [ ] Preflight refuses two default kinds, a kind with neither `match` nor default, and an unknown match key (a front matter field key included), one test each, and accepts a second kind name next to `code` (tests).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.WorkflowFileTest'` passes, with the new preflight cases.
- [ ] A workflow with only `code` and no `match` parses to the same config as before, and `KindsConfig.code` still compiles at every existing call site (test).
- [ ] `./gradlew --no-daemon :spec-core:test` passes, the existing suites unchanged.
- [ ] A CLI test on a temporary project with two kinds dispatches a labelled issue under the second kind and an unlabelled one under `code`, and the ledger rows of each hold its kind (test).
- [ ] Every hook gets `LOOP_KIND` (test on `hookEnv`).
- [ ] The orchestrator edit is limited to call sites: the matcher and the kind lookup live in `loop.core.kinds`, and `Orchestrator.kt` only calls them (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.2 describe the kind map, the matcher order, the match keys and `LOOP_KIND` (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
