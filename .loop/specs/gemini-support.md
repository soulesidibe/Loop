---
id: spec-gemini-support
title: Gemini CLI support in Loop, headless and ACP runners, plus skill portability
labels: [needs-triage]
---

## Problem statement

Loop currently runs issues through three runners: `command` (single-turn shell command), `claude-code` (Claude Code bidirectional `stream-json`), and `codex-app-server` (Codex JSON-RPC over stdio). There is no runner for Google's Gemini CLI (`gemini`, v0.42.0 installed locally).

Gemini CLI exposes two programmatic transports:

1. **Headless streaming JSON** (`gemini -p ... --output-format stream-json`): unidirectional, one process per turn, `--resume <session_id>` for continuation, native `--policy` TOML rules, `--sandbox`, and `-m/--model`.
2. **Agent Client Protocol** (`gemini --acp`): bidirectional JSON-RPC 2.0 over stdio, one long-lived process per session, `session/cancel` for clean mid-turn interrupts, `session/new` `mcpServers` for Loop's MCP tools, and `session/request_permission` callbacks for host-side guards.

Supporting both lets operators choose between a lightweight native Gemini CLI runner and a full-fidelity bidirectional ACP runner, while also delivering the generic `runner-acp` plugin planned for M7 in `docs/PLAN.md`.

## Architectural decision: two separate plugins

Rather than adding a `mode: headless | acp` switch inside one runner, Gemini support is split into two `RunnerProvider` plugins:

- **`plugins/runner-gemini-cli`** (`runner.kind: gemini-cli`, section `runners.gemini-cli`): drives Gemini CLI headless with `-p` and `--output-format stream-json`.
- **`plugins/runner-acp`** (`runner.kind: acp`, section `runners.acp`): drives any Agent Client Protocol agent over JSON-RPC stdio, with `command: gemini --acp` as its first proven target.

Why two plugins instead of one:

- **Honest capabilities:** `claude-code` uses bidirectional `stream-json` and `codex-app-server` uses bidirectional JSON-RPC, whereas Gemini's `-p -o stream-json` is unidirectional (no stdin interrupt channel) and `gemini --acp` is bidirectional. Separate plugins keep each `RunnerCapabilities` declaration exact and avoid conditional branching across unrelated lifecycles.
- **No duplicate work for M7:** `runner-acp` is already on the roadmap in `docs/PLAN.md` (sections 6.3, 14 and 17). Running `runners.acp.command: gemini --acp` proves `runner-acp` on a real runtime today and works for other ACP agents tomorrow with no extra runner code.

| Capability | `runner-gemini-cli` (headless `stream-json`) | `runner-acp` (`command: gemini --acp`) |
|---|---|---|
| Process model | One process per turn (`--resume <id>` for turns 2..n) | One long-lived JSON-RPC process per session |
| Hard-limit `interrupt()` | Falls back to Loop's 10 s grace + kill (in-flight turn usage may be lost) | Clean `session/cancel` notification |
| Loop tools (`loop tool mcp`) | Via Gemini MCP settings / `--allowed-mcp-server-names` | Passed directly in `session/new` `mcpServers` |
| Guards and holdout folder | Generated `--policy` TOML rules | Kotlin `session/request_permission` rules (plus optional `--policy` in `command`) |
| Gemini-specific flags | First-class config keys (`model`, `sandbox`, `strict_mcp`) | Passed through `command` args (for example `gemini --acp -m gemini-2.5-pro`) |
| Works with other agents | Gemini CLI only | Any ACP agent |

---

## Part 1: Inside Loop's Gemini support

Everything in this section lives in the Loop repository.

### Shared Spike 0 (human / HITL prerequisite)

Before implementing either plugin, probe the installed `gemini` CLI (`0.42.0`) in both `-p -o stream-json` and `--acp` modes, modeled on `m3-02` and `m3-03` from the Codex lane:

