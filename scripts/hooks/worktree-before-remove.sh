#!/bin/sh
# Loop before_remove hook: unregisters and deletes the git worktree of the
# workspace (the current directory). The branch loop/<workspace key> is kept with
# its commits; uncommitted changes in the worktree are lost. See docs/workspaces.md.
#
# Safe to run again: a folder that is not a worktree of the repository is left to
# Loop, which deletes the workspace folder after this hook.
set -eu

: "${LOOP_WORKFLOW:?LOOP_WORKFLOW is not set}"

repo=$(cd "$(dirname "$LOOP_WORKFLOW")" && pwd -P)
workspace=$(pwd -P)

# The real path of a repository's common git folder, shared by all its worktrees.
common_dir() {
    (cd "$1" && cd "$(git rev-parse --git-common-dir)" && pwd -P)
}

if [ -e "$workspace/.git" ]; then
    top=$(git -C "$workspace" rev-parse --show-toplevel 2>/dev/null || true)
    if [ "$top" = "$workspace" ] && [ "$(common_dir "$workspace")" = "$(common_dir "$repo")" ]; then
        cd "$repo"
        git worktree remove --force "$workspace"
    fi
fi

git -C "$repo" worktree prune
