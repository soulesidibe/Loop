---
id: bl-61
title: "Merge check low findings from the recheck of #69"
state: todo
priority: 3
labels: [deferred]
blocked_by: [bl-54]
---

## Parent

None. Backlog, not scheduled in a milestone.

## What

Found in the Fable 5.2 recheck of #69 (m2-26, first attempt to review) on 2026-11-14. The highs and mediums went back into m2-26; these are the lows. Line numbers on `loop/m2-26` at 2fe2bb3.

What is known:

- Slot leak on cancel before start (reasoned): the merge check launch (`Orchestrator.kt`, around line 3675) uses a lazy `async` whose `finally` frees the slot; a scope cancelled before the body starts never runs it. Only matters if an Orchestrator outlives its scope; `invokeOnCompletion` would be safer.
- Branch moved between the fingerprint check and the merge (reasoned): another issue's agent can `update-ref` `loop/<key>` in the shared repository after the check (around line 3839), so M is built on an unapproved commit, rechecked and merged. Checking that M's first parent is the stored head closes it (m2-26 send back 1 may already do this).
- `cat-file -t` proves presence, not integrity (demonstrated): a file planted at the tip's object path passes `record()` (`GitPush.kt`, around line 286). The merge then refuses it with a hash mismatch, so no wrong base is trusted.
- Duplicate `branch_pushed` (reasoned): recorded on every retry of the merge phase (around line 3981) even when the push was a no op.
- `on_delivered.move_to` is validated without `merge` (`WorkflowFile.kt`, around line 223): a workflow that already carried a non terminal `on_delivered` is refused at load, a small m2-25 behaviour change.
- The pull request body keeps the verified commit V after the merged head M becomes the delivered commit.
- Docs: `docs/spec-decisions.md` (around line 1623) says every workspace git command goes through `WorkspaceGit`, but `diff_policy`'s git in the recheck uses `CoreChecks.Git` (no hooks pin, check env); around line 1618 a timeout is listed as `delivery_failed`, but a fetch timeout is `Unavailable` and retried every tick, holding a slot for 5 minutes each time; "so a new approval replays cleanly" was false for the workspace not at the fingerprint case (m2-26 send back 1); `merge_check_finished` carries `head` on `refuted` and `up_to_date` too.

From the second Fable 5.2 recheck of #69, at 34e2342 (verdict merge, all low):

- A crafted merge commit passes the resume probe (demonstrated up to the push): `stoppedMerge` (`Orchestrator.kt`, around lines 4014 to 4021) checks only that HEAD has parents (stored head, stored `base_sha`), then restores the tree, and `checkPhase` (around 3888 to 3894) skips the fingerprint compare when resumed. Another process that runs `update-ref` on `loop/<key>` to a commit X with those parents plus an unapproved file gets X rechecked, pushed and stored as the merged head. Needs a hostile process with access to the shared `.git` and a wait whose `base_sha` is set. Cure: also require `X^{tree}` to equal `git merge-tree --write-tree <head> <intent>`.
- `rerere` hides a real conflict (demonstrated on real git): with `rerere.enabled=true`, `rerere.autoUpdate=true` and a recorded resolution, `git merge` exits 1 but `--diff-filter=U` lists nothing, so Loop records `delivery_failed` instead of a conflict, and every new approval fails the same way (`WorkspaceGit.kt`, `conflictedPaths` around 160, `MORE_PINS` around 278). Cure: pin `-c rerere.enabled=false`.
- H1 strand through the pull request lookup (reasoned): `runMergeCheck` (around 3811 to 3832) runs before `resumeStopped`, so after a stop that left the branch at M with the wait at V, a pull request closed by hand or not found is refused for good and the wait goes back to approval at V; every later approval then fails on the push (`GitPush.kt`, around 321). Same when `stoppedMerge` returns null at M on a transient git failure.
- A diverged local `main` still gets base commits blamed (reasoned): `fetchedBases` (around 2921) keeps `guards.base` when local `main` has unpushed commits, so after a failing recheck a protected path from the base refutes the agent again.
- Untested conditions (surviving mutations): the first parent check and the clean check after the restore in `stoppedMerge`, and the ancestor check in `fetchedBases`.
- Push before the reread (reasoned): `mergePhase` (around 4108 against 4133) pushes M to the pull request branch before `stillActive` stops the merge for an issue a human moved out. No merge happens.
- From the Sonnet merge risk pass: `awaitHead` passes through silently, with no event, when no pull request is found or after 10 reads still show another head, and blocks the background merge check for up to about 9 seconds.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] `./gradlew --no-daemon :spec-core:test --tests "loop.core.verify.WorkspaceGitTest"` passes, with a test on real git that a conflicting merge with rerere enabled, autoupdate on and a recorded resolution is a `MergeOutcome.Conflict`.
- [ ] `./gradlew --no-daemon :spec-core:test --tests "loop.core.orchestrator.MergeCheckOrchestratorTest"` passes, with tests: a branch moved after the fingerprint check merges nothing and pushes nothing; a merge commit with the right parents and an extra file is not resumed; a merge phase retried twice records one `branch_pushed`; the first parent check and the clean check of the resume and the ancestor check of `fetchedBases` each have a test that fails without them.
- [ ] `docs/spec-decisions.md` (merge check) is corrected on the four points named in the brief.

## Comments

2026-11-14 filed from the Fable 5.2 recheck of #69.

