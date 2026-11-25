---
id: m4-26
title: "Resource limits, the read only check after release, and resources for checks"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-51]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 123 (PLAN 6.9). Lane A, runs on the claude-code runner. Area: `loop.core.resources`, the release path of m4-25, the checks (`spec-core/src/main/kotlin/loop/core/verify/Checks.kt`) and the merge check.

- **Limits:** at most 2 Android devices and emulators together (`android-device` and `avd` count as one pool), at most 2 self managed iOS simulators, both machine wide since the store is shared. A grant that would pass a limit stays queued with `resource_queued reason=limit`. The limits are constants in `loop.core.resources`, named in the docs. Declaring more resources than a limit is valid: the extra ones wait their turn.
- **The order on `release_resource`:**
  1. The resource's `read_only_check` runs in the tool process, while the resource is still up, so its result is in the reply. A failure goes back in the reply, naming the difference, with the resource's `restore` entries as what the agent must run, and the lock stays with the attempt.
  2. On a pass, `loop run` runs the provider's `release`, then the provider's own check. A failure there marks the resource dirty; the agent is not asked, since the resource is gone.
  3. At attempt end with a `read_only_check` still failing, Loop runs the `restore` commands once itself, then the check again; a failure marks the resource dirty.
- **A dirty resource is never lent:** a dirty mark records `resource_check_failed`, shows in `loop status`, and the resource is not granted again. Before the next grant Loop runs the check again; a pass clears the mark. No new command.
- **A check may hold a resource:** a kind's `command` check may declare `resource: <kind>`. For the verification and for the merge check, Loop takes a lock of that kind in queue order, runs `prepare`, passes its map to the check as `LOOP_RESOURCE_<KEY>` env, and releases after the check. The limits apply to it like any grant. The check's timeout starts when the check runs, so a wait in the queue is never a failed check and never takes a `max_attempts` slot. Preflight refuses a `resource:` kind that no `resources:` entry declares. This is why the pilot app check (`./scripts/loop-check.sh`) can stop claiming its own simulator (m4-43).
- Out of this issue: the real providers (m4-27, m4-28).

## Acceptance criteria

