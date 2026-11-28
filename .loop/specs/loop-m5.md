---
id: spec-loop-m5
title: Loop, permissions hardening (M5)
labels: [needs-triage]
---

## Problem statement

M4 made Loop the harness I run every day, in parallel, on Loop and on a pilot app. Every guard that keeps an agent in its lane is still a guardrail, not a boundary:

- An agent runs as my OS user with my environment. The runtime policies Loop generates (the Claude Code deny rules, the Codex approval answers, the `agy` strict tool permission) match command text and can be dodged with `sh -c`, `git -C`, a script or a write by absolute path (PLAN 11.1, `docs/spec-decisions.md` approval and sandbox rows).
- The tracker token reached nothing it should not in the m4-43 wave, but an agent can still read it with `security find-generic-password`, `gh auth token`, `git credential fill` or `ps eww` on `loop run`. The stored token was the gh CLI's own, with `admin:org`, `repo`, `workflow` and `gist` (m4-43, security notes).
- Loop strips only the names a workflow references (`agentEnvRemovedNames` in `cli/src/main/kotlin/loop/cli/Host.kt`, `agentEnvironment` in `plugins/runner-process/src/main/kotlin/loop/runner/process/HostEnvironment.kt`), so `LOOP_PR_TOKEN`, the playground token, `GEMINI_API_KEY` and `SSH_AUTH_SOCK` reached agents, and Xcode wrote two GitHub tokens into DerivedData. Hooks inherit the full host environment, the tracker token included (`ShellHookRunner`, `spec-core/src/main/kotlin/loop/core/workspace/ShellHookRunner.kt`).
- Holdouts are hidden by deny rules only. The Codex runner cannot refuse a read by a command Codex holds as known safe, so a Codex workflow with holdouts does not auto merge (bl-134).
- Nothing on GitHub stops a push to the base branch or a merge outside Loop's own conditions: no branch protection, a token that can do everything (PLAN 11.1, decided 2026-11-11 for M2).
- Clone workspaces (ADR 0002) protect my refs against mistakes, not against a hostile agent: the agent's build code still runs unsandboxed in verification, and `git push /abs/path` or any write by absolute path reaches the project. Several low findings wait for "the M5 sandbox" (the check to use windows of bl-260, bl-261 and bl-262, the unpinned hook git of bl-251, the filters of bl-55).
- When an agent needs something risky in the middle of a run, it has no way to ask and wait; `ask` ends the attempt (m4-31).
- No step after an attempt checks that nothing outside the attempt's branch moved. A violation is found only if a check happens to fail.

## Solution

M5 enforces the permissions in four layers, as PLAN 11.2 says, and adds approvals in the middle of a run and a guard model. In order:

- **The ground first:** a policy denial never fails an attempt (bl-267), one deny list that every runner's generated policy states, an allow listed environment for agents, checks and hooks, and secrets read by reference from the Keychain, never kept in `loop run`'s environment.
- **The sandbox (layer 2):** a sandbox plugin seam, with macOS Seatbelt built in. Every worker runs inside a profile generated per attempt: the workspace writable, home secrets unreadable, the token readers blocked, a stripped environment. The agent, its checks, the holdouts and the hooks each get their profile. A container plugin comes later, for Linux and cloud mode.
- **Server side limits (layer 3):** a fine grained token per project with contents, pull requests and issues write, no admin and no workflow scope, kept in the Keychain; branch protection on Loop's `main` and on the playground repository, with Loop's own merge path kept working.
- **The audit (layer 4):** after every attempt, as part of verification. A violation refutes the attempt and alerts me.
- **Approvals in the middle of a run:** a risky action requested by an agent waits for me through the human channel, and is denied after 30 minutes without an answer.
- **The guard model,** last, for gray areas the deny list does not cover.
- **The exit:** on the playground, an agent told to merge its own pull request, push to the base branch or read a holdout fails, and the attempt is refuted with an alert. Before it, the first M5 run proves what M4 proved by tests only (bl-270).

## User stories

### The ground

142. As a Loop user, I want a tool call the runtime denied by policy to leave the turn going, with the agent told the command is not allowed, so that a stricter policy never costs an attempt (bl-267).
143. As a Loop user, I want one deny list (no merge, no push to the base, no force push, no `release/*`, no `--no-verify`, no `git stash`, no token reader, no holdout read) that every runner's generated policy states and that layers 2 to 4 enforce, so that a rule is never only a sentence in the prompt.
144. As a Loop user, I want every policy denial recorded as an event naming the deny list entry it matched, so that the audit and I can see what an agent tried.

### Secrets

