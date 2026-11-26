---
id: bl-249
title: "Two CI flakes stop deliveries, ResourceGrantsTest's release tick and WorkflowReloadCommandTest"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: []
area: [spec-core/src/test/kotlin/loop/core/resources/ResourceGrantsTest.kt, cli/src/test/kotlin/loop/cli/WorkflowReloadCommandTest.kt, cli/src/test/kotlin/loop/cli/LoopFixture.kt]
---

## Parent

None. Backlog, not scheduled in a milestone. Each failure below forced a manual `gh run rerun` of the failed jobs and a human approval before the merge.

## What

Two tests fail on CI on pull requests that do not touch them.

`ResourceGrantsTest > a tick gives back a released lock through the provider, frees it and the next in its queue is prepared`:

- Pull request #84 (bl-221), run 45764326909, attempts 1 and 2, `AssertionFailedError at ResourceGrantsTest.kt:170`, head 6186922.
- Pull request #85 (bl-219), run 45800353591, attempt 1, `AssertionFailedError at ResourceGrantsTest.kt:191`, head f1ebbe0.
- Pull request #99 (bl-04), run 45869576555, attempt 1, `AssertionFailedError at ResourceGrantsTest.kt:191`, head 8fce6cb.
- Each time it passed on the rerun and locally.

`WorkflowReloadCommandTest > an invalid edit is rejected the last good config stays active and the error is recorded`:

- Pull request #92 (bl-230), run 45826126281, attempt 1, `IllegalArgumentException at WorkflowReloadCommandTest.kt:152`, head 1413f76, 13:07Z on 2026-11-26.

The reported lines do not name the failing assertion. At each of those commits, line 170 or 191 is the `runTest {` line of the test, because kotlinx coroutines recovers the stack of an exception rethrown by `runTest` at the call site. The CI log keeps no assertion message (bl-57). The cause below is deduced from the code and must be confirmed.

### Likely cause of the ResourceGrantsTest flake: the test assumes an order the tick does not promise

The test asks iphone for a1 then a2, prepares it for a1, requests a1's release, then calls `pass()` (one `tick()`, then `idle()`), and asserts `calls == ["prepare iphone", "release iphone"]` with the message "the release runs in the background of the tick". A third `pass()` should then prepare iphone for a2.

`ResourceGrants.tick()` reads the rows twice. The first read starts `giveBackAndRelease` for each released lock in the background, on `Dispatchers.Default` and the provider call pool. The tick then runs `recheckDirty()` and reads the rows again to start the prepare of each unprepared lock. With the in memory store and provider of the test, the background release can finish, free a1's lock and grant it to a2 before the second read. a2's lock is then unprepared and its guard key (attempt, kind, id) differs from a1's, so `inBackground` starts its prepare in the same tick. After `idle()`, `calls` is `["prepare iphone", "release iphone", "prepare iphone"]` and the intermediate assertion fails. On a busy CI runner the test thread is slower to reach the second read, which fits a CI only failure.

The production behaviour looks correct: preparing the next holder in the same tick as the free is what m4-25 describes, and the guards still keep the calls on one lock from overlapping. The test is wrong to assert that the prepare waits for the next tick.

A second, smaller risk in the same class: the test `Sink` keeps its events in a plain `mutableListOf`, while `ResourceLocks.release` records `resource_released` and `resource_granted` from the background threads of the grants. Make it thread safe as the store already is.

### WorkflowReloadCommandTest: probably fixed by bl-186, to confirm

