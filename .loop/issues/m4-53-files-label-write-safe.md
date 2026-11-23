---
id: m4-53
title: "The files tracker label write is safe"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-01]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Lane C, in `plugins/tracker-files` only. Not gating the exit. Split from m4-33 after the Fable 5.2 review: a follow up writes a new file whole and never goes through `rewriteLabels`, so the two do not share code.

Premise, checked before the first edit: no Gradle edit needed. The fix stays in `plugins/tracker-files` and its tests. If a Gradle edit turns out to be needed, stop and move the issue to `needs_info` naming it.

Carries bl-16. `FilesTracker.rewriteLabels` (`plugins/tracker-files/src/main/kotlin/loop/tracker/files/FilesTracker.kt`, around line 463, called by `updateLabels` around line 212) rewrites the `labels:` entry by scanning lines and writes the result without parsing it back:

- A block list with a blank line or a column 0 `#` comment between items stops the scan, the remaining `- item` lines stay under the new flow line, the front matter no longer parses and the issue drops out of the tracker.
- A key written `"labels":` or `labels :` is not matched, so a second `labels:` line is added.
- A trailing comment on the `labels:` line is dropped, against "every other byte is kept".
- The two `TrackerError.Malformed` throws in `updateLabels` are unreachable: `fileOf` parses first and returns `NotFound`.

bl-16's approved brief stands: after a label write that succeeds, the file parses to the intended labels in order with every other field unchanged; when the tracker cannot produce such a text, the write fails with `TrackerError.Malformed` and the file is byte for byte what it was, nothing written first and repaired after. Handling a shape is better than refusing it, where simple. The KDoc and the tracker profile say what happens to a comment on the entry (replaced whole, or the write refused), the agent picks one.

Out of this issue: the state rewrite and the comment insert of the same tracker, the `NotFound` on a file that does not parse, and `createIssue` (m4-33).

## Acceptance criteria

- [ ] Premise checked before the first edit: no Gradle edit needed (judgment).
- [ ] One new test per bl-16 shape (blank line and column 0 comment inside a block list, a `"labels":` key, a `labels :` key, a trailing comment on the entry), each asserting the labels and the other fields read back, or `TrackerError.Malformed` exactly and an unchanged file (judgment).
- [ ] `./gradlew --no-daemon :plugins:tracker-files:test --tests 'loop.tracker.files.FilesTrackerTest'` passes.
- [ ] The label write tests of `TrackerAdapterContract` pass unchanged (judgment).
- [ ] `./gradlew --no-daemon :plugins:tracker-files:test --tests 'loop.tracker.files.FilesTrackerContractTest'` passes.
- [ ] `docs/plugins/tracker-files.md` states the label read back rule and what happens to a comment on the entry (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-33 after the Fable 5.2 review.

2026-11-23 05:54Z loop: Loop opened pull request #22 for this issue: https://github.com/soulesidibe/Loop/pull/22
