#!/bin/sh
# Loop before_run hook: makes sure the workspace (the current directory) is the
# worktree of the branch loop/<workspace key>, with the .loop/issues/ folder left
# out. See docs/workspaces.md.
#
# Self healing: runs worktree-after-create.sh first, which does nothing when the
# workspace already is a worktree. Never resets or cleans: a branch switch that
# would lose local changes fails the hook, and with it the attempt.
#
# A branch switch lists the other worktrees, so it runs under the lock of
# worktree-lock.sh. The lock is taken after worktree-after-create.sh returns,
# never around it: that hook takes the same lock.
#
# A workspace Loop made a clone of the project (workspace.git: clone) is done once
# worktree-after-create.sh returns: that hook checks a clone out on the branch and
# hides .loop/issues/, with the clone's git pinned, so this hook stops there.
set -eu

: "${LOOP_WORKFLOW:?LOOP_WORKFLOW is not set}"
: "${LOOP_WORKSPACE_KEY:?LOOP_WORKSPACE_KEY is not set}"

sh "$(dirname "$0")/worktree-after-create.sh"

. "$(dirname "$0")/worktree-lock.sh"

if is_clone "$(pwd -P)" "$(cd "$(dirname "$LOOP_WORKFLOW")" && pwd -P)"; then
    exit 0
fi

branch="loop/$LOOP_WORKSPACE_KEY"
current=$(git symbolic-ref --quiet --short HEAD || true)
if [ "$current" != "$branch" ]; then
    loop_lock_take "$(common_dir .)"
    git checkout --quiet "$branch"
    loop_lock_release
fi

if ! git sparse-checkout list 2>/dev/null | grep -qxF '!/.loop/issues/'; then
    git sparse-checkout set --no-cone '/*' '!/.loop/issues/'
fi
