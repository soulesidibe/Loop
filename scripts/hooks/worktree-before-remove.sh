#!/bin/sh
# Loop before_remove hook: unregisters and deletes the git worktree of the
# workspace (the current directory). The branch loop/<workspace key> is kept with
# its commits; uncommitted changes in the worktree are lost. See docs/workspaces.md.
#
# Safe to run again: a folder that is not a worktree of the repository is left to
# Loop, which deletes the workspace folder after this hook.
#
# The worktree remove and prune run under the lock of worktree-lock.sh, shared
# with the other hooks. The files of the worktree are deleted before, outside it.
set -eu

: "${LOOP_WORKFLOW:?LOOP_WORKFLOW is not set}"

. "$(dirname "$0")/worktree-lock.sh"

repo=$(cd "$(dirname "$LOOP_WORKFLOW")" && pwd -P)
workspace=$(pwd -P)

remove=
predelete=
if [ -e "$workspace/.git" ]; then
    top=$(git -C "$workspace" rev-parse --show-toplevel 2>/dev/null || true)
    if [ "$top" = "$workspace" ] && [ "$(common_dir "$workspace")" = "$(common_dir "$repo")" ]; then
        remove=yes
        # Deleting a large tree (build output) takes seconds: do it before taking the
        # lock, so worktree remove under it has only the .git file left to delete.
        # Only for a linked worktree that git will remove: never the main working
        # tree (.git is a folder there) nor a worktree locked with git worktree lock.
        gitdir=$(git -C "$workspace" rev-parse --absolute-git-dir 2>/dev/null || :)
        if [ -f "$workspace/.git" ] && [ "$workspace" != "$repo" ] && [ -n "$gitdir" ] && [ ! -e "$gitdir/locked" ]; then
            predelete=yes
        fi
    fi
fi

if [ -n "$predelete" ]; then
    for entry in "$workspace"/* "$workspace"/.[!.]* "$workspace"/..?*; do
        if [ -e "$entry" ] || [ -L "$entry" ]; then
            if [ "${entry##*/}" != .git ]; then
                rm -rf "$entry" 2>/dev/null || :
            fi
        fi
    done
fi

loop_lock_take "$(common_dir "$repo")"
if [ -n "$remove" ]; then
    cd "$repo"
    git worktree remove --force "$workspace"
fi
git -C "$repo" worktree prune
