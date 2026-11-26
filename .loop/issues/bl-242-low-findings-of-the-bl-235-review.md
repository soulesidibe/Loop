---
id: bl-242
title: "Low findings of the bl-235 review, strict MCP features"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk review of pull request #88 (bl-235), Sonnet 5.5 then a Fable 5.2 recheck (required by the medium band on a guard), on 2026-11-26. Only lows; approved.

## What

1. **No runtime guard under strict MCP.** Nothing fails or warns when an unlisted MCP server still starts (a later Codex with another source). Warn or fail the session on `mcpServer/startupStatus/updated` under `strict_mcp`. The docs already name this as not covered.
2. **Tests would pass with a wrong feature key.** Both bl-235 tests compare the request against literals with a fake app server. The keys are verified only by the agent's one real probe and `codex features list` on 0.167.0. A guarded real codex test that fails on any MCP server start would pin it.
3. **Turning `plugins` off removes more than MCP.** A strict agent also loses the skills and hooks of the operator's Codex plugins. Nothing in Loop relies on them, but the docs describe only the MCP effect. Add one sentence, and that `strict_mcp: false` restores them.
4. **Dynamic tools not exercised with `plugins` off.** The real probe ran one plain turn; no dynamic tool call was made. Check on the first real codex run.
5. **Doc nits:** the bl-72 paragraph in `docs/spec-decisions.md` still quotes `"features": {"apps": false}` as the config sent, and one KDoc line in `CodexAppServerRunner.kt` is overlong after the rewrap.

Area: `plugins/runner-codex-app-server`, `docs/plugins/runner-codex-app-server.md`, `docs/spec-decisions.md`.

## Comments
