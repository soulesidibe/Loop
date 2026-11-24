---
id: spec-gemini-support
title: Gemini support in Loop through the Antigravity CLI, ACP and print mode runners
labels: [needs-triage]
---

## Problem statement

Loop runs issues through three runners today: `command` (a shell command that supports continuation turns by re-invoking per turn, see `docs/spec-decisions.md#command-runner-continuation-extension-m1-06`), `claude-code` (Claude Code bidirectional `stream-json`), and `codex-app-server` (Codex JSON-RPC over stdio). There is no runner for Google's Gemini models.

Google's Gemini CLI (`gemini`) no longer serves the operator: on 2026-11-24, every call of `gemini` 0.42.0 on the operator's personal Google login fails at authentication with `IneligibleTierError` ("This client is no longer supported for Gemini Code Assist for individuals ... migrate to the Antigravity suite"), whatever the model. Its replacement is the Antigravity CLI, `agy` (1.5.14 installed on the development machine), which runs on the same Google login. `agy models` lists Gemini 4 models whose id carries the effort level (for example `gemini-4-neon-high`, Gemini 4 Neon at high effort), plus Claude and GPT-OSS models.

`agy` exposes two programmatic transports:

1. **Agent Client Protocol** (`agy acp`): bidirectional JSON-RPC 2.0 over stdio, one long lived process per session (`--process-per-session` isolates each session in a worker process), `session/cancel` for mid-turn interrupts, `session/new` `mcpServers` for Loop's MCP server, and `session/request_permission` callbacks for host side guards. `agy acp` takes no `--model` flag.
2. **Print mode** (`agy -p --output-format stream-json --input-format stream-json`): one process that reads one NDJSON message per line on stdin and runs a turn for each, `--conversation <id>` to resume a conversation, `--model`, `--mode` (`accept-edits`, `auto`, `plan`), `--settings` (session-only settings, validated against the known keys), and `--sandbox` ("terminal restrictions").

`docs/PLAN.md` lists both `acp` and `gemini` as `AgentRunner` plugins (`docs/PLAN.md` section 12) and schedules `runner-acp` in M7 (section 17). Pulling ACP and a Gemini runner ahead of M5 (permissions hardening) and M6 (manager agents) lets Loop run and compare Gemini models now; `docs/PLAN.md` will carry a note recording this milestone jump, and that the `gemini` row is served by `agy`, when implementation starts.

## Solution

Support Gemini through two separate `RunnerProvider` plugins, built in order after a shared verification spike:

1. **`plugins/runner-acp`** (`runner.kind: acp`, section `runners.acp`): built **first**, gated on Spike 0. Drives an Agent Client Protocol agent over JSON-RPC stdio, with an optional `runners.acp.agent: agy` preset that adds the per-attempt flags, settings and model selection `agy acp` needs (exact mechanism from Spike 0). Only ACP is known to give a clean mid-turn `interrupt()`, and live context occupancy if `agy` reports it, which Loop needs to rotate sessions and keep main sessions under 25 percent of their window.
2. **`plugins/runner-agy`** (`runner.kind: agy`, section `runners.agy`): built **second**, only if Spike 0 shows a clear reason (for example, if ACP occupancy or guards prove unusable, or if print mode gives occupancy and a clean interrupt of its own). Drives `agy` print mode with stream-json in and out. If dropped, `agy` stays unregistered and is never aliased to `acp`.

Both plugins register through `META-INF/services/loop.api.RunnerProvider`, reuse `plugins/runner-process` (`ProcessGroupLauncher`, `BoundedLineReader`, `agentEnvironment` in `HostEnvironment.kt`), share one canonical deny list and holdout matcher moved into `plugins/runner-process`, and pass `AgentRunnerContract`.

