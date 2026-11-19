---
id: m4-42
title: "OpenTelemetry export plugin"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-01, m4-13]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 139. Lane B, in the `plugins/export-otel` module that m4-01 creates, plus a provider interface in `plugin-api` and a loader in `cli`. Not gating the exit. Blocked by m4-13 so the edit of `Host.kt` or `RunCommand.kt` comes after the lane A chain's own edit there.

Premise, checked first: no Gradle edit needed. m4-01 created `plugins/export-otel` with its OpenTelemetry dependencies and its `cli` runtime wiring. The `META-INF/services` file is this issue's to add (a resource file, not protected). If the module or its dependencies are missing, stop and move the issue to `needs_info` naming what is missing. m3-17 stopped on a Gradle step its issue did not name; this premise is there so that cannot happen again.

- **The provider interface and loader:** this issue adds `EventExporterProvider` to `plugin-api` and an `ExporterPlugins.kt` loader in `cli`, like `RunnerPlugins.kt`, found by ServiceLoader. The plugin shows in the plugin list `loop run` logs.
- **What it reads:** every event Loop records, as an `EventSink` (`plugin-api/src/main/kotlin/loop/api/EventSink.kt`). The store redacts inside `record` (`store/src/main/kotlin/loop/store/SqliteEventStore.kt`, around lines 89 to 96), so a sink beside the store would get raw events, `runner_output` and comments included. The host wraps each exporter in the run's `Redactor` before it sees an event. A failing exporter returns a failed `Result` and never stops the run, as `EventSink` already requires.
- **Mapping:** one trace per attempt, its trace id derived from the attempt id; spans for the attempt, each session, each turn and each verification, built from `attempt_started` and `attempt_finished`, `session_rotated`, `turn_completed` and `verdict` (`spec-core/src/main/kotlin/loop/core/events/`). Other events become span events. Attributes carry the ids of ADR 0001 (project, run, issue, attempt, session, turn) and the ledger setup (tracker, kind, runner, model, template version).
- **Config:** opt in, under the plugin's own key in `WORKFLOW.md`, with the OTLP endpoint and protocol; nothing is exported when the key is absent. Preflight checks the key and refuses a bad endpoint. Headers that hold secrets come from the environment, never the file.
- **Tests:** a `SpanExporter` written in the test, no extra dependency, and no collector needed. A contract test checks that a failing exporter never fails `record`.

Out of this issue: metrics and logs signals, a collector setup, and exporting the manager agents' traces (M6).

## Acceptance criteria

- [ ] Premise checked first: no Gradle edit needed, and `diff_policy` refutes a protected edit (judgment).
- [ ] An attempt with two sessions, three turns and one verification exports one trace with the expected span tree and ids (test with a `SpanExporter` written in the test).
- [ ] Span attributes hold the ADR 0001 ids and the ledger setup (test).
- [ ] A token in an event's data never reaches the exported span (test with a token in `runner_output`).
- [ ] With the plugin key absent, nothing is exported; a bad endpoint is refused by preflight (tests).
- [ ] An exporter that throws or cannot reach its endpoint returns a failed `Result` and the run goes on (contract test).
- [ ] `./gradlew --no-daemon :plugins:export-otel:test` passes.
- [ ] The plugin loads through `ExporterPlugins.kt`, wrapped in the redactor, and shows in the plugin list `loop run` logs (CLI test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ExporterPluginsTest'` passes.
- [ ] `docs/plugins/export-otel.md` describes the config, the mapping, the redaction and the plugin profile (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
