---
id: bl-206
title: "Low findings of the runner-acp review, base pinning, hash batching, and error matching"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of Fable 5.2 review 3 of pull request #47 (`runner-acp`, Gemini support), on 2026-11-25, plus residual notes from review 2 and plan review follow-ups.

## What

Low findings from PR #47 review 3 (`e8f91fe`). Triage decides which are worth fixing.

1. **L1. Base SHA is pinned per attempt, not per workspace.** `Orchestrator.kt` (`resolveAttemptBaseRef`) resolves the base on each attempt, and worktrees share refs with the project repo, so `git fetch --update-head-ok . +HEAD:main` moves the project's `main` (and `refs/loop/base/main` after a failed fetch) so the next attempt resolves the agent's commit. Also returns a bare ref name when nothing resolves, which the runner resolves inside the workspace on each turn. Persist the first resolved SHA per issue workspace host-side and reuse it on retries; return `null` instead of a bare ref name when unresolved.
2. **L2. One `git hash-object` process per customization file.** `ProtectedRootsCheck.kt` invokes one git process per file (~4.5 s for 300 tracked rule files, blocking, twice on turn 1). Batch with one `git hash-object --no-filters --stdin-paths` call and scope `ls-tree` to the matched roots (plus `ls-tree -r` cost on very large repos).
3. **L3. Wrong config key name in refusal message and docs.** `ProtectedRootsCheck.kt`, `docs/plugins/runner-acp.md`, and `ProtectedRootsCheckTest.kt` say `kinds.code.guards.base`, whereas the actual workflow key is `kinds.code.verify.guards.base` (`WorkflowFile.kt`).
4. **L4. Base resolved before checking whether any protected root exists.** `ProtectedRootsCheck.kt` resolves the base commit before checking `matchedRoots.isEmpty()`, so a repository with no `.agents` directory and no resolvable base refuses every `agy` turn. Check `matchedRoots.isEmpty()` before base resolution.
5. **L5. Symlinked subdirectories inside protected roots.** `ProtectedRootsCheck.kt` refuses symlinked roots (`.agents`), but a tracked `.agents/rules -> ../shared-rules` symlink allows edits and new files in the target to pass. Refuse symlinked `rules/`, `hooks/`, and `plugins/` subdirectories like symlinked roots.
6. **L6. `core.fileMode=false` executable bit false refusal.** `ProtectedRootsCheck.kt` compares `Files.isExecutable` against `ls-tree` mode `100644`/`100755`, so under `core.fileMode=false` a dropped exec bit refuses while `git status` is clean.
7. **L7. `resolveAttemptBaseRef` runs for every runner with no warning.** `Orchestrator.kt` runs up to 6 uncancellable `rev-parse` calls (5 minute timeout each) for every runner even though only `runner-acp` reads `baseRef`, and `resolveGitCommit` logs nothing when unresolved. Log a warning when unresolved and consider resolving only for runners that consume `baseRef`.
8. **L8. `runGit` does not destroy the child process on interrupt.** `ProtectedRootsCheck.kt` (`runGit`) catches `InterruptedException` and returns `null` without calling `process.destroyForcibly()`.
9. **Residual review 2 finding 9 (`AcpErrors.kt`).** `AcpErrors.kt` still matches whole tokens inside free-text `message` (for example a quoted `403 forbidden` or `ECONNREFUSED`). Restrict matching to structured fields in `data` or explicit JSON-RPC error codes.
10. **Residual review 2 finding 10 (`AcpRunner.kt`).** Cleanup warnings during `stop()` still go only to process stderr (`onWarn`) rather than Loop's structured run log.
11. **Two weak tests noted in review 3.** The `git update-ref` test in `ProtectedRootsCheckTest.kt` passes without the host-pinning fix (since it passes `baseSha` directly), and the `bare_model_turn.jsonl` replay passes without the bare-model fix because `agy` omitted `thought_level` in that fixture. Strengthen both tests.
12. **Additional follow-ups from plan review:**
    - LFS pointers, `ident`, `working-tree-encoding`, and smudge filters fail closed under `git hash-object --no-filters` (by design; track if any workflow needs them).
    - Special files (FIFOs, sockets) at customization paths are skipped by `scanOnDiskCustomizations` (`isRegularFile || isSymbolicLink`).
    - Check-then-use timing gap between `ProtectedRootsCheck` and `agy` reading files during a turn.
    - `findGitRoot` climbs above plain (non-git) workspaces (`PlainWorkspace`) if a parent directory happens to be a git repository.

Area: `plugins/runner-acp/src/main/kotlin/loop/runner/acp/ProtectedRootsCheck.kt`, `AcpErrors.kt`, `AcpRunner.kt`, `spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt`, `docs/plugins/runner-acp.md`.

## Comments

2026-11-25 triage:

Filed from Fable 5.2 review 3 of pull request #47 (`runner-acp`). Triage decides which findings are worth fixing.