| Capability | `runner-acp` (`command: agy acp`, `agent: agy`) | `runner-acp` (generic, no `agent` preset) | `runner-agy` (print mode, stream-json) |
|---|---|---|---|
| Process model | One long lived JSON-RPC process per session | One long lived JSON-RPC process per session | One long lived process per session, one stdin message per turn (verified in Spike 0) |
| Model selection | Through ACP or a generated settings file (Spike 0), from `runners.acp.model` | Agent dependent | `--model <id>` |
| Context occupancy and rotation | Yes, if Spike 0 confirms per-request input tokens; else reconsider spec | Agent dependent (`false` when not reported) | Only if Spike 0 confirms per-request input tokens in stream-json; else `occupancy = false`, and Loop warns and enforces only the prompt limit and turn cap (`RunCommand.kt:264`) |
| Hard-limit `interrupt()` | Clean `session/cancel` plus `cancelled` reply to any pending permission request | Clean `session/cancel` plus `cancelled` reply to any pending permission request | Only if Spike 0 finds a stdin interrupt message; else Loop's 10 s grace and kill |
| Loop MCP server (`submit_result` and tracker tools) | Passed in `session/new` `mcpServers` | Passed in `session/new` `mcpServers` | Through a generated MCP config (mechanism verified in Spike 0) |
| `strict_mcp` | Enforced by the preset's isolated config (probe 5) | `strict_mcp: true` is a preflight error (cannot isolate an unknown agent's MCP config) | Only if probe 13 finds an isolation for print mode; else `strict_mcp: true` is a preflight error |
| Guards and holdout folder | Kotlin `session/request_permission` (fail closed), generated deny rules, the `loop tool guard` hook, and protected workspace customization roots (guardrail, not a sandbox) | Kotlin `session/request_permission` (fail closed) plus client `fs` reads if supported; weaker on unprompted reads (guardrail, not a sandbox) | Generated deny rules, the `loop tool guard` hook, and protected workspace customization roots (guardrail, not a sandbox) |

## Decisions

### Spike 0 gates both plugins (ACP probes first)

Before writing runner code, an operator spike probes `agy` (1.5.16 on 2026-11-24; it updates itself, see probe 8) and pins the version in `docs/spec-decisions.md` (following the Codex precedent in `docs/spec-decisions.md#codex-cli-app-server-schema-and-mcp-loading-m3-02`). Run the ACP probes first so work can stop early. Probes run with `gemini-4-neon-high`. Three probes are stop conditions: if the model cannot be pinned (probe 2), if neither transport exposes per-request occupancy (probe 3), or if workspace hooks and plugins can neither be turned off nor detected before they run (probe 6), stop and reconsider this spec.

**ACP probes (first):**

1. **Handshake, auth and protocol version:** record `initialize` (protocol version, auth methods, agent capabilities), whether `authenticate` is required under the Google login (`agy acp --login` exists for terminal auth), and whether `session/load` works for `resumeSession`.
2. **Model selection (stop condition):** find how an ACP client pins the model for `agy acp`, which has no `--model` flag (an ACP session config or mode option, a settings file, an environment variable), record the exact model ids and their context windows, and confirm the session reports the model it runs on. Hook payloads show `"modelName": "auto"`, so an unpinned session may be routed automatically. If `gemini-4-neon-high` cannot be pinned and confirmed, stop. The effort is part of the model id, so `--effort` is never passed.
3. **Occupancy and usage (stop condition):** confirm whether ACP updates emit per-request input tokens (main session, cached included, not cumulative) for `RunnerEvent.ContextUsage`, absolute totals for `RunnerEvent.Usage`, and USD cost. If neither ACP nor print mode (probe 15) exposes per-request occupancy, stop.
4. **Permission triggers, approval mode and client `fs`:** list every built-in tool (file read, search, list, write, edit, `run_command`, browser, MCP) and record which ones fire `session/request_permission` when `--dangerously-skip-permissions` is not passed, what fields their payloads carry (`rawInput`, `locations`), which approval mode `agy acp` runs in and how a client sets it (it has no `--mode`), and whether advertising ACP client `fs` capabilities routes file reads through Loop.
5. **Config isolation and the login (ACP):** `agy` reads settings from `~/.gemini/antigravity-cli/settings.json`, global customizations (rules, skills, `skills.json`, `mcp_config.json`, plugins, hooks) from `~/.gemini/config/`, and the login from the top of `~/.gemini/` (`oauth_creds.json`, `google_accounts.json`). Record what each of `--app-data-dir`, `--cli_dir_name`, `--gemini-dir` and `--include-default-customizations=false` moves. Prefer a flag that gives one attempt its own settings, MCP servers and hooks while the login stays read in place. Run two sessions in parallel across a token refresh and confirm the login is never copied, never turned from a link into a file, and never lost by either session.
6. **Workspace customizations (stop condition):** `agy` loads workspace customizations with the highest priority from `.agents/`, `.agent/`, `_agents/` and `_agent/` (rules, skills, `skills.json`, `hooks.json`, plugins with their own `mcp_config.json`), plus `GEMINI.md` and `AGENTS.md`, in each directory up to the repository root. Hooks run shell commands at `SessionStart` and `PreInvocation`, outside any tool permission. Find whether any flag or setting turns workspace hooks and plugins off, and whether a file written in one turn applies in the next turn and in the next session (rotation opens a new session in the same workspace). Record which roots `agy` writes to by itself (for example transcripts under `<workspace>/.gemini/`).
7. **Guards and hook behavior:** confirm whether settings `permissions` rules support deny entries (only `allow`, for example `command(git describe)`, is seen today), and whether a `PreToolUse` hook (`hooks.json`, decision `deny`) in the isolated config blocks a tool call before it runs. Measure a hook's latency when it starts a JVM (`loop tool guard`, see Guards), and record what a hook timeout (30 s default), a nonzero exit and invalid JSON on stdout do: the guard design requires each of them to deny.
8. **Process tree and updates:** record every process `agy acp` starts (session workers under `--process-per-session`, MCP servers started by its language server, the `remote-control` daemon) and whether each stays in the process group Loop kills. Find how to keep `agy` from updating itself during a run (a setting, an environment variable, or the `~/.gemini/antigravity-cli/updater` state).
9. **Cancel and errors:** capture `session/cancel` during an active turn and during a pending `session/request_permission`, plus structured errors for an ineligible or expired login, 429, quota, overload, model refusal, and network loss.
10. **MCP tool names and skills:** record the exact tool names `agy` exposes for `loop tool mcp` (`submit_result`, `move`, `comment`), and which skills and rules a fresh worktree loads (`AGENTS.md` is loaded natively).
11. **Sandbox:** record what `--sandbox` ("terminal restrictions") restricts, to decide whether `sandbox = true` can ever be declared.