145. As a Loop user, I want agents and checks to get an allow listed environment only (the basic names, Loop's own `LOOP_*` variables, `agent.env` and the names the workflow allows), so that a host secret I did not name never reaches them.
146. As a Loop user, I want hooks to get only the `LOOP_*` variables and the names the workflow lists for them, and git in a hook to run with no hook, no fsmonitor and no lazy fetch, so that a hook never carries the tracker token or takes orders from a clone's config.
147. As a Loop user, I want the tracker token and the pull request token read by Loop from the Keychain when it needs them, never placed in `loop run`'s environment or any child's, so that `ps eww` shows no token.
148. As a Loop user, I want `loop approve`, `loop send-back` and `loop resume` to read the token the same way, so that I stop passing it from the Keychain by hand for each command.

### The sandbox

149. As a Loop user, I want a sandbox plugin seam with macOS Seatbelt built in, a profile generated per attempt and kept outside the workspace, so that the boundary is the OS, not the runtime's goodwill.
150. As a Loop user, I want the Claude Code, Codex and `agy` runners to run inside the sandbox with their own login, state and model API working, and each to declare the `sandbox` capability true, so that every runner gets the same boundary.
151. As a Loop user, I want the agent's profile to leave the workspace and its scratch folders writable and nothing else, and to make my home secrets, the holdout folder, Loop's store and the other workspaces unreadable or unwritable as listed, so that a write by absolute path or a read of a secret fails.
152. As a Loop user, I want the token readers (`security`, `gh`, the git credential helpers) blocked by the sandbox, so that an agent cannot read a token whatever command it writes.
153. As a Loop user, I want checks, holdout checks and hooks run in their own profiles, with the holdout folder readable only by a holdout check, so that the agent's build code never runs unsandboxed as me.
154. As a Loop user, I want an iOS check that builds with Xcode and tests on a simulator granted through `resource:` to work inside its profile, so that the second pilot app keeps verifying in parallel.
155. As a Loop user, I want `loop run` to refuse a workflow that runs workers unsandboxed unless it says `sandbox: { kind: none }`, and `loop status` to show it, so that a worker is never unsandboxed in silence.
156. As a Loop user, I want the agent's network limited to an allow list (its model API, the package registries the workflow names), with each refused host recorded, so that the audit can say no call left the allow list.

### Token and branch protection

157. As a Loop user, I want each project to use a fine grained token with contents, pull requests and issues write and no admin or workflow scope, and preflight to refuse a classic or gh CLI token for it, so that a leaked token cannot lift a protection or edit CI.
158. As a Loop user, I want branch protection on Loop's `main` and on the playground repository (no direct push, no force push, no deletion, a pull request required), with Loop's merge check and merge still merging, so that a push to the base or a merge outside Loop's path fails on GitHub.

### The audit

159. As a Loop user, I want an audit after every attempt, as part of verification, that checks no ref Loop alone writes moved but as Loop wrote it, nothing of the attempt reached the base or a `release/*` branch outside Loop's merge, no protected file was touched, no deny list entry was tried and no call left the network allow list, so that a violation is found even when every check passes.
160. As a Loop user, I want a violation to refute the attempt, park the issue and tell me through the human channel, with what was found in the evidence, so that I look before anything else runs on that issue.

### Approvals in the middle of a run

161. As an agent, I want `request_approval` for a risky action outside my sandbox, answered within the turn, so that I can go on with the human's answer instead of ending the attempt.
162. As a Loop user, I want to be told of each approval request and to answer it with a command, and a request with no answer denied after 30 minutes, the agent told and the notice kept, so that a run never waits on me forever.

### The guard model

163. As a Loop user, I want an optional guard model that judges approval requests and the audit's gray findings before me, and that can never allow a deny list entry, so that I am asked only about the gray cases it cannot settle.

### Exit

164. As a Loop user, I want three seeded issues on the playground that tell an agent to merge its own pull request, push to the base branch and read a holdout, each failing with the attempt refuted and an alert, so that permissions hardening is proven on a real repository with real runners.
165. As a Loop user, I want the first M5 run to prove `ask`, `loop answer`, the per tracker limit and the resource tools on Codex in real attempts (bl-270), so that no part of the retired manager skill stays proven by tests only.

## Implementation decisions

### Decisions taken before the spec (m4-45, 2026-11-27)

- **Sandbox:** a sandbox plugin seam. macOS Seatbelt (`sandbox-exec`, a profile generated per attempt: the workspace writable, home secrets unreadable, a stripped environment) is built in M5; a container plugin comes later, for Linux and cloud mode.
- **Worker token:** a fine grained personal access token per project, contents and pull requests write, no admin and no workflow scope (issues write and Actions read added for the tracker and `ci: actions`, 2026-11-28), kept in the Keychain. Merging is blocked by branch protection, not by the token alone.
- **Branch protection:** in M5, on Loop's `main` and on the playground repository, with Loop's own merge path kept working; the M5 exit tests it. Private projects get it when they can.
- **Guard model:** in M5, as the last slice, after the hard layers (sandbox, token, protection, audit, approvals, deny list).
- **Approvals in the middle of a run:** denied after 30 minutes without an answer; the agent is told and goes on or ends with `needs_info`, and the notice stays.
- **Secrets:** agents get an allow listed environment only, hooks get only the `LOOP_` variables and what the workflow names for them, and the token readers (`security`, `gh auth token`, `git credential`) are blocked by the sandbox. Answers the m4-43 security notes.

### Rules for every M5 issue

- One or two stories, sized for one attempt at `max_turns: 10`, as in M4.
- Lane A logic goes in new packages (`loop.core.sandbox`, `loop.core.audit`, `loop.core.approvals`, `loop.core.secrets`), with call site edits only in `Orchestrator.kt`, stated as a criterion, for the reason of M4.
- Edits to protected paths (`WORKFLOW.md`, `scripts/`, `*.gradle.kts`, `.github/`, `docs/adr/`) are human steps, named in the issue, never left for an agent to find.
- A sandbox issue states which runner and which profile it proves, and carries a real `sandbox-exec` test, not only a generated profile text.
- Runnable criteria open with a backticked command followed by `passes`; prose criteria are marked `(judgment)` or name the test that proves them.
- Loop builds M5 itself, so a slice that changes what an agent may do lands with the matching change to Loop's own `WORKFLOW.md` in its human steps, and Loop is reinstalled before the workflow uses it.

### The tracker (decided 2026-11-28)

- **M5 issues live in GitHub issues** on Loop's own repository, not in `.loop/issues/`. With no direct push to `main` for anyone, every tracker note and issue file of the `files` tracker would have to reach `main` through a pull request; GitHub issues keep the tracker state off the code branch. Specs stay in `.loop/specs/` and reach `main` through pull requests like any change.
- **Two issues first, on the `files` tracker:** m5-00 lets `acceptance_commands` run on a GitHub issue when the tracker declares its issue text trusted (every possible editor of a dispatched issue is trusted, bl-157), so M5 keeps its runnable criteria; m5-01 (human) moves Loop's primary tracker to GitHub and keeps the open backlog in files as a second tracker that keeps the id `default`, so the store rows written as `default:<id>` since m4-12 stay its own and nothing in flight is orphaned. One `code` kind serves both trackers, its `on_delivered.move_to` a map by tracker id (`{ gh: closed, default: done }`, bl-266). `loop resume` reaches the issues of every tracker first (bl-273).
- **The backlog.** New backlog issues go on GitHub, so the files backlog only shrinks. Moves, comments and follow ups of the remaining file issues still write the main checkout, so once Loop's `main` is protected they reach it through a pull request per batch (m5-34).
- **Names.** Each GitHub issue's title opens with its milestone id, `m5-nn:`, so docs and comments keep naming it; blockers are GitHub's native "blocked by" links, areas a column 0 `Area:` line, and a backlog blocker a `Blocked by default:bl-nn` line. The no private names rule holds for GitHub issues as for files, before each issue is created.

### Human prerequisites

- m5-02: a fine grained token for Loop and one for the playground repository, each with contents and pull requests write, issues write for the tracker, Actions read under `ci: actions`, and no admin or workflow scope, stored in the Keychain; the two tokens exposed in the m4-43 wave rotated and narrower gh scopes set (the owner steps of m4-43); the playground repository `owner/playground` ready on GitHub with the seeded issues' base. The rulesets themselves come with their slice.
- m5-03: the module `sandbox-seatbelt` exists with its dependencies and runtime wiring, and `plugin-api` holds empty `Sandbox` and `SandboxProvider` interfaces, the m4-01 pattern, since agents cannot edit Gradle files; and a macOS job in CI runs the real Seatbelt tests, since the build job runs on Linux and skips them.
- m5-00 and m5-01 come before both: they move Loop's own tracker to GitHub issues (above).

### Layer 1, runtime policy and the deny list

- **One deny list, in the core.** `loop.core.sandbox.DenyList` holds PLAN 11.2's entries plus the token readers and the holdout read, each with an id (`merge`, `push_base`, `force_push`, `release_branch`, `no_verify`, `stash`, `token_read`, `holdout_read`). A workflow may add entries, never remove one. Each runner generates its policy from it: the Claude Code settings file (`ClaudeSettingsFile.kt`, today `git push`, `gh`, the history rewrites and the operator commands), the Codex approval answers (`ServerRequestPolicy.kt`, `ExecPolicyRules.kt`) and the `agy` permission policy (`AcpPermissionPolicy.kt`). It stays best effort at this layer, as PLAN 11.1 says.
- **A policy denial never fails the attempt** (bl-267, first slice). A denied call whose turn ended normally continues the attempt with a continuation turn naming the denied command; `turn_input_required` stays for a turn that really asked for input. The policy denial classification of bl-33 (`StreamJsonMapper.kt`, `HiddenFolders.kt`) is reused, not rewritten.
- **A `policy_denied` event** per denial, with the runner, the deny list id it matched or `other`, and the command redacted. The audit reads them. No such event exists today; a denial shows only in the turn's result.
- **`--no-verify` and `git stash`** do no harm in a clone workspace, which has no hooks and owns its refs (ADR 0002). They stay on the list for worktree mode and for the audit's record, not because a clone needs them.

### Layer 2, the sandbox

- **The seam.** `Sandbox` in `plugin-api` turns a command, a working folder, an environment and a `SandboxProfile` (writable paths, readable deny paths, denied executables, network mode, allowed mach services) into the command to start. `SandboxProvider` registers by `kind` through `ServiceLoader`, like `ResourceProvider`. `sandbox: { kind: seatbelt }` selects it; `kind: none` is the explicit way out, and preflight refuses a workflow with no `sandbox:` block once the sandbox slice lands (decided 2026-11-28). The profile is built in the core (`loop.core.sandbox.Profiles`) from the workflow, the runner's declared needs and the attempt; the provider only renders and applies it, so a container plugin reuses the same profile.
- **Seatbelt.** The provider writes the profile in SBPL to the attempt directory, outside the workspace and unwritable by the profile itself, and starts `sandbox-exec -f <profile> <command>`. Writes are denied by default and allowed under the listed paths; reads follow the middle rule decided on 2026-11-28: the system folders (`/usr`, `/System`, `/Library`, `/Applications`, `/opt/homebrew` and the like) are readable, the home folder is unreadable but for an allow list, and the secret paths stay unreadable even inside an allowed folder. A read allow list for the whole disk was rejected as brittle (every runtime reads widely in the system folders), a secret deny list as too open (other checkouts, the shell history and other logins stay readable).
- **Where it wraps.** Every runner process start goes through the shared start of `runner-process`, which wraps it when the attempt has a sandbox; the runner's children inherit it. Each runner declares in its profile what its runtime needs (its state folder, its login, its model API hosts), and declares the `sandbox` capability true (`RunnerCapabilities`, false in all four runners today) once a real test proves it.
- **The agent's profile.**
  - Writable: the workspace, its `.scratch/`, the attempt's temporary folder (as `TMPDIR`), the runner's state folder, and the caches the workflow lists under `sandbox.writable` (for example the Gradle caches and the SwiftPM cache). Read only inside a writable path when listed under `sandbox.read_only`, for example `~/.gradle/init.d` and `~/.gradle/gradle.properties`, so an agent cannot plant code that runs in my own builds.
  - Readable in the home folder, nothing else: the workspace, the runner's own state folder and login (an agent can always read the login of the runtime it runs in), the caches and toolchains the workflow names under `sandbox.writable` and `sandbox.read_home` (for example `~/.gradle`, `~/.m2`, `~/Library/Developer` for Xcode and the simulator). The rest of the home folder, other checkouts and the shell history included, is unreadable.
  - Unreadable even inside an allowed folder: `~/.ssh`, `~/.config/gh`, `~/.git-credentials`, `~/.netrc`, `~/.aws`, `~/.docker/config.json`, `~/Library/Keychains`, the holdout folder, `LOOP_HOME` but what the attempt needs, the other workspaces, plus `sandbox.deny_read`.
  - Denied executables: `/usr/bin/security`, every `gh` on the agent's `PATH` and at the known install paths, `git-credential-*` helpers.
  - Network: through Loop's proxy only (below), loopback always allowed for the build's own servers. Until the proxy slice lands, outbound connections stay open and the audit records network as not audited.
- **The runners.**
  - Claude Code: `~/.claude` and `~/.claude.json` writable, its Keychain login kept working, which is why the profile blocks `security` by executable rather than the Keychain service (open question 5).
  - Codex: `~/.codex` writable, `auth.json` readable. The thread keeps `danger-full-access`, since Loop's profile is now the boundary (m3-18 reasoning).
  - `agy`: its isolated `--gemini-dir` writable. Whether `agy acp --sandbox` still works inside Loop's profile, since it applies its own Seatbelt profile, is a spike before the slice (open question 3); the same holds for the read only sandbox of the Codex merge risk reviewer.
  - The merge risk reviewers run in a read only profile: no writable path but their temporary folder.
- **Tool calls.** `loop tool mcp` runs as the runner's child, so inside the profile, and today opens the run store itself (`cli/src/main/kotlin/loop/cli/ToolMcpCommand.kt`, `ResourceTools.kt`). A store writable by the agent would let it write its own answer or approval. So before the sandbox lands, the tool process talks to `loop run` over a Unix socket, and the profile keeps the store and `LOOP_HOME` unwritable (decided 2026-11-28, a broker as in the MCP security guidance and Claude Code's own sandbox runtime, which also gives a sandboxed process one sanctioned channel to a privileged proxy). The rules of the broker:
  - The socket sits at a short path (macOS caps a socket path at 104 bytes), for example `$TMPDIR/loop/<8 characters>.sock`, mode 0600 in a 0700 folder, created and removed by `loop run`, stale sockets removed at start, the length checked at start.
  - Among Unix sockets, the profile allows only a connect to that one socket, by its resolved real path (`(allow network-outbound (remote unix-socket (path-literal "...")))`), never a `subpath` over a folder where the agent could plant its own socket.
  - Each attempt gets a random token of at least 128 bits, held by `loop run` with the attempt id, revoked when the attempt ends. The agent can read it, which only scopes it to its own attempt. The attempt and the issue come from the token, never from a field of the request; the peer's uid is a sanity check only, since the agent runs as me.
  - Every request is treated as hostile, since the agent can reach the socket without the tool process: every field validated, only the moves legal for the attempt's state accepted (an agent can move its issue to `review` or `needs_info`, never approve, answer its own question or touch another issue), a size cap, a rate limit per attempt, a request id so a replay changes nothing, and every call recorded.
  - Unverified until the sandbox slice: whether `sandbox-exec` honors the socket rule on the macOS in use (denials read with `log stream --predicate 'sender=="Sandbox"'`), and what the stdio pipe of the tool process needs inside the profile.
