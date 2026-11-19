---
id: m4-44
title: "Retire the manager skill, with each PLAN 16 row covered or named as a gap"
state: todo
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

- [ ] Each of the 11 rows of PLAN section 16 is marked covered or gap in `docs/PLAN.md`, with the issue that covers it or what replaces it until it is built, the two split rows with both halves.
- [ ] Each gating row was used in the m4-43 wave or an M4 run, with a trace or issue linked in a comment here.
- [ ] `loop doctor` and the M6 rows (experts, triage, scope estimates, lessons) are recorded as gaps in `docs/PLAN.md` section 16 and section 20, with what replaces each.
- [ ] The manager skill is removed from the projects that used it, or marked retired in its own file, and the date is recorded here.
- [ ] The issue skill and its Loop variant stay as templates, ending with `submit_result` and never moving the issue, checked on the pilot app (judgment).
- [ ] The M4 exit line of `docs/PLAN.md` section 17 says the manager skill is retired, with the gaps named (judgment).

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
