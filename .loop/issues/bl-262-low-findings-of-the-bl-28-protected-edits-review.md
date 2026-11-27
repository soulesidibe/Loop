---
id: bl-262
title: "Low findings of the bl-28 protected edits review, hooks in a clone"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Low findings of the merge risk reviews of pull request #103 (bl-28 protected edits: hooks accept a clone, `workspace.git: clone` on), round 2 at d46b7cb, Sonnet 5.5 and a Fable 5.2 recheck, on 2026-11-27. Round 1 was sent back for two Fable and two Sonnet mediums; round 2 found only lows. Merged on 2026-11-27 after bl-246, bl-253 and bl-254 landed and Loop was reinstalled.

## What

1. A `.git` file naming another registered worktree of the project (`gitdir: <project>/.git/worktrees/other`) passes the worktree path of `after_create` and `before_run`: the hook moved that worktree's `HEAD`, rewrote its index and `config.worktree`, and set `extensions.worktreeConfig` in the project. Reachable in clone mode only by swapping `.git` between Loop's check and the hook. Hardening: in the worktree path, require the `gitdir` file in the named git folder to point back at the workspace's own `.git`. Reproduced on the hooks alone. Strongest of this list.
2. An emptied workspace without `.git` makes `after_create` register a worktree in the project. Loop sees Absent and clones first, so it is fenced. Reproduced on the hooks alone.
3. Check then use: `is_clone` runs once in `after_create`, then up to four git calls follow, and a straggling agent process could swap a symlink in between. Outside ADR 0002's threat model; add one line to "What the hooks do not cover" in `docs/workspaces.md`.
4. The `extensions.refStorage` read (`git config --file`) in `worktree-lock.sh` runs with the workspace as cwd, so git discovers the clone first, contrary to the comment. Run it with `-C "$2"`.
5. `is_clone` word splits `$loop_clone_storage` unquoted, so a glob value expands against the cwd. Use `set -f` or read line by line.
6. On git older than 2.45 `GIT_NO_LAZY_FETCH` is ignored. Note a 2.45 minimum in `docs/workspaces.md` or the ADR; `GIT_ALLOW_PROTOCOL=file` still guards.
7. A symlinked `.git/objects/pack` is not checked by the hooks. Harmless today since the hooks write no objects (bl-260 notes the same for Loop's own check).
8. `before_remove` on a clone runs `git worktree prune` in the project, which also prunes stale entries of the operator's own session worktrees. Pre-existing.

Noted, no action: clean and smudge filters in the clone's config still run on the hooks' checkout (documented, accepted by the threat model). The PR body's gate text was refreshed before the merge.

## Comments