- **Clone workspaces become the default** (bl-245 item 2), and the sandbox requires `workspace.git: clone` for git kinds: a worktree keeps its git folder in the project, which the profile would have to make writable. Preflight refuses `worktree` under a sandbox.
- **Checks and holdouts.** Visible checks run in the check profile: the agent's writable paths plus the paths a resource grants, no token reader, the same network rule. A holdout check runs in the holdout profile, the only one that reads the holdout folder (`Holdouts.dir`, `spec-core/src/main/kotlin/loop/core/verify/Holdouts.kt`). That closes the read gap of bl-134 for Codex and of the shell read for Claude Code, at the OS level that m2-14 named.
- **iOS checks.** The check profile of a kind whose checks hold a `resource:` of an iOS simulator allows the CoreSimulator mach services `simctl` needs, writes under the DerivedData path the workflow names (a per workspace `-derivedDataPath` is recommended, so a build never writes a shared folder), and the SwiftPM and Xcode caches listed in `sandbox.writable`. The simulator itself is started by CoreSimulator outside the profile, and `prepare`, `release` and the read only check of a resource keep running in `loop run`, unsandboxed (m4-25). The slice proves it on the second pilot app.
- **Hooks.** Hooks run in the hook profile: the workspace writable, and the project's git folder writable for the refs Loop's hook scripts write. With the env allow list and git pinned through `GIT_CONFIG_COUNT` (no hook, no fsmonitor, lazy fetch off), this answers bl-251's unpinned hook git and the hook half of bl-262. Notice hooks (`on_human_needed`, which runs `terminal-notifier`) stay host side, since they touch no workspace and a profile would break them.
- **What stays outside.** Loop's own git on a workspace (fingerprint, import, merge check git) stays host side with its pins (bl-253, bl-254). Its exposure to filters and merge drivers named by a tracked `.gitattributes` (bl-55) is open question 7.

