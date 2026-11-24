---
id: spec-gemini-support
title: Gemini support in Loop through the Antigravity CLI ACP runner
labels: [needs-triage]
---

## Problem statement

Loop runs issues through three runners today: `command` (a shell command that supports continuation turns by re-invoking per turn, see `docs/spec-decisions.md#command-runner-continuation-extension-m1-06`), `claude-code` (Claude Code bidirectional `stream-json`), and `codex-app-server` (Codex JSON-RPC over stdio). There is no runner for Google's Gemini models.

Google's Gemini CLI (`gemini`) no longer serves the operator: on 2026-11-24, every call of `gemini` 0.42.0 on the operator's personal Google login fails at authentication with `IneligibleTierError` ("This client is no longer supported for Gemini Code Assist for individuals ... migrate to the Antigravity suite"), whatever the model. Its replacement is the Antigravity CLI, `agy` (1.5.16 pinned in Spike 0 on 2026-11-24), which runs on the same Google login stored in the OS keyring. `agy models` lists Gemini 4 models whose id carries the effort level (for example `gemini-4-neon-high`, Gemini 4 Neon at high effort), plus Claude and GPT-OSS models.

`agy` exposes two programmatic transports:

1. **Agent Client Protocol** (`agy acp`): bidirectional JSON-RPC 2.0 over stdio, one long lived process per session, `session/set_model` for pinning the model, live `usage_update` notifications for per-request occupancy, `session/cancel` for mid-turn interrupts, `session/new` `mcpServers` for Loop's MCP server, `--gemini-dir <relative>` for isolated per-attempt config while keeping the OS keyring login untouched, and `session/request_permission` callbacks for every tool action when `"toolPermission": "strict"` is set in the isolated `settings.json`.
2. **Print mode** (`agy --input-format stream-json --output-format stream-json`): multi-turn NDJSON over stdio with per-step `usage`, but top-level `agy` has no `--gemini-dir` flag, so it loads `~/.gemini/config/` and cannot enforce `strict_mcp` or interactive permission callbacks.

`docs/PLAN.md` lists both `acp` and `gemini` as `AgentRunner` plugins (`docs/PLAN.md` section 12) and schedules `runner-acp` in M7 (section 17). Pulling `runner-acp` ahead of M5 (permissions hardening) and M6 (manager agents) lets Loop run and compare Gemini models now; `docs/PLAN.md` will carry a note recording this milestone jump, and that the `gemini` row is served by `runner-acp` with `preset: agy`, when implementation starts.

## Solution

Build **`plugins/runner-acp`** (`runner.kind: acp`, section `runners.acp`) as the sole Gemini runner plugin, based on the verified Spike 0 results recorded in `docs/spec-decisions.md#antigravity-cli-agy-acp-and-print-mode-spike-2026-11-24`.

- **Generic ACP transport + `preset: agy`:** drives any Agent Client Protocol agent over JSON-RPC stdio, with an optional `runners.acp.preset: agy` preset that pins the model (`session/set_model`), isolates global config (`--gemini-dir <relative-under-HOME>`), sets `"toolPermission": "strict"` and `"allowNonWorkspaceAccess": false` in the isolated `settings.json`, enables terminal Seatbelt containment (`--sandbox`), escapes leading `/` prompts, and checks protected workspace customization roots before every session and before every turn.
- **No `runner-agy` print-mode plugin:** Spike 0 proved that `agy acp` already provides live per-request occupancy, clean `session/cancel` interrupts with preserved usage, cross-process `session/load`, strict config isolation, and in-process `session/request_permission` gating for `read_file` and all other tools, whereas print mode lacks `--gemini-dir` isolation and interactive permission callbacks. `agy` stays unregistered and is never aliased to `acp`.
- **Shared guards in `plugins/runner-process`:** move `DeniedCommands` and `HoldoutFolder` from `runner-codex-app-server` into `plugins/runner-process` (adding the protected workspace roots matcher) so `runner-claude-code`, `runner-codex-app-server`, and `runner-acp` share one canonical implementation.
- **Plugin registration and contract:** `runner-acp` registers through `META-INF/services/loop.api.RunnerProvider`, reuses `plugins/runner-process` (`ProcessGroupLauncher`, `BoundedLineReader`, `agentEnvironment` in `HostEnvironment.kt`), and passes `AgentRunnerContract`.

