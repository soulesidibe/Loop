---
id: m4-22
title: "Content delivery: attach to the issue, human by default"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-21, m4-29]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 120, second half (PLAN 6.2 and 6.5). Lane A, runs on the claude-code runner. Area: the delivery executors (`spec-core/.../delivery/`), `ApproveCommand.kt` only if its message needs the new outcome (m4-29 owns that file and its `ApproveCommandTest`, so this issue waits for it), the tracker `addComment` path.

- A new delivery for files, `delivery: { attach: true }` (name settled by soule on 2026-11-18), for a kind that declares `deliverable.files` or `deliverable.report` (m4-21: behavior follows the keys, not the kind's name), in a new `loop.core.delivery` class beside `PullRequestDelivery.kt`. With `policy: human`, a verified content result posts its deliverable files on the issue as one comment (each file under its path, in a fenced block) and waits as `Waiting(approval)`, so I read the work where I approve it. `loop approve` then moves the issue to `delivery.on_delivered.move_to` (or `on_verified.move_to` when absent).
- A comment has a size cap, below GitHub's limit of 65,536 characters per comment. Files over it are listed by path, size and digest, with the workspace kept until the issue is delivered, so nothing is cut in silence. The cap is a constant named in the docs.
- Each file's fence is longer than any backtick fence inside the file, so a file holding a code fence never breaks the comment's fenced block.
- The comment goes through the tracker's `addComment`, so it works on `files` and GitHub alike, redacted like every Loop comment.
- `policy: auto` on a content kind posts and moves at once, with no approval. Preflight allows it only with the explicit opt in `delivery.attach_auto.enabled: true`, as `delivery.auto_merge.enabled` does for code, and the default stays `human` (PLAN 6.5: kinds with weak checks default to human approval).
- A content kind may still use `delivery.pull_request` on a `git` workspace as code does. That path is unchanged and not tested again here.
- Out of this issue: the research report (m4-23), uploads as real attachments (GitHub has no issue attachment API), a review rubric.

## Acceptance criteria

- [ ] With `policy: human`, a verified content result is posted on the issue as one comment holding each deliverable file, and the issue waits as `Waiting(approval)` (CLI test on the `files` tracker).
- [ ] `loop approve` on that wait moves the issue to its `on_delivered.move_to` and the message says so (test).
- [ ] A file over the size cap is listed by path, size and digest instead of inlined, and the cap is below 65,536 characters (test).
- [ ] A file holding a code fence is wrapped in a longer fence, so the comment's blocks stay whole (test).
- [ ] With the GitHub tracker on recorded responses, the same comment is posted through `addComment` (test).
- [ ] `policy: auto` without `delivery.attach_auto.enabled: true` is refused by preflight, and with it the comment and the move happen without approval (one test each).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.delivery.AttachDeliveryTest'` passes, a new test class of the comment builder, a pure function.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.5 describe `delivery.attach`, `delivery.attach_auto.enabled`, the cap and the default (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 03:11Z loop: Loop opened pull request #56 for this issue: https://github.com/soulesidibe/Loop/pull/56

2026-11-25 03:14Z loop: Loop did not merge pull request #56 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff implements delivery.attach, attach_auto opt-in, the 60,000 cap, longer fences, addComment posting and docs as the issue asks, with orchestrator edits limited to call sites.
  - impact: medium, It adds a new delivery path and changes the loader's preflight rules and the orchestrator's approval and auto flow, but only for kinds that opt in with attach.
  - verification: strong, A pure-builder unit test, orchestrator tests for human and auto, preflight refusals, and CLI tests on the files tracker and on recorded GitHub responses cover the acceptance criteria and the cap, fence and changed-file edge cases.
  - rollback_cost: two_way, A revert removes the code, and the only state left behind is comments already posted on issues and a new event reason, which older readers handle through the else branch.
  - error_detection: partial, Under human, a failed deliverable comment is only logged at warn and the wait still starts, so the operator may approve without having seen the files. Under auto, a failed comment moves nothing and is reported.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 4 of 13 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-25 03:24Z soule: sent back before merge, after the merge risk reviews of pull request #56 (Sonnet 5.5 then Fable 5.2, medium 1.67, two medium findings). Fix these on the same branch, then submit again. Line numbers are in `Orchestrator.kt` at ccc259e unless another file is named.
  - Medium: under auto, an issue can be moved with no deliverable posted. `postDeliverable` (around lines 4410 to 4412) returns success when `deliverableFiles` or `attemptId` is null, and `deliverAttached` (around 4405) then moves. `applySubmitted` (around 2145) delivers a second submitted result in the same drain (a tool move plus a `done` result file) from a fresh `Verification` built by `verification()` (around 2319), whose `deliverableFiles` is null. A restored wait with a null `wait.attemptId` (around 5400 to 5416) skips the post the same way. Fix: keep the verified state (deliverable files, fingerprint, passed checks) across the drain instead of delivering from a fresh instance, and make `postDeliverable` tell posted from skipped, so the auto path never moves when nothing was posted. Under human, the same case must not start the wait without the deliverable.
  - Medium: under auto, a deliverable that is not inlined still moves the issue. `AttachDelivery.post` succeeds when every file is only listed (over the cap, changed since its verification, not UTF-8, cannot be read), and with a terminal target the workspace is removed at once, so the content is lost. A check that rewrites the deliverable makes every file "changed" on every run (the listing is taken before the checks, around line 4022). Fix: under auto, move only when every deliverable file was inlined; otherwise fall back to the approval wait, as auto merge falls back to human, so the workspace stays. Make `AttachDelivery.post` report whether any file was only listed.
  - Tests for both: a tool move plus a `done` result file in one drain under auto with a failing first comment, asserting no move; an over cap file and a check changed file under auto, asserting no move to the terminal state and a wait instead.
  - Low: under human, a failed deliverable comment is only a WARN and the wait still starts (around 4377, return ignored). Add a line to the evidence summary ("the deliverable could not be posted: <reason>") or record an event.
  - Low: `deliver()` (around 4266 to 4270) routes attach plus auto without rechecking `attachAutoEnabled`, enforced only by the loader (`WorkflowFile.kt`, around 837 to 843). Recheck it.
  - Low: `AttachComment.block` (`AttachDelivery.kt`, around 127 to 134) puts `file.path` in a code span without joining lines, unlike `listLine`; a file name with a newline can inject markdown into a comment authored by Loop. Join the lines.
  - Low: the overflow line "listed in the deliverable evidence of the verdict" (`AttachDelivery.kt`, around 102 or 338) can point at evidence that was never saved; word it for that case and test it.
  - Low: under auto, a move failure after a successful post re-posts on the next try (around 4405); and a crash between `postDeliverable` and `startApprovalWait` re-posts. Guard against posting the same deliverable twice for one attempt if cheap.
  - Low: redaction runs after the cap math, so a `[redacted:NAME]` marker longer than the secret can push the comment slightly past 60,000. Apply the cap after redaction or leave a margin.

2026-11-25 03:44Z loop: Loop updated pull request #56 for this issue: https://github.com/soulesidibe/Loop/pull/56

2026-11-25 03:48Z loop: Loop did not merge pull request #56 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: aligned, The diff adds delivery.attach, the attach_auto opt-in, a 60,000 cap, longer fences, posting through addComment and the docs, and it also fixes the review findings (auto only moves when every file was inlined, the second result of a drain is delivered from the first's verification).
  - impact: medium (floor medium: the diff changes 548 lines outside tests and docs, over half the limit of 1000), It adds a delivery path and changes the orchestrator's approval and auto flow and the loader's preflight rules, but only for kinds that set attach (548 non-test lines).
  - verification: strong, A pure-builder test, orchestrator tests for the review-found failure cases, and CLI tests on the files tracker and on recorded GitHub responses cover the criteria; the cut-out test files could not be read here.
  - rollback_cost: two_way, A revert removes the code; only comments already posted and a new event reason stay behind, and older readers handle that reason through the else branch.
  - error_detection: partial, Under human a failed deliverable post is now named in the evidence summary but the wait still starts, and the dedup of posts lives in memory only, so a crash between the post and the wait or move can post twice.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 8 of 15 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
