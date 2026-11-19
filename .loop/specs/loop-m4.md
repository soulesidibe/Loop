---
id: spec-loop-m4
title: Loop, parallel production and the retirement of the manager skill (M4)
labels: [ready-for-agent]
---

## Problem statement

M3 proved that Loop can deliver a verified issue with either runner and keep a main session under 25 percent of its window. It did not prove that Loop can replace the manager skill on real work:

- Loop reads one tracker per project. My work lives on GitHub for apps and in local files for Loop and side work, and I cannot run both at once.
- Every issue is `code`. Posts, docs and research go through the skill or by hand.
- Two agents on one app cannot share devices: there is no resource lock, no simulator provider, and nothing stops two issues from editing the same area.
- When a run needs me, nothing tells me. An agent cannot ask me a question and wait, cannot file a follow up, and I cannot send work back with a reason.
- A pull request that goes red, gets a review comment or conflicts with main after delivery waits for me to notice.
- I cannot answer "what did Loop deliver this week, at what cost, with how many touches" without reading the store by hand.
- M3 left reliability gaps that bite on long unattended runs: the default hard limit cannot keep the 25 percent rule, background wakes use up turns, an idle sleep or a network loss costs an attempt, a GitHub billing refusal reads as a red build, and no pull request can auto merge while Actions is refused.

## Solution

M4 makes Loop the harness I run every day, in parallel, and retires the manager skill. In order:

- **Reliability first,** since Loop builds M4 itself: limits that keep the 25 percent rule by default, wakes that are not turns, network and sleep failures that cost no attempt, auto merge that works under `ci: none`, a merge risk reviewer for Codex, and `loop drain`.
- **The multi tracker registry:** `trackers:` next to the spec's `tracker:`, namespaced ids, each tracker polled and reconciled on its own, tool writes routed to the right tracker, and a minimal area lock for parallel work.
- **Kinds:** a kind map with `code` unchanged, context sources per kind, and the `content` and `research` kinds.
- **Resources:** declared devices, `request_resource` and `release_resource`, locks in the store, and the iOS simulator and Android providers.
- **The human side:** a terminal channel that tells me when a run needs me, `ask` for human questions, `loop send-back`, and `file_followup`.
- **Reactions** to merge conflicts, red CI, review comments and approved green pull requests.
- **Observability:** `loop report`, `loop query`, `loop replay` and an OpenTelemetry export plugin.
- **The exit:** a wave on the pilot app with two code issues in parallel and a non code issue, with GitHub and `files` trackers active at once, then the manager skill retired with its remaining gaps named.

## User stories

### Reliability

101. As a Loop user, I want the default hard limit to keep every session under 25 percent of its window, so that the rule holds without a setting in each workflow.
102. As a Loop user, I want a session whose model has no window in `context.windows` recorded as an event and shown in `loop status`, and the pinned Codex model's window shipped, so that a session never runs unlimited in silence.
103. As a Loop user, I want a background wake (a subagent or a long run reporting back) never counted as a turn, so that `agent.max_turns` counts work, not wakes.
104. As a Loop user, I want the usage of a wake that ends after the stop grace counted in spend, so that a rotation or a stop never loses tokens.
105. As a Loop user, I want the auto merge `ci` condition to follow the kind's `ci` setting, with green under `ci: none` meaning the merge check passed, so that safe changes merge on their own while Actions is unavailable.
106. As a Loop user, I want a merge risk reviewer for Codex results, run outside my own checkout, so that a Codex result can auto merge like a Claude Code one.
107. As a Loop user, I want a CI run that GitHub refused to start (billing, no step) read as not started, not red, so that a billing problem never sends verified work back as broken.
108. As a Loop user, I want a lost network read as the runner being unavailable, using no attempt, so that a dropped connection or a sleep never parks an issue.
109. As a Loop user, I want my machine kept awake while attempts run, so that an idle sleep never fails an attempt.
110. As a Loop user, I want `loop drain` to finish the running attempts, start nothing new and stop, so that I can end a run without cutting work.

### Several trackers