- [ ] A third Android grant and a third iOS simulator grant stay queued with `reason=limit` while two are held, and are granted when one releases (table test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.resources.ResourceLimitsTest'` passes.
- [ ] A release whose `read_only_check` fails keeps the lock and answers with the difference and the `restore` entries; a second release with a passing check runs the provider's `release` then its own check (CLI test with the fake runner and a fake provider).
- [ ] A provider check that fails after `release` marks the resource dirty without asking the agent (test).
- [ ] An attempt that ends with a failing `read_only_check` runs `restore` once, then marks the resource dirty if the check still fails, records `resource_check_failed`, and `loop status` shows it (test).
- [ ] A dirty resource is not granted until its check passes again, then is granted (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ResourceReleaseCheckTest'` passes.
- [ ] Two verifications of the fake runner whose `command` check declares `resource:` each get their own resource with its map as `LOOP_RESOURCE_<KEY>` env, a third waits in the queue without a failed check, and the merge check gets a resource the same way (CLI tests).
- [ ] Preflight refuses a `resource:` kind that no `resources:` entry declares (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.CheckResourceTest'` passes.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.9 describe the limits, the release order, the dirty mark and the `resource:` key of checks (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 09:27Z loop: Loop opened pull request #63 for this issue: https://github.com/soulesidibe/Loop/pull/63

2026-11-25 09:36Z loop: Loop did not merge pull request #63, ci: Actions runs did not pass: build (failure). The pull request stays open and the result waits for a new approval.
  Failed runs:
  - build (failure): https://github.com/soulesidibe/Loop/actions/runs/45585606802
  Rerun the runs on GitHub or fix the branch, then approve again with loop approve.

2026-11-25 09:52Z soule: sent back before merge, after the merge risk reviews of pull request #63 (Sonnet 5.5 then Fable 5.2, both medium). Fix these on the same branch, then submit again:
  - Medium, self deadlock of a check with `resource:`. `applySubmitted` runs the verification inside the attempt, and the attempt's locks are freed only in its `finally`, so a check whose pool the attempt still holds (the agent forgot to release, or its `read_only_check` failed and it kept the lock) queues forever in `withCheckResource`. This breaks the criterion "a third waits in the queue without a failed check". Fix: `CheckResources.kt` reads `LOOP_ATTEMPT_ID` from the check env (null for the merge check) and passes it to `ResourceGrants.withCheckResource`; before the request loop, under the mutex, give back the locks that attempt holds in the pool of the kind (same kind when it has no pool) through the end of attempt path (`endCheck`, `giveBackOnce`, `locks.release`), recorded with a reason. Say it in the `request_resource` and `release_resource` descriptions and replace the spec-decisions sentence "a resource the attempt itself holds never satisfies its check". Test in `CheckResourceTest`: one declared resource, the fake agent holds it and submits, the check runs and passes. Optional: a WARN every few minutes while a check waits.
  - Low, dirty recheck (`recheckDirty`, `fullCheck`): it runs with no lock row, outside the pool limits, from every `loop run` that declares the resource, under the mutex. Take a real holder for the recheck (a `recheck-<uuid>` the queue rules may grant on a dirty resource) so it is exclusive across processes and counts in the pool; call `provider.release` after a failed `prepare`, like `giveBack`; set `rechecked[key]` after `fullCheck` returns, not before.
  - Low, the `read_only_check` runs in the tool process cwd with `tool.rawEnv` (`ResourceTools.kt`, around lines 42 to 43) but in `workflow.path.parent` with `agentEnvironment` on the `loop run` side (`RunCommand.kt`, around lines 284 to 289). Use one folder, `workflow_dir` of the tool config snapshot, in the default `ShellResourceCommands` of `ResourceTools`, and fix the docs sentence "run like a check".
  - Low, `ShellResourceCommands.TIMEOUT_MS` is 5 minutes per command, held under the `ResourceGrants` mutex and in the `NonCancellable` end of a check. Lower the default to 30 to 60 s and skip the end check on cancellation.
  - Low, the limit reply (`ResourceTools.why`) should tell the agent to release a resource it holds if it does not need both, since two attempts each holding one of a pool of 2 can wait on each other.
  - Low, `requestAny` records `resource_requested` and `resource_queued` with the ids joined by commas as `resource_id`; record the kind or one event per id.
  - Docs only: a lock freed as abandoned skips the `read_only_check`, restore and dirty mark (the provider's `recover` is the hook), and a v9 `loop run` left open beside a v10 one drops `any_of_kind`, so restart every `loop run` after the upgrade. One line each in spec-decisions.

2026-11-25 10:15Z agent 3089a000: Review fixes of pull request #63, on the same branch after merging main at 7e08b3f:
  - Medium, self deadlock: a check with resource: reads LOOP_ATTEMPT_ID and first gives back the locks and drops the queue rows its attempt holds in the pool of the kind, through the end of attempt path (read only check, restore, dirty mark, provider release and check), recorded as resource_released reason=check. New CheckResourceTest case: the agent holds the only resource and submits, the check runs and passes (it hung before the fix). Tool descriptions of request_resource and release_resource say it; a WARN log line every 5 minutes while a check waits.
  - Dirty recheck holds a real lock, recheck-<uuid>, the only holder the queue rules grant on a dirty resource, so it is exclusive across processes and counts in the pool; a failed prepare is released; the recheck time is set after the check.
  - The tool runs the read only check in workflow_dir of the snapshot (test proves the folder).
  - Resource command timeout 60 s; the declared end check is skipped when a check is canceled.
  - The limit reply tells the agent to release a resource it holds if it does not need both.
  - requestAny records one resource_requested and one resource_queued per id.
  - spec-decisions: abandoned locks skip the checks (recover is the hook), restart every loop run after the v10 upgrade, the take back replaces the old sentence.

2026-11-25 10:19Z loop: Loop updated pull request #63 for this issue: https://github.com/soulesidibe/Loop/pull/63

2026-11-25 10:24Z loop: Loop did not merge pull request #63 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1292 lines outside tests and docs, over the limit of 1000, 2644 lines in all
  Merge risk: band medium, score 1.47.
  - alignment: aligned, The diff covers the limits, the release order, the dirty mark and the check `resource:` key, and it applies the review fixes from the earlier send back; the orchestrator edit is mostly call sites.
  - impact: medium (floor medium: the diff changes 1292 lines outside tests and docs, over half the limit of 1000), It adds 1292 lines outside tests and docs and changes the shared lock, queue and grant code that every attempt, check and merge check goes through; the event store and the tool config snapshot change too.
  - verification: strong, Each acceptance criterion has a named test, the review fixes have their own cases such as the self deadlock and the tool's folder, and the merge check is tested end to end with a fake GitHub server.
  - rollback_cost: costly, The store changes shared state with dirty marks and new lock and queue columns or events, and the docs say every `loop run` must restart after the upgrade, so a revert leaves rows that the older code may not read.
  - error_detection: loud, A failed read only check, a dirty mark and a limit wait are each reported through a tool error, an event, `loop status` or a WARN log line, and a check that cannot hold a resource fails with a reason.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 23 of 32 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
