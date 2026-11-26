# Clone workspaces isolate the operator's refs

A workspace was a git worktree of the project, so it shared every ref, the repository config, `info/`, the hooks and the objects with the operator's checkout, and an agent could move the operator's `main`, delete a tag or set `core.hooksPath` from its workspace (bl-28). Workspaces are meant to be isolated from the operator's refs. With `workspace.git: clone`, opt in first and the default once the pilot app runs on it (bl-245), each workspace is a `git clone --shared` of the project: it borrows the objects read only through alternates and owns its refs, config, hooks and info. Loop removes its `origin`, imports a verified commit into the project by its id with `transfer.fsckObjects` before any push, writes only `refs/heads/loop/<key>`, `refs/loop/base/*`, `refs/loop/workspace/<key>` and `refs/loop/workspace-base/<key>` in the project, and never reads a ref of the clone to choose a base, a diff base or a push target. The clone's config is the agent's, so every git Loop and its workspace hooks run in a clone is pinned: no hook, no fsmonitor, no lazy fetch.

## Considered options

- **More deny rules:** bypassed by `git -C`, aliases, scripts and writes under `.git`.
- **A ref audit after each turn:** cannot tell the operator's or other attempts' ref writes from the agent's.
- **A reference-transaction hook in the operator's repository:** bypassed with `-c core.hooksPath=/dev/null` and by writing the ref file.
- **Per worktree config (`extensions.worktreeConfig`):** a plain `git config` still writes the shared file.
- **A Loop owned bare mirror with worktrees:** protects the operator, not attempts from each other.
- **A plain local clone:** hardlinks objects, which a sandboxed agent could later write through.

## Consequences

The threat model is mistakes and casual misuse by an agent, not a hostile agent: verification still runs the agent's build code unsandboxed as the operator, clean and smudge filters in the clone's config still run on a checkout, and `git push /abs/path` or any write by absolute path stays possible until the M5 sandbox, for which a clone is the right shape. A clone costs the same as a worktree to create (measured on 40,000 and 100,000 files). The operator's git hooks no longer run on agent commits, the project's `info/exclude` and `info/attributes`, LFS objects, submodules and git-crypt keys are not shared, and keep refs per live clone hold the objects it borrows through a gc. Loop's own workflow switched to clones once its workspace hooks accepted a clone and the low findings of the bl-28 review that let a clone run an agent program on the host or fail to start (bl-246 items 1 and 2) were fixed.