111. As a Loop user, I want to add trackers under `trackers:` while `tracker:` stays the primary one, so that one project works its GitHub issues and its local files at once.
112. As a Loop user, I want every issue named with its tracker (`gh:454`, `local:research-03`) in the store, logs, the ledger and commands, while the primary tracker's issues keep their workspace keys and branches and a single tracker project still shows bare ids, so that ids never collide and nothing in flight is lost.
113. As a Loop user, I want each tracker polled and reconciled on its own, so that a failing tracker never stops the others.
114. As an agent, I want `move` and `comment` to reach my issue's own tracker, so that I never write to the wrong one.
115. As a Loop user, I want an optional concurrency limit per tracker, so that one backlog cannot take every slot.
116. As a Loop user, I want a line such as `Blocked by lin:MKT-12` in an issue's body to block it across trackers, and kept blocked when the tracker id is unknown, so that dependent work waits whatever tracker holds its blocker and a typo never unblocks it.
117. As a Loop user, I want two issues of the same area never worked at once, so that parallel agents do not edit the same files.

### Kinds

118. As a Loop user, I want each issue matched to a kind from its tracker, labels or state, with `code` working as before, so that adding kinds changes nothing for code.
119. As a Loop user, I want each kind to declare its context sources as pointers the agent reads on demand, so that non code issues get what they need without a bigger prompt.
120. As a Loop user, I want a `content` kind whose deliverable is files, checked by commands and attached to the issue for my approval, so that posts and docs go through Loop.
121. As a Loop user, I want a `research` kind whose report is posted on the issue, with its required sections present and its sources reachable, so that research is verified before I read it.

### Resources

122. As a Loop user, I want devices declared under `resources:` and granted to agents through `request_resource` and `release_resource` in queue order, with locks in the store, so that two agents never share a device.
123. As a Loop user, I want resource limits enforced, a read only check run after each release, and a check able to hold a resource, so that a device comes back as it was lent and two checks never share a simulator.
124. As a Loop user, I want an iOS simulator provider, so that two issues of the pilot app verify in parallel, each on its own simulator.
125. As a Loop user, I want an Android provider that boots a configured AVD when no device is connected, so that Android work needs no device on my desk.

### The human side

126. As a Loop user, I want `loop approve` to say what actually happens next for the issue, so that its message never contradicts the outcome.
127. As a Loop user, I want to be told in the terminal and through a hook when a run needs me (a park, an approval, a question), so that I do not have to watch `loop status`.
128. As an agent, I want `ask` for a question only a human can answer, so that my issue waits as `Waiting(ask)` and resumes with the answer from `loop answer`.
129. As a Loop user, I want `loop send-back <issue>` with a reason, taking no attempt slot, so that work I reject returns to its agent with my words.
130. As an agent, I want `file_followup` to create a follow up issue in the parent's tracker, needing triage, so that findings outside my issue are not lost.

### Reactions

131. As a Loop user, I want a delivered pull request that conflicts with main sent back to its agent, so that conflicts are fixed without me.
132. As a Loop user, I want a red CI on a delivered pull request sent back to its agent, a set number of times, so that CI failures are fixed without me.
133. As a Loop user, I want review comments that request changes sent back to the agent, and escalated to me after a set time, so that review loops do not stall.
134. As a Loop user, I want to be told when a pull request is approved and green, so that I merge it without polling GitHub.

### Observability

135. As a Loop user, I want `loop report --since 7d` to show issues delivered by kind and tracker, delivered with zero human touches, cost per delivered issue, refutation reasons and a comparison by runner, model and template, so that I can measure Loop.
136. As a Loop user, I want `loop query "<sql>"`, read only, over the store, so that I can answer questions the report does not.
137. As a Loop user, I want `loop replay <attempt>` to render the prompt again with the current config and diff it with the original, so that I see what a template change does.
138. As a Loop user, I want `loop replay <attempt>` to run verification again on the recorded workspace fingerprint, and to say so when the workspace is gone, so that a verdict can be replayed.
139. As a Loop user, I want an OpenTelemetry export plugin, so that traces reach the tools I already use.

### Exit

140. As a Loop user, I want a wave of at least 3 code issues on the pilot app, 2 in parallel, plus one content or research issue, delivered through Loop in one wave with GitHub and `files` trackers active at once and any restart recorded, so that parallel production is proven on real work.
141. As a Loop user, I want the manager skill retired, with every row of PLAN section 16 either covered by Loop or named as a gap, so that I stop maintaining the skill.

## Implementation decisions

