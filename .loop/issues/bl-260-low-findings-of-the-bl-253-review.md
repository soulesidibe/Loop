---
id: bl-260
title: "Low findings of the bl-253 review, clone git redirect guards"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #109 (bl-253), Sonnet 5.5 reading the whole diff and a Fable 5.2 recheck that tried each redirect against real git 2.54 with Loop's pins, on 2026-11-27. Both found only lows and no bypass; approved and merged.

## What

1. `ReplaySites.kt:75-76` builds `WorkspaceGit()` and `WorkspaceFingerprint` with no clone scope (built from `ReplayCommand.kt:186`). bl-253's key interfaces listed it as a construction site. `loop replay` of a kept clone workspace reads it unpinned and unchecked, so a hostile `core.worktree` or `commondir` can make the fingerprint describe the project. Read only.
2. `WorkspaceFingerprint.kt:34`: the default `withClones` returns `this`, so a custom `Fingerprinter` gets no pins and no check, silently. `WorkspaceFingerprinter.withClones` rebuilds a plain `WorkspaceFingerprinter`, dropping any subclass override. Test double trap only today.
3. `CloneLayout.kt:320-325`: `CloneScope.covers` treats a folder whose real path is the project as not a clone, so a symlink `<workspaceRoot>/<key>` to the project would run unpinned and unchecked, and the symlink refusal in `refusal()` is never reached. `Workspaces.locate` rejects such a symlink today. Defense in depth.
4. `WorkspaceGit.kt:551`: `refStorage()` throws `IOException` on a git exit other than 0 or 1 (a corrupt clone config), and `CloneLayout.refusal` does not catch it, so `state()` throws instead of returning `Foreign` naming the key. Callers fail closed; only the message differs.
5. `CloneLayout.kt:409` `linked()`: `attributes(entry) ?: return null` fails open for an entry under `refs/` or `logs/` whose attribute read throws. No practical trigger found. The top level read fails closed.
6. Cost: every git process in a clone now runs an extra `git config --file` and a walk of `refs/` and `logs/` (`CloneLayout.kt:383-393`). Not measured. The issue accepted per call checks; worth a measurement on a repo with many loose refs.
7. Test gaps: a symlink at `.git/HEAD` or under `logs/`, a hard linked `MERGE_MODE`, an invalid clone config. The `core.worktree` test for the clone delivery import calls `clones.import`, not `CloneDelivery`.

Noted, no action: `writeBase`, `import`, `CloneDelivery.push` and `Orchestrator.writeCloneBase` now refuse an Absent or Foreign workspace instead of skipping it. The reviewers traced the call sites and found no regression for normal clones, plain kinds or worktree mode. The check to process start window (TOCTOU) stays out of scope until the M5 sandbox.

## Comments
