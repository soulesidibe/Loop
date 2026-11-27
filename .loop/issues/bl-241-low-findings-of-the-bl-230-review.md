---
id: bl-241
title: "Low findings of the bl-230 review, agy resume checks"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk review of pull request #92 (bl-230), Sonnet 5.5 then a Fable 5.2 recheck (required by the medium band, which came only from the reviewer's diff cut), on 2026-11-26. Only lows; approved.

## What

1. **Human step: the protected spec.** `.loop/specs/gemini-support.md` still has the stale lines (the `--gemini-dir` bullet around line 61, the `resumeSession` bullet around line 109, issue 4 around line 164). bl-230's second acceptance criterion names it; edit it by hand.
2. **The allow lists come from one agy 1.5.18 session.** If agy writes any other name (a dotfile, a lock file), every resume refuses and falls back to a fresh session, with a `loop:` line. Run one real agy session of a few turns and check `checkBeforeLoad` passes on its folder; consider a test from a captured real layout instead of the hand written `writeAgyLayout`.
3. **The allow list is one level deep.** `antigravity-cli/bin/`, `builtin/`, `daemon/`, `cache/` and `knowledge/` are accepted as directories with their contents uninspected (beyond the no link walk), and `config/config.json` and `projects/` are type checked only. Contained by the permission policy and `--sandbox`; documented.
4. **`checkMcpConfig` inspects only `mcpServers`.** Confirm the real key name, or refuse any key other than an empty `mcpServers`.
5. **Hard links pass the check** (`isRegularFile` is true). Loop never writes through one, but agy could write through a hard linked state file. Same containment as 3.
6. **No lock between `checkBeforeLoad` and the agy load** (older than bl-230). Writers would be a surviving agy child or the agent, both contained.
7. **Superseded kept folders linger** when the newer session is not kept (never reports an id, or is ended by a guard), until the sweep. They can never be loaded. Documented as best effort.
8. **The startup sweep uses the host `HOME` and `LOOP_HOME` only.** A workflow that overrides them in `agent.env` is swept at the first launch, as before; an invalid `HOME` now throws in the `AcpRunner` constructor instead of at the first spawn.

Area: `plugins/runner-acp` (`AgyIsolationDir.kt`, `AcpRunner.kt`), `.loop/specs/gemini-support.md`, `docs/plugins/runner-acp.md`.

## Comments

2026-11-27 soule: item 1, the human step, is done: `.loop/specs/gemini-support.md` now describes the kept isolated folder (bl-213), its location, the `checkBeforeLoad` check (bl-230), and `resumeSession` loading from the kept folder, in the isolation bullet, the `resumeSession` bullet and slice 4. Items 2 to 8 stay here for triage.
