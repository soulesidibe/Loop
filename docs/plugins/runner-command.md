# `command` runner profile

The `command` runner runs a shell command as the agent, one process per turn. This page is its plugin profile, the keys, defaults, secrets and limits plan section 12 asks every plugin to publish. The implementation is `CommandRunner` in `plugins/runner-command` (`loop.runner.command`), registered as `CommandRunnerProvider` in `META-INF/services/loop.api.RunnerProvider`.

Written 2026-11-16 in issue m3-05. Terms follow [`CONTEXT.md`](../../CONTEXT.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [How it runs](#how-it-runs)
5. [Capabilities](#capabilities)

## Configuration

`runner.kind: command` selects it, and its section is `runners.command`:

```yaml
runner:
  kind: command
runners:
  command:
    command: ./agent.sh
```

The value of `command` is kept verbatim, `$` and `~` included: the shell expands them, not Loop.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.command.command` | string | required | no | none |

The runner has no secret and no limit. A `$NAME` in `command` is not a secret reference: the shell reads it from the agent environment when the command runs.

## Startup check

Loop checks `runners.command` against the profile on every load of `WORKFLOW.md`, at startup, in the preflight of every tick and on every reload, before the plugin reads the section (see [plugin profiles](../spec-decisions.md#plugin-profiles-extension-m3-05)):

| Problem | Code | Message |
|---|---|---|
| `command` absent, null or blank | `invalid_config` | `runners.command.command is required` |
| `command` not a string, such as a list | `invalid_config` | `runners.command.command must be a string` |
| `runners.command` not a map | `invalid_config` | `runners.command must be a map` |
| a key the profile does not list | none, a startup warning | `runners.command.<key> is not a key of the command runner, Loop ignores it` |

An unknown key stays allowed, for forward compatibility: `loop run` prints the warning at startup and goes on, and a reload that adds one logs it once.

## How it runs

Each turn launches `sh -c <command>` in the workspace, in its own process group, with the turn input on stdin (see [command runner shell](../spec-decisions.md#command-runner-shell-deviation-from-101-m1-02) and [continuation](../spec-decisions.md#command-runner-continuation-extension-m1-06)). The agent env follows the shared policy: the host env minus every `$NAME` `WORKFLOW.md` references, plus `agent.env` and Loop's own variables.

## Capabilities

What `CommandRunner.capabilities` declares (m3-06, see [runner capabilities](../spec-decisions.md#runner-capabilities-and-the-agentrunner-contract-extension-m3-06)), checked by `CommandRunnerContractTest`:

| Capability | Value | Why |
|---|---|---|
| `continuation` | true | A session runs the command again for each turn on the same thread. |
| `loop_tools` | `files` | The agent uses `loop tool move`, `loop tool comment` and the result file `.loop/result.json`. |
| `usage` | false | A script reports no tokens. |
| `cost` | false | A script reports no cost. |
| `occupancy` | false | A script reports no `context_usage`, so Loop can enforce only the prompt limit and a turn cap. `loop run` says so at startup and `loop status` under each running attempt, see [m3-09](../spec-decisions.md#peak-occupancy-and-runners-without-occupancy-extension-m3-09). The runner names no model, so its prompt limit is `context.prompt_limit` of the smallest window in `context.windows`, see [m3-13](../spec-decisions.md#prompt-tokens-and-prompt-limit-extension-m3-13). |
| `sandbox` | false | The script runs as the same OS user, unconfined. |
