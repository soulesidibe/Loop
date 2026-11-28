---
id: bl-268
title: "A check whose resource fails to prepare parks the issue at once, and a released simulator can stay booted"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. Found in the m4-43 wave on the second pilot app, 2026-11-27.

## What

1. **Release left a simulator booted.** At 18:03:16 issue 197's agent released `sim-a`; the provider's read only check after release recorded `resource_check_failed`: "the simulator loop-sim-sim-a-40687@d9c80165-... of sim-a is still on the machine, Booted", and `sim-a` was queued as `dirty`. Release had not shut down and deleted it.
2. **The next prepare timed out and parked the issue.** At 18:06:03 issue 244's verification check took `sim-a`; prepare failed with `xcrun simctl bootstatus ... -b timed out after 4m 55s` (two warm pool simulators of another tool were booted, and `sim-b` was booting). The check "could not run", the verdict was `unverified`, and the issue was parked at once (`issue_parked reason=unverified`), with no retry and no other simulator tried. A human had to raise `prepare_timeout_seconds` to 900 and `loop resume`.

A wait for a resource is never a failed check (m4-26), but a failed prepare is treated worse than a failed check: it parks.

## Agent brief

- Treat a prepare failure of a check's resource as transient: release the lock (with its read only check), and retry the check's request, trying the other resources of the kind, a bounded number of times with backoff, before the verdict is `unverified`. Log each retry.
- Find why release left the simulator booted (shutdown or delete failing or racing), make release retry the shutdown and delete, and make a dirty resource's next prepare clean it up first.

## Acceptance criteria

- [ ] A test with the fake xcrun: a prepare that times out once is retried, on the other resource of the kind when free, and the check runs; no park.
- [ ] Repeated prepare failures still end as `unverified` after the bound, naming every attempt.
- [ ] A test where shutdown fails once on release: release retries, and the read only check passes.
- [ ] `docs/plugins/resource-ios.md` and the m4-26 section of `docs/spec-decisions.md` state the rule.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
