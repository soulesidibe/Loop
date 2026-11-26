---
id: bl-246
title: "Low findings of the bl-28 review, clone workspaces"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #98 (bl-28), Sonnet 5.5 and a Fable 5.2 recheck that replayed the git commands, on 2026-11-26. Attempt 1 was sent back for four mediums (the import copied the whole history, a half made clone could be reused with `origin`, the pull request push skipped the import, a clone ref could pick the diff base); attempt 2 fixed them, confirmed by replay, and its reviews found only the lows below; approved. Priority 2 for items 1 and 2, which the recheck would do first.

## What

1. **Lazy fetch can run an agent program on the host.** With `extensions.partialClone` set to an `ext::` URL, `protocol.ext.allow=always` and a branch ref at a missing object in the clone's config, the pinned `rev-parse` of reuse (and `diff`) ran the program as the operator. `git remote` lists nothing, so reuse does not remove it. Needs a hostile agent, same class as bl-55, already present with worktrees. Fix: `GIT_NO_LAZY_FETCH=1` in the `WorkspaceGit` and `CoreChecks.Git` environment, or `protocol.allow=never` in the pins, with a test.
2. **Create fails when the project has `loop/<key>` checked out.** The clone already makes that local branch, so `checkout -b` fails with "a branch named 'loop/<key>' already exists" on every attempt until the operator switches away; the import also moves a branch the operator has checked out, with no warning. Fix: `checkout -B`, and a warning (or refusal) when the project has `loop/<key>` checked out.
3. **False comment on `IMPORT_PINS`.** It says the `-c` pins reach the `upload-pack` in the clone and win over its config; replayed, `GIT_CONFIG_PARAMETERS` is empty there. No effect today, but fix the comment and the doc sentence.
4. **The session brief resolves a base name in the clone.** For a kind without pull request delivery, or when the fetch failed, `SessionBrief.workspace` resolves `guards.base` in the clone. Prompt text only, but it contradicts "never reads a ref of the clone". Pass the project resolved id.
5. **Cleanup keyed on `createdNow`.** `prepare` cleans up on `prepared.createdNow`, not on "clone made by this call". With a folder left empty earlier, a failed `create` leaves its keep ref (a retry overwrites it, removal drops it), and a failure or cancel in `writeBase` right after a successful create leaves the clone to be reused without `after_create`. Track "made by this call".
6. **The build folder is shared by two attempts of the same key.** `.loop-clone-<name>` is one path per key, so a second concurrent create would delete the first one's build. Orchestrator serialization makes it unlikely; add a unique suffix or a per key lock. A build folder left by a hard kill is only deleted by the next create of the same key, removal does not touch it.
7. **Reuse does not check HEAD is on `loop/<key>`** (the brief asked for it). With the build aside it is no longer a sign of a half made clone, only of an agent switching branches; check `symbolic-ref HEAD` or document it.
8. **One base keep ref per key, overwritten.** An earlier base commit merged into the clone is protected only once imported. Edge case.
9. **Project commits outside the haves are copied again.** An agent commit on top of a project commit not reachable from the keep refs, `loop/<key>` or `refs/loop/base/*` re-copies those objects (about 2 MB in the replay). Bloat only.
10. **A kill after the move but before `after_create`** leaves a clone reused without `after_create`, as with worktrees.

Area: `spec-core/src/main/kotlin/loop/core/workspace/WorkspaceClones.kt`, `WorkspaceManager.kt`, `WorkspaceGit`, `CoreChecks.kt`, the session brief, `docs/workspaces.md`.

## Comments
