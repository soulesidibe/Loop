---
id: bl-150
title: "Low findings of the m3-25 merge risk review, GitHub real smoke test cleanup and lag"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found by the merge risk review of pull request #116 (m3-25, band medium 1.67 on Sonnet 5.5, held there only by the missing CI) and its Fable 5.2 recheck, nothing above low, on 2026-11-17. Merged on the local evidence.

## What to look at

Paths in `plugins/tracker-github/src/test/kotlin/loop/tracker/github/real/GitHubRealSmokeTest.kt`.

- A lost response on the issue create leaves an open orphan. `number` is set only after the POST returns and parses, so when the transport timeout or `RUN_CAP` fires after GitHub created the issue, cleanup skips the close, deletes both labels, and `leftBehind()` says issue none. The playground keeps an open, unlabelled issue. When `number` is null, list `issues?labels=<id>&state=all` and close what is found, before deleting the labels.
- The same for labels. `labels += label` runs after the POST, so a label created with a lost response is never deleted. Add it to `labels` before the POST; the delete already accepts 404.
- The review check can flake. `settle` waits only for `needs-review`, then asserts the ready label is gone, so a stale read between the two label writes fails the test. Put both conditions in `until`.
- The cleanup close check is not settled. One GET right after the PATCH can read a lagging `open` and report a false cleanup failure. Read the state from the PATCH response, or settle the read.

## Acceptance criteria

- [ ] Each case above is handled, or closed here with a comment saying why not.
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test` passes with the profile unset, the smoke test skipped.
- [ ] One enabled run against the playground passes (by a human), its output linked here.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29, no blocker. Its criteria stand.
