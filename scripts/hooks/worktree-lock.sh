# Sourced by Loop's worktree hooks, never run on its own. See docs/workspaces.md.
#
# Parallel hooks on one repository share its common git folder: `git worktree add`
# writes .git/worktrees/<name>/commondir with a plain create then write, and a git
# in another hook that lists the worktrees in between dies on the empty file. The
# first `sparse-checkout set` also writes the shared .git/config. So the hooks run
# the steps that change or list the shared worktree metadata under one lock,
# loop-worktree-hooks.lock in the common git folder, and the per worktree work
# (read-tree, the checkout of files) outside it.
#
# The lock is a folder made with mkdir, which only one process can win, with the
# holder's PID in a pid file inside. A waiter gives up after
# $LOOP_HOOK_LOCK_TIMEOUT seconds (20 by default), under Loop's hook timeout, and
# takes over a stale lock: one whose PID is dead, whose pid file is older than 2
# minutes (longer than any hook may run), or that has had no PID for 5 seconds.
# Takeovers go one at a time behind a second folder, <lock>.takeover, and check
# again under it that the lock is still the stale one before moving it aside, so
# a waiter never moves a lock that another hook has just taken. The lock is
# released on exit, so a failing step still frees it, and the step's exit code
# is kept. A hook killed with SIGKILL leaves a stale lock, which the next hook
# takes over.
#
# Never take the lock while calling another hook that takes it: the child would
# wait on its live parent until the timeout.
#
# Every variable and function here starts with loop_lock_, except common_dir.

loop_lock=
loop_lock_guard=

# The real path of a repository's common git folder, shared by all its worktrees.
common_dir() {
    (cd "$1" && cd "$(git rev-parse --git-common-dir)" && pwd -P)
}

# Removes the lock folder, and a takeover guard, if this shell holds them. Always
# succeeds, so the exit trap never changes the exit code of the hook.
loop_lock_release() {
    if [ -n "${loop_lock_guard:-}" ]; then
        if [ "$(cat "$loop_lock_guard/pid" 2>/dev/null || :)" = "$$" ]; then
            rm -rf "$loop_lock_guard" 2>/dev/null || :
        fi
        loop_lock_guard=
    fi
    if [ -n "${loop_lock:-}" ]; then
        if [ "$(cat "$loop_lock/pid" 2>/dev/null || :)" = "$$" ] || [ -e "$loop_lock/pid.$$" ]; then
            rm -rf "$loop_lock" 2>/dev/null || :
        fi
        loop_lock=
    fi
    trap - HUP INT TERM
    return 0
}

# Prints the inode of the folder $1, or nothing when it is gone.
loop_lock_inode() {
    ls -di "$1" 2>/dev/null | awk '{ print $1 }' || :
}

# True when the holder $2 of the lock $1 is gone: its PID is dead, or its pid file
# is older than 2 minutes.
loop_lock_holder_gone() {
    if ! kill -0 "$2" 2>/dev/null; then
        return 0
    fi
    [ -n "$(find "$1/pid" -mmin +2 2>/dev/null || :)" ]
}

# Moves the stale lock $1 aside, judged stale with the holder $2 (empty when it had
# no PID) and the folder inode $3. Behind the guard <lock>.takeover it reads the
# lock again and moves it only if it still is that holder's and still stale.
loop_lock_takeover() {
    loop_lock_g="$1.takeover"
    if ! mkdir "$loop_lock_g" 2>/dev/null; then
        # Another waiter is taking over. A guard left by a waiter that was killed
        # in those few milliseconds is cleared: dead PID, or no PID for 5 seconds.
        loop_lock_gpid=$(cat "$loop_lock_g/pid" 2>/dev/null || :)
        case $loop_lock_gpid in
            '' | *[!0-9]*)
                loop_lock_now=$(date +%s)
                if [ -z "$loop_lock_guard_since" ]; then
                    loop_lock_guard_since=$loop_lock_now
                elif [ $((loop_lock_now - loop_lock_guard_since)) -ge 5 ]; then
                    rm -rf "$loop_lock_g" 2>/dev/null || :
                    loop_lock_guard_since=
                fi
                ;;
            *)
                loop_lock_guard_since=
                if ! kill -0 "$loop_lock_gpid" 2>/dev/null; then
                    rm -rf "$loop_lock_g" 2>/dev/null || :
                fi
                ;;
        esac
        return 0
    fi
    loop_lock_guard=$loop_lock_g
    loop_lock_guard_since=
    echo "$$" >"$loop_lock_g/pid" 2>/dev/null || :

    loop_lock_again=$(cat "$1/pid" 2>/dev/null || :)
    loop_lock_still=
    if [ -n "$2" ]; then
        if [ "$loop_lock_again" = "$2" ] && loop_lock_holder_gone "$1" "$2"; then
            loop_lock_still=yes
        fi
    elif [ -z "$loop_lock_again" ] && [ -n "$3" ] && [ "$(loop_lock_inode "$1")" = "$3" ]; then
        loop_lock_still=yes
    fi
    if [ -n "$loop_lock_still" ]; then
        loop_lock_aside="$1.stale.$$"
        rm -rf "$loop_lock_aside" 2>/dev/null || :
        if mv "$1" "$loop_lock_aside" 2>/dev/null; then
            echo "loop: took over the stale lock $1 of pid ${2:-unknown}" >&2
            rm -rf "$loop_lock_aside" 2>/dev/null || :
        fi
    fi

    rm -rf "$loop_lock_g" 2>/dev/null || :
    loop_lock_guard=
    return 0
}

