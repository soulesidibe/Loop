# Loop: plan

Status: validated plan, revised after the review of 2026-11-05 and the grilling of 2026-11-06 (terms in [`CONTEXT.md`](../CONTEXT.md), decisions in [`docs/adr/`](adr/)). M1 is done (2026-11-11), M2 is done (2026-11-14), M3 is done (2026-11-18); the M4 issues are in `.loop/issues/m4-*`, written in m3-29, with the spec in [`.loop/specs/loop-m4.md`](../.loop/specs/loop-m4.md).

> Ids such as `m3-29` (milestone 3, issue 29) or `bl-134` (backlog issue 134) name Loop's own issues. Open ones are in [`.loop/issues/`](../.loop/issues/). Finished ones were kept out of this repository when it went public, so their ids remain only as history. Pull request numbers up to #119 (for example "pull request #115") refer to the old private repository, not to this one. "The pilot app" is a private iOS app used to prove Loop on real work.

## 1. What Loop is

**Loop is not a coding agent.** Loop is an issue execution harness. It takes issues that are ready for an agent from one or more trackers, runs one agent per issue in an isolated workspace, verifies the result with checks the agent did not write, and delivers it only when the evidence holds. Loop never does the work of an issue itself.

Loop is built in three layers:

1. **A Kotlin implementation of the [OpenAI Symphony spec](https://github.com/openai/symphony/blob/main/SPEC.md)** (Draft v1, Apache 2.0). Symphony defines the service that polls a tracker, gives each issue a workspace, runs an agent, retries, and reconciles. Loop follows it for everything it covers, and aims for its Core Conformance.
2. **A verification layer in the style of [claw-orchestrator](https://github.com/Enderfga/claw-orchestrator).** A run only counts as done when checks the agent did not write pass. Every run ends with a verdict: `verified`, `refuted` or `unverified`. Symphony has no verification step; this is Loop's main addition.
3. **Loop extensions and plugins.** Kinds (code and non-code), several trackers at once, any agent runtime, budgets and a ledger, shared devices, a human channel, manager agents. Integrations (trackers, runtimes such as ACP, channels, check types) are plugins, not core.

An issue can be any work an agent can do and someone can check:

- **code**: a feature, a bug fix, a refactor, delivered as a pull request;
- **content**: a blog post, a LinkedIn post, docs, a store listing, delivered as files;
- **research**: a competitor scan, a library comparison, delivered as a report;
- **ops**: a release note sent, a dashboard created, delivered as an action in another system, always behind an approval.

Loop replaces a manager skill for Claude Code that I used to run issues and removes its dependency on Claude Code.

### Success means

- an issue goes from "ready" to "delivered" with no human touch, and Loop can prove it (the ledger);
- a `WORKFLOW.md` that only uses Symphony keys runs on Loop (in Symphony mode, section 6.0) as it would on any Symphony implementation;
- trackers and agent runtimes are swapped by changing config, and several trackers can feed one Loop at the same time;
- a backlog can mix kinds, and each kind is verified with checks that fit it;
- Loop runs on a laptop or headless on a server.

### Non-goals (for now)

- Loop does not do the work. It drives agents that do.
- Loop does not write issues. It triages and bounces vague ones.
- No web dashboard in the core. Symphony's optional HTTP API is a plugin.
- Physical devices are not available in remote mode.

## 2. Principles

1. **Follow the spec where it speaks.** For polling, workspaces, hooks, retries, reconciliation, prompts, tracker adapters and observability, Symphony's spec is authoritative. Loop adds; it does not fork.
2. **Deterministic where possible, a model only for judgment.** Claims, locks, checks, verdicts, delivery, budgets and limits are Kotlin code. Models plan, do the issue's work, review and answer questions.
3. **Never trust the worker's claim.** Loop runs the checks itself, including checks the agent never sees. No checks means `unverified`, never "done".
4. **A small core, everything else a plugin.** The core knows the spec's contracts and Loop's extensions. Trackers, runtimes, channels, check types and delivery executors plug in through stable interfaces.
5. **No long conversations in the manager.** Orchestrator state lives in a database. Manager decisions are small, fresh model calls built from that state.
6. **Trace everything from the first issue.** Every decision, prompt, agent event, check and cost is recorded with its ids and can be retrieved and analyzed (section 10).
7. **No context above 25 percent of its window.** Loop rotates sessions before they fill up, and budgets every prompt (section 8).
8. **Ship on a real workload at every milestone.** Each milestone ends with real issues delivered on one of your projects.

## 3. Glossary

Terms marked (S) come from the Symphony spec and keep its meaning. The canonical glossary is [`CONTEXT.md`](../CONTEXT.md); this table adds the terms of the plan.

| Term | Meaning |
|---|---|
| Issue (S) | A unit of work read from a tracker, normalized: `id`, `identifier`, `title`, `description`, `priority`, `state`, `labels`, `blocked_by`, `dispatchable`, and more. Not necessarily code. |
| Tracker adapter (S) | A plugin that reads issues from one provider and normalizes them. Loop can run several at once. |
| `WORKFLOW.md` (S) | The repository contract: YAML front matter for config, Markdown body for the prompt template. Reloaded while running. |
| Workspace (S) | The per-issue directory the agent works in, under `workspace.root`. |
| Attempt | One execution of an agent on one issue, inside a run (the spec's run attempt). Retries and continuations create new attempts. |
| Run | One working period of the Loop service, started by `loop run`. It can span restarts when Loop is relaunched with its id. A run only groups records for tracing and holds no issue state. |
| Project | One `WORKFLOW.md` and the state Loop keeps for it: claims, waiting and parked issues, pending approvals, budgets, the ledger. It survives every restart, whatever the run. |
| Agent runner | The plugin that drives one agent runtime. Symphony specifies only the Codex app-server; Loop generalizes it. |
| Runtime | The agent a runner drives: Codex, Claude Code, an ACP agent, a shell command, later a Koog agent. |
| Prompt template (S) | The body of `WORKFLOW.md`, or a kind's own template. This is what the manager skill's plan called a recipe. It can tell a runtime to use a skill. |
| Kind | A Loop profile (`code`, `content`, `research`, `ops`, or your own) that sets the runner, template, verify contract and delivery for matching issues. A kind with its own runner is a gap after M4. |
| Verify contract | The checks Loop runs on a finished attempt: visible checks, holdout checks, guards, and an optional review. |
| Holdout check | A check stored outside the workspace that the agent never sees. |
| Verdict | `verified`, `refuted` or `unverified`. Only `verified` can be delivered. |
| Symphony mode, Loop mode | Without a `kinds:` key, Loop is a plain Symphony implementation. With it, Loop owns verification and the move to the handoff state (section 6.0). |
| Waiting, parked | Loop claim states on top of the spec's. A waiting issue is not dispatched until what it waits for arrives; a parked issue is not dispatched until a human resumes it (section 6.6). |
| Deliverable | What a done issue produces: a pull request, files, a report, or an external action. |
| Delivery | Moving a verified deliverable to its destination: merge, post the report, run the approved action, then move the issue to its handoff state. |
| Evidence | Saved raw output of a check, stamped with the fingerprint of the workspace it ran on. |
| Resource | Something one attempt can use at a time: a phone, an emulator, the display. |
| Ledger | The record of every attempt and its metrics. |
| Occupancy | How full a model's context window is at a given moment: the input size of the latest request. |
| Context budget | The limits on occupancy: soft 20 percent, hard 25 percent of the window. |
| Rotation | Ending a session that crossed the soft or hard limit and starting a fresh one in the same workspace, with a session brief. |
| Handoff state (S) | The tracker state an issue moves to when the agent's work is ready for someone else, for example `Human Review`. |
| Session brief | What a fresh session gets after a rotation or a retry: goal, git state, check results, verdicts, counters, then the agent's own notes. Built from facts. |
| Trace | The full, retrievable record of everything that happened for a run, an issue or an attempt. |
| Human touch | Any moment a human had to act: an answer, an approval, a fix, a manual delivery. |

## 4. Architecture

```
  trackers (plugins)                               human channel (plugin)
  files, GitHub, Linear, Jira, ...                 terminal, Telegram, Slack
        │  fetch_issues_by_states / by_ids                  ▲
        ▼                                                    │
┌───────────────────────────────────────────────────────────┴──────────────┐
│ Loop                                                                       │
│  Symphony core (spec)                     Loop extensions                  │
│   ├ WORKFLOW.md loader + live reload       ├ multi-tracker registry        │
│   ├ typed config + preflight               ├ kinds                         │
│   ├ orchestrator: poll, sort, dispatch     ├ verify: contracts, holdout,   │
│   ├ claims, retries, backoff, stalls       │   guards, review, verdicts    │
│   ├ reconciliation, startup cleanup        ├ delivery: merge, report,      │
│   ├ workspaces + hooks + path safety       │   approved actions, handoff   │
│                                            ├ context budgets + rotation    │
│   ├ prompt rendering (strict templates)    ├ budgets + ledger + store      │
│                                            ├ event store + tracing         │
│   └ structured logs + token accounting     ├ resources (devices)           │
│                                            ├ reactions (CI, review)        │
│                                            ├ Loop tools for agents         │
│                                            └ manager agents (Koog)         │
│  plugin API: TrackerAdapter, AgentRunner, CheckType, DeliveryExecutor,     │
│              HumanChannel, ResourceProvider                                │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │ launch, turns, events, usage
          ┌────────────────────┼─────────────────────┐
   workspace A            workspace B            workspace C
   GitHub #454, code      Linear MKT-12, content files:research/03
   Claude Code runner     Codex app-server       ACP runner
```

### How the six harness parts map

| Part | Inside one issue (runtime) | Across the backlog (Loop) |
|---|---|---|
| Model | The worker's model | A model per manager role (triage, experts, reviewer), any provider via Koog |
| Context | The runtime's context file, the workspace | The rendered prompt within its budget, occupancy limits, rotation, context sources per kind |
| Memory | The runtime's working memory | Session briefs built from facts, lessons with a source and expiry, issue summaries, run store |
| Tools | read, edit, run | Loop tools for agents (`ask`, `request_resource`, `release_resource`, `submit_result`, `file_followup`) plus tracker tools from adapters |
| Verification | The worker's own checks | Verify contracts with holdout checks and guards, review, verdicts |
| Permissions and sandbox | The runtime's approval and sandbox policy | Path safety, generated policies, no risky tools, approval for external actions, limits; full hardening in M5 |
| (Observability) | The runtime's transcript | Event store, OpenTelemetry traces, `loop trace`, `loop replay`, `loop query` |

## 5. The Symphony core

Loop implements the spec's Core Conformance (section 18.1 of the spec) and uses the spec's test matrix (section 17) as its own acceptance tests. Summary of what that means:

- **`WORKFLOW.md`.** Front matter keys `tracker`, `polling`, `workspace`, `hooks`, `agent`, `codex`, with the spec's defaults (poll every 30 s, 10 concurrent agents, 20 max turns, backoff capped at 5 min, hook timeout 60 s, stall timeout 5 min). `$VAR` resolution, strict prompt rendering with `issue` and `attempt`, live reload that keeps the last good config on error, preflight before each dispatch tick.
- **Orchestrator.** A single authority that polls, sorts candidates (priority, then oldest, then identifier), respects global and per-state concurrency, claims issues (`Unclaimed`, `Claimed`, `Running`, `RetryQueued`, `Released`), and runs attempts through the spec's phases (`PreparingWorkspace`, `BuildingPrompt`, `LaunchingAgentProcess`, `InitializingSession`, `StreamingTurn`, `Finishing`, then `Succeeded`, `Failed`, `TimedOut`, `Stalled` or `CanceledByReconciliation`). Loop adds two claim states, `Waiting` and `Parked` (section 6.6).
- **Continuation and retries.** After a normal turn, the issue is fetched again; if still active, another turn on the same thread, up to `max_turns`. Failures retry with `min(10000 * 2^(attempt - 1), max_retry_backoff_ms)`; a clean exit schedules a 1 s continuation check.
- **Reconciliation.** Every tick, running issues are fetched again: terminal means stop and clean the workspace; not active means stop without cleanup. Terminal workspaces are swept at startup.
- **Workspaces.** `<workspace.root>/<workspace_key>`, keys limited to `[A-Za-z0-9._-]` with a hash suffix when the identifier was changed, the agent's working directory always equal to the workspace, the four hooks (`after_create`, `before_run`, `after_run`, `before_remove`) with timeouts. `before_run` runs before every attempt, continuations included, so hooks must be idempotent. Hooks are not rendered as templates; Loop passes `LOOP_ISSUE_ID`, `LOOP_ISSUE_IDENTIFIER` and `LOOP_WORKSPACE_KEY` as environment variables, documented as an extension.
- **Tracker writes.** The orchestrator never writes to trackers directly in the spec; adapters expose host-side tools to the agent, and tracker credentials never reach the agent process.
- **Observability.** Structured `key=value` logs with `issue_id`, `issue_identifier` and `session_id`; token accounting from absolute totals, never double counted; a runtime snapshot.
- **Codex app-server runner.** Required for conformance, so it ships in the core as the reference runner.

Where the spec is ambiguous (the hash algorithm for workspace keys, whether `after_run` runs after a failed `before_run`, sorting of priorities outside 1 to 4, cleanup on a terminal retry, the absence of a retry cap), Loop picks an answer, documents it in `docs/spec-decisions.md`, and pins the spec commit it implements.

The spec's own open items (persist the retry queue and sessions across restarts) are done by Loop's run store.

## 6. Loop extensions

All extensions live in new top-level front matter keys, which the spec allows. A Loop `WORKFLOW.md` stays readable by other Symphony implementations: they ignore the keys they do not know. This holds because `tracker:` stays the primary tracker (section 6.1).

### 6.0 Symphony mode and Loop mode

- **Symphony mode:** no `kinds:` key. Loop is a plain Symphony implementation. The tracker tools are intact, the agent moves the issue to its handoff state itself, and there is no verification gate and no Loop delivery. The conformance tests run in this mode.
- **Loop mode:** a `kinds:` key is present. Loop owns the move to the handoff state. The tracker tool that moves an issue to a handoff or terminal state is intercepted, not removed: Loop treats the call as `submit_result`, runs the kind's verify contract, and performs the move itself only on a `verified` verdict.
- The same prompt works in both modes, so a workflow switches to Loop mode by adding `kinds:` and nothing else.

### 6.1 Several trackers at once

```yaml
tracker:                    # the spec's key, the primary tracker
  id: gh
  kind: github
  provider: { repo: Jublu-Inc/jublu, token: $GITHUB_TOKEN }
  required_labels: [ready-for-agent]
  active_states: [open]
  terminal_states: [closed]
trackers:                   # Loop extension, additional trackers
  - id: lin
    kind: linear
    provider: { team: MKT, api_key: $LINEAR_API_KEY }
    active_states: [Todo, In Progress]
    terminal_states: [Done, Canceled]
  - id: local
    kind: files
    provider: { dir: .loop/issues }
```

- The spec's `tracker:` key stays the primary tracker, with the id given by its `id` key or `default`; `trackers:` adds others. A Loop `WORKFLOW.md` always keeps a `tracker:` entry, so other implementations still pass preflight.
- Each issue is namespaced by tracker: `gh:454`, `lin:MKT-12`, `local:research-03`. Workspace keys, logs and the ledger use the namespaced id.
- Each tracker is polled and reconciled on its own; the candidates of all trackers are merged and sorted with the spec's rule. A failing tracker does not stop the others.
- Concurrency: the spec's global and per-state limits, plus an optional per-tracker limit.
- Blockers: native `blocked_by` within a tracker; across trackers, a line in the body such as `Blocked by lin:MKT-12`.
- Follow-up issues go to the parent's tracker unless the kind says otherwise.
- Swapping trackers is a config change. Moving from files to GitHub, or adding Linear next to GitHub, touches no code.

### 6.2 Kinds

A kind is matched from the issue (tracker id, label, state, or a field (not in M4)) and sets the runner, the template, the verify contract and the delivery. Loop's core knows these slots, never the work itself.

Gap after M4: kinds with their own runner are out of M4 (m4-45). In M4 a kind sets its template, verify contract, workspace and delivery, and every kind runs on the project's runner.

| Kind | Workspace population | Deliverable | Checks (examples) | Delivery |
|---|---|---|---|---|
| `code` | git worktree via `after_create` | pull request | build, tests, lint, tamper guard, holdout tests | merge (policy `human` or `auto`), then move to the handoff state |
| `content` | git worktree of a content repo, or empty | files | markdown lint, link check, length limits, banned words and dashes, review rubric | merge or attach to the issue |
| `research` | empty | report | sources listed and reachable, required sections present, review | post on the issue |
| `ops` | empty | external action plan | plan matches a schema, dry run where possible | executed only after human approval |

Every kind gets a review on top of its checks once the reviewer is available (M6), because many criteria ("the tone matches the brand voice") are not checkable by a command. Until then, `review:` is optional. Kinds with weak checks get a rubric and default to human approval.

An issue with no acceptance criteria is not ready, whatever its kind. Readiness is checked in code before dispatch: a kind declares `ready: { require_sections: [Acceptance criteria] }`, and an issue that fails it is not dispatched; in Loop mode, Loop comments and adds `needs-info`. The triage agent (M6) adds judgment on top of this check.

### 6.3 Any agent runtime

The spec defines only the Codex app-server. Loop defines an `AgentRunner` interface modeled on the spec's section 10, so every runner looks the same to the orchestrator:

```kotlin
interface AgentRunner {
    val kind: String
    val capabilities: RunnerCapabilities   // continuation, loop tools, usage, cost, occupancy, sandbox
    suspend fun start(ctx: AttemptContext, prompt: String): AgentSession
    suspend fun continueTurn(session: AgentSession, guidance: String): TurnOutcome
    fun events(session: AgentSession): Flow<RunnerEvent>   // session_started, turn_completed, ...
    suspend fun stop(session: AgentSession)
}
```

- Events reuse the spec's names (`session_started`, `turn_completed`, `turn_failed`, `turn_input_required`, and the rest) and error categories, so the orchestrator, stall detection and token accounting work the same for every runner. `turn_input_required` carries `reason`. Loop adds the trace event kinds `turn_started` (data: `turn`, `max_turns`, `thread_id`, `turn_id`; `turn_completed` and `turn_failed` carry the same data, `turn_failed` plus `reason`; a turn a spend budget ended also carries `capped` = `true`), `retry_scheduled` (data: `kind` (`continuation` or `error`), `attempt`, `delay_ms`, `due_at`, `error` (absent for `continuation`), `failures`; a `continuation` also carries `no_progress` and `max_continuations`), `issue_parked` (`reason`, `failures`, `error`, optional `store_error`; a `budget` park carries `budget`, `used` and `limit` instead of `failures` and `error` (a `token_cap` park, before m2-20, carried `attempt_tokens` and `max_tokens`); a `max_continuations` park carries `no_progress` and `max_continuations`), `issue_unparked` (`reason`, `from_state`, `to_state`), `issue_commented` (project, run, issue, identifier, attempt, session, turn; data `chars`), `retry_dropped` (`reason`: `not_found`, `terminal`, `not_active`, `not_eligible`), `budget_warning` and `budget_reached` (`budget`, `used`, `limit`; m2-20, `token_cap_reached` with `attempt_tokens` and `max_tokens` before), `attempt_stalled`, `session_stop_failed` (data `reason`: the agent session could not be stopped, its process group may still run; m3-07), `candidate_skipped` (a dispatch candidate was skipped, with the reason, recorded when the reason changes) and `run_stopped` (the `loop run` process is stopping; data `reason`, `stop_requested`, `signal`, `internal_error` or `abandoned`, and `attempts_canceled`; a Run can span restarts; `abandoned` is recorded by the next `loop run` for a dead process that recorded none, with `detected_by_run`). `attempt_finished` carries the reasons `stalled`, `stop_requested`, `signal`, `internal_error` (an internal error canceled the run) and `abandoned` (the next `loop run` closed an Attempt a dead process left open, with `detected_by_run`) besides the existing ones. Since bl-39 `turn_failed` carries `category` = `runner_unavailable` (and `retry_after_ms`) when the runner says it cannot reach its model, and the run level hold that follows adds `runner_hold_started` (data `reason`, `hold`, `delay_ms`, `probe_at`, optional `retry_after_ms`; one per hold period, with the issue and attempt whose failure began it) and `runner_hold_lifted` (`holds`, `held_ms`; with the issue and attempt whose turn succeeded). Since m3-15 the CI wait before a merge adds `ci_waiting` (data `sha`, the merged head pushed for its GitHub Actions runs) and `ci_finished` (`outcome`: `green`, `red`, `no_run_pass`, `no_run_fail` or `timeout`; `runs`, the ids of the runs it kept). Since m3-08 `context_usage` records the occupancy of the agent session once per turn end, right before `turn_completed` or `turn_failed` (data `latest_tokens`, `peak_tokens`, `model`, and `window` and `fraction` when `context.windows` knows the model; section 8.3). Since m3-11 `session_rotated` records a rotation at the soft limit (data `reason` = `soft_limit`, `occupancy_tokens`, `window`, `fraction`, `rotation`, `max_rotations`, `old_thread_id`, `old_runtime_session_id`, `new_thread_id`, `new_runtime_session_id`; section 8.4), and `issue_parked` gains the reason `max_rotations` (with `rotations`, `max_rotations` and the occupancy). A rotation stops the session and opens a new one with `openSession`, whose first turn is its own turn 1, so the Claude Code runner starts it without `--resume`. Since m3-12 a `context_usage` at or above the hard limit during a turn interrupts that turn at once: its `turn_completed` carries `interrupted` = `hard_limit` (an interrupted turn is not a failed one), and `session_rotated` reads `reason` = `hard_limit`. `issue_parked` gains the reason `split_issue` (with `without_progress`, `rotations`, `limit` and the occupancy), and `issue_commented` the reason `split_issue` for its comment. Since bl-136 `interrupt_grace_expired` records, on the interrupted turn, that the runtime did not end it within the 10 second grace after the interrupt request, so Loop cancelled the runner's flow and the usage it had not reported is lost (data `grace_ms`, the session's latest `occupancy_tokens`, and `window` and `fraction` when the window is known, and the latest usage known, `attempt_tokens` and `total_cost_usd`; section 8.4).
- **Core:** `codex-app-server` (the spec's runner) and `command` (any shell command, one turn).
- **Plugins:** `claude-code` (headless, streaming JSON, resume), `acp` (any agent that speaks the Agent Client Protocol), later `koog` (a native Koog agent).
- A kind picks its runner (not in M4, see section 6.2); `codex:` settings apply to the Codex runner, `runners.<kind>:` settings to others.
- Loop's tools reach the agent in the way each runtime supports: client tools for the Codex app-server, an MCP server for Claude Code and ACP agents, files for `command`.
- **Declared capabilities (m3-06):** every runner declares `RunnerCapabilities`: `continuation` (a session for turns 2 to n), `loop_tools` (`mcp`, `client_tools` or `files`, as above), `usage` (absolute `Usage` totals), `cost` (a cost on them, which needs `usage`), `occupancy` (the `context_usage` event of section 8.3, main session only) and `sandbox` (an OS sandbox or a container; deny rules do not count). `command` declares continuation and `files` only; `claude-code` declares continuation, `mcp`, usage, cost and occupancy (m3-08); `codex-app-server` declares continuation, `client_tools`, usage and occupancy, and no cost (m3-21 to m3-23). A runner event `context_usage` carries the session, the turn, the occupancy in tokens and the model (`provider/model`, the key of `context.windows`): the input size of the latest model request of the main session, cached tokens included, never a cumulative total.
- **Interrupt (m3-12):** `AgentSession.interrupt()` asks the runtime to end the turn that runs now, for the hard limit of section 8.4. The turn's flow then reports the usage so far and ends with its terminal event, which Loop does not count as a failure. It does nothing by default: Loop then cancels the turn's flow after a grace of 10 seconds. The `claude-code` runner writes a stream-json interrupt request, and Claude Code ends the turn with a `result` line that carries its usage and cost. The `codex-app-server` runner writes a `turn/interrupt` request, and Codex reports the turn's last usage and ends it with `turn/completed` `interrupted` (m3-23).
- **Runner contract (m3-06):** `AgentRunnerContract`, in the `plugin-api` test fixtures beside `TrackerAdapterContract`, is the test suite every runner passes, the Codex runner included: each turn opens with `session_started` or `turn_started` and ends with exactly one of `turn_completed`, `turn_failed`, `turn_input_required`; usage is absolute and never decreases within a session; cancelling leaves no agent process or child; the declared capabilities match the behavior; a `turn_failed` category is null or `runner_unavailable`. The cases are listed in [spec decisions](spec-decisions.md#runner-capabilities-and-the-agentrunner-contract-extension-m3-06).

### 6.4 Verification, in the style of claw

When an attempt ends (the agent calls `submit_result`, or calls the intercepted handoff tool, section 6.0), Loop runs the kind's verify contract before anything is delivered.

```yaml
kinds:
  code:
    verify:
      checks:                                   # visible to the agent, also in the prompt
        - { type: command, run: "./gradlew testStagingUnitTest" }
        - { type: command, run: "./gradlew lintStaging detekt" }
      holdout:                                  # stored outside the workspace, never shown
        - { type: command, run: "$LOOP_HOLDOUT/jublu/acceptance.sh" }
      guards:
        protect: ["**/src/test/**", "detekt-baseline.xml", ".github/**"]
        deny: [deleted_tests, added_skips, weaker_assertions]
      review: { role: reviewer, rubric: .loop/rubrics/code.md }
```

- **Check types** are plugins. Core: `command`, `file_exists`, `file_matches`, `diff_policy`, `changed_line_coverage`. Plugins: `http` probe, `screenshot` compare, `maestro` flow, others.
- **Changed line coverage** (bl-117). `{ type: changed_line_coverage, reports: ["**/build/reports/kover/report.xml"], min_percent: 80, base: main }` refutes a result whose added or changed lines are covered below `min_percent`, so an agent cannot add a code path with no test. The changed lines are those of the diff against the merge base with `base`, committed, uncommitted and untracked, like `diff_policy`, and `base` defaults like the `diff_policy` base, to `guards.base`, else `main`; a pull request kind takes the same fetched base as `diff_policy`. The reports are JaCoCo XML, which Kover writes too, found by the globs in the workspace and merged: a line counts when one report lists it with instructions, and is covered when one report covers it. A report's source file matches a changed file whose path ends with its package path and file name, else whose declared `package` and file name are that key, so a file whose folder does not match its package still counts. Lines no report lists (declarations, comments, generated code) and files in no report do not count, so the check passes when no changed line counts. It runs in process after the checks before it, so a preceding `command` check writes the reports. A glob that finds no report or a report that cannot be parsed means the check could not run, `unverified`, never a pass. Its output lists the uncovered lines per file, capped, then the percentage and the counts, so they reach `loop.verify_findings`. List the report paths the build writes, so a stale or planted file is overwritten. Branch coverage and other report formats are out of scope.
- **Verdicts.**
  - `verified`: every check and holdout passed, no guard fired, and the review passed when the kind declares one.
  - `refuted`: a check failed, a guard fired, or the agent edited a protected file a check depends on. Loop sends the findings back as continuation guidance (the prompt's `loop.verify_findings`), which counts against `max_turns` and the attempt budget.
  - `unverified`: no checks are defined, or a check could not run. Never delivered automatically; the issue is parked (section 6.6) and reported to the human.
- **Evidence.** Each check's output is saved with the workspace fingerprint (HEAD, uncommitted diff and untracked files for git; a content hash otherwise) and the exit code.
- **Holdouts stay hidden.** They live in Loop's own config directory, outside every workspace and outside the paths the runner's sandbox can read.
- **Review.** An independent Koog agent, ideally on a different model than the worker, grades each acceptance criterion from the deliverable and the evidence.
- **Later:** a council (several reviewers on different models) and fan-out (best of n attempts on different runners; among the verified ones, the council keeps the best implementation, judged on correctness, design and fit with the project's architecture and conventions, never on cost, since every attempt has already been paid for; costs go to the ledger to choose runners for later issues).

### 6.5 Delivery and handoff

In the spec, the agent moves the issue to a handoff state itself (for example `Human Review`). In Loop mode, the agent's call to the tool that moves an issue to a handoff or terminal state is intercepted (section 6.0). Only Loop moves it, and only after a `verified` verdict:

```yaml
kinds:
  code:
    delivery:
      policy: human              # human | auto
      on_verified: { move_to: "Human Review", comment: evidence_summary }
      on_delivered: { move_to: "Done" }
```

- `human`: Loop asks through the human channel (`loop approve <issue>` until the terminal channel lands in M4), then merges or runs the action.
- `auto`: Loop delivers directly (merge after a merge check on current main; post the report; run an allowlisted action). For `code`, `auto` is conditional and opt in per project (M2): Loop merges only when the verdict is `verified` with holdouts, the merge check on current main passes and the merged head is the verified fingerprint, CI is green and the pull request mergeable, no test was lost, no protected path is touched (`WORKFLOW.md`, `scripts/`, `.github/`, Gradle files, `build-logic/`, `gradle/`, `.loop/`, `docs/adr/`, `CONTEXT.md` by default) and the diff stays under a size limit. Otherwise it falls back to `human`.
- The merge check always merges current main into the branch and reruns the kind's checks on the result. M1 showed why: branches that touch the same files conflict when they merge out of order (m1-17, m1-34).
- Default `human` for each new project and always for external actions.
- Loop posts progress and evidence on the issue, so the tracker shows what happened.
- `move_to` is defined per adapter: a state for Linear and Jira, label changes for GitHub (section 6.6).
- Pull request delivery (m2-25, story 79) is opt in per kind with `delivery.pull_request` (`repo`, `token: $NAME`, `base`, `remote`). Delivering a verified result then pushes the verified commit to `loop/<workspace key>` host side, through a credential helper and never forced, opens or updates the pull request with a body built from the verification's facts, comments its URL on the issue, then moves the issue. The steps are separate and the move is the last, optional one, so m2-26 can stop after the comment. The pull request is looked up in every state, and one already merged at the verified commit counts as delivered. A push needs a clean committed fingerprint, so with `pull_request` a verified work tree that holds uncommitted or untracked files is refuted at verification, its finding naming them. The token reaches git through a credential helper that answers only the configured GitHub host over https. The push goes to an explicit `https://<host>/<owner>/<repo>.git` from a temporary repository Loop owns that borrows the project's objects, with Loop's settings pinned and no config file read, so a `.git/config` an agent writes from its worktree cannot steer it; every delivery git process gets only `PATH`, `HOME` and the locale. `api_url` must be https (http only for a loopback host, for tests). A work tree clean before the checks is restored to its commit after them. A transient failure retries at the next tick, a permanent one records `delivery_failed` and waits for a new approval. Without the key, delivery stays the move to the handoff state. The merge check and the merge came in m2-26 (2026-11-14): with `delivery.pull_request.merge` (`method` `merge` or `squash`, `ci` required) and `delivery.on_delivered.move_to` (a terminal state), an approved delivery stops after the comment and waits as `Waiting(merge)`; in the background, on an agent slot and one at a time per project, Loop fetches the base host side into `refs/loop/base/<base>`, merges it in the issue's own workspace, reruns every check with `diff_policy` against the fetched base, pushes the merged head, reads the base tip again (at most 3 rounds), and merges with a sha guard, then moves the issue to `delivery.on_delivered.move_to` and removes the workspace. A conflict or a failing recheck comments the reason and sends the issue back to its agent, and its later verifications base `diff_policy` on `refs/loop/base/<base>`; a `git merge` failure that is not a conflict, like a merge refused for good, records `delivery_failed` and waits for a new approval. A stop between the merge commit and the next write is resumed from the base stored before merging. Before the merge, Loop waits for the pull request to show the pushed head, reads the issue again (a human move cancels the merge) and tries a "branch was modified" refusal once more at the next tick. Every git command Loop runs in a workspace now has hooks, fsmonitor, signature checks, gpg programs, automatic gc and maintenance and replace refs off, a minimal environment and a timeout (part of bl-55). m2-26 merges with `ci: none`; waiting for GitHub Actions is m2-30, moved to M3 on 2026-11-14 (the pilot app merges with `ci: none` in M2, Loop's merge check being the gate). m3-15 (carrying m2-30) adds `ci: actions`: after a passing merge check Loop pushes the merged head, waits as `Waiting(merge)` in phase `ci` for its Actions runs, polled once per tick, and merges only on green, or on no run when `ci_no_run` is `pass`; red CI, a failing no run rule and a timeout (`ci_timeout_ms`) record `delivery_failed` and wait for a new approval, the pull request left open. Conditional auto merge came in m2-28 (story 100): `policy: auto` needs the opt in `delivery.auto_merge.enabled`, and with `merge` the drain approves for itself, the merge check runs, and right before the merge Loop reads seven hard conditions (verified with holdouts, the merged head checked and shown, GitHub `mergeable` and `clean`, every deny guard in the merge check's `diff_policy`, no protected path, a diff under `max_diff_lines`, no `diff_base_unpinned`), then a merge risk assessment: code facts set floors, a read only reviewer (`delivery.risk.reviewer`) judges five dimensions, and Loop computes the weighted score and band. It merges only on a low band with no dimension at its worst; anything else, a reviewer failure included, falls back to an unapproved `Waiting(approval)` whose comment names why. Details in `docs/spec-decisions.md`.

### 6.6 Waiting and parked

The spec retries without a cap and dispatches again any issue still in an active state. Loop adds two claim states, stored in the run store and excluded from dispatch and continuation retries:

- **`Waiting(reason)`:** verification running, approval pending, an `ask` pending, a reaction pending. The issue is not dispatched while it waits, whatever its tracker state.
- **`Parked(reason)`:** budget exhausted (the opt in per attempt token ceiling of M1 included, which parked with `token_cap` until m2-20 folded it into `budget`), verdict `unverified`, `max_attempts` reached, too many attempts without progress (`max_continuations`, M1), a rotation past `context.max_rotations` (`max_rotations`, m3-11), or two rotations in a row without progress (`split_issue`, m3-12, with the finding "split this issue" in a comment on the issue, section 8.4). The issue is not dispatched again until `loop resume <issue>`, or until its tracker state or labels change.
- **`retries.max_attempts`** per issue, default 5. The spec has no retry cap; this is recorded in `docs/spec-decisions.md`.
- **`retries.max_continuations`** per issue, default 3: normal exits in a row that leave the issue's state and labels unchanged before it parks with `max_continuations`. Recorded in `docs/spec-decisions.md`.
- **`move_to` per adapter.** Linear and Jira move to a state. GitHub only has open and closed, so `move_to` is a label change: remove the routing label (`ready-for-agent`) and add `needs-review`. The issue becomes unroutable, and the spec's reconciliation stops the worker without cleaning the workspace.

### 6.7 Reactions after delivery starts

Learned from Composio's Agent Orchestrator: work does not end when a PR opens.

```yaml
reactions:
  ci_failed:          { action: send_to_agent, retries: 2 }
  changes_requested:  { action: send_to_agent, escalate_after: 4h }
  merge_conflict:     { action: send_to_agent }
  approved_green:     { action: notify_human }
```

Each reaction resumes the issue's workspace with a continuation turn, then verifies again.

### 6.8 Budgets and the ledger

- Budgets per attempt, per issue and per day (a rolling 24 hours), in tokens and USD, for every runner (claw's `maxBudgetUsd` idea). At 80 percent Loop warns; at 100 percent it stops the attempt cleanly and parks the issue (`Parked(budget)`, section 6.6).
- Token thresholds landed in M1 (m1-38): every `budgets.token_threshold_every` tokens an attempt uses (default 10,000,000, cache reads included), Loop records a `token_threshold` event with the spend so far, logs it and runs the optional `hooks.on_token_threshold` command, and the attempt goes on. With Claude Code, a running estimate from the turn's per message usage feeds thresholds before the turn ends. A threshold only tells, it never stops anything.
- The per attempt token ceiling, `budgets.per_attempt_tokens`, is opt in: it has no default, and when set it parks with `budget` (`token_cap` before m2-20) on real usage only. Why: nobody can know in advance how many tokens an issue needs. On Loop's first self run (m1-13) an attempt parked on the then default 2,000,000 cap after one turn of 5,220,740 tokens (97 percent cache reads, $2.62) while another finished the same issue in one turn of 7,166,570 tokens ($3.38), and 107 hand run issue sessions on two other projects used a median of 5 to 18 million tokens, a 90th percentile of 45 to 50 million and a maximum above 100 million. No fixed cap fits that spread. m2-20 folded `token_cap` into `budget`: the key is unchanged, it is the per attempt token budget, and like every budget it warns at 80 percent.
- Token accounting follows the spec (absolute totals, no double counting). Manager agent usage comes from Koog's tracing.
- These are spend budgets. Context budgets (how full a window gets) are separate, see section 8.
- A ledger line per turn (JSONL) and a row per attempt: tracker, kind, runner, model, template, attempt, tokens, cost, wall time, checks, guards, verdict, human touches.
- `loop report --since 7d`: issues delivered by kind and tracker, delivered with zero human touches, cost per delivered issue, refutation reasons, comparison by runner, model and template.

### 6.9 Resources

Ported from the manager skill:

- Declared under `resources:`: kind (`android-device`, `avd`, `mac-app`, `display`, other), id, what it can and cannot test, restore list, read only check.
- Agents ask with `request_resource` and release with `release_resource`. Locks live in the run store; grants are in queue order.
- Limits: at most 2 Android devices and emulators together, at most 2 self-managed iOS simulators.
- With no Android device connected, Loop boots a configured AVD and grants it.
- After a release, Loop runs the read only check and asks the agent to fix any difference.
- Resource providers (Android, iOS simulator) are plugins.

### 6.10 Tools Loop gives to agents

| Tool | Behavior |
|---|---|
| `ask(kind, question, options, recommendation)` | Engineering, design and product questions go to manager agents and are answered within the call. Human questions return `pending`; the issue moves to `Waiting(ask)` (section 6.6), like the Elixir implementation's `blocked`, and resumes with the answer. |
| `request_resource(name)` / `release_resource(name)` | See resources. |
| `submit_result(result)` | Ends the attempt and starts verification. |
| `file_followup(title, body)` | Creates a follow-up issue in the parent's tracker, needing triage. |

Tracker adapters add their own host-side tools (the spec's `agent_tool_specs`). In Loop mode, calls that move an issue to a handoff or terminal state are intercepted (section 6.0).

### 6.11 Manager agents (Koog)

Fresh, small calls with structured input and output, on models chosen per role, any provider:

- **experts** (engineering, design, product) answer `ask`;
- **triage** marks follow-ups and vague issues `needs-info`, sets priority, estimates the scope (files, modules, folders) to avoid parallel conflicts;
- **reviewer** grades acceptance criteria during verification;
- **retro** (later) reads the ledger and proposes changes to templates, contracts and config as a PR you review.

## 7. The result an agent submits

```json
{
  "status": "done | blocked | needs_info | failed",
  "deliverable": {
    "type": "pull_request | files | report | external_action",
    "branch": "loop/gh-454-block-x-com-alias",
    "pr_url": null,
    "paths": [],
    "action_plan": null
  },
  "summary": "one or two sentences",
  "acceptance": [
    { "criterion": "text from the issue", "proof": "check | review | human", "ref": "evidence file or test name" }
  ],
  "residual_human_steps": [],
  "followups": [],
  "notes_for_next_attempt": ""
}
```

Only the fields of the kind's deliverable are required. An `action_plan` names the target, the exact content and the undo step. Runners without tool support write the same JSON to `.loop/result.json` in the workspace.

## 8. Context and tokens

Context management is a core part of Loop, not a runtime detail. It covers every model call Loop makes or drives: worker sessions, manager agents and reviewers.

### 8.1 Two different budgets

- **Context budget:** how full a model's context window is at a given moment (its occupancy). This section.
- **Spend budget:** cumulative tokens and USD per attempt, issue and day (section 6.8).

They are tracked separately. A session can be cheap and still dangerously full, or empty and expensive.

### 8.2 The 25 percent rule

No model context goes above 25 percent of its window, and Loop aims to stay under 20 percent.

```yaml
context:
  soft_limit: 0.20        # rotate at the next turn boundary
  hard_limit: 0.25        # stop now and rotate
  prompt_limit: 0.05      # the rendered prompt alone
  tool_output_tokens: 2000
  windows:                # Loop ships defaults, overridable per model
    anthropic/claude-opus-5-5: 1000000
    openai/<model>: 400000
```

With a 1M window that is 200k soft and 250k hard; with a 200k window, 40k and 50k. The limits are fractions, so the rule holds whatever model a kind uses.

The section is parsed and validated from M1 (m1-17), with `max_rotations: 5` too, and enforced from M3. Validation rules and defaults are in [spec decisions](spec-decisions.md#context-config-and-prompt-slot-sizes-extension-m1-17).

### 8.3 Measuring occupancy

- **Occupancy is the input size of the latest model request in the session**, cached tokens included, not the cumulative total. Each runner maps it from its runtime into a `context_usage` event: per-message usage for Claude Code, per-turn usage (`last_token_usage`) for the Codex app-server, the `last` usage of `thread/tokenUsage/updated` on the session's thread, its model `<modelProvider>/<model>` of the `thread/start` answer and its window from `context.windows` (m3-23, [spec decisions](spec-decisions.md#codex-runner-context-occupancy-and-rotation-m3-23)). Spend keeps using the absolute totals, as the spec requires.
- Occupancy covers the main session. A subagent's window is its own and Loop cannot see it yet: the gap is recorded in [spec decisions](spec-decisions.md#claude-code-context-occupancy-extension-m3-08), not enforced.
- A runner that cannot report occupancy says so in its capabilities. Loop then enforces only the prompt limit and a turn cap, and `loop status` and the startup log report the weaker guarantee (m3-09). `loop doctor` is not built and stays out of M3.
- Peak occupancy per session goes into the ledger, so the limits can be tuned from data.

### 8.4 Rotation instead of compaction

Loop rotates sessions long before a runtime's own compaction would trigger:

- **Soft limit crossed:** at the end of the current turn, Loop ends the session and starts a fresh one in the same workspace with a session brief (section 9).
- **Hard limit crossed:** Loop interrupts the turn and rotates. The workspace persists, so nothing on disk is lost.
- A rotation is not a retry and not a failure. It is counted, traced, and capped (`max_rotations`, default 5 per attempt).
- **Soft limit, since m3-11.** Checked at the end of a turn that completed normally when the attempt would run a next turn, for a runner that declares occupancy and a session whose window is known: the session's latest `context_usage` at or above `soft_limit` times the window rotates it. Loop stops the session and opens a new one in the same workspace; a rotation never respawns with `--resume`, the new runtime session starts empty, and its first prompt is the template rendered with `loop.retry_kind` = `rotation` and `loop.session_brief`, with the issue as the tracker returns it at the rotation (the issue Loop holds when that read fails, with a warning on the operator log), the findings of the attempt's latest verdict (none when it is not a refutation, the dispatch findings only before any verdict of the attempt) and the notices of the requests Loop could not apply in the turn that just ended (bl-132). A failed `stop()` records `session_stop_failed` and ends the attempt as an error retry, so no second process starts beside one that may still live. No `max_attempts` slot, no backoff; turns keep counting across the sessions of an attempt against `agent.max_turns`, and the closed sessions' spend is carried, so the attempt's totals never drop. The `session_rotated` event, the ledger's `rotations` per turn line and `loop show --cost` count it; `session_rotated` is recorded when the new session's first turn starts, and at once when the rotated prompt fails to render, so a template error after the stop keeps the rotation in the trace before the attempt ends. A wake the old session reads at its stop counts in the attempt's spend. The ledger's and `loop show`'s peak occupancy is per session, as the 25 percent rule is: a new session's peak starts from zero (bl-132); a rotation past `max_rotations` parks the issue with `max_rotations`. Details in [spec decisions](spec-decisions.md#soft-limit-rotation-extension-m3-11).
- **Hard limit, since m3-12.** Checked on every `context_usage` during a turn on a session, for a runner that declares occupancy and a model whose window is known: at or above `hard_limit` times the window, Loop asks the session to interrupt the turn at once (`AgentSession.interrupt()`; Claude Code gets a stream-json interrupt request and ends the turn with a `result` line that carries its usage and cost, so the spend counts), keeps recording the turn's events until its terminal event, which is not a failure, or 10 seconds after the interrupt request, then cancels the runner's flow, which stops the agent's process group, and rotates as for the soft limit with `reason` = `hard_limit`. The workspace persists, so nothing on disk is lost. The interrupted turn records `turn_completed` with `interrupted` = `hard_limit` and its ledger line `interrupted`: it is not a failed turn and takes no `max_attempts` slot. **Since bl-136** the 10 seconds are a deadline counted from the interrupt request: no event the runtime writes after it restarts them, so a runtime that ignores the interrupt but keeps writing is cut on time. When the deadline cuts the turn, Loop records `interrupt_grace_expired` on it with `grace_ms`, the session's latest occupancy and the latest usage known, so the lost usage is no longer silent, and `loop trace` prints it in the attempt's timeline. Its queued tool requests are applied as after any turn, then the rotation follows, with the same rules: never `--resume`, a failed `stop()` records `session_stop_failed` and starts no second session, and it counts against `max_rotations`.
- Rotations without progress (no new commit, no change to the working tree, no check improving) mean the issue is too big for one session: the issue is parked with the finding "split this issue" (section 6.6); from M6, triage proposes a split. **Since m3-12:** progress between rotations is a new commit on the issue's branch since the previous rotation (the workspace's HEAD at the attempt's first prompt for the first), or a verification of the attempt with fewer failing checks than the previous one. **Since bl-136** a working tree changed since the previous rotation (since the attempt's first prompt for the first) is progress too, so a healthy agent interrupted twice before its first commit does not park: it is measured on the content of the tracked and untracked files git does not ignore, never the index, so edits under the gitignored `.scratch/` and a `git add` alone do not count, and the read is bounded and cancellable (bl-149). The second rotation in a row without progress starts no session: the attempt ends and the issue parks with `split_issue`, takes no `max_attempts` slot, and Loop comments "split this issue" on it. A workspace that is not a git work tree, or whose HEAD or working tree Loop cannot read, counts as progress, so Loop never parks on a fact it did not read; since bl-149 a failed read is logged once per attempt. Details in [spec decisions](spec-decisions.md#hard-limit-and-split-issue-extension-m3-12).

### 8.5 The prompt has its own budget

The rendered prompt must fit under `prompt_limit`. It is built from slots, filled in priority order:

| Slot | Rule when over budget |
|---|---|
| Template and rules | Never cut. If the template alone is over budget, preflight fails. |
| Issue | Long descriptions are cut, with a pointer to the full text. |
| Verify findings | Newest first, older ones summarized. |
| Session brief | Built from facts, newest first, capped. |
| Lessons | Most relevant first, until the slot is full. |
| Context sources of the kind | Passed as file pointers the agent reads, inlined only when small. |

Tokens are counted with the model's tokenizer when available, otherwise with a conservative estimate. The size of every slot is recorded in the trace.

The slot sizes are recorded from M1 (m1-17), in characters, on the `BuildingPrompt` event that `loop trace` prints; nothing is cut before M3. How the issue slot is measured is in [spec decisions](spec-decisions.md#context-config-and-prompt-slot-sizes-extension-m1-17).

**Since m3-13** the prompt is measured in tokens against `context.prompt_limit` of a window, for every runner and for the merge risk reviewer. Cutting slots is m3-14, below. Details in [spec decisions](spec-decisions.md#prompt-tokens-and-prompt-limit-extension-m3-13).

- **The counter.** The model's tokenizer when one is available offline, otherwise a conservative estimate, one token per 3 characters, rounded up. No tokenizer of the models Loop runs is available offline on the JVM, so every count is the estimate for now, and the trace names the method (`token_counter=estimate`).
- **The window.** The `context.windows` entry of the runner's configured model, its key or the key ending with `/<model>` (Claude Code's `claude-opus-5-5` finds `anthropic/claude-opus-5-5`). A runner with no known model, such as `command`, or a model no key names, such as the alias `sonnet`, uses the smallest window in `context.windows`, so the limit is never looser than for any configured model. The limit is `prompt_limit` times the window, rounded down.
- **Slot sizes in tokens.** `BuildingPrompt` gains `prompt_tokens`, a `slot_<name>_tokens` beside each `slot_<name>_chars`, `prompt_limit_tokens`, `over_limit` and `token_counter`, and `loop trace` prints them.
- **Preflight.** It renders the template with an empty issue and no optional slot; when that alone is over the limit, preflight fails with `invalid_config` naming the tokens and the limit.
- **At dispatch.** A prompt over the limit is recorded with `over_limit` = `true` and a human log line, and sent whole until m3-14.
- **The merge risk reviewer.** Its prompt (issue, submitted result, diff, code facts) is counted against `prompt_limit` of the reviewer model's window. The diff is cut to fit, with a line saying how many characters were left out and the merge base and head to read it from, and `merge_risk_assessed` records `prompt_tokens`. A prompt that cannot fit even with an empty diff falls back to `Waiting(approval)` naming the reason, as other reviewer failures do.

**Since m3-14** a prompt over its limit is filled slot by slot in the order of the table, each slot taking the room the slots before it left, so a later slot is cut first. The attempt's first prompt and the first prompt of a rotated session are filled; a prompt within its limit is sent as rendered. Details in [spec decisions](spec-decisions.md#prompt-slots-filled-and-tool-outputs-trimmed-extension-m3-14).

| Slot | Rule since m3-14 |
|---|---|
| Template and rules | Never cut (preflight fails when it alone is over, m3-13). |
| Issue | The description keeps its longest start that fits, at a line end, then a note with the characters left out and a pointer to the full text: the issue's URL, else its issue file. |
| Verify findings | The head (the check and its exit code) and the newest lines stay, the older lines are summarized in one line. With no room for the head, one line says the findings are left out. |
| Session brief | Every part keeps its heading and its newest lines, each part cut to the same share: the first lines of a part listed newest first, marked `(cut to fit the prompt limit)`, and the first line and the last lines of the checks and the agent's notes, whose newest facts come last, marked `(older lines cut to fit the prompt limit)`. |
| Lessons | Empty until M6, size 0. |
| Context sources of the kind | Empty until M4, size 0. |

- **Recorded.** `prompt_chars`, the slot sizes and `prompt_tokens` are those of the prompt sent, `over_limit` says whether it is still over, and a prompt that was over adds `cut_<slot>_tokens` for each slot, which `loop trace` prints last. A rotated session's prompt that was over records its budget and cuts on its `session_rotated` event. The human log names what each slot lost.

### 8.6 Context sources per kind

Each kind declares where its context comes from, so non-code issues get what they need without bloating the prompt:

```yaml
kinds:
  content:
    context:
      files: [docs/brand/voice.md, posts/published/]   # pointers, read on demand
  code:
    context:
      files: [ARCHITECTURE.md, docs/technical/patterns/]
```

### 8.7 Tool outputs and manager agents

- **Tool outputs** (checks, tracker payloads, Loop tools) are trimmed to `tool_output_tokens`. The full output goes to evidence; the model gets the exit code, the failing items and a few lines.
- **Since m3-14.** Three outputs reach the model trimmed to `tool_output_tokens`, the start of the text kept at a line end with a note saying how much is left out and where the full text is:
  - the verify findings, whose character caps become `tool_output_tokens`: the check and its exit code always, then the newest output lines that fit, at most 20; the full check output stays in the evidence file;
  - the replies of Loop's MCP tools (the tracker tools' answers and the refusals), whose full text `loop tool mcp` saves for the orchestrator to append to the attempt file as a `tool_output` record;
  - the failed tool request notices of m2-07, sent after the continuation guidance, each staying whole in its `tool_request_failed` event.

  Details in [spec decisions](spec-decisions.md#prompt-slots-filled-and-tool-outputs-trimmed-extension-m3-14).
- **Manager agents** get structured state plus file pointers, read excerpts through tools, and follow the same limits. Multi-step manager agents use Koog's history compression under the same 25 percent rule.

### 8.8 Order and caching

Templates are ordered from stable to variable (rules, kind, issue, then session brief and findings), so prompt caching works across attempts. Loop never rewrites committed `CLAUDE.md` or `AGENTS.md`; project rules live in the template.

### 8.9 Prompt variables

The template is rendered with the spec's strict rules. Loop adds variables under `loop.*`, documented as an extension:

| Variable | Content |
|---|---|
| `issue`, `attempt` (S) | As in the spec |
| `loop.kind` | The matched kind and its deliverable |
| `loop.checks` | The visible checks, so the agent can run them first |
| `loop.context_files` | The kind's context sources |
| `loop.lessons` | Active lessons relevant to this issue's area |
| `loop.session_brief` | On a retry, on a reused workspace and after a rotation (m3-11); null on a fresh first dispatch. Built from facts in five bounded parts (section 9.2, m3-10) |
| `loop.verify_findings` | After a refutation: what failed and why |
| `loop.workspace_reused` | True when the issue's workspace already existed when the attempt prepared it (a restart, a resume, a return from `needs_info` or review, a retry), false when the attempt created it. A fact about the folder; `attempt` keeps its spec value (bl-19) |
| `loop.retry_kind` | Since m3-11: `continuation` (the retry after a normal exit), `error` (after a failure), `refuted` (after a refutation), `rotation` (the first prompt of a rotated session); null on a first dispatch. Preflight renders each. `reaction` comes with the reactions of section 6.7 |

## 9. Memory

### 9.1 Within an attempt

The runtime's own working memory, a progress file in the workspace (`.loop/progress.md`, gitignored), and rotations (section 8.4).

### 9.2 Session brief, built from facts

A session brief is what a fresh session gets after a rotation or a retry. Loop builds it from data it collects itself, not from the agent's memory:

1. the goal and the acceptance criteria;
2. `git log` and diff stat against the base (or the file list of a scratch workspace);
3. the latest result of every check, and the last verdict and findings;
4. counters: attempts, rotations, spend so far;
5. last, the agent's own notes (`notes_for_next_attempt`, the progress file), marked as unverified.

Since m3-10 the brief is the template variable `loop.session_brief`, set on a retry and on a reused workspace, and since m3-11 after a rotation. Each part is bounded in characters, newest first (4000 for the goal, 20 commits and 40 files then 3000 for the workspace, 3000 for the checks, 500 for the counters, 3500 for the notes); a part Loop cannot read is one line saying so, never a failed render. Rotations count those of the current attempt (m3-11), and cutting the slot to a token budget is m3-14. The parts, their sources and bounds are in [spec decisions](spec-decisions.md#session-brief-extension-m3-10).

### 9.3 Across issues: lessons with a source

Lessons are useful and dangerous: one wrong lesson reaches every prompt in its area. So each lesson has:

| Field | Meaning |
|---|---|
| `source` | The attempt, answer or retro that produced it |
| `area` | Module, folder or topic it applies to |
| `status` | `candidate`, `active` or `retired` |
| `created_at`, `last_confirmed_at`, `expires_at` | Expires after 60 days unless confirmed again |

- A lesson from a refuted attempt stays a `candidate` until a later verified attempt in the same area confirms it, or you approve it.
- A lesson is retired when it expires, when a verified attempt contradicts it, or when you retire it.
- Each prompt records which lessons it used, so a bad lesson can be traced to every outcome it influenced.
- `loop lessons` lists, approves and retires lessons. The number of active lessons per area is capped.

One summary per delivered issue (what changed, decisions, traps) is kept the same way, searchable by area.

### 9.4 The manager

No transcript. State lives in SQLite (section 10).

## 10. Observability

Everything that happens in Loop can be retrieved and analyzed afterwards: what was decided, what each model saw, what each agent did, what each check said, and what it cost. Nothing is sampled.

### 10.1 One identity for everything

Every record carries the ids of its place in the hierarchy: `run_id`, `issue_id` (namespaced by tracker), `attempt_id`, `session_id` (S), `turn_id`, plus an OpenTelemetry `trace_id` and `span_id`.

```
run
 └ issue (gh:454)
    └ attempt 2
       ├ phase PreparingWorkspace ── hook after_create
       ├ phase BuildingPrompt ────── slots, sizes, lessons used
       ├ session 1 ── turn 1..n ──── messages, tool calls, usage, occupancy
       ├ rotation
       ├ session 2 ── turn 1..n
       ├ verify ──── checks, holdouts, guards, review, verdict
       └ delivery ── approval, merge, tracker transitions
```

### 10.2 What is recorded

| Area | Events |
|---|---|
| Orchestrator | Poll ticks, candidates and why each was skipped, claims, dispatches, retries and delays, reconciliation outcomes, stalls |
| Config | Every reload with the `WORKFLOW.md` hash and diff, preflight results; each attempt records the config version it ran with |
| Trackers | Every request (operation, duration, count, error) and every write |
| Workspaces | Creation, each hook (command, exit code, duration, output), cleanup |
| Prompts | The full rendered prompt, the size of each slot, the lessons used |
| Runners | The raw runtime stream: agent messages, tool calls with arguments and results, approvals, usage, occupancy, rotations |
| Loop tools | Every call and its answer (for `ask`: which expert, which model, the answer) |
| Verification | Each check (command, exit, duration, evidence path, fingerprint), guards fired, review input and output, verdict |
| Delivery | Approvals asked and given (who, when), merges, tracker transitions |
| Budgets | Spend per call, warnings, stops |
| Manager agents | Every Koog call: model, prompt, response, tokens, latency |
| Human channel | Notifications, questions, answers, time to answer |
| Resources | Requests, grants, waits, releases, read only check results |

### 10.3 How it is stored

- **Where it lives:** one store per machine in `~/.loop/`, shared by every Loop process on that machine, never inside a repository. Every record carries a `project_id`. Machine-wide limits (device locks, the global daily budget, a global concurrency cap) are transactions in this store. See [ADR 0001](adr/0001-one-process-per-project-shared-machine-store.md). The global daily budget and concurrency cap landed in m3-16, set in the machine level `<LOOP_HOME>/config.yaml` ([spec decisions](spec-decisions.md#machine-limits-extension-m3-16)).
- **Event store**, the source of truth: append-only, local, a SQLite index plus one compressed JSONL file per attempt for raw runner streams and prompts.
- **OpenTelemetry traces and metrics** for the same hierarchy. Koog 1.0 already emits OpenTelemetry for the manager agents, so they land in the same trace. An exporter plugin sends everything to any OTLP backend (Jaeger, Grafana Tempo, Langfuse, Phoenix, or another).
- **Logs** in the spec's `key=value` format (spec section 13), carrying the trace ids.
- **Redaction** of secrets before anything is written (`$VAR` values, token patterns).
- **Retention** configurable: by default raw streams for 90 days, the ledger and verdicts forever.

### 10.4 How it is retrieved and analyzed

- `loop trace <issue>`: the full timeline of an issue across attempts, sessions and rotations.
- `loop resume <issue> [--workflow WORKFLOW.md]`: unpark a Parked issue so it can be dispatched again on the next tick. Exit 0 resumed (`Resumed t-1, parked since <time> (<reason>). It can be dispatched again on the next tick.`), 1 startup failure, 2 unknown issue (`loop resume: no issue "t-9" in the tracker at <path>.`), 3 store failure, 4 not parked (`loop resume: t-1 is not parked. Nothing changed.`), 5 inside an attempt (m2-24, `LOOP_ATTEMPT_ID` set, nothing changed).
- `loop approve <issue> [--workflow WORKFLOW.md]`: approve the delivery of a verified result that waits as `Waiting(approval)` under the `human` delivery policy (m2-24); the running Loop, or the next one to start, delivers it on its next tick. Exit 0 approved (`Approved t-1, waiting since <time>. Loop moves it to review on its next tick.`) or already approved, 1 startup failure, 2 unknown issue, 3 store failure, 4 not waiting for approval (`loop approve: t-1 is not waiting for approval. Nothing changed.`), 5 inside an attempt (`LOOP_ATTEMPT_ID` set, nothing changed).
- `loop stop [path-to-WORKFLOW.md]`: stop the `loop run` of the project, ending every running attempt and keeping every workspace. Exit 0 stopped (`Stopped run <run8> (pid <pid>) in <n>s, <k> attempts canceled, workspaces kept.`), nothing running (`No run in progress for project <id>. Nothing to stop.`) or a stale run file removed (`Removed a stale run file for pid <pid>, that process is gone. Nothing to stop.` or `Removed a stale run file that could not be read. Nothing to stop.`), 1 outside a project, 3 unreadable run file (`loop stop: cannot read the run file at <path>: <sentence>.`) or a run file that names a pid that is not the run holding it (`loop stop: the run file at <path> names pid <pid>, which is not the run that holds it. Nothing was stopped.`), 4 SIGTERM could not be sent (`loop stop: could not send SIGTERM to pid <pid>.`) or the run did not exit within 15s of SIGTERM (`loop stop: run <run8> (pid <pid>) did not exit within 15s of SIGTERM. The run file is kept and its agent processes may still be running.`). `loop run` refuses to start when a run is already in progress for the project (`loop run: a run is already in progress for project <id> (pid <pid>). Run loop stop first.`, exit 1).
- `loop show <attempt> --prompt | --events | --checks | --cost`.
- `loop replay <attempt>`: re-run verification on the recorded workspace fingerprint, or re-render the prompt with the current config and diff it with the original.
- `loop query "<sql>"`: SQL over the event store.
- `loop report`: aggregates (section 6.8).
- Later, the spec's HTTP API extension serves the same data, and the retro agent reads it to propose improvements.

Observability starts in M1: the event store and the ids are the first thing every other part writes to.

## 11. Permissions and sandbox

### 11.1 First milestones (M1 to M4)

What Loop does from the start:

- **Path safety** as in the spec: the agent works in its workspace, under the workspace root, with sanitized names.
- **Runtime policies** generated from config, best effort: the Codex approval and sandbox policies, Claude Code permission settings, the equivalents for other runners.
- **No tools for risky moves:** in Loop mode, agent calls that change an issue's state are intercepted and performed by Loop only after verification, and external actions are only submitted as an `action_plan` that Loop runs after approval.
- **Secrets** as in the spec: `$VAR` only, never logged, tracker credentials removed from the agent's environment (`secret_environment_names`). The agent gets your environment minus tracker credentials, every `$VAR` name referenced in WORKFLOW.md, and ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN. Basic names such as HOME, PATH, USER and the locale are kept even when referenced, since they are not secrets. Holdout checks stored outside the workspaces.
- **Limits:** wall time, turns, context, tokens and USD per attempt.
- **Everything traced** (section 10).

Known gap, accepted for now: in local mode, an agent runs with your environment (your `gh` login, SSH keys), so Loop cannot prevent a misbehaving agent from pushing to the base branch or merging. Until M5, Loop runs only on trusted repositories. Branch protection on the base branch is a cheap safeguard, but GitHub only offers it on public repositories or with GitHub Pro, so it is not available on the private repositories today. Decided on 2026-11-11: no branch protection in M2. Agents keep no GitHub access, Loop pushes and opens pull requests host side, and auto merge rests on Loop's own conditions until M5.

In M1, agents get no GitHub access at all: they commit on a local branch `loop/<issue>` in their worktree, and the Claude Code settings Loop generates deny `git push`, `gh` and history rewrites. You review and merge locally. These deny rules are a guardrail, not a sandbox. In M1 the Claude Code runner uses bypassPermissions, so it never stops on a prompt, and the generated deny rules still block git push, gh and history rewrites.

### 11.2 Permissions hardening (M5, the next evolution)

Enforcement in four layers, instead of relying on the runtime alone:

1. **Runtime policy**, generated per runner (already there, best effort).
2. **Sandbox for every worker, local mode included:** a container or the OS sandbox, with a stripped environment and only the workspace writable.
3. **Server-side limits:** a separate worker token that can push branches and open pull requests but cannot merge, plus branch protection on the base branch.
4. **Audit after every attempt,** as part of verification: no ref other than the attempt's branch moved, nothing pushed to the base, no protected file touched, no network call outside the allowlist. Any violation refutes the attempt and alerts you.

Plus:

- **Approvals in the middle of a run:** a risky action requested by an agent pauses the issue and asks you through the human channel, with a timeout.
- **Deny list** (no merge, no push to the base, no force push, no `release/*`, no `--no-verify`, no `git stash`) enforced by layers 2 to 4, not only stated in the prompt.
- **Optional guard model** for gray areas the deny list does not cover.

## 12. Plugins

The plugin API is a small Kotlin module that the core depends on and plugins implement:

| Interface | Core implementations | Plugins |
|---|---|---|
| `TrackerAdapter` (spec section 11, plus optional writes for comments, labels, follow-ups) | `files` | `github`, `linear`, `jira`, `gitlab`, `asana` |
| `AgentRunner` | `codex-app-server`, `command` | `claude-code`, `acp`, `gemini`, `koog` |
| `CheckType` | `command`, `file_exists`, `file_matches`, `diff_policy` | `http`, `screenshot`, `maestro` |
| `DeliveryExecutor` | `pull_request` (via the tracker or `gh`), `report`, `files` | external actions per system |
| `HumanChannel` | `terminal` | `telegram`, `slack`, `http` (the spec's optional API and dashboard) |
| `ResourceProvider` | none | `android`, `ios-simulator` |

Two ways to write a plugin:

- **In process:** a module on Loop's classpath or a JAR in `<LOOP_HOME>/plugins/` (each JAR with its own class loader), discovered with Java's `ServiceLoader` through a `META-INF/services` entry, selected by `kind` in config. Runners first (m3-04): a `RunnerProvider` in `plugin-api` gives its `kind` and builds an `AgentRunner` from its own `runners.<kind>` section and the host's agent env policy. An unknown kind fails preflight with `invalid_config` naming the kinds found, and two providers of one kind fail startup naming both. See [runner plugins](spec-decisions.md#runner-plugins-extension-m3-04). Trackers next (m3-05): a `TrackerProvider` gives its `kind`, its default states and settings reader, and builds the `TrackerAdapter`; `files` and `github` register the same way. See [tracker plugins](spec-decisions.md#tracker-plugins-extension-m3-05).
- **Out of process:** any executable that speaks a small JSON-RPC protocol over stdio, for plugins written in another language. One generic adapter per interface wraps it.

Each plugin has a profile (its config keys, defaults, secrets and limits), as the spec asks of tracker adapters, and contract tests that every implementation of an interface must pass. Since m3-05 runner and tracker plugins declare it as a `PluginProfile`, which Loop checks their sections against at startup, see [plugin profiles](spec-decisions.md#plugin-profiles-extension-m3-05).

## 13. Configuration example

`WORKFLOW.md` at the project root:

```markdown
---
# Symphony keys
polling: { interval_ms: 30000 }
workspace: { root: ~/loop/workspaces/jublu }
hooks:
  after_create: |
    git clone --quiet git@github.com:Jublu-Inc/jublu.git . && cp ~/loop/secrets/jublu/local.properties .
  before_run: |
    git fetch --quiet origin
    git checkout --quiet "loop/$LOOP_WORKSPACE_KEY" 2>/dev/null || git checkout --quiet -b "loop/$LOOP_WORKSPACE_KEY" origin/main
agent: { max_concurrent_agents: 5, max_turns: 20 }
codex: { command: codex app-server, thread_sandbox: workspace-write }
tracker: { id: gh, kind: github, provider: { repo: Jublu-Inc/jublu, token: $GITHUB_TOKEN },
           required_labels: [ready-for-agent], active_states: [open], terminal_states: [closed] }

# Loop extensions
trackers:
  - { id: local, kind: files, provider: { dir: .loop/issues } }
retries: { max_attempts: 5, max_continuations: 3 }
runner: { kind: claude-code }                    # the project's runner; a kind can set its own
runners:                                         # settings of each runner, by runner.kind
  command: { command: ./agent.sh }               # the command runner of the walking skeleton (m1-02)

kinds:
  code:
    match: { default: true }
    ready: { require_sections: [Acceptance criteria] }
    runner: { kind: claude-code, model: opus, effort: high }
    template: .loop/templates/issue.md          # "Run /issue {{ issue.identifier }}, then submit_result"
    verify:
      checks:
        - { type: command, run: "./gradlew testStagingUnitTest" }
        - { type: command, run: "./gradlew lintStaging detekt" }
      holdout: []
      guards: { protect: ["**/src/test/**", "detekt-baseline.xml"], deny: [deleted_tests, added_skips] }
      review: { role: reviewer }                 # from M6, optional until then
    delivery: { policy: human, on_verified: { move_to: { remove_labels: [ready-for-agent], add_labels: [needs-review] } } }
  content:
    match: { label: content }
    runner: { kind: codex-app-server }
    template: .loop/templates/content.md
    verify:
      checks:
        - { type: command, run: "! grep -rnP '\\x{2014}|\\x{2013}' out/" }
      review: { role: reviewer, rubric: .loop/rubrics/voice.md }   # from M6
    delivery: { policy: human }

roles:
  experts:  { engineering: anthropic/claude-opus-5-5, design: anthropic/claude-sonnet-5-5, product: anthropic/claude-opus-5-5 }
  triage:   anthropic/claude-haiku-5-5
  reviewer: openai/<a model different from the worker>

reactions:
  ci_failed: { action: send_to_agent, retries: 2 }
  changes_requested: { action: send_to_agent, escalate_after: 4h }

budgets: { per_attempt_usd: 10, per_issue_usd: 25, per_day_usd: 100 }
context: { soft_limit: 0.20, hard_limit: 0.25, prompt_limit: 0.05, max_rotations: 5 }
observability: { store: ~/.loop/, retention_days: 90, otlp: { endpoint: $OTLP_ENDPOINT } }   # machine level folder, LOOP_HOME overrides
resources:
  - { kind: avd, id: wallpilates_api35, cannot: [real calls, sms] }
channel: { kind: terminal }
---
You are working on {{ issue.identifier }}: {{ issue.title }}.
...
```

Secrets and holdouts live outside the repository, in Loop's own directory (`~/loop/`).

## 14. Tech stack (confirmed in m1-01)

Confirmed on 2026-11-06 in issue m1-01. Versions live in `gradle/libs.versions.toml`; a dependency is declared there only once a module uses it.

- **Kotlin 2.4.21 on JVM toolchain 21**, with coroutines. Amendment: the proposal named no versions. JVM 21 is a long term support release, available locally (Corretto 21) and in CI (Temurin 21).
- **Gradle 9.7.0, Kotlin DSL**, with convention plugins in `build-logic/` and a version catalog. 9.7.0 is the newest Gradle that Kotlin 2.4.21 supports; convention plugins keep the module build files short and identical.
- **JUnit Jupiter 6 with kotlin-test** for tests. Amendment: the proposal named no test framework; JUnit is the JVM default and kotlin-test gives Kotlin assertions on top.
- **ktlint through `org.jlleitschuh.gradle.ktlint` 14.2.0**, wired into `./gradlew build`. Amendment: detekt is deferred until detekt 2.0 is stable, because 1.23.8 targets Kotlin 2.0.
- **SQLite through sqlite-jdbc, with plain JDBC** in the `store` module for the run store and ledger. Amendment: SQLDelight was dropped in m1-02. The walking skeleton's store is one `events` table with two indexes and a schema version in `PRAGMA user_version`, which a code generator does not pay for. Revisit SQLDelight when the store has more than three tables or needs a migration beyond `user_version`. Revisited in m2-02 at four tables: plain JDBC kept, see [store access](spec-decisions.md#store-access-plain-jdbc-kept-m2-02).
- **liqp as the template engine**, in strict mode behind Loop's own `TemplateEngine` seam. Amendment: the proposal left the choice open. The spike showed liqp's strict mode alone accepts an unknown filter at parse and cannot tell a null `attempt` from a missing variable, so Loop's engine adds guards (a filter scan, a fail closed null check, no `tablerow`), signed off by the user in manual QA; a strict subset written for Loop replaces it if a later issue needs loops over nullable fields. Details in [`spec-decisions.md`](spec-decisions.md#template-engine).
- **Clikt 5.1.0** for the CLI.
- **kaml 0.104.0** for YAML front matter, declared when first used.
- **Distribution through the Gradle `application` plugin and `installDist`.** Amendment: the proposal named no distribution; `installDist` gives the installed build outside the repository that M1 runs from.
- **CI on GitHub Actions**, running `./gradlew build` on every push. Amendment: not in the proposal; it keeps every branch green before review.
- **The official MCP Kotlin SDK 0.16.0** (`io.modelcontextprotocol:kotlin-sdk-server`) to expose Loop's tools to MCP runtimes, added in m2-16 in the `mcp` module. 0.16.0 is the latest release and is built with Kotlin 2.4.21, like Loop. Its SLF4J logging goes to `slf4j-nop` 2.0.18, so no command prints SLF4J notices.

Later, unchanged from the proposal and not part of the M1 build yet:

- Koog 1.0 for manager agents and multi-provider LLM clients.
- JSON-RPC over stdio for the Codex app-server (schema generated from the Codex app-server docs) and for out-of-process plugins.
- Ktor for the HTTP extension and webhooks.

### Repository layout

```
loop/
├── spec-core/          Symphony core: workflow, config, orchestrator, workspace, prompt, logs
├── plugin-api/         interfaces and profiles
├── loop-ext/           trackers registry, kinds, verify, delivery, budgets, reactions, resources, tools (never built)
├── store/              SQLite run store, ledger
├── mcp/                Loop's MCP server (submit_result), on the official MCP Kotlin SDK
├── agents/             Koog manager agents
├── plugins/
│   ├── tracker-files/          (core)
│   ├── runner-codex-app-server/ (core)
│   ├── runner-command/         (core)
│   ├── tracker-github/
│   ├── tracker-linear/
│   ├── tracker-jira/
│   ├── runner-claude-code/
│   ├── runner-acp/
│   ├── channel-telegram/
│   └── resource-android/
├── conformance/        the spec's test matrix (section 17) as tests
├── cli/                loop run, status, answer, approve, doctor, report
└── docs/               PLAN.md, spec-decisions.md, plugin guide, symphony/ (the pinned spec, vendored)
```

The `loop-ext/` module does not exist. Since M4 its parts are packages under `loop.core` (`loop.core.trackers`, `loop.core.kinds`, `loop.core.resources`, `loop.core.reactions`, `loop.core.channel`, `loop.core.replay`, `loop.core.verify`), with the orchestrator edited only at call sites.

## 15. Testing Loop itself

- **Conformance tests** from the spec's section 17 (workflow parsing, config, orchestrator, workspace, prompt, observability, CLI), run against fake trackers and runners.
- **Contract tests per plugin interface**, which every tracker, runner and check type must pass.
- **A fake runner** that applies scripted patches and returns scripted events, so the whole lifecycle runs without model calls: pass, refuted by a check, refuted by a guard, holdout failure, budget exhausted, stall, question, resource queue, reaction.
- **A playground** with seeded issues of every kind in a `files` tracker and a test GitHub repo: a good code issue, a vague one, one that tempts a test deletion, a content issue, a research issue, an ops issue against a fake system.
- The spec's **Real Integration Profile** (section 17.8) against Codex and GitHub before production use.

## 16. What ports from the manager skill

| Today (skill) | In Loop |
|---|---|
| Preflight in one Bash call | Spec preflight + `loop doctor` (gap at M4) |
| `claude --bg` per issue | `claude-code` runner plugin |
| `SendMessage` conventions in the brief | Loop tools for agents |
| "device request" chat protocol | `request_resource` / `release_resource` |
| Expert agents via the Agent tool | Koog expert agents (gap at M4, M6) |
| Triage in a subagent | Koog triage agent (gap at M4, M6) |
| `merge-check.md` | Delivery check for pull requests |
| `gate.sh` + evidence in the issue skill | Verify contracts with evidence and fingerprints |
| Waves and module conflicts | Spec scheduling + the area lock (m4-17); triage scope estimates are a gap at M4 (M6) |
| Brief extras in the config | Template + lessons (lessons a gap at M4, M6) |
| The usage script | Spec token accounting + ledger, and `loop show --cost` per attempt (no cost for Codex) |

The issue skill stays your template of choice for code issues on your projects. It needs one change: end with `submit_result` instead of messaging the manager, and never move the issue itself.

M4 retires the manager skill with the rows marked as gaps above recorded in m4-44, not built: `loop doctor` and the M6 rows. Until M6 the issue skill's own subagents play the expert role.

## 17. Milestones

Each milestone ends with real issues delivered on one of your projects. Observability is part of every milestone from the start, not added later. Prompt sizes are recorded from M1, and context limits are enforced from M3.

### M1. Symphony core in Kotlin, traced from day one
- `spec-core`, `plugin-api`, `tracker-files`, `runner-command`, a minimal `runner-claude-code` (headless, streaming JSON, one session) wired in directly, the CLI.
- Conformance tests for workflow loading, config, workspaces and scheduling, against the fake runner.
- Event store with the full id hierarchy, in the machine-level store; spec logs with trace ids; `loop trace` and `loop show`.
- The `context:` config parsed and the size of every prompt slot recorded. Nothing is cut yet.
- `docs/spec-decisions.md` for the ambiguities, with the spec commit pinned.
- Safety pulled forward from M2: `retries.max_attempts`, a minimal `Parked` with `loop resume`, token thresholds every 10 million tokens per attempt (cache reads included) that report and let the attempt go on, an opt in token ceiling per attempt (no default) that parks the issue, and `loop stop`.
- **Slicing:** vertical slices, not layers. A HITL issue first (pin the spec, confirm the stack, choose the template engine), then a walking skeleton (`loop run` reads one `todo` issue, a plain workspace, `runner-command`, events recorded, `loop trace` prints them), then thin slices that each thicken one path end to end with its conformance tests.
- **Workload:** Loop itself, in two phases.
  - **By hand:** you and Claude Code build the slices up to the bootstrap point: the walking skeleton, git worktree hooks, the Claude Code runner, and the safety items above.
  - **By Loop:** from the bootstrap point, Loop works the rest of its own backlog in Symphony mode. You review and merge each branch.
  - The running Loop is an installed build outside the repository. It changes only when you run `./gradlew installLoop`, typically after merging work Loop should use, never during a run with active attempts. Every attempt records the Loop version it ran with.
- **Loop's own backlog:**
  - Issues live in `.loop/issues/<id>-<slug>.md` in the main checkout, versioned in git. Front matter: `id`, `title`, `state`, `priority`, `labels`, `blocked_by`. Body: `## What`, `## Acceptance criteria`, `## Comments`.
  - States: `todo` and `in_progress` (active), `review` and `needs_info` (handoff states, neither active nor terminal), `done` and `canceled` (terminal).
  - The agent never edits issue files. It goes through `loop tool move <state>` and `loop tool comment`, which write to the main checkout. The `after_create` hook leaves `.loop/issues/` out of each worktree.
  - A product question: the agent comments it, commits, and moves the issue to `needs_info`. You answer and move it back to `todo`. Changes requested after review work the same way. Technical questions the agent decides and records.
  - Each attempt works on the branch `loop/<issue>`. `before_remove` removes the worktree but never deletes the branch.
  - Acceptance criteria are checkable, with the command that checks them wherever one exists. Prose is marked as a judgment call. M2 reuses these commands as visible checks.
- **Prompt template:** a dedicated one in Loop's `WORKFLOW.md`, not the issue skill. It keeps a default-fail acceptance checklist, `/tdd` for logic, evidence from real command output, a progress file, a self-review and the writing rules. It ends with a commit on `loop/<issue>` and `loop tool move review`.
- **Limits in Loop's own `WORKFLOW.md`:** `max_concurrent_agents: 2`, `max_turns: 10`. Claude Code runs on your subscription. Issues that touch the same files declare `blocked_by` on each other.
- **Issues:** written with `/to-tickets` from the PRD, for M1 only. The last M1 issue writes the M2 issues from the PRD and what M1 taught.
- **Exit:** an issue from Loop's own backlog is worked by Claude Code, through the installed Loop build, from `todo` to `review`, with its full timeline retrievable through `loop trace`.

### M2. Verification and delivery on the GitHub backlog
- Loop mode: the `code` kind, the intercepted move to the handoff state, `Waiting`, `Parked` for every reason (the minimal version lands in M1), the readiness check.
- Run store: claim states, the retry queue and sessions survive a restart.
- Verify contracts: check types, holdouts, guards, evidence, verdicts, refutation loop, rerun of a failing check once to catch flaky ones. Review stays optional until M6.
- `submit_result` and the other Loop tools over MCP for Claude Code, and `.loop/result.json`.
- Delivery policies, `loop approve`, pull request delivery with a merge check on current main, and conditional auto merge (opt in per project) once human delivery has worked.
- `tracker-github`, with label based `move_to`.
- Ledger and spend budgets per Loop process. The machine wide budget and concurrency cap (m2-21) moved to M3 on 2026-11-14.
- Carried from M1: the full raw runner stream per attempt (m1-22) and reporting a failed tool request to the agent (m1-35). Scheduled from the backlog: bl-01, bl-02, bl-06.
- **Workload:** the pilot app (an iOS app with its issues on GitHub), decided 2026-11-11. Loop's own backlog stays the first test bed (story 86) on the `files` tracker; switching its `WORKFLOW.md` to Loop mode and replaying m1 issues (m2-10) moved to M3 on 2026-11-14.
- **Exit:** a seeded shortcut (a deleted test) is refuted on the playground, and a real GitHub issue worked by Claude Code with the Loop variant of the pilot app's issue skill is delivered only with a `verified` verdict and merged by Loop with `ci: none`, its merge check being the gate.
- **Exit reached, 2026-11-14:** bl-51 refuted the deleted test on the playground. On the pilot app, an issue was worked by Claude Code with the Loop variant of its issue skill in one turn, verified by `diff_policy` and the unit suite on a dedicated simulator, approved, and merged by Loop (m2-27). What the run taught, kept in `docs/spec-decisions.md` (First real delivery on the pilot app): an iOS turn runs for most of an hour with long silences, so the stall timeout has to be raised; a skill that delegates spends about 2.5 times the tokens Loop records, because subagents are not counted (bl-71); subagents inherit the deny rules and the MCP tools, Loop's included; the operator's own MCP servers reach the agent unless `--strict-mcp-config` is passed (bl-72); a token kept out of shell startup files breaks the read commands (bl-70); and a merge check whose base has not moved reruns only `diff_policy`, so the suite runs once per verified commit.

### M3. Codex, full conformance and context control
- `runner-codex-app-server` and the rest of the Core Conformance tests; Loop tools as Codex client tools.
- Plugin loading (in process), plugin profiles and contract tests.
- Occupancy events from both runners; soft and hard limits; session rotation with session briefs built from facts; peak occupancy per session in the ledger.
- Prompt budget: slots filled in priority order, token counting.
- Moved from M2 on 2026-11-14: Loop mode on Loop's own `WORKFLOW.md` with a replay of m1 issues (m3-01, carrying m2-10, first), waiting for GitHub Actions before the merge (m3-15, carrying m2-30), and the machine wide budget and concurrency cap (m3-16, carrying m2-21).
- Claude Code continuation through one long lived process per attempt (m3-07, carrying m1-30), landing before session rotation.
- Lessons from M2 scheduled from the backlog: runner access failures (bl-39), strict MCP (bl-72), subagent spend (bl-71), the prompt after a restart (bl-19), read commands without the tracker secret (bl-70).
- **Slicing:** three lanes. Lane A is one serial chain (orchestrator, CLI host, store schema, Claude Code runner, prompt); lane B is the Codex module in new files; lane C holds small separate issues. Edits to `WORKFLOW.md` and Gradle files, protected under Loop mode, are human steps.
- **Workload:** Loop's own backlog in Loop mode for both runners; the pilot app as a second proof for Claude Code.
- **Exit:** the core conformance tests pass, and each runner delivers a real issue with no session above 25 percent of its window. For Claude Code, a Loop issue with at least one real rotation traced (m3-27); for Codex, one of Loop's own issues (m3-28), carried to M4 if Codex access is absent.
- **Codex half reached, 2026-11-17:** bl-63 was delivered by the Codex runner (`gpt-6.1-sol`, m3-28) in one turn, its one session peaking at 0.14 of a 475,000 token window, and merged by Loop as pull request #113 after `loop approve`: the merge risk reviewer exists only for the claude-code runner (bl-145), and the auto merge `ci` condition needs Actions runs (bl-146). On the same kind of small backlog fix, Codex spent 1.55M tokens in 4 minutes where the claude-code runner spent 3.55M to 7.21M in 10 to 14 minutes; the occupancy comparison at the same limits is m3-27's. What the run taught is in `docs/spec-decisions.md` (Codex exit run).
- **Claude Code half reached, 2026-11-18:** at a soft limit of 0.20 and a hard limit of 0.21, Loop's bl-132 was delivered after a real hard limit rotation (its first session interrupted at 0.2103, pull request #117), bl-136 in one turn at 0.1844 (pull request #115), and the second pilot app issue in two attempts peaking at 0.1228 and 0.0848, so no session went above 25 percent (m3-27). The figures are the main session's: the pilot app's subagents run in windows Loop does not measure. The soft limit never fired, since each attempt ended its last turn by handing off, so the hard limit below 0.25 is what keeps the rule (bl-144). Background wakes count against `agent.max_turns` (bl-155), an idle sleep of the Mac failed an attempt (bl-156), and the auto merge still needs Actions runs under `ci: none` (bl-146). What the runs taught is in `docs/spec-decisions.md` (Claude Code exit runs).
- **M3 closed 2026-11-18 (m3-29):** every M3 issue is done and nothing is carried into M4.

### M4. Parallel production, retire the manager skill
- Spec: [Loop, M4](../.loop/specs/loop-m4.md), stories 101 to 141.
- Reliability first, since Loop builds M4 itself: a default hard limit of 0.21 that keeps the 25 percent rule, wakes that are not turns, network and sleep failures that cost no attempt, a refused CI run read as not started, auto merge under `ci: none`, a merge risk reviewer for Codex, and `loop drain`.
- Multi tracker registry: `trackers:` beside the spec's `tracker:`, ids namespaced by tracker while the primary tracker keeps bare ids, workspace keys and branches, each tracker polled on its own, writes routed to the issue's tracker, a per tracker limit, and cross tracker blockers.
- A minimal area lock (bl-10), since the exit runs two code issues in parallel on one app.
- Kinds `content` and `research` beside `code`, with a kind map, context sources per kind and per kind templates. Kinds with their own runner are out of M4 (section 6.2).
- Resources with locks in the store, `request_resource` and `release_resource` for both runners, a `resource:` key on `command` checks, and the iOS simulator and Android providers. The iOS simulator provider gates the exit; Android is in scope but does not gate it.
- The human side: a terminal channel and a notification hook, `ask` for human questions answered with `loop answer`, `loop send-back`, and `file_followup` for follow ups.
- Not gating the exit: reactions (CI, review comments, conflicts, approved and green), which watch only waits that hold a pull request; `loop report`, `loop query` and `loop replay`; the OpenTelemetry export plugin behind the run's redactor; Android.
- **Slicing:** human prerequisites (m4-00) and module scaffolding (m4-01) come first. Lane A is one serial chain on the claude-code runner (reliability, trackers, kinds, resources, human side, reactions, then the verification extraction); lane B holds the providers and the exporter; lane C holds small separate issues. Every M4 issue covers one or two stories at `max_turns: 10`, puts lane A logic in a new `loop.core` package with call site edits only in `Orchestrator.kt`, states "no Gradle edit needed" in lanes B and C, and proves CI work on recorded Actions answers. Nine issues split after review (m4-46 to m4-54).
- **Backlog, scheduled from triage:** 26 backlog issues carried into M4 and closed as moved, each closing comment naming the parts not carried. Ready-for-agent backlog issues run alongside, with blockers on the M4 issues that share their files.
- **Workload:** Loop's own backlog in Loop mode on the `files` tracker builds M4; the pilot app on GitHub, beside a `files` tracker, is the exit app (decided 2026-11-18 in m3-29).
- **Exit:** a wave on the pilot app of at least 3 code issues, 2 in parallel, plus one content or research issue, delivered through Loop in one wave with GitHub and `files` trackers active at once and any restart of `loop run` recorded (m4-43). No main session above 25 percent; subagents run in windows Loop does not measure. Then the manager skill is retired with every PLAN 16 row covered or named as a gap, `loop doctor` and the M6 rows recorded as gaps (m4-44).

### M5. Permissions hardening (the next evolution)
- Section 11.2: sandbox for every worker in local mode, a worker token that cannot merge, branch protection, audit after every attempt, approvals in the middle of a run.
- **Exit:** on the playground, an agent told to merge its own PR, push to the base branch or read a holdout fails, and the attempt is refuted with an alert.

### M6. Manager agents and memory across issues
- Koog experts, triage (vague issue bounce, scope estimate, split proposals after rotations without progress), reviewer.
- Lessons with source, status and expiry; issue summaries; `loop lessons`.
- **Exit:** a week of runs where every non-human question was answered by the experts, and every lesson in a prompt can be traced to its source.

### M7. More integrations, best of n
- `tracker-linear`, `tracker-jira`, `runner-acp`, out-of-process plugin protocol.
- Fan-out (best of n across runners) and engine handoff; council review.
- **Exit:** the same issues delivered by two runners, with a published comparison.

### M8. Remote
- Container image, headless service, webhooks next to polling, Telegram or Slack channel, the spec's HTTP API extension, kind `ops` with approvals from the channel.
- Device issues are held for the next local run.
- **Exit:** issues delivered overnight with the laptop closed.

### M9. Self improvement
- Retro agent reading traces and the ledger, proposing template, contract, context and config changes as PRs; optional guard model for gray-area actions.
- **Exit:** a merged retro proposal that measurably lowers cost, occupancy or refutations.

### M10. Optional: native Koog runner
- A Koog agent with file, search, edit and shell tools as an `AgentRunner`, with its own compaction disabled in favor of Loop's rotation.
- **Exit:** it delivers a simple issue, compared in the ledger with the other runners.

## 18. Prior art

| Project | What Loop takes from it | What Loop adds |
|---|---|---|
| [OpenAI Symphony](https://github.com/openai/symphony) | The whole core: `WORKFLOW.md`, orchestrator, workspaces, hooks, retries, reconciliation, tracker contract, observability | Any runner, several trackers, verification, kinds, delivery owned by Loop |
| [claw-orchestrator](https://github.com/Enderfga/claw-orchestrator) | Checks the agent did not write, verdicts, protected files, budget caps per engine, ledger per turn, engine handoff, councils | An issue backlog as the source of work |
| [Composio Agent Orchestrator](https://github.com/ComposioHQ/agent-orchestrator) | Reactions to CI failures and review comments | Independent verification, non-code kinds |
| [Cyrus](https://github.com/ceedaragents/cyrus) | Progress and approvals posted on the issue | Budgets, verification |
| [maxtechera/orchestrator](https://awesome.ecosyste.ms/projects/github.com%2Fmaxtechera%2Forchestrator) | Non-code issues with a contract and an independent verifier | A standalone service |
| Ralph loops | Fresh sessions with memory in files and git; small issues | Orchestration, verification |
| [Paperclip](https://www.datacamp.com/tutorial/paperclip-tutorial) | Budgets and approvals across agents | A verified issue backlog instead of agent roles |

Shared physical devices across parallel issues, and a ledger comparing runners, models and templates on your own issues, were not found in any of them.

## 19. Risks

| Risk | Mitigation |
|---|---|
| The spec is a draft and changes | Pin the spec commit; track changes in `spec-decisions.md`; conformance tests catch drift |
| The Codex app-server protocol changes | Generate types from the published schema; contract tests with recorded sessions |
| Runtime CLIs change flags or output | Contract tests per runner plugin (m3-06); the Codex CLI version pinned (m3-02); a version check in `loop doctor` later |
| A headless agent hangs on a prompt | Spec stall detection and timeouts; generated non-interactive policies |
| Cost overrun | Budgets at three levels; cheap models for cheap roles; ledger |
| Shortcuts pass the checks | Holdout checks, protected files, guards, reviewer on another model, `human` delivery until trust is earned |
| Non-code issues have weak checks | Rubrics, required sources, `human` delivery by default |
| An external action goes wrong | Agents never execute them; approval and an undo step first |
| Scope creep | Each milestone exits on real issues |
| A runner cannot report occupancy | Declared in its capabilities; prompt limit and turn cap still apply; `loop status` and the startup log say so |
| Rotations lose useful reasoning | Session briefs built from facts; rotation counts and outcomes in the ledger to tune the limits |
| Wrong lessons spread | Source, status, expiry, quarantine of lessons from refuted attempts, traceable use |
| Traces grow large or leak secrets | Compressed per-attempt files, retention policy, redaction before writing |
| Agents misuse your credentials before M5 | Trusted repositories only; agents get no GitHub access, Loop pushes host side; branch protection in M5 |
| Unattended parallel runs in M4 before the hardening of M5: an agent can still move the operator's refs (bl-28) | Trusted repositories only; `human` delivery by default; the exit wave is watched; bl-28 is M5 |
| GitHub Actions refused every job for billing from 2026-11-16 until the repository went public (free minutes since) | CI work in M4 is proven on recorded Actions answers; a refused run reads as not started (m4-07); auto merge passes under `ci: none` once the merge check passed (m4-05) |
| The simulator cap meets parallel verification: two verifications or merge checks on one simulator | A `resource:` key on `command` checks takes a lock of that kind, and a wait in the queue is never a failed check (m4-26) |
| `Orchestrator.kt` (5,327 lines, about 70k tokens) is too big for one session's window | New logic goes in new `loop.core` packages with call site edits only; lane A runs on the claude-code runner; verification extracted before `loop replay --verify` (m4-54) |
| Codex runs known safe reads with no approval request, so its holdout read deny is not enforced (bl-134) | Writes under the holdout folder are refused; bl-134 stays a human spike, and until it is decided a Codex workflow with holdout checks does not auto merge |
| Subagents run in windows Loop does not measure | The 25 percent claim is for main sessions only; subagent spend counted since bl-71; the gap stays named in the exit |

## 20. Open questions

| Question | Default if you do not decide |
|---|---|
| Do you have Codex access for M3 (the spec's reference runner)? | Decided 2026-11-16 (m3-02): a ChatGPT login, `codex login` with soule's ChatGPT account (Plus plan), on codex 0.167.0 pinned from npm. No API key. It unblocks m3-03, the enabled run of m3-24 and m3-28. The login lives in `~/.codex/auth.json`, which matters for strict MCP (m3-20), see `docs/spec-decisions.md`. Closed for M4: m3-03, m3-24's enabled run, m3-26 and m3-28 were done in M3, nothing is carried |
| Which project goes first? | Decided: Loop itself for M1. For M2, the pilot app (decided 2026-11-11) |
| Which app is the M4 exit app? | Decided 2026-11-18 in m3-29: the pilot app. The iOS simulator provider gates the exit; the Android provider is in scope but does not gate it |
| Where do you answer questions in M4 to M6? | Decided for M4 in m3-29: the terminal channel and its notification hook tell you, an agent asks with `ask`, and you answer with `loop answer`. Every `ask` goes to you in M4; manager agents answer some from M6. Telegram in M8 |
| Default delivery policy | `human` for each new project and every external action |
| Public repository or GitHub Pro, so branch protection works? | Decided 2026-11-11: neither for M2, no branch protection until M5 |
| Is the Loop repo public? | Decided: public since November 2026, under Apache 2.0. Everything committed (traces, examples, docs) must be safe to publish |
| Which non-code kind first? | Decided: `content`, or any other non-code kind that has a real need first |
| What did "Maze" refer to? | A platform to deploy agents online ([mazeagent.net](https://mazeagent.net)). To evaluate in M8 as a place to run Loop or as a runtime plugin |
| Should Loop also be usable as a Paperclip worker? | Later, as a plugin |
| How are human touches counted (bl-64)? | Open. Until it is answered, `loop report` prints human touches as not reliable (m4-38) |
| Should the last session carry into the next one, by a resume after a stop or a transcript digest in the brief (bl-151)? | Deferred. In M4 only a Claude Code session below the soft limit resumes (m4-46); otherwise the next attempt starts fresh with the session brief |
| Should a kind set its own runner? | Open, a gap after M4: every kind runs on the project's runner (section 6.2, m4-45) |
