---
id: bl-171
title: "The earlier work block's heading is wrong in a reused workspace"
state: done
priority: 3
labels: [ready-for-human]
blocked_by: []
---

## Parent

[Loop, M4](../specs/loop-m4.md). Backlog, found by the Fable 5.2 reviews of m4-00 steps 5 to 7.

## What

Lane: human, since `WORKFLOW.md` is a protected path. Since m4-00 step 5, the earlier work block of `WORKFLOW.md` (`{% if attempt or loop.workspace_reused %}`, around line 109) also renders on a first dispatch into a reused workspace (`attempt` null: after a restart, `loop resume`, or a return from `needs_info` or `review`), where the heading "This is not the first attempt" clashes with a first dispatch, and is false for a folder no attempt left. On a retry into a recreated workspace (a failed `after_create` deletes the folder), "already worked on this issue in this workspace" is false, and in a retry after a failed `after_create` or `before_run` no agent ran at all.

New text of the block, the rest of the prompt unchanged:

```
# Earlier work on this issue

An earlier attempt may have left work on this issue in this workspace or on the current branch. Read your progress file if it exists, `git log` and `git status` on the current branch first, then build on what is there. Do not start over and do not redo steps already marked as passing. If there is no progress file, create it from what the branch already holds.
```

Area: `WORKFLOW.md` (lines 110 and 112), its copy `spec-core/src/test/resources/prompt/own-template.md` (lines 14 and 16, which `OwnWorkflowTest` compares with the root prompt), `cli/src/test/kotlin/loop/cli/OwnWorkflowTest.kt` (the two tests on the block, lines 229, 230, 241 and 243, asserting on the new heading, never quoting the old one), and `docs/spec-decisions.md` (lines 1088 and 1095, which must not quote the old heading either). `own-template.golden.md`, `PromptRenderUnchangedTest` and `WorkspaceReusedPromptTest` stay unchanged.

## Acceptance criteria

- [x] `grep -q '^# Earlier work on this issue$' WORKFLOW.md && ! grep -rq 'not the first attempt' WORKFLOW.md spec-core/src/test docs/spec-decisions.md cli/src/test` passes.
- [x] `grep -q '^# Earlier work on this issue$' spec-core/src/test/resources/prompt/own-template.md` passes.
- [x] `! grep -qF 'already worked on this issue in this workspace' WORKFLOW.md` passes.
- [x] `./gradlew --no-daemon :cli:test --tests 'loop.cli.OwnWorkflowTest'` passes, with a retry and a reused workspace each rendering the new heading and sentence, and a fresh first dispatch rendering neither.
- [x] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.PromptRenderUnchangedTest'` passes, with the golden file unchanged.
- [x] The text is true for a retry in the same workspace, a retry after a failed `after_create`, a first dispatch into a reused workspace, and both at once (judgment).
- [x] `./gradlew --no-daemon build` passes.

## Comments

2026-11-21 soule: written in m4-00, from the product review of steps 5 to 7.

2026-11-21 soule: triaged to ready-for-human, priority 3, after a Fable 5.2 review at high effort, which hedged the first sentence (no agent ran in a retry after a failed setup hook) and added the criterion on the second phrase.

2026-11-21 soule: done in a human lane session. New heading and first sentence in `WORKFLOW.md` and `own-template.md`, `OwnWorkflowTest` asserts on them through shared constants (a fresh first dispatch renders neither, a reused workspace and a retry into a reused one render the heading once), `docs/spec-decisions.md` follows. Criteria 1 to 5 and 7 pass, criterion 6 judged true by the session and a Fable 5.2 review at high effort (a reused `loop/<id>` branch keeps earlier commits after a recreated folder).