# Takes the lock of the common git folder $1, waiting for it with a time bound.
# A hook that cannot take it in time fails, naming the lock and its holder.
loop_lock_take() {
    if [ -z "$1" ] || [ ! -d "$1" ]; then
        echo "loop: no common git folder to hold the hook lock in ('$1')" >&2
        exit 1
    fi
    loop_lock_path="$1/loop-worktree-hooks.lock"
    loop_lock_timeout=${LOOP_HOOK_LOCK_TIMEOUT:-20}
    case $loop_lock_timeout in
        '' | *[!0-9]*)
            echo "loop: LOOP_HOOK_LOCK_TIMEOUT must be a whole number of seconds, not '$loop_lock_timeout'" >&2
            exit 1
            ;;
    esac
    trap loop_lock_release EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    loop_lock_deadline=$(($(date +%s) + loop_lock_timeout))
    loop_lock_pidless_since=
    loop_lock_pidless_inode=
    loop_lock_guard_since=
    while :; do
        if mkdir "$loop_lock_path" 2>/dev/null; then
            loop_lock=$loop_lock_path
            if echo "$$" >"$loop_lock_path/pid.$$" 2>/dev/null && mv "$loop_lock_path/pid.$$" "$loop_lock_path/pid" 2>/dev/null &&
                [ "$(cat "$loop_lock_path/pid" 2>/dev/null || :)" = "$$" ]; then
                return 0
            fi
            # The new lock was moved aside before its PID was written: wait again.
            loop_lock=
        fi
        loop_lock_holder=$(cat "$loop_lock_path/pid" 2>/dev/null || :)
        loop_lock_now=$(date +%s)
        loop_lock_ino=
        loop_lock_stale=
        case $loop_lock_holder in
            '' | *[!0-9]*)
                # The holder is between mkdir and writing its PID, or died there.
                # Another folder, or none, starts the count again.
                loop_lock_holder=
                loop_lock_ino=$(loop_lock_inode "$loop_lock_path")
                if [ -z "$loop_lock_ino" ] || [ "$loop_lock_ino" != "$loop_lock_pidless_inode" ]; then
                    loop_lock_pidless_since=$loop_lock_now
                    loop_lock_pidless_inode=$loop_lock_ino
                elif [ $((loop_lock_now - loop_lock_pidless_since)) -ge 5 ]; then
                    loop_lock_stale=yes
                fi
                ;;
            *)
                loop_lock_pidless_since=
                loop_lock_pidless_inode=
                if loop_lock_holder_gone "$loop_lock_path" "$loop_lock_holder"; then
                    loop_lock_stale=yes
                fi
                ;;
        esac
        if [ -n "$loop_lock_stale" ]; then
            loop_lock_takeover "$loop_lock_path" "$loop_lock_holder" "$loop_lock_ino"
            loop_lock_pidless_since=
            loop_lock_pidless_inode=
        fi
        if [ "$loop_lock_now" -ge "$loop_lock_deadline" ] && [ -d "$loop_lock_path" ]; then
            echo "loop: could not take the lock $loop_lock_path within $loop_lock_timeout s, held by pid ${loop_lock_holder:-unknown}" >&2
            exit 1
        fi
        if [ -d "$loop_lock_path" ]; then
            sleep 0.1 2>/dev/null || sleep 1
        fi
    done
}