### Decisions taken in m3-29

- **The exit app is the pilot app.** The iOS simulator provider (m4-27) gates the exit. The Android provider (m4-28) is in scope but does not gate it.
- **A minimal bl-10 area lock lands in M4** (m4-17), since the exit runs two code issues in parallel on one app.
- **`ask` is built** (m4-31, m4-52). `loop doctor` and the PLAN section 16 rows that belong to M6 (expert agents, the triage agent, brief extras through lessons, triage scope estimates) are recorded as gaps at retirement (m4-44), not built.
- **`loop report` prints human touches as not reliable** until bl-64 is answered: the approve touch is rarely counted today.
- **bl-151 is deferred.** After a network hold, a session resumes only below the soft limit (m4-46); the wider resume question waits.
- **The 25 percent claim of the exit is for main sessions.** Subagents run in windows Loop does not measure (the subagent gap of M3).
- **Default limits:** `hard_limit` 0.21, `soft_limit` stays 0.20. On a 475,000 window the 4,750 token gap means some Codex rotations become hard interrupts; that cost is accepted (m4-02).
- **`agent.max_wake_turns`,** default 30, caps wake turns and ends the attempt with exit reason `max_wake_turns` (m4-03).
- **The Codex result is reviewed through the claude-code command** (`runners.claude-code.command`), bl-145's first option (m4-06).
- **A refused CI run is `not_started`:** under `ci: actions` it ends in `delivery_failed` and an unapproved `Waiting(approval)`, never counts as no run, and no reaction treats it as red (m4-07).
- **`unstable` passes the `ci` condition under `ci: none`;** `dirty`, `blocked`, `behind` and unknown mergeability still fail it (m4-05).
- **Sleep rule, option (a):** a failure after a sleep surfaces as a network error, so it takes no slot. No sleep detection is built (m4-08).
- **During a drain,** a retry of an issue whose attempt was running still runs; a retry of any other issue does not start (m4-10).
- **`tracker.id` is a Loop key** in the spec's block, and each extra tracker has its own `required_labels` (m4-11, m4-13).
- **The primary tracker keeps bare workspace keys and branches;** only an extra tracker's issues get `<tracker id>:<identifier>` keys. Old rows read as the primary's ids (m4-12). A project with no `trackers:` shows bare ids (m4-47).
- **`agent.max_concurrent_agents_by_tracker`,** a map of tracker id to a positive count, beside the spec's limits (m4-15).
- **An unknown tracker id in a blocker line fails closed:** the issue stays blocked with `blocked_cross_tracker` naming the tracker (m4-16).
- **The area lock:** repository relative paths, no globs, overlap on declared areas only, busy while claimed, no reordering, no area never blocks. GitHub carries it as a column 0 `Area:` body line (m4-17).
- **Kinds:** no match on a front matter field in M4 (m4-18); a kind may set its own `template` (m4-19); a kind declares `workspace: git` or `workspace: plain`, and `populate: empty` runs none of the four workspace hooks (m4-20).
- **Key names:** `deliverable.files`, `deliverable.report`, `delivery.attach: true` and `delivery.attach_auto.enabled: true` for `policy: auto` (m4-21, m4-22).
- **Kinds with their own runner are left out,** recorded as a gap in PLAN 6.2 and 20 (m4-45).
- **`request_resource` is answered by the tool process,** which takes or queues the lock in the store; `prepare` and `release` run in `loop run`, so device processes are its children (m4-25).
- **A `resource:` key on `command` checks:** the verification and the merge check take a lock of that kind, and a wait in the queue is never a failed check (m4-26).
- **Every `ask` goes to the human in M4,** and the tool description says it is only for a question a human must answer (m4-31).
- **A send back takes no `max_attempts` slot;** the slot stays for automatic reactions (m4-32).
- **Reactions watch only waits that hold a pull request.** An issue delivered to a handoff state with no wait is not watched in M4 (m4-34).
- **Verification is extracted to `loop.core.verify`** in its own issue (m4-54), so `loop replay --verify` (m4-41) edits no orchestrator code.
- **The exporter is an `EventSink` behind the run's `Redactor`,** loaded through an `EventExporterProvider` (m4-42).
- **The exit is one wave,** with any restart of `loop run` recorded (m4-43).
- **The usage script row is covered** by the ledger and `loop show --cost`, per attempt; Codex shows no cost (m4-44).

