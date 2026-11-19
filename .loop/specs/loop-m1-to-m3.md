---
id: spec-loop-m1-to-m3
title: Loop, from the Symphony core to verified delivery and context control (M1 to M3)
labels: [ready-for-agent]
---

## Problem statement

I run agents on several of my projects, and now on Loop itself, and today that work depends on a manager skill for Claude Code. It works, but it has real limits:

- It is tied to Claude Code. I cannot swap the agent runtime (Codex, an ACP agent) or the tracker without rewriting the skill.
- The manager is a long conversation. Its context fills up, it forgets decisions, and its state lives in a transcript instead of somewhere I can query.
- Nothing proves an agent's work is done. The agent says "done", and I have to check by hand that tests were not deleted or weakened and that every acceptance criterion holds.
- I cannot see afterwards what happened in a run: which prompt the agent saw, what it did, what it cost, why it was retried.
- Nothing stops a runaway: an agent that keeps failing can retry and burn tokens until I notice.
- Context windows fill up silently, and quality drops long before compaction kicks in.

OpenAI's Symphony spec describes a solid service for polling a tracker and running one agent per issue, but it has no verification step, supports only the Codex runtime, and only one tracker.

## Solution

Loop is an issue execution harness written in Kotlin. It takes issues that are ready for an agent from a tracker, runs one agent per issue in an isolated workspace, verifies the result with checks the agent did not write, and delivers it only when the evidence holds. Loop never does the work of an issue itself.

Across M1 to M3:

- **M1:** a Kotlin implementation of the Symphony core, traced from day one, with a `files` tracker, a command runner and a minimal Claude Code runner. Loop builds itself: I build the first slices by hand with Claude Code up to a bootstrap point, then Loop works the rest of its own backlog while I review and merge each branch. Safety limits (attempt cap, parking, token thresholds and an opt in token ceiling, stop) are in place before Loop works alone.
- **M2:** Loop mode. Loop owns verification and the move to the handoff state. Issues of the `code` kind are verified by visible checks, holdout checks and guards, and get a verdict (`verified`, `refuted`, `unverified`). Only `verified` work is delivered, as a pull request, on a GitHub tracker, with a ledger and spend budgets.
- **M3:** the Codex app-server runner and full Core Conformance with the spec, plugin loading, and context control: no session goes above 25 percent of its model's window, thanks to occupancy tracking, rotation with session briefs built from facts, and a prompt budget. Reached on 2026-11-18: each runner delivered a real issue with no main session above 25 percent, at a hard limit of 0.21 (m3-27, m3-28).

M4 has its own spec, [Loop, M4](loop-m4.md).

## User stories

### Running Loop on a project

1. As a Loop user, I want to start Loop on a project with one command, so that it begins working the project's ready issues.
2. As a Loop user, I want Loop's whole configuration to live in the project's `WORKFLOW.md`, so that the repository carries its own contract.
3. As a Loop user, I want a `WORKFLOW.md` that uses only Symphony keys to run on Loop as it would on any Symphony implementation, so that I am not locked in.
4. As a Loop user, I want `$VAR` references in the config resolved from my environment, so that secrets never sit in the repository.
5. As a Loop user, I want Loop to check the config before every dispatch tick and refuse to dispatch on an invalid one, so that a typo never launches a broken attempt.
6. As a Loop user, I want edits to `WORKFLOW.md` picked up while Loop runs, keeping the last good config when an edit is invalid, so that I can tune prompts and limits without restarting.
7. As a Loop user, I want every config reload recorded with its hash and diff, so that I know which config each attempt ran with.
8. As a Loop user, I want to stop Loop cleanly with one command that stops every attempt and keeps the workspaces, so that I can always pull the plug.
9. As a Loop user, I want to relaunch Loop with an existing run id and keep writing into that run, so that one working period stays one trace group across restarts.
10. As a Loop user, I want issue state (claims, parked issues, the retry queue, pending approvals, budgets) to survive every restart, with or without a run id, so that restarting never undoes a park, drops an approval or resets a budget.
11. As a Loop user, I want one Loop process per project, all sharing one machine level store, so that machine wide limits work across my projects.
12. As a Loop user, I want every record to carry its project id, so that reports and traces can be filtered by project.

### The files tracker and Loop's own backlog

13. As a Loop user, I want issues stored as one Markdown file each, with front matter for id, title, state, priority, labels and blockers, so that the backlog is plain text, versioned in git, and readable by humans and skills.
14. As a Loop user, I want issue states `todo`, `in_progress`, `review`, `needs_info`, `done` and `canceled`, with `todo` and `in_progress` active, `review` and `needs_info` as handoff states, and `done` and `canceled` terminal, so that every issue's place in its life is explicit.
15. As a Loop user, I want Loop to dispatch only `todo` issues labeled `ready-for-agent`, so that issues I build by hand (`ready-for-human`) or that need triage are never picked up.
16. As a Loop user, I want issues whose blockers are not done to wait, so that dependent work does not start on stale foundations.
17. As a Loop user, I want candidates sorted by priority, then age, then identifier, as the spec says, so that dispatch order is predictable.
18. As an agent, I want a `loop tool` command to move my issue to a state and to add a comment, so that I can report progress without touching tracker files or credentials.
19. As a Loop user, I want agents never to edit issue files directly, and the issue folder left out of their worktrees, so that no agent writes to a stale copy that later conflicts.
20. As a Loop user, I want specs stored apart from issues, so that Loop never dispatches a spec.

### Workspaces and hooks

