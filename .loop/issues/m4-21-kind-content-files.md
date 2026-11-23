---
id: m4-21
title: "Kind content: files deliverable, command checks on a worktree"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-20]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 120, first half (PLAN 6.2). Lane A, runs on the claude-code runner. Split from the delivery (m4-22) by finding H1 of the m3-29 review.

- **Behavior follows the keys, not the kind's name.** `kinds:` is a free map (m4-18) and `CONTEXT.md` allows custom kinds, so `deliverable.files` gives the files deliverable and `deliverable.report` the report (m4-23), whatever the kind is called. "A content kind" below means a kind that declares `deliverable.files`.
- **Key names, settled by soule on 2026-11-18:** `deliverable.files` and `deliverable.report` for the deliverables, `delivery.attach: true` for the attach delivery and `delivery.attach_auto.enabled: true` for its opt in to `policy: auto` (both m4-22).
- A content kind's deliverable is files: the kind declares `deliverable: { files: [<glob>, ...] }`, paths relative to the workspace, for example `out/**/*.md`. After the agent submits, Loop lists the files that match and records them with the verdict (paths, sizes, digests). No match is a finding, so the result is refuted with "no deliverable file".
- The workspace is a git worktree, populated by `after_create` like `code` (`workspace: git` from m4-20). A content kind on a `plain` workspace works the same through the `tree:` fingerprint.
- The checks are `command` checks the workflow declares: banned words and dashes, length limits, markdown lint. No new check type: bl-14, bl-116 and bl-118 are deferred after M4. The issue ships a worked example in the docs that runs on BSD and GNU grep: `! grep -rnE "$(printf '\342\200\224|\342\200\223')" out/` (exit 1 on a file with an em dash, checked on macOS). The example must fail on an em dash under macOS grep. PLAN 13's `grep -rnP` form is corrected in the same change: macOS grep has no `-P`, the usage error exits non zero, and the `!` turns it into a pass on a file full of dashes.
- A content kind keeps the default `delivery.policy: human` of every kind (`CONTEXT.md`, Delivery policy), and `policy: auto` on a kind with `deliverable.files` is refused by preflight until m4-22 defines what auto means for it.
- The kind's template and context sources come from m4-19.
- Logic in `loop.core.kinds`; the orchestrator only calls it.
- Out of this issue: attaching the files to the issue (m4-22), a review rubric (M6), new check types.

## Acceptance criteria

- [ ] A content issue on a temporary project is dispatched under `content`, its files matching `deliverable.files` are recorded with the verdict, and its `command` checks run in the worktree (CLI test with the fake runner).
- [ ] A submission with no file matching `deliverable.files` is refuted with the finding "no deliverable file" (test).
- [ ] A banned dash in a deliverable fails the example `command` check and the finding names the file and line, and a file with no dash passes it (test).
- [ ] Preflight refuses `policy: auto` on a kind with `deliverable.files` (test).
- [ ] A kind with another name that declares `deliverable.files` behaves the same as one named `content` (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.kinds.DeliverableFilesTest'` passes, a new test class of the deliverable listing, a pure function.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.2 describe the content kind, the rule that behavior follows the keys, `deliverable.files` and the example checks (judgment).
- [ ] `! grep -q 'grep -rnP' docs/PLAN.md` passes, PLAN 13's example corrected.
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