### Rules for every M4 issue

- One or two stories, sized for one attempt at `max_turns: 10`.
- Lane A logic goes in a new package (`loop.core.trackers`, `loop.core.kinds`, `loop.core.resources`, `loop.core.reactions`, `loop.core.channel`, `loop.core.replay`, `loop.core.verify`). The orchestrator edit is limited to call sites, stated as a criterion: `Orchestrator.kt` is 5,327 lines, about 70k tokens for one read. PLAN's `loop-ext/` layout does not exist; the packages replace it.
- Lane A runs on the claude-code runner, since a Codex window at 0.21 leaves too little room for `Orchestrator.kt`.
- Lane B and C issues state "no Gradle edit needed" as a checked premise and are blocked by m4-01, the scaffolding issue, so no agent stops on an unnamed human step as m3-17 did.
- While Actions refuses jobs, every issue that touches CI is proven on recorded Actions answers, and says so.
- Runnable criteria open with a backticked command followed by `passes`. Prose criteria are marked `(judgment)`, or name the test that proves them.
- Not gating the exit: reactions (m4-34 to m4-37), observability (m4-38 to m4-42, m4-54), the files label write (m4-53) and Android (m4-28). The exit run can start while they finish.

### Human prerequisites

- m4-00: `:cli:test` runs in parallel forks after bl-07 is checked, and the CI timeout is raised above the suite (bl-142); the live checkout runs the committed `ci: actions` again, now that the public repository has free Actions minutes; the `loop.workspace_reused` prompt step of bl-129 is done; run 43779352010 and its jobs are recorded for m4-07, and the wake turn lines of attempt 10c2f759 are copied for m4-03, since agents cannot reach `gh` or `~/.loop/attempts/`.
- m4-01: the modules `resource-ios`, `resource-android` and `export-otel` exist with their full dependencies and runtime wiring, and `plugin-api` holds empty `HumanChannel` and `ResourceProvider` interfaces. The terminal channel is a core implementation, not a module. The loaders come with m4-24 and m4-42.

### Reliability (m4-02 to m4-10, m4-46)

- The default `context.hard_limit` moves to 0.21, with the reasoning of bl-144 recorded; the soft limit stays 0.20, the early rotation for multi turn attempts.
- In Loop mode with a runner that declares occupancy, a model with no window records `context_window_unknown` and shows in `loop status`; the pinned Codex model's window ships in the defaults (bl-138, bl-125).
- A wake never counts against `agent.max_turns`, has its own `agent.max_wake_turns`, and the runner's idle channel is bounded (bl-155, bl-114, bl-122). A wake read after the stop grace reports its usage (bl-152, findings 1 to 4).
- The auto merge `ci` condition reads Actions runs only under `ci: actions`; under `ci: none` it passes once the merge check passed (bl-146).
- A merge risk reviewer for the Codex runner; no reviewer ever checks out in the operator's checkout (bl-145, bl-100).
- bl-134 (holdout reads on Codex) stays a human spike. Until it is decided, a Codex workflow with holdout checks does not auto merge.
- A CI run with no started job is `not_started`, not red, proven on recorded run 43779352010 (bl-98).
- A network loss is `runner_unavailable` and takes no `max_attempts` slot; the next attempt starts fresh with the session brief (m4-08, bl-123, bl-156). m4-46 resumes the Claude Code session when the last occupancy was below the soft limit.
- `loop run` holds an idle sleep assertion while attempts run (m4-09, bl-156).
- `loop drain` is a new run state: running attempts finish, nothing new starts, the run stops (bl-110).

### Several trackers (m4-11 to m4-17, m4-47 to m4-49)