21. As a Loop user, I want each issue to get its own workspace under a configured root, with a sanitized key, so that agents never collide or escape their folder.
22. As a Loop user, I want the four spec hooks (`after_create`, `before_run`, `after_run`, `before_remove`) with timeouts, so that a project decides how its workspaces are populated and cleaned.
23. As a Loop user, I want hooks to receive the issue id, identifier and workspace key as environment variables, so that a hook can create a git worktree on the right branch.
24. As a Loop user, I want each attempt's work on a branch named after its issue, so that I can find and review it.
25. As a Loop user, I want workspace cleanup to remove the worktree but never delete its branch, so that marking an issue done before merging never loses work.
26. As a Loop user, I want a workspace kept when an issue leaves the active states, and cleaned when it reaches a terminal state, as the spec says, so that review and rework happen in the same workspace.
27. As a Loop user, I want terminal workspaces swept at startup, so that disk usage does not grow forever.

### Attempts, continuation and retries

28. As a Loop user, I want at most a configured number of agents running at once, globally and per state, so that my machine and my plan limits are not overwhelmed.
29. As a Loop user, I want an attempt to continue on the same thread while its issue stays active, up to a turn cap, so that an agent can finish multi-turn work.
30. As a Loop user, I want failed attempts retried with exponential backoff capped at a maximum delay, so that transient failures recover without hammering anything.
31. As a Loop user, I want an attempt cap per issue (5 by default), after which the issue is parked, so that an issue that always fails stops consuming tokens.
32. As a Loop user, I want token thresholds per attempt (every 10 million tokens, cache reads included) that record an event with the spend so far, log it and run an optional hook while the attempt goes on, and an opt in token ceiling with no default that parks the issue when crossed, so that I hear about a costly attempt without normal work being parked. Nobody can know in advance how many tokens an issue needs: on Loop's first self run an attempt parked on a 2 million cap after one turn of 5.2 million tokens, mostly cache reads, while hand run issue sessions on two other projects used a median of 5 to 18 million tokens and up to more than 100 million.
33. As a Loop user, I want a parked issue never dispatched again until I resume it or its tracker state changes, so that parking is a real stop.
34. As a Loop user, I want stalled agents detected after a timeout and handled like failures, so that a hung process never holds a slot forever.
35. As a Loop user, I want running issues reconciled every tick (stopped and cleaned when terminal, stopped and kept when no longer active), so that the tracker stays the source of truth.

### Runners

36. As a Loop user, I want a runner that runs any shell command as one turn, so that Loop can be tested and used without a model.
37. As a Loop user, I want a Claude Code runner that drives Claude Code headless, maps its stream to the spec's events and reports usage, so that Loop can work real issues with Claude Code.
38. As a Loop user, I want every runner to emit the same event names and error categories, so that the orchestrator, stall detection and token accounting work the same for all of them.
39. As a Loop user, I want Claude Code to run on my own subscription login, so that cost appears in my usage and plan limits act as a ceiling.
40. As a Loop user, I want the Claude Code settings Loop generates for each workspace to deny `git push`, `gh` and history rewrites in M1 and M2, so that agents cannot reach GitHub while only Loop pushes branches and opens pull requests, host side.
41. As a Loop user, I want a runtime that needs input it cannot get (a blocked permission, a prompt) to fail the attempt with the reason traced, so that it is retried and eventually parked instead of hanging.
42. As a Loop user, in M3, I want a Codex app-server runner, so that Loop reaches the spec's Core Conformance and I can compare runtimes. Built in m3-17 to m3-23, on a ChatGPT login.
43. As a Loop user, in M3, I want runners and trackers loaded as plugins selected by `kind` in config, so that swapping a runtime or a tracker is a config change. Built in m3-04 and m3-05.

### Questions and rework

44. As an agent, when I hit a product question, I want to write it as a comment, commit my work and move the issue to `needs_info`, so that a human answers instead of me guessing.
45. As a Loop user, I want to answer in the issue file and move it back to `todo`, so that the next attempt resumes in the same workspace with my answer.
46. As a Loop user, I want "changes requested" after review to work the same way, so that rework needs no special flow.
47. As an agent, I want technical questions decided by me and recorded as decisions, so that only product questions reach the human.

### The prompt template

48. As a Loop user, I want the prompt rendered strictly from `WORKFLOW.md` with the issue and attempt variables, failing on an unknown variable, so that a template mistake is caught instead of sent.
49. As a Loop user, I want Loop's own template to require a default fail acceptance checklist, test first for logic, evidence from real command output, a progress file, a self review and my writing rules, so that agents follow the discipline the issue skill taught.
50. As a Loop user, I want acceptance criteria written as checkable statements with the command that checks them, judgment calls marked as prose, so that M2 can reuse them as checks.

### Observability

51. As a Loop user, I want every record to carry its run, project, issue, attempt, session and turn ids, so that everything can be joined and traced.
52. As a Loop user, I want structured `key=value` logs in the spec's format with those ids, so that logs and traces line up.
53. As a Loop user, I want the raw runner stream and every rendered prompt stored per attempt, so that I can see exactly what an agent saw and did. M1 stores the turn 1 rendered prompt in the attempt file (m1-13); continuation guidance is not recorded. The full raw runner stream and the retention of attempt files move to M2 (m1-22).
54. As a Loop user, I want the size of every prompt slot recorded from M1, so that context limits can be tuned from data later.
55. As a Loop user, I want `loop trace <issue>` to print the full timeline across attempts, sessions and turns, so that I can understand what happened without reading raw files.
56. As a Loop user, I want `loop show <attempt>` with the prompt, the events or the cost, so that I can dig into one attempt.
57. As a Loop user, I want `loop status` to show the runtime snapshot (running, retrying, waiting, parked), so that I know what Loop is doing right now.
58. As a Loop user, I want secrets redacted before anything is written, so that traces are safe to keep and, later, to publish.
59. As a Loop user, I want token accounting from absolute totals, never double counted, as the spec says, so that cost numbers are right.
60. As a Loop user, I want each attempt to record the Loop version it ran with, so that I know which build worked each issue while Loop builds itself.

### Self hosting in M1