1. Pin the tested `gemini` CLI version in `docs/spec-decisions.md`.
2. Capture scrubbed JSONL session recordings for both transports under `src/test/resources/recorded/`:
   - normal multi-turn session (`--resume` for headless; two `session/prompt` calls for ACP),
   - tool call and denied command / holdout denial,
   - `session/cancel` mid-turn interrupt in ACP mode,
   - usage and per-request input token fields (to confirm whether `occupancy` and `cost` can be declared `true`),
   - error responses (auth refusal, 429 / quota, model not found, network failure).
3. Verify how `strict_mcp` isolates Loop's `loop tool mcp` from the operator's `~/.gemini/settings.json` and project `.gemini/settings.json` without modifying git-tracked workspace files.
4. Verify whether `--skip-trust` loads `.agents/skills/` and `.gemini/skills/` in a fresh worktree in both modes.
5. Record the exact MCP tool names Gemini exposes for `loop tool mcp` (`submit_result`, `move`, `comment`).

### Plugin 1: `plugins/runner-gemini-cli` (headless `stream-json`)

#### Configuration and profile

```yaml
runner:
  kind: gemini-cli
runners:
  gemini-cli:
    command: gemini
    model: gemini-2.5-pro
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
    sandbox: false
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.gemini-cli.command` | string | `gemini` | no | no shell metacharacters, run without a shell |
| `runners.gemini-cli.model` | string | none | no | none |
| `runners.gemini-cli.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.gemini-cli.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.gemini-cli.strict_mcp` | boolean | `true` | no | none |
| `runners.gemini-cli.sandbox` | boolean | `false` | no | none |

#### Behavior

