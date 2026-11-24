---
id: m4-26
title: "Resource limits, the read only check after release, and resources for checks"
state: todo
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