**Print mode probes (second, if still building `runner-agy`):**

12. **Turns on one process:** prove several turns through `--input-format stream-json` on one process, then resuming the same conversation from a fresh process with `--conversation <id>`. Never use `--continue`, since parallel attempts collide on the most recent conversation.
13. **Config isolation (print mode):** top level `agy` has none of the isolation flags of probe 5; it has `--settings` (session-only settings) and the environment. Find whether either gives an attempt its own MCP servers and hooks without moving the login. If nothing isolates `~/.gemini/config/mcp_config.json`, `strict_mcp: true` is a preflight error for `runner-agy`.
14. **Approval mode and denials:** pick one `--mode` and confirm that, without `--dangerously-skip-permissions`, every tool Loop needs is allowed and every guarded tool is denied, never prompted (observed: print mode cannot prompt and auto-denies).
15. **Stats, interrupt and exit codes:** confirm the stream-json event shapes, whether usage is per request or cumulative, whether a stdin message can interrupt a turn, and the exit codes.

**Recordings:** save scrubbed JSONL transcripts in `plugins/runner-acp/src/test/resources/recorded/` and (if built) `plugins/runner-agy/src/test/resources/streams/`, scrubbed with a script modeled on `plugins/runner-codex-app-server/recording/scrub.py`.

### Auth, env policy and secret redaction

- **Leave `ALWAYS_REMOVED_ENV_NAMES` untouched** (`plugins/runner-process/src/main/kotlin/loop/runner/process/HostEnvironment.kt:9`). That global list wins even over `agent.env` and would affect the `command` runner.
- **Strip Google API keys by default:** pass `env.removedEnvNames + setOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_GENAI_USE_VERTEXAI")` to `agentEnvironment(...)` (following `ClaudeCodeRunner.kt:650` and `CodexAppServerRunner.kt:387`). This applies to `runner-agy` always and to `runner-acp` only under `agent: agy`; a generic ACP agent keeps the host's env policy unchanged. Both paths therefore run on the operator's Google login, matching the subscription login rule. Spike 0 records which variables `agy` actually reads, and the list follows it.
- **Opt in to API billing via `agent.env`:** because `agentEnvironment` applies `added` (`agent.env`) after `removed` (`HostEnvironment.kt:20-25`), setting a key under `agent.env` is the single opt in, with no extra config key.
- **Redaction:**
  - Add `GEMINI_API_KEY` and `GOOGLE_API_KEY` beside `ANTHROPIC_KEYS` in `cli/src/main/kotlin/loop/cli/Host.kt` (`Host.kt:592`, `617`) so host values are always redacted.
  - Add two prefix-anchored linear patterns to `TokenPattern.DEFAULTS` in `plugin-api/src/main/kotlin/loop/api/TokenPattern.kt`: `google-api-key` (`AIza[0-9A-Za-z_-]{35}`) and `google-oauth-token` (`ya29\.[0-9A-Za-z_-]{20,}`). Keys written into `agent.env` rely on these patterns. Google OAuth refresh tokens (`1//`) are skipped because the prefix is too generic.
  - Never copy, read or log the files under `~/.gemini/` that hold the login (`oauth_creds.json`, `google_accounts.json`, the app data directory).