| Capability | `runner-acp` (`command: agy acp`, `preset: agy`) | `runner-acp` (generic, no `preset`) |
|---|---|---|
| Process model | One long lived JSON-RPC process per session (0 descendants outside process group) | One long lived JSON-RPC process per session |
| Model selection | `session/set_model` from `runners.acp.model`, verified against `config_option_update` | Agent dependent |
| Context occupancy and rotation | Yes (`usage_update.used`, `usage_update.size` = `1048576` for `gemini-4-neon-high`) | Emits `ContextUsage` when agent sends `usage_update` |
| Usage and cost | `usage = true` (accumulated from `session/prompt` `result.usage`), `cost = false` | `usage = true` when reported, `cost = false` |
| Hard-limit `interrupt()` | Clean `session/cancel` plus `cancelled` reply to any pending permission request; preserves turn usage | Clean `session/cancel` plus `cancelled` reply to any pending permission request |
| Loop MCP server (`submit_result` and tracker tools) | Passed in `session/new` `mcpServers` (`mcp_loop_submit_result`, `mcp_loop_move`, `mcp_loop_comment`) | Passed in `session/new` `mcpServers` |
| `strict_mcp` | Enforced via `--gemini-dir <relative>` (drops `~/.gemini/config/` MCPs, skills and hooks) | `strict_mcp: true` is a preflight error (cannot isolate an unknown agent's MCP config) |
| Guards and holdout folder | `"toolPermission": "strict"` routes every tool (`read_file` included) to Kotlin `session/request_permission` (fail closed), plus `--sandbox` (Seatbelt for shell) and pre-session + pre-turn protected roots check (guardrail, not a sandbox) | Kotlin `session/request_permission` (fail closed); weaker on tools the agent does not prompt for (guardrail, not a sandbox) |

## Decisions

### Spike 0 results (completed 2026-11-24 on `agy 1.5.16`)

Full probe results are recorded in `docs/spec-decisions.md#antigravity-cli-agy-acp-and-print-mode-spike-2026-11-24`. Summary of the three stop conditions:

1. **Model pinning (Probe 2): passed.** `session/set_model` pins `gemini-4-neon-high` (and rejects unknown ids with `-32602`). `usage_update.size` reports `1048576`.
2. **Occupancy and usage (Probe 3): passed.** Live `usage_update` notifications fire after every LLM request mid-turn; `session/prompt` `result.usage` reports per-turn totals (`inputTokens` non-cached, `cachedReadTokens`, `outputTokens` including `thoughtTokens`, `totalTokens`, no cost).
3. **Workspace customizations (Probe 6): hit, Option 1 accepted on 2026-11-24.** Workspace `.agents/hooks.json` fires in `agy acp`, including a file written between turn 1 and turn 2 of the same session, and `agy` has no flag to disable workspace hooks. Accepted guardrail boundary: enforce `"toolPermission": "strict"` so every `read_file`, `write_file`, `edit_file`, `run_command` and `mcp_*` hits Loop's Kotlin `session/request_permission` handler (blocking `.agents/`, `.agent/`, `_agents/`, `_agent/`, `holdoutDir` and denied commands), pass `--sandbox` (Seatbelt) for terminal commands, and check protected workspace roots against `<base>` before every session **and** before every `session/prompt` turn. Document honestly as a guardrail (a shell script bypassing command matching could still write `.agents/hooks.json` within a single turn) until M5 sandboxing or an `agy` flag closes it.

Key simplification from Spike 0: because `"toolPermission": "strict"` inside `<gemini-dir>/antigravity-cli/settings.json` routes `read_file` and every other tool to Loop's in-process Kotlin `session/request_permission` handler at 0 ms overhead, **no `loop tool guard` subcommand or `PreToolUse` hook is required** (avoiding a 326 ms JVM startup on every tool call).

### Auth, env policy and secret redaction

- **Leave `ALWAYS_REMOVED_ENV_NAMES` untouched** (`plugins/runner-process/src/main/kotlin/loop/runner/process/HostEnvironment.kt:9`). That global list wins even over `agent.env` and would affect the `command` runner.
- **Strip Google API keys by default under `preset: agy`:** pass `env.removedEnvNames + setOf("GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_GENAI_USE_VERTEXAI")` to `agentEnvironment(...)` (following `ClaudeCodeRunner.kt:650` and `CodexAppServerRunner.kt:387`). A generic ACP agent (no `preset`) keeps the host's env policy unchanged. `preset: agy` therefore runs on the operator's Google login in the OS keyring, matching the subscription login rule.
- **Opt in to API billing via `agent.env`:** because `agentEnvironment` applies `added` (`agent.env`) after `removed` (`HostEnvironment.kt:20-25`), setting a key under `agent.env` is the single opt in, with no extra config key.
- **Redaction:**
  - Add `GEMINI_API_KEY` and `GOOGLE_API_KEY` beside `ANTHROPIC_KEYS` in `cli/src/main/kotlin/loop/cli/Host.kt` so host values are always redacted.
  - Add two prefix-anchored linear patterns to `TokenPattern.DEFAULTS` in `plugin-api/src/main/kotlin/loop/api/TokenPattern.kt`: `google-api-key` (`AIza[0-9A-Za-z_-]{35}`) and `google-oauth-token` (`ya29\.[0-9A-Za-z_-]{20,}`). Keys written into `agent.env` rely on these patterns. Google OAuth refresh tokens (`1//`) are skipped because the prefix is too generic.
  - Never copy, read or log any credential file under `~/.gemini/`.
- **Isolated `--gemini-dir` under `$HOME`:** Spike 0 proved `--gemini-dir` requires a path relative to `$HOME` (absolute paths fail with `must not be absolute`) and keeps the OS keyring login working with zero credential files created or copied. For each attempt under `preset: agy`, Loop creates an attempt-local directory relative to `$HOME` (for example under `$LOOP_HOME/attempts/<project>/<attempt>/agy-gemini` when `LOOP_HOME` is under `$HOME`, or a cleaned-up `.loop-agy/<attempt>` folder under `$HOME` when `LOOP_HOME` is outside `$HOME`, such as `/tmp` in tests), writes `<dir>/antigravity-cli/settings.json` (`{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`), and deletes the directory in `stop()`.

### `runner-acp` configuration, preset and lifecycle

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

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.acp.command` | string | required | no | split into words without a shell, like `runners.codex.command`; under `preset: agy`, exactly the program and `acp` |
| `runners.acp.preset` | string | none | no | optional preset; `agy` is the only supported value, any other value fails `validate` |
| `runners.acp.model` | string | none | no | required with `preset: agy`, forbidden otherwise; a `gemini-` id that `agy models` lists |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

- **Launch and framing:** `ProcessGroupLauncher` with no shell, workspace as `cwd`, `BoundedLineReader` with `ProcessGroupLauncher.MAX_LINE_BYTES` (10 MB line cap), JSON-RPC 2.0 over stdio.
- **`preset: agy` launch flags and setup:**
  - Appends `--disable-telemetry`, `--sandbox` (terminal Seatbelt containment; `RunnerCapabilities.sandbox` stays `false` because `read_file` is not sandboxed), and under `strict_mcp: true`, `--gemini-dir <relative-under-HOME>` pointing to the attempt's isolated directory containing `antigravity-cli/settings.json` (`{"toolPermission": "strict", "allowNonWorkspaceAccess": false}`). Never passes `--dangerously-skip-permissions`, `--effort`, `--remote-agent` or `--remote-control`.
  - Pins `runners.acp.model` via `session/set_model` after `session/new` (or `session/load`) and checks `config_option_update` / `models.currentModelId`; a rejection (`-32602`) or mismatch fails the turn and stops the session.
  - Escapes a leading `/` in `session/prompt` text (for example prefixing a space or newline) because Spike 0 proved `/tdd` expands skills in ACP.
  - Verifies `agentInfo.version` from `initialize` is on the pinned major (`1`, `agy 1.5.16`) and logs the version.
- **Preset validation (`RunnerProvider.validate`):** fails preflight with `invalid_config` through `RunnerConfigException` (naming the key, never a value) when:
  - `runners.acp.preset` is set to anything other than `agy`.
  - `runners.acp.model` is set without `preset: agy`.
  - `runners.acp.strict_mcp: true` with no `preset` (cannot guarantee MCP isolation for an unknown agent, matching `docs/spec-decisions.md#strict-mcp-extension-bl-72`).
  - `preset: agy` while `command` is not exactly two words: a program whose file name is `agy`, then `acp` (allowlist, no operator flags).
  - `preset: agy` without `runners.acp.model`, or a model id that does not start with `gemini-`.
- **Startup sequence:**
  1. `initialize` with `protocolVersion: 1`, `clientInfo` (`{"name": "loop", "version": "<Loop's version>"}`), and `clientCapabilities` (`{"fs": {"readTextFile": false, "writeTextFile": false}, "terminal": false}`).
  2. `authenticate` only if `session/new` or `initialize` signals auth is required (not required with an active OS keyring login).
  3. `session/new` with `cwd` (workspace path) and `mcpServers` containing Loop's MCP server (`name: "loop"`, `command: <LOOP_LAUNCHER>`, `args: ["tool", "mcp"]`, `env` with all 8 env vars of `ClaudeMcpConfig.PASSED`: `LOOP_TOOL_CONFIG`, `LOOP_ATTEMPT_ID`, `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER`, `LOOP_TRACKER_ID`, `LOOP_HOME`, `LOOP_SESSION_ID`, `LOOP_TURN_ID`). Because the process lives across turns, `LOOP_SESSION_ID` and `LOOP_TURN_ID` in the MCP server env stay fixed at the values of the turn that opened the session, matching the Claude Code precedent (`docs/spec-decisions.md#session-and-turn-ids-for-the-claude-code-runner-clarification-of-42-m1-05`).
  4. Under `preset: agy`: `session/set_model` with `runners.acp.model`.
- **Turns, occupancy, usage, interrupt, resume and stop:**
  - Turn 1 emits `RunnerEvent.SessionStarted` with the ACP `sessionId` as `runtimeSessionId`; turns 2..n send `session/prompt` on the same process and emit `RunnerEvent.TurnStarted`.
  - Every `usage_update` notification with `used > 0` emits `RunnerEvent.ContextUsage(occupancyTokens = used, model = "google/<model>")` when occupancy or model changed.
  - Each `session/prompt` response carrying `usage` adds its turn counts (`inputTokens` = non-cached input, `cacheReadInputTokens` = `cachedReadTokens`, `cacheCreationInputTokens` = `cachedWriteTokens`, `outputTokens` = `outputTokens`) into the session's running absolute totals and emits `RunnerEvent.Usage(..., totalCostUsd = null)`.
  - `AgentSession.interrupt()` sends `session/cancel` and answers any pending `session/request_permission` with `{"outcome": {"outcome": "cancelled"}}`. A `session/prompt` returning `stopReason: "cancelled"` records its preserved `usage` and completes as an interrupted turn (not a failure).
  - `resumeSession(ctx, runtimeSessionId)` opens a fresh process and calls `session/load` with `sessionId`, `cwd` and `mcpServers`, then `session/set_model` under `preset: agy` (confirmed working across processes in Spike 0).
  - `stop()` and `stop(report)` close stdin and wait up to 5 seconds for exit. When the grace expires, the runner kills the process group, reports any usage read during the grace as a `RunnerEvent.Usage`, then emits `RunnerEvent.StopGraceExpired` (`m4-04`).
- **ACP guards and protected workspace roots (guardrail, not a sandbox):**
  - `session/request_permission` evaluates every tool call using the shared `plugins/runner-process` matchers:
    - `run_command` (or `kind == "execute"`): extracts command text from `rawInput.CommandLine` / `args.CommandLine` (failing closed when missing, matching `NO_COMMAND` in `ServerRequestPolicy.kt`); declines if it matches `DeniedCommands` (including `LOOP_LAUNCHER` for `approve` and `resume`), names `holdoutDir`, or targets a protected workspace customization root (`.agents/`, `.agent/`, `_agents/`, `_agent/`).
    - File tools (`read_file`, `write_file`, `edit_file`, `replace`, or `kind in ["read", "edit"]`): extracts paths from `locations`, `args.Path` / `rawInput.Path` / `file_path` (failing closed when missing); declines any read or edit under `holdoutDir` (absolute and real paths), and declines any write or edit under `.agents/`, `.agent/`, `_agents/`, `_agent/` (absolute and real paths).
    - MCP tools (`mcp_loop_*`): allowed for Loop's own `loop` server; under `strict_mcp: true`, any MCP tool from another server is declined.
    - Unsupported human-input prompts end the turn at once with `RunnerEvent.TurnInputRequired` and stop the session.
  - **Check protected roots before every session and every turn (`preset: agy`):** before `session/new` / `session/load` and before every `session/prompt`, compare `.agents/`, `.agent/`, `_agents/`, `_agent/` against the attempt's base commit (`git diff --name-only <base> -- <roots>` plus untracked files there). Any added or modified `hooks.json`, `mcp_config.json`, `skills.json`, plugin folder or rule file fails the turn as an ordinary failure naming the path and stops the session.

### Shared guards in `plugins/runner-process`

Move the canonical denied command list and hardened matchers (`DeniedCommands` and `HoldoutFolder` from `plugins/runner-codex-app-server/.../Guards.kt`) into `plugins/runner-process`, and add the protected workspace roots matcher (`.agents/`, `.agent/`, `_agents/`, `_agent/`). Update `runner-claude-code` and `runner-codex-app-server` to use the shared matchers, replacing `DeniedCommandsTest`'s source-file scraping (`DeniedCommandsTest.kt:103-119`).

### Error mapping

Aligned with `RunnerEvent.kt` (`bl-39`, `bl-141`) and `docs/spec-decisions.md#network-loss-extension-m4-08`:

- **`RunnerEvent.TurnFailed(category = RunnerUnavailable)`** is set **only** when the runtime's own structured JSON-RPC error or error result reports unavailability: authentication, eligibility or access failure (401, 403, expired login, `IneligibleTierError`), rate limit, quota or overload (429), model refusal (`-32602 Unknown model ID` or `modelNotFound`, `bl-141`), or a runtime-reported network transport failure with no HTTP response (`m4-08`).
- **Ordinary failure (`category = null`)** for everything else: process crashes, non-zero exits without a structured unavailability error, malformed JSON, and any error text printed by the agent or a tool output.

### Merge risk reviewer

Keep the existing Claude reviewer (`riskReviewerCommand` in `Host.kt`, `m4-06`). `docs/PLAN.md` asks for a reviewer model different from the worker, and `riskReviewerCommand` already falls back to `runners.claude-code.command` or `claude` for non-Claude workers. Gemini runs under `policy: auto` with merge still require Claude Code installed (`riskReviewerWarning`). A Gemini-backed `RiskReviewer` is filed as a separate `bl-<nn>` (`needs-triage`) backlog issue.

### Core touchpoints

1. **Gradle wiring:** add `:plugins:runner-acp` to `settings.gradle.kts` and `cli/build.gradle.kts`.
2. **Shared guards:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process`, add the protected roots matcher, and update `runner-claude-code` and `runner-codex-app-server` to use them.
3. **Secrets and redaction:** add `google-api-key` (`AIza...`) and `google-oauth-token` (`ya29....`) to `TokenPattern.DEFAULTS` (`TokenPattern.kt`), and redact `GEMINI_API_KEY` and `GOOGLE_API_KEY` in `Host.kt`.
4. **Context windows:** add shipped Gemini 4 defaults (`google/gemini-4-neon-high: 1_048_576`, `google/gemini-4-neon-medium: 1_048_576`, `google/gemini-4-neon-low: 1_048_576`, `google/gemini-4-neon-xhigh: 1_048_576`, `google/gemini-3.8-flash-high: 1_048_576`, `google/gemini-3.8-flash-medium: 1_048_576`, `google/gemini-3.8-flash-low: 1_048_576`) to `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`.
5. **Real integration targets:** add `"agy"` to `RealIntegration.TARGETS` in `plugin-api/src/testFixtures/kotlin/loop/api/RealIntegration.kt:27`.
6. **Startup preflight:** add an `agy` preflight check (binary reachable, version on pinned major `1`, login state warning, `agy models` check warning when offline or missing model) in `cli/src/main/kotlin/loop/cli/Host.kt` (`runnerPreflight`). Only a missing binary or wrong version major fails preflight.
7. **Loop repo files:** no `GEMINI.md` is needed, since `agy` loads `AGENTS.md` natively. Update `WORKFLOW.md:153` to mention Loop's MCP tools (`mcp__loop__move` / `mcp_loop_move` or runtime-neutral phrasing with `loop tool move` fallback).
8. **Docs:** add `docs/plugins/runner-acp.md` stating capabilities and context-limit enforcement, and record in `docs/PLAN.md` the M7 milestone jump and that the `gemini` plugin row is served by `runner-acp` (`preset: agy`).

## Rejected

- **Targeting Gemini CLI (`gemini`).** Fails on the operator's personal Google login with `IneligibleTierError` since 2026-11.
- **Building `plugins/runner-agy` (print mode).** Spike 0 showed top-level `agy` has no `--gemini-dir` flag, so print mode loads `~/.gemini/config/` and cannot enforce `strict_mcp` or interactive permission callbacks, while `runner-acp` already delivers live occupancy, `session/cancel`, `session/load`, and strict isolation.
- **Spawning `loop tool guard` from a `PreToolUse` hook on every tool call.** Measured 326 ms per JVM invocation in Spike 0; `"toolPermission": "strict"` inside `<gemini-dir>/antigravity-cli/settings.json` routes `read_file` and every other tool to Loop's in-process Kotlin `session/request_permission` handler with 0 ms overhead.
- **Relying on `settings.json` `permissions.deny` alone for `read_file`.** Spike 0 probe 5d showed `read_file` still ran when the `strict` prompt answered `allow`; Loop enforces denials directly in Kotlin `session/request_permission`.
- **Pure generic `runner-acp` with no `preset`.** A static `runners.acp.command` cannot pass per-attempt `--gemini-dir` isolation, `"toolPermission": "strict"`, or `session/set_model`.
- **An `{attempt_dir}` placeholder in `runners.acp.command`.** Pushes internal config paths and flag syntax onto every operator's `WORKFLOW.md`.
- **Adding `GEMINI_API_KEY` and `GOOGLE_API_KEY` to `ALWAYS_REMOVED_ENV_NAMES`.** That global list wins over `agent.env` and would also strip those keys from the `command` runner.
- **`--dangerously-skip-permissions`.** Approves every tool call, bypassing Loop's guards.
- **A blacklist of forbidden `agy` flags in `command`.** An allowlist (`agy acp` only under `preset: agy`) cannot fall behind new flags.

## Issues

Spike 0 is done. Implementation is split into 7 independent vertical slices plus 1 backlog issue:

1. **Shared guards move:** move `DeniedCommands` and `HoldoutFolder` into `plugins/runner-process`, add the protected workspace roots matcher (`.agents/`, `.agent/`, `_agents/`, `_agent/`), switch `runner-claude-code` and `runner-codex-app-server` to them, and replace `DeniedCommandsTest`'s source scraping.
2. **Core prep:** add `AIza...` and `ya29....` to `TokenPattern.DEFAULTS`, `GEMINI_API_KEY` / `GOOGLE_API_KEY` redaction to `Host.kt`, default `google/<model>` windows (`1_048_576`) to `WorkflowConfig.kt`, and `"agy"` to `RealIntegration.TARGETS`.
3. **`runner-acp` scaffold, handshake and turns:** create `plugins/runner-acp`, `AcpRunnerProvider`, `PluginProfile`, preset validation in `validate`, JSON-RPC 2.0 framing (`ProcessGroupLauncher.MAX_LINE_BYTES`), `initialize` (major version pin `1`), `session/new`, `session/load`, `session/set_model`, `session/prompt` (with leading `/` escaping under `preset: agy`), `read_timeout_ms` and `turn_timeout_ms`, `stop(report)` with `StopGraceExpired`, fake ACP server, and `docs/plugins/runner-acp.md`.
4. **`runner-acp` MCP server, isolation and `strict_mcp`:** pass `loop tool mcp` with all 8 `ClaudeMcpConfig.PASSED` env vars in `session/new` and `session/load` `mcpServers`; create and clean up the isolated `--gemini-dir <relative-under-HOME>` containing `antigravity-cli/settings.json` (`toolPermission: strict`, `allowNonWorkspaceAccess: false`); pass `--disable-telemetry` and `--sandbox`; refuse `strict_mcp: true` without a preset at preflight.
5. **`runner-acp` permission guards and holdout protection:** implement fail-closed `session/request_permission` policy using shared `runner-process` matchers (denied commands including `LOOP_LAUNCHER`, `holdoutDir` reads and writes, protected roots writes/edits/commands, non-Loop MCP denial under `strict_mcp`), `TurnInputRequired` for human-input prompts, and the pre-session + pre-turn protected roots diff check against `<base>`.
6. **`runner-acp` usage, occupancy, interrupt, errors, preflight and contract:** map `usage_update` to `ContextUsage` (`google/<model>`) and `session/prompt` `result.usage` to absolute `Usage`, wire `interrupt()` to `session/cancel` plus `cancelled` permission replies, map `RunnerUnavailable` per `RunnerEvent.kt` and `m4-08`, add `agy` preflight in `Host.kt`, commit scrubbed Spike 0 recordings under `plugins/runner-acp/src/test/resources/recorded/`, and pass `AgentRunnerContract` + rotation tests.
7. **`runner-acp` real integration, `WORKFLOW.md` update and exit run:** add `LOOP_REAL_INTEGRATION=agy` smoke test, update `WORKFLOW.md:153` and `docs/PLAN.md`, and deliver a real Loop issue on `runner.kind: acp` (`command: agy acp`, `preset: agy`, `model: gemini-4-neon-high`) with peak occupancy under 25 percent.
8. **Backlog (`needs-triage`):** file `bl-<nn>` for a Gemini-backed `RiskReviewer`.

## Out of scope

- **Print mode runner (`plugins/runner-agy`):** deferred after Spike 0 showed no `--gemini-dir` isolation for print mode.
- **Skill portability and target-project setup:** sharing `SKILL.md` skills with `agy` through `~/.gemini/config/skills.json` or `.agents/skills/`, global rules in `~/.gemini/config/GEMINI.md`, and adapting Claude-specific skills. Documented in `docs/gemini-skills.md`.
- **A Gemini-backed `RiskReviewer`:** tracked as a separate backlog issue; Claude Code stays the merge risk reviewer under `policy: auto`.
- **OS sandboxing guarantees (M5):** `session/request_permission` under `toolPermission: strict` and `--sandbox` (terminal-only Seatbelt) are guardrails, not a full OS sandbox covering `read_file` or mid-turn workspace hook writes. Full worker sandboxing remains in M5.
- **Additional ACP agent presets** beyond `agy` (for example Goose or OpenCode).
- **`agy --remote-agent` and remote projects.** Loop runs the agent on the local machine.
- **Per-kind runner selection** (`docs/PLAN.md` section 6.2, gap after M4).
