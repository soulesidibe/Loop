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
| `strict_mcp` | Enforced by the preset's isolated config (Spike 0) | `strict_mcp: true` is a preflight error (cannot isolate an unknown agent's MCP config) | Enforced by the isolated config (Spike 0) |
| Guards and holdout folder | Kotlin `session/request_permission` (fail closed) plus generated deny rules or a `PreToolUse` hook (guardrail, not a sandbox) | Kotlin `session/request_permission` (fail closed) plus client `fs` reads if supported; weaker on unprompted reads (guardrail, not a sandbox) | Generated deny rules or a `PreToolUse` hook (guardrail, not a sandbox) |

## Decisions

### Spike 0 gates both plugins (ACP probes first)

Before writing runner code, an operator spike probes `agy` 1.5.14 and pins the version in `docs/spec-decisions.md` (following the Codex precedent in `docs/spec-decisions.md#codex-cli-app-server-schema-and-mcp-loading-m3-02`). Run the ACP probes first so work can stop early if neither path can enforce context occupancy. Probes run with `gemini-4-neon-high`.

**ACP probes (first):**

1. **Handshake, auth and protocol version:** record `initialize` (protocol version, auth methods, agent capabilities), whether `authenticate` is required under the Google login (`agy acp --login` exists for terminal auth), and whether `session/load` works for `resumeSession`.
2. **Model selection:** find how an ACP client picks the model for `agy acp` (an ACP session config or mode option, a settings file, an environment variable), and record the exact model ids and their context windows.
3. **Occupancy and usage:** confirm whether ACP updates emit per-request input tokens (main session, cached included, not cumulative) for `RunnerEvent.ContextUsage`, absolute totals for `RunnerEvent.Usage`, and USD cost. If neither ACP nor print mode exposes per-request occupancy, stop and reconsider this spec.
4. **Permission triggers and client `fs`:** list every built-in tool (file read, search, list, write, edit, `run_command`, browser, MCP) and record which ones fire `session/request_permission` when `--dangerously-skip-permissions` is not passed, what fields their payloads carry (`rawInput`, `locations`), and whether advertising ACP client `fs` capabilities routes file reads through Loop.
5. **Config isolation:** `agy` reads settings from `~/.gemini/antigravity-cli/settings.json`, global customizations (rules, skills, `skills.json`, `mcp_config.json`, plugins, hooks) from `~/.gemini/config/`, and workspace customizations from `.agents/`, `GEMINI.md` and `AGENTS.md`, walking up to the repository root. Find which of `--gemini-dir`, `--app-data-dir`, `--include-default-customizations=false` or a generated settings file gives one attempt its own settings, MCP servers and hooks without losing the operator's Google login, and record what each flag changes.
6. **Guards:** confirm whether settings `permissions` rules support deny entries (only `allow`, for example `command(git describe)`, is seen today), and whether a `PreToolUse` hook (`hooks.json`, decision `deny`) placed in the isolated config blocks a tool call before it runs, under ACP and print mode.
7. **Cancel and errors:** capture `session/cancel` during an active turn and during a pending `session/request_permission`, plus structured errors for an ineligible or expired login, 429, quota, overload, model refusal, and network loss.
8. **MCP tool names and skills:** record the exact tool names `agy` exposes for `loop tool mcp` (`submit_result`, `move`, `comment`), and confirm which skills and rules a fresh worktree loads (`AGENTS.md` is loaded natively).
9. **Sandbox:** record what `--sandbox` ("terminal restrictions") restricts, to decide whether `sandbox = true` can ever be declared.

**Print mode probes (second, if still building `runner-agy`):**

10. **Turns on one process:** prove several turns through `--input-format stream-json` on one process, then resuming the same conversation from a fresh process with `--conversation <id>`. Never use `--continue`, since parallel attempts collide on the most recent conversation.
11. **Workspace config reload:** verify whether `.agents/` rules, skills, hooks or settings written during turn `n` apply in turn `n+1`, and that the isolated config of probe 5 prevents it.
12. **Approval mode and denials:** pick one `--mode` and confirm that, without `--dangerously-skip-permissions`, every tool Loop needs is allowed and every guarded tool is denied, never prompted (print mode cannot prompt and auto-denies).
13. **Stats, interrupt and exit codes:** confirm the stream-json event shapes, whether usage is per request or cumulative, whether a stdin message can interrupt a turn, and the exit codes.

**Recordings:** save scrubbed JSONL transcripts in `plugins/runner-acp/src/test/resources/recorded/` and (if built) `plugins/runner-agy/src/test/resources/streams/`, scrubbed with a script modeled on `plugins/runner-codex-app-server/recording/scrub.py`.

### Auth, env policy and secret redaction

