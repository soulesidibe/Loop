---
id: m4-34
title: "Reactions config and the merge_conflict reaction"
state: todo
priority: 3
labels: [deferred]
blocked_by: [m4-33]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 131, and the `reactions:` config of PLAN 6.7 that m4-35 to m4-37 build on. Lane A, run on the claude-code runner. Not gating the exit.

Today a conflict is found only by the merge check, at merge time (`spec-core/src/main/kotlin/loop/core/merge/Merges.kt`, around line 448, with `MergeCheck.conflictComment` and `conflictFindings` in `spec-core/src/main/kotlin/loop/core/delivery/MergeCheck.kt`). A pull request Loop opened that then waits is never looked at again, so a conflict with main waits for me to notice.

- **The watched set, for every reaction:** every wait that holds a pull request number, whose issue is not terminal. Under the `human` policy the pull request is opened after the approval, so in practice the watched waits are the fallback ones: an unapproved `Waiting(approval)` after a `delivery_failed` that left the pull request open, or after a fallen back auto merge. An issue delivered to a handoff state without `merge` (in `review`, no wait) is not watched in M4, and m4-36 and m4-37 say so.
- **Config:** `reactions:` in `WORKFLOW.md`, parsed in `spec-core/.../workflow/WorkflowConfig.kt` with the four keys of PLAN 6.7 (`ci_failed`, `changes_requested`, `merge_conflict`, `approved_green`), each with `action` (`send_to_agent` or `notify_human`) and its options (`retries`, `escalate_after`). Preflight refuses an unknown reaction, an unknown action and a bad option. Absent means no reaction, as today. This issue acts only on `merge_conflict`; the other keys parse and do nothing until their issue.
- **Detection:** on a tick, at most one poll per watched pull request with a 30 second floor, through the run's delivery client (the memoized executor of m3-15, so a rate limit hold lasts across ticks). It reads `mergeability(number)` (`plugins/tracker-github/.../GitHubPullRequests.kt`, around line 173); `mergeable_state` `dirty` is a conflict, `unknown` or a null `mergeable` keeps waiting.
- **Action `send_to_agent`:** the wait ends, the issue's workspace resumes with a continuation turn rendered with `loop.retry_kind` = `reaction`, the findings are those of `MergeCheck.conflictFindings`, then the result is verified again. A new `retry_kind` value joins `PromptVariables.RETRY_KINDS`, so preflight renders it, and the list assertion of `PromptVariablesTest` gains it.
- **Bound:** a reaction attempt takes a `max_attempts` slot, like the merge check's conflict path; the last one parks with `max_attempts`. So a base that keeps moving cannot send the issue back without end, though `merge_conflict` has no `retries` key. m4-35 to m4-37 follow the same rule.
- **Logic** in a new package `loop.core.reactions` (`spec-core/src/main/kotlin/loop/core/reactions/`). `Orchestrator.kt` (5,327 lines) gets call sites only. Grep it, never read it whole.
- **Events:** `reaction_triggered` (reaction, action, issue, pull request number) and `reaction_skipped` (reason), shown by `loop trace`.
- A new glossary term for Reaction goes under `residual_human_steps` with its exact text: `CONTEXT.md` is protected.

Out of this issue: `ci_failed`, `changes_requested` and `approved_green` (m4-35 to m4-37), any change to the merge check's own conflict path, and pull requests of issues delivered to a handoff state without a wait.

## Acceptance criteria

- [ ] `reactions:` parses the four keys of PLAN 6.7, and preflight refuses an unknown reaction, an unknown action and a bad option, one test each.
- [ ] A waiting pull request whose `mergeable_state` is `dirty` ends the wait and resumes the workspace with a continuation turn, `loop.retry_kind` = `reaction` and the conflict findings, then verifies again (test on recorded GitHub answers with the fake runner).
- [ ] A reaction attempt takes a `max_attempts` slot, and the last one parks with `max_attempts` (test).
- [ ] `unknown`, a null `mergeable`, an unreachable API and a rate limit hold keep the wait and record nothing, one poll per tick at most with a 30 second floor (tests).
- [ ] With no `reactions:` key, a conflicting waiting pull request is left as today (test).
- [ ] `reaction_triggered` and `reaction_skipped` are recorded and shown by `loop trace` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.reactions.*'` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.PromptVariablesTest'` passes.
- [ ] The logic lives in `loop.core.reactions`, and the diff of `Orchestrator.kt` holds call sites only (judgment).
- [ ] The glossary text for Reaction is listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md` describes the `reactions:` keys, the watched set, the slot rule, the poll and the `reaction` retry kind, and PLAN section 6.3 lists the events (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