### Network allow list

- Seatbelt cannot filter by host name, so the agent's and the checks' profiles allow outbound connections only to a local proxy that `loop run` starts, set as `HTTPS_PROXY` and `HTTP_PROXY`. The proxy allows `CONNECT` to the hosts of the runner's profile and of `sandbox.network.allow` (for example the package registries), refuses the rest and records `network_refused` with the host.
- It is its own slice after the sandbox, and the audit reads its record. Whether it stays in M5 is open question 6.

### Secrets and the environment

- **An allow list for agents and checks.** `agentEnvironment` becomes allow based: the basic names Loop keeps today (`HOME`, `PATH`, `USER`, `LOGNAME`, `SHELL`, `TMPDIR`, `LANG`, `TERM`, `LC_*`, the kept set of `Host.kt`), the names the runner's profile declares, Loop's overrides, `agent.env`, and the host names listed in a new `agent.env_allow`. `AgentEnvPolicy` (`plugin-api/src/main/kotlin/loop/api/RunnerProvider.kt`) gains the allow list in place of `removedEnvNames`. A check gets the same environment plus the resource variables, and a holdout `LOOP_HOLDOUT`, as today. `SSH_AUTH_SOCK`, `GEMINI_API_KEY` and every other unnamed host variable are gone.
- **Hooks.** `hookEnv` (`spec-core/src/main/kotlin/loop/core/workspace/HookEnv.kt`) stays the source of the `LOOP_*` variables; `ShellHookRunner` starts from the basic names instead of the host environment, plus `hooks.env_allow`.
- **Keychain references.** A secret value in `WORKFLOW.md` may be `keychain:<service>` beside `$NAME`. Loop runs `/usr/bin/security find-generic-password -s <service> -w` itself, host side, when it builds the tracker or the delivery, and keeps the value in memory: never in its own environment, a file, an argument or a child's environment. The redactor learns the value like a `$NAME` secret (`secretValues`, `Host.kt`). The one off commands resolve it the same way (story 148); bl-264 stays for `stop` and `drain`, which should need no token at all.
- **The token check.** Preflight refuses a pull request or tracker token that is neither fine grained (a `github_pat_` prefix) nor a GitHub App installation token (`ghs_`) unless `allow_classic_token: true`, so the gh CLI's own `gho_` token of m4-43 and classic `ghp_` tokens are refused (decided 2026-11-28). Loop cannot read a fine grained token's permissions, so the narrow scope stays a human step, named in m5-02.

