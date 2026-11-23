# `claude-code` runner profile

The `claude-code` runner runs Claude Code headless as the agent, one long lived process per attempt (m3-07). This page is its plugin profile, the keys, defaults, secrets and limits plan section 12 asks every plugin to publish. The implementation is `ClaudeCodeRunner` in `plugins/runner-claude-code` (`loop.runner.claudecode`), registered as `ClaudeCodeRunnerProvider` in `META-INF/services/loop.api.RunnerProvider`; its profile is `ClaudeCodeSettings.PROFILE`.

Written 2026-11-16 in issue m3-05. Terms follow [`CONTEXT.md`](../../CONTEXT.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md#claude-code-runner-configuration-keys-extension-m1-05).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [Machine checks](#machine-checks)
5. [Capabilities](#capabilities)
6. [MCP servers](#mcp-servers)

## Configuration

`runner.kind: claude-code` selects it, and its section is `runners.claude-code`:

```yaml
runner:
  kind: claude-code
runners:
  claude-code:
    command: claude
    model: sonnet
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
```

- `command`: the Claude Code executable, for example `claude`.
- `model`: passed as `--model` when set; without it Claude Code uses its own default.
- `turn_timeout_ms`: the silence timeout, the turn fails when the agent writes no stdout line for this long during the turn (one hour by default). It does not run between turns, while the process is idle.
- `read_timeout_ms`: the time to the first line, the turn fails when Claude Code writes no stdout line within it once the turn's input is written (two minutes by default). Before m3-07 it was the time to the `system` `init` event.
- `strict_mcp`: passes `--strict-mcp-config`, so Loop's MCP server is the agent's only one (`true` by default, see [MCP servers](#mcp-servers)).

A timeout may be written bare or quoted, `600000` or `"600000"`, and so may `strict_mcp`, `false` or `"false"`, in any case.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.claude-code.command` | string | required | no | none |
| `runners.claude-code.model` | string | none | no | none |
| `runners.claude-code.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.claude-code.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.claude-code.strict_mcp` | boolean | `true` | no | none |

The runner has no secret key. Claude Code reads its own credentials; `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` are always removed from the agent env and redacted wherever they show up. Both timeouts are capped at one day (86400000 ms); before m3-05 any positive integer was accepted.

## Startup check

Loop checks `runners.claude-code` against the profile on every load of `WORKFLOW.md`, at startup, in the preflight of every tick and on every reload, before the plugin reads the section (see [plugin profiles](../spec-decisions.md#plugin-profiles-extension-m3-05)). The first problem found, in profile order, fails:

| Problem | Code | Message |
|---|---|---|
| `command` absent, null or blank | `invalid_config` | `runners.claude-code.command is required` |
| a key of the wrong type, such as `turn_timeout_ms: soon` | `invalid_config` | `runners.claude-code.turn_timeout_ms must be an integer` |
| a timeout out of its limits | `invalid_config` | `runners.claude-code.turn_timeout_ms must be between 1 and 86400000, got 0` |
| `strict_mcp` neither `true` nor `false`, such as `yes` | `invalid_config` | `runners.claude-code.strict_mcp must be a boolean` |
| `runners.claude-code` not a map | `invalid_config` | `runners.claude-code must be a map` |
| a key the profile does not list | none, a startup warning | `runners.claude-code.<key> is not a key of the claude-code runner, Loop ignores it` |

An unknown key stays allowed, for forward compatibility: `loop run` prints the warning at startup and goes on, and a reload that adds one logs it once.

## Machine checks

Apart from the profile, `loop run` checks at startup that Claude Code can run on this machine ([preflight for the Claude Code runner](../spec-decisions.md#preflight-for-the-claude-code-runner-extension-m1-05)): an unreadable managed settings file or an unknown uid is a warning. The merge risk reviewer (m2-28) uses the same `command`, whatever `runner.kind` is (m4-06): under another runner it reads `runners.claude-code.command` when set, else runs `claude`, and under `policy: auto` with merge `loop run` warns at startup when that command cannot be found ([the reviewer apart from the runner](../spec-decisions.md#the-reviewer-apart-from-the-runner-extension-m4-06)).

## Capabilities

What `ClaudeCodeRunner.capabilities` declares (m3-06, see [runner capabilities](../spec-decisions.md#runner-capabilities-and-the-agentrunner-contract-extension-m3-06)), checked by `ClaudeCodeRunnerContractTest`:

| Capability | Value | Why |
|---|---|---|
| `continuation` | true | Turns 2 to n write their input to the attempt's long lived process; a process that died is respawned with `--resume <runtime session id>` (m3-07). |
| `loop_tools` | `mcp` | Loop's MCP server, `loop tool mcp`, passed with `--mcp-config`. |
| `usage` | true | From the `result` lines, absolute for the session. Since bl-71 each line's `model_usage_since_last_result`, every model and subagent included, else its `usage`, see [subagent spend](../spec-decisions.md#claude-code-subagent-spend-extension-bl-71). |
| `cost` | true | `total_cost_usd` of the `result` lines, the session's so far, subagents included. |
| `occupancy` | true | Since m3-08: each main session `assistant` message's usage, `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`, once per message id, with the model of the `init` line. Subagent messages are not counted, see [context occupancy](../spec-decisions.md#claude-code-context-occupancy-extension-m3-08). |
| `sandbox` | false | The generated deny rules are a guardrail, not a sandbox (plan 11.1). |

## MCP servers

The process runs under `--permission-mode bypassPermissions`, so the agent, and every subagent it spawns, calls any MCP tool of the session with no prompt. With `strict_mcp: true`, the default since bl-72, each spawn passes `--strict-mcp-config`: the session loads Loop's MCP server, passed with `--mcp-config` when the attempt has one, and nothing else. None of the operator's servers, none of the project's `.mcp.json`, none of the claude.ai connectors. Without Loop's config the flag alone loads no server.

`strict_mcp: false` loads Loop's server beside every server Claude Code finds, the behavior from m2-16 to bl-72. Use it only when the workflow's agents need the operator's servers and the operator accepts that they call them with no prompt. The decision and the Codex runner's matching default are in [strict MCP](../spec-decisions.md#strict-mcp-extension-bl-72).
