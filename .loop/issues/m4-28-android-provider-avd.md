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
