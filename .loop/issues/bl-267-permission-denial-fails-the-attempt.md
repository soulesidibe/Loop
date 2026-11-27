---
id: bl-267
title: "A permission denial at the end of a Claude Code turn fails the attempt"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. Found in the m4-43 wave on Sigma, 2026-11-27, issue Sigma-199 attempt 68b8b710.

## What

Claude Code denied one Bash call of the agent (a compound command with `rm -rf` cleaning evidence bundles). The turn's result carried it in `permission_denials`. Loop recorded `turn_input_required reason=Bash`, ended the attempt with `Failed reason="turn_input_required: Bash"`, and scheduled an error retry. The agent had finished its turn normally (`stop_reason: end_turn`, "flow test passed at all sizes; awaiting UI reviewer") and could have continued. The attempt took 1912 s, 5 turns and 6.34 usd, and used one of Sigma's two `max_attempts`; one more such denial would have parked the issue.

## Agent brief

Decide when a denial means the agent needs a human. A denied tool call whose turn still ended with `end_turn` and a result is not a question: continue the attempt with a continuation turn that names the denied command and says it is not allowed, rather than failing it. Keep `turn_input_required` for a turn that actually stopped to ask (the runner's real input request). Check how `permission_denials` is read in the Claude Code runner and the codex runner.

## Acceptance criteria

- [ ] A runner test with a recorded result that has `permission_denials` and `stop_reason: end_turn`: the attempt goes on with a continuation turn naming the denied command, no `turn_input_required`.
- [ ] A turn that really asks for input still ends as `turn_input_required`.
- [ ] Docs (the runner's plugin page) state the rule.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
