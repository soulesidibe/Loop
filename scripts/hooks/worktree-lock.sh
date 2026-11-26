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
# The lock is released on exit, so a failing step still frees it. A hook killed
# with SIGKILL leaves a stale lock behind, which the next hook takes over.
#
# Never take the lock while calling another hook that takes it: the child would
# wait on its live parent until the timeout.

loop_lock=

# The real path of a repository's common git folder, shared by all its worktrees.
common_dir() {
    (cd "$1" && cd "$(git rev-parse --git-common-dir)" && pwd -P)
}

# Removes the lock folder if this shell holds it. Always succeeds, so the exit
# trap never changes the exit code of the hook.
loop_lock_release() {
    if [ -n "${loop_lock:-}" ]; then
        if [ "$(cat "$loop_lock/pid" 2>/dev/null || :)" = "$$" ] || [ -e "$loop_lock/pid.$$" ]; then
            rm -rf "$loop_lock" 2>/dev/null || :
        fi
        loop_lock=
    fi
    return 0
}

# Moves the stale lock $1, last seen held by $2 (empty when it had no PID), out of
# the way. The move is atomic, so of two waiters only one takes the lock over; the
# other's move fails and it goes back to mkdir.
loop_lock_takeover() {
    aside="$1.stale.$$"
    rm -rf "$aside" 2>/dev/null || :
    if mv "$1" "$aside" 2>/dev/null; then
        moved=$(cat "$aside/pid" 2>/dev/null || :)
        if [ "$moved" != "$2" ]; then
            # Another waiter took the stale lock over and a new holder took it in
            # the meantime. That holder has lost its lock; its release leaves ours alone.
            echo "loop: warning: moved the lock $1 of pid ${moved:-unknown} aside, not the stale one of pid ${2:-unknown}" >&2
        else
            echo "loop: took over the stale lock $1 of pid ${2:-unknown}" >&2
        fi
        rm -rf "$aside" 2>/dev/null || :
    fi
    return 0
}

# Takes the lock of the common git folder $1, waiting for it with a time bound.
# A hook that cannot take it in time fails, naming the lock and its holder.
loop_lock_take() {
    lock="$1/loop-worktree-hooks.lock"
    timeout=${LOOP_HOOK_LOCK_TIMEOUT:-20}
    case $timeout in
        '' | *[!0-9]*)
            echo "loop: LOOP_HOOK_LOCK_TIMEOUT must be a whole number of seconds, not '$timeout'" >&2
            exit 1
            ;;
    esac
    trap loop_lock_release EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    deadline=$(($(date +%s) + timeout))
    pidless_since=
    while ! mkdir "$lock" 2>/dev/null; do
        holder=$(cat "$lock/pid" 2>/dev/null || :)
        now=$(date +%s)
        stale=
        case $holder in
            '' | *[!0-9]*)
                # The holder is between mkdir and writing its PID, or died there.
                # A lock that is gone was released: the next mkdir may win.
                if [ ! -d "$lock" ]; then
                    pidless_since=
                elif [ -z "$pidless_since" ]; then
                    pidless_since=$now
                elif [ $((now - pidless_since)) -ge 5 ]; then
                    stale=yes
                fi
                ;;
            *)
                pidless_since=
                if ! kill -0 "$holder" 2>/dev/null; then
                    stale=yes
                elif [ -n "$(find "$lock/pid" -mmin +2 2>/dev/null || :)" ]; then
                    stale=yes
                fi
                ;;
        esac
        if [ -n "$stale" ]; then
            loop_lock_takeover "$lock" "$holder"
            pidless_since=
            continue
        fi
        if [ "$now" -ge "$deadline" ]; then
            echo "loop: could not take the lock $lock within $timeout s, held by pid ${holder:-unknown}" >&2
            exit 1
        fi
        sleep 0.1 2>/dev/null || sleep 1
    done
    loop_lock=$lock
    echo "$$" >"$lock/pid.$$"
    mv "$lock/pid.$$" "$lock/pid"
}