- **The login stays in place:** the isolation of probe 5 or 13 must leave the login read from `~/.gemini/` itself. Moving the Gemini dir (`--gemini-dir`) moves the login with it, and a link into an attempt directory can be replaced by a file on token refresh, leaving a credential copy there; so `--app-data-dir` or `--cli_dir_name` is preferred, and `--gemini-dir` is used only if probe 5 shows the login stays in place. If two parallel sessions cannot share the login safely across a refresh, the `agy` preset and `runner-agy` refuse more than one concurrent session (a preflight error when `agent.max_concurrent_agents` is above 1).

### `runner-acp` preset, strict MCP and lifecycle

```yaml
runner:
  kind: acp
runners:
  acp:
    command: agy acp
    agent: agy
    model: gemini-4-neon-high
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.acp.command` | string | required | no | split into words without a shell, like `runners.codex.command`; under `agent: agy`, exactly the program and `acp` (see Preset validation) |
| `runners.acp.agent` | string | none | no | optional preset; `agy` is the only supported value, any other value fails `validate` |
| `runners.acp.model` | string | none | no | only with `agent: agy`; one of the ids `agy models` lists |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

- **Launch and framing:** `ProcessGroupLauncher` with no shell, workspace as `cwd`, `BoundedLineReader` with `ProcessGroupLauncher.MAX_LINE_BYTES` (10 MB line cap), JSON-RPC 2.0 over stdio.
- **Preset:** when `agent: agy` is set, the runner:
  - appends every flag itself, after `agy acp`: the isolation flags of probe 5 (preferring `--app-data-dir` or `--cli_dir_name`, see Auth), and any flag Spike 0 adds to the preset's own list (for example `--disable-telemetry`). Never `--dangerously-skip-permissions`, `--effort`, `--remote-agent` or `--remote-control`, and never `--process-per-session` unless probe 8 shows its workers stay in Loop's process group;
  - writes the isolated config in the attempt directory: settings with the deny rules of probe 7, the `PreToolUse` hook that calls `loop tool guard` (see Workspace customizations and the hook guard), an MCP config holding only Loop's server under `strict_mcp: true`, and no operator plugins or skills unless Spike 0 shows they cannot reach outside the workspace;
  - pins `runners.acp.model` through the mechanism of probe 2, then checks the model the session reports. A session that reports another model, or `auto`, fails the turn as an ordinary failure and is stopped; it never runs on an unpinned model;
  - keeps `agy` from updating itself during the run (the mechanism of probe 8), and records the `agy` version in the attempt's events.
- **Preset validation:** `RunnerProvider.validate` runs on every load of `WORKFLOW.md`, so these fail preflight with `invalid_config` through `RunnerConfigException`, naming the key and never a value:
  - `runners.acp.agent` set to anything other than `agy`.
  - `runners.acp.model` set without `agent: agy`.
  - `runners.acp.strict_mcp: true` with no `agent` preset (decidable from config alone, so it never waits for session start).
  - `agent: agy` while `command` is not exactly two words: a program whose file name is `agy`, then `acp`. This is an allowlist: any other word, a flag in either `--flag value` or `--flag=value` form included, fails, so no operator flag can weaken a guard or move the login. Settings the operator may change are config keys (`model`, `strict_mcp`, the timeouts), never words of `command`.
  - `agent: agy` without `runners.acp.model`.
- **`strict_mcp` behavior:** with `strict_mcp: true` (the default) and `agent: agy`, the isolated config holds only Loop's `loop` server. With no `agent` preset (generic ACP agent), Loop cannot prevent the agent from loading its own MCP servers, so `strict_mcp: true` is the preflight error above (matching the Codex precedent in `docs/spec-decisions.md#strict-mcp-extension-bl-72`). Operators using an unpreset ACP agent must set `strict_mcp: false`.
- **Startup sequence:**
  1. `initialize` with Loop's client info, pinned ACP protocol version (confirmed in Spike 0), and client capabilities (including client `fs` if Spike 0 confirms it routes reads through Loop).
  2. `authenticate` when `initialize` requires an auth method.
  3. `session/new` with `cwd` (workspace path) and `mcpServers` containing Loop's MCP server (`<LOOP_LAUNCHER> tool mcp`, server name `loop`) with all 8 env vars of `ClaudeMcpConfig.PASSED` (`ClaudeMcpConfig.kt:24-34`): `LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`. Because the process lives across turns, `LOOP_SESSION_ID` and `LOOP_TURN_ID` in the MCP server env stay fixed at the values of the turn that opened the session, matching the Claude Code precedent (`docs/spec-decisions.md#session-and-turn-ids-for-the-claude-code-runner-clarification-of-42-m1-05`).
