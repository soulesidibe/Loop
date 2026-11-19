---
id: m4-27
title: "iOS simulator provider"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-01, m4-26]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 124 (PLAN 6.9 and 12). Lane B. Gates the exit: the exit app is the pilot app, and its two parallel code issues each run on their own simulator, agent and check (m4-43).

Premise, checked before the first edit: no Gradle edit needed. m4-01 created the `resource-ios` module (`plugins/resource-ios`, as m4-01 named it) with its full dependencies and its place on `cli`'s runtime classpath. The `META-INF/services/loop.api.ResourceProvider` file is this issue's to add (a resource file, not protected). m4-24 gave `ResourceProvider` its methods and `ResourceProviderContract`. If the module, its dependencies or the interface are missing, stop and say so in a comment: the module's Gradle files are protected.

- The provider serves the kinds `{ios-simulator}`. A resource entry names a device type and a runtime (for example `iPhone 16`, `iOS 18.2`), or a base simulator to clone.
- `prepare` creates a self managed simulator with `xcrun simctl create` (or `clone` of the base), boots it, waits until it is booted (`xcrun simctl bootstatus <udid> -b`), and returns its UDID. It runs in `loop run` (m4-25) and can take a minute or more, so it has its own timeout, longer than the other calls. The simulator is named with a Loop prefix and the attempt id, so the provider never touches a simulator it did not create.
- `release` shuts the simulator down and deletes it. The provider's read only check confirms the simulator is gone, or for a kept base, that it is shut down.
- `recover(liveHolders)` (m4-24), called once at startup: deletes the Loop prefixed simulators whose attempt holds no live lock, and leaves every other simulator alone.
- Every `xcrun` call has a timeout, and a failure names the command and its exit code.
- Tests run against a fake `xcrun` script on `PATH` that records its arguments and answers like `simctl` (the spec's testing decisions). The real proof is the pilot app wave of m4-43.
- Out of this issue: the Android provider (m4-28), installing or launching the app (the agent's job, with the UDID).

## Acceptance criteria

- [ ] Premise checked before the first edit: no Gradle edit needed (judgment).
- [ ] `./gradlew --no-daemon :plugins:resource-ios:compileKotlin` passes.
- [ ] `prepare` creates, boots and waits for a simulator through the fake `xcrun`, and returns its UDID (test on the recorded arguments).
- [ ] `release` shuts down and deletes it, and the read only check passes only when it is gone (test).
- [ ] `recover` deletes Loop prefixed simulators of attempts with no live lock and leaves every other simulator alone (test).
- [ ] A failing or hanging `xcrun` call fails with the command and exit code, or the timeout (one test each).
- [ ] `ResourceProviderContract` passes against the provider and the fake `xcrun` (judgment).
- [ ] `./gradlew --no-daemon :plugins:resource-ios:test` passes.
- [ ] `docs/plugins/resource-ios.md` gives the provider's profile: its config keys, defaults, limits and the `prepare` timeout (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