- **Leave `ALWAYS_REMOVED_ENV_NAMES` untouched** (`plugins/runner-process/src/main/kotlin/loop/runner/process/HostEnvironment.kt:9`). That global list wins even over `agent.env` and would affect the `command` runner.
- **Strip Google API keys by default:** pass `env.removedEnvNames + setOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_GENAI_USE_VERTEXAI")` to `agentEnvironment(...)` (following `ClaudeCodeRunner.kt:650` and `CodexAppServerRunner.kt:387`). This applies to `runner-agy` always and to `runner-acp` only under `agent: agy`; a generic ACP agent keeps the host's env policy unchanged. Both paths therefore run on the operator's Google login, matching the subscription login rule. Spike 0 records which variables `agy` actually reads, and the list follows it.
- **Opt in to API billing via `agent.env`:** because `agentEnvironment` applies `added` (`agent.env`) after `removed` (`HostEnvironment.kt:20-25`), setting a key under `agent.env` is the single opt in, with no extra config key.
- **Redaction:**
  - Add `GEMINI_API_KEY` and `GOOGLE_API_KEY` beside `ANTHROPIC_KEYS` in `cli/src/main/kotlin/loop/cli/Host.kt` (`Host.kt:592`, `617`) so host values are always redacted.
  - Add two prefix-anchored linear patterns to `TokenPattern.DEFAULTS` in `plugin-api/src/main/kotlin/loop/api/TokenPattern.kt`: `google-api-key` (`AIza[0-9A-Za-z_-]{35}`) and `google-oauth-token` (`ya29\.[0-9A-Za-z_-]{20,}`). Keys written into `agent.env` rely on these patterns. Google OAuth refresh tokens (`1//`) are skipped because the prefix is too generic.
  - Never copy, read or log the files under `~/.gemini/` that hold the login (`oauth_creds.json`, `google_accounts.json`, the app data directory). An isolated config dir (probe 5) points at them or shares them, never duplicates them into the attempt directory.

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
| `runners.acp.command` | string | required | no | split into words without a shell, like `runners.codex.command` |
| `runners.acp.agent` | string | none | no | optional preset; `agy` is the only supported value, any other value fails `validate` |
| `runners.acp.model` | string | none | no | only with `agent: agy`; one of the ids `agy models` lists |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

- **Launch and framing:** `ProcessGroupLauncher` with no shell, workspace as `cwd`, `BoundedLineReader` with `ProcessGroupLauncher.MAX_LINE_BYTES` (10 MB line cap), JSON-RPC 2.0 over stdio.
- **Preset:** when `agent: agy` is set, the runner:
  - appends its per-attempt flags after the words of `command`: the isolation flags of probe 5 (for example `--gemini-dir <attempt dir>`), never `--dangerously-skip-permissions`;
  - writes the isolated config in the attempt directory: settings with the deny rules or `PreToolUse` hook of probe 6, an MCP config holding only Loop's server under `strict_mcp: true`, and no operator plugins or skills unless Spike 0 shows they cannot reach outside the workspace;
  - selects `runners.acp.model` through the mechanism of probe 2.
- **Preset validation:** `RunnerProvider.validate` runs on every load of `WORKFLOW.md`, so these fail preflight with `invalid_config` through `RunnerConfigException`, naming the key and never a value:
  - `runners.acp.agent` set to anything other than `agy`.
  - `runners.acp.model` set without `agent: agy`.
  - `runners.acp.strict_mcp: true` with no `agent` preset (decidable from config alone, so it never waits for session start).
  - `agent: agy` while the program in `command` (its first word, by file name) is not `agy`, or its second word is not `acp`.
  - `agent: agy` while `command` already carries a flag the preset owns or that could weaken a guard: `--dangerously-skip-permissions`, `--gemini-dir`, `--gemini_dir`, `--app-data-dir`, `--app_data_dir`, `--include-default-customizations`, `--add-dir`, `--agent`, `--sandbox`. Other words pass through untouched.
- **`strict_mcp` behavior:** with `strict_mcp: true` (the default) and `agent: agy`, the isolated config holds only Loop's `loop` server. With no `agent` preset (generic ACP agent), Loop cannot prevent the agent from loading its own MCP servers, so `strict_mcp: true` is the preflight error above (matching the Codex precedent in `docs/spec-decisions.md#strict-mcp-extension-bl-72`). Operators using an unpreset ACP agent must set `strict_mcp: false`.
- **Startup sequence:**
  1. `initialize` with Loop's client info, pinned ACP protocol version (confirmed in Spike 0), and client capabilities (including client `fs` if Spike 0 confirms it routes reads through Loop).
  2. `authenticate` when `initialize` requires an auth method.
  3. `session/new` with `cwd` (workspace path) and `mcpServers` containing Loop's MCP server (`<LOOP_LAUNCHER> tool mcp`, server name `loop`) with all 8 env vars of `ClaudeMcpConfig.PASSED` (`ClaudeMcpConfig.kt:24-34`): `LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`. Because the process lives across turns, `LOOP_SESSION_ID` and `LOOP_TURN_ID` in the MCP server env stay fixed at the values of the turn that opened the session, matching the Claude Code precedent (`docs/spec-decisions.md#session-and-turn-ids-for-the-claude-code-runner-clarification-of-42-m1-05`).
