---
id: m4-43
title: "M4 exit: a wave on the pilot app with two code issues in parallel and a non code issue"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: [m4-26, m4-27, m4-49]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 140, the exit of PLAN 17 M4. A human issue: I run the wave and record it here, like m3-27.

- **The wave:** at least 3 code issues on the pilot app (an iOS app with its issues on GitHub), with 2 running in parallel at some point, plus one `content` or `research` issue, all delivered through Loop in one wave. A restart of `loop run` (a `loop stop` to fix a config, for example) is allowed and recorded here, and at least one run polls both trackers.
- **Two trackers at once:** The pilot app's `WORKFLOW.md` keeps its GitHub `tracker:` as the primary one and adds a `files` tracker under `trackers:` (m4-11 to m4-16, m4-47, m4-48) holding the non code issue. Both are polled in the same run, and ids show their tracker (`gh:<n>`, `local:<id>`).
- **Parallel safety:** the two parallel code issues each get their own simulator through `request_resource` and the iOS provider (m4-24 to m4-27), and their `area` fields differ or the area lock of m4-17 skips one with `candidate_skipped reason=area_busy`. Each verification and each merge check gets its own simulator through the `resource:` key of its `command` check (m4-26). With the limit of 2 Loop simulators, a check waits in the queue while two agents hold theirs; the wait is not a failed check and takes no `max_attempts` slot (the pilot app has 2).
- **Limits:** the hard limit below 0.25 of m4-02 (the m3-27 runs used 0.21), `max_turns` 10.
- **The 25 percent claim is for main sessions only.** The pilot app work runs in subagents (the app's expert and reviewer subagents), each in its own window, which Loop does not measure: the subagent gap of M3. The figures recorded here say so next to them, and the subagents' spend is in the token figures.
- **Not gating:** reactions (m4-34 to m4-37), observability (m4-38 to m4-42, m4-54), the files tracker label write (m4-53) and the Android provider (m4-28). The wave can start while they finish. If m4-09 (the idle sleep assertion) is not merged yet, I keep the Mac awake by hand (`caffeinate`), since an idle sleep failed an attempt in m3-27 (bl-156).
- **Actions:** while GitHub refuses Actions jobs for billing (since 2026-11-16), the pilot app merges under `ci: none`, green meaning the merge check passed (m4-05).

Setup, before the run. The pilot app's `WORKFLOW.md` and `scripts/` are protected there, so each step is mine, by hand:

1. `id: gh` on `tracker:`, so ids show `gh:<n>` (m4-11 gives the primary the id `default` otherwise).
2. A `trackers:` entry with id `local`, kind `files`, holding the non code issue.
3. The `content` or `research` kind under `kinds:`, with its matcher (m4-18).
4. `resources:` with two `ios-simulator` entries (m4-24, m4-27).
5. `resource: ios-simulator` on the `command` check of `kinds.code.verify` (`./scripts/loop-check.sh`), and the script using the simulator Loop passes as `LOOP_RESOURCE_<KEY>` env (m4-26) instead of claiming one through the app's own simulator script.
6. `max_concurrent_agents` from 1 to 2 or 3.
7. The prompt and the Loop variant of the issue skill tell agents to call `request_resource` and `release_resource` instead of the app's own simulator script.
8. Install the Loop build that holds `resource-ios` (`./gradlew installLoop`), and record its build id.

Out of this issue: retiring the manager skill (m4-44) and Android work.

## Acceptance criteria

- [ ] The setup above is recorded in a comment here, with the install build id.
- [ ] At least 3 code issues of the pilot app are delivered through Loop in one wave, 2 of them running in parallel, each agent on its own simulator granted by `request_resource`, with `loop trace` of each linked in a comment here. Any restart of `loop run` is recorded with its reason.
- [ ] One `content` or `research` issue from the `files` tracker is delivered in the same wave, its delivery (attachment or posted report) linked here.
- [ ] Both trackers were polled in the same run, at least once, shown by the namespaced ids in `loop status` or the ledger, quoted here.
- [ ] No main session went above 25 percent of its window, read from the ledger or `loop status`, with the subagent gap stated next to the figures.
- [ ] The human touches of each issue are counted by hand and recorded here, next to what the ledger says (bl-64 is not settled).
- [ ] Resource grants and releases, for agents and for checks, and any `area_busy` skip, are quoted from `loop trace` (judgment).
- [ ] What the wave taught is recorded in `docs/spec-decisions.md` and on the M4 exit line of `docs/PLAN.md`, defects filed as `bl-<nn>` issues labelled `needs-triage` (judgment).

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-27 soule: the wave runs on Sigma (Soules-Studio-Ltd/Sigma, a KMP app with an iOS target and its issues on GitHub) instead of Ayyam, since an Ayyam release is in progress and must not be disturbed. Sigma's Loop setup mirrors Ayyam's (GitHub tracker, Loop's worktree hooks, `scripts/loop/check.sh` claiming a simulator), so every setup step and criterion applies with Sigma's paths. Android stays out, as above. The setup goes through a pull request on Sigma, not the main checkout.

2026-11-27 soule, the setup: Loop installed at build 56976ce (`~/.loop/install/build-info.properties`, commit 56976ce807d4). Sigma's Loop files were untracked in its main checkout, so a workspace (a git worktree from main) had no `scripts/loop/check.sh` and no `iplmt-loop` skill; Sigma PR 366 tracked them and made the setup:
1. `tracker.id: gh`.
2. `trackers:` with `id: local`, kind `files`, `.loop/issues/` beside the workflow.
3. A `research` kind, `match: { tracker: local }`, `workspace: plain`, `populate: empty`, `deliverable: { report: report.md }`, `require_sections: [Summary, Findings, Sources]`, a `check-sources.sh` command check, `delivery.attach: true`, its own template `.loop/templates/research.md`. No `on_delivered`: preflight checks `move_to` against the primary tracker's states (bl-266), so an approved report moves to `review`.
4. `resources:` `sim-a` and `sim-b`, `ios-simulator`, iPhone 17 Pro; `prepare_timeout_seconds: 900` added during the wave (see below).
5. `resource: ios-simulator` on `./scripts/loop/check.sh`, which uses `LOOP_RESOURCE_UDID` and falls back to its own pool lease.
6. `max_concurrent_agents: 2`, `max_turns: 10`, `context.hard_limit: 0.21`.
7. The code prompt tells agents to use `request_resource` (`sim-a`, else `sim-b`) and `release_resource` instead of `scripts/iplmt-simulator.sh`; the prompt overrides the skill.
8. The token is a Keychain item read by `~/loop-sigma/run.sh`. The run was started once, run 63ace978, at 15:27 UTC under `caffeinate -i -s`, and never restarted; one live reload of `WORKFLOW.md` at 18:13 (config ce13445d).

2026-11-27 soule, the wave, all in run 63ace978:
- Delivered: 3 code issues from GitHub and 1 research issue from the local tracker (`loop report --since 1d`: "Delivered issues: 4, code, gh: 3, research, local: 1").
  - Sigma-199 (216, the read only pill scales with Dynamic Type): merged as Soules-Studio-Ltd/Sigma PR 369, https://github.com/Soules-Studio-Ltd/Sigma/pull/369. 3 attempts, 8.87 usd. Trace: `loop trace gh:199 --workflow ~/PROJECTS/Sigma/WORKFLOW.md`.
  - Sigma-197 (214, the blueprint grid's major line): merged as PR 370, https://github.com/Soules-Studio-Ltd/Sigma/pull/370. 2 attempts, 11.55 usd. Trace: `loop trace gh:197 ...`.
  - Sigma-244 (240, a unit word highlighted as a reference): merged as PR 371, https://github.com/Soules-Studio-Ltd/Sigma/pull/371. 3 attempts, 17.40 usd. Trace: `loop trace gh:244 ...`.
  - local:r-1 (notepad calculator competitors and their pricing): verified, the report attached as a comment on `~/PROJECTS/Sigma/.loop/issues/r-1-notepad-calculator-competitors.md`, approved, moved to `review`. 1 attempt, 1.57 usd. Trace: `loop trace local:r-1 ...`.
- In parallel: Sigma-197 and local:r-1 started together at 15:27:47; Sigma-199 started at 15:34:34 in the slot r-1 freed, beside Sigma-197; Sigma-244 waited ("1 issue eligible, waiting for a free slot (Sigma-244)") and started at 16:30:13. Two code issues ran in parallel from 15:34 to 16:29.
- Both trackers in one run: `loop status` at 15:28 showed `running local:r-1 attempt 1` and `running gh:Sigma-197 attempt 1`; the log shows `2 issues eligible (Sigma-199, Sigma-244)` and the attempts of `local:r-1` and `gh:Sigma-*` interleaved.
- Each agent on its own simulator through `request_resource` (from the store, `resource_*` events of project 60d1f232117484f0): Sigma-197 `resource_granted sim-a` at 15:29:55 (attempt ef23b9dd); Sigma-199 `resource_queued sim-a position=1 reason=held` at 15:38:24, then `resource_granted sim-b` at 15:42:52 (attempt 68b8b710); Sigma-244 `resource_granted sim-a` at 17:07:21 (attempt d0e80896). Checks took their own: for example Sigma-197's check queued at 15:59:09 on both (`reason=held`) while Sigma-199's agent held both, and was granted `sim-a` at 16:06:25 when that attempt ended. Totals: 33 requests, 20 grants, 6 queued, 20 releases, 1 failed read only check. No `area_busy` skip: the three issues carried no `area`.
- Restarts of `loop run`: none.

2026-11-27 soule, the figures:
- Peak main session occupancy, from the `context_usage` events, window 1,000,000 (Opus 5.5): highest 0.1691 (Sigma-244 attempt d0e80896, 169,100 tokens); then 0.1459 (Sigma-199 attempt 68b8b710), 0.1397 (Sigma-197 attempt ef23b9dd), 0.1107, 0.1052 (r-1), 0.1037, 0.0886, 0.0873, 0.0862. No main session above 25 percent, none at the 0.21 hard limit, no rotation. The subagent gap stands: the agents' ios-expert, ios-ui-expert and reviewer subagents each run in their own window, which Loop does not measure; their spend is in the token and cost figures above (9.85 usd per delivered issue on average, 39.39 usd in all).
- Human touches, ledger (`human_touch` events): r-1 1 (approve), Sigma-199 2 (send back, approve), Sigma-197 2 (send back, approve), Sigma-244 3 (send back, resume, approve). `loop report`: "Delivered with zero human touches: 1 of 4" (bl-64 still applies).
- Human touches counted by hand, which the ledger does not see: the three send backs were caused by one repository problem that only a human could fix (Sigma PR 368, below), a shared touch on each code issue; Sigma-244 also needed the simulator timeout raised in `WORKFLOW.md` before its resume. So by hand: r-1 1, Sigma-199 3, Sigma-197 3, Sigma-244 5. Before the wave, the setup (PR 366, labels, the research file, holding Sigma #349 out) is setup, not touches.
- Every result was reviewed before approval by a Sonnet 5.5 merge risk review of the full diff (the code kind's `policy: human` opens the pull request after the approval).

2026-11-27 soule, what went wrong and what it taught:
- **A workspace folder name leaked into Sigma's Xcode project.** XcodeGen 2.45.3 names the `..` group of a `configFiles` path outside `iosApp/` after the checkout folder, so `make ios-generate` in `~/loop-sigma-workspaces/Sigma-197` rewrote `project.pbxproj` with a `Sigma-197` group, and the check (`make ios-generate` must leave the tree clean) forced each agent to commit its own folder name. Merging one would have broken every later check. Not a Loop defect but a lesson for any project Loop runs in worktrees: generated files must not depend on the checkout's path. Fixed on Sigma by PR 368 (a wrapper xcconfig inside `iosApp/`); all three issues were sent back to merge main and came back clean.
- **A permission denial failed an attempt** (bl-267): Claude Code denied one `rm -rf` Bash call of Sigma-199's agent, and Loop ended the attempt as `turn_input_required: Bash` though the agent had finished its turn normally; it used one of Sigma's 2 `max_attempts`.
- **A failed simulator prepare parked an issue** (bl-268): a released simulator stayed booted (`resource_check_failed`, `sim-a` dirty), and the next prepare of `sim-a` for Sigma-244's check timed out after 300 s; the check could not run and the issue was parked at once, with no retry and no try on `sim-b`. Raising `prepare_timeout_seconds` to 900 and `loop resume` got it through.
- **One attempt held both simulators** (bl-269): a queued request kept its place after the agent got the other simulator, and was granted later.
- **A kind's `move_to` is checked against the primary tracker** (bl-266), so the research kind cannot close its issues as `done`.
- **The token for one off commands**: `loop approve`, `send-back` and `resume` need `$LOOP_SIGMA_GITHUB_TOKEN`, passed from the Keychain for each command (bl-264 covers `stop`, `drain` and `send-back`).
- **Security, noted here by the owner's choice, not filed** (a Fable 5.2 review of how the token is passed, 2026-11-27): the Sigma token leaked nowhere (no file, argv, agent, MCP server or check environment held it), but (1) the stored token was the gh CLI's own token with `admin:org`, `repo`, `workflow`, `gist`; (2) an agent running as the same user can still read it through `security find-generic-password`, `gh auth token`, `git credential fill` or `ps eww` on `loop run`, so the env strip is no boundary until the M5 sandbox; (3) Loop strips only the names the workflow references, so other host secrets (`LOOP_PR_TOKEN`, `LOOP_PLAYGROUND_GITHUB_TOKEN`, `GEMINI_API_KEY`, `SSH_AUTH_SOCK`) reached the agents, and Xcode wrote the two GitHub tokens into DerivedData build files in two workspaces; (4) hooks inherit the full host environment, the tracker token included. Loop fixes to consider: an agent and hook env allowlist instead of a blocklist, hooks started with the checks' env policy and `core.hooksPath=/dev/null`, and a `token_command` so the token is never in `loop run`'s own environment. Owner steps: a fine grained Sigma token, narrower gh scopes, rotating the two exposed tokens.
- **What worked**: two trackers in one run with namespaced ids; two agents and their checks sharing two Loop owned simulators through the queue, with no wait counted as a failure; the merge check merging the moved main and rerunning every check before each merge (PRs 370 and 371); the research kind end to end, from an empty workspace to a sourced report checked for its sections and links.
