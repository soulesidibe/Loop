---
id: m4-22
title: "Content delivery: attach to the issue, human by default"
state: todo
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
