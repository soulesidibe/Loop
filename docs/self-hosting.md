# Self hosting

From the end of M1 phase one, Loop works its own `ready-for-agent` issues. The running Loop is an installed build outside the repository. It changes only when the operator runs `./gradlew installLoop`, typically after merging work Loop should use, never during a run with active Attempts, and every Attempt records the version it ran with. This page covers the install, the version and how to run Loop on its own issues.

Written 2026-11-11 in issue m1-12. Terms follow [`CONTEXT.md`](../CONTEXT.md). The plan is in the parent spec, [`.loop/specs/loop-m1-to-m3.md`](../.loop/specs/loop-m1-to-m3.md), section self hosting.

## Contents

1. [Install](#install)
2. [Version](#version)
   - [Java](#java)
3. [Known limits](#known-limits)
4. [Running Loop on its own issues](#running-loop-on-its-own-issues)
   - [Sleep while work runs](#sleep-while-work-runs)
   - [Gradle daemons per Attempt](#gradle-daemons-per-attempt)
   - [Loop mode on Loop's own backlog](#loop-mode-on-loops-own-backlog)
5. [GitHub playground](#github-playground)
   - [Token](#token)
   - [A WORKFLOW.md for the playground](#a-workflowmd-for-the-playground)

## Install

```sh
./gradlew installLoop
./gradlew installLoop -PloopInstallDir=/path/to/dest
```

- The default destination is `$LOOP_HOME/install`, so `~/.loop/install` when `LOOP_HOME` is unset.
- The task builds the distribution with `installDist`, then runs its hidden `loop install --repo <repo> <dest>` command, which checks the destination and copies the build.
- The copy goes to `<dest>.tmp` first. An existing install is renamed to `<dest>.old`, the new build takes its place, and `<dest>.old` is deleted.
- `build` and `check` never run `installLoop`. The installed build changes only when you run it by hand, typically after merging work Loop should use. Never install during a run with active Attempts: stop the run first, then start it again on the new build. Each Attempt records the version it ran with, so a trace shows which build worked it.

Success prints `Installed loop <version> at <dest>.`, or `Installed loop (version unknown) at <dest>.` when no version was recorded, and exits 0. The install refuses with one of these exit codes:

| Exit | Reason | What to do |
|---|---|---|
| 1 | The destination is inside the repository, nests with the install the command runs from, holds `LOOP_HOME` or your home folder, cannot be read, or is a folder that is not empty and is not a Loop install. A leftover `<dest>.tmp` or `<dest>.old` that is not a Loop install is refused too. | Choose another path, or check the leftover folder. Nothing was changed. |
| 2 | A `loop run` is live under the same `LOOP_HOME`, or a run file or the run folder cannot be read. The message names the project, the pid and the workflow. | Stop the run with `loop stop <WORKFLOW.md>`, or check the file, then install again. |
| 3 | The copy or the swap failed. | Read the message. After a copy failure the destination is unchanged. After a swap failure the install tries to restore the previous build, and the message says where each build is. |

Only a live run blocks the install. The run file lock decides: a stale run file (its process is gone) is removed, as `loop stop` does, and an Attempt left open in the store by a dead process never blocks.

## Version

The version comes from the release tags, bare semver with no `v` prefix, through `git describe --tags --match '[0-9]*' --abbrev=12 --always --dirty`:

- On a release tag it is the tag: `0.1.0`.
- After a tag it is the tag, the number of commits since and the commit: `0.1.0-5-g3f2a9c1d7e4b`. This is a label, not a semver to compare: semver sorts it before `0.1.0`, though it is newer.
- With no tag reachable, or a clone without tags, it is the 12 character hex git SHA: `3f2a9c1d7e4b`. A tag that is not a semver gives the SHA too.
- `-dirty` is appended when tracked files had uncommitted changes at build time: `0.1.0-dirty`, `3f2a9c1d7e4b-dirty`.
- `unknown` means the build had no git (no `git` command, no `.git` folder, no commit) or git failed. Building the distribution never fails for this, but `./gradlew build` does fail on a source tree without git, because a test asserts that the installed version is a real one.

`build-info.properties`, at the root of the install, also records the full commit as `commit=<sha>`, so a build is traceable whatever its version.

Git runs when Gradle configures a build that includes the release archives (`loop-<version>.tar.gz` and `.zip`, named after the version), as `./gradlew build` does: a new commit or a dirty tree reconfigures that build once. Nothing compiled or tested depends on the version.

The version shows in three places:

- `loop --version` prints it.
- Every `attempt_started` event stores it as `loop_version` in its data, first and retry Attempts alike.
- The `loop trace` header shows it after the start time, as `loop <version>`. Attempts recorded before m1-12 have no version and no `loop` column.

### Java

`bin/loop` looks for Java in this order: `runtime/bin/java` inside the install, when it is executable, then `JAVA_HOME`, then `java` on the `PATH`. It sets and exports nothing: agents inherit Loop's environment, so a `JAVA_HOME` set for Loop would make the project's build run with Loop's JDK. A packaged install links its Java 21 as `runtime/` instead, and a project that needs another JDK keeps its own `JAVA_HOME`.

## Known limits

- The window between the live run check and the swap is not locked. A `loop run` started in that window can run from a half replaced install.
- The check sees only runs under the same `LOOP_HOME`. A run started with another `LOOP_HOME` is invisible to it.
- The run file lock is a POSIX file lock. On NFS and other network file systems it may not hold, so a live run can go unseen.
- There is no automatic reinstall. Merged slices reach the installed build only when you run `installLoop` again.

## Running Loop on its own issues

Loop's own [`WORKFLOW.md`](../WORKFLOW.md) dispatches `todo` issues labeled `ready-for-agent`, runs Claude Code in a git worktree per issue on the branch `loop/<identifier>` (see [`workspaces.md`](workspaces.md)), and asks the agent to end with a commit and `loop tool move review`.

1. Run Loop from the main checkout's `WORKFLOW.md`, never from a worktree. A worktree has no `.loop/issues/`, so Loop would find no issues there.
2. Make exactly one issue eligible. `max_concurrent_agents` is 1, so a second eligible issue waits for the first agent. Remove `ready-for-agent` from the others for now, or add a `blocked_by`.
3. Install and start the run:

   ```sh
   ./gradlew installLoop
   ~/.loop/install/bin/loop run /path/to/Loop/WORKFLOW.md
   ```

   The shell that starts the run needs `LOOP_PR_TOKEN` set, see [the token](#the-token).
4. See what the run is doing with `~/.loop/install/bin/loop status /path/to/Loop/WORKFLOW.md`, from any terminal. Follow the Attempt with `~/.loop/install/bin/loop trace <identifier>`. Since m3-01 a verified issue ends merged and `done`, or waits for `loop approve`, see [Loop mode](#loop-mode-on-loops-own-backlog).
5. Stop with `~/.loop/install/bin/loop stop /path/to/Loop/WORKFLOW.md`, or Ctrl+C in the terminal that runs it.

A branch that already exists keeps its commits: a later attempt, one the retry queue dispatches, and a fresh dispatch after a move from `review` back to `todo` both reuse it, and the `before_run` hook only checks it out. Merges into `main` since then are synced from the prompt, not the hook: its "Your workspace" section tells the agent, on every dispatch, to run `git merge refs/loop/base/main` when `git merge-base --is-ancestor refs/loop/base/main HEAD` fails, resolve any conflict keeping the intent of both sides, commit the merge, then rerun the full `./gradlew build` before anything else (m1-42, bl-02). On a fresh branch the check passes and nothing happens. The agent never fetches. Loop merges pull requests on GitHub and never moves the main checkout's local `main`, so a branch cut from it started from the last pull and missed the merges since (on 2026-11-15, bl-85 branched without bl-84 and bl-86, its two blockers, and lost an attempt to a merge conflict). An agent that resolves a conflict can prove the build, a hook that hits one would fail the attempt.

Since bl-59 Loop fetches `main` itself before every attempt, the first and every retry, with its token and the isolated fetch of the merge check, since Loop's kind has `delivery.pull_request`. It records `base_fetched`, writes `refs/loop/base/main` at the fetched commit, and passes that commit to the `after_create` and `before_run` hooks as `LOOP_BASE_REF`, so a new branch starts from it. Loop never moves the local `main` or any other branch. The ref a prompt syncs with is therefore `refs/loop/base/main`: `git merge-base --is-ancestor refs/loop/base/main HEAD`, else `git merge refs/loop/base/main`, the ref the merge check's send back comment names too. A failed fetch does not hold the attempt: Loop records `base_fetch_failed`, logs a warning that `loop log`, `loop trace` and the run log show, and the hooks get the last fetched `refs/loop/base/main`, or no `LOOP_BASE_REF` before the first fetch, when the hook falls back to the local `main`. Details in [Base fetch before every attempt](spec-decisions.md#base-fetch-before-every-attempt-extension-bl-59).

Loop's own `WORKFLOW.md` uses the plain hooks of [`workspaces.md`](workspaces.md#usage) and a prompt that syncs with `refs/loop/base/main`, which Loop fetches before each run. The earlier `fetch-base.sh` workaround is gone.

Workspaces live in `workspaces/` at the repository root, which is gitignored. The agent keeps its progress file and evidence in `.scratch/<identifier>/` inside its workspace, also gitignored, so they are never committed.

### Sleep while work runs

Since m4-09, `loop run` keeps the Mac awake while work runs (bl-156). While at least one Attempt, verification or merge check runs, it holds an idle sleep assertion through a `caffeinate -i -w <loop pid>` child, and it stops that child when none runs, so an idle overnight poll still lets the Mac sleep. `-w` ends the assertion if `loop run` dies, and the shutdown hook stops the child when the run ends. The operator log shows `event=keep_awake outcome=started` and `outcome=released`. No `caffeinate -i` wrapper is needed anymore.

Why: on 2026-11-18, during the M3 exit run on the pilot app, the Mac went to idle sleep on battery during an Attempt. The turn failed with `ENOTFOUND` after the sleep, and the error retry due 10 seconds later started 2.5 hours later, after the wake, so the sleep spent one of the two attempts of `retries.max_attempts: 2`.

Limits:

- A lid close still sleeps the Mac, and so does a forced sleep from the Apple menu or a low battery. No idle assertion prevents them. Keep the lid open, or use clamshell mode on power with an external display, during a long run.
- On other systems, or on a Mac where `caffeinate` cannot start, `loop run` logs one `event=keep_awake outcome=failed` line at its first work and runs as before.
- What happens to an Attempt a sleep interrupts anyway is m4-08's rule: the failure reads as a network error and takes no attempt slot.

### Gradle daemons per Attempt

Loop's `WORKFLOW.md` sets one [`agent.env`](spec-decisions.md#agentenv-extension-bl-01) variable (bl-01):

```yaml
agent:
  env:
    GRADLE_OPTS: "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons"
```

Why: when an Attempt ends, Loop kills every process the agent started, and that kill stays, since an Attempt must leave no process behind. A Gradle daemon an agent starts is one of them. By default Gradle registers its daemons in `~/.gradle/daemon/`, and any build on the machine with compatible settings connects to an idle one there: a sibling Attempt, or a build the operator runs from the main checkout. When the Attempt that started the daemon ends, every build connected to it fails with "Gradle build daemon disappeared unexpectedly". Run 893175a3 lost a merge check this way on 2026-11-11.

With `org.gradle.daemon.registry.base` under the workspace, each Attempt has its own daemon registry, so its builds only find and start daemons of their own. Builds without that setting, like the operator's in the main checkout, keep using `~/.gradle/daemon/` and never connect to an Attempt's daemon, so the end of an Attempt cannot kill them. `GRADLE_USER_HOME` is not changed: the dependency caches, the build cache and the wrapper downloads stay shared in `~/.gradle`. The registry lives in `.scratch/`, gitignored, and goes away with the workspace.

Notes:

- Each Attempt pays one daemon start on its first build, then reuses its daemon for the rest of the Attempt.
- `agent.env` replaces a `GRADLE_OPTS` the host may set. Put JVM settings for the daemon in `gradle.properties` (`org.gradle.jvmargs`) instead.
- To see an Attempt's daemons, run `./gradlew --status` in its workspace with the same `GRADLE_OPTS`. Without it, `--status` lists the shared pool.
- `--no-daemon` in the prompt was not chosen: it relies on the agent remembering it and costs a cold JVM on every build.

### Agent commands and the full build

The full `./gradlew build` takes about 15 minutes, and one agent command may run at most 10 minutes, in the foreground or the background. Before bl-95 the prompt asked every agent to run the full build before handing off: agents started it in the background, ended their turn to wait, and went silent until the 15 minute `codex.stall_timeout_ms` stopped them. On the night of 2026-11-15 that was 11 stalled attempts, $35.73 and 397 minutes.

Since bl-95 the prompt keeps every agent command under 10 minutes: focused tests (`./gradlew :<module>:test --tests '<class or pattern>'`, never all of `:cli:test`) and `./gradlew ktlintCheck`, and never the full build. The full build stays where it already ran, the `command` check of the `code` kind on the host, with no time limit; a failure there refutes the result and comes back to the next attempt as verify findings. A criterion that says the full build passes is marked PASS with the note "verified by Loop". `codex.stall_timeout_ms` is 20 minutes, above the longest silent tool call.

### Loop mode on Loop's own backlog

Since m3-01, Loop's `WORKFLOW.md` has a `kinds:` key with one `code` kind, so Loop's own issues run in Loop mode. Before it, an issue reached `review` on the agent's word. Now the agent's move to `review` is intercepted and verified with checks the agent did not write, and only a `verified` result is delivered.

What the `code` kind does:

- **Readiness.** `ready: { require_sections: [Acceptance criteria] }`. An issue with no `## Acceptance criteria` section, or an empty one, is not dispatched: Loop comments once and adds `needs-info`.
- **Checks.** `diff_policy` (the guards below), `./gradlew --no-daemon build` and `acceptance_commands`, the runnable criteria of the issue (see [`agents/issue-tracker.md`](agents/issue-tracker.md#runnable-criteria)). `--no-daemon` keeps host side checks off every agent's Gradle daemon (bl-01).
- **Delivery.** Conditional auto merge (m2-28): Loop pushes the verified commit to `loop/<identifier>`, opens a pull request on `soulesidibe/Loop` with the evidence summary, merges the current `main` into the branch and reruns the checks on the result (the merge check), waits for the GitHub Actions runs of the merged head (see [the CI wait](#the-ci-wait)), then asks `claude-sonnet-5-5` for a merge risk review. It merges and moves the issue to `done` only when every hard condition holds and the band is `low`. Otherwise it comments why and the issue waits as `Waiting(approval)`: run `loop approve <identifier>` to merge after a fresh merge check, or move the issue back to `todo` with a comment to send it back.
- **Slots.** `agent.max_concurrent_agents` caps the agent Attempts and the merge checks, not the merge risk reviewer. Since bl-173 the reviewer's model session runs in the merge phase, which holds no agent slot, so it can run while Attempts hold every agent slot. Only a machine slot, when `machine.max_concurrent_agents` is set in `<LOOP_HOME>/config.yaml`, and the delivery queue, which merges one pull request at a time, bound it.

`loop trace <identifier>` shows the path: the verdict, `pull_request_opened`, `merge_risk_assessed`, then `pull_request_merged` or `auto_merge_fallback` with the failed conditions.

#### The token

Delivery pushes and merges with a fine grained GitHub token held in `LOOP_PR_TOKEN`, scoped to `soulesidibe/Loop` only:

- Contents: read and write (the push and the merge).
- Pull requests: read and write.
- Actions: read (the CI wait and the `ci` condition, since m3-15).

A fine grained token has no Checks permission: GitHub grants `checks: read` only to GitHub Apps and classic tokens. Before m3-15 the `ci` condition read `GET /commits/<sha>/check-runs`, which answered 403 with this token, so every auto merge fell back to `loop approve`. Since m3-15 it reads the Actions runs of the merged head, which Actions read allows. A classic token with the `repo` scope is not used: it can write to every repository of the account.

Export it from a shell file outside every repository, such as `~/.zshenv`, next to `LOOP_PLAYGROUND_GITHUB_TOKEN`, and never write the value in a file of any repository: `WORKFLOW.md` names it as `token: $LOOP_PR_TOKEN`. A name referenced this way is removed from the agent's environment, so no Attempt sees it. An unset or blank variable stops startup, naming it. Since bl-70 this holds for the commands that write, `loop run`, `loop approve`, `loop resume` and `loop stop`, while the read commands, `loop status`, `loop trace`, `loop log` and `loop show`, start without it. A token can then be set only in the process that starts `loop run`, such as a wrapper that reads it from the Keychain, and stay out of every other shell.

#### The CI wait

Since m3-15 the `code` kind merges with `ci: actions`: after a passing merge check, Loop waits for the GitHub Actions runs of the merged head and merges only when they are green. `.github/workflows/build.yml` runs on every push, and on pull requests from forks, and takes 12 to 16 minutes, so the default `ci_timeout_ms` of 45 minutes fits. It has no `paths-ignore`, so every merged head gets a run and the default `ci_no_run: fail` stays. A red run comments the failed runs on the issue and leaves the pull request open; a timeout or a refused listing is `delivery_failed`. The auto merge `ci` condition reads the same Actions runs.

#### When auto merge falls back

- **CI.** What green means for the auto merge `ci` condition follows the kind's `ci` (m4-05). Under `ci: actions`, Loop's own setting, the pull request is `mergeable` and `clean` and the Actions runs of the merged head are green, as the CI wait read them; a red, pending or missing run, or a listing the token cannot read (it needs "Actions: read"), falls back. Under `ci: none`, green means the merge check passed: no Actions run and no workflow file is read, and `unstable` passes, so a result merges on its own while Actions is down, as it was for billing from 2026-11-16 (bl-146). `dirty`, `blocked`, `behind` and an unknown mergeability fall back under both.
- **Size.** `auto_merge.max_diff_lines` is 1000, added plus deleted. A larger diff always falls back, and a diff over 500 lines floors the merge risk `impact` at `medium`. m3-15 itself fell back here, at 2512 lines, and merged after `loop approve`.
- **Risk.** A merge risk band above `low`, or any dimension at its worst verdict.

The default `auto_merge.protected_paths` also apply: a diff that touches `WORKFLOW.md`, `scripts/`, `.github/`, the Gradle files, `.loop/`, `docs/adr/`, `CONTEXT.md`, `AGENTS.md`, `CLAUDE.md` or `.claude/` never merges without approval.

#### The protect list

`guards.protect` refutes any result that edits these paths:

- `scripts/`, the worktree hooks included: the checks run them.
- `.github/`: the CI and release workflows hold write permissions (publishing a release, attesting it) that `./gradlew build` cannot verify, and auto merge is on for the `code` kind, so no agent changes them (bl-159).
- `WORKFLOW.md`, the build scripts (`*.gradle.kts`, `*/*.gradle.kts`, `plugins/*/*.gradle.kts`), `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew`, `gradlew.bat`: the checks and how the build runs them. A `build.gradle.kts` can exclude tests or turn a check off. `build-logic/` is protected through its sources only: `diff_policy` counts ignored files too, and every build writes ignored output under `build-logic/build/` and `build-logic/.gradle/`, so protecting the folder whole refuted every result (m3-15's first attempt, 2026-11-15). For the same reason the build scripts are listed by depth instead of `**/*.gradle.kts`: Gradle's kotlin-dsl writes ignored copies of the convention plugins under `build-logic/build/kotlin-dsl/`, the build check recreates them after `diff_policy` runs, and the merge check's `diff_policy` then refuted them (m3-15, attempts 2 and 3). A module nested deeper than `plugins/*/` needs its own glob; `OwnWorkflowTest` pins the list, and bl-88 tracks fixing this in `diff_policy` itself.
- `.loop/specs/`, `docs/adr/`, `CONTEXT.md`: the decisions the work is judged against.

`guards.deny` adds `deleted_tests`, `added_skips` and `weaker_assertions`.

`.loop/` is protected through `.loop/specs/` only. The worktree hook keeps `.loop/issues/` out of every workspace under skip-worktree, and a protected path missing from the working tree that way makes `diff_policy` unable to run (see [deny guards](spec-decisions.md), what git would hide), so protecting `.loop/` whole would make every verdict `unverified`. `docs/spec-decisions.md` stays open to agents: that is where they record how they built something.

#### Human steps

An issue that needs an edit to a protected path names it as a human step in its own text, with one of two timings:

- **Before dispatch, for an edit the build needs:** a dependency or plugin in a build script, a module in `settings.gradle.kts`, a version in `gradle/`. Without it the agent's own work fails the build and can never be `verified`. The maintainer makes the edit on `main` before setting the issue to `ready-for-agent`, and triage names it while writing the brief. Example: bl-79 needed `testImplementation(project(":store"))` in `spec-core/build.gradle.kts`.
- **After the merge, for an edit the build does not need:** a `WORKFLOW.md` template change, a `CONTEXT.md` term, an ADR, or deleting a test whose code became dead. `deleted_tests` refutes any test name that disappears, with no exception, so the agent keeps such a test and the code it covers, and the maintainer deletes both. The agent does the rest and lists the edit, with its exact proposed text, under `residual_human_steps` in its submitted result. The maintainer makes it on `main` after the merge, with any golden copy a test compares with, such as `spec-core/src/test/resources/prompt/own-template.md`.

## GitHub playground

[`Soules-Studio-Ltd/loop-playground`](https://github.com/Soules-Studio-Ltd/loop-playground) is a private test repository where Loop opens issues, labels, branches and pull requests without touching a real project (story 87, m2-01). It was set up on 2026-11-13.

- **Project**: a tiny Kotlin calculator with one test suite, `CalcTest`. Run it with `./gradlew test`. The test `divides by zero` is committed disabled, so `main` passes.
- **No CI.** The playground has no workflow. Checks run locally in the workspace.
- **Labels**: `ready-for-agent`, `ready-for-human`, `needs-triage`, `needs-info`, `needs-review` and `wontfix`, next to GitHub's default labels.
- **Seeded issues**, all `ready-for-agent`:
  - #1, add a modulo operation: a plain change.
  - #2, make the divides by zero test pass: tempts deleting the test, since the test run passes without it.
  - #3, make the calculator nicer to use: vague, with no acceptance criteria section.

### Token

Loop reaches the playground with a fine grained token held in the environment variable `LOOP_PLAYGROUND_GITHUB_TOKEN`.

- Resource owner `Soules-Studio-Ltd`, repository access limited to `loop-playground`.
- Permissions: issues, contents and pull requests read and write, checks read.
- Export it from a shell file outside every repository, such as `~/.zshenv`. Never write the value in a file of any repository, `WORKFLOW.md` included: reference it as `token: $LOOP_PLAYGROUND_GITHUB_TOKEN`.
- The name is not `GITHUB_TOKEN` on purpose: the `gh` CLI reads `GITHUB_TOKEN`, so every shell would switch to the narrow token.
- The `github` target of the real integration profile (m3-25) reads it too. Its issues write permission is enough for the labels that test creates and deletes. See `docs/spec-decisions.md`, real integration profile.
- A variable referenced as the tracker token is removed from the agent's environment (`secret_environment_names`, see [`PLAN.md`](PLAN.md)), so no Attempt sees it.

### A WORKFLOW.md for the playground

Since bl-23, `tracker.kind: github` selects the [`github` tracker](plugins/tracker-github.md). This `WORKFLOW.md` reads the playground's issues and hands each one to the command runner. Put it in a folder of its own outside every repository, such as `~/loop-playground/WORKFLOW.md`. Its agent is a stub that only prints the issue it got:

```yaml
---
tracker:
  kind: github
  provider:
    repo: Soules-Studio-Ltd/loop-playground
    token: $LOOP_PLAYGROUND_GITHUB_TOKEN
  required_labels: [ready-for-agent]
workspace:
  root: ~/loop-playground/workspaces
agent:
  max_concurrent_agents: 1
  max_turns: 1
runner:
  kind: command
runners:
  command:
    command: 'echo "playground attempt on $LOOP_ISSUE_IDENTIFIER"'
---
Work on {{ issue.identifier }}: {{ issue.title }}
```

Then run one tick:

```sh
loop run ~/loop-playground/WORKFLOW.md --once
```

- The `poll` line of the operator log shows `candidates=3`, the three seeded issues, and one Attempt starts on the oldest, `loop-playground-1`. The issue stays `open`: the stub agent changes nothing.
- `active_states` and `terminal_states` are left out, so the profile defaults `open` and `closed` apply.
- The writes come with m2-23. Until then an agent's `loop tool move` and `loop tool comment` are queued, then fail host side as `tool_request_failed`, so keep the stub agent here and leave real runners for later. The command runs in the workspace, so a script would need an absolute path.
- A missing or empty `LOOP_PLAYGROUND_GITHUB_TOKEN` stops startup with `missing_tracker_secret`, naming the variable.
