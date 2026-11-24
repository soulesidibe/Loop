---
id: m4-42
title: "OpenTelemetry export plugin"
state: done
priority: 1
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

2026-11-24 20:10Z loop: Loop opened pull request #44 for this issue: https://github.com/soulesidibe/Loop/pull/44

2026-11-24 20:19Z loop: Loop did not merge pull request #44 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1261 lines outside tests and docs, over the limit of 1000, 2291 lines in all
  Merge risk: band medium, score 1.53.
  - alignment: partial, The diff covers the provider interface, loader, otel plugin, config, redaction and docs, but it also edits spec-core (AttemptEvents, Orchestrator, WorkflowFile, KeyValueLog) and rewires RunCommand with an async queueing ExportingSink. The issue did not name those changes, and the diff shows no Gradl
  - impact: medium (floor medium: the diff changes 1261 lines outside tests and docs, over half the limit of 1000), About 1261 non-test lines are new, and the run's sink path in RunCommand and the attempt_started event schema in spec-core change. Exporters are opt in, so the default behavior is unchanged.
  - verification: strong, Tests cover the span tree, ids and attributes, redaction, preflight refusals, the exporter contract and CLI runs with an unreachable endpoint, and a unit test covers redaction in the sink wrapper. I did not run them or read the exporter source.
  - rollback_cost: two_way, A revert removes the code, and the exporter keeps no state. The only leftover is the extra setup fields on attempt_started rows already stored, which are additive and harmless.
  - error_detection: partial, Exporter failures are logged as event=export outcome=failed at most once a minute, and a full queue drops events. Bad config fails preflight loudly. Dropped events and the OTel SDK's own log being turned off make steady partial loss easy to miss.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 16 of 27 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-24 20:40Z soule: sent back before merge, after the merge risk reviews of pull request #44 (Sonnet 5.5 then Fable 5.2, both medium 2.07). Fix this on the same branch, then submit again:
  - **Shutdown must fit the stop grace.** With an exporter configured and a collector that hangs (no answer, not a refused connection), `ExportingSink.close()` waits up to 10 s for the drain (`ExporterPlugins.kt`, around lines 177 and 231), then `OtelEventExporter.close()` waits up to 2 x `timeout_ms` for export and shutdown (`OtelEventExporter.kt`, around lines 63 and 64). The shutdown hook waits only `SHUTDOWN_WAIT_SECONDS = 10` (`RunCommand.kt`, around line 614), and the sink closes inside that window, before the store closes and before `onEnd`. So `loop stop`, SIGINT or SIGTERM exits 143 instead of 0, with no `run completed` line, no run lock release and no store close, which breaks the clean stop of m1-34. Bound the drain plus the exporter close to about 2 to 3 s in total, whatever `timeout_ms` says, dropping what is left and counting it in the `event=export outcome=failed` line.
  - **Tests.** Add a test with an exporter that hangs (a collector that accepts the connection and never answers, or a fake exporter that blocks in export and close), proving that `loop stop` still exits 0 with `run completed`, and a test that a full queue drops events without blocking `record`.
  - **Docs.** `docs/plugins/export-otel.md` must not say the exporter never slows the run for this case; state the shutdown budget, and that a secret the redactor does not know leaves the machine with the export.

2026-11-24 20:51Z loop: Loop updated pull request #44 for this issue: https://github.com/soulesidibe/Loop/pull/44

2026-11-24 20:54Z loop: Loop did not merge pull request #44 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Failed conditions:
  - diff_size: the diff changes 1325 lines outside tests and docs, over the limit of 1000, 2588 lines in all
  Merge risk: band medium, score 1.53.
  - alignment: partial, The diff covers the provider interface, loader, plugin, config, redaction, docs and the requested shutdown budget, but it also edits spec-core (AttemptEvents, Orchestrator, WorkflowFile, KeyValueLog) and rewires RunCommand's sink path, which the issue did not name.
  - impact: medium (floor medium: the diff changes 1325 lines outside tests and docs, over half the limit of 1000), About 1325 non-test lines are new and the run's sink path and shutdown sequence in RunCommand change, though exporters are opt in so default behavior is unchanged.
  - verification: strong, Tests cover the span tree, ids, redaction, preflight refusals, the exporter contract, a hanging collector with exit 0 on stop, queue overflow and the close budget, though I did not run them.
  - rollback_cost: two_way, A revert removes the code and the exporter keeps no state, leaving only additive setup fields on stored attempt_started rows.
  - error_detection: partial, Export failures and events dropped at close are logged as event=export outcome=failed, rate limited to once a minute, but a full queue drops events and a closer thread left behind is easy to miss.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 22 of 28 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
