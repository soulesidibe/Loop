---
id: bl-245
title: "Clone workspaces, part 2: replay of an unimported commit and clone as the default"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: [bl-28]
---

## Parent

None. Backlog, not scheduled in a milestone. Split out of bl-28 in triage on 2026-11-26, after the maintainer chose clone workspaces (opt in `workspace.git: clone`) as the ref isolation before M5.

## What

1. **Replay of a commit that was never imported.** `loop replay` (`ReplaySites`) resolves a recorded attempt's commit in the project repository. In clone mode a commit is imported into the project only before a push or on a verified verdict, so a refuted or abandoned attempt's commits live only in its clone and are gone once the workspace is removed. Decide whether replay imports on demand while the clone exists, records a keep ref for every recorded commit, or says the commit is gone.
2. **Clone as the default.** After the pilot app runs on clone workspaces (the m4-43 wave or later), make `clone` the default for `loop init` and generated workflows, keep `worktree` as an explicit choice. Loop's own `WORKFLOW.md` already runs on `workspace.git: clone` since the human steps of bl-28, with the scripts in `scripts/hooks/` accepting a clone.

## Comments
