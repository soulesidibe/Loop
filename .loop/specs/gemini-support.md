---
id: spec-gemini-support
title: Gemini CLI support in Loop, ACP and headless runners
labels: [needs-triage]
---

## Problem statement

Loop runs issues through three runners today: `command` (a shell command that supports continuation turns by re-invoking per turn, see `docs/spec-decisions.md#command-runner-continuation-extension-m1-06`), `claude-code` (Claude Code bidirectional `stream-json`), and `codex-app-server` (Codex JSON-RPC over stdio). There is no runner for Google's Gemini CLI (`gemini`, v0.42.0 installed on the development machine).

Gemini CLI exposes two programmatic transports:

1. **Agent Client Protocol** (`gemini --acp`): bidirectional JSON-RPC 2.0 over stdio, one long lived process per session, `session/cancel` for mid-turn interrupts, `session/new` `mcpServers` for Loop's MCP server, and `session/request_permission` callbacks for host side guards.
2. **Headless streaming JSON** (`gemini -p ... --output-format stream-json`): unidirectional, one process per turn, `--session-id` / `--resume` for continuation, native `--admin-policy` TOML rules, `--sandbox`, and `-m/--model`.

`docs/PLAN.md` lists both `acp` and `gemini` as `AgentRunner` plugins (`docs/PLAN.md` section 12) and schedules `runner-acp` in M7 (section 17). Pulling Gemini and ACP ahead of M5 (permissions hardening) and M6 (manager agents) lets Loop run and compare Gemini models now; `docs/PLAN.md` will carry a note recording this milestone jump when implementation starts.

## Solution

Support Gemini CLI through two separate `RunnerProvider` plugins, built in order after a shared verification spike:

1. **`plugins/runner-acp`** (`runner.kind: acp`, section `runners.acp`): built **first**, gated on Spike 0. Drives an Agent Client Protocol agent over JSON-RPC stdio, with an optional `runners.acp.agent: gemini` preset that injects per-attempt Gemini flags (`--admin-policy`, `--skip-trust`, `--allowed-mcp-server-names`, `-e <value>`). Only ACP can give Loop live context occupancy, clean mid-turn `interrupt()`, and session rotation to keep main sessions under 25 percent of their window.
2. **`plugins/runner-gemini`** (`runner.kind: gemini`, section `runners.gemini`, matching `docs/PLAN.md` section 12): built **second**, only if Spike 0 shows a clear reason (for example, if ACP occupancy or guards prove unusable, or if a lightweight headless fallback without context limits is still wanted). If dropped, `gemini` stays unregistered and is never aliased to `acp`.

Both plugins register through `META-INF/services/loop.api.RunnerProvider`, reuse `plugins/runner-process` (`ProcessGroupLauncher`, `BoundedLineReader`, `agentEnvironment` in `HostEnvironment.kt`), share one canonical deny list and holdout matcher moved into `plugins/runner-process`, and pass `AgentRunnerContract`.

