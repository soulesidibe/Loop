# Workspaces and Loop's own worktree hooks

Loop gives each Issue a Workspace, a folder under `workspace.root` named after the workspace key. Loop has no built in VCS logic: the hooks in `WORKFLOW.md` fill and clean the Workspace. This page documents the hook scripts Loop's own Project uses to make each Workspace a git worktree. With `workspace.git: clone` Loop makes each Workspace a clone of the project itself, before the hooks run, see [clone workspaces](#clone-workspaces).

Policies live in [`spec-decisions.md`](spec-decisions.md), not here:

- How hooks run (shell, working directory, timeout, environment, `hook_finished` event, log lines): the hook shell row of [implementation defined behaviors](spec-decisions.md#implementation-defined-behaviors).
- A file already at the Workspace path, and what happens to a new Workspace when `after_create` fails: the non-directory and workspace population rows of the same table.
- The `LOOP_` variables: [hook environment variables](spec-decisions.md#hook-environment-variables-extension-m1-04).
- Workspace removal on a terminal retry: [answer 4](spec-decisions.md#4-cleanup-on-a-terminal-retry).
- Workspace removal when a running Issue turns terminal, and at startup for every terminal Issue: [reconciliation and startup cleanup](spec-decisions.md#reconciliation-and-startup-cleanup-clarification-of-85-86-and-163-m1-16).

Added 2026-11-07 in issue m1-04.

## Usage

Add the hooks to `WORKFLOW.md`. The paths resolve from the folder of `WORKFLOW.md` through `LOOP_WORKFLOW`, so they work wherever the Project is checked out:

```yaml
hooks:
  after_create: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-after-create.sh"
  before_run: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-before-run.sh"
  before_remove: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-before-remove.sh"
```

The repository is the folder of `$LOOP_WORKFLOW`. Keep `workspace.root` out of version control, for example with `workspaces/` in `.gitignore`.

## Branch

Each Workspace is a worktree on the branch `loop/<workspace key>`, from `LOOP_WORKSPACE_KEY`. For Loop's ids the key equals the Issue identifier, so the branch of Issue `m1-04` is `loop/m1-04`.

A new branch starts from `$LOOP_BASE_REF` when it is set, else from `main` if it exists, else from `origin/HEAD`, else from `HEAD`. An existing branch is reused as it is: the scripts never recreate, reset or move it.

### The base of a pull request kind

With pull request delivery, Loop merges on GitHub and the local `main` falls behind until someone pulls. Since bl-59 Loop fetches the base itself before every Attempt of a kind with `delivery.pull_request`, the first and every retry, through the same isolated, token bearing fetch as the merge check, and passes the fetched commit to the hooks of the Attempt as `LOOP_BASE_REF`. The plain hooks of [usage](#usage) then cut a new branch from the fresh base with nothing more to configure. The details are in [Base fetch before every attempt](spec-decisions.md#base-fetch-before-every-attempt-extension-bl-59).

- **The ref the prompt names.** The fetch also writes `refs/loop/base/<base>` (`refs/loop/base/main` by default) in the project repository at the same commit, and a worktree shares the project's refs, so the prompt tells the agent to sync with that ref: run `git merge-base --is-ancestor refs/loop/base/main HEAD`, and `git merge refs/loop/base/main` when it fails. It is the ref the merge check's send back text names too. The agent never fetches.
- **No local branch moves.** Loop never moves the operator's `main` or any other branch, so the main checkout's `main` stays where the operator left it.
- **A failed fetch** (offline, a rate limit, a refused token) never holds the Attempt. Loop records `base_fetch_failed` and logs a warning, visible in `loop log`, `loop trace` and the run log, then the hooks get the last fetched `refs/loop/base/<base>` as `LOOP_BASE_REF`. With no earlier fetch, `LOOP_BASE_REF` is unset and the scripts fall back to `main`.
- **Without pull request delivery** Loop fetches nothing and sets no `LOOP_BASE_REF`: new branches start from the local `main`, and the operator pulls after each merge.

Loop's own `WORKFLOW.md` and the pilot app's use the plain hooks above, and Loop's prompt syncs with `refs/loop/base/main`. Before bl-59 both worked around the stale `main` with `scripts/hooks/fetch-base.sh` and `LOOP_BASE_REF=origin/main` in each hook. That fetch read the repository config an agent can write, unlike Loop's own fetch, which is why it went.

## Scripts

All three scripts are POSIX `sh`, live in `scripts/hooks/`, and are idempotent: running one twice leaves the same end state and keeps the commits on the branch. They source `worktree-lock.sh`, a fourth file in the same folder, so a copy of the scripts takes all four. See [How parallel hooks share the repository](#how-parallel-hooks-share-the-repository).

### `worktree-after-create.sh`

Runs once, when Loop creates the Workspace folder.

1. Exits 0 if the Workspace is already a worktree of the repository.
2. Takes the lock of the repository.
3. Runs `git worktree prune` in the repository, which clears a stale entry left by an earlier failed Attempt.
4. Runs `git worktree add --no-checkout`: with `-b loop/<key> <path> <base>` when the branch does not exist, else `<path> loop/<key>`.
5. Sets a non-cone sparse checkout, `sparse-checkout set --no-cone '/*' '!/.loop/issues/'`, so the Workspace has every file except `.loop/issues/`.
6. Releases the lock and checks the files out with `read-tree -mu HEAD`.

If the Workspace is already a worktree with an empty checkout (for example after Loop was killed mid creation), it fills the checkout again. It sets the sparse pattern under the lock only while the repository does not have `extensions.worktreeConfig` yet.

The agent therefore does not get the Issue files in its Workspace; it moves its Issue through `loop tool move`. The main checkout is still reachable by absolute path, so this is not a sandbox.

Note: sparse checkout in a worktree turns on `extensions.worktreeConfig` in the main repository's config, so that the sparse pattern stays local to the worktree. The main checkout keeps all its files.

Both calls to `sparse-checkout set` (step 5 and the refill of an empty checkout) are tried up to 5 times, with a short pause between tries, and the hook fails only after the last try, with git's own error. On a repository that does not have `extensions.worktreeConfig` yet, git writes that setting to the shared `.git/config` on the first `sparse-checkout set`, and two hooks doing it at once collide on the config lock (`could not lock config file`, then `failed to initialize worktree config`). Added in bl-08, the retry was the normal path until bl-239 put the call under the lock; it stays as a safety net. The script writes no config of its own.

### `worktree-before-run.sh`

Runs before every Attempt.

1. Calls `worktree-after-create.sh` first, so a Workspace that lost its worktree heals itself.
2. Checks out `loop/<key>` if another branch is checked out, under the lock, and sets the sparse pattern again if it is missing. A switch that would lose local changes fails the hook, and with it the Attempt.

It never resets or cleans the Workspace, so work from an earlier Attempt stays.

### `worktree-before-remove.sh`

Runs before Loop deletes the Workspace.

1. If the Workspace is a linked worktree that git will remove (not the main working tree, not locked with `git worktree lock`), deletes its files except `.git`, outside the lock, since a large tree with build output takes seconds to delete.
2. Takes the lock and runs `git worktree remove --force <workspace>` in the repository if the Workspace is a registered worktree.
3. Runs `git worktree prune`.

It never deletes the branch `loop/<key>`, so removing a Workspace never loses committed work. Uncommitted changes in the Workspace are lost.

### How parallel hooks share the repository

Loop runs Attempts in parallel, so the hooks of several Workspaces run at the same time against one repository. Their worktrees share the repository's common git folder: the list of worktrees in `.git/worktrees/`, the refs and `.git/config`. Git takes no lock for readers of `.git/worktrees/`. `git worktree add` writes `.git/worktrees/<name>/commondir` with a plain create then write, and a git that lists the worktrees in between (another hook's `worktree add`, `worktree remove` or branch checkout) dies with `failed to read .git/worktrees/<name>/commondir` (bl-239). The first `sparse-checkout set` also writes `.git/config`, see above.

So the hooks take turns through one lock, `worktree-lock.sh`:

- **What it covers.** In `after_create`: `worktree prune`, `worktree add` and the first `sparse-checkout set`. In `before_run`: the branch checkout. In `before_remove`: `worktree remove` and `worktree prune`. Everything that only touches one worktree, the `read-tree` checkout of the files above all, runs outside it, so parallel checkouts still overlap. A locked span takes well under a second.
- **How it works.** The lock is the folder `loop-worktree-hooks.lock` in the common git folder, made with `mkdir`, which only one process can win, with the holder's PID in a `pid` file inside. A hook waits for it up to `LOOP_HOOK_LOCK_TIMEOUT` seconds, 20 by default, under Loop's hook timeout, then fails with `could not take the lock <path> within 20 s, held by pid <n>`. It releases the lock on exit, even when a step fails, and keeps the step's exit code.
- **A stale lock.** A hook killed with SIGKILL, as Loop does after the hook timeout, leaves the lock behind. The next hook takes it over when the PID is dead, when the `pid` file is older than 2 minutes (up to 3 with GNU `find`, which rounds down), or when the same lock folder has had no PID for 5 seconds. Takeovers go one at a time behind a second folder, `loop-worktree-hooks.lock.takeover`: under it the waiter reads the lock again and moves it aside only if it still holds the PID it judged stale, or for a lock with no PID is still the same folder. So a waiter never moves a lock another hook has just taken, whether the stale judgement came from a killed hook or from a holder that released between two reads. A hook that took the lock checks that the `pid` file reads back its own PID, and waits again otherwise. A takeover guard left by a hook killed in those few milliseconds is cleared when its PID is dead or it has had no PID for 5 seconds.
- **Not covered.** Git commands outside the hooks are not locked: the worktrees of `loop replay --verify` (`ReplaySites.kt`) and an agent running git in its own Workspace can still read a half written `commondir` while a hook runs `worktree add`. A hook running as another user, or a holder's PID reused by an unrelated live process, is outside what the lock handles: Loop runs every hook as one user, and the 2 minute rule ends a reused PID. Never take the lock around a call of another hook: `before_run` takes it only after `worktree-after-create.sh` returns, since a child waiting on its live parent would time out.

## Clone workspaces

Added in bl-28. A worktree shares with the project repository everything but `HEAD`, the index and a few per worktree refs: every branch and tag, `refs/loop/base/*`, `.git/config`, `info/exclude`, `info/attributes`, the hooks and the objects. So an agent in a worktree can run `git update-ref refs/heads/main HEAD` and move the operator's `main`, delete a tag, or set `core.hooksPath` in the shared config. With `workspace.git: clone` each Workspace is a clone of the project instead, and none of that reaches the project through git. The policy is in [Clone workspaces](spec-decisions.md#clone-workspaces-extension-bl-28).

### Usage

```yaml
workspace:
  root: ./workspaces
  git: clone
```

`clone` is the only value. Without the key the hooks own the folder as before. With it, when Loop creates a Workspace folder, before `after_create`, it runs `git clone --shared --no-checkout --no-tags` of the project repository (the folder of `WORKFLOW.md`), then:

1. removes the clone's `origin` remote, so `git push origin ...` from the Workspace fails instead of writing the project's refs;
2. copies the project's repository local `user.name` and `user.email` into the clone's config, when set;
3. writes the keep ref `refs/loop/workspace/<key>` in the project at the start commit;
4. creates or resets and checks out `loop/<key>`, from the project's `refs/heads/loop/<key>` when it exists, else from `LOOP_BASE_REF` (the base Loop fetched), else from the base resolved in the project, else from the project's `HEAD`, which Loop logs as a `clone_start` warning. Since bl-246 it is reset, so the creation works even when the project has `loop/<key>` checked out and `git clone` already made that branch.

When a worktree of the project, the main checkout or a linked one, has `loop/<key>` checked out as a new clone is made, Loop logs a `clone_branch_checked_out` warning naming the branch and that worktree's path: delivery will refuse to move that branch until the operator switches that worktree to another branch, see [import](#import-before-push).

Clone mode needs git 2.45 or later. Every git command Loop runs in a clone, and in the project for the clone steps, runs with lazy fetch off (`GIT_NO_LAZY_FETCH=1`), so a promisor an agent configures in its clone (`extensions.partialClone` set to an `ext::` URL, with a ref at a missing object) never runs its program as the operator. An older git ignores that variable, so creating or reusing a clone on it fails with the version found and the minimum. Worktree mode keeps working on an older git, and gets the same variable.

The clone is built in the sibling folder `.loop-clone-<key>` and moved into place once every step is done, so a run killed half way leaves no half made clone in the Workspace. A failed or cancelled creation, or a failed or cancelled `after_create`, deletes the folder and the keep refs.

Loop sets no sparse pattern: hiding `.loop/issues/` stays the hooks' job, and the worktree scripts must accept a clone (they make a worktree today, so a workflow that clones uses hooks that only set the sparse pattern and check out `loop/<key>`, or none).

A Workspace that already is a clone of the project (its `.git/objects/info/alternates` names the project's object folder, in the [layout Loop requires](#the-layout-of-a-clones-git)) is reused as it is on a retry or a restart, never reset. Loop first removes any remote the clone has, writes back a missing keep ref, and fails the Attempt if the clone has no `loop/<key>` branch. A folder whose `.git` is anything else, a worktree's `.git` file included, fails the Attempt with `cannot prepare workspace <key>: the workspace <path> is not a clone of the project, ...`. Since bl-253 the reason names what Loop refused: `<path>/.git/commondir is there, ...`, `<path> is a symbolic link, ...`, `<path> is hard linked (<n> links), ...`, or `extensions.refStorage in <path>/.git/config is "<value>", ...`.

### The layout of a clone's `.git`

Added in bl-253. Git finds the repository, the ref store and the work tree from files inside the clone's `.git`, which the agent owns, and some of them sent Loop's own git to the operator's project: reuse removed the project's remotes, the base ref was written in the project, and the merge check's `git merge` moved the project's `main`. So Loop requires of a clone's `.git`:

- it is a real folder, not a symbolic link and not a file;
- it holds no `commondir` entry, of any type;
- it holds no symbolic link at its top level or anywhere under `refs/` and `logs/` (links under `objects/` are allowed: they only add objects the clone sees);
- it holds no hard linked regular file (more than one link) at its top level or under `logs/`, since git rewrites some of those in place, such as `MERGE_MSG`;
- its `.git/config`, read with `git config --file` run outside the Workspace, has no `extensions.refStorage` value naming a path (one with `://`).

Loop checks this at reuse, and again right before every git process it runs in the clone, since the agent can change its `.git` between turns: the fingerprint, the workspace facts, the session brief, the merge check, the auto merge gate, the base ref write and the import. A failed check runs no git and fails that call with the reason. In clone mode a `.git` that became a file, a link or went missing is refused the same way: the mode comes from `workspace.git`, never from what `.git` looks like.

Every git process Loop runs in a clone also gets `GIT_DIR` and `GIT_COMMON_DIR` set to the clone's `.git` and `GIT_WORK_TREE` set to the clone, real paths, after any variable a caller passes, beside `GIT_NO_LAZY_FETCH=1`. `core.worktree` is config the agent can set at any time, and only `GIT_WORK_TREE` wins over it, so the merge check never writes the operator's files and `status` never reads them. The two rules work together: git's ref store reads `commondir` and `extensions.refStorage` whatever the three variables say. Git Loop runs in the project and in the import's temporary repository gets neither (`git clone` fails with `GIT_DIR` and `GIT_WORK_TREE` set), and `hooks` mode keeps its behavior.

Since bl-254 the core checks and the protected roots check follow the same rule: the git of `diff_policy` and `changed_line_coverage`, and the git of the ACP runner's protected roots check under `preset: agy`, check the clone's layout before every process and get the three pins. A refusal runs no git: the core check could not run, so the verdict is `unverified`, and the protected roots check refuses the turn, both naming the path or the key. In clone mode the protected roots check takes the Workspace itself as the repository and never walks up to the folder above it, so a clone whose `.git` went missing is refused instead of judged against the project. The runner learns the mode from the attempt context the orchestrator builds from `workspace.git`, never from what `.git` looks like.

The Gemini merge risk reviewer runs the same protected roots check in the issue's clone, so the auto merge gate hands it the project's base commit the merge was checked against and the clone mode, and it passes both to the runner. In clone mode the check refuses when no base is given: a clone's `main`, `master` and `origin/HEAD` are refs the agent owns, so the base is always the project's commit. The Codex and Claude Code reviewers keep the defaults, since their runners start no git of their own in the Workspace. The check compares the customization files on disk with the merge base of that commit and `HEAD`, not with the commit itself: a branch behind the base would otherwise be refused before its first turn for a file only the operator changed upstream, and the merge base is an ancestor of the project's commit, so its files are always ones the operator committed. An agent that moves `HEAD` back can at most bring back an older version the operator committed.

A Workspace is a clone workspace by its name, a folder right under `workspace.root` that is not the project, and the folder itself is never resolved to decide it. A Workspace that is a symbolic link to the project, or that resolves to the project through another name, is therefore still a clone workspace, and Loop refuses it instead of running its git unpinned in the project.

### What stays shared

The objects only, read only: the clone borrows the project's object folder through `objects/info/alternates` and writes its own objects in its own folder. Its refs, config, hooks and `info/` are its own. The commits an agent makes live in the clone, so Loop imports a verified commit into the project, see [import](#import-before-push).

### The base ref

Before the hooks and the agent run, and again at each base fetch of a verification or a merge check, Loop writes `refs/loop/base/<base>` in the clone at the commit it fetched in the project, so the prompt's `git merge-base --is-ancestor refs/loop/base/main HEAD` and `git merge refs/loop/base/main` work as in a worktree. Loop never reads a ref of the clone to choose a base, a diff base or a push target: moving `main` or `refs/loop/base/main` in the clone changes nothing Loop checks. `diff_policy` and `changed_line_coverage` resolve their base in the project, for every kind, with or without pull request delivery; a base the project does not have makes the check unable to run, so the verdict is `unverified`.

### Import before push

On a verified verdict on a clean committed fingerprint, and again before every push of a Workspace commit (the pull request push, the merge check's push of the merged head), Loop imports that commit into the project by its id: a fetch from the clone with `transfer.fsckObjects` into a temporary repository with no config file, then a plain copy of the objects into the project's object folder, then `refs/heads/loop/<key>` written in the project at that commit, with no old value check, since in clone mode Loop is that ref's only writer. The fetch tells the clone which commits the project already has (the keep refs, `loop/<key>`, `refs/loop/base/*`), so only the new objects are copied. The push then runs as without clones. A commit that fails the object check is never imported or pushed.

The import never moves `loop/<key>` while a worktree of the project has it checked out (bl-246): that would leave the operator's index and files disagreeing with their branch. It fails naming the branch and the worktree, and tells the operator to switch away, with no project ref changed. The pull request push and the merge check then end as a rejected delivery with that reason, and the import at verification logs its warning. Once the operator switches that worktree to another branch, the next import works.

In the project Loop writes these refs and no other: `refs/heads/loop/<key>`, `refs/loop/base/*`, `refs/loop/workspace/<key>` and `refs/loop/workspace-base/<key>`.

### The keep ref

The clone borrows objects it does not own. If the operator deletes the branch the clone started from and runs `git gc --prune=now`, those objects would go and the clone would break with `bad object HEAD`. `refs/loop/workspace/<key>` keeps the start commit reachable while the Workspace lives, and `refs/loop/workspace-base/<key>` the last base Loop wrote in the clone. Loop deletes both when it removes the Workspace; the imported `loop/<key>` branch stays.

Switching a workflow from `clone` back to the worktree hooks leaves the keep refs of the clones that still exist. Delete them with `git for-each-ref --format='delete %(refname)' refs/loop/workspace/ refs/loop/workspace-base/ | git update-ref --stdin`.

### What is lost compared with a worktree

- The operator's git hooks, such as husky or a lint hook, do not run on agent commits: the clone has no hooks.
- The project's `info/exclude` and `info/attributes` are not shared. Ignore rules belong in `.gitignore`.
- LFS objects, submodules and git-crypt keys are not shared.
- `branch.loop/<key>.merge` is unset: the clone has no remote to track.

### Threat model

Clones protect the operator's repository against mistakes and casual misuse by an agent, not against a hostile agent. Two hostile paths are closed: Loop's own git runs with lazy fetch off, so a promisor the agent configures never runs from it (bl-246), and Loop's own git never follows the clone's `.git` to the project, through the layout check and the three pins above (bl-253), the git of the core checks and of the protected roots check included (bl-254). Verification still runs the agent's build code unsandboxed as the operator, and any write by absolute path, `git push /abs/path/to/project` included, stays possible until the OS sandbox of M5.
