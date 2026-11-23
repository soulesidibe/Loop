---
id: m4-40
title: "loop replay renders an attempt's prompt again and diffs it"
state: todo
priority: 3
labels: [deferred]
blocked_by: [m4-39]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 137, the first half of `loop replay` in PLAN 10.4. Lane C, a new CLI command. Not gating the exit. The second half, verification on the recorded fingerprint, is m4-41.

Premise, checked first: no Gradle edit needed (`cli` already depends on `spec-core` and `store`). If a Gradle edit turns out to be needed, stop and move the issue to `needs_info` naming it.

- **Command:** `loop replay <attempt> --prompt [--workflow WORKFLOW.md]`. `--prompt` is the only mode in this issue; with no mode the command says which modes exist.
- **The original:** the turn 1 rendered prompt from the Attempt file, as `loop show <attempt> --prompt` reads it (`cli/src/main/kotlin/loop/cli/ShowCommand.kt`, around lines 106 to 118).
- **The render:** the prompt template of the current `WORKFLOW.md`, with the same variables the attempt had: `attempt`, `loop.retry_kind`, `loop.verify_findings`, `loop.workspace_reused` and `loop.session_brief` from the attempt's recorded events where they exist (`spec-core/src/main/kotlin/loop/core/workflow/PromptVariables.kt`). The issue is the one the tracker returns now; the output says so, since its body may have changed since.
- **Output:** a unified diff, original first, with a header naming the attempt, the template versions of both, and every variable that could not be recovered. No diff prints "the same prompt".
- Secrets: the original is redacted already; the new render goes through the same redactor before it is printed.
- Read only: nothing written to the store, the tracker or the workspace.

Out of this issue: verification replay (m4-41), replay of later turns or of a rotation prompt, and the prompt fill and trimming of M3 beyond what the render itself does.

## Acceptance criteria

- [ ] `loop replay <attempt> --prompt` prints a unified diff between the recorded prompt and a render with the current template (test with a changed template).
- [ ] An unchanged template prints "the same prompt", exit 0 (test).
- [ ] A retry attempt renders with its recorded `loop.retry_kind` and `loop.verify_findings` (test).
- [ ] A variable that cannot be recovered is named in the header, and the render goes on (test).
- [ ] An unknown attempt exits 2, an attempt with no recorded prompt says so, exit 4 (tests).
- [ ] The new render is redacted before it is printed (test with a token in the issue body).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ReplayCommandTest'` passes.
- [ ] Premise checked first: no Gradle edit needed, and `diff_policy` refutes a protected edit (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
