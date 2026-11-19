#!/bin/sh
# Loop before_run hook: makes sure the workspace (the current directory) is the
# worktree of the branch loop/<workspace key>, with the .loop/issues/ folder left
# out. See docs/workspaces.md.
#
# Self healing: runs worktree-after-create.sh first, which does nothing when the
# workspace already is a worktree. Never resets or cleans: a branch switch that
# would lose local changes fails the hook, and with it the attempt.
set -eu

: "${LOOP_WORKSPACE_KEY:?LOOP_WORKSPACE_KEY is not set}"

sh "$(dirname "$0")/worktree-after-create.sh"

branch="loop/$LOOP_WORKSPACE_KEY"
current=$(git symbolic-ref --quiet --short HEAD || true)
if [ "$current" != "$branch" ]; then
    git checkout --quiet "$branch"
fi

if ! git sparse-checkout list 2>/dev/null | grep -qxF '!/.loop/issues/'; then
    git sparse-checkout set --no-cone '/*' '!/.loop/issues/'
fi
