# `otel` exporter profile

The `otel` exporter sends the events Loop records to an OpenTelemetry backend as traces, over OTLP. This page is its plugin profile, the keys, defaults, secrets and limits plan section 12 asks every plugin to publish, and how it maps Loop's events to spans. The implementation is `OtelEventExporter` in `plugins/export-otel` (`loop.export.otel`), registered as `OtelExporterProvider` in `META-INF/services/loop.api.EventExporterProvider`.

Written 2026-11-24 in issue m4-42. Terms follow [`CONTEXT.md`](../../CONTEXT.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [Mapping](#mapping)
5. [Redaction](#redaction)
6. [Failures](#failures)
7. [Plugin interface](#plugin-interface)

## Configuration

The exporter is opt in. Its section is `exporters.otel`, and nothing is exported when `WORKFLOW.md` has no such section:

```yaml
exporters:
  otel:
    endpoint: https://otel.example.com:4318
    protocol: http/protobuf
    headers:
      authorization: $OTEL_AUTHORIZATION
    timeout_ms: 10000
```

- `endpoint` is the collector's base URL, as `OTEL_EXPORTER_OTLP_ENDPOINT` takes it. Over `http/protobuf` the spans go to `<endpoint>/v1/traces`, over `grpc` to the endpoint itself. It must be an `http://` or `https://` URL with a host, and no user, password, query or fragment, which could hold a secret.
- `protocol` is `http/protobuf`, the default, or `grpc`.
- `headers` are sent with every export. Each value must be a whole `$NAME`, read from the host env when `loop run` starts. A header value written in the file is refused, so a token never sits in `WORKFLOW.md`.
- `timeout_ms` bounds one export.

The exporter is built once, when `loop run` starts. A reload that changes `exporters:` is checked by the preflight of the next tick, and takes effect at the next run.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `exporters.otel.endpoint` | string | required | no | none |
| `exporters.otel.protocol` | string | `http/protobuf` | no | none |
| `exporters.otel.headers` | map | none | no | none |
| `exporters.otel.timeout_ms` | integer | `10000` | no | 100 to 60000 |

`headers` is not a secret key of the profile, which checks string values only: the plugin checks each of its values itself, as described above, and the run's redactor learns the value of every `$NAME` it names.

## Startup check

Loop checks each section of `exporters:` in the preflight of startup and of every tick, so a bad section fails `loop run` before any attempt, naming the key and never a value:

| Problem | Message |
|---|---|
| `endpoint` absent, null or blank | `exporters.otel.endpoint is required` |
| `endpoint` not a URL, or not `http` or `https` | `exporters.otel.endpoint is not a valid URL`, `exporters.otel.endpoint must start with http:// or https://` |
| `endpoint` with no host, a user, a query or a fragment | `exporters.otel.endpoint must name a host`, `must not hold a user or a password, send them as headers from the env`, `must not hold a query`, `must not hold a fragment` |
| `protocol` not `http/protobuf` or `grpc` | `exporters.otel.protocol must be http/protobuf or grpc` |
| `timeout_ms` out of its limits | `exporters.otel.timeout_ms must be between 100 and 60000, got <n>` |
| a header value that is not a whole `$NAME` | `exporters.otel.headers.<name> must be a $NAME reference to the env, never the secret itself` |
| a header `$NAME` unset or blank | `exporters.otel.headers.<name> uses $NAME, which is not set` or `which is empty` |
| `exporters.otel` not a map | `exporters.otel must be a map` |
| a key of `exporters:` no plugin provides | `exporters.<key> has no exporter plugin, use otel` |
| a key the profile does not list | a startup warning, `exporters.otel.<key> is not a key of the otel exporter, Loop ignores it` |

`loop run` logs the plugins it found at startup, `event=plugins outcome=found` with `runners`, `trackers`, `exporters` and `enabled_exporters`, the last `none` when the workflow turns no exporter on.

## Mapping

Each attempt is one trace. Its trace id is the first 16 bytes of the SHA-256 of `loop:attempt:<attempt id>`, so an attempt always gives the same trace, whichever run exports it. Span ids are the first 8 bytes of the SHA-256 of a typed key, such as `loop:session:<attempt id>:<session id>`. Spans are built from the events' own timestamps, not from the time they are exported.

| Span | Opened by | Ended by | Parent |
|---|---|---|---|
| `loop.attempt` | `attempt_started` | `attempt_finished` | none, the root |
| `loop.session` | the first event of a new session id, `session_rotated` included | the next session, or the end of the attempt | the attempt |
| `loop.turn` | `turn_started` | `turn_completed` or `turn_failed` | its session |
| `loop.verification` | `check_started`, or `result_submitted` outside a turn | `verdict` | the attempt |
| `loop.event` | an event of the attempt no open span holds, such as `merge_check_started` after the attempt ended | the same event | the attempt |

Every other event of an attempt becomes a span event of the innermost span open for it, named after the event kind, with each data value as a `loop.<key>` attribute, and `loop.phase`, `loop.session_id` and `loop.turn_id` when the event has them. An event with the id of a turn that already ended, such as the `issue_moved` of the handoff, goes to the verification when one is open, else to the attempt. Events outside any attempt, such as `poll`, are not exported.

A span is exported as soon as its closing event is recorded, so a verification that comes after the attempt ended, a merge check or a restored wait, joins the same trace under the attempt span. When the run ends, the spans still open end at that time and are exported.

Every span carries these attributes:

- The ids of ADR 0001: `loop.project_id`, `loop.run_id`, `loop.issue_id`, `loop.issue_identifier` and `loop.attempt_id`, plus `loop.session_id` on session and turn spans, and `loop.turn_id` and `loop.turn` on turn spans.
- The ledger setup, which `attempt_started` carries since m4-42: `loop.tracker`, `loop.kind`, `loop.runner`, `loop.model` and `loop.template_version`, with `loop.config_version` and `loop.loop_version`. `kind` and `model` are absent when the setup has none.

The attempt span also carries `loop.outcome`, `loop.reason` and `loop.turns` from `attempt_finished`, the verification span `loop.verdict`. A span's status is an error for an attempt whose outcome is not `succeeded`, a `turn_failed` turn and a verdict other than `verified`; it is unset otherwise. The resource is `service.name=loop`, the instrumentation scope `loop`.

The exporter keeps the state of the last 256 attempts. An attempt dropped from it while a span is still open loses that span.

## Redaction

The store redacts inside its own `record`, so an exporter beside it would get raw events, runner output and comments included. The host wraps every exporter in the run's redactor (`RedactingEventSink` in `plugin-api`) before it sees an event: the issue identifier, the phase and every data value are redacted as the store redacts them, so a token in a `runner_output` line never reaches a span. The ids, the event kind and the data keys are Loop's own and pass as they are. The run's redactor also learns the value of every header `$NAME`, so a header token is redacted wherever Loop writes.

## Failures

An exporter never stops or slows the run. `loop run` records each event in the store first, whose result is the only one the orchestrator sees, then hands it to each exporter through a bounded queue of its own, read by its own coroutine. A full queue drops the event.

An export that throws, fails or takes longer than `timeout_ms` is a failed `Result`, never an exception. `loop run` logs an exporter's failures at most once a minute, `event=export outcome=failed exporter=otel failures=<n>` with the latest reason, and goes on. The OpenTelemetry SDK's own log is turned off, so a collector that is down does not fill stderr with stack traces. When the run ends, each exporter gets up to 10 seconds to send what its queue holds, then it is closed.

## Plugin interface

An exporter plugin implements `EventExporterProvider` in `plugin-api`: its `key`, the section `exporters.<key>`, its `profile`, a `validate` the preflight calls, `secretEnvNames` for the values the run's redactor learns, and `create`, which returns an `EventExporter`, an `EventSink` that Loop closes when the run ends. `ExporterPlugins` in `cli` finds the providers with `ServiceLoader`, on the classpath and in each JAR of `<LOOP_HOME>/plugins/`, like the runner and tracker plugins. Two providers of one key fail startup. `EventExporterContract` in the `plugin-api` test fixtures is the behavior every exporter must show: a failed export is a failed result, and the exporter goes on taking events after it.

Out of this plugin: metrics and logs signals, a collector setup, and the traces of the manager agents (M6).
