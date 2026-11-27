---
id: m4-44
title: "Retire the manager skill, with each PLAN 16 row covered or named as a gap"
state: done
priority: 2
labels: [ready-for-human]
blocked_by: [m4-43, m4-33]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 141. A human issue: after the exit wave (m4-43), I stop using the manager skill and record, row by row of PLAN section 16, what replaces it.

Rows that gate retirement, each covered by Loop before the skill goes:

- `claude --bg` per issue: the `claude-code` runner plugin (M1 to M3).
- `SendMessage` conventions: Loop tools for agents, `submit_result`, `move` and `comment` today, plus `ask` and `loop answer` (m4-31, m4-52), `file_followup` (m4-33) and `request_resource` and `release_resource` (m4-25, m4-51).
- "device request" chat protocol: `request_resource` and `release_resource` with the iOS simulator provider (m4-25, m4-27), and the `resource:` key of checks (m4-26).
- `merge-check.md`: the delivery check for pull requests (M2).
- `gate.sh` plus evidence: verify contracts with evidence and fingerprints (M2).
- Waves and module conflicts, the scheduling half: `blocked_by`, the per tracker limit and the area lock (m4-15, m4-17).
- the usage script: spec token accounting and the ledger, with `loop show --cost`. The script sums one session with its subagents, which the ledger and `loop show --cost` give per attempt. Codex reports no cost, so a Codex attempt shows none. `loop report` (m4-38) is named if it is merged, never a gate, since observability does not gate M4.
- Preflight in one Bash call, the spec preflight half: the spec preflight of `loop run` and `loop status` (M1 to M3).
- Brief extras, the template half: the prompt template and its variables (M1 to M3).

Rows recorded as gaps, with what replaces them until they are built:

- Preflight in one Bash call, the `loop doctor` half: not built in M4. The spec preflight of `loop run` and `loop status` replace it.
- Expert agents via the Agent tool: Koog experts are M6. Until then, the issue skill's own subagents play the expert role.
- Triage in a subagent: the Koog triage agent is M6. Until then, the triage skill run by hand.
- Waves and module conflicts, the triage scope estimate half: M6. Until then, the area field and `blocked_by` set by hand in triage.
- Brief extras, the lessons half: M6. Until then, the prompt template and the session brief.

## Acceptance criteria

- [x] Each of the 11 rows of PLAN section 16 is marked covered or gap in `docs/PLAN.md`, with the issue that covers it or what replaces it until it is built, the two split rows with both halves.
- [x] Each gating row was used in the m4-43 wave or an M4 run, with a trace or issue linked in a comment here. Amended 2026-11-27 by soule: `ask` and `loop answer`, the per tracker limit and the resource tools on Codex count as proven by tests only, to prove in the first M5 run (bl-270).
- [x] `loop doctor` and the M6 rows (experts, triage, scope estimates, lessons) are recorded as gaps in `docs/PLAN.md` section 16 and section 20, with what replaces each.
- [x] The manager skill is removed from the projects that used it, or marked retired in its own file, and the date is recorded here.
- [ ] The issue skill and its Loop variant stay as templates, ending with `submit_result` and never moving the issue, checked on the pilot app (judgment).
- [x] The M4 exit line of `docs/PLAN.md` section 17 says the manager skill is retired, with the gaps named (judgment).

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-27 soule: the plan was reviewed by Fable 5.2 at high effort before any change ("ready with changes"); its findings shaped what follows.

2026-11-27 soule: evidence, one trace per gating row. The wave is run 63ace978 on Sigma, build 56976ce, unless another run is named.

- `claude --bg` per issue: 9 `attempt_started` on the `claude-code` runner in the wave.
- Loop tools for agents: 10 `result_submitted` and 11 `issue_commented` in the wave. `file_followup`: bl-235 filed bl-239 (`followup_filed`, 2026-11-26T13:10Z, run 9ae661e3, on Loop). `ask`, `loop answer` and the resource tools on Codex: tests only, see the amendment of criterion 2 and bl-270.
- Device requests: 33 `resource_requested`, 20 `resource_granted`, 6 `resource_queued`, 20 `resource_released`; the first grant was `sim-a` to Sigma-197 at 15:29:55Z. The checks took their simulator through `resource:` (`LOOP_RESOURCE_UDID` in Sigma's `check.sh`).
- Delivery check for pull requests: 3 `merge_check_finished`, then Sigma pull requests 369 (Sigma-199, 18:14:28Z), 370 (Sigma-197, 18:28:32Z) and 371 (Sigma-244, 18:33:53Z) merged.
- Verify contracts with evidence and fingerprints: 13 `verdict` events, each with a fingerprint and evidence files, for example r-1 verified (`tree:1ebc20bc894a4ed7`, 15:34:02Z) and Sigma-199 refuted (`git:f1a42a71...`, 16:21:56Z).
- Scheduling: the area lock skipped bl-210 while bl-209 held `plugins/runner-claude-code` (`area_busy`, 2026-11-25T09:09Z, run b92a92fb, on Loop); `blocked_by` skipped m2-04 behind m2-03 (2026-11-12T11:12Z, run a2e09f2d); slot skips (`no_available_slots`) in the wave. The per tracker limit: tests only (bl-270).
- Token accounting: `loop show d0e80896 --cost` (gh:Sigma-244) prints 23,297,507 tokens, 16.11 USD and a peak of 0.1691 of a 1,000,000 token window. `loop report` (m4-38) is merged.
- Spec preflight: 803 `preflight` events in the wave; a failing one caught a template using `loop.answer` before it was a variable (2026-11-25T15:43Z).
- Template: r-1 ran on the research kind's own template (`template_version ee1a5a70...` on its `attempt_started`), and a config reload of `WORKFLOW.md` is recorded at 18:13:04Z.

2026-11-27 soule: the global manager skill (`~/.claude/skills/iplmt-manager/SKILL.md`) is marked retired on 2026-11-27, in its description and with a header, and its files are kept. The project copies were left alone (a live `loop run` and uncommitted work in Sigma, a release in progress on the pilot app, an archived repository) and are bl-271.

2026-11-27 soule: criterion 5 is not met. Sigma's Loop variant of the issue skill ends with one `submit_result` (line 48) and sends product questions through `needs_info` (lines 42 and 60), but nothing forbids `mcp__loop__move` (line 16), Sigma-199 attempt a1d53ccb moved its issue to `review` in the wave (`issue_move_requested`, 16:22:21Z), and line 47 has the agent `git mv` the issue file to `issues/done/`. The pilot app's copy has the same lines, and Sigma's base issue skill still reads its merge gates from the manager skill (line 184). The fix is bl-272.

2026-11-27 soule: PLAN section 16 has a status column with three split rows and a paragraph of the skill's duties with no row, section 20 asks the two gap questions, and section 17 has the retirement bullet (42e3cb9, 6d9e894).

2026-11-27 soule: closed. Criterion 5 is handed to bl-272 and stays unticked here; the owner chose to close m4-44 without waiting for it.
