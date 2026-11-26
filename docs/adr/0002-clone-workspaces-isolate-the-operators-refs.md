# Clone workspaces isolate the operator's refs

A workspace was a git worktree of the project, so it shared every ref, the repository config, `info/`, the hooks and the objects with the operator's checkout, and an agent could move the operator's `main`, delete a tag or set `core.hooksPath` from its workspace (bl-28). Workspaces are meant to be isolated from the operator's refs. With `workspace.git: clone`, opt in first and the default once the pilot app runs on it (bl-245), each workspace is a `git clone --shared` of the project: it borrows the objects read only through alternates and owns its refs, config, hooks and info. Loop removes its `origin`, imports a verified commit into the project by its id with `transfer.fsckObjects` before any push, writes only `refs/heads/loop/<key>`, `refs/loop/base/*`, `refs/loop/workspace/<key>` and `refs/loop/workspace-base/<key>` in the project, and never reads a ref of the clone to choose a base, a diff base or a push target.

The clone's config is the agent's, so git run in a clone on the operator's behalf must not take orders from it. Loop's workspace hooks run every git in a clone on the clone itself (`GIT_DIR`, `GIT_COMMON_DIR` and `GIT_WORK_TREE` set), with no hook, no fsmonitor and no transport but `file`, and refuse a `.git` with a `commondir` file. Loop's own git in the clone has had no hook and no fsmonitor since bl-28. Two gaps remain in it: the lazy fetch guard comes with bl-246 item 1, and pinning git to the clone itself, with the refusal of a `commondir` when a clone is reused, comes with a follow up raised in the review of the switch. A project switches to clones only once these and bl-246 item 2 are on main, and Loop's own workflow follows that gate.

## Considered options

- **More deny rules:** bypassed by `git -C`, aliases, scripts and writes under `.git`.
- **A ref audit after each turn:** cannot tell the operator's or other attempts' ref writes from the agent's.
- **A reference-transaction hook in the operator's repository:** bypassed with `-c core.hooksPath=/dev/null` and by writing the ref file.
- **Per worktree config (`extensions.worktreeConfig`):** a plain `git config` still writes the shared file.
- **A Loop owned bare mirror with worktrees:** protects the operator, not attempts from each other.
- **A plain local clone:** hardlinks objects, which a sandboxed agent could later write through.

## Consequences

The threat model is mistakes and casual misuse by an agent, not a hostile agent. Verification still runs the agent's build code unsandboxed as the operator, clean and smudge filters in the clone's config still run on a checkout, and the agent's own git can reach the project through a `commondir` or a `core.worktree` it writes in its clone, as through `git push /abs/path` or any write by absolute path, until the M5 sandbox, for which a clone is the right shape. In the bl-28 triage measurement, with Loop's real hook sequence, a clone took about as long to create as a worktree (3.75 s against 3.55 s on 40,000 files, 15 to 25 s either way on 100,000). The operator's git hooks no longer run on agent commits, the project's `info/exclude` and `info/attributes`, LFS objects, submodules and git-crypt keys are not shared, and keep refs per live clone hold the objects it borrows through a gc.
