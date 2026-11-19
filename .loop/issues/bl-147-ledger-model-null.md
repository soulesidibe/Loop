---
id: bl-147
title: "The ledger records model null when the runner config names no model"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found in the m3-28 Codex exit run on 2026-11-17.

## What to look at

- Every turn line of attempt b9d9e179 (bl-63, `codex-app-server`) in `~/.loop/ledger/9f94ee14126425f0.jsonl` reads `"model": null`, though the `thread/start` answer named `openai/gpt-6.1-sol` and the `context_usage` events carry it.
- The claude-code turn lines of 2026-11-15 to 2026-11-17 read `"model": null` too, since `WORKFLOW.md` names no model there either.
- So the ledger cannot say which model a turn ran on, which a runner comparison (M8) and the cost per model need.
- For triage: record the model the runtime reported (the `context_usage` model, or the session's), falling back to the configured one; and whether the field takes the `context.windows` key form `<provider>/<model>`.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] A turn line of the ledger holds the model the runtime reported (the `context_usage` model, else the session's), falling back to the configured model. A test covers a Codex turn and a claude-code turn whose `WORKFLOW.md` names no model.
- [ ] Whether the field takes the `<provider>/<model>` form of the `context.windows` key is decided and stated in the ledger docs.

## Comments

2026-11-17 soule: filed from m3-28.

2026-11-18 soule: triaged ready-for-agent in m3-29, no blocker. It blocks m4-38, because `loop report` shows the model of each run.
