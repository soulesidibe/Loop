#!/bin/sh
# Loop after_create hook: makes the workspace (the current directory) a git
# worktree of the project repository, on the branch loop/<workspace key>, without
# the .loop/issues/ folder. See docs/workspaces.md.
#
# The repository is the folder of $LOOP_WORKFLOW. A new branch starts from
# $LOOP_BASE_REF, else main, else origin/HEAD, else HEAD. An existing branch is
# reused as is, never reset. Safe to run again: a workspace that already is a
# worktree of the repository is left untouched.
#
# The sparse checkout turns on extensions.worktreeConfig in the repository.
set -eu

: "${LOOP_WORKFLOW:?LOOP_WORKFLOW is not set}"
: "${LOOP_WORKSPACE_KEY:?LOOP_WORKSPACE_KEY is not set}"

repo=$(cd "$(dirname "$LOOP_WORKFLOW")" && pwd -P)
workspace=$(pwd -P)
branch="loop/$LOOP_WORKSPACE_KEY"

# The real path of a repository's common git folder, shared by all its worktrees.
common_dir() {
    (cd "$1" && cd "$(git rev-parse --git-common-dir)" && pwd -P)
}

# Sets the sparse pattern of the worktree $1, trying up to 5 times. On a repository
# without extensions.worktreeConfig, git first writes that setting to the shared
# .git/config, and two hooks doing it at once collide on the config lock (bl-08).
# Once one of them has written it, git skips the write, so a retry succeeds. After
# the last try the hook fails with git's own error and exit code.
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

if [ -e "$workspace/.git" ]; then
    top=$(git -C "$workspace" rev-parse --show-toplevel 2>/dev/null || true)
    if [ "$top" = "$workspace" ] && [ "$(common_dir "$workspace")" = "$(common_dir "$repo")" ]; then
        # A hard kill between worktree add and read-tree leaves an empty index: finish the checkout.
        sparse_set "$workspace"
        if [ -z "$(git -C "$workspace" ls-files | head -n 1)" ]; then
            git -C "$workspace" read-tree -mu HEAD
        fi
        exit 0
    fi
    echo "loop: $workspace has a .git entry but is not a worktree of $repo" >&2
    exit 1
fi

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
git -C "$workspace" read-tree -mu HEAD