- **Turns, interrupt, resume and stop:**
  - Turn 1 emits `RunnerEvent.SessionStarted` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n send `session/prompt` on the same process and emit `RunnerEvent.TurnStarted`.
  - `AgentSession.interrupt()` sends `session/cancel` and answers any pending `session/request_permission` with `cancelled`, so the turn ends cleanly and reports its usage before rotation.
  - `resumeSession(ctx, runtimeSessionId)` uses ACP `session/load` if Spike 0 confirms support, else returns `null` (falling back to a fresh session with the session brief).
  - `stop()` and `stop(report)` close stdin and wait up to 5 seconds for exit. When the grace expires, the runner kills the process group, reports any usage read during the grace as a `RunnerEvent.Usage`, then emits `RunnerEvent.StopGraceExpired` (`m4-04`) after that last `Usage`. What the agent had not reported yet is lost.
- **ACP guards (guardrail, not a sandbox):**
  - `session/request_permission` checks commands and paths using the shared `plugins/runner-process` matchers (including the attempt's `LOOP_LAUNCHER` path for `loop approve` and `loop resume`, plus `holdoutDir` absolute and real paths).
  - **Fail closed:** any shell or write permission request with no readable command text or path is declined (matching `NO_COMMAND` in `plugins/runner-codex-app-server/.../ServerRequestPolicy.kt`).
  - Unsupported human-input prompts end the turn at once with `RunnerEvent.TurnInputRequired` and stop the session.
  - With `agent: agy`, the generated deny rules or `PreToolUse` hook add a second guardrail covering tools that run without asking (file reads, search, list) and workspace `.agents/` overrides. Without the preset, unprompted reads rely on client `fs` routing when supported and remain a documented guardrail limit.

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
| `runners.agy.command` | string | required | no | no shell metacharacters, run without a shell (matches `runners.claude-code.command`) |
| `runners.agy.model` | string | required | no | one of the ids `agy models` lists |
| `runners.agy.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.agy.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.agy.strict_mcp` | boolean | `true` | no | none |
| `runners.agy.sandbox` | boolean | `false` | no | `true` only if Spike 0 probe 9 shows `--sandbox` is an OS sandbox |

- **Launch:** `agy -p --output-format stream-json --input-format stream-json --model <model> --mode <mode>`, the mode picked by probe 12, the isolation flags of probe 5, and `--sandbox` when `sandbox: true`. Never `--dangerously-skip-permissions`, never `--continue`.
- **Continuation:** one process per session; each turn writes one NDJSON message on stdin. Turn 1 records the conversation id from the stream as `runtimeSessionId`; `resumeSession` starts a fresh process with `--conversation <runtimeSessionId>`.
- **Guards and workspace isolation:** the isolated config of probe 5 holds the deny rules or `PreToolUse` hook of probe 6, which cover the shared denied command list (plus `LOOP_LAUNCHER` for `approve` and `resume`), `holdoutDir` (absolute and real paths) across every file, search and command tool, plus non-Loop MCP servers under `strict_mcp: true`. Documented guardrail limits (not a sandbox): relative paths, symlinks created inside the workspace, and shell indirections (`sh -c`, scripts, `git -C . push`) that bypass simple prefix rules.
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
6. **Startup preflight:** add an `agy` preflight check (binary reachable, login state warnings, `agy models` lists the configured model) in `cli/src/main/kotlin/loop/cli/Host.kt` (`runnerPreflight`, line 653).
7. **Loop repo files:** no `GEMINI.md` is needed, since `agy` loads `AGENTS.md` natively. Update `WORKFLOW.md:153` once Spike 0 records the exact MCP tool names for `move`, `comment`, and `submit_result` (today line 153 names `mcp__loop__move` with a `loop tool move` fallback).
8. **Docs:** add `docs/plugins/runner-acp.md` and (if built) `docs/plugins/runner-agy.md`, each stating clearly whether the runner enforces context limits, and record in `docs/PLAN.md` the M7 milestone jump and that the `gemini` plugin row is served by `agy`.

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

## Issues

Each slice is scoped to land independently after Spike 0 settles the facts:

1. **Spike 0 (operator spike):** probe `agy` 1.5.14 with `agy acp` first, then print mode; pin the version, model ids and windows in `docs/spec-decisions.md`; save scrubbed recordings; confirm whether to build `runner-agy` after `runner-acp`.
2. **Shared guards move:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process`, switch `runner-claude-code` and `runner-codex-app-server` to them, and replace `DeniedCommandsTest`'s source scraping. A refactor of two shipped runners with no Gemini code, so it can land before Spike 0 ends.
3. **Core prep:** add `AIza...` and `ya29....` to `TokenPattern.DEFAULTS`, `GEMINI_API_KEY` / `GOOGLE_API_KEY` redaction to `Host.kt`, the default `google/<model>` windows from Spike 0 to `WorkflowConfig.kt`, and `"agy"` to `RealIntegration.TARGETS`.
4. **`runner-acp` scaffold, handshake and turns:** create `plugins/runner-acp`, `RunnerProvider`, `PluginProfile`, JSON-RPC 2.0 framing (`ProcessGroupLauncher.MAX_LINE_BYTES`), `initialize` / `authenticate` / `session/new` / `session/prompt`, `read_timeout_ms` and `turn_timeout_ms`, `stop(report)` with `StopGraceExpired`, fake ACP server, `docs/plugins/runner-acp.md`.
5. **`runner-acp` MCP server, preset and `strict_mcp`:** pass `loop tool mcp` with all 8 `ClaudeMcpConfig.PASSED` env vars in `session/new` `mcpServers`; implement the `runners.acp.agent: agy` preset (isolation flags, isolated config, model selection) and its preset validation in `validate`, including the `strict_mcp: true` with no preset preflight error.
6. **`runner-acp` permission guards and holdout protection:** implement fail-closed `session/request_permission` policy using shared `runner-process` matchers (including `LOOP_LAUNCHER` and `holdoutDir`), client `fs` read handling if supported, `TurnInputRequired`, and the generated deny rules or `PreToolUse` hook for `agent: agy`.
7. **`runner-acp` usage, occupancy, interrupt and contract:** map ACP usage and `ContextUsage` (`google/<model>`), wire `interrupt()` to `session/cancel` plus `cancelled` permission replies, replay recorded sessions, pass `AgentRunnerContract` and rotation tests.
8. **`runner-acp` error mapping and `agy` preflight:** map `RunnerUnavailable` per `RunnerEvent.kt` and `m4-08` from recorded error responses, and add the `agy` preflight check in `Host.kt` (`runnerPreflight`).
9. **`runner-acp` real integration and exit run:** add `LOOP_REAL_INTEGRATION=agy` smoke test and deliver a real Loop issue on `runner.kind: acp` (`command: agy acp`, `agent: agy`, `model: gemini-4-neon-high`) with peak occupancy under 25 percent.
10. **`runner-agy` scaffold, turns and resume *(conditional on Spike 0)*:** create `plugins/runner-agy`, `RunnerProvider`, `PluginProfile` (`command` and `model` required), stream-json reader and writer, turns on one process, `--conversation` resume, `docs/plugins/runner-agy.md` stating whether it enforces context limits.
11. **`runner-agy` MCP, `strict_mcp` and guards *(conditional on Spike 0)*:** generated MCP config with `loop tool mcp`, the isolated config, and the deny rules or `PreToolUse` hook covering shared denied commands and `holdoutDir` under the chosen `--mode`.
12. **`runner-agy` usage, errors, contract and real integration *(conditional on Spike 0)*:** absolute `Usage`, `RunnerUnavailable` mapping, recorded stream replay tests, `AgentRunnerContract`, and `LOOP_REAL_INTEGRATION=agy` smoke test.
13. **Backlog (`needs-triage`):** file `bl-<nn>` for a Gemini-backed `RiskReviewer`.

## Out of scope

- **Skill portability and target-project setup:** sharing `SKILL.md` skills with `agy` through `~/.gemini/config/skills.json` or `.agents/skills/`, global rules in `~/.gemini/config/GEMINI.md`, and adapting Claude-specific skills (slash commands, hardcoded tool names, `Agent` subagents). Documented separately in `docs/gemini-skills.md`.
- **A Gemini-backed `RiskReviewer`:** tracked as a separate backlog issue; Claude Code stays the merge risk reviewer under `policy: auto`.
- **OS sandboxing guarantees (M5):** deny rules, hooks and `session/request_permission` are guardrails, not a sandbox. Full worker sandboxing remains in M5.
- **Additional ACP agent presets** beyond `agy` (for example Goose or OpenCode).
- **`agy --remote-agent` and remote projects.** Loop runs the agent on the local machine.
- **Per-kind runner selection** (`docs/PLAN.md` section 6.2, gap after M4).
