# Workspaces and Loop's own worktree hooks

Loop gives each Issue a Workspace, a folder under `workspace.root` named after the workspace key. Loop has no built in VCS logic: the hooks in `WORKFLOW.md` fill and clean the Workspace. This page documents the hook scripts Loop's own Project uses to make each Workspace a git worktree.

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

1. If the Workspace is a registered worktree, deletes its files except `.git`, outside the lock, since a large tree with build output takes seconds to delete.
2. Takes the lock and runs `git worktree remove --force <workspace>` in the repository if the Workspace is a registered worktree.
3. Runs `git worktree prune`.

It never deletes the branch `loop/<key>`, so removing a Workspace never loses committed work. Uncommitted changes in the Workspace are lost.

### How parallel hooks share the repository

Loop runs Attempts in parallel, so the hooks of several Workspaces run at the same time against one repository. Their worktrees share the repository's common git folder: the list of worktrees in `.git/worktrees/`, the refs and `.git/config`. Git takes no lock for readers of `.git/worktrees/`. `git worktree add` writes `.git/worktrees/<name>/commondir` with a plain create then write, and a git that lists the worktrees in between (another hook's `worktree add`, `worktree remove` or branch checkout) dies with `failed to read .git/worktrees/<name>/commondir` (bl-239). The first `sparse-checkout set` also writes `.git/config`, see above.

So the hooks take turns through one lock, `worktree-lock.sh`:

- **What it covers.** In `after_create`: `worktree prune`, `worktree add` and the first `sparse-checkout set`. In `before_run`: the branch checkout. In `before_remove`: `worktree remove` and `worktree prune`. Everything that only touches one worktree, the `read-tree` checkout of the files above all, runs outside it, so parallel checkouts still overlap. A locked span takes well under a second.
- **How it works.** The lock is the folder `loop-worktree-hooks.lock` in the common git folder, made with `mkdir`, which only one process can win, with the holder's PID in a `pid` file inside. A hook waits for it up to `LOOP_HOOK_LOCK_TIMEOUT` seconds, 20 by default, under Loop's hook timeout, then fails with `could not take the lock <path> within 20 s, held by pid <n>`. It releases the lock on exit, even when a step fails, and keeps the step's exit code.
- **A stale lock.** A hook killed with SIGKILL, as Loop does after the hook timeout, leaves the lock behind. The next hook takes it over when the PID is dead, when the `pid` file is older than 2 minutes, or when the lock has had no PID for 5 seconds. It moves the folder aside first, an atomic step, so of two waiters only one takes it over. The one race left needs two waiters taking over the same stale lock while a third hook takes it in between, within milliseconds: the second waiter then moves the third hook's lock aside and says so on stderr.
- **Not covered.** Git commands outside the hooks are not locked: the worktrees of `loop replay --verify` (`ReplaySites.kt`) and an agent running git in its own Workspace can still read a half written `commondir` while a hook runs `worktree add`. Never take the lock around a call of another hook: `before_run` takes it only after `worktree-after-create.sh` returns, since a child waiting on its live parent would time out.
