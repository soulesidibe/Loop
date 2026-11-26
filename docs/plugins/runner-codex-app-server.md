# `codex-app-server` runner profile

The `codex-app-server` runner runs the Codex app server as the agent, over JSON-RPC on stdio (spec section 10). This page is its plugin profile, the keys, defaults, secrets and limits plan section 12 asks every plugin to publish. The implementation is `CodexAppServerRunner` in `plugins/runner-codex-app-server` (`loop.runner.codex`), registered as `CodexAppServerRunnerProvider` in `META-INF/services/loop.api.RunnerProvider`; its profile is `CodexAppServerSettings.PROFILE`.

Written 2026-11-16 in issue m3-17, the first slice of the Codex lane: the runner launches the app server and reads it. Since m3-18 it starts the session, `initialize`, then the thread and the first turn. Since m3-19 a turn ends at its `turn/completed` notification, the read and turn timeouts are enforced, and continuation turns run on the same thread in the same app server. Since m3-20 the runner answers approvals, user input and tool calls by rule, refuses `git push`, `gh`, history rewrites and the holdout folder, and starts every thread with no MCP server under `strict_mcp`. Since m3-21 it reports usage and rate limits, and no cost. Since m3-22 it declares Loop's tools to the thread as client tools and runs their calls through `loop tool mcp`. Since m3-23 it reports the occupancy of its thread and interrupts a turn at the hard limit, so Loop rotates its sessions. Since bl-141 `runners.codex.model` picks the thread's model, and a turn the login refuses for its model is `runner_unavailable`. Since bl-212 a turn whose app server is gone starts a new one and resumes the thread with `thread/resume`, and the attempt after a network hold resumes the thread of the held attempt. The later issues of the lane each add their own keys here. Terms follow [`CONTEXT.md`](../../CONTEXT.md); the pinned Codex version and schema are in [`spec-decisions.md`](../spec-decisions.md#codex-cli-app-server-schema-and-mcp-loading-m3-02).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Startup check](#startup-check)
4. [Launch and streams](#launch-and-streams)
5. [Session startup](#session-startup)
6. [Turns, timeouts and continuation](#turns-timeouts-and-continuation)
7. [Approvals, user input and tool calls](#approvals-user-input-and-tool-calls)
8. [Loop's tools](#loops-tools)
9. [Guards](#guards)
10. [Usage, rate limits and cost](#usage-rate-limits-and-cost)
11. [Occupancy and rotation](#occupancy-and-rotation)
12. [Capabilities](#capabilities)

## Configuration

`runner.kind: codex-app-server` selects it, and its section is `runners.codex`, the spec's `codex` object (5.3.6):

```yaml
runner:
  kind: codex-app-server
runners:
  codex:
    command: codex app-server
    turn_timeout_ms: 3600000
    read_timeout_ms: 5000
    strict_mcp: true
    model: gpt-6.1-sol
```

- `command`: the program that starts the app server and its arguments, run in the workspace with no shell (m3-20). It is split into words at blanks as a shell would, with single quotes, double quotes and backslashes, but nothing is expanded: an unquoted operator (`|`, `&`, `;`, `<`, `>`, `(`, `)`), `$`, a backtick, a glob or a leading `~`, or an unterminated quote, fails the startup check, and so does a program word that is an env assignment, such as `FOO=bar codex app-server`, which a shell would read as `FOO` set for codex: set it in `agent.env` instead (bl-135). The program is looked up on the agent env's `PATH`, which no profile extends: a `codex` found only through a `PATH` entry a login profile adds, such as nvm or Homebrew, is not found and the start fails with `agent_not_found`, so give its absolute path or put its folder in the `PATH` Loop starts with (bl-135). Without it, or blank, the spec's default `codex app-server`.
- `turn_timeout_ms`: how long a started turn may go without a protocol message on stdout before Loop kills the app server and fails the turn with `turn_timeout`. Each protocol message restarts it, so it is a silence limit, not a cap on the turn. The spec's default, 1 hour. A real agent can be silent for long stretches (a cold build, a long test run), so set it above the longest silence the project's work has.
- `read_timeout_ms`: how long Loop waits for the answer to each request it writes, `initialize`, `thread/start` and every `turn/start`, before it kills the app server and fails the turn with `response_timeout`. The spec's default, 5 seconds.

- `strict_mcp`: `true` or `false`, `true` by default. With it every thread starts with each MCP server of the Codex config disabled and the `apps`, `plugins` and `computer_use` features off, so the agent gets no MCP server, see [guards](#guards). `false` lets Codex start every server its config lists.
- `model`: the model the thread runs, sent as `model` on `thread/start` (bl-141). Without it, or blank, Loop sends none and the thread takes the `model` of `$CODEX_HOME/config.toml`, else Codex's default. Set it to the model the workflow was checked with, one of those `model/list` returns for the Codex login: a ChatGPT login refuses some models, such as `gpt-5.1-codex-max`, that an operator's config may name, see [a refused model](#a-refused-model).

All are read from `runners.codex`, beside `command`, with the same names as the claude-code runner's. The top level `codex` block of the spec is not read by this runner.

## Profile

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `runners.codex.command` | string | `codex app-server` | no | none |
| `runners.codex.turn_timeout_ms` | integer | `3600000` | no | 1 to 86400000 |
| `runners.codex.read_timeout_ms` | integer | `5000` | no | 1 to 86400000 |
| `runners.codex.strict_mcp` | boolean | `true` | no | none |
| `runners.codex.model` | string | none | no | none |

The runner has no secret key. Codex reads its own login from `$CODEX_HOME`; `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` are removed from the agent env, as for every runner.

## Startup check

Loop checks `runners.codex` against the profile on every load of `WORKFLOW.md`, at startup, in the preflight of every tick and on every reload, before the plugin reads the section (see [plugin profiles](../spec-decisions.md#plugin-profiles-extension-m3-05)):

| Problem | Code | Message |
|---|---|---|
| `command` not a string, such as a list | `invalid_config` | `runners.codex.command must be a string` |
| `command` written for a shell, such as `codex app-server > log` | `invalid_config` | `runners.codex.command is run without a shell, so it cannot hold > unquoted` |
| `command` with an unterminated quote | `invalid_config` | `runners.codex.command has an unterminated single quote` |
| a timeout that is not an integer, such as `turn_timeout_ms: soon` | `invalid_config` | `runners.codex.turn_timeout_ms must be an integer` |
| a timeout out of its limits | `invalid_config` | `runners.codex.turn_timeout_ms must be between 1 and 86400000, got 0` |
| `strict_mcp` not a boolean, such as `yes` | `invalid_config` | `runners.codex.strict_mcp must be a boolean` |
| `model` not a string, such as a list | `invalid_config` | `runners.codex.model must be a string` |
| `runners.codex` not a map | `invalid_config` | `runners.codex must be a map` |
| a key the profile does not list | none, a startup warning | `runners.codex.<key> is not a key of the codex-app-server runner, Loop ignores it` |

The merge risk reviewer follows the model of `kinds.code.delivery.risk.reviewer`, not `runner.kind`. A Codex model (`gpt-`, `codex-` or an `o` series name such as `o3`) runs on Codex since bl-214: one read only turn of this runner with the settings of `runners.codex`, on the `read-only` sandbox with every approval declined (see [Codex merge risk reviewer](../spec-decisions.md#codex-merge-risk-reviewer-extension-bl-214)). Any other model runs through the Claude Code command, `runners.claude-code.command` when set, else `claude` (m4-06, see [the reviewer apart from the runner](../spec-decisions.md#the-reviewer-apart-from-the-runner-extension-m4-06)). Under `policy: auto` with merge `loop run` warns at startup when the reviewer's command cannot be found.

## Launch and streams

- **Launch:** the words of `command` as the argv, with the workspace as its working directory, in its own process group, with the agent env of the shared policy (`agent.env` added, removed names taken out). Cancelling the attempt kills the group. No shell runs it, unlike the spec's `bash -lc`: a login shell reads the user's profile after the env policy built the env, and a profile export would bring back a name the policy removed, such as `GH_TOKEN` or `LOOP_HOLDOUT` (m3-20).
- **The agent's commands:** every `thread/start` sets `allow_login_shell` to false in its `config`, so Codex runs each command of the agent with `zsh -c` or `bash -c`, not `-lc`, and the same profile export does not reach them either. A command the model asks to run in a login shell is refused by Codex. Not covered: `zsh` reads `~/.zshenv` for every shell, login or not, so an export there still reaches the agent's commands when the user's shell is zsh.
- **Stdout** is the protocol stream: one JSON object per line, the framing of the pinned app server. Each line is kept as output as read, except an error response, echoed with its `id`, `code` and `message` and a `[error data omitted, N chars]` marker in place of its data (bl-240), see [error responses in runner output](../spec-decisions.md#error-responses-in-runner-output-extension-bl-240). A line split across reads is read whole once its line break arrives. A line that is not a protocol message is logged as `loop: an app server stdout line is not a protocol message (<reason>), skipped`, the reason a fixed phrase such as `not JSON`, never the line, and reading goes on. A line longer than 10 MB, the max line size of spec 10.1, is dropped by the launcher, never held whole in memory, and a `loop: a stdout line of <n> bytes was dropped, longer than the maximum of 10485760 bytes` line on stderr takes its place (bl-128).
- **Stderr** is kept apart, as diagnostics: each line is kept as stderr output and never read as protocol.
- **Stdin** carries the client's messages, one JSON object per line. It stays open across the turns of a session and is closed when the session stops.
- **Failures:** `invalid_workspace_cwd` (the workspace is not a directory, nothing starts), `agent_not_found` (the process cannot be spawned, or the launcher exits 127 because it could not run the command, before any stdout line), `response_error` (the start or a `turn/start` failed, see below), `response_timeout` and `turn_timeout` (see [timeouts](#turns-timeouts-and-continuation)), `turn_failed` and `turn_cancelled` (the turn's own end), `port_exit: exit code N, before the <method> response` (the server exited while a request waited for its answer), `port_exit: exit code N, no turn result` (it exited during the turn) and `port_exit: the app server of this session is gone, turn N cannot start on thread <id>`.

## Session startup

The startup of spec 10.2 on the pinned protocol (m3-18), each request written once the answer before it arrived. The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-session-startup-m3-18).

| Step | Message | What Loop sends |
|---|---|---|
| 1 | `initialize` (id 1) | `clientInfo` `{"name": "loop", "version": "<Loop's version>"}`, `capabilities` `{"experimentalApi": false}`, or `true` when the thread declares [Loop's tools](#loops-tools) |
| 2 | `initialized` | after the `initialize` answer |
| 3 | `config/read` (id 2), with `strict_mcp` only | `cwd` the workspace's absolute path; the answer lists the MCP servers of the Codex config |
| 4 | `thread/start` (id 3, or 2 without `strict_mcp`) | `approvalPolicy` `untrusted`, `sandbox` `danger-full-access`, `cwd` the workspace's absolute path, `model` when `runners.codex.model` is set, and a `config` with `allow_login_shell: false`, plus, with `strict_mcp`, each server `config/read` listed disabled and the `apps`, `plugins` and `computer_use` features off, plus, in an attempt Loop started, [Loop's tools](#loops-tools) as `dynamicTools` |
| 5 | `turn/start` (id 4, or 3 without `strict_mcp`) | `threadId` from the `thread/start` answer, the prompt as one `text` input, `approvalPolicy` `untrusted`, `sandboxPolicy` `{"type": "dangerFullAccess"}`, `cwd` the workspace |

- **Ids:** `session_started` carries `thread.id` of the `thread/start` answer and `turn.id` of the `turn/start` answer, with the session id `<thread_id>-<turn_id>`. Output read before it comes right after it. A run that ends before the turn started gets two UUIDs instead.
- **Loop's runs:** the provider builds the runner with the holdout folder the host gives it, which `loop run` always does, and the runner's [guards](#guards) refuse reads and edits under it. Until m3-20 the provider refused it, since nothing guarded the run.
- **Version:** the `initialize` answer's `userAgent`, `<originator>/<version> (...)`, names the codex version of the app server. Another major version than the pinned one fails the start with `response_error: codex <version> is not of the pinned major version <major> (codex-cli <pinned>)`, and a `userAgent` with no version with `response_error: the initialize response names no codex version in its userAgent`, before `initialized` is written. Another minor or patch version of the pinned major starts (bl-128).
- **A failed start** kills the process group at once and fails the turn: `response_error: <method> failed with code <code>: <message>` for an error answer, `response_error: an answer to request <id> arrived while waiting for the <method> response` for an answer out of order, `response_error: the <method> response does not match the pinned schema` for an answer that is not its method's response type.

## Turns, timeouts and continuation

How a turn ends, the timeouts and the turns after the first (m3-19). The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-timeouts-and-continuation-turns-m3-19).

- **Turn end:** the `turn/completed` notification of the current turn ends it. `completed` gives `turn_completed` with exit code 0, since the app server still runs; `failed` gives `turn_failed: <message>` and `interrupted` `turn_cancelled: <message>`, the message of the turn's error when it has one. A `turn/completed` that does not match the pinned schema fails the turn with `response_error` and kills the app server.
- **Read timeout:** each request Loop writes waits at most `read_timeout_ms` for its answer, else the app server's process group is killed and the turn fails with `response_timeout: no <method> response within <n> ms`.
- **Turn timeout:** a started turn fails with `turn_timeout: no protocol message for <n> ms` once `turn_timeout_ms` passed since its latest protocol message on stdout, and the group is killed. Stderr lines and lines that are not protocol messages do not restart it. Between turns the app server is idle and neither timer runs.
- **Continuation:** a session runs every turn on one app server and one thread. Turn 2 and later write one more `turn/start` (request id 5, then 6 and so on, or 4, 5 and so on without `strict_mcp`) on the thread of turn 1, with the continuation input as the only `text` item and the same approval, sandbox and `cwd` settings, and emit `turn_started` with that thread id and the turn id of the answer. What the app server wrote between turns comes right after it.
- **Exit:** an app server that exits during a turn fails it at once with `port_exit`, whatever the timeouts. The session goes on: the next turn respawns it, see below.
- **Respawn** (bl-212): a continuation turn whose app server is gone, because it exited during an earlier turn or between turns, before it answered this turn's `turn/start`, or an earlier turn's failure killed it, starts a new app server and resumes the session's thread, after the stderr line `loop: the app server of this session is gone, turn <n> starts a new one and resumes the thread <id>`. The new app server gets the same startup, `initialize`, `initialized` and `config/read` under `strict_mcp`, with request ids from 1 again, then `thread/resume` with the thread id in place of `thread/start`, then the turn's `turn/start`. A turn respawns at most once: a new app server that exits too fails the turn with `port_exit`, and the next turn tries again. A session whose thread Codex never named, because turn 1 failed before its `turn/start` answer, has nothing to resume: a later turn fails at once with `port_exit: the app server of this session is gone, turn <n> cannot start on thread <id>` after its `turn_started`, and no app server starts. The workspace check and the [exec policy rules](#guards) run before every new app server, so a rules file the agent wrote during a turn stops the respawn with `exec_policy_rule`. A continuation prompt can reach Codex twice: when the old app server persisted this turn's `turn/start` and died before it answered, the new one gets the same input again on the resumed thread. This is harmless, as for the Claude Code respawn, whose process can die after its input was written.
- **Resume after a network hold** (bl-212, m4-46): `resumeSession` gives a session whose turn 1 starts a new app server and resumes the thread of the held attempt the same way, after the stderr line `loop: turn 1 resumes the thread <id> of an earlier attempt on a new app server`. The runtime session id of `session_started` is the Codex thread id, so Loop's own rule, below the soft limit, decides when it is used. The resumed session keeps that thread id, the thread is the same one, and its session id is new through the turn id of its answer.
- **The resume request:** `thread/resume` carries what `thread/start` carries, the approval policy, the sandbox, the workspace as `cwd`, `runners.codex.model`, the `config` (login shell off, and under `strict_mcp` every MCP server of `config/read` disabled and the `apps`, `plugins` and `computer_use` features off) and Loop's tools as `dynamicTools`, so the guards, the strict MCP settings and the tools hold on a resumed thread as on a started one. It also sets `excludeTurns`, since Loop never reads the history and a long one could pass the launcher's line bound. An error answer, such as `no rollout found for thread id <id>`, an answer that is not the pinned `ThreadResumeResponse` or one that names another thread fails the turn with `response_error: thread/resume ...` and kills the app server. Loop never starts a new, empty thread in its place.
- **Usage of a resumed thread:** right after `thread/resume`, codex 0.167.0 replays the thread's latest `thread/tokenUsage/updated`, for a turn of the earlier attempt, and its later totals include it. A replayed total for a turn the session did not start is the thread's baseline: it gives no `usage` event and is taken out of the thread's later totals, so a resumed attempt is not charged the held attempt's tokens again. Only a resume made before any turn of the session started, turn 1 of `resumeSession`, takes that baseline. After a respawn the replay is an ordinary update and counts once, whether it names a turn of the session itself or one Loop never learned because the old app server died between accepting its `turn/start` and answering it (bl-232), so the session's usage never drops.
- **Stop:** stopping the session, or the end of a single turn run, closes stdin, gives the app server 5 seconds to exit, then kills its group.
- **Network loss:** a `failed` turn whose error is a transport failure with no HTTP answer is `runner_unavailable` (m4-08): it takes no `retries.max_attempts` slot and holds the run until a probe succeeds.

### A refused model

When the Codex login refuses the thread's model, Codex warns that the model's metadata is missing, then ends the turn `failed` with the `codexErrorInfo` `modelNotFound`, for example `The 'gpt-5.1-codex-max' model is not supported when using Codex with a ChatGPT account.` Every turn on that machine then fails the same way, before any work (bl-141).

- **Category:** such a turn is `runner_unavailable`, not an agent failure: it takes no `retries.max_attempts` slot and holds the run, as a lost network does, so no issue is parked or charged for it. `loop status` shows the hold with Codex's message.
- **Reason:** `turn_failed: <Codex's message> (model_not_found: set runners.codex.model to a model the Codex login accepts)`.
- **Fix:** set `runners.codex.model` to a model `model/list` returns for the login, such as `gpt-6.1-sol`, or change the `model` of the operator's `$CODEX_HOME/config.toml`. The command can also pass it, with no change to the section's keys: `command: codex app-server -c model=gpt-6.1-sol`. The workflow reloads on its next tick and the next probe lifts the hold.

## Approvals, user input and tool calls

The thread asks for approvals with the policy `untrusted`: Codex asks before every command it does not hold as known safe and before every file change, and runs its known safe read only commands, such as `cat`, `rg` or `git status`, without asking. The runner answers every request the app server sends at once, by rule, whatever thread or turn it names, so no request leaves a turn stalled (m3-20). The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-approvals-user-input-tool-calls-and-guards-m3-20).

| Request | Answer |
|---|---|
| `item/commandExecution/requestApproval` | `decline` when its `kind` is anything but a plain command (`writeStdin`, `humanCommand`), when it has no `command`, or when the command holds a denied command or names the holdout folder, else `accept`. The agent goes on after a decline. |
| `item/fileChange/requestApproval` | `decline` when a change of the item, the path it moves to or the root it asks for lies under the holdout folder, or when no change of the item was seen, else `accept`. The changes come from the item's `item/started` and `item/fileChange/patchUpdated` notifications. |
| `execCommandApproval`, `applyPatchApproval` | The same rules, with `approved` or `{"denied": {"rejection": "..."}}`. An empty command is denied. |
| `item/permissions/requestApproval` | Granted nothing. |
| `mcpServer/elicitation/request` | Declined. |
| `item/tool/requestUserInput` | Not answered: the turn ends at once with `turn_input_required` as its only terminal event, and the app server is stopped. |
| `item/tool/call` | A call of one of [Loop's tools](#loops-tools) runs through `loop tool mcp` and its reply answers it. Any other call gets `success: false` with a text naming the tool; the turn goes on. |
| any other request | A JSON-RPC error, method not found; the turn goes on. |

Each answer adds a `loop:` line to the stderr output of the turn, such as `loop: declined the command approval request 7: it touches the holdout folder`, which never quotes the request or names the folder.

## Loop's tools

In an attempt Loop started, whose env has `LOOP_LAUNCHER` and `LOOP_TOOL_CONFIG`, the thread declares Loop's tools as client tools, `dynamicTools` on `thread/start` (m3-22). The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-client-tools-m3-22).

| Tool | Arguments | What a call does |
|---|---|---|
| `submit_result` | The PLAN section 7 result, `status` required, as the MCP tool takes it | Queues the result, as `loop tool mcp`'s `submit_result` does; Loop applies it when the turn ends. |
| `move` | `state`, required, one of the defined states of the workflow's tracker | Queues the move, as `loop tool move` does. |
| `comment` | `text`, required | Queues the comment, as `loop tool comment` does. |

- **Only the workflow's tracker:** the tools are built from the attempt's tool config snapshot, so `move` accepts exactly the states of the `files` or the `github` tracker, and the descriptions name it.
- **The experimental API:** the pinned app server takes `dynamicTools` only when `initialize` asks for the experimental API, so the runner asks for it when it declares its tools, and keeps the stable protocol otherwise.
- **A call** spawns `<LOOP_LAUNCHER> tool mcp` with the agent env and the turn's `LOOP_SESSION_ID` and `LOOP_TURN_ID`, and makes one MCP `tools/call`. The request is the one the Claude Code runner's MCP tools queue, applied at the same drain with Loop's own tracker credentials. The answer is the reply's text, with `success` false for a refusal that queued nothing. An undeclared tool, arguments that are not an object, or a `loop tool mcp` that fails or takes more than 60 s get a failure result. The turn goes on in every case. Each call adds `loop: ran the Loop tool <tool> for the tool call request <id>` on stderr, with `, refused` for a failure.
- **Outside an attempt,** or with a snapshot that cannot be read, the thread declares no tool; the second case adds a `loop:` line saying so.

## Guards

The guards Loop mode puts on the claude-code runner, on Codex (m3-20). Each is a guardrail, not a sandbox: the agent runs as the same OS user.

- **`git push`, `gh` and history rewrites:** the claude-code runner's deny list, `loop approve` and `loop resume` included, matched as whole words anywhere in a command's text, and a test fails when the two lists differ. A program run by its path (`/usr/bin/git push`), git's own options before the subcommand (`git -c x=y push`, `git -C . push`), a backslash a shell drops (`g\h`) and a git subcommand from a substitution or a variable (`git $(echo push)`) are caught too, and the message of a `git commit -m` is left out, so a message that names `gh` is not a denied command (bl-135). None of them is known safe to Codex, so each comes as an approval request and is declined. A command that dodges the words, such as a script file written first and run next, is not caught.
- **Exec policy rules:** a Codex `prefix_rule` with the decision `allow` runs a matching command with no approval request. So before each session starts its app server, the runner reads each `*.rules` file of `$CODEX_HOME/rules`, `/etc/codex/rules` and the `.codex/rules` folders from the workspace up to its project root. One that allows a denied command, or that Loop cannot read as literal rules, fails the first turn with `exec_policy_rule: <file> ...` and nothing starts (bl-135). Not covered: a rule that allows another program that runs a denied command for it, such as an interpreter.
- **Holdout folder:** a command that names the folder, by path, from `~`, by a relative path or by a parsed read action, and a file change under it, are declined. A read by a known safe command, such as `cat` on the folder, runs without a request and is not refused.
- **Strict MCP:** with `strict_mcp`, the default, `config/read` lists the MCP servers of the user's config and of a trusted project's `.codex/config.toml`, and `thread/start` and `thread/resume` disable each by name, so the agent gets no MCP server while the operator's login stays in use. A `config/read` that fails stops the start with `response_error` rather than run with the operator's servers. Some servers come from elsewhere and are not listed, so strict MCP also turns these Codex features off, and no other:
  - `apps`: keeps out `codex_apps`, the ChatGPT apps server of a ChatGPT login.
  - `plugins` (bl-235): keeps out the MCP servers of the operator's enabled Codex plugins, such as `cua_repl` of the `unified-computer-use` plugin the ChatGPT app installs. `config/read` does not list them.
  - `computer_use` (bl-235): Codex's computer use, off as a precaution. Alone it did not keep `cua_repl` out; `plugins` does.

  Checked on codex 0.167.0 with the operator's Codex home, on a started and on a resumed thread: with these off, no `mcpServer/startupStatus/updated` came; with `apps` alone off, `cua_repl` started on both.

## Usage, rate limits and cost

The runner reads two notifications of the pinned schema, one mapping each (m3-21). The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-usage-rate-limits-and-cost-m3-21).

| Notification | Event |
|---|---|
| `thread/tokenUsage/updated` | `usage`, absolute for the session: the sum of the latest `total` of every thread the session saw, a subagent's thread included. A repeated identical update counts once, a later total replaces the earlier. The input count is `inputTokens` without the cached input, `cachedInputTokens` is the cache read count and `cacheWriteInputTokens` the cache creation count, so the four add up to the protocol's `totalTokens`. No cost. |
| `account/rateLimits/updated` | `rate_limit`, with the limit's id and name, the used percent, length and reset time of its primary and secondary windows, and `reached` when Codex says a limit is reached. A sparse update keeps what an earlier update of the same limit said and it leaves out. An update that changes nothing records nothing. |

A notification of either kind that does not match the pinned schema adds a `loop:` line on stderr and is skipped; the turn goes on. `loop trace` prints a rate limit as `rate_limit  limit=codex name=Codex primary=42%/300m primary_resets_at=2026-11-17T12:00:00Z secondary=7%/10080m`.

- **No cost:** Codex reports no cost, so the ledger records no cost for its attempts, never 0, and every `budgets.*_usd` budget spends nothing for this runner: it never warns nor parks. `loop run` says so at startup when the workflow sets a dollar budget. Token budgets still hold.

## Occupancy and rotation

The runner reports the context occupancy of its thread (m3-23, plan 8.3), and Loop enforces the soft and hard limits on it as on the claude-code runner (m3-11, m3-12). The decisions and their reasons are in [`spec-decisions.md`](../spec-decisions.md#codex-runner-context-occupancy-and-rotation-m3-23).

- **Occupancy:** each `thread/tokenUsage/updated` of the session's own thread gives a `context_usage` runner event right after its `usage`, with `last.inputTokens`, the input of the latest model request, cached input included, never the cumulative `total`. A subagent's thread gives none. A report equal to the last one, same tokens and model, gives none.
- **Model and window:** the model is `<modelProvider>/<model>` of the `thread/start` answer, for example `openai/gpt-5.1-codex-max`, or the `toModel` of a `model/rerouted` notification of the thread until the next turn starts. Loop looks its window up in `context.windows`, which ships no Codex model: add the model there with its window in tokens, under `context.windows` as `openai/gpt-5.1-codex-max: <window>`, or its sessions report occupancy in tokens only and never rotate, with Loop's once per run warning. The runner does not read the protocol's `modelContextWindow`; the operator can take the value from it.
- **Interrupt:** at the hard limit Loop calls the session's `interrupt()`, which writes one `turn/interrupt` request with the thread and turn ids. Codex reports the turn's last usage and ends it with `turn/completed` `interrupted`; Loop records the turn as completed with `interrupted` = `hard_limit`. The answer to the request is passed over whenever it comes.
- **Rotation:** Loop stops the session, which closes the app server, and opens a new one, which starts a new app server and a new thread whose first turn gets the rotation prompt with the session brief.

## Capabilities

What `CodexAppServerRunner.capabilities` declares (m3-06, see [runner capabilities](../spec-decisions.md#runner-capabilities-and-the-agentrunner-contract-extension-m3-06)).

| Capability | Value | Why |
|---|---|---|
| `continuation` | true | A session runs its turns on one thread in one app server (m3-19). A turn whose app server is gone starts a new one and resumes the thread with `thread/resume`, and `resumeSession` resumes the thread of the attempt a network hold took (bl-212), see [continuation](#turns-timeouts-and-continuation). |
| `loop_tools` | `client_tools` | The Codex way of plan 6.3: Loop's tools declared to the app server as client tools and served by the runner (m3-22), see [Loop's tools](#loops-tools). |
| `usage` | true | From `thread/tokenUsage/updated` (m3-21), see [usage](#usage-rate-limits-and-cost). |
| `cost` | false | Codex reports no cost, so dollar budgets do not hold for this runner (m3-21). |
| `occupancy` | true | From the `last` usage of the session's thread (m3-23), see [occupancy](#occupancy-and-rotation). |
| `sandbox` | false | The thread runs with `danger-full-access`, no Codex sandbox, see [session startup](#session-startup). The guards are the approval rules, see [guards](#guards). |
