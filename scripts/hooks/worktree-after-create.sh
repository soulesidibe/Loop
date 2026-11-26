#!/bin/sh
# Loop after_create hook: makes the workspace (the current directory) a git
# worktree of the project repository, on the branch loop/<workspace key>, without
# the .loop/issues/ folder. A workspace Loop made a clone of the project
# (workspace.git: clone) only gets the branch checked out and the folder hidden,
# with no lock. See docs/workspaces.md.
#
# The repository is the folder of $LOOP_WORKFLOW. A new branch starts from
# $LOOP_BASE_REF, else main, else origin/HEAD, else HEAD. An existing branch is
# reused as is, never reset. Safe to run again: a workspace that already is a
# worktree of the repository is left untouched.
#
# The sparse checkout turns on extensions.worktreeConfig in the repository.
#
# Parallel hooks on one repository take turns on the steps that change its shared
# worktree metadata and config (worktree prune, worktree add, the sparse pattern)
# through the lock of worktree-lock.sh. The checkout of the files runs outside it.
set -eu

. "$(dirname "$0")/worktree-lock.sh"

: "${LOOP_WORKFLOW:?LOOP_WORKFLOW is not set}"
: "${LOOP_WORKSPACE_KEY:?LOOP_WORKSPACE_KEY is not set}"

repo=$(cd "$(dirname "$LOOP_WORKFLOW")" && pwd -P)
workspace=$(pwd -P)
branch="loop/$LOOP_WORKSPACE_KEY"

# Sets the sparse pattern of the worktree $1, trying up to 5 times. On a repository
# without extensions.worktreeConfig, git first writes that setting to the shared
# .git/config, which two hooks doing it at once collide on (bl-08). The hooks call
# it under the lock, so the retry is only a safety net. After the last try the hook
# fails with git's own error and exit code.
sparse_set() {
    try=1
    while :; do
        status=0
        git -C "$1" sparse-checkout set --no-cone '/*' '!/.loop/issues/' || status=$?
        if [ "$status" -eq 0 ]; then
            return 0
        fi
        if [ "$try" -ge 5 ]; then
            return "$status"
        fi
        echo "loop: sparse-checkout set failed on try $try of 5, trying again" >&2
        try=$((try + 1))
        sleep 0.2 2>/dev/null || sleep 1
    done
}

# bl-28: with workspace.git: clone, Loop made the workspace a clone of the project
# before this hook, its files checked out on loop/<workspace key>. The hook checks
# the branch out again if HEAD left it and hides .loop/issues/: no worktree add, no
# lock, since the clone shares no metadata or config with the project.
if is_clone "$workspace" "$repo"; then
    if [ "$(clone_git "$workspace" symbolic-ref --quiet --short HEAD || true)" != "$branch" ]; then
        clone_git "$workspace" checkout --quiet "$branch"
    fi
    if ! clone_git "$workspace" sparse-checkout list 2>/dev/null | grep -qxF '!/.loop/issues/'; then
        clone_git "$workspace" sparse-checkout set --no-cone '/*' '!/.loop/issues/'
    fi
    exit 0
fi

if [ -e "$workspace/.git" ]; then
    top=$(git -C "$workspace" rev-parse --show-toplevel 2>/dev/null || true)
    if [ "$top" = "$workspace" ] && [ "$(common_dir "$workspace")" = "$(common_dir "$repo")" ]; then
        # A hard kill between worktree add and read-tree leaves an empty index: finish the checkout.
        # Once extensions.worktreeConfig is on, the sparse pattern only writes this worktree's own config.
        if [ "$(git -C "$workspace" config --type=bool --get extensions.worktreeConfig 2>/dev/null || :)" = true ]; then
            sparse_set "$workspace"
        else
            loop_lock_take "$(common_dir "$repo")"
            sparse_set "$workspace"
            loop_lock_release
        fi
        if [ -z "$(git -C "$workspace" ls-files | head -n 1)" ]; then
            git -C "$workspace" read-tree -mu HEAD
        fi
        exit 0
    fi
    echo "loop: $workspace has a .git entry but is neither a worktree nor a clone of $repo" >&2
    exit 1
fi

loop_lock_take "$(common_dir "$repo")"

# A workspace removed without before_remove leaves a stale registration behind.
git -C "$repo" worktree prune

if git -C "$repo" show-ref --verify --quiet "refs/heads/$branch"; then
    git -C "$repo" worktree add --quiet --no-checkout "$workspace" "$branch"
else
    base=${LOOP_BASE_REF:-}
    if [ -z "$base" ]; then
        if git -C "$repo" show-ref --verify --quiet refs/heads/main; then
            base=main
        elif git -C "$repo" rev-parse --verify --quiet origin/HEAD >/dev/null; then
            base=origin/HEAD
        else
            base=HEAD
        fi
    fi
    git -C "$repo" worktree add --quiet --no-checkout -b "$branch" "$workspace" "$base"
fi

sparse_set "$workspace"
loop_lock_release

git -C "$workspace" read-tree -mu HEAD