- **Launch:** Uses `plugins/runner-process` (`ProcessGroupLauncher`, `BoundedLineReader`, `HostEnvironment`) with no shell, workspace as `cwd`, and `--skip-trust`, `--output-format stream-json`, `--approval-mode yolo` (or `auto_edit` paired with `--policy`), and optional `-m <model>` and `-s` when `sandbox: true`.
- **Continuation:** Turn 1 captures `session_id` from the `init` JSONL event and emits `RunnerEvent.SessionStarted` with `runtimeSessionId`. Turns 2..n pass `--resume <runtimeSessionId>`. `resumeSession(ctx, runtimeSessionId)` resumes across attempts after a network hold.
- **Loop tools and `strict_mcp`:** Exposes `loop tool mcp` to the session (exact mechanism confirmed in Spike 0) and restricts active MCP servers to Loop's server when `strict_mcp: true`.
- **Guards and holdout folder:** Generates a per-attempt TOML policy file in the attempt directory and passes `--policy <file>`. Denies:
  - the exact same command list as `ClaudeSettingsFile.deniedCommands` and `runner-codex-app-server` (`git push`, `gh`, history rewrites, `loop approve`, `loop resume`, plus the attempt's `LOOP_LAUNCHER` path),
  - file reads and edits (`read_file`, `write_file`, `replace`) and shell paths referencing `holdoutDir` (both absolute and real paths).
- **Usage, occupancy and cost:** Accumulates `RunnerEvent.Usage` as absolute totals across turns in the session (never decreasing). Emits `RunnerEvent.ContextUsage` with `google/<model>` only if Spike 0 confirms per-request input tokens (not cumulative totals) are present; otherwise declares `occupancy = false`. Declares `cost = false` unless Gemini CLI reports USD cost.
- **Errors:** Maps exit code 42 / 53, auth errors (401/403), 429 / quota / overload, model refusal, and transport failures to `RunnerEvent.TurnFailed`, setting `category = RunnerUnavailable` only for runtime/model unavailability (never from agent-printed text).

#### Declared `RunnerCapabilities`

- `continuation = true`
- `loopTools = RunnerCapabilities.LoopTools.Mcp`
- `usage = true`
- `cost = false` (pending Spike 0)
- `occupancy = false` (or `true` if Spike 0 confirms per-request input tokens)
- `sandbox = settings.sandbox` (`false` by default; `--policy` is a guardrail, not a sandbox)

### Plugin 2: `plugins/runner-acp` (Agent Client Protocol)

#### Configuration and profile

```yaml
runner:
  kind: acp
runners:
  acp:
    command: gemini --acp
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
    strict_mcp: true
```

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.acp.command` | string | required | no | split into words without a shell, like `runners.codex.command` |
| `runners.acp.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.acp.read_timeout_ms` | integer | `120000` | no | 1 to 86400000 |
| `runners.acp.strict_mcp` | boolean | `true` | no | none |

#### Behavior

- **Launch and framing:** Uses `plugins/runner-process` (`ProcessGroupLauncher`, `BoundedLineReader` with 10 MB line cap, `HostEnvironment`), JSON-RPC 2.0 over stdio, no shell.
- **Session startup:** `initialize` -> `session/new` (passing `cwd` and `mcpServers` with `loop tool mcp`, plus `LOOP_SESSION_ID`, `LOOP_TURN_ID`, `LOOP_TOOL_CONFIG`). Emits `RunnerEvent.SessionStarted` with the ACP `sessionId` as `runtimeSessionId`.
- **Turns and continuation:** Each turn sends `session/prompt` on the same long-lived process and reads `session/update` notifications until the prompt response completes. `read_timeout_ms` guards JSON-RPC request/response handshakes; `turn_timeout_ms` guards silence during an active turn.
- **Interrupt:** `AgentSession.interrupt()` sends a `session/cancel` notification so the agent ends the current turn cleanly at the hard context limit and reports usage before Loop rotates the session.
- **Approvals and guards:** Answers `session/request_permission` callbacks in Kotlin (modeled on `runner-codex-app-server`'s `ServerRequestPolicy`):
  - declines any shell command matching the shared denied-command list (`git push`, `gh`, history rewrites, `loop approve`, `loop resume`),
  - declines any command or file operation touching `holdoutDir`,
  - accepts allowed workspace operations,
  - maps unsupported human-input prompts to `RunnerEvent.TurnInputRequired` and stops the session.
- **Stop:** Closes stdin, waits up to 5 seconds for clean exit, then kills the process group.
- **Usage, occupancy and errors:** Maps ACP usage updates to `RunnerEvent.Usage` (absolute session totals) and `RunnerEvent.ContextUsage` (`<provider>/<model>`, for example `google/gemini-2.5-pro`) when reported. Classifies transport/auth/rate-limit/model-refused errors as `RunnerUnavailable`.

#### Declared `RunnerCapabilities`

- `continuation = true`
- `loopTools = RunnerCapabilities.LoopTools.Mcp`
- `usage = true` (confirmed in Spike 0)
- `cost = false` (or `true` if ACP usage reports cost)
- `occupancy = true` (if Spike 0 confirms per-request input tokens in ACP updates; otherwise `false`)
- `sandbox = false`

### Core Loop touchpoints

Loop's plugin architecture keeps core edits small:

1. **Gradle wiring:** Add `:plugins:runner-gemini-cli` and `:plugins:runner-acp` to `settings.gradle.kts` and `cli/build.gradle.kts`. Each plugin registers via `META-INF/services/loop.api.RunnerProvider`, so `cli/src/main/kotlin/loop/cli/Host.kt` needs no discovery code.
2. **Secret redaction:** Add a `google-api-key` pattern (`AIza[0-9A-Za-z_-]{35}`) to `TokenPattern.DEFAULTS` in `plugin-api/src/main/kotlin/loop/api/TokenPattern.kt`.
3. **Environment policy:** Decide whether `GEMINI_API_KEY` and `GOOGLE_API_KEY` are redacted in `Host.kt` and whether they are stripped in `HostEnvironment.kt` (`ALWAYS_REMOVED_ENV_NAMES`) or allowed through (see Open decisions).
4. **Context windows:** Add shipped defaults for Gemini models (for example `google/gemini-2.5-pro: 1_048_576`) in `spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`.
5. **Shared deny-list parity:** Add a test asserting that the denied command list in `runner-claude-code`, `runner-codex-app-server`, `runner-gemini-cli`, and `runner-acp` never drifts.
6. **Loop repo context and workflow:** Add `GEMINI.md` (`@./AGENTS.md`) at the root of Loop if running Gemini on Loop's own backlog. Update `WORKFLOW.md` by one line only if Gemini's MCP tool naming differs from `mcp__loop__*`.
7. **Plugin docs:** Add `docs/plugins/runner-gemini-cli.md` and `docs/plugins/runner-acp.md` matching the structure of `docs/plugins/runner-claude-code.md` and `docs/plugins/runner-codex-app-server.md`.

### Vertical delivery slices

| Slice | Target | Scope | Proof |
|---|---|---|---|
| **0. Spike and recordings** | Both | Probe `gemini 0.42.0` in `-p -o stream-json` and `--acp`; verify MCP isolation, `--skip-trust`, usage/occupancy, `--policy`, and `session/cancel`. | Pinned version in `docs/spec-decisions.md`; scrubbed JSONL recordings. |
| **1. `runner-gemini-cli` scaffold and single turn** | Headless | Module, `RunnerProvider`, `PluginProfile`, process launch without shell, `stream-json` parser, timeouts, `docs/plugins/runner-gemini-cli.md`. | Profile tests + launch/timeout/exit tests + fake `gemini` script. |
| **2. `runner-gemini-cli` continuation, MCP and guards** | Headless | `--resume` across turns, `resumeSession()`, `loop tool mcp` + `strict_mcp`, generated `--policy` TOML for denied commands and holdout folder. | Multi-turn tests + MCP E2E test + deny-list parity test. |
| **3. `runner-gemini-cli` usage, errors and contract** | Headless | Absolute `Usage`, optional `ContextUsage`, `RunnerUnavailable` classification, `TokenPattern` `AIza...`, `context.windows` defaults. | Recorded stream replay tests + `AgentRunnerContract` passing. |
| **4. `runner-acp` scaffold and JSON-RPC framing** | ACP | Module, `RunnerProvider`, `PluginProfile`, process launch without shell, JSON-RPC 2.0 framing, `initialize` / `session/new`, `docs/plugins/runner-acp.md`. | Profile tests + fake ACP server handshake tests. |
| **5. `runner-acp` turns, continuation and `interrupt()`** | ACP | `session/prompt` turns 1..n, `read_timeout_ms` and `turn_timeout_ms`, `session/cancel` interrupt, graceful `stop()`. | Multi-turn and interrupt tests + `AgentRunnerContract` lifecycle tests. |
| **6. `runner-acp` MCP tools and permission guards** | ACP | `session/new` `mcpServers` (`loop tool mcp`), `strict_mcp`, `session/request_permission` Kotlin rules (shared deny list + holdout), `TurnInputRequired`. | Permission policy unit tests + deny-list parity test + MCP E2E test. |
| **7. `runner-acp` usage, occupancy, rotation and contract** | ACP | ACP usage/occupancy mapping, hard-limit rotation test, `RunnerUnavailable` classification. | Recorded ACP replay tests + rotation test + `AgentRunnerContract` full suite. |
| **8. Real integration and exit runs** | Both | `LOOP_REAL_INTEGRATION=gemini` smoke tests for both runners; deliver a real issue with `runner.kind: gemini-cli` and one with `runner.kind: acp` (`gemini --acp`). | Green real integration tests + traced deliveries (`loop trace`, `loop show --cost`). |

---

## Part 2: Outside Loop's Gemini support (skills and project setup)

Everything in this section lives in your personal environment (`~/.agents/skills/`, `~/.claude/skills/`) or in target project repositories, not in Loop's core code. Loop remains runner-agnostic: it renders `WORKFLOW.md` and lets the runtime load whatever skills exist in the environment or workspace.

### 1. Share one source of truth for `SKILL.md` skills

Gemini CLI (`0.42.0`) natively supports the Agent Skills standard (`SKILL.md` with YAML frontmatter) via `gemini skills` (`list`, `enable`, `disable`, `link`, `install`) and discovers skills in:

- **Project:** `.agents/skills/` and `.gemini/skills/`
- **Global:** `~/.agents/skills/` and `~/.gemini/skills/`

To avoid maintaining duplicate skill copies across Claude Code, Codex, and Gemini:

- Keep portable skills under `.agents/skills/<name>/SKILL.md` in a repository or `~/.agents/skills/<name>/SKILL.md` globally.
- Link existing Claude skills into Gemini with `gemini skills link ~/.claude/skills/<name>`.
- In target repositories that use `AGENTS.md` and `CLAUDE.md` (`@AGENTS.md`), add a one-line `GEMINI.md` containing `@./AGENTS.md` so Gemini reads the same project instructions.

### 2. Adapt Claude-specific skills for cross-runtime use

Skills originally written for Claude Code commonly have three dependencies that need adaptation when running on Gemini (or Codex):

- **Slash invocation (`/tdd`, `/issue`):** In headless and ACP runs, slash commands are not expanded like interactive TUI commands. Reference the skill by name in the `WORKFLOW.md` prompt template so Gemini activates it via its skill tool or reads the `SKILL.md` directly.
- **Hardcoded tool names:** Replace Claude-specific names (`Bash`, `Edit`, `Read`, `mcp__loop__move`) with runtime-neutral phrasing (for example, "use Loop's `move` or `submit_result` MCP tool when available, else `loop tool move`"). Loop's own `WORKFLOW.md` already follows this pattern.
- **Subagent delegation (`Agent` tool, `context: fork`, `SendMessage`):** Skills that fan out to parallel Claude subagents (such as expert reviewers in the pilot app's issue skill) will not behave identically on Gemini CLI. Until Loop's Koog manager/expert agents land in M6 (`docs/PLAN.md`), either:
  - flatten those subagent steps into sequential single-agent phases (portable across Claude, Codex, and Gemini), or
  - keep a thin Gemini-adapted skill variant only where subagent fan-out is essential.

### 3. Keep enforcement in Loop, not in skill frontmatter

Gemini CLI ignores Claude-specific frontmatter fields such as `allowed-tools` and Claude Code hooks:

- **Security and guardrails** (blocking `git push`, `gh`, history rewrites, `loop approve`/`resume`, and holdout reads) are enforced by Loop's runner (`--policy` TOML in `runner-gemini-cli` and `session/request_permission` in `runner-acp`) and by `kinds.*.verify`.
- **Mandatory workflow steps** (progress file, evidence files, writing rules, handoff protocol) should live in the `WORKFLOW.md` prompt template rather than an optional skill that a model might skip activating.

---

## Open decisions

1. **Auth and env policy for Gemini API keys:** Claude Code strips `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` so Loop always runs on the operator's subscription login. For Gemini, choose one:
   - *(a)* Allow `GEMINI_API_KEY` / `GOOGLE_API_KEY` to pass through to Gemini CLI if set, while always redacting their values (and any `AIza...` key) from logs, traces, and evidence.
   - *(b)* Strip `GEMINI_API_KEY` / `GOOGLE_API_KEY` in `ALWAYS_REMOVED_ENV_NAMES` so Gemini CLI always runs on the operator's Google OAuth login (`~/.gemini`).
2. **Implementation order after Spike 0:**
   - *(a)* Build `runner-gemini-cli` (headless `stream-json`) first for the fastest path to a working Gemini delivery, then `runner-acp`.
   - *(b)* Build `runner-acp` first to get full hard-limit `interrupt()` and Kotlin `session/request_permission` guards from day one, then `runner-gemini-cli`.
3. **Merge risk reviewer:** Under `policy: auto` with merge, `Host.kt` currently runs the merge risk reviewer via `runners.claude-code.command` even when the worker is Codex (`m4-06`). Keep that behavior for Gemini runs, or also add a Gemini-backed `RiskReviewer`?
