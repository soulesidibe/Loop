---
id: bl-261
title: "Low findings of the bl-254 review, clone rule for the core checks and the protected roots check"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #110 (bl-254), Sonnet 5.5 reading the whole diff and a Fable 5.2 recheck with real git, on 2026-11-27. Round 1 was sent back for a reproduced medium (the Gemini risk reviewer ran the protected roots check in a clone with no base and no clone mode) and four lows. Round 2 fixed all five; both reviews found only lows; approved and merged.

## What

1. `ProtectedRootsCheck.kt:122-124` and `473-475` accept any non blank `baseRef` in clone mode and resolve it in the clone. Reproduced: the round 1 recipe with `baseRef = "main"` and clone mode passes. Both current callers pass a commit id resolved in the project, so this only fails open for a future caller of the plugin API; `RiskReviewer.kt:18-21` says "base commit" but nothing enforces it. Hardening: in clone mode, refuse a base that is not a full hex object id. Strongest of this list.
2. `ProtectedRootsCheck.kt:473-482` compares against `merge-base <base> HEAD`. Reproduced: project commit A has `.agents/hooks.json`, the base B removes it, the clone moves `HEAD` to A with A's file on disk, and the check passes, so `agy` could load a hook the operator revoked. Pre turn path only (in the reviewer path `HEAD` is the verified merged head). Kept on purpose and documented in `docs/workspaces.md:143`, since comparing with the base itself would refuse a branch behind on a file only the operator changed. Decide whether a revoked customization file should also refuse.
3. Test gap: no end to end test from `AutoMergeGate` into the real `GeminiRiskReviewer` under `workspace.git: clone`. Each half is tested (`MergesTest` with a fake reviewer, `GeminiRiskReviewerTest` with a hand built request), so a hole needs two regressions.
4. `CloneScope.covers` now compares the unresolved `workflow.path` parent. A project reached through a differently spelled path (for example `/var` against `/private/var`) as a direct child of `workspace.root` could be seen as covered and then refused as resolving to the project. Fail closed, not reproduced, not a normal setup.
5. `AutoMergeGate.kt:128-133` with an empty `wait.baseSha`: `git.diffStat` runs with an empty base before the reviewer. Expected to fall back before the reviewer is called; not run. Add a test.
6. Carried from round 1, by design: `CloneLayoutCopy.kt` in runner-acp is a hand copy of spec-core's `CloneLayout`. The new shared `CloneLayoutContract` parity suite now ties the two; keep new layout cases in the contract, not in one side.

Noted, no action: TOCTOU between the layout check and the git process stays out of scope until the M5 sandbox.

## Comments