61. As a Loop user, I want to build the first slices by hand with Claude Code, marked `ready-for-human`, so that Loop has a working core before it works on itself.
62. As a Loop user, I want Loop to run from an installed build outside the repository, reinstalled only by hand after merging work Loop should use and never during a run with active attempts, so that an agent never runs the code it is editing.
63. As a Loop user, I want the safety limits (attempt cap, parking, token thresholds and an opt in token ceiling, stop, concurrency 2, 10 turns) in place before the bootstrap point, so that Loop is safe from its first unsupervised issue.
64. As a Loop user, I want the last M1 issue to write the M2 issues from this spec and what M1 taught, so that the next backlog reflects reality.

### Verification and delivery (M2)

65. As a Loop user, I want adding a `kinds:` key to switch a workflow to Loop mode with no other change, so that the same prompt works in both modes.
66. As a Loop user, I want the agent's call to move an issue to a handoff or terminal state intercepted and treated as a submitted result, so that only Loop moves issues, and only after verification. The intercept plugs in at the host side drain of queued tool requests, built in M1 (m1-23).
67. As an agent, I want a `submit_result` tool (over MCP for Claude Code, or a result file for runners without tools), so that I end my attempt with a structured result.
68. As a Loop user, I want a kind's visible checks run by Loop itself after the agent finishes, so that the agent's claim is never trusted.
69. As a Loop user, I want holdout checks stored outside every workspace and every sandbox readable path, so that the agent cannot see or game them.
70. As a Loop user, I want guards that refute an attempt when protected files are edited, tests are deleted, skips are added or assertions are weakened, so that shortcuts are caught.
71. As a Loop user, I want every check's raw output saved as evidence stamped with the workspace fingerprint, so that a verdict can be audited and replayed.
72. As a Loop user, I want a failing check rerun once before a verdict, so that a flaky test does not refute good work.
73. As a Loop user, I want a `refuted` verdict to send the findings back to the agent as continuation guidance, counted against the turn cap and budgets, so that the agent fixes its work.
74. As a Loop user, I want an attempt with no checks or with a check that could not run to be `unverified` and parked, never delivered, so that missing evidence never counts as done.
75. As a Loop user, I want an issue with no acceptance criteria section not dispatched, commented and labeled `needs-info`, so that vague issues are bounced before they cost anything.
76. As a Loop user, I want a `Waiting` state for issues waiting on verification, an approval or an answer, excluded from dispatch, so that nothing is dispatched twice.
77. As a Loop user, I want a delivery policy per kind, `human` by default, where Loop asks me to approve before merging, so that nothing merges without me until I choose `auto`.
78. As a Loop user, I want `loop approve <issue>` to approve a pending delivery, so that I can approve from the terminal.
79. As a Loop user, I want verified code delivered as a pull request with an evidence summary, and merged by Loop only after a merge check on current main passes (main merged into the branch, the kind's checks rerun on the result, CI green), so that what merges is what was verified on the main it lands on. M2 merges with `ci: none`, Loop's merge check being the gate (m2-26); waiting for CI moves to M3 (m3-15, carrying m2-30). Built in m3-15 as `ci: actions`. Since 2026-11-16 GitHub refuses every Actions job for billing, so Loop's own workflow runs `ci: none` and its merges rest on local build evidence.
80. As a Loop user, I want a GitHub tracker where moving to a state is a label change (remove `ready-for-agent`, add `needs-review`), so that GitHub's open and closed model still supports handoff states.
81. As a Loop user, I want tracker credentials kept out of the agent's environment, so that agents act on the tracker only through Loop's tools. M1 strips every `$VAR` from the agent environment and gives `loop tool` a secret free tool config snapshot (m1-23); M2 extends this to the GitHub token.
82. As a Loop user, I want claim states, the retry queue and sessions persisted in the run store, so that a restart resumes exactly where Loop stopped.
83. As a Loop user, I want a ledger line per turn and a row per attempt (tracker, kind, runner, model, template, tokens, cost, wall time, checks, guards, verdict, human touches), so that I can measure Loop.
84. As a Loop user, I want spend budgets per attempt, per issue and per rolling day, warning at 80 percent and parking at 100 percent, so that spending has hard limits.
85. As a Loop user, I want the machine wide daily budget and concurrency cap enforced through the shared store, so that several projects together stay within my limits. Moved to M3 on 2026-11-14 (m3-16, carrying m2-21). Built in m3-16, opt in through `<LOOP_HOME>/config.yaml`.
86. As a Loop user, I want M2's first test to be Loop verifying its own M1 issues from their acceptance criteria commands, so that verification is proven on real history. Moved to M3 on 2026-11-14 (m3-01, carrying m2-10); M2 proved Loop mode on the playground and the pilot app instead. Built in m3-01: five done M1 issues verified from their merge commits, and m3-15 was the first agent issue Loop worked in Loop mode, merged after `loop approve`.
87. As a Loop user, I want a seeded shortcut (a deleted test) on a playground refuted, so that I know the guards work before trusting them on real projects.

### Added after M1

99. As a Loop user, I want a plain sentence log per project next to the `key=value` operator log, printed by `loop log`, so that I can follow a run without reading structured logs. Built in M1 (m1-39, m1-40).
100. As a Loop user, I want to opt a project into conditional auto merge, where Loop merges a verified pull request only when every listed condition holds and falls back to `human` otherwise, so that safe changes land without me and risky ones still wait for me. Built in m2-28. In M3 two pull requests merged this way, bl-84 and bl-86 on 2026-11-15. Since the Actions refusal of 2026-11-16 none did: the `ci` condition reads Actions runs even under `ci: none` (bl-146), and the merge risk reviewer exists only for the claude-code runner (bl-145). Both go to M4. Before that, most fallbacks were on `diff_size` (bl-96).

### Context control and conformance (M3)

88. As a Loop user, I want occupancy (the input size of the latest request) reported by every runner that can, so that Loop knows how full each session is.
89. As a Loop user, I want a session that crosses 20 percent of its window rotated at the next turn boundary, and one that crosses 25 percent interrupted and rotated, so that no session degrades from a full window. As built, the soft limit is checked only at the end of a turn after which the attempt goes on. In the exit runs every agent ended its last turn by handing off, so the soft limit never fired and the hard limit was the only guard. The default hard limit of 0.25 cannot keep a session under 25 percent, since the request that crosses it can land above; the exit runs used 0.21 (bl-144, M4).
90. As a Loop user, I want a rotated session to start with a session brief built from facts (goal and acceptance criteria, git state, latest checks and verdicts, counters, then the agent's notes marked as unverified), so that continuity does not depend on the agent's memory.
91. As a Loop user, I want rotations capped per attempt, and rotations without progress to park the issue with "split this issue", so that issues too big for one session are surfaced.
92. As a Loop user, I want the rendered prompt to fit its own budget, built from slots in priority order, with the template never cut and preflight failing if the template alone is too big, so that prompts never crowd out the work.
93. As a Loop user, I want tool outputs trimmed for the model with the full output kept as evidence, so that a long test log does not flood the context.
94. As a Loop user, I want a runner that cannot report occupancy to declare it, and Loop to enforce only the prompt limit and a turn cap for it, so that weaker guarantees are explicit. Built in m3-09. A runner that reports occupancy for a model with no window in `context.windows` gets no fraction and never rotates, with only a warning (bl-125, bl-138, M4).
95. As a Loop user, I want peak occupancy per session in the ledger, so that limits can be tuned from data.
96. As a Loop user, I want the spec's conformance test matrix to pass, so that Loop is a real Symphony implementation. Audited in m3-26: every row covered but one partial, 17.5 item 16, since no tool write reads `native_ref` (bl-153, M4).
97. As a plugin author, I want contract tests per plugin interface that every implementation must pass, so that a new tracker or runner is trustworthy.
98. As a Loop user, I want each runner to deliver a real issue with no session above 25 percent of its window, so that the rule is proven in practice. Reached: Codex delivered bl-63 at a peak of 0.14 (m3-28); Claude Code delivered bl-132 after a real hard limit rotation, bl-136 and the second pilot app issue, peaking at 0.2103 (m3-27). The figures are the main session's: subagents run in windows Loop does not measure.

## Implementation decisions

### Shape

- Loop follows the Symphony spec (Draft v1) wherever it speaks, and pins the spec commit it implements. Ambiguities in the spec (the workspace key hash, `after_run` after a failed `before_run`, priorities outside 1 to 4, cleanup on a terminal retry, the absence of a retry cap) are answered once and recorded in a spec decisions document.
- Loop is built from a small set of modules: the Symphony core (workflow loading, config, orchestrator, workspaces, prompt rendering, logs), the plugin API, the store, the Loop extensions (kinds, verification, delivery, budgets), the CLI, and plugins (the `files` tracker, the command runner, the Claude Code runner, later the GitHub tracker and the Codex app-server runner).
- In M1 the plugins are wired in directly. Plugin loading by `kind` (in process, through Java's service loader) arrives in M3. As built (m3-04, m3-05): a `RunnerProvider` or `TrackerProvider` registers through `META-INF/services`, found on the classpath and in each JAR of `<LOOP_HOME>/plugins/`, read once per process. An unknown kind fails preflight naming the kinds found, and two providers of one kind fail startup. Each plugin declares a `PluginProfile` (keys, types, defaults, secrets, limits), checked on every load of `WORKFLOW.md`; an unknown key is a warning, not an error.
- Code avoids process wide singletons (config, store), so several orchestrators can share one process later (ADR 0001).

### Domain terms

- **Issue** is the one term, in code and docs. **Kind** replaces "ticket kind". A **run** is one working period of Loop, resumable by id, holding only tracing. An **attempt** is one agent execution on one issue inside a run. A **project** is one `WORKFLOW.md` and the state Loop keeps for it. **Handoff state** is the tracker state for work ready for someone else. A **session brief** is what a rotated session receives. **Parked** issues are not dispatched until resumed. These are defined in the glossary.

### Process and store

- One Loop process per project. All processes on a machine share one store in a machine level folder, never inside a repository (ADR 0001). Every record carries a project id.
- Machine wide concerns (device locks later, the global daily budget, the global concurrency cap) are transactions in that shared store.
- Issue state belongs to the project and survives every restart. A run only groups records for tracing.
- The store is SQLite (a SQLite index plus one compressed JSONL file per attempt for raw runner streams and prompts), append only for events.

### Plugin interfaces

- `TrackerAdapter` follows the spec's tracker contract (fetch candidates by states, fetch by ids), plus optional writes (state moves, comments, labels).
- `AgentRunner` is modeled on the spec's runner section: start a session with a prompt, continue a turn with guidance, a stream of events using the spec's event names and error categories, stop, and declared capabilities (continuation, tools, MCP, usage, occupancy, sandbox).
- As built in m3-06, the capabilities are `continuation`, `loopTools` (`mcp`, `client_tools` or `files`), `usage`, `cost`, `occupancy` and `sandbox`, declared with no default. `AgentRunnerContract` checks every declared one but `loopTools` and `sandbox`, which no runtime neutral test can observe.
- Later interfaces (`CheckType`, `DeliveryExecutor`, `HumanChannel`, `ResourceProvider`) follow the same pattern. `HumanChannel` and `ResourceProvider` are not in `plugin-api` yet; they come with M4.

### The files tracker

- One Markdown file per issue in the project's issue folder in the main checkout, versioned in git. Front matter: `id`, `title`, `state`, `priority`, `labels`, `blocked_by`. Body: parent spec, what, acceptance criteria, comments.
- States: `todo`, `in_progress` (active); `review`, `needs_info` (handoff states, neither active nor terminal); `done`, `canceled` (terminal).
- Loop always reads the folder in the main checkout by absolute path, never from a workspace. The `after_create` hook leaves it out of each worktree.
- Agents write only through `loop tool move <state>` and `loop tool comment`, which know their issue from the environment Loop passes.
- Specs live in a separate folder and are never dispatched.
- Loop's own `WORKFLOW.md` dispatches only `todo` issues labeled `ready-for-agent`.

### Limits and safety

- `retries.max_attempts` defaults to 5 per issue. A minimal `Parked` state, `loop resume`, token thresholds per attempt (every 10 million tokens, cache reads included, reporting only), an opt in token ceiling per attempt (no default, parks when crossed), a cap on attempts without progress (`retries.max_continuations`, default 3, m1-27) and `loop stop` land in M1, before the bootstrap point. M2 extends parking to every reason, adds `Waiting`, and decides whether `token_cap` folds into the `budget` park reason. Decided in m2-20: it folds into `budget`.
- Loop's own `WORKFLOW.md` sets `max_concurrent_agents: 2` and `max_turns: 10`. Issues that touch the same files declare `blocked_by` on each other.
- In M1, agents get no GitHub access. The Claude Code settings Loop generates deny `git push`, `gh` and history rewrites. These are a guardrail, not a sandbox. Real isolation is M5.
- No branch protection in M2 (decided 2026-11-11): the repositories stay private on the free plan. Agents still get no GitHub access (the generated deny rules and the stripped environment), Loop pushes branches and opens pull requests host side with the user's token, and conditional auto merge is guarded only by Loop's own conditions. Branch protection and a worker token that cannot merge come with M5.

### Self hosting

- Phase one, by hand with Claude Code, issues labeled `ready-for-human`: the stack decisions, the walking skeleton, git worktree hooks, the Claude Code runner and the safety limits. That is the bootstrap point.
- Phase two, by Loop in Symphony mode, issues labeled `ready-for-agent`: the rest of M1. I review each branch and merge it through a pull request after a manual merge check: the branch merged with current main, `./gradlew --no-daemon build`, CI green.
- The running Loop is an installed build outside the repository. It changes only when I run `./gradlew installLoop` after merging work Loop should use, never during a run with active attempts (m1-43). Every attempt records the Loop version (git SHA) it ran with.

### Prompt template

- A dedicated template in Loop's own `WORKFLOW.md`, not the issue skill. It keeps a default fail acceptance checklist, test first for logic, evidence from real command output, a progress file in the workspace, a self review against the acceptance criteria and the writing rules (sentence case, no em or en dashes, no co author line). It drops issue picking, worktree creation, pull requests and asking the user. It ends with a commit on the issue's branch and a move to `review`.
- Rendering is strict: unknown variables and filters fail. The engine is chosen in the first issue: an existing JVM Liquid library in strict mode first, otherwise a small strict subset written for Loop.

### Verification and delivery (M2)

- Symphony mode (no `kinds:` key) and Loop mode (a `kinds:` key) as described in the plan. In Loop mode, the tool call that moves an issue to a handoff or terminal state is intercepted, not removed. The intercept and the verification plug in at the host side drain of queued tool requests (m1-23).
- Verdicts: `verified` (every check and holdout passed, no guard fired, the review passed when declared), `refuted` (a check failed or a guard fired, findings sent back as continuation guidance), `unverified` (no checks, or a check could not run; parked, never delivered).
- Evidence is stamped with the workspace fingerprint (HEAD, uncommitted diff and untracked files for git).
- Core check types: `command`, `file_exists`, `file_matches`, `diff_policy`.
- Loop's tools reach Claude Code through an MCP server built with the official MCP Kotlin SDK. Runners without tools write the result as a JSON file in the workspace.
- The submitted result carries a status, the deliverable, a summary, each acceptance criterion with its proof and reference, residual human steps, follow ups and notes for the next attempt.
- Delivery policy `human` by default. The GitHub tracker implements `move_to` as label changes.
- What M1 taught about merging: branches that touch the same files conflict when they merge out of order (m1-17, m1-34 needed a "merge main" retry, m1-42), and a build that shares a Gradle daemon with an agent can die when the attempt ends (bl-01). So Loop's merge check always merges current main into the branch and reruns the kind's checks on the result, never on a stale base, and Loop's own checks never share build daemons with agents.
- Conditional auto merge (story 100) is `policy: auto` with conditions, opt in per project. Loop merges a pull request only when every condition holds: the attempt is in the handoff state with a `verified` verdict, holdouts included; the merge check on current main passes and the merged head is the fingerprint that was verified, re-verified after main was merged in; CI is green and GitHub reports the pull request mergeable; no test was lost (the guards found no deleted tests, skips or weaker assertions); the diff touches no protected path (`WORKFLOW.md`, `scripts/`, `.github/`, Gradle files, `build-logic/`, `gradle/`, `.loop/`, `docs/adr/`, `CONTEXT.md`, configurable per project); the diff stays under a size limit. On top of these vetoes, a merge risk assessment (added 2026-11-14, adapted from Warp's `merge-risk` skill) rates alignment, impact, verification, rollback cost and error detection: facts from code set floors, a read only reviewer session gives the verdicts, and Loop computes a weighted score and band in code. Auto merge also needs a low band and no dimension at its worst verdict; a failed review falls back to `human`. When CI is pending or red, or any condition fails, delivery falls back to `human`. Reinstalling the running Loop build stays human. As built in m2-28, auto merge reads seven hard conditions (verified with holdouts, the merged head checked and shown, GitHub `mergeable` and `clean`, every deny guard in the merge check's `diff_policy`, no protected path, a diff under `max_diff_lines`, no `diff_base_unpinned`) and then the risk band; CI green joins the conditions with m3-15. As built in m3-15, the `ci` condition reads the Actions runs of the merged head through the CI wait's verdict, but it does so even under `merge.ci: none`, so with Actions refused no auto merge can pass (bl-146). The merge risk reviewer is wired to the claude-code runner only, so a Codex result never merges on its own (bl-145).
- The CI wait as built (m3-15): with `ci: actions`, the merged head is pushed, the issue waits as `Waiting(merge)` in phase `ci`, and Loop polls the Actions runs of that sha once per tick, at most every 30 seconds. Green merges; red, a failing no run rule and `ci_timeout_ms` record `delivery_failed` and wait for a new approval with the pull request left open. Time with no answer never counts. A run GitHub refuses for billing, with no step, reads as red today (bl-98).
- What the first real delivery taught (the pilot app, m2-27, 2026-11-14; details in `docs/spec-decisions.md`, First real delivery on the pilot app):
  - **Silence inside a turn.** A real iOS turn runs for most of an hour with long silences (a cold build, a simulator wait, a working subagent), so stall and turn timeouts are set per workflow (the pilot app: 75 and 90 minutes).
  - **The check reads the workspace.** Check scripts run from the agent's own worktree, so they belong in `guards.protect`, with every script they call.
  - **Subagents inherit the session.** They get the deny rules and the MCP tools, Loop's included. Token figures come from the main session only, about 2.5 times too low on a skill that delegates (bl-71); whether the dollar figure covers subagents is not checked yet, and a runner may report no cost at all.
  - **Operator MCP servers.** Without `--strict-mcp-config` an agent and its subagents get every MCP server of the operator (bl-72).
  - **Secrets out of shells.** A tracker token kept out of shell startup files breaks the read commands (bl-70).
  - **An unmoved base.** A merge check whose base has not moved reruns only `diff_policy`, so the suite runs once per verified commit.
  - **Runner access failures** (bl-39) use up every attempt today; a typed signal goes into `plugin-api` before the Codex runner copies the contract.

### Context control (M3)

- Occupancy is the input size of the latest request in a session, cached tokens included, reported as a `context_usage` event. Spend keeps using absolute totals.
- Limits are fractions of the model's window: soft 0.20 (rotate at the next turn boundary), hard 0.25 (interrupt and rotate), prompt 0.05. Windows have shipped defaults per model, overridable in config.
- As built (m3-11, m3-12): the soft limit is read on the latest report at the end of a turn that completed, only when the attempt would run a next turn. The hard limit is read on every report during a turn: Loop asks the session to interrupt, waits a grace of 10 seconds from the request, then cancels the runner's flow and records `interrupt_grace_expired`. An interrupted turn is `turn_completed` with `interrupted=hard_limit`, not a failure.
- What the exit runs taught (m3-27, m3-28): both ran at soft 0.20 and hard 0.21. The soft limit never fired, since every agent ended its last turn by handing off, which ends the attempt. The hard limit is what keeps the 25 percent rule, and only below 0.25: bl-132's first session crossed 0.21 at 0.2103, and stayed under 0.25 only because the largest jump between two requests was near 27k tokens. The shipped default of 0.25 cannot keep the rule (bl-144, M4). Loop's own `WORKFLOW.md` sets `hard_limit: 0.21`.
- Windows as built: Loop ships one, `anthropic/claude-opus-5-5: 1000000`. A model with no entry still has its occupancy recorded in tokens, with no fraction, one warning per run, no event, and it never rotates (bl-125, bl-138). Codex reports its window but the runner does not read it, so a Codex workflow names it under `context.windows` (`openai/gpt-6.1-sol: 475000` in m3-28). M4 makes that gap visible and ships the pinned Codex model's window.
- Rotation is not a retry and not a failure. It is counted, traced and capped (`max_rotations`, 5 per attempt by default).
- The prompt is built from slots in priority order: template and rules (never cut), the issue, verify findings, the session brief, lessons, context sources.
- Token counting as built (m3-13): no tokenizer of the models Loop runs is available offline on the JVM, so every count is the estimate, one token per 3 characters, rounded up, named `token_counter=estimate` in the trace. It overcounts, so a limit it holds also holds for the model. A runner with no known model takes the smallest window in `context.windows`. Preflight fails when the template alone is over the prompt limit.
- Prompt fill as built (m3-14): a prompt within its limit is sent as rendered. Over it, each slot gets the room the slots before it left and is cut by its own rule, with a note saying what was left out and where to read it. Tool outputs Loop shows the model are cut to `context.tool_output_tokens` (2000 by default), the full text kept as evidence or in the attempt file.
- The session brief as built (m3-10) is set on a retry, on a reused workspace and after a rotation, null on a fresh first dispatch. On the pilot app the skill's own progress file did most of that work across attempts; Loop's brief added 307 tokens.
- Claude Code continuation moves to one long lived process per attempt (`--input-format stream-json`), with m1-30's triaged decisions (2026-11-08): result lines end turns, no timer between turns, a respawn with `--resume` when the process died, and `session_stop_failed` recorded. m3-07 carries it and lands with session rotation: m3-11 is blocked by it, a file ordering edge. A rotation never respawns with `--resume`.
- Occupancy measures the main session. A subagent's window is its own and Loop cannot see it yet; that gap is recorded, not enforced. On the pilot app the work runs in subagents, so the 25 percent figures of m3-27 hold for the main session and say nothing of them. Their spend does count since bl-71, read from `model_usage_since_last_result`.
- A background wake is a turn (m3-07, m3-27): an agent that ends its turn while a subagent or a long run works starts the next turn when the wake reports back, so `agent.max_turns` counts wakes, not units of work (bl-155, M4). Nothing keeps the machine awake: an idle sleep failed an the pilot app attempt with a network error that took an attempt slot (bl-156, M4).
- The runner contract gains declared capabilities and the `context_usage` event type (m3-06) before the Codex runner is written. A runner without occupancy says so in `loop status` and at startup, and still gets the prompt limit and the turn cap. `loop doctor` does not exist and stays out of M3; M4 records it as a gap.
- The 25 percent rule covers every model call Loop drives, the merge risk reviewer of m2-28 included (m3-13). As built, the reviewer's prompt is counted against `prompt_limit` of its window, and a diff that does not fit is cut, the cut stated, which floors the band at `medium` (bl-137).
- A runner access failure (401, 403, an expired login, 429, overloaded), recognized by the runner on error results only, is a `runner_unavailable` category on `TurnFailed`: it takes no `max_attempts` slot and holds dispatch for the whole run, 1 minute doubling to 15, until a turn succeeds (bl-39).

### Machine limits (M3)

- Decided 2026-11-14 for m3-16 (carrying m2-21): opt in, in `<LOOP_HOME>/config.yaml` (`machine.max_concurrent_agents`, `machine.per_day_usd`, `machine.per_day_tokens`), read every tick; a malformed file fails preflight; no file means no machine limit.
- A machine slot is a row written in one `BEGIN IMMEDIATE` before launch and deleted at attempt end; only slots of processes whose run file names a live process count. The merge check takes a slot. A dispatch that cannot claim one is skipped with the reason `no_machine_slot`, and a store failure on the claim is a skip too, so the cap holds when the store does not answer. With a machine config, `loop status` prints the slots in use and the machine day spend.
- A crossed machine day budget skips dispatch with the reason `machine_budget`, lifting by itself when the window has room; no park, running attempts are not cut. Overshoot from other processes' live spend is accepted. `per_day_tokens` undercounted subagents until bl-71, and `per_day_usd` is blind to a runner that reports no cost, Codex included.

### The Codex runner (M3)

- As built in m3-17 to m3-23: `runner.kind: codex-app-server`, JSON-RPC over stdio to `codex app-server`, codex-cli 0.167.0 pinned, on a ChatGPT login (an API key variable alone does not log codex in). The runner sends no model, so a workflow picks it with `-c model=<model>` in `runners.codex.command`; a ChatGPT login refuses some models (bl-141).
- Loop's tools reach Codex as client tools of its thread, `move`, `comment` and `submit_result`, not `mcp__loop__*`, so a Codex prompt names them. The guards of Loop mode (denied commands, writes under the holdout folder, a strict start) match the claude-code runner's. The holdout read deny is declared but not enforced: Codex runs known safe reads with no approval request (bl-134, open).
- Codex declares occupancy and answers `interrupt()`, so rotation works with no orchestrator change (m3-23). It reports usage and rate limits but no cost, so dollar budgets do not see it. The ledger writes `"model": null` for it (bl-147).
- No merge risk reviewer exists for Codex yet (bl-145, M4). A second workflow file, `WORKFLOW.codex.md` with a label of its own, runs it beside `WORKFLOW.md` (local, not committed) on the same backlog (m3-28).
- Codex's window is 475,000 tokens against Claude's 1,000,000, so at 0.21 a full read of `Orchestrator.kt` (about 70k tokens) takes most of a Codex session's room. M4 routes orchestrator issues to the claude-code runner.

### Stack (confirmed in the first issue)

- Confirmed in m1-01. Versions and the reason for each change are in [the plan, section 14](../../docs/PLAN.md#14-tech-stack-confirmed-in-m1-01).
- Kotlin on JVM 21 with coroutines; Gradle Kotlin DSL with convention plugins and a version catalog; JUnit Jupiter with kotlin-test; ktlint (detekt later); kaml for YAML front matter; SQLite through sqlite-jdbc with plain JDBC (SQLDelight dropped in m1-02, revisited past three tables or a migration beyond `user_version`; M1 ended with two tables, and the M2 run store slice makes that call); liqp in strict mode as the template engine (spike in [spec decisions](../../docs/spec-decisions.md#template-engine)); Clikt for the CLI; distribution through the Gradle `application` plugin (`installDist`); CI runs `./gradlew build` on every push.
- The official MCP Kotlin SDK, `io.modelcontextprotocol:kotlin-sdk-server` 0.16.0, pinned in the version catalog as `mcp-kotlin-sdk`, serves Loop's tools to Claude Code since m2-16 (the `mcp` module, `loop tool mcp`).
- M3: JSON-RPC over stdio for the Codex app-server, its types generated from the published schema and the Codex CLI version pinned (m3-02, codex-cli 0.167.0). Later: out of process plugins. Koog arrives with manager agents after M3.

### Slicing

- Issues are vertical slices (tracer bullets), not layers. After the stack decisions, the first slice is a walking skeleton: `loop run` reads one `todo` issue from the `files` tracker, creates a plain workspace, runs the command runner (a script that writes a file and moves the issue to `review`), records the events, and `loop trace` prints the timeline. Each later slice thickens one path end to end with its tests.
- Issues are written for M1 only. M2 issues are written at the end of M1, M3 issues at the end of M2, M4 issues at the end of M3 (m3-29).
- Each issue names the area it touches, and issues that touch the same files declare `blocked_by` on each other: in M1, agents did part of a sibling issue (m1-18 did part of m1-34), and branches merged out of order conflicted. In M2 most slices touch the orchestrator, so they are chained.
- An issue sized for one attempt at `max_turns: 10` covers one or two stories, not six. A small M1 issue used about 14 million tokens in 17 minutes (m1-18).
- M3 is sliced in three lanes: one serial chain for the issues that edit the orchestrator, the CLI host, the store schema, the Claude Code runner and the prompt (lane A), the Codex module in new files (lane B), and small separate issues (lane C). From m3-01 on, Loop's own backlog runs in Loop mode, so an issue that edits `WORKFLOW.md` or Gradle files, protected paths, names that edit as a human step. M3 schedules five backlog issues, keeping their ids: bl-39 (runner access failures) before plugin loading, bl-72 (strict MCP) after the runner contract, bl-71 (subagent spend) after occupancy, bl-19 (the prompt says a workspace holds earlier work) before the session brief, and bl-70 (read commands without the tracker secret). bl-10 (area locks) stays in the backlog.
- What the M3 lanes taught: a protected path human step has to be named in the issue and done before dispatch: m3-17's agent stopped on a Gradle step the issue did not name (the module's dependencies), since editing its `build.gradle.kts` would refute the result. So a new module gets a human scaffolding issue first, with its full dependencies, and the issues that use it state "no Gradle edit needed". A verification or merge check pays `:cli:test`, 22 minutes in one fork, whenever core or a plugin changed (bl-142). bl-10 comes into M4 as a minimal area lock, since its exit runs two code issues in parallel on one app.
- M2 also schedules three backlog issues, keeping their `bl-` ids: bl-01 (the end of an attempt kills a shared Gradle daemon), before Loop runs checks host side; bl-02 (sync with main on every dispatch), before delivery; bl-06 (answers reach the agent only through the description), before GitHub answers arrive as comments.

## Testing decisions

- **What makes a good test:** it checks behavior visible from outside (tracker file states and comments, workspaces and branches, CLI output, recorded events), never internal classes or call order. A test that survives a refactor of the orchestrator's internals is a good test.
- **Seam 1, the main one: the `loop` CLI on a temporary project.** Each test creates a folder with a `WORKFLOW.md` and issue files, runs Loop with the command runner or a fake runner, and asserts on the issue files, the workspaces and branches, and the output of `loop trace`, `loop show` and `loop status`. This covers the walking skeleton and most slices: continuation, retries and backoff, parking, the token cap, `needs_info`, reconciliation, concurrency and `loop stop`. In M2 it covers verdicts and delivery on a playground; in M3, rotation.
- **Seam 2: the plugin interfaces.** Contract tests every `TrackerAdapter` and `AgentRunner` implementation must pass. The Claude Code runner is tested against recorded stream transcripts, so no model call is needed. In M2, check types get the same treatment; in M3, plugin loading.
- **The spec's conformance tests** (its test matrix) run against the public API of the Symphony core components the spec names (workflow loader, config, workspace manager, orchestrator scheduling rules). The spec defines this seam; Loop does not choose it.
- **A fake runner** applies scripted patches and returns scripted events, so the whole lifecycle runs without models: pass, failure and retry, stall, token threshold crossed, opt in token ceiling crossed, question to `needs_info`, and from M2, refuted by a check, refuted by a guard, holdout failure, budget exhausted.
- **Time** is controlled in tests (backoff, stalls, polling) so tests run fast and deterministically.
- **Acceptance criteria commands** of each issue are part of its tests and are run by the agent with evidence saved, and in M2 by Loop as visible checks.
- **The playground (M2):** seeded issues in a `files` tracker and a test GitHub repository, including one that tempts a test deletion.
- **Real integration (M3):** the spec's real integration profile, split in two: GitHub against the playground (m3-25) and Codex (m3-24), so the GitHub half does not wait on Codex access. As built, JUnit tests gated by `LOOP_REAL_INTEGRATION` (`codex`, `github`), skipped when unset, failing when a named target lacks what it needs; both enabled runs passed on 2026-11-17.
- **Runner contract (M3):** contract tests every `AgentRunner` implementation passes, next to `TrackerAdapterContract` (m3-06). The Codex runner is tested against a scripted fake app server built from the pinned schema, then against recorded real sessions (m3-03, five scrubbed recordings).
- **Conformance audit (M3):** m3-26 checked every matrix row against the code and every named test by script; one row stays partial (bl-153).
- **Under the Actions refusal:** since 2026-11-16 no CI run checks a merge, so pull requests merge on the local `./gradlew --no-daemon build` and Loop's merge check, and CI behavior is proven on recorded Actions answers, never on live runs.
- **Prior art:** M1 built the main seam: CLI tests on a temporary project with the command runner or the fake runner, the conformance module for the spec's test matrix, and recorded stream transcripts for the Claude Code runner. New tests follow those.

## Out of scope

- Manager agents (experts, triage, reviewer, retro) and lessons across issues: M6. Until then, `review:` is optional in verify contracts.
- Shared devices and resource providers, reactions to CI and review comments, the terminal human channel, follow ups, the multi tracker registry, the `content` and `research` kinds, `loop report`, `loop query`, `loop replay` and OpenTelemetry export: M4, in [Loop, M4](loop-m4.md). The human channel beyond the terminal stays later.
- Permissions hardening (sandbox for every worker, a worker token that cannot merge, audit after every attempt): M5.
- Other trackers and runners (Linear, Jira, ACP), best of n and councils: M7.
- Remote mode and running Loop on a hosting platform such as Maze: M8.
- A web dashboard or the spec's HTTP API in the core.
- Loop doing the work of an issue itself, or writing issues.
- Automatic reinstall of the running Loop build.

## Further notes

- The full design, with configuration examples, lives in the plan; the glossary and ADRs are the reference for terms and decisions.
- Codex access, as of 2026-11-14: the `codex` CLI is not installed on the development machine and access is unconfirmed. m3-02 settles it or records its absence. The Codex lane runs against a fake server without access; recorded sessions, the real integration profile and the Codex exit need it. Without it, M3 cannot reach full Core Conformance, and m3-26 and m3-28 carry to M4. Settled on 2026-11-16 by m3-02: codex 0.167.0 is installed and pinned, with a ChatGPT login, so recorded sessions, the real integration profile and the Codex exit can run in M3. All three ran (m3-03, m3-24, m3-28), so nothing carries to M4 for lack of access.
- GitHub Actions refuses every job for billing since 2026-11-16. Loop's own workflow runs `merge.ci: none` (live, not yet committed; the committed `WORKFLOW.md` still reads `ci: actions`), and every Loop pull request of late M3 merged after `loop approve` (bl-146).
- The repository was private during M1 to M3 and is public since November 2026, so everything committed (traces, examples, docs) must be safe to publish.
- The M2 workload project is the pilot app (decided 2026-11-11), an iOS app with its backlog on GitHub (`Soules-Studio-Ltd/example-app`), checked with `fastlane test`. Its checks need an iOS simulator, so the workload slice settles concurrency for it before resources land in M4.
- Success for the whole effort: an issue goes from ready to delivered with no human touch and Loop can prove it; trackers and runtimes swap by config; Loop runs on a laptop.
