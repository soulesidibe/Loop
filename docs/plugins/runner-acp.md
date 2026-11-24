# `acp` runner profile

The `acp` runner drives an Agent Client Protocol (ACP) agent over JSON-RPC 2.0 on stdio, with an optional `agy` preset for Google's Antigravity CLI (`agy acp`). This page is its plugin profile, stating its keys, defaults, secrets, limits, capabilities, and context limit enforcement. The implementation is `AcpRunner` in `plugins/runner-acp` (`loop.runner.acp`), registered as `AcpRunnerProvider` in `META-INF/services/loop.api.RunnerProvider`; its profile is `AcpSettings.PROFILE`.

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [Launch and streams](#launch-and-streams)
5. [Session startup and turns](#session-startup-and-turns)
6. [Context limits and rotation](#context-limits-and-rotation)
7. [Capabilities](#capabilities)

## Configuration

`runner.kind: acp` selects it, and its section is `runners.acp`:

```yaml
runner:
  kind: acp
runners:
  acp:
    command: agy acp
    preset: agy
    model: gemini-4-neon-high
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
```

- `command`: required. Split into argv words without a shell, supporting single quotes, double quotes, and backslashes, and rejecting unquoted shell operators, expansions, globs, leading `~`, or leading environment assignments. Under `preset: agy`, `command` must be exactly two words: a program whose file name is `agy` (bare or by path), followed by `acp`, with no extra flags.
- `preset`: optional agent preset. `agy` is the only supported value; any other value fails validation.
- `model`: optional for generic ACP (forbidden when no `preset` is set), required under `preset: agy`, where it must start with `gemini-` and match a model id accepted by `agy`.
- `turn_timeout_ms`: silence limit while `session/prompt` is running before Loop kills the process group and fails the turn with `turn_timeout`. Each valid protocol message on stdout resets it. Defaults to `3600000` (1 hour).
- `read_timeout_ms`: maximum wait for each startup JSON-RPC response (`initialize`, `session/new`, `session/load`, `session/set_model`) before Loop kills the process group and fails the turn with `response_timeout`. Defaults to `120000` (2 minutes).
- `strict_mcp`: `true` or `false`, `true` by default. Without a `preset`, `strict_mcp: true` fails validation because Loop cannot isolate an unknown ACP agent's MCP configuration; set `strict_mcp: false` for a generic ACP agent or use `preset: agy`.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.acp.command` | string | required | no | none |
| `runners.acp.preset` | string | none | no | none |
| `runners.acp.model` | string | none | no | none |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

The runner has no secret key. Under `preset: agy`, Loop strips `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`, and `GOOGLE_GENAI_USE_VERTEXAI` from the inherited host environment by default so `agy` runs on the operator's Google login in the OS keyring; setting a key in `agent.env` opts in to API billing.

## Startup check

Loop checks `runners.acp` against the profile and `AcpRunnerProvider.validate` on every load of `WORKFLOW.md`:

| Problem | Code | Message |
|---|---|---|
| `command` missing or blank | `invalid_config` | `runners.acp.command is required` |
| `command` written for a shell | `invalid_config` | `runners.acp.command is run without a shell, so it cannot hold ... unquoted` |
| `preset` set to anything other than `agy` | `invalid_config` | `runners.acp.preset must be agy` |
| `model` set without `preset: agy` | `invalid_config` | `runners.acp.model requires runners.acp.preset: agy` |
| `strict_mcp: true` without `preset` | `invalid_config` | `runners.acp.strict_mcp cannot be true without runners.acp.preset: set runners.acp.strict_mcp to false or use runners.acp.preset: agy` |
| `preset: agy` with a command other than `agy acp` | `invalid_config` | `runners.acp.command must be agy acp (or <path>/agy acp) when runners.acp.preset is agy` |
| `preset: agy` without `model` | `invalid_config` | `runners.acp.model is required when runners.acp.preset is agy` |
| `preset: agy` with a model not starting with `gemini-` | `invalid_config` | `runners.acp.model must start with gemini- when runners.acp.preset is agy` |

## Launch and streams

- **Launch:** argv words run in the workspace working directory inside an isolated process group (`ProcessGroupLauncher`) with no shell. Under `preset: agy`, Loop appends `--disable-telemetry` and `--sandbox`, and under `strict_mcp: true` (the default) creates an attempt-local directory relative to `$HOME` containing `antigravity-cli/settings.json` (`{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`), passes `--gemini-dir <relative-under-HOME>`, and deletes that directory when the session stops. Loop never passes `--dangerously-skip-permissions`, `--effort`, `--remote-agent`, or `--remote-control`.
- **Loop MCP server:** when the attempt environment provides `LOOP_LAUNCHER` and `LOOP_TOOL_CONFIG`, `session/new` and `session/load` pass Loop's stdio MCP server (`name: "loop"`, `command: <LOOP_LAUNCHER>`, `args: ["tool", "mcp"]`) with only the 8 Loop environment variables (`LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`). `LOOP_SESSION_ID` and `LOOP_TURN_ID` stay fixed at the values of the turn that opened the session.
- **Stdout:** newline delimited JSON-RPC 2.0 messages, bounded to 10 MB per line (`ProcessGroupLauncher.MAX_LINE_BYTES`). Malformed lines are logged on stderr with a fixed reason (never quoting the line) and skipped.
- **Stderr:** kept apart from the protocol stream as diagnostic output.
- **Stop:** closing a session closes stdin and waits up to 5 seconds (`STOP_GRACE`) for the process to exit. When the grace expires, Loop kills the process group and reports `stop_grace_expired` through `AgentSession.stop(report)`. The isolated `--gemini-dir` folder is removed after the process exits.

## Session startup and turns

1. `initialize` with `protocolVersion: 1`, `clientInfo` (`{"name": "loop", "version": "<Loop version>"}`), and `clientCapabilities` (`{"fs": {"readTextFile": false, "writeTextFile": false}, "terminal": false}`). Under `preset: agy`, `agentInfo.version` must be on major version `1` (pinned `1.5.16`).
2. `session/new` (for a new session) or `session/load` (for `resumeSession`) with `cwd` and `mcpServers`.
3. Under `preset: agy`, `session/set_model` with `runners.acp.model`, verified against any `config_option_update` or response model id.
4. `session/prompt` with the prompt text (escaping a leading `/` under `preset: agy`). Turn 1 emits `session_started` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n reuse the same process and `sessionId` and emit `turn_started`.

## Context limits and rotation

Loop ships default context windows of `1048576` tokens in `ContextConfig.DEFAULT_WINDOWS` for `google/gemini-4-neon-high`, `google/gemini-4-neon-medium`, `google/gemini-4-neon-low`, `google/gemini-4-neon-xhigh`, `google/gemini-3.8-flash-high`, `google/gemini-3.8-flash-medium`, and `google/gemini-3.8-flash-low`. Soft and hard context limits are enforced by Loop's orchestrator using `context_usage` events reported from ACP `usage_update` notifications and session rotation on `stop()` / `openSession()`.

## Capabilities

| Capability | Value | Why |
|---|---|---|
| `continuation` | true | A session runs turns 1..n on one long lived ACP process and session id. |
| `loop_tools` | `mcp` | Loop's MCP server (`loop tool mcp`) is passed in `session/new` and `session/load` `mcpServers`. |
| `usage` | true | Session token totals are accumulated from `session/prompt` responses. |
| `cost` | false | ACP reports no USD cost, so dollar budgets do not hold for this runner. |
| `occupancy` | true | Per-request context occupancy comes from ACP `usage_update` notifications. |
| `sandbox` | false | Built-in file reads are gated by permission guardrails rather than a full OS sandbox. |
