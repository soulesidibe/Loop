# Loop

Loop is an issue execution harness written in Kotlin. It takes issues that are ready for an agent from a tracker, runs one coding agent per issue in its own workspace, checks the result with checks the agent did not write, and delivers it only when the evidence holds.

Loop is not a coding agent. It drives agents such as Claude Code and Codex, and never does the work of an issue itself.

The terms used below (attempt, kind, verdict, parked, handoff) are defined in [`CONTEXT.md`](CONTEXT.md).

Loop builds itself. Since the end of its first milestone, Loop works its own `ready-for-agent` issues: an agent does the work, Loop verifies it, and Loop delivers it as a pull request. Its backlog is in [`.loop/issues/`](.loop/issues/).

## How it works

1. **Poll.** Loop reads issues from a tracker and keeps those in an active state with the required labels, such as `ready-for-agent`, and whose blockers are done.
2. **Workspace.** Each issue gets its own workspace, typically a git worktree on its own branch, prepared by your hooks.
3. **Agent.** Loop runs an agent with a prompt rendered from your `WORKFLOW.md`. The agent can comment, ask a question or hand off through Loop's tools, never through the tracker directly.
4. **Verify.** When the agent hands off, Loop runs the checks you declared: the build, the issue's acceptance commands, a diff policy, guards against deleted tests. Every attempt ends with a verdict: `verified`, `refuted` or `unverified`.
5. **Deliver.** A verified result can wait for your approval, or be delivered as a pull request that Loop merges after a merge check on the current base, its CI and a merge risk assessment.
6. **Record.** Every attempt, event, token and dollar is kept in a local store, so `loop trace` and `loop show` can explain what happened and why.

Loop implements the [Symphony service specification](docs/symphony/README.md) and aims for its core conformance. Verification, delivery, budgets, context limits and several trackers at once are Loop's own additions, described in [`docs/spec-decisions.md`](docs/spec-decisions.md).

## Status

Loop is pre 1.0 and changes quickly. Its first three milestones (the Symphony core, verification and delivery, then plugins and context limits) are done. The fourth, parallel production on real projects, is in progress. The plan is in [`docs/PLAN.md`](docs/PLAN.md).

What works today:

- **Trackers:** `files` (one markdown file per issue in your repository) and `github` (GitHub issues and labels).
- **Runners:** `claude-code`, `codex-app-server`, and `command` for any program.
- **Verification:** command checks, acceptance commands read from the issue, diff policies, file checks.
- **Delivery:** human approval, or pull requests with a merge check, CI wait and conditional auto merge.
- **Limits:** turn and retry limits, token and dollar budgets, and context rotation that replaces a session before it grows past a share of the model's window.

## Requirements

- macOS or Linux.
- Java 21. `bin/loop` uses `runtime/bin/java` inside the install when there is one, then `JAVA_HOME`, then `java` on the `PATH`, and never sets `JAVA_HOME` for your agents.
- git.
- An agent CLI signed in on your machine: [Claude Code](https://docs.anthropic.com/en/docs/claude-code) for the `claude-code` runner, or Codex for the `codex-app-server` runner.

Agents run unattended, with their permission prompts off and without an operating system sandbox. Loop keeps tracker credentials out of the agent's environment and blocks some commands, but an agent can still run anything your user can. Run Loop only on repositories and machines where you accept that.

## Install

```sh
git clone https://github.com/soulesidibe/loop.git
cd loop
./gradlew installLoop
```

Clone with git rather than downloading a zip: the build records its version from git, the release tag such as `0.1.0` or, between releases, a label such as `0.1.0-5-g3f2a9c1d7e4b` (the tag, the commits since and the commit, not a semver to compare). This builds Loop and installs it in `~/.loop/install` (or `$LOOP_HOME/install`). Add `~/.loop/install/bin` to your `PATH`, then check:

```sh
loop --version
```

See [`docs/self-hosting.md`](docs/self-hosting.md) for install options and how the version is recorded.

## Quick start

In the repository you want agents to work on, write a `WORKFLOW.md`. Its front matter configures Loop, its body is the prompt:

```markdown
---
tracker:
  kind: files
  provider:
    dir: .loop/issues
  active_states: [todo]
  terminal_states: [done, canceled]
  required_labels: [ready-for-agent]
workspace:
  root: ../my-project-workspaces
agent:
  max_concurrent_agents: 1
  max_turns: 10
runner:
  kind: claude-code
runners:
  claude-code:
    command: claude
---
You are working on issue {{ issue.identifier }}: {{ issue.title }}.

{{ issue.description }}

Do the work in the current directory, then move the issue to review with the `mcp__loop__move` tool.
```

Add an issue as `.loop/issues/demo-1-hello.md`:

```markdown
---
id: demo-1
title: "Add a hello file"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: []
---

## What

Create `hello.txt` with one line of text.
```

The workspace root sits next to your repository, not inside it, so an agent never works inside your checkout. Loop keeps its own store in `~/.loop`.

Then run one tick and look at what happened:

```sh
loop run --once
loop trace demo-1
```

The agent works in `../my-project-workspaces/demo-1/`, so that is where `hello.txt` lands. This minimal workflow gives the agent an empty workspace and no checks. For real work, add workspace hooks that create a git worktree, and kinds with checks and delivery. Loop's own [`WORKFLOW.md`](WORKFLOW.md) is a complete example, with worktree hooks, verification, pull request delivery and auto merge.

## Commands

| Command | What it does |
|---|---|
| `loop run [WORKFLOW.md]` | Poll the tracker and start an attempt for each eligible issue. `--once` runs one tick. |
| `loop stop` | Stop the project's run, keeping its workspaces. |
| `loop drain` | Let the project's running attempts finish, start nothing new, then stop the run. |
| `loop status` | Show what the run is doing: running, retrying, waiting and parked issues. |
| `loop trace <issue>` | Print the recorded attempts of an issue. |
| `loop show <attempt>` | Print one attempt in depth, with `--cost` for its token and dollar spend. |
| `loop log` | Print the human readable run log. |
| `loop resume <issue>` | Make a parked issue dispatchable again. |
| `loop approve <issue>` | Approve the delivery of a verified result. |

`loop <command> --help` gives the options of each command.

## Documentation

- [`CONTEXT.md`](CONTEXT.md): the glossary, the terms used everywhere else.
- [`docs/PLAN.md`](docs/PLAN.md): what Loop is for, its principles and its milestones.
- [`docs/spec-decisions.md`](docs/spec-decisions.md): how Loop reads the Symphony spec, and every extension.
- [`docs/plugins/`](docs/plugins/): the configuration of each tracker and runner.
- [`docs/workspaces.md`](docs/workspaces.md): workspaces and hooks.
- [`docs/self-hosting.md`](docs/self-hosting.md): installing Loop and running it on its own issues.
- [`docs/gemini-skills.md`](docs/gemini-skills.md): sharing skills and project instructions with Gemini CLI.
- [`docs/adr/`](docs/adr/): architecture decisions.

## Contributing

Loop is a solo project for now, and outside pull requests are not accepted yet. Issues and ideas are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). The Symphony specification in [`docs/symphony/`](docs/symphony/) is Copyright 2025 OpenAI, also under Apache 2.0.