- The spec's `tracker:` stays the primary tracker, with the id of its `id` key or `default`; `trackers:` adds others. A Loop `WORKFLOW.md` always keeps `tracker:`, so other implementations still pass preflight.
- Ids are namespaced in the core, the store, the ledger, events and logs (m4-12). `issue.identifier` and the `LOOP_*` names stay native; `issue.tracker` and `LOOP_TRACKER_ID` are new. Commands and agent tools take bare or namespaced ids, a bare id meaning the primary (m4-47).
- Each tracker is polled and reconciled on its own, with its own states and `required_labels`; candidates are merged and sorted by the spec's rule. Ledger rows hold the tracker id (m4-13). One GitHub client per origin and token, so one rate limit hold covers the tracker, delivery and the CI wait (m4-48, bl-50).
- Tool writes are routed by issue and read `native_ref`, closing the partial conformance row (m4-14, bl-153).
- A per tracker concurrency limit beside the spec's limits; the requeue of waiting retries is quieter (m4-15, bl-77).
- Cross tracker blockers are a body line, `Blocked by <tracker>:<id>`; within a tracker, native `blocked_by` stays (m4-16).
- The area lock is an optional `area` field. A candidate whose area is busy is skipped with `candidate_skipped reason=area_busy`, no model involved (m4-17). Base fetches get a mutex per repository, so two verifications at once never degrade one to `diff_base_unpinned` (m4-49, bl-67, the fetch part).

### Kinds (m4-18 to m4-23)

- `KindsConfig` holds only `code` today. It becomes a map of kinds with a matcher (`default`, label, tracker id or state), `code` unchanged; ledger rows hold the matched kind, and hooks get `LOOP_KIND` (m4-18).
- Context sources per kind (PLAN 8.6) are pointers in the prompt's context sources slot, read on demand; a kind may set its own `template` (m4-19).
- Verification and the workspace fingerprint work on a `workspace: plain` kind (`tree:<hash>`), before any non code kind; preflight refuses git only settings on it (m4-20).
- `content`: `deliverable.files` on a worktree, checked by `command` checks the workflow declares (banned words and dashes, length, markdown lint), not new check types (m4-21). Delivered by `delivery.attach`, `human` by default (m4-22).
- `research`: `populate: empty`, a `deliverable.report` posted on the issue, required sections present and sources reachable (m4-23).

### Resources (m4-24 to m4-28, m4-50, m4-51)

- `resources:` declares each resource: kind, id, what it can and cannot test, restore list and read only check. `ResourceProvider` gets its members and a contract, and providers load through `ResourcePlugins.kt` (m4-24).
- Locks and queue rows live in the run store, machine wide; grants are in queue order, and every lock frees at attempt end or at the startup sweep (m4-50).
- `request_resource` and `release_resource` reach both runners, over MCP for Claude Code (m4-25) and as client tools for Codex (m4-51).
- Limits: at most 2 Android devices and emulators together, at most 2 self managed iOS simulators. On release the read only check runs, and a failure asks the agent to restore; a resource still dirty is never lent. A `command` check may hold a resource (m4-26).
- Providers implement `ResourceProvider`. The Android one boots a configured AVD when no device is connected (m4-27, m4-28).

### The human side (m4-29 to m4-33, m4-52, m4-53)

- `loop approve` messages match the outcome (m4-29, bl-69, bl-66), a CLI text fix with no channel.
- `HumanChannel` with `terminal` as its core implementation, plus a notification hook, fired on a park, an approval wait and a question (m4-30, bl-109).
- `ask` in M4 is for human questions only: it returns `pending` and the issue waits as `Waiting(ask)` (m4-31). `loop answer` ends the wait and the next attempt carries the answer (m4-52). Routing engineering and design questions to manager agents is M6.
- `loop send-back` ends a `Waiting(approval)` and requeues the issue with the human's reason (m4-32, bl-99). bl-89's `loop reject` is the same command.
- `file_followup` reaches both runners and creates the issue in the parent's tracker unless the kind says otherwise (m4-33). The `files` tracker's label write becomes safe for front matter in its own issue (m4-53, bl-16).

### Reactions (m4-34 to m4-37)

- Configured under `reactions:` as in PLAN 6.7. Each reaction resumes the issue's workspace with a continuation turn, `loop.retry_kind` = `reaction`, then verifies again. A reaction attempt takes a `max_attempts` slot.
- `ci_failed` is proven on recorded Actions answers (bl-89) and never fires on `not_started`. Under `ci: none`, `approved_green` reads green as the merge check passed.

### Observability (m4-38 to m4-42, m4-54)

