# `acp` runner profile

The `acp` runner drives an Agent Client Protocol (ACP) agent over JSON-RPC 2.0 on stdio, with an optional `agy` preset for Google's Antigravity CLI (`agy acp`). This page is its plugin profile, stating its keys, defaults, secrets, limits, capabilities, and context limit enforcement. The implementation is `AcpRunner` in `plugins/runner-acp` (`loop.runner.acp`), registered as `AcpRunnerProvider` in `META-INF/services/loop.api.RunnerProvider`; its profile is `AcpSettings.PROFILE`.

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [Launch and streams](#launch-and-streams)
5. [Session startup and turns](#session-startup-and-turns)
6. [Isolated folder lifetime](#isolated-folder-lifetime)
7. [Context limits and rotation](#context-limits-and-rotation)
8. [Capabilities](#capabilities)

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
- `preset`: optional agent preset. `agy` is the only supported value; any other value fails validation. The preset, not the command, decides how Loop runs `agy`: `command: agy acp` without `preset: agy` runs as generic ACP, with no `agy` version check, no isolated `--gemini-dir` and no strict settings.
- `model`: optional for generic ACP (forbidden when no `preset` is set), required under `preset: agy`, where it must start with `gemini-` and match a model id accepted by `agy`.
- `turn_timeout_ms`: silence limit while `session/prompt` is running before Loop kills the process group and fails the turn with `turn_timeout`. Each valid protocol message on stdout resets it. Defaults to `3600000` (1 hour).
- `read_timeout_ms`: maximum wait for each startup JSON-RPC response (`initialize`, `session/new`, `session/load`, `session/set_model`) before Loop kills the process group and fails the turn with `response_timeout`. Defaults to `120000` (2 minutes).
- `strict_mcp`: `true` or `false`, `true` by default. Without a `preset`, `strict_mcp: true` fails validation because Loop cannot isolate an unknown ACP agent's MCP configuration; set `strict_mcp: false` for a generic ACP agent. Under `preset: agy`, `strict_mcp` must stay `true` so Loop always launches with the isolated `--gemini-dir` that enforces `toolPermission: strict`.

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
| `preset: agy` with `strict_mcp: false` | `invalid_config` | `runners.acp.strict_mcp must be true when runners.acp.preset is agy` |
| `preset: agy` with a command other than `agy acp` | `invalid_config` | `runners.acp.command must be agy acp (or <path>/agy acp) when runners.acp.preset is agy` |
| `preset: agy` without `model` | `invalid_config` | `runners.acp.model is required when runners.acp.preset is agy` |
| `preset: agy` with a model not starting with `gemini-` | `invalid_config` | `runners.acp.model must start with gemini- when runners.acp.preset is agy` |

## Launch and streams

- **Launch:** argv words run in the workspace working directory inside an isolated process group (`ProcessGroupLauncher`) with no shell. Under `preset: agy`, Loop sweeps stale isolated folders, removes the folders earlier sessions of the same issue kept, and appends `--disable-telemetry`, `--sandbox`, and `--gemini-dir <relative-under-HOME>` pointing to an isolated folder containing `antigravity-cli/settings.json` (`{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`). The folder is `$LOOP_HOME/agy/<attempt>-<id>` when `LOOP_HOME` lies under `$HOME`, else `$HOME/.loop-agy/<attempt>-<id>`, never in the attempt folder, which Loop deletes when the attempt ends. See [Isolated folder lifetime](#isolated-folder-lifetime). Loop never passes `--dangerously-skip-permissions`, `--effort`, `--remote-agent`, or `--remote-control`.
- **Loop MCP server:** when the attempt environment provides `LOOP_LAUNCHER` and `LOOP_TOOL_CONFIG`, `session/new` and `session/load` pass Loop's stdio MCP server (`name: "loop"`, `command: <LOOP_LAUNCHER>`, `args: ["tool", "mcp"]`) with only the 8 Loop environment variables (`LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`). `LOOP_SESSION_ID` and `LOOP_TURN_ID` stay fixed at the values of the turn that opened the session. A respawn or a resume under `preset: agy` passes the `mcpServers` again in `session/load`.
- **Stdout:** newline delimited JSON-RPC 2.0 messages, bounded to 10 MB per line (`ProcessGroupLauncher.MAX_LINE_BYTES`). Malformed lines are logged on stderr with a fixed reason (never quoting the line) and skipped.
- **Stderr:** kept apart from the protocol stream as diagnostic output.
- **Stop:** closing a session closes stdin and waits up to 5 seconds (`STOP_GRACE`) for the process to exit. When the grace expires, Loop kills the process group and reports `stop_grace_expired` through `AgentSession.stop(report)`. Under `preset: agy`, the isolated `--gemini-dir` folder of a session that reported its runtime session id is kept after the process exits, since it holds `agy`'s session store; any other folder is removed (logging any removal failure).

## Session startup and turns

1. Under `preset: agy`, verify protected workspace customization roots (`.agents/`, `.agent/`, `_agents/`, `_agent/`) against the attempt base commit before spawning the session.
2. `initialize` with `protocolVersion: 1`, `clientInfo` (`{"name": "loop", "version": "<Loop version>"}`), and `clientCapabilities` (`{"fs": {"readTextFile": false, "writeTextFile": false}, "terminal": false}`). Under `preset: agy`, `agentInfo.version` must be on major version `1` and no older than `1.5.18` (pinned `1.5.18`); an older one fails the turn with `response_error: agy <version> is older than the minimum version 1.5.18 (pinned 1.5.18)`. Both failures carry the category `runner_unavailable`, so an `agy` downgraded during a run, or a different `agy` than the one preflight checked, holds the run until `agy` is updated instead of spending `max_attempts` slots. An `initialize` answer with no `agentInfo.version` stays an ordinary failure.
3. `session/new` (for a new session) or `session/load` (for `resumeSession`, and under `preset: agy` for a respawn) with `cwd` and `mcpServers`. Under `preset: agy`, a load first needs `agentCapabilities.loadSession: true` in the `initialize` answer.
4. Under `preset: agy`, `session/set_model` with `runners.acp.model`, verified against any `config_option_update` or response model id.
5. `session/prompt` with the prompt text (escaping a leading `/` under `preset: agy`), preceded by the protected workspace roots check before every turn. Turn 1 emits `session_started` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n reuse the same process and `sessionId` and emit `turn_started`.

## Isolated folder lifetime

Under `preset: agy`, the isolated `--gemini-dir` folder holds `agy`'s session store, so since bl-213 it lives as long as the session may still be loaded, not as long as the process.

- **Record:** each folder holds `.loop-pid` (the Loop process that owns it) and, once `session/new` or `session/load` answered, `.loop-session.json` with the project, the issue id, the workspace, the runtime session id and a state, `live` or `kept`. A session that never reported a runtime session id deletes its folder when it stops.
- **Respawn after a dead process:** a turn 2..n whose `agy` process is gone (it exited, or a failed turn killed it) starts a new `agy acp` with the same `--gemini-dir`, then sends `initialize`, `session/load` with the session id, `cwd` and the `mcpServers` of the turn that opened the session, `session/set_model`, and runs the turn, after the line `loop: the agy process of session <id> is gone, turn <n> starts a new one and loads the session`. At most once per turn: a failed respawn fails the turn with the reason. A fresh session would get only the continuation guidance, not the prompt, so a respawn never falls back to one.
- **Resume after a network hold:** `resumeSession(ctx, runtimeSessionId)` finds the kept folder whose record names that runtime session, the issue and the project, claims it as `live` for this Loop process, and returns a session whose turn 1 loads it. When no folder is kept, it returns null and Loop opens a fresh session. When turn 1 cannot load it (`initialize` without `loadSession`, a `session/load` error, or a refused folder), the turn says why in a `loop:` line, deletes the folder and opens a fresh session with the full prompt in a fresh folder, never a half resumed one.
- **Isolation on a load:** before every `session/load` from a kept folder, Loop refuses the folder when its root holds anything but `antigravity-cli/`, `config/`, `.loop-pid` and `.loop-session.json`, a symbolic link among them, or `config/hooks.json` (which `agy` would run), then rewrites `antigravity-cli/settings.json` with the strict settings. The protected workspace roots check runs before the load and before every turn. `strict_mcp`, `--sandbox`, `--disable-telemetry` and the permission policy are unchanged.
- **Removal:** a kept folder is removed at one of three ends. (1) A new session of the same issue opens, a fresh one or a resume: Loop only ever resumes the last session of an issue, so the folders of earlier sessions can never be loaded again. A resumed session that stops is kept again, so a second hold resumes too. (2) The issue reaches a terminal state: Loop removes its workspace, and the sweep removes a kept folder whose recorded workspace is gone. (3) The stale sweep, at every launch and every resume, removes any folder whose owner process is dead or whose `.loop-pid` is missing: the choice to resume is kept in memory, so a restarted `loop run` never resumes.

## Guards and holdout protection

Every `session/request_permission` request is evaluated fail-closed in Kotlin using the shared matchers in `plugins/runner-process` (a guardrail, not an OS sandbox):

- **Paths:** every path the policy reads, from `locations[].path`, `locations[].uri`, any `rawInput` or `args` path key and the working directory key, goes through one normalization before any check. A `file:` URI (`file:///x`, `file://localhost/x`, `file:/x`) becomes its absolute path with percent encoding decoded, and a plain path is unchanged. Any other URI scheme, a `file:` URI naming another host, one with no absolute path or one with broken percent encoding is declined with a `loop:` line naming the problem, never read as a workspace relative path.
- **Commands (`run_command`, `kind: execute`):** fails closed when command text is missing; declines any command matching `DeniedCommands` (including `<LOOP_LAUNCHER> approve` and `<LOOP_LAUNCHER> resume`), naming `holdoutDir`, or targeting `.agents/`, `.agent/`, `_agents/`, or `_agent/`.
- **File reads and searches (`read_file`, `kind: read`/`search`):** fails closed when paths are missing; declines any path under `holdoutDir` (absolute and real paths).
- **File writes and edits (`write_file`, `edit_file`, `replace`, `kind: edit`/`delete`/`move`):** fails closed when paths are missing; declines any path under `holdoutDir`, under `.agents/`, `.agent/`, `_agents/`, or `_agent/`, or inside Loop's isolated `--gemini-dir` or attempt folder.
- **Allow options:** accepts only `allow_once` (never falls back to `allow_always` or bare `allow`), declining when no `allow_once` option is offered.
- **MCP tools:** allows Loop's own `mcp_loop_*` tools and declines any other MCP tool when `strict_mcp: true`.
- **Human-input prompts:** unsupported human-input requests end the turn immediately with `turn_input_required` and stop the session.
- **Protected workspace roots check (`preset: agy`):** before every session and before every `session/prompt`, inspects `.agents/`, `.agent/`, `_agents/`, and `_agent/` directly in directories between the workspace and the repository root (ignoring nested paths `agy` never loads). Any protected root that is a symbolic link (even if tracked) is refused on every turn. Every on-disk customization file (`hooks.json`, `mcp_config.json`, `skills.json`, `rules.json`, `plugins.json`, or any file under `plugins/`, `rules/`, or `hooks/`) is compared against the host-resolved base commit SHA (`git ls-tree` mode and `git hash-object` blob SHA, independent of the git index). When no base commit can be resolved from `AttemptContext.baseRef`, `main`, `master`, or `origin/HEAD`, the check fails closed naming `kinds.code.guards.base` and `kinds.code.delivery.pull_request.base`. Any mismatch fails the turn naming the path and stops the session.

## Context limits and rotation

Loop ships default context windows of `1048576` tokens in `ContextConfig.DEFAULT_WINDOWS` for `google/gemini-4-neon-high`, `google/gemini-4-neon-medium`, `google/gemini-4-neon-low`, `google/gemini-4-neon-xhigh`, `google/gemini-3.8-flash-high`, `google/gemini-3.8-flash-medium`, and `google/gemini-3.8-flash-low`.

- **Occupancy:** every `usage_update` notification with `used > 0` emits `context_usage` with `google/<model>`, deduplicated when occupancy and model are unchanged.
- **Usage:** every `session/prompt` response carrying `usage` adds its turn counts into the session's running absolute totals with `totalCostUsd = null`.
- **Hard-limit interrupt:** `AgentSession.interrupt()` sends a `session/cancel` notification, answers any pending `session/request_permission` with `cancelled`, preserves `result.usage` on `stopReason: "cancelled"`, and ends the turn cleanly so Loop rotates onto a fresh session.
- **Error mapping:** structured JSON-RPC errors reporting authentication or eligibility failure (401, 403, expired login, `IneligibleTierError`), rate limit or overload (429, 503, 529), model refusal (`-32602 Unknown model ID` or `modelNotFound`), or runtime-reported network transport failure with no HTTP status map to `runner_unavailable`, as does an `agy` of another major or older than the minimum at `initialize` under `preset: agy`. All other errors, exits, and malformed lines are ordinary failures.
- **Startup preflight (`preset: agy`):** verifies the `agy` binary is reachable, on pinned major version `1` and no older than `1.5.18`, reading the version from the line of `agy --version` that is only a version, else its last version token, so a banner such as `new version 1.6.0 available` printed before it never decides it (errors stop startup; 1.5.18 is the first version that applies permission prompts to files read or edited through `file:///` URIs, which the guards above rely on), and runs `agy models` to warn on login problems, offline failures, or a configured model not listed.

## Capabilities

| Capability | Value | Why |
|---|---|---|
| `continuation` | true | A session runs turns 1..n on one long lived ACP process and session id. `resumeSession` loads the runtime session of an earlier attempt with `session/load`; under `preset: agy` it does so from the kept isolated folder, and returns null when none is kept. |
| `loop_tools` | `mcp` | Loop's MCP server (`loop tool mcp`) is passed in `session/new` and `session/load` `mcpServers`. |
| `usage` | true | Session token totals are accumulated from `session/prompt` responses. |
| `cost` | false | ACP reports no USD cost, so dollar budgets do not hold for this runner. |
| `occupancy` | true | Per-request context occupancy comes from ACP `usage_update` notifications. |
| `sandbox` | false | Built-in file reads are gated by permission guardrails rather than a full OS sandbox. |