| Capability | `runner-acp` (`command: gemini --acp`, `agent: gemini`) | `runner-acp` (generic, no `agent` preset) | `runner-gemini` (headless `stream-json`) |
|---|---|---|---|
| Process model | One long lived JSON-RPC process per session | One long lived JSON-RPC process per session | One process per turn (UUID resume verified in Spike 0) |
| Context occupancy and rotation | Yes, if Spike 0 confirms per-request input tokens; else reconsider spec | Agent dependent (`false` when not reported) | No (`occupancy = false`); Loop warns and enforces only the prompt limit and turn cap (`RunCommand.kt:264`) |
| Hard-limit `interrupt()` | Clean `session/cancel` plus `cancelled` reply to any pending permission request | Clean `session/cancel` plus `cancelled` reply to any pending permission request | Falls back to Loop's 10 s grace and kill; never triggers without occupancy |
| Loop MCP server (`submit_result` and tracker tools) | Passed in `session/new` `mcpServers` | Passed in `session/new` `mcpServers` | Via Gemini MCP config (mechanism verified in Spike 0) |
| `strict_mcp` | Enforced via preset flags (`--allowed-mcp-server-names`, `-e <value>`), verified in Spike 0 | `strict_mcp: true` refuses to start (cannot isolate unknown agent's MCP config) | Enforced via `--allowed-mcp-server-names` and `-e <value>`, verified in Spike 0 |
| Guards and holdout folder | Kotlin `session/request_permission` (fail closed) plus generated `--admin-policy` TOML (guardrail, not a sandbox) | Kotlin `session/request_permission` (fail closed) plus client `fs` reads if supported; weaker on unprompted reads (guardrail, not a sandbox) | Generated `--admin-policy` TOML and `-e <value>` (guardrail, not a sandbox) |

## Decisions

### Spike 0 gates both plugins (ACP probes first)

Before writing runner code, an operator spike probes `gemini 0.42.0` and pins the version in `docs/spec-decisions.md` (following the Codex precedent in `docs/spec-decisions.md#codex-cli-app-server-schema-and-mcp-loading-m3-02`). Run the ACP probes first so work can stop early if neither path can enforce context occupancy.

**ACP probes (first):**

1. **Handshake, auth and protocol version:** record `initialize` (protocol version, auth methods, agent capabilities), whether `authenticate` is required under Google OAuth login, and whether `session/load` works for `resumeSession`.
2. **Occupancy and usage:** confirm whether ACP updates emit per-request input tokens (main session, cached included, not cumulative) for `RunnerEvent.ContextUsage`, absolute totals for `RunnerEvent.Usage`, USD cost, and the active default model name and context window for CLI 0.42.0. If ACP does not expose per-request occupancy and headless does not either, stop and reconsider this spec.
3. **Permission triggers and client `fs`:** list every built-in tool (`read_file`, `read_many_files`, glob, grep, `list_directory`, `write_file`, `replace`, shell, MCP) and record which ones fire `session/request_permission` under each approval mode (`default`, `auto_edit`, `yolo`), what fields their payloads carry (`rawInput`, `locations`), and whether advertising ACP client `fs` capabilities routes file reads through Loop. Pick the single approval mode the `agent: gemini` preset passes: the one that sends every shell and write tool through `session/request_permission`.
4. **Preset flags under `--acp`:** confirm whether `--admin-policy <file>`, `--skip-trust`, `--allowed-mcp-server-names loop`, and `-e <value>` are honored by `gemini --acp`, which `-e` value loads no extension (a bare `-e` fails on 0.42.0 with "Not enough arguments following: e", and without the flag every extension loads; `none` is the likely value), whether `--admin-policy` overrides workspace `.gemini/policies/` and `--approval-mode`, and whether `session/new` `mcpServers` works alongside `--allowed-mcp-server-names`.
5. **Cancel and errors:** capture `session/cancel` during an active turn and during a pending `session/request_permission`, plus structured errors for auth failure, 429 / quota / overload, model refusal, and network loss.
6. **MCP tool names and skills:** record the exact tool names Gemini exposes for `loop tool mcp` (`submit_result`, `move`, `comment`) and confirm `--skip-trust` loads `.agents/skills/` in a fresh worktree.

**Headless probes (second, if still building `runner-gemini`):**

7. **UUID resume:** prove starting a session with `--session-id <uuid>` and resuming that exact UUID from a fresh process. Never use `--resume latest`, since parallel attempts collide.
8. **Workspace config reload across turns:** verify whether `.gemini/settings.json`, `.gemini/policies/`, hooks, or extensions written during turn `n` reload in turn `n+1` under `--skip-trust`, and confirm `--admin-policy` plus `-e <value>` prevent override.
9. **Approval mode and policy coverage:** pick one approval mode (`yolo` vs `auto_edit`) and confirm `--admin-policy` deny rules beat it across shell and file tools.
10. **Stats and exit codes:** confirm the exact `stream-json` `result` stats shape (confirming whether occupancy is unavailable) and exit codes (including 41 auth, 42 input error, 53 turn limit).

**Recordings:** save scrubbed JSONL transcripts in `plugins/runner-acp/src/test/resources/recorded/` and (if built) `plugins/runner-gemini/src/test/resources/streams/`, scrubbed with a script modeled on `plugins/runner-codex-app-server/recording/scrub.py`.

### Auth, env policy and secret redaction

- **Leave `ALWAYS_REMOVED_ENV_NAMES` untouched** (`plugins/runner-process/src/main/kotlin/loop/runner/process/HostEnvironment.kt:9`). That global list wins even over `agent.env` and would affect the `command` runner.
- **Strip inside the Gemini runners by default:** pass `env.removedEnvNames + setOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_GENAI_USE_VERTEXAI")` to `agentEnvironment(...)` (following `ClaudeCodeRunner.kt:650` and `CodexAppServerRunner.kt:387`). This applies to `runner-gemini` always and to `runner-acp` only under `agent: gemini`; a generic ACP agent keeps the host's env policy unchanged. Both Gemini paths therefore default to the operator's Google OAuth login (`~/.gemini`), matching the subscription login rule.
- **Opt in to API billing or Vertex AI via `agent.env`:** because `agentEnvironment` applies `added` (`agent.env`) after `removed` (`HostEnvironment.kt:20-25`), setting `GEMINI_API_KEY`, `GOOGLE_API_KEY`, or Vertex variables under `agent.env` is the single opt in, with no extra config key.
- **Redaction:**
  - Add `GEMINI_API_KEY` and `GOOGLE_API_KEY` beside `ANTHROPIC_KEYS` in `cli/src/main/kotlin/loop/cli/Host.kt` (`Host.kt:592`, `617`) so host values are always redacted.
  - Add two prefix-anchored linear patterns to `TokenPattern.DEFAULTS` in `plugin-api/src/main/kotlin/loop/api/TokenPattern.kt`: `google-api-key` (`AIza[0-9A-Za-z_-]{35}`) and `google-oauth-token` (`ya29\.[0-9A-Za-z_-]{20,}`). Keys written into `agent.env` rely on these patterns. Google OAuth refresh tokens (`1//`) are skipped because the prefix is too generic.

### `runner-acp` preset, strict MCP and lifecycle

```yaml
runner:
  kind: acp
runners:
  acp:
    command: gemini --acp
    agent: gemini
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.acp.command` | string | required | no | split into words without a shell, like `runners.codex.command` |
| `runners.acp.agent` | string | none | no | optional preset; `gemini` is the only supported value, any other value fails `validate` |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

- **Launch and framing:** `ProcessGroupLauncher` with no shell, workspace as `cwd`, `BoundedLineReader` with `ProcessGroupLauncher.MAX_LINE_BYTES` (10 MB line cap), JSON-RPC 2.0 over stdio.
- **Preset flags:** when `agent: gemini` is set, the runner appends its per-attempt flags after the words of `command`, in this order: `--admin-policy <file>` (generated in the attempt directory), `--skip-trust`, `--approval-mode <mode>` (the mode Spike 0 probe 3 picks), and under `strict_mcp: true`, `--allowed-mcp-server-names loop` and `-e <value>`. Exact flags and values are confirmed by Spike 0.
- **Preset validation:** `RunnerProvider.validate` runs on every load of `WORKFLOW.md`, so these fail preflight with `invalid_config` through `RunnerConfigException`, naming the key and never a value:
  - `runners.acp.agent` set to anything other than `gemini`.
  - `runners.acp.strict_mcp: true` with no `agent` preset (decidable from config alone, so it never waits for session start).
  - `agent: gemini` while the program in `command` (its first word, by file name) is not `gemini`.
  - `agent: gemini` while `command` already carries a flag the preset owns or that could weaken a guard: `--admin-policy`, `--policy`, `--allowed-mcp-server-names`, `-e` or `--extensions`, `-y` or `--yolo`, `--approval-mode`, `--skip-trust`. Other words, such as `-m <model>`, pass through untouched.
- **`strict_mcp` behavior:** with `strict_mcp: true` (the default) and `agent: gemini`, the preset isolates MCP servers to Loop's `loop` server. With no `agent` preset (generic ACP agent), Loop cannot prevent the agent from loading its own MCP servers, so `strict_mcp: true` is the preflight error above (matching the Codex precedent in `docs/spec-decisions.md#strict-mcp-extension-bl-72`). Operators using an unpreset ACP agent must set `strict_mcp: false`.
- **Startup sequence:**
  1. `initialize` with Loop's client info, pinned ACP protocol version (confirmed in Spike 0), and client capabilities (including client `fs` if Spike 0 confirms it routes reads through Loop).
  2. `authenticate` when `initialize` requires an auth method.
  3. `session/new` with `cwd` (workspace path) and `mcpServers` containing Loop's MCP server (`<LOOP_LAUNCHER> tool mcp`, server name `loop`) with all 8 env vars of `ClaudeMcpConfig.PASSED` (`ClaudeMcpConfig.kt:24-34`): `LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`. Because the process lives across turns, `LOOP_SESSION_ID` and `LOOP_TURN_ID` in the MCP server env stay fixed at the values of the turn that opened the session, matching the Claude Code precedent (`docs/spec-decisions.md#session-and-turn-ids-for-the-claude-code-runner-clarification-of-42-m1-05`).
- **Turns, interrupt, resume and stop:**
  - Turn 1 emits `RunnerEvent.SessionStarted` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n send `session/prompt` on the same process and emit `RunnerEvent.TurnStarted`.
  - `AgentSession.interrupt()` sends `session/cancel` and answers any pending `session/request_permission` with `cancelled`, so the turn ends cleanly and reports its usage before rotation.
  - `resumeSession(ctx, runtimeSessionId)` uses ACP `session/load` if Spike 0 confirms support, else returns `null` (falling back to a fresh session with the session brief).
  - `stop()` and `stop(report)` close stdin, wait up to 5 seconds for exit, and when the grace expires, kill the process group, report any usage read during the grace as a `RunnerEvent.Usage`, then emit `RunnerEvent.StopGraceExpired` (`m4-04`) after that last `Usage`. What the agent had not reported yet is lost.
- **ACP guards (guardrail, not a sandbox):**
  - `session/request_permission` checks commands and paths using the shared `plugins/runner-process` matchers (including the attempt's `LOOP_LAUNCHER` path for `loop approve` and `loop resume`, plus `holdoutDir` absolute and real paths).
  - **Fail closed:** any shell or write permission request with no readable command text or path is declined (matching `NO_COMMAND` in `plugins/runner-codex-app-server/.../ServerRequestPolicy.kt`).
  - Unsupported human-input prompts end the turn at once with `RunnerEvent.TurnInputRequired` and stop the session.
  - With `agent: gemini`, the generated `--admin-policy` TOML file adds a second guardrail covering unprompted read tools (`read_file`, `read_many_files`, glob, grep, `list_directory`) and workspace policy overrides. Without `agent: gemini`, unprompted reads rely on client `fs` routing when supported and remain a documented guardrail limit.

### `runner-gemini` (headless, conditional on Spike 0)

Built only if Spike 0 gives a clear reason after evaluating `runner-acp`. Its plugin doc states clearly that it declares `occupancy = false` and **cannot enforce Loop's context limits** (Loop logs a warning and enforces only the prompt limit and `agent.max_turns`, `RunCommand.kt:264`).

```yaml
runner:
  kind: gemini
runners:
  gemini:
    command: gemini
    model: <pinned-default-from-spike>
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
    sandbox: false
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.gemini.command` | string | required | no | no shell metacharacters, run without a shell (matches `runners.claude-code.command`) |
| `runners.gemini.model` | string | none | no | none |
| `runners.gemini.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.gemini.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.gemini.strict_mcp` | boolean | `true` | no | none |
| `runners.gemini.sandbox` | boolean | `false` | no | none |

- **Continuation:** mints a UUID with `--session-id <uuid>` on turn 1 and resumes that UUID across turns (exact resume flag verified in Spike 0; never `--resume latest`).
- **Guards and workspace isolation:** writes a per-attempt TOML file in the attempt directory and passes `--admin-policy <file>` (never `--policy`, which workspace `.gemini/policies/` could outrank on turn `n+1`), passes `-e <value>` to pin extensions, passes `--skip-trust`, and passes the single approval mode selected in Spike 0 (`yolo` or `auto_edit`) once confirmed that `--admin-policy` deny beats it.
- **Policy coverage:** deny rules cover the shared denied command list (plus `LOOP_LAUNCHER` for `approve` and `resume`), `holdoutDir` (absolute and real paths) across `read_file`, `read_many_files`, glob, grep, `list_directory`, `write_file`, `replace`, and shell commands, plus non-Loop MCP servers under `strict_mcp: true`. Documented guardrail limits (not a sandbox): `@path` includes, relative paths, symlinks created inside the workspace, and shell indirections (`sh -c`, scripts, `git -C . push`) that bypass simple prefix rules.
- **Capabilities:** `continuation = true`, `loopTools = Mcp`, `usage = true`, `cost = false` (unless Spike 0 confirms USD cost), `occupancy = false`, `sandbox = settings.sandbox`.

### Shared guards in `plugins/runner-process`

Move the canonical denied command list and hardened matchers (`DeniedCommands` and `HoldoutFolder` from `plugins/runner-codex-app-server/.../Guards.kt`) into `plugins/runner-process` as shared utilities used by `runner-claude-code`, `runner-codex-app-server`, `runner-acp`, and `runner-gemini`. This replaces `DeniedCommandsTest`'s source-file scraping (`DeniedCommandsTest.kt:103-119`) and keeps all runners in sync, including denying `loop approve` and `loop resume` via both bare `loop` and the attempt's `LOOP_LAUNCHER` path.

### Error mapping

Aligned with `RunnerEvent.kt` (`bl-39`, `bl-141`) and `docs/spec-decisions.md#network-loss-extension-m4-08`:

- **`RunnerEvent.TurnFailed(category = RunnerUnavailable)`** is set **only** when the runtime's own structured error result reports unavailability: authentication or access failure (401, 403, expired login, exit 41 once verified), rate limit, quota or overload (429), model refusal (`modelNotFound`, `bl-141`), or a runtime-reported network transport failure with no HTTP response (`m4-08`).
- **Ordinary failure (`category = null`)** for everything else: process crashes, non-zero exits without a structured unavailability error, malformed JSON, exit 42 (input error), exit 53 (turn limit), and any error text printed by the agent or a tool output.

### Merge risk reviewer

Keep the existing Claude reviewer (`Host.kt:403-413`, `m4-06`). `docs/PLAN.md` asks for a reviewer model different from the worker, and `riskReviewerCommand` already falls back to `runners.claude-code.command` or `claude` for non-Claude workers. Gemini runs under `policy: auto` with merge still require Claude Code installed (`riskReviewerWarning`). A Gemini-backed `RiskReviewer` is filed as a separate `bl-<nn>` (`needs-triage`) backlog issue.

### Core touchpoints

1. **Gradle wiring:** add `:plugins:runner-acp` (and `:plugins:runner-gemini` if built) to `settings.gradle.kts` and `cli/build.gradle.kts`.
2. **Shared guards:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process` and update `runner-claude-code` and `runner-codex-app-server` to use them.
3. **Secrets and redaction:** add `google-api-key` (`AIza...`) and `google-oauth-token` (`ya29....`) to `TokenPattern.DEFAULTS` (`TokenPattern.kt`), and redact `GEMINI_API_KEY` and `GOOGLE_API_KEY` in `Host.kt`.
4. **Context windows:** add the default `google/<model>` window from Spike 0 to `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`.
5. **Real integration targets:** add `"gemini"` (and `"acp"` if gated separately) to `RealIntegration.TARGETS` in `plugin-api/src/testFixtures/kotlin/loop/api/RealIntegration.kt:27`.
6. **Startup preflight:** add a Gemini preflight check (binary reachable, login state warnings) in `cli/src/main/kotlin/loop/cli/Host.kt` (`runnerPreflight`, line 653).
7. **Loop repo files:** add `GEMINI.md` (`@./AGENTS.md`) at the root of Loop if running Gemini on Loop's backlog. Update `WORKFLOW.md:153` once Spike 0 records Gemini's exact MCP tool names for `move`, `comment`, and `submit_result` (today line 153 names `mcp__loop__move` with a `loop tool move` fallback).
8. **Docs:** add `docs/plugins/runner-acp.md` and (if built) `docs/plugins/runner-gemini.md`, each stating clearly whether the runner enforces context limits, and record the M7 milestone jump in `docs/PLAN.md`.

## Rejected

- **One `runner-gemini-cli` plugin with a `mode: headless | acp` switch.** The two transports have different process lifecycles, interrupt semantics, guard mechanisms, and `RunnerCapabilities`. Two plugins match `docs/PLAN.md:647` and avoid duplicating ACP in M7.
- **Pure generic `runner-acp` with no `agent` preset.** A static `runners.acp.command` cannot pass per-attempt generated `--admin-policy` files or Gemini's `--allowed-mcp-server-names` and `-e <value>` flags, leaving holdout reads unguarded and `strict_mcp` unenforceable on Gemini.
- **An `{attempt_dir}` placeholder in `runners.acp.command`.** Pushes internal policy file paths and flag syntax onto every operator's `WORKFLOW.md` instead of encapsulating them in a validated preset (`runners.acp.agent: gemini`).
- **Adding `GEMINI_API_KEY` and `GOOGLE_API_KEY` to `ALWAYS_REMOVED_ENV_NAMES`.** That global list wins over `agent.env` and would also strip those keys from the `command` runner. Stripping inside the Gemini runners via `removedEnvNames` matches the subscription login rule while keeping `agent.env` as the clean opt in.
- **Aliasing `runner.kind: gemini` to `acp` if the headless runner is dropped.** Confusing when `gemini` is documented as the headless runner; leave `gemini` unregistered instead.
- **Using `--resume latest` in headless mode.** Parallel attempts on the same repository would collide on the latest session.
- **Using `--policy` instead of `--admin-policy` in headless mode.** Workspace `.gemini/policies/` written during turn `n` could reload and outrank `--policy` on turn `n+1`.

## Issues

Each slice is scoped to land independently after Spike 0 settles the facts:

1. **Spike 0 (operator spike):** probe `gemini 0.42.0` in `--acp` mode first, then `-p -o stream-json`; pin version and model/window in `docs/spec-decisions.md`; save scrubbed recordings; confirm whether to build `runner-gemini` after `runner-acp`.
2. **Shared guards move:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process`, switch `runner-claude-code` and `runner-codex-app-server` to them, and replace `DeniedCommandsTest`'s source scraping. A refactor of two shipped runners with no Gemini code, so it can land before Spike 0 ends.
3. **Core prep:** add `AIza...` and `ya29....` to `TokenPattern.DEFAULTS`, `GEMINI_API_KEY` / `GOOGLE_API_KEY` redaction to `Host.kt`, the default `google/<model>` window from Spike 0 to `WorkflowConfig.kt`, and `"gemini"` to `RealIntegration.TARGETS`.
4. **`runner-acp` scaffold, handshake and turns:** create `plugins/runner-acp`, `RunnerProvider`, `PluginProfile`, JSON-RPC 2.0 framing (`ProcessGroupLauncher.MAX_LINE_BYTES`), `initialize` / `authenticate` / `session/new` / `session/prompt`, `read_timeout_ms` and `turn_timeout_ms`, `stop(report)` with `StopGraceExpired`, fake ACP server, `docs/plugins/runner-acp.md`.
5. **`runner-acp` MCP server, preset and `strict_mcp`:** pass `loop tool mcp` with all 8 `ClaudeMcpConfig.PASSED` env vars in `session/new` `mcpServers`; implement the `runners.acp.agent: gemini` preset flags (appended after `command`, including the approval mode) and its preset validation in `validate`, including the `strict_mcp: true` with no preset preflight error.
6. **`runner-acp` permission guards and holdout protection:** implement fail-closed `session/request_permission` policy using shared `runner-process` matchers (including `LOOP_LAUNCHER` and `holdoutDir`), client `fs` read handling if supported, `TurnInputRequired`, and generated `--admin-policy` TOML for `agent: gemini`.
7. **`runner-acp` usage, occupancy, interrupt and contract:** map ACP usage and `ContextUsage` (`google/<model>`), wire `interrupt()` to `session/cancel` plus `cancelled` permission replies, replay recorded sessions, pass `AgentRunnerContract` and rotation tests.
8. **`runner-acp` error mapping and Gemini preflight:** map `RunnerUnavailable` per `RunnerEvent.kt` and `m4-08` from recorded error responses, and add the Gemini preflight check in `Host.kt` (`runnerPreflight`).
9. **`runner-acp` real integration and exit run:** add `LOOP_REAL_INTEGRATION=gemini` smoke test and deliver a real Loop issue on `runner.kind: acp` (`command: gemini --acp`, `agent: gemini`) with peak occupancy under 25 percent.
10. **`runner-gemini` scaffold, turns and UUID resume *(conditional on Spike 0)*:** create `plugins/runner-gemini`, `RunnerProvider`, `PluginProfile` (`command` required), `stream-json` parser, `--session-id` / verified UUID resume, `docs/plugins/runner-gemini.md` noting no context limit enforcement.
11. **`runner-gemini` MCP, `strict_mcp` and `--admin-policy` guards *(conditional on Spike 0)*:** wire `loop tool mcp`, `--allowed-mcp-server-names`, `-e <value>`, and generated `--admin-policy` TOML covering shared denied commands and `holdoutDir` under the chosen approval mode.
12. **`runner-gemini` usage, errors, contract and real integration *(conditional on Spike 0)*:** absolute `Usage`, `RunnerUnavailable` mapping, recorded stream replay tests, `AgentRunnerContract`, and `LOOP_REAL_INTEGRATION=gemini` smoke test.
13. **Backlog (`needs-triage`):** file `bl-<nn>` for a Gemini-backed `RiskReviewer`.

## Out of scope

- **Skill portability and target-project setup:** sharing `SKILL.md` skills via `.agents/skills/` and `gemini skills link`, adding `GEMINI.md` (`@./AGENTS.md`) to target repositories, and adapting Claude-specific skills (slash commands, hardcoded tool names, `Agent` subagents). Documented separately in `docs/gemini-skills.md`.
- **A Gemini-backed `RiskReviewer`:** tracked as a separate backlog issue; Claude Code stays the merge risk reviewer under `policy: auto`.
- **OS sandboxing guarantees (M5):** `--admin-policy` and `session/request_permission` are guardrails, not a sandbox. Full worker sandboxing remains in M5.
- **Additional ACP agent presets** beyond `gemini` (for example Goose or OpenCode).
- **Per-kind runner selection** (`docs/PLAN.md` section 6.2, gap after M4).