2026-11-14 added the lows of the second Fable 5.2 recheck of #69 and of the Sonnet merge risk pass.

> *This was generated by AI during triage.*

## Agent brief

**Category:** bug

**Summary:** Harden the merge check against a recorded rerere resolution and a branch moved under it, stop duplicate and missing events, and cover three untested guards.

**Current behavior:**
- With `rerere.enabled=true`, `rerere.autoUpdate=true` and a recorded resolution in the project's git config, a conflicting `git merge` of the base exits non zero but lists no conflicted path. Loop then records `delivery_failed` instead of a conflict, and every new approval fails the same way.
- The merge check compares the workspace fingerprint with the stored one, then merges. If the branch `loop/<key>` is moved in the shared repository between the two, the merge commit is built on a commit nobody verified, rechecked and merged.
- A stopped merge check is resumed when `HEAD` is a merge commit whose parents are the stored head and the stored base. Its tree is not compared with what that merge would produce, so a crafted commit with those parents and extra content is rechecked and pushed.
- `branch_pushed` is recorded on every retry of the merge phase, also when the push changed nothing.
- `awaitHead` gives up after its reads, or when no pull request is found, without an event or a log line.
- After the merged head M becomes the delivered commit, the pull request body still names the verified commit V only.
- Three conditions have no test that fails when they are removed: the first parent check and the clean check after the restore in `stoppedMerge`, and the ancestor check in `fetchedBases`.
- The spec decisions doc says every workspace git command goes through `WorkspaceGit` (the recheck's `diff_policy` git does not), lists a fetch timeout as `delivery_failed` (it is retried every tick), says a new approval replays cleanly for a workspace not at the fingerprint, and omits that `merge_check_finished` carries `head` on `refuted` and `up_to_date`.

**Desired behavior:**
- Every workspace git command runs with rerere off, so a conflict is always reported as a conflict: the comment, the findings and the send back to the agent are those of any other conflict.
- After Loop's merge, the merge commit's first parent must be the stored verified head and its second the fetched base. Otherwise nothing is rechecked or pushed, the merge commit is undone, and the wait is refused as for a workspace not at its fingerprint.
- A resumed merge commit is accepted only when, besides its parents, its tree equals the tree that merging the stored base into the stored head produces. Otherwise it is refused as today's "anything else". If the installed git cannot compute that tree (it needs git 2.38 for `merge-tree --write-tree`), the resume is refused, never trusted.
- `branch_pushed` is recorded once per merged head per wait.
- When `awaitHead` stops without seeing the pushed head, a log line at warn says which head the pull request showed, or that none was found. The merge still goes on and its sha guard decides.
- The pull request body, or a comment on the pull request, names the merged head M once M is the delivered commit, built from facts like the rest of the body.
- The docs say what the code does for the four points above.

**Key interfaces:**
- `WorkspaceGit` (`PINS`, `MORE_PINS`, `merge`, `MergeOutcome`, `parents`, `head`, `conflictedPaths`).
- The orchestrator's merge check phases (`checkPhase`, `stoppedMerge`, `resumeStopped`, `mergePhase`, `awaitHead`), `MergeCheck` names, `WaitingIssue` keys (`FINGERPRINT`, `BASE_SHA`, `MERGED_HEAD`, `VERIFIED_FINGERPRINT`).
- `PullRequestDelivery.BRANCH_PUSHED` and the pull request body builder.
- `Verification.fetchedBases`.

**Out of scope:**
- The order of the pull request lookup and the resume (H1 strand), a diverged local `main`, and pushing before the reread: they stay in bl-61 as notes for a design decision.
- Filters and merge drivers from `.gitattributes` (M5).
- CI wait (m2-30).

**Triage notes:**
- Scope: the deterministic hardening part. Priority 3 is right. If bl-53 (the playground merge run) is run by a human with `rerere.enabled`, the rerere item is the one that will bite.
- The two "hostile process with access to the shared `.git`" items (branch moved under the merge, crafted merge commit) share the threat of bl-28, bl-32 and the M5 notes of bl-55. They are cheap to close here, so the brief keeps them, but they do not raise the priority.
- Wontfix: slot leak on cancel before start. It can only happen if an orchestrator outlives its scope, which no code path does.
- Wontfix: `cat-file -t` proves presence, not integrity. The merge refuses a bad object, so no wrong base is trusted.
- Wontfix: `on_delivered.move_to` validated without `merge`. A non terminal `on_delivered` never worked.
- Kept as notes in this file, not in scope: the H1 strand through the pull request lookup (needs a design choice on the order of the lookup and the resume, and on what a pull request closed by hand means for a branch already at M); a diverged local `main` still gets base commits blamed (depends on bl-59, what the base ref of a project is, and bl-32, base pinning); the push before the reread (no merge happens, the cost is one pushed commit on the pull request branch).
- The `blocked_by` entry m2-26 is done and holds nothing back.

2026-11-14: approved in triage.

> *This was generated by AI during triage.*
Blocked by bl-54 so the two never run side by side: both edit pull request delivery and the merge check. Done blocker m2-26 removed.
2026-11-14: approved in triage.

2026-11-14 soule: deferred until M2 closes, only M2 mandatory work runs until then. The brief stands; set the label back to ready-for-agent to dispatch it.

2026-11-18 soule: kept deferred in m3-29: merge check lows, blocked by bl-54, low.