- **Turns, interrupt, resume and stop:**
  - Turn 1 emits `RunnerEvent.SessionStarted` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n send `session/prompt` on the same process and emit `RunnerEvent.TurnStarted`.
  - `AgentSession.interrupt()` sends `session/cancel` and answers any pending `session/request_permission` with `cancelled`, so the turn ends cleanly and reports its usage before rotation.
  - `resumeSession(ctx, runtimeSessionId)` uses ACP `session/load` if Spike 0 confirms support, else returns `null` (falling back to a fresh session with the session brief).
  - Every process `agy acp` starts (session workers, MCP servers, helpers) must stay in the process group Loop kills; a process probe 8 finds outside it is a blocker for the preset, never left running.
  - `stop()` and `stop(report)` close stdin and wait up to 5 seconds for exit. When the grace expires, the runner kills the process group, reports any usage read during the grace as a `RunnerEvent.Usage`, then emits `RunnerEvent.StopGraceExpired` (`m4-04`) after that last `Usage`. What the agent had not reported yet is lost.
- **ACP guards (guardrail, not a sandbox):**
  - `session/request_permission` checks commands and paths using the shared `plugins/runner-process` matchers (including the attempt's `LOOP_LAUNCHER` path for `loop approve` and `loop resume`, plus `holdoutDir` absolute and real paths).
  - **Fail closed:** any shell or write permission request with no readable command text or path is declined (matching `NO_COMMAND` in `plugins/runner-codex-app-server/.../ServerRequestPolicy.kt`).
  - Unsupported human-input prompts end the turn at once with `RunnerEvent.TurnInputRequired` and stop the session.
  - With `agent: agy`, the generated deny rules and the `PreToolUse` hook add a second guardrail covering tools that run without asking (file reads, search, list), and the workspace customization rules below apply. Without the preset, unprompted reads rely on client `fs` routing when supported and remain a documented guardrail limit.

### Workspace customizations and the hook guard

Both plugins, `runner-acp` under `agent: agy` and `runner-agy`, apply these rules, because `agy` loads workspace customizations with the highest priority and an isolated global config does not stop that:

- **Protected roots:** `.agents/`, `.agent/`, `_agents/` and `_agent/` in the workspace and every directory up to its root. The shared matcher in `plugins/runner-process` denies every write, edit or shell command that targets a protected root, by absolute and real path, through the permission callback (ACP), the deny rules and the hook.
- **Check before every session:** before a session opens (first turn, rotation, resume), the runner compares the protected roots with the attempt's base commit (`git diff --name-only <base> -- <roots>` plus untracked files there). A hooks file, a plugin folder, an `mcp_config.json`, a `skills.json` or a rule file that the base commit does not hold refuses the session: the turn fails as an ordinary failure with a reason naming the file, and the session never starts on it.
- **Switch when it exists:** if probe 6 finds a flag or setting that turns workspace hooks and plugins off, the runner also passes it; the two rules above stay.
- **Stop condition:** if probe 6 shows `agy` runs workspace hooks or plugins that neither a switch nor the check before every session can stop (for example a file written in one turn that applies in the next turn of the same session), the preset and `runner-agy` are not built until it is solved.
- **Files `agy` writes:** roots `agy` writes on its own (probe 6, for example transcripts under `<workspace>/.gemini/`) are moved to the attempt directory by the isolation flags or excluded from delivery, so the pull request never carries them.

**The hook guard:** the `PreToolUse` hook is one command, `<LOOP_LAUNCHER> tool guard`, a new `loop tool` subcommand. It reads the hook's JSON on stdin, decides with the same Kotlin matchers as the permission callback (denied commands, `holdoutDir`, protected roots), and prints `deny` or `allow`. One matcher, never a generated script that could drift from it.

- **Fail closed:** the guard prints `deny` on any input it cannot parse. Probe 7 must show that a hook timeout, a nonzero exit and invalid output each deny the tool call; if any of them lets the call run, the hook is not a guard, and the preset relies on deny rules and the permission callback alone, with the gap documented.
- **Latency:** a JVM starts on every tool call. Probe 7 measures it; if it is too slow for normal work, the guard runs as a small daemon the runner starts and stops with the session, with the same fail closed rules.

### `runner-agy` (print mode, conditional on Spike 0)

Built only if Spike 0 gives a clear reason after evaluating `runner-acp`. Unless Spike 0 shows per-request occupancy in stream-json, its plugin doc states that it declares `occupancy = false` and **cannot enforce Loop's context limits** (Loop logs a warning and enforces only the prompt limit and `agent.max_turns`, `RunCommand.kt:264`).

```yaml
runner:
  kind: agy
runners:
  agy:
    command: agy
    model: gemini-4-neon-high
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
    sandbox: false
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.agy.command` | string | required | no | exactly one word, the path or name of the `agy` program; any other word fails `validate` (the runner adds every flag itself) |
| `runners.agy.model` | string | required | no | one of the ids `agy models` lists |
| `runners.agy.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.agy.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.agy.strict_mcp` | boolean | `true` | no | `true` is a preflight error unless probe 13 finds a way to isolate the MCP config |
| `runners.agy.sandbox` | boolean | `false` | no | `true` only if Spike 0 probe 11 shows `--sandbox` is an OS sandbox |

- **Launch:** `agy -p --output-format stream-json --input-format stream-json --model <model> --mode <mode>`, the mode picked by probe 14, the isolation of probe 13 (top level `agy` has no `--gemini-dir`, `--app-data-dir` or `--include-default-customizations`; only `--settings` and the environment), and `--sandbox` when `sandbox: true`. Never `--dangerously-skip-permissions`, `--continue`, `--effort`, `--remote-agent`, `--remote-control` or `--add-dir`.
- **No isolation, no strict MCP:** if probe 13 finds nothing that keeps `~/.gemini/config/mcp_config.json` and global hooks out of an attempt, `runners.agy.strict_mcp: true` fails preflight with `invalid_config`, and the plugin doc says `runner-agy` runs with the operator's global MCP servers. Building `runner-agy` in that case needs a fresh decision.
- **Continuation:** one process per session; each turn writes one NDJSON message on stdin. Turn 1 records the conversation id from the stream as `runtimeSessionId`; `resumeSession` starts a fresh process with `--conversation <runtimeSessionId>`.
- **Guards and workspace isolation:** the isolated config of probe 13 holds the deny rules of probe 7 and the `loop tool guard` hook, and the workspace customization rules apply. They cover the shared denied command list (plus `LOOP_LAUNCHER` for `approve` and `resume`), `holdoutDir` (absolute and real paths) across every file, search and command tool, plus non-Loop MCP servers under `strict_mcp: true`. Documented guardrail limits (not a sandbox): relative paths, symlinks created inside the workspace, and shell indirections (`sh -c`, scripts, `git -C . push`) that bypass simple prefix rules.
- **Capabilities:** `continuation = true`, `loopTools = Mcp`, `usage = true`, `cost = false` (unless Spike 0 confirms USD cost), `occupancy = false` (unless Spike 0 confirms per-request input tokens), `sandbox = settings.sandbox`.

### Shared guards in `plugins/runner-process`

Move the canonical denied command list and hardened matchers (`DeniedCommands` and `HoldoutFolder` from `plugins/runner-codex-app-server/.../Guards.kt`) into `plugins/runner-process` as shared utilities used by `runner-claude-code`, `runner-codex-app-server`, `runner-acp`, and `runner-agy`. This replaces `DeniedCommandsTest`'s source-file scraping (`DeniedCommandsTest.kt:103-119`) and keeps all runners in sync, including denying `loop approve` and `loop resume` via both bare `loop` and the attempt's `LOOP_LAUNCHER` path.

### Error mapping

Aligned with `RunnerEvent.kt` (`bl-39`, `bl-141`) and `docs/spec-decisions.md#network-loss-extension-m4-08`:

- **`RunnerEvent.TurnFailed(category = RunnerUnavailable)`** is set **only** when the runtime's own structured error result reports unavailability: authentication, eligibility or access failure (401, 403, an expired login, `IneligibleTierError`), rate limit, quota or overload (429), model refusal (`modelNotFound`, `bl-141`), or a runtime-reported network transport failure with no HTTP response (`m4-08`).
- **Ordinary failure (`category = null`)** for everything else: process crashes, non-zero exits without a structured unavailability error, malformed JSON, and any error text printed by the agent or a tool output. Exit codes are mapped from Spike 0 probe 13.

### Merge risk reviewer

Keep the existing Claude reviewer (`Host.kt:403-413`, `m4-06`). `docs/PLAN.md` asks for a reviewer model different from the worker, and `riskReviewerCommand` already falls back to `runners.claude-code.command` or `claude` for non-Claude workers. Gemini runs under `policy: auto` with merge still require Claude Code installed (`riskReviewerWarning`). A Gemini-backed `RiskReviewer` is filed as a separate `bl-<nn>` (`needs-triage`) backlog issue.

### Core touchpoints

1. **Gradle wiring:** add `:plugins:runner-acp` (and `:plugins:runner-agy` if built) to `settings.gradle.kts` and `cli/build.gradle.kts`.
2. **Shared guards:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process` and update `runner-claude-code` and `runner-codex-app-server` to use them.
3. **Secrets and redaction:** add `google-api-key` (`AIza...`) and `google-oauth-token` (`ya29....`) to `TokenPattern.DEFAULTS` (`TokenPattern.kt`), and redact `GEMINI_API_KEY` and `GOOGLE_API_KEY` in `Host.kt`.
4. **Context windows:** add the default `google/<model>` windows from Spike 0 (for example `google/gemini-4-neon-high`) to `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`.
5. **Real integration targets:** add `"agy"` (and `"acp"` if gated separately) to `RealIntegration.TARGETS` in `plugin-api/src/testFixtures/kotlin/loop/api/RealIntegration.kt:27`.
6. **Startup preflight:** add an `agy` preflight check (binary reachable, version on the major Spike 0 pinned, login state warnings, `agy models` lists the configured model) in `cli/src/main/kotlin/loop/cli/Host.kt` (`runnerPreflight`, line 653).
7. **`loop tool guard`:** a new `loop tool` subcommand that reads a `PreToolUse` hook payload on stdin and prints the decision from the shared matchers, failing closed.
8. **Loop repo files:** no `GEMINI.md` is needed, since `agy` loads `AGENTS.md` natively. Update `WORKFLOW.md:153` once Spike 0 records the exact MCP tool names for `move`, `comment`, and `submit_result` (today line 153 names `mcp__loop__move` with a `loop tool move` fallback).
9. **Docs:** add `docs/plugins/runner-acp.md` and (if built) `docs/plugins/runner-agy.md`, each stating clearly whether the runner enforces context limits, and record in `docs/PLAN.md` the M7 milestone jump and that the `gemini` plugin row is served by `agy`.

## Rejected

- **Targeting Gemini CLI (`gemini`).** It fails on the operator's personal Google login with `IneligibleTierError` since 2026-11, and Google points individuals to Antigravity.
- **One runner plugin with a `mode: print | acp` switch.** The two transports have different process lifecycles, interrupt semantics, guard mechanisms, and `RunnerCapabilities`. Two plugins match `docs/PLAN.md:647` and avoid duplicating ACP in M7.
- **Pure generic `runner-acp` with no `agent` preset.** A static `runners.acp.command` cannot pass per-attempt isolated config, deny rules or model selection, leaving holdout reads unguarded and `strict_mcp` unenforceable on `agy`.
- **An `{attempt_dir}` placeholder in `runners.acp.command`.** Pushes internal config paths and flag syntax onto every operator's `WORKFLOW.md` instead of encapsulating them in a validated preset (`runners.acp.agent: agy`).
- **Naming the print mode plugin `gemini`.** The program is `agy`, and it serves Claude and GPT-OSS models as well as Gemini; the kind follows the program, as `codex` does.
- **Adding `GEMINI_API_KEY` and `GOOGLE_API_KEY` to `ALWAYS_REMOVED_ENV_NAMES`.** That global list wins over `agent.env` and would also strip those keys from the `command` runner.
- **Aliasing `runner.kind: agy` to `acp` if the print mode runner is dropped.** Hides the transport; leave `agy` unregistered instead.
- **`--dangerously-skip-permissions`, in either plugin.** It approves every tool call, so Loop's guards would never see one.
- **`--continue` in print mode.** Parallel attempts on the same repository would collide on the most recent conversation.
- **Writing guard rules into the workspace `.agents/` folder.** The agent can edit it, and a rule changed during turn `n` could apply in turn `n+1`.
- **A generated guard script instead of `loop tool guard`.** It would duplicate the Kotlin matchers in another language and drift from them.
- **A blacklist of forbidden `agy` flags in `command`.** `agy` keeps adding flags (`--cli_dir_name`, `--cloud_code_endpoint`, `--login`, `--flag=value` forms); an allowlist (the program and `acp` only) cannot fall behind.

## Issues

Each slice is scoped to land independently after Spike 0 settles the facts:

1. **Spike 0 (operator spike):** probe `agy` (1.5.16 today) with `agy acp` first, then print mode; settle the three stop conditions (model pinning, occupancy, workspace customizations); pin the version major, model ids and windows in `docs/spec-decisions.md`; save scrubbed recordings; confirm whether to build `runner-agy` after `runner-acp`.
2. **Shared guards move:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process`, add the protected roots matcher, switch `runner-claude-code` and `runner-codex-app-server` to them, and replace `DeniedCommandsTest`'s source scraping. A refactor of two shipped runners with no Gemini code, so it can land before Spike 0 ends.
3. **Core prep:** add `AIza...` and `ya29....` to `TokenPattern.DEFAULTS`, `GEMINI_API_KEY` / `GOOGLE_API_KEY` redaction to `Host.kt`, the default `google/<model>` windows from Spike 0 to `WorkflowConfig.kt`, and `"agy"` to `RealIntegration.TARGETS`.
4. **`runner-acp` scaffold, handshake and turns:** create `plugins/runner-acp`, `RunnerProvider`, `PluginProfile`, JSON-RPC 2.0 framing (`ProcessGroupLauncher.MAX_LINE_BYTES`), `initialize` / `authenticate` / `session/new` / `session/prompt`, `read_timeout_ms` and `turn_timeout_ms`, `stop(report)` with `StopGraceExpired`, fake ACP server, `docs/plugins/runner-acp.md`.
5. **`runner-acp` MCP server, preset and `strict_mcp`:** pass `loop tool mcp` with all 8 `ClaudeMcpConfig.PASSED` env vars in `session/new` `mcpServers`; implement the `runners.acp.agent: agy` preset (isolation flags, isolated config, model selection) and its preset validation in `validate`, including the `strict_mcp: true` with no preset preflight error.
6. **`runner-acp` permission guards and holdout protection:** implement fail-closed `session/request_permission` policy using shared `runner-process` matchers (including `LOOP_LAUNCHER`, `holdoutDir` and the protected roots), client `fs` read handling if supported, `TurnInputRequired`, `loop tool guard` with its `PreToolUse` hook and generated deny rules for `agent: agy`, and the check of protected roots before every session.
7. **`runner-acp` usage, occupancy, interrupt and contract:** map ACP usage and `ContextUsage` (`google/<model>`), wire `interrupt()` to `session/cancel` plus `cancelled` permission replies, replay recorded sessions, pass `AgentRunnerContract` and rotation tests.
8. **`runner-acp` error mapping and `agy` preflight:** map `RunnerUnavailable` per `RunnerEvent.kt` and `m4-08` from recorded error responses, and add the `agy` preflight check in `Host.kt` (`runnerPreflight`).
9. **`runner-acp` real integration and exit run:** add `LOOP_REAL_INTEGRATION=agy` smoke test and deliver a real Loop issue on `runner.kind: acp` (`command: agy acp`, `agent: agy`, `model: gemini-4-neon-high`) with peak occupancy under 25 percent.
10. **`runner-agy` scaffold, turns and resume *(conditional on Spike 0)*:** create `plugins/runner-agy`, `RunnerProvider`, `PluginProfile` (`command` and `model` required), stream-json reader and writer, turns on one process, `--conversation` resume, `docs/plugins/runner-agy.md` stating whether it enforces context limits.
11. **`runner-agy` MCP, `strict_mcp` and guards *(conditional on Spike 0)*:** generated MCP config with `loop tool mcp`, the print mode isolation of probe 13 (or the `strict_mcp: true` preflight error), the deny rules and `loop tool guard` hook covering shared denied commands, `holdoutDir` and the protected roots under the chosen `--mode`, and the check of protected roots before every session.
12. **`runner-agy` usage, errors, contract and real integration *(conditional on Spike 0)*:** absolute `Usage`, `RunnerUnavailable` mapping, recorded stream replay tests, `AgentRunnerContract`, and `LOOP_REAL_INTEGRATION=agy` smoke test.
13. **Backlog (`needs-triage`):** file `bl-<nn>` for a Gemini-backed `RiskReviewer`.

## Out of scope

- **Skill portability and target-project setup:** sharing `SKILL.md` skills with `agy` through `~/.gemini/config/skills.json` or `.agents/skills/`, global rules in `~/.gemini/config/GEMINI.md`, and adapting Claude-specific skills (slash commands, hardcoded tool names, `Agent` subagents). Documented separately in `docs/gemini-skills.md`.
- **A Gemini-backed `RiskReviewer`:** tracked as a separate backlog issue; Claude Code stays the merge risk reviewer under `policy: auto`.
- **OS sandboxing guarantees (M5):** deny rules, hooks and `session/request_permission` are guardrails, not a sandbox. Full worker sandboxing remains in M5.
- **Additional ACP agent presets** beyond `agy` (for example Goose or OpenCode).
- **`agy --remote-agent` and remote projects.** Loop runs the agent on the local machine.
- **Per-kind runner selection** (`docs/PLAN.md` section 6.2, gap after M4).
