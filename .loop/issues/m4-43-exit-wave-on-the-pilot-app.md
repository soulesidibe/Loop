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
