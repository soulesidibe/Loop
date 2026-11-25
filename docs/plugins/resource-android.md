# Android resource provider profile

The Android resource provider makes Android devices and emulators usable by agents and checks: it grants a connected device by its serial, and boots a configured AVD when no emulator of it is connected. This page is its plugin profile, the keys, defaults and limits plan section 12 asks every plugin to publish, and how it prepares, releases and recovers. The implementation is `AndroidResourceProvider` in `plugins/resource-android` (`loop.resource.android`), registered in `META-INF/services/loop.api.ResourceProvider`.

Written 2026-11-25 in issue m4-28. Terms follow [`CONTEXT.md`](../../CONTEXT.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md), this provider's in [the Android provider](../spec-decisions.md#the-android-resource-provider-extension-m4-28).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Defaults](#defaults)
4. [Limits](#limits)
5. [How it prepares](#how-it-prepares)
6. [Release, the read only check and recover](#release-the-read-only-check-and-recover)
7. [Failures](#failures)

## Configuration

The provider serves two kinds of `resources:` entries (see [resources](../spec-decisions.md#resources-config-and-the-provider-interface-extension-m4-24)). The `id` of an `android-device` entry is the serial `adb devices` lists, the `id` of an `avd` entry is the name of the AVD, as `emulator -list-avds` lists it:

```yaml
resources:
  - { kind: android-device, id: R58M123ABC, can: [ui tests on a real phone] }
  - { kind: avd, id: pixel_api35, cannot: [real calls, sms] }
```

A check that needs one declares `resource: avd` or `resource: android-device`, and gets the serial as `LOOP_RESOURCE_SERIAL`.

## Profile

The provider reads no section of its own: it reads each entry of `resources:` and the host environment.

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `resources[].kind` | string | required, `android-device` or `avd` | no | none |
| `resources[].id` | string | required, the serial or the AVD name | no | unique in the workflow |
| `resources[].can` | list of strings | none | no | none |
| `resources[].cannot` | list of strings | none | no | none |
| `resources[].restore` | list of strings | none | no | none |
| `resources[].read_only_check` | string | none | no | none |

`can`, `cannot`, `restore` and `read_only_check` are Loop's, the same for every kind; the provider does not read them.

What `prepare` returns, the map the agent gets with `granted` and a check gets as `LOOP_RESOURCE_<KEY>` env:

| Key | Value |
|---|---|
| `serial` | the device's serial, or `emulator-<port>` for an emulator |

The host environment it reads:

| Variable | Use |
|---|---|
| `PATH` | where `adb` and `emulator` are looked for first |
| `ANDROID_HOME`, then `ANDROID_SDK_ROOT` | where they are looked for next, as `platform-tools/adb` and `emulator/emulator` |
| `LOOP_HOME`, else `HOME` | the folder of the provider's state, `<LOOP_HOME>/resources/android/`, `LOOP_HOME` defaulting to `~/.loop` |

`adb` and `emulator` run with the environment of `loop run`, so the emulator finds the AVDs of the user who runs Loop.

## Defaults

Fixed in code, `AndroidTimeouts`; no key changes them.

| What | Default |
|---|---|
| Each `adb` call | 30 seconds |
| A boot, from the start of `emulator` to `sys.boot_completed` = 1 | 300 seconds |
| An emulator leaving `adb devices` after `emu kill`, then again after its process is killed | 30 seconds each |
| The pause between two reads while waiting | 1 second |
| Emulator ports | the first free even port from 5554 to 5584, with its next port free too |
| Each `prepare`, `release` and read only check, as a whole | 10 minutes, Loop's own timeout (`ResourceGrants.PROVIDER_TIMEOUT`, bl-208), not the provider's |

## Limits

- At most 2 Android devices and emulators together, `android-device` and `avd` counted as one pool, machine wide. The limit is Loop's, `ResourceLimits.ANDROID` (m4-26): the provider never counts.
- 16 emulators booted by the provider at most, one per even port from 5554 to 5584.
- A `prepare` holds only its own lock while it runs: `loop run` prepares in the background, and gives each provider call at most 10 minutes before it interrupts the call and fails it (bl-208), longer than a boot's 300 seconds.

## How it prepares

- **`android-device`.** `adb devices` must list the serial in the `device` state. A serial it does not list is refused, `android-device R58M123ABC: the device is not connected, adb devices lists none`, and so is one in another state, `the device is unauthorized, not ready`. Nothing is started.
- **`avd`, an emulator of it already connected.** For each connected `emulator-<port>`, `adb -s <serial> emu avd name` gives its AVD. The first that runs the entry's AVD is granted once `sys.boot_completed` reads 1. A real device connected does not count: it is not an emulator of the AVD.
- **`avd`, none connected.** The provider takes the first free port, writes a record of the boot, then starts `emulator -avd <name> -no-snapshot-save -port <port>` in the background, as a child of `loop run` (m4-25), its output in `<LOOP_HOME>/resources/android/logs/emulator-<port>-<attempt>.log`, named by the attempt that holds the lock (bl-208). It waits for `adb -s emulator-<port> wait-for-device`, in slices so an emulator that exits is seen, then reads `adb -s emulator-<port> shell getprop sys.boot_completed` every second until it reads 1, and grants `emulator-<port>`. `-no-snapshot-save` keeps what the holder did out of the AVD's snapshot.

- **A prepare run again.** Loop may call `prepare` again for the same attempt when the outcome of the first was lost (bl-208). An emulator of the AVD already connected is granted as above. When none is ready yet, the provider waits for the emulator its record names, if it started that one for the same attempt and it still runs, and boots no other. Any other record is stale: its emulator is stopped before a new boot. An `adb` call Loop interrupts kills its `adb` child and fails, `adb devices was interrupted`.

## Release, the read only check and recover

- **The record.** Each emulator the provider boots has a file in `<LOOP_HOME>/resources/android/booted/`, one per AVD: the AVD, the serial, the pid of `emulator`, the process of Loop that booted it, as `<pid>@<start time>`, and the attempt whose prepare booted it. A port is chosen and its record written under a lock of the folder, so two processes of the machine never take one port. The record goes once the emulator is gone.
- **`release`.** For an emulator it booted, `adb -s <serial> emu kill`, then a wait for the serial to leave `adb devices`. An emulator still there after the wait gets its process killed, then a second wait. A real device, and an emulator of the AVD started outside Loop, are left as they are.
- **The read only check.** For an `avd`, a difference when an emulator the provider booted still runs the AVD after release: `the emulator emulator-5554 of avd pixel_api35 that Loop booted is still running after release`. Loop then marks the resource dirty (m4-26). Nothing for an `android-device`.
- **`recover`**, called once at the startup of `loop run` with the holders of every live lock on the machine (m4-50). It stops, like `release`, each emulator of a record whose AVD no live lock of kind `avd` holds and whose booting process of Loop is no longer running. It never touches a real device, an emulator started outside Loop, or an emulator a live `loop run` booted. With no record it calls no `adb`.

## Failures

Every failure is a `ResourceException` whose message starts with the kind and the id, and Loop records it as the prepare or release failure of the lock.

| Case | Message |
|---|---|
| A command exits non zero | `avd pixel_api35: adb -s emulator-5554 emu kill exited with code 4: <its last line of output>` |
| A command passes its timeout | `android-device R58M123ABC: adb devices timed out after 30s` |
| Loop interrupts the call past its own timeout (bl-208) | `android-device R58M123ABC: adb devices was interrupted`, and Loop records `the provider did not answer in 10m` |
| `adb` or `emulator` not found | `adb is not on the PATH, nor under ANDROID_HOME or ANDROID_SDK_ROOT in platform-tools/` |
| The emulator exits before it booted | `avd pixel_api35: emulator -avd pixel_api35 -no-snapshot-save -port 5554 exited with code 1: <the last line of its log>` |
| The boot does not complete in time | `avd pixel_api35: emulator-5554 did not boot in 5m, the last sys.boot_completed read was "0"` |
| No port is free | `avd pixel_api35: no emulator port is free from 5554 to 5584` |

A boot that fails stops the emulator it started before the failure is returned. A failing `emu kill` kills the emulator's process, then fails the release. `recover` tries every record, then fails once with every failure, and the run goes on.
