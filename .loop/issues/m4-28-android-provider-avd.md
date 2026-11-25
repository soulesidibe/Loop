---
id: m4-28
title: "Android provider, boots a configured AVD"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-01, m4-26]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 125 (PLAN 6.9 and 12). Lane B. In scope but not gating the exit: the exit app is the pilot app (iOS), so m4-43 can start while this finishes.

Premise, checked before the first edit: no Gradle edit needed. m4-01 created the `resource-android` module (`plugins/resource-android`, as m4-01 named it) with its full dependencies and its place on `cli`'s runtime classpath. The `META-INF/services/loop.api.ResourceProvider` file is this issue's to add (a resource file, not protected). m4-24 gave `ResourceProvider` its methods and `ResourceProviderContract`. If the module, its dependencies or the interface are missing, stop and say so in a comment: the module's Gradle files are protected.

- The provider serves the kinds `{android-device, avd}`. An `android-device` entry names a serial; an `avd` entry names an AVD.
- `prepare` for a device checks it is connected (`adb devices`) and returns its serial. For an AVD, when no device of the entry is connected, it boots the AVD (`emulator -avd <name> -no-snapshot-save`, in the background, as a child of `loop run`, m4-25), waits for `adb wait-for-device` and `sys.boot_completed`, and returns the emulator's serial.
- `release` stops an emulator it booted (`adb -s <serial> emu kill`) and leaves a real device connected. The provider's read only check confirms an emulator it booted is gone.
- `recover(liveHolders)` (m4-24), called once at startup: stops an emulator this provider booted for an attempt that holds no live lock, and leaves real devices alone.
- Every `adb` and `emulator` call has a timeout, and a failure names the command and its exit code. A boot that does not complete in time is a failure with the last boot property read.
- The Android limit (2 devices and emulators together) is m4-26's; this provider does not count.
- Tests run against fake `adb` and `emulator` scripts on `PATH` (the spec's testing decisions).
- Out of this issue: the iOS provider (m4-27), installing the app.

## Acceptance criteria

- [ ] Premise checked before the first edit: no Gradle edit needed (judgment).
- [ ] `./gradlew --no-daemon :plugins:resource-android:compileKotlin` passes.
- [ ] A connected device is granted by serial with no emulator started (test with the fake `adb`).
- [ ] With no device connected, the configured AVD is booted, waited for and granted, and `release` kills it (test on the recorded arguments).
- [ ] `recover` stops an emulator it booted for an attempt with no live lock and leaves a real device alone (test).
- [ ] A boot that never completes fails after the timeout naming the last `sys.boot_completed` value (test).
- [ ] A failing `adb` call fails with the command and exit code (test).
- [ ] `ResourceProviderContract` passes against the provider and the fakes (judgment).
- [ ] `./gradlew --no-daemon :plugins:resource-android:test` passes.
- [ ] `docs/plugins/resource-android.md` gives the provider's profile: its config keys, defaults and limits (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 10:51Z soule: scope added by the operator from bl-208 (low findings of the m4-25 review), checked at the merge risk review; the issue is sent back if any is missing:
  - bl-208 finding 2: provider `prepare` and `release` get a core side timeout, and run outside the `ResourceGrants` mutex (or the mutex is not held across them), so a hung or slow provider does not stall every grant and attempt end of the run. Test with a provider that hangs.
  - bl-208 finding 4: `prepare` is idempotent, so a prepare whose outcome was lost (canceled on shutdown, or a failed `locks.prepared` write) and runs again on the next tick reuses or cleans what the first call made instead of leaking it.
  - bl-208 finding 5: `guarded` in `ResourceGrants.kt` catches `Throwable` except `CancellationException`, so a plugin `Error` (a `LinkageError` from a provider jar) cannot kill the run, and in `endAttempt` the free gets its own try so `dropRequests` and `deleteDirectory` still run.
  - bl-208 finding 12: `ResourceProvider.prepare` receives the attempt id (plugin-api change, documented), and the provider uses it where it names what it creates.
  If m4-27 and m4-28 both change the shared core (findings 2, 5 and 12), the second to merge rebases on the first; the core change is made once.

2026-11-25 11:20Z soule: m4-27 was approved by the operator without the core part of the bl-208 scope, so m4-28 carries all of it. Missing on main after m4-27: finding 2 (`ResourceGrants.tick` and `endAttempt` hold the mutex across `prepareAndWrite`, `giveBack` and `free`; `guarded` has no timeout and uses `withContext(Dispatchers.IO)`, which does not respond to cancellation; use `runInterruptible` and a core `withTimeout`), finding 5 (`guarded` still catches `Exception`; the free in `endAttempt` has no own try), finding 12 (`ResourceProvider.prepare(resource)` gets no attempt id; update `ResourceGrants.prepare` and `fullCheck`, `FakeResourceProvider`, the contract test fixture, and the iOS provider, which names its simulator `loop-sim-<resource id>-<pid>` as a documented deviation in `docs/spec-decisions.md`). Tests: a provider whose `prepare` hangs (the mutex stays free, the timeout fires), and a provider that throws an `Error` from `prepare`, `release` and `readOnlyCheck`.

2026-11-25 11:28Z agent 450a6f65: bl-208 scope done in m4-28, since m4-27 has not merged; m4-27 rebases on it and does not make the core change again.
  - Finding 2: ResourceGrants has no global mutex any more. Each lock has a mutex of its own. A tick starts the prepares, the releases and the rechecks in the background and never waits on a provider. The end of an attempt and the checks wait only for calls in flight on their own locks. Each provider call runs on a daemon thread with a 10 minute core timeout (ResourceGrants.PROVIDER_TIMEOUT); past it the thread is interrupted and left behind. Tested with a hung provider that ignores interrupts.
  - Finding 4: the contract has a new case, a second prepare for the same attempt followed by one release leaves nothing behind. The core releases a lock whose prepare ran even when its outcome was not written, and releases at once a prepare that ends after its lock was freed. The Android provider waits for the emulator it booted for the same attempt instead of booting another.
  - Finding 5: guarded catches Throwable except CancellationException, and the free in Orchestrator has its own try.
  - Finding 12: the signature is now ResourceProvider.prepare(resource, attemptId) (a source breaking plugin-api change, documented in spec-decisions). The Android provider writes the attempt on its boot record and in the name of the emulator's log.
  Evidence: focused spec-core, plugin-api, Android and cli resource tests, plus ktlintCheck, all pass.

2026-11-25 11:36Z loop: Loop opened pull request #67 for this issue: https://github.com/soulesidibe/Loop/pull/67

2026-11-25 11:37Z loop: Loop could not merge pull request #67: merging main at ed96c6a2bee6e43608902b2afa1e216b944b1cad into loop/m4-28 conflicts (conflict in cli/src/test/kotlin/loop/cli/ResourcePluginsTest.kt, docs/spec-decisions.md). The merge was aborted and the branch is unchanged. The next attempt merges refs/loop/base/main into its branch, resolves the conflict and submits the result again.

2026-11-25 11:42Z soule: findings of the merge risk review of pull request #67 (Sonnet 5.5). All four bl-208 scope items are present in the core. Fix these on the same branch in the next attempt, with the conflict:
  - High, stale base. m4-27 (iOS simulator provider) merged first, at ed96c6a. Merge origin/main into the branch, resolve `ResourcePluginsTest.kt` (keep `containsAll`, the `display` and `phone` kinds, the Android test, and `ResourceDeclaration.provider`) and `docs/spec-decisions.md`. Then update `plugins/resource-ios`: `IosSimulatorProvider.prepare` takes `attemptId` and names its simulator with it (remove the "The name, a deviation" text), and its tests call the new signature; the iOS contract run must pass the new idempotent prepare case. Fix the three sentences that say m4-28 merged first, that m4-27 rebases on it, and that m4-27 names its simulator with it. Run `./gradlew --no-daemon build`.
  - Low, the kill identity is serial plus AVD name only (`AndroidResourceProvider.kt`, around lines 282 and 311). If Loop's emulator dies with its record left and the user starts the same AVD on the same port, `recover` or a later `release` stops the user's emulator, and `runs()` is true for any emulator not in `device` state. Require the record's pid to be live with a matching start time (or compare the emulator's pid), and in `prepareAvd` delete a stale record with a dead pid before taking the `running` branch.
  - Low, the kill fallback (`AndroidResourceProvider.kt`, around line 298) kills `ProcessHandle.of(record.pid)` and its descendants with no start time or command check. Store the start time and compare it, or check the command is the emulator or qemu.
  - Low, `recover` leaves an emulator that started but is not yet in `adb devices`: kill a live, matching recorded pid even when the serial is absent.
  - Low, tests: an `Error` from `readOnlyCheck`, a hung provider `release` on the tick path (a tool marked release), adb missing and adb present with `emulator` missing against an empty PATH; make the `repeat(20) { yield() }` wait assertion prove the wait.
  - Low, the background scope of `ResourceGrants` is never canceled: add a `close()` that cancels it and interrupts in-flight calls, called from `RunCommand` shutdown.
  - Low, emulator logs under `<LOOP_HOME>/resources/android/logs/` are never deleted: delete after a clean release, sweep by age in `recover`.