- `loop report --since` reads the ledger. It is blocked by bl-147 (the model in the ledger) and by the tracker and kind columns of m4-13 and m4-18. A runner that reports no cost shows its cost as not reported, never zero; human touches are marked not reliable until bl-64.
- `loop query` opens the store read only.
- `loop replay` is three issues: the prompt render and diff (m4-40, lane C), the extraction of verification to `loop.core.verify` (m4-54), then verification on the recorded fingerprint, handling a workspace that is gone (m4-41).
- The OpenTelemetry exporter is a plugin in `export-otel`, wrapped in the run's redactor.

### Exit and retirement (m4-43 to m4-45)

- m4-43, the exit run, is a human issue, blocked by m4-26, m4-27 and m4-49. m4-44 retires the manager skill, naming the PLAN 16 rows that gate retirement and recording `loop doctor` and the M6 rows as gaps; until M6 the issue skill's own subagents play the expert role. m4-45 writes the M5 issues, carries the open M4 issues and records the per kind runner gap.

### Slicing

- Lane A is one serial chain, in the order reliability (m4-02 to m4-08, m4-46, m4-10), trackers (m4-11, m4-12, m4-47, m4-13, m4-48, m4-14 to m4-17), kinds (m4-18 to m4-23), resources (m4-24, m4-50, m4-25, m4-51, m4-26), human side (m4-30, m4-31, m4-52, m4-32, m4-33), reactions (m4-34 to m4-37), then m4-54 and m4-41. Lane B holds the providers and the exporter (m4-27, m4-28, m4-42). Lane C holds small separate issues (m4-09, m4-29, m4-38 to m4-40, m4-49, m4-53).
- 26 backlog issues are carried into M4 and closed as moved (24 from the needs-triage set, plus bl-50 and bl-16 from the deferred ones), each closing comment naming the parts not carried. Backlog issues that stay ready for an agent declare blockers against the M4 issues that share their files.

## Testing decisions

- **The seams of M1 to M3 stay:** the `loop` CLI on a temporary project with the command or fake runner, contract tests per plugin interface, recorded transcripts for runners, and the conformance module.
- **New contracts:** `HumanChannel` and `ResourceProvider` get contract tests next to `TrackerAdapterContract` and `AgentRunnerContract`.
- **Several trackers** are tested with a `files` tracker and a GitHub tracker on recorded responses in one temporary project: namespaced ids, a failing tracker, routed writes and cross tracker blockers.
- **The fake runner** gains scripted `ask`, `request_resource`, `release_resource` and `file_followup` calls and scripted wakes, so the waits, grants and turn counting run without models.
- **Resource providers** are tested against fake `xcrun simctl` and `adb` scripts, then proven on the pilot app in the exit.
- **CI and reactions** are proven on recorded Actions answers while Actions is refused, including run 43779352010.
- **Non code kinds** are tested on workspaces with and without git, checking the `tree:` fingerprint and the delivery to the issue.
- **The exit** (m4-43) is the real proof: traces of the wave, the main session peaks and the human touches, recorded in the issue.

## Out of scope

- Manager agents (experts, triage, reviewer, retro), `ask` answered by agents, triage scope estimates and lessons: M6.
- `loop doctor`: recorded as a gap at retirement.
- Permissions hardening (a sandbox for every worker, a worker token that cannot merge, an audit after every attempt, bl-28): M5.
- Human channels beyond the terminal (Telegram, Slack, HTTP) and the `ops` kind.
- New check types (bl-14, bl-116, bl-118): after M4.
- Linear, Jira and other trackers and runners: M7. Cross tracker work is proven with `files` and GitHub.
- Out of process plugins.
- bl-151, the resume question beyond m4-46.
- Remote mode: M8.

## Further notes

- GitHub Actions refused every job for billing from 2026-11-16 until the repository went public, which made its minutes free. Issues written in that window prove CI behavior on recorded answers. m4-05 and m4-07 still matter for any repository whose Actions are refused or unused.
- Workloads: Loop's own backlog in Loop mode on the `files` tracker, and the pilot app on GitHub for the exit.
- The full design lives in PLAN sections 6.1, 6.2, 6.5 to 6.10, 8.6, 10.4, 12 and 16; the M3 lessons in `docs/spec-decisions.md` (Codex exit run, Claude Code exit runs) and in [Loop, M1 to M3](loop-m1-to-m3.md).
- M4 issues are written in m3-29 from the approved outline and its review; M5 issues at the end of M4 (m4-45).
