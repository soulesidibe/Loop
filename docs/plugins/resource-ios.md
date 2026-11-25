# `ios-simulator` resource provider profile

The iOS simulator provider makes each resource of kind `ios-simulator` a self managed simulator: Loop creates it, or clones it from a base, boots it and waits for it when the resource is granted, then shuts it down and deletes it when the resource is released. The agent gets its UDID and installs and launches the app itself. This page is its plugin profile, the keys, defaults, limits and timeouts plan section 12 asks every plugin to publish. The implementation is `IosSimulatorProvider` in `plugins/resource-ios` (`loop.resource.ios`), registered in `META-INF/services/loop.api.ResourceProvider`.

Written 2026-11-25 in issue m4-27. Terms follow [`CONTEXT.md`](../../CONTEXT.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md), this provider's in [iOS simulator provider](../spec-decisions.md#ios-simulator-provider-extension-m4-27).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Checks of the settings](#checks-of-the-settings)
4. [How it runs](#how-it-runs)
5. [Limits](#limits)

## Configuration

An entry of `resources:` in `WORKFLOW.md` with `kind: ios-simulator` selects it. Its settings are the entry's `provider` map, either a device type with an optional runtime:

```yaml
resources:
  - kind: ios-simulator
    id: iphone-16
    can: [ui tests, deep links]
    cannot: [push notifications]
    provider:
      device_type: iPhone 16
      runtime: iOS 18.2
```

or a base simulator to clone, by name or UDID, which Loop never boots, changes or deletes:

```yaml
resources:
  - kind: ios-simulator
    id: iphone-seeded
    provider:
      base: Pilot base iPhone
      prepare_timeout_seconds: 600
```

The device type and the runtime are the names or identifiers `xcrun simctl list devicetypes` and `xcrun simctl list runtimes` print. A base must be shut down when it is cloned.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `resources[].provider.device_type` | string | required unless `base` is set | no | none |
| `resources[].provider.runtime` | string | none, the newest runtime for the device type, as `simctl create` picks it | no | none |
| `resources[].provider.base` | string | none | no | none |
| `resources[].provider.prepare_timeout_seconds` | integer | 300 | no | 1 to 3600 |

The provider has no secret. Set exactly one of `device_type` and `base`, and `runtime` only with `device_type`: a clone keeps the runtime of its base.

## Checks of the settings

The provider checks the `provider` map when it prepares the resource, before any `xcrun` call, so a wrong setting is a failed prepare, the `prepare_failed` answer of `request_resource`, which names the resource and the key:

| Problem | Message |
|---|---|
| neither `device_type` nor `base` | `iphone-16 needs provider.device_type, such as "iPhone 16", or provider.base, a simulator to clone` |
| both `device_type` and `base` | `iphone-16 sets both provider.device_type and provider.base, keep one` |
| `runtime` with `base` | `iphone-16 sets provider.runtime with provider.base, a clone keeps the runtime of its base` |
| a key the profile does not list | `provider.<key> of iphone-16 is not a key of the ios-simulator provider, use one of device_type, runtime, base, prepare_timeout_seconds` |
| a timeout out of its limits | `provider.prepare_timeout_seconds of iphone-16 must be an integer from 1 to 3600, not "0"` |
| a base not on the machine | `the base simulator "Pilot base iPhone" of iphone-16 is not on the machine` |
| a base not shut down | `the base simulator Pilot base iPhone (<udid>) of iphone-16 is Booted, a clone needs it shut down` |

The preflight of `WORKFLOW.md` only checks that `provider` is a map of scalars (`resources[0].provider must be a map`, `resources[0].provider.runtime must be a string`).

## How it runs

Every call runs in `loop run`, so a booted simulator is a child of the run, never of the agent's tool process.

- **prepare.** Lists the simulators, deletes a stale one of the same resource, such as the one a prepare of the same attempt made before its outcome was lost, then runs `xcrun simctl create <name> <device_type> [<runtime>]`, or `xcrun simctl clone <base udid> <name>`, then `xcrun simctl boot <udid>` and `xcrun simctl bootstatus <udid> -b`, and returns `{"udid": "<udid>"}`. The agent reads it in the `granted` answer of `request_resource`, and a check holding the resource gets it as `LOOP_RESOURCE_UDID`.
- **release.** Shuts the simulator down, unless it is already, and deletes it.
- **The read only check.** After each release the provider reports a simulator of the resource still on the machine and, for a clone, a base that is gone or not shut down. Release never boots or shuts down the base, so a base a holder left booted is reported and the resource is marked dirty.
- **recover.** At the startup of `loop run`, deletes the simulators of the provider's name whose resource no live `ios-simulator` lock holds, and leaves every other simulator alone. On a host with no `xcrun` on the `PATH` it does nothing.
- **Names.** The simulator is named `loop-sim-<resource id>-<pid>@<attempt>`, the pid of the `loop run` that prepared it and the attempt that holds the lock, which `prepare` receives since bl-208, each character of it but a letter, a digit, `.`, `_` and `-` replaced by `_`, such as `loop-sim-iphone-16-4242@check-1f0e`. The provider only shuts down or deletes a simulator of that shape whose process is the current one or no longer alive, so a run of another Loop home of the machine, such as one of Loop's own tests, never removes the simulator of a live run, and a simulator a person made, whatever its name, is never touched.
- **Environment.** `xcrun` is the one on the `PATH` of `loop run`, run with the environment of `loop run`, in its own process group through the shared launcher of `plugins/runner-process`, which needs `perl` on the `PATH`.

## Limits

- **Timeouts.** The create or clone, the boot and the wait for the boot share `prepare_timeout_seconds`, 300 seconds by default. Every other `xcrun` call, the list, the shutdown and the delete, has 60 seconds. A call past its timeout has its process group killed and fails with `xcrun simctl bootstatus <udid> -b timed out after 5m`; a call that exits other than 0 fails with the command, its exit code and its last line of stderr, such as `xcrun simctl create loop-sim-iphone-16-4242@a-1 "iPhone 16" "iOS 18.2" exited 3: ...`.
- **How many.** Loop lends at most 2 self managed iOS simulators at once across the machine, the `ios-simulator` pool of [the resource limits](../spec-decisions.md#resource-limits-the-read-only-check-after-release-and-resources-for-checks-extension-m4-26). More entries are valid and wait their turn.
- **Pid reuse.** A simulator whose `loop run` died while its pid went to another live process is kept until a later `recover` finds the pid free.
- **macOS only.** Simulators need Xcode. On another host `recover` does nothing, and a prepare fails with the exit code of the missing `xcrun`.
- **Proof.** The tests run against a fake `xcrun` script (`src/test/resources/fake-xcrun/xcrun`) that records its arguments and answers like `simctl`. The real proof is the pilot app wave of m4-43.
