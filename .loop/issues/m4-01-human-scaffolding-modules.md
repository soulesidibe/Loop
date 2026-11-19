---
id: m4-01
title: "Human scaffolding: resource and export modules, empty HumanChannel and ResourceProvider"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: [m4-00]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Lane: human, the m3-02 of M4. It readies the ground of lanes B and C, so no agent stops on an unnamed human step. Files: `settings.gradle.kts`, three new module build scripts, `gradle/libs.versions.toml`, `cli/build.gradle.kts`, and two new files in `plugin-api/src/main/kotlin/loop/api/`. The Gradle files are protected (`*.gradle.kts`, `plugins/*/*.gradle.kts`, `gradle/` in `guards.protect`), so a human makes them.

The lesson of m3-17: m3-02 created `plugins/runner-codex-app-server` with only `kotlinx-serialization-json` on its main classpath. m3-17's agent stopped with "blocked on a before dispatch human step the issue does not name" (comment of 2026-11-16 16:42Z), and soule added `plugin-api`, `runner-process` and coroutines by hand. Here every module gets its full dependency set up front, and a smoke test proves the classpath before any agent needs it.

Steps:

1. Add `:plugins:resource-ios`, `:plugins:resource-android` and `:plugins:export-otel` to `settings.gradle.kts`.
2. Give each a `build.gradle.kts` copied from `plugins/runner-claude-code/build.gradle.kts`: `loop.kotlin-jvm`, `implementation` of `:plugin-api`, `kotlinx-coroutines-core` and `kotlinx-serialization-json`; `testImplementation` of `kotlinx-coroutines-test` and `testFixtures(project(":plugin-api"))`. The two resource modules also get `:plugins:runner-process`, since the providers spawn `xcrun simctl`, `adb` and `emulator` through the shared launch. `export-otel` spawns nothing and does not get it.
3. For `export-otel`, add the OpenTelemetry SDK and the OTLP exporter to `gradle/libs.versions.toml` (through the OpenTelemetry BOM if it fits the catalog), each a release at least seven days old, the same release age rule as m3-02's codex pin. The exporter builds on `EventSink` (`plugin-api/.../EventSink.kt`).
4. Put the three modules on `cli`'s runtime classpath in `cli/build.gradle.kts`, with a comment like m3-02's: each provider registers through its own `META-INF/services` file, added by its own issue (a resource file, not protected).
5. In `plugin-api/src/main/kotlin/loop/api/`, add `HumanChannel.kt` and `ResourceProvider.kt`, each an empty `public interface` (the module uses `explicitApi()`) with a KDoc pointing at PLAN section 12. m4-24 and m4-30 give them their members. The terminal channel is a core implementation (m4-30), not a module.
6. One smoke test per module, compiling against `plugin-api`, the coroutines test library and the `plugin-api` test fixtures, plus `runner-process` for the two resource modules and an OpenTelemetry SDK class for `export-otel`, so a missing dependency fails here and not in an agent's attempt.

Out of this issue: the `ServiceLoader` loaders in `cli`, which load only runners and trackers today (`RunnerPlugins.kt`, `TrackerPlugins.kt`): m4-24 adds `ResourcePlugins.kt` in lane A, and m4-42 adds `ExporterPlugins.kt` with its provider interface, blocked by m4-13 so it edits `cli` after lane A's own edit there. Also out: any provider or exporter code, the members of the two interfaces, the contract tests (`HumanChannelContract`, `ResourceProviderContract` come with their issues).

## Acceptance criteria

- [ ] `./gradlew --no-daemon :plugins:resource-ios:build :plugins:resource-android:build :plugins:export-otel:build` passes.
- [ ] `./gradlew --no-daemon :plugins:resource-ios:test :plugins:resource-android:test :plugins:export-otel:test` passes, each module with a smoke test that uses `plugin-api`, `kotlinx-coroutines-test` and the `plugin-api` test fixtures, the resource modules' also `runner-process`, and `export-otel`'s also an OpenTelemetry SDK class.
- [ ] `grep -q ':plugins:resource-ios' cli/build.gradle.kts && grep -q ':plugins:resource-android' cli/build.gradle.kts && grep -q ':plugins:export-otel' cli/build.gradle.kts` passes.
- [ ] `grep -q 'public interface HumanChannel' plugin-api/src/main/kotlin/loop/api/HumanChannel.kt && grep -q 'public interface ResourceProvider' plugin-api/src/main/kotlin/loop/api/ResourceProvider.kt` passes.
- [ ] The OpenTelemetry versions are in `gradle/libs.versions.toml`, each at least seven days old on the day of the commit (judgment).
- [ ] The premise "no Gradle edit needed" of m4-09, m4-27, m4-28, m4-29, m4-38 to m4-40, m4-42 and m4-49 is checked against this scaffolding, and what each will need from the classpath is listed in a comment on this issue (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