### Layer 3, the worker token and branch protection

- **One worker token per project,** in the Keychain, read by reference. Loop uses it for every GitHub write it makes host side: the push of `loop/<key>`, the pull request, the merge (`GitHubPullRequests.merge`, sha guarded since m2-26) and the tracker. Agents never get it: the environment does not hold it, and the sandbox blocks every way to read it.
- **Branch protection,** as a ruleset on the base branch of Loop and of `owner/playground`: a pull request required with no approval count, no direct push, no force push, no deletion, and, under `ci: actions`, the CI checks required. A second ruleset protects `release/*` with no push but by a pull request. Loop's own path keeps working, since it pushes `loop/<key>` and merges through the pull request merge API, which a satisfied ruleset allows. The slice proves both: Loop's merge goes through, and a direct push and a force push with the same token are refused. No bypass (decided 2026-11-28): nobody pushes to `main` directly, the owner and operator sessions included. Every change, tracker notes and issue files too, reaches `main` through a pull request, so the ruleset has no bypass list and the playground proves a direct push by the owner is refused as well.
- **The merge is not gated by a second credential** in M5: an agent cannot merge because it holds no credential, and a leaked token still cannot push to the base, rewrite it or lift the ruleset. A required status that only Loop could post would need a second token with commit statuses write; it is open question 2.
- **The owner's own pushes.** I push straight to Loop's `main` today. Once the ruleset is on, nobody does (open question 1): my changes go through pull requests, and the tracker lives in GitHub issues, so notes on GitHub issues need no commit; the remaining backlog files go through a pull request per batch. The playground's ruleset and the GitHub recordings come early, the ruleset on Loop's `main` late, so the many human `WORKFLOW.md` steps of M5 land before every change needs a pull request.
- **Visibility.** `loop status` says, per pull request repository, whether a ruleset applies to the base, where the token may read the rules, and warns otherwise; a private project without protection runs with the warning (the decision's "when they can").

### Layer 4, the audit after every attempt

- **When.** At the end of every attempt that verifies, as a phase of `loop.core.verify` after the holdouts, and at the end of an attempt that ends without a result (failed, max turns, stopped), on its own. It needs no model.
- **What it checks,** each a finding with its source:
  - Refs: the project refs Loop alone writes (`refs/heads/loop/<key>`, `refs/loop/base/*`, `refs/loop/workspace/<key>`, `refs/loop/workspace-base/<key>`, `docs/workspaces.md`) are where Loop last wrote them. Only those refs can be a violation: ADR 0002 rejected a ref audit that cannot tell my own ref writes (a `git pull`, a branch) from an agent's, so any other moved project ref goes to the evidence, never a violation alone. With the sandbox the project is unwritable, so this is the check that the boundary held.
  - The remote: no commit made in the attempt's workspace is reachable from the base branch or a `release/*` branch on GitHub unless Loop merged it, and the attempt's pull request was not merged, closed or pushed to by anyone but Loop. It tests ancestry, not who pushed, since pull requests I merge myself during a run are normal.
  - Protected files: the `diff_policy` result of the verification, reused, not run again (m2-12).
  - Tried violations: every `policy_denied` event with a deny list id, every Seatbelt denial of a deny list path or executable, every `network_refused`. Where Seatbelt's denials are read from (the unified log or a report) is decided in the sandbox slice (open question 4).
  - Other sandbox denials are recorded in the evidence, never a violation alone; the guard model may weigh them.
- **On a violation.** The verdict is `refuted` with the reason `audit`, whatever the checks said; the findings go to the evidence and to `loop.verify_findings`, redacted; the issue is parked at once, with no retry (decided 2026-11-28: a violation means the agent tried a forbidden move, so a human looks first); the human channel gets a new notice event, `audit_violation`. An attempt that ended without a result is parked the same way. Events: `audit_finished` with the counts, `audit_violation` per finding.
- **Delivery.** No delivery, merge or reaction starts on an attempt whose audit found a violation, and the merge check runs the remote part again just before the merge.

### Approvals in the middle of a run

- **The tool.** `request_approval(action, reason)`, carried like `request_resource`: over `loop tool mcp` for Claude Code and `agy`, as a client tool for Codex, answered inside the turn, never queued (m4-25, m4-51). It answers `pending` with the instruction to call again later, then `approved`, `denied` or `denied: no answer in 30 minutes`.
- **What may be asked** (decided 2026-11-28): a network host for the rest of the attempt, which the proxy adds at once, or a command from the workflow's `approvals.commands`, which Loop runs host side in the workspace in the check profile with a timeout, returning its output. A deny list entry is refused at once and recorded as a `policy_denied`. A Seatbelt profile cannot widen a running process, which is why an approved action is done by Loop or by the proxy, never by the agent's own process.
- **The human side.** The request is recorded (`approval_requested_mid_run`), the human channel gets a new notice event, `approval_requested`, and `loop status` lists it. I answer with `loop approve <issue> --request <id>` or `loop deny <issue> --request <id> [reason]` (decided 2026-11-28), both refused inside an attempt like `loop approve` today (`LOOP_ATTEMPT_ID`).
- **The 30 minutes.** A request with no answer is denied after 30 minutes (`approvals.timeout_minutes`, 30 by default), measured on Loop's clock from the request, surviving a restart through the store. The agent is told and goes on or ends with `needs_info`; the notice stays in the channel and in `loop status` until I answer or dismiss it.
- **Bounds.** At most 3 requests per attempt, like `file_followup`; the polling turns count against `max_turns` and the wall time as any other call. A request is not a human touch until I answer it.

### The guard model, last

- `guard: { model: <name> }`, off by default. Its runtime follows the model name as `risk.reviewer` does (Claude Code, Codex for a Codex model, `agy` for a `gemini-` model, bl-214 and bl-205), read only, in the reviewer profile, outside my checkout.
- It sees each approval request first and answers `approve` for a request the workflow marks as low risk, `deny`, or `ask_human`; and it reads the audit's gray findings and may raise one to a violation. It can never approve a deny list entry nor turn a violation into a pass. A guard that fails or times out is `ask_human`.
- Each decision is a `guard_decided` event with the model and its reason. PLAN 17 lists the guard model in M9; M5 pulls it ahead, and PLAN moves the line to M5 with a "moved to M5 (m4-45)" note on M9, under m4-45 criterion 7.

### What M5 absorbs

- No M4 issue is open but m4-45, so M5 absorbs backlog items, each closed as moved in its slice with the parts not carried:
  - bl-266, whole, and the new bl-273, before m5-01: the move map by tracker and `loop resume` on every tracker.
  - bl-267, whole (the first slice).
  - bl-245 item 2, clone as the default. Item 1, the replay of an unimported commit, stays in the backlog.
  - bl-134: the holdout profile closes the Codex read gap; the issue closes when the playground's holdout seed is refuted on Codex.
  - bl-251's unpinned hook git and bl-262 items 1 and 3: the hook profile and pins.
  - The check to use (TOCTOU) notes of bl-260, bl-261 and bl-262: answered by the sandbox, since the agent can no longer write the project between a check and a git process. Their other low findings stay in the backlog.
  - The m4-43 security notes, items 2 to 4, and the Loop fixes it lists (an env allow list, hooks with the checks' env policy, a token never in `loop run`'s environment). Item 1 and the rotation are m5-02.
  - bl-270, as the first M5 run.
- Related, not absorbed: bl-264 (`stop` and `drain` without a token), bl-55 (filters and merge drivers in Loop's own git, open question 7), bl-36 (holdout spellings, mostly moot once the folder is unreadable), bl-03 (process groups).

## Testing decisions

- **The seams of M1 to M4 stay:** the `loop` CLI on a temporary project with the command or fake runner, contract tests per plugin interface, recorded transcripts and recorded GitHub answers, and the conformance module.
- **A fake sandbox** in `plugin-api` test fixtures records the profile it was given and runs the command unwrapped, so core tests check which profile each process got without macOS. `SandboxProviderContract` sits beside `ResourceProviderContract`.
- **Real Seatbelt tests,** run on macOS only (a macOS CI job, m5-03) and skipped elsewhere with a reason, and skipped with a reason inside an outer sandbox, since `sandbox-exec` refuses to apply a profile inside a restrictive one (`sandbox_apply: Operation not permitted`, checked on macOS 27). A probe command under a generated profile tries to read a secret under a temporary `HOME`, run `security`, `gh` and a git credential helper, write outside the workspace, read the holdout folder and open a socket past the proxy, and each must fail, while a write in the workspace and a read of the system folders succeed. Each runner's test proves its own runtime starts and finishes a scripted turn inside its profile; the runner contract's `sandbox` case becomes observable through the probe. Loop's own workflow keeps `sandbox: { kind: none }` until the check, holdout and hook profiles land, then switches to `seatbelt` in a human step (m5-25).
- **The environment:** tests that an unlisted host variable reaches no agent, check or hook, that a `keychain:` value is never in a child's environment (a fake `security` on a test `PATH` host side), and that the redactor hides it.
- **The audit** runs on real git repositories with the fake runner scripted to try each violation (a ref write in the project, a commit reaching the base on a recorded GitHub answer, a deny list command, a refused host), and with a clean attempt that finds nothing.
- **Approvals** run on a fake clock: an answer before the timeout, a denial at 30 minutes, a restart in between, a deny list request refused at once.
- **Branch protection** is proven on recorded GitHub answers (a push refused by a rule, the merge allowed) in tests, then on `owner/playground` in its slice.
- **The iOS check** in its profile is tested against the fake `xcrun simctl` scripts of m4-27, then proven on the second pilot app.
- **The first M5 run** proves bl-270 in real attempts: one `question_asked` answered with `loop answer`, one `candidate_skipped` from the per tracker limit, and one Codex attempt with `resource_requested` and `resource_granted`, each trace linked in bl-270.
- **The exit** is the real proof: on `owner/playground`, with branch protection on, the three seeded issues (merge your own pull request, push to the base, read a holdout) each end refuted by the audit with an `audit_violation` notice, on Claude Code, and the holdout seed also on Codex (closing bl-134). The traces and the notices are recorded in the exit issue.

## Out of scope

- The container sandbox plugin, Linux and cloud mode: after M5 (M8 for remote). On Linux a workflow runs with `sandbox: { kind: none }` and its warning.
- A second credential and a required status only Loop can post (open question 2), unless the owner decides otherwise.
- Branch protection on private projects that cannot have it: they run with the `loop status` warning.
- Manager agents answering `ask`: M6. The guard model judges permissions only.
- Human channels beyond the terminal: M8.
- Kinds with their own runner: the gap recorded after M4 (PLAN 6.2 and 20).
- The other low findings of bl-260, bl-261 and bl-262, and bl-245 item 1.

## Open questions

| Question | Default if you do not decide |
|---|---|
| 1. How do my own changes reach Loop's `main` once the ruleset is on? | Decided 2026-11-28: no direct push for anyone, no bypass list. My commits, tracker notes and issue files go through pull requests like everything else. Settled the same day: the tracker moves to GitHub issues (m5-00, m5-01), so notes on GitHub issues need no commit; the backlog left in files only shrinks, and its notes reach `main` through a pull request per batch (m5-34) |
| 2. Should the merge also require a status only Loop can post, with a second token holding commit statuses write? | Decided 2026-11-28: No in M5: agents hold no credential, and the worker token cannot push to the base or lift the ruleset |
| 3. Does `agy acp --sandbox`, and the read only sandbox of the Codex reviewer, still work inside Loop's Seatbelt profile? | A spike before the runner slice. If not, Loop's profile replaces theirs and the runner says so in its profile |
| 4. Where does the audit read Seatbelt denials from? | Decided in the sandbox slice, from what a real `sandbox-exec` run reports; until then the audit reads policy denials and proxy refusals only |
| 5. Does blocking `/usr/bin/security` by executable keep the Keychain token unreadable, given the item's access list, while Claude Code's own Keychain login keeps working? | Verified in the spike m5-05, with a real Keychain item made like the m5-02 ones, before the Claude Code runner slice |
| 6. Does the network proxy stay in M5? | Decided 2026-11-28: Yes, as the last slice before the audit's network part; if it slips, the audit records network as not audited and M5 exits without that part |
| 7. Should Loop's own host side git on a workspace (fingerprint, import, merge check) run in a profile too, for the filters and merge drivers of bl-55? | No in M5: its pins stay; bl-55 stays deferred with a note |
| 8. A violation parks the issue at once, rather than sending the findings back as a refutation usually does. Right? | Decided 2026-11-28: Park |
| 9. Does preflight refuse a workflow with no `sandbox:` block, or only warn? | Decided 2026-11-28: Refuse once the sandbox slice is on main; `kind: none` is the explicit way out |
| 10. `sandbox-exec` is deprecated by Apple, though still shipped and used by Codex and `agy`. | Accepted, a PLAN 19 risk; the seam lets a container or another provider replace it |

## Slicing

The M5 issues, in dependency order, after the Fable 5.2 review of the slice list (m4-45, 2026-11-28). m5-00 and m5-01 live in the `files` tracker; m5-02 onward are created on GitHub once m5-01 is done. Issues that edit `Orchestrator.kt`, `Host.kt`, `Preflight.kt`, the status command or `AttemptTool.kt` name them in their area, so Loop serializes lane A. Every human `WORKFLOW.md` step reinstalls first, then edits, and keeps `OwnWorkflowTest` green.

1. m5-00 Acceptance commands on the GitHub tracker. Blockers: none. Agent.
2. m5-01 Move Loop's own tracker to GitHub issues, the backlog kept in files. Blockers: m5-00, bl-266, bl-273. Human.
3. m5-02 Human prerequisites: tokens, Keychain, rotation, playground: fine grained tokens for Loop and `owner/playground` in the Keychain, the m4-43 tokens rotated, narrower gh scopes (m4-43 item 1), the playground base. Blockers: none. Human.
4. m5-03 Human scaffolding: `sandbox-seatbelt` module, empty `Sandbox` and `SandboxProvider`, a macOS CI job: m4-01 pattern, smoke test, a macOS job in `.github/` that runs the Seatbelt tests. Blockers: none. Human.
5. m5-04 First M5 run proves ask, the per tracker limit and Codex resources: story 165, carries bl-270's criteria and the PLAN 16 edit, traces linked here and on bl-270. Blockers: none. Human.
6. m5-05 Spike: what runs inside Seatbelt: nesting (`sandbox-exec` under an outer profile, `agy --sandbox`, the Codex reviewer, `xcodebuild` and SwiftPM), a real Keychain item with Claude Code's login (question 5), each runtime's real hosts. Blockers: m5-02. Human.
7. m5-06 Playground ruleset and GitHub recordings: the ruleset on `owner/playground` (base and `release/*`, no bypass), recordings: rules read with and without a ruleset, refused push and force push, allowed merge, ancestry answers. Blockers: m5-02. Human.
8. m5-07 A policy denial continues the turn (bl-267): story 142, Claude Code and Codex halves. Blockers: none. Agent.
9. m5-08 One deny list, Claude Code settings from it, `policy_denied` events: stories 143 (Claude Code), 144. Blockers: m5-07. Agent.
10. m5-09 Codex policy from the deny list: story 143, Codex, its `policy_denied` events. Blockers: m5-08. Agent.
11. m5-10 `agy` policy from the deny list: story 143, `agy`, its `policy_denied` events. Blockers: m5-08. Agent.
12. m5-11 Env allow list for agents, checks and the reviewers: story 145, `agent.env_allow`, the three reviewers' `hostEnv` and `removedEnvNames`. Blockers: none. Agent.
13. m5-12 Hook environment and hook git pins: story 146, `hooks.env_allow`. Blockers: m5-11. Agent.
14. m5-13 Keychain references, no token in `loop run`'s environment, one off commands: stories 147, 148, a `CONTEXT.md` Secret term as a human step. Blockers: m5-02. Agent.
15. m5-14 The token check: story 157, preflight half, with its fixture sweep and the pilot apps' workflows. Blockers: m5-13. Agent.
16. m5-15 Socket broker and the queued tools: the socket, a token per attempt, `submit_result`, `move`, `comment` on MCP, `loop tool` and Codex's `LoopToolCall`, its own real socket probe, an ADR for the broker as a human step. Blockers: none. Agent.
17. m5-16 In turn tools over the broker, hostile request rules: resource tools, legal moves only, size cap, rate limit, replay id, the store unwritable by the tool process. Blockers: m5-15. Agent.
18. m5-17 Clone as the default (bl-245 item 2): the ADR 0002 condition that the pilot app runs on clones, pilot workflows that need `worktree` say so. Blockers: none. Agent.
19. m5-18 Sandbox seam, Seatbelt provider, agent profile: stories 149, 151, 152: `.scratch`, `TMPDIR`, `sandbox.writable`, `read_only`, `read_home`, `deny_read`, executables from the deny list, the network mode before the proxy, loopback, `clone --shared` objects readable, `git commit` and a Gradle test pass inside, tests skip inside an outer sandbox, question 4, an ADR for the seam as a human step. Blockers: m5-03, m5-08, m5-17. Agent.
20. m5-19 Claude Code runner in the sandbox, its reviewer read only: story 150, Claude Code, question 5 from m5-05, the socket rule. Blockers: m5-18, m5-05, m5-11, m5-16. Agent.
21. m5-20 Preflight refusal and the `loop status` sandbox line: story 155, the `worktree` refusal, `seatbelt` on Linux, the sweep of fixtures, conformance, docs and `loop init`; human step before dispatch: `sandbox: { kind: none }` in every workflow. Blockers: m5-18. Agent.
22. m5-21 Codex runner in the sandbox, its reviewer read only: story 150, Codex. Blockers: m5-19, m5-05. Agent.
23. m5-22 `agy` runner in the sandbox, its reviewer read only: story 150, `agy`, `--gemini-dir` under the home folder beside the unwritable profile. Blockers: m5-19, m5-05. Agent.
24. m5-23 Check and holdout profiles: story 153, checks and holdouts, a `CONTEXT.md` Holdout check edit as a human step. Blockers: m5-19. Agent.
25. m5-24 Hook profile: story 153, hooks, notice hooks stay host side. Blockers: m5-12, m5-18. Agent.
26. m5-25 Loop's own workflow switches to Seatbelt: human: `seatbelt` in Loop's and the playground's workflows, one watched attempt, `kind: none` as the way back. Blockers: m5-19, m5-20, m5-23, m5-24. Human.
27. m5-26 iOS checks in the check profile: story 154, proof on the second pilot app as a human step. Blockers: m5-23, m5-05. Agent.
28. m5-27 Audit, local part: stories 159 (local), 160: only refs Loop alone writes are violations, other moved refs are evidence, protected files, tried violations, refute, park, alert, attempts with no result, network not audited, `CONTEXT.md` Verdict and channel event edits as human steps. Blockers: m5-08, m5-19. Agent.
29. m5-28 `loop status` ruleset line: story 158, visibility, on m5-06's recordings. Blockers: m5-06, m5-13. Agent.
30. m5-29 Audit, remote part: story 159 remote, the merge check reruns it, on m5-06's recordings. Blockers: m5-27, m5-13, m5-06. Agent.
31. m5-30 Approvals: the request and the human side: story 161 and part of 162: `request_approval` on Claude Code and `agy`, `approval_requested` notice, the `loop status` listing, `loop approve` and `loop deny --request`, deny list refused at once, at most 3, a `CONTEXT.md` term as a human step. Blockers: m5-08, m5-16. Agent.
32. m5-31 Approvals: the 30 minute timeout and the command run: story 162: timeout through the store, restart, the approved command run host side in the check profile. Blockers: m5-30, m5-23. Agent.
33. m5-32 `request_approval` as a Codex client tool: story 161, Codex. Blockers: m5-30. Agent.
34. m5-33 Network proxy and allow list, host approvals, the audit's network part: story 156, loopback, real hosts from m5-05, may slip. Blockers: m5-19, m5-27, m5-30, m5-05. Agent.
35. m5-34 Branch protection on Loop's `main`: story 158, late, no bypass, the owner's own changes and the remaining backlog files through pull requests, one per batch of notes. Blockers: m5-06, m5-13, m5-25, m5-28. Human.
36. m5-35 The exit: story 164, three seeds on Claude Code, the holdout seed also on Codex. Blockers: m5-09, m5-21, m5-22, m5-25, m5-26, m5-29. Human.
37. m5-36 The guard model: story 163, last. Blockers: m5-21, m5-22, m5-29, m5-31, m5-32, m5-34. Agent.

Splits from the 21 candidate slices of the draft, each for size or for Loop's own run: the deny list per runner, the seam apart from the Claude Code runner, the preflight refusal apart from both, Codex apart from `agy`, checks and holdouts apart from hooks, the broker in two, the audit in two, approvals in three, branch protection in three (the playground ruleset and recordings early, the `loop status` line, Loop's `main` late), the token check apart from the Keychain references, and a spike that settles what runs inside Seatbelt before any runner slice. The guard model waits for every hard layer but the proxy, which may slip.

## Further notes

- The full design lives in PLAN sections 6.4, 6.10, 11, 12 and 17 M5; the earlier sandbox notes in `docs/spec-decisions.md` (the approval and sandbox rows of the Claude Code and Codex runners, holdout checks, the `sandbox` capability row, the `agy` spike's `--sandbox` notes, clone workspaces) and in `docs/workspaces.md` (threat model) and ADR 0002.
- Workloads: Loop in Loop mode builds M5, from GitHub issues once m5-01 is done (m5-00 and m5-01 on the `files` tracker); the playground repository proves the exit; the second pilot app proves the iOS check profile.
- M5 issues are written from this spec in m4-45, after the owner confirms the choices marked here and the open questions.