bl-186 found the cause: `LoopFixture.writeWorkflow` truncated then wrote `WORKFLOW.md`, so a reload poll could read an empty or partial file and record a second `config_reload_failed`. Its fix (commit 96f6edf, pull request #96, merged at 15:55Z on 2026-11-26) writes through `replaceAtomically` in `AtomicFiles.kt`. The bl-230 failure ran at 13:07Z on 1413f76, whose `writeWorkflow` still called `workflow.writeText`, so it predates the fix. No CI run of `build.yml` failed on this test since. `Orchestrator.followWorkflow` compares content hashes, not modification times, so file time granularity plays no part.

What remains is to confirm it under load and close the last exposure: any test that rewrites `WORKFLOW.md` while `loop run` runs must go through `replaceAtomically`. In `WorkflowReloadCommandTest` every write goes through `writeWorkflow`. The other direct `fixture.workflow.writeText` calls in `cli` tests seen in triage run before the process starts, and `LoopFixture` still has one non atomic write for hooks.

### Rules

- Fix in test code only, unless the investigation proves a real race in production code. Then name it, fix it, and add a test that fails without the fix.
- No added retries, no longer timeouts, no `@Disabled`, no skipped test, no wider assertion that would also pass on a real defect.
- No wall clock sleep as synchronization. Order the steps with latches, a gated provider or the `providerCalls` executor the grants take for tests (bl-219). The existing sleeps that prove something did not happen in a window, such as the ones of the bl-208 and bl-219 tests, may stay.
- Protected paths that the diff must not touch: `scripts/`, `.github/`, `WORKFLOW.md`, `*.gradle.kts`, `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew*`, `.loop/specs/`, `docs/adr/`.

## Acceptance criteria

- [ ] A comment on the issue through `loop tool comment` names the cause of the ResourceGrantsTest flake with evidence: a throwaway change, removed before the commit, that makes the background release finish before the tick's second read of the rows (for example a latch the test store opens after the free) and shows the test failing with the assertion and the `calls` it saw. If the evidence points elsewhere, the comment names that cause instead, with the same kind of proof (judgment).
- [ ] The test asserts only what `tick()` promises. Either it holds the release (gated provider or held executor) so the intermediate state is fixed, and then asserts after `tick()` and before `idle()`, since `idle()` would block on a held release, or it drops the intermediate assertion and checks the final calls, holders and events. It still proves the release runs before a2's prepare and that a2 holds the prepared lock (judgment).
- [ ] The `Sink` of `ResourceGrantsTest` records events safely from several threads.
- [ ] No production code changes, or a comment on the issue through `loop tool comment` names the production race it fixes and the new test that fails without it (judgment).
- [ ] A comment on the issue through `loop tool comment` confirms or refutes, with the run ids and commits above, that the bl-230 failure of `WorkflowReloadCommandTest` predates bl-186's fix, and moves any `WORKFLOW.md` write of a cli test that can run while `loop run` runs to `replaceAtomically` (judgment).
- [ ] `./gradlew :spec-core:test --tests 'loop.core.resources.ResourceGrantsTest' --rerun` passes 20 times in a row, run in chunks under 10 minutes each, with a throwaway `@RepeatedTest(200)` removed before the commit as the load proof. A comment on the issue through `loop tool comment` gives the loop it ran and the count.
- [ ] `./gradlew :cli:test --tests 'loop.cli.WorkflowReloadCommandTest' --rerun` passes 20 times in a row, run in chunks under 10 minutes each, with a throwaway `@RepeatedTest(200)` removed before the commit as the load proof. A comment on the issue through `loop tool comment` gives the loop it ran and the count.
- [ ] `./gradlew --no-daemon ktlintCheck` passes.
- [ ] `./gradlew --no-daemon build` passes.

## Out of scope

- Changing the order of the tick, the guards or the reload dedup in production, unless the cause is a proven production race.
- Keeping test reports or assertion messages on CI (bl-57, and `.github/` is protected).
- The 60 second stalls of process tests (bl-07), `ResourceToolsTest` at line 468, `MultiTrackerRunCommandTest` and `RestartStateTest` at line 126 (main run 45893029905). File any new flake as its own `bl-<nn>` needs-triage issue.
- The low findings of bl-208, bl-216, bl-234 and bl-238 on `ResourceGrants`.

## Comments

2026-11-26 triage:

> *This was generated by AI during triage.*

Bug, ready-for-agent, priority 2. Filed from the failures on pull requests #84, #85, #92 and #99, after reading the failed logs of runs 45764326909, 45800353591, 45826126281 and 45869576555 and the code at their heads. It takes over the ResourceGrantsTest notes left on bl-183 (canceled as a duplicate of bl-07, which covers the 60 second process stalls, not this pure spec-core test) and the WorkflowReloadCommandTest follow up of bl-186 (done; its fix landed after the bl-230 failure). The area lists the test files only, so it does not hold `ResourceGrants.kt` against the open low finding issues. A production change would overlap bl-238 and should be raised in a comment first.

Revised after a Fable 5.2 review of the brief on 2026-11-26.
