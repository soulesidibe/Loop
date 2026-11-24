---
id: bl-135
title: "Low findings of the m3-20 merge risk review, Codex Loop mode guards"
state: done
priority: 3
labels: [ready-for-agent]
blocked_by: [bl-128]
---

## Parent

None. Backlog, not scheduled in a milestone. Found by the merge risk review of pull request #104 (m3-20), Sonnet and a Fable 5.2 recheck, run 9e6be449 on 2026-11-16. Sent back for the medium findings in the m3-20 comment; the holdout read gap is bl-134. These are the rest.

## What to look at

- An agent written workspace `.codex/rules` file, or operator exec policy rules, can allow a denied command (`git push`) with no approval request in later sessions, and the trust entry persists in `CODEX_HOME` (`docs/spec-decisions.md` around `:2247` and `:2250`). Ignore workspace rules, or check them before each session.
- `Guards.kt:57` misses `/usr/bin/git push`, `git -c x=y push`, `g\h` and `git $(echo push)`, the same gaps as the claude-code runner, and over matches commit messages that name `gh`.
- The deny list in `Guards.kt:27` to `:29` is copied by hand from `ClaudeSettingsFile`, with no parity test. The lists match today.
- No real Codex turn has checked that the `untrusted` policy actually asks for approval. Deferred to m3-24.

## Second review, head 99f9bad

- `CommandWords.kt:18` and `:63` accept `FOO=bar codex app-server`, which then runs `FOO=bar` as the program and fails only at launch with `agent_not_found`, exit 127. Refuse an assignment word when the config is read.
- Without the login shell, the launch no longer gets PATH entries a profile adds (nvm, Homebrew), so a `codex` found only through the profile is no longer found. Intended, but `docs/plugins/runner-codex-app-server.md` does not say it.

## Third review, head c526d1a (merged as pull request #104, band low, Sonnet 1.40, Fable 5.2 1.27)

Every `thread/start` now carries `config.allow_login_shell=false`, a real key of codex 0.167.0. The rest:

- Only the agent's manual run with a fake provider shows codex honours the setting (`docs/spec-decisions.md:2245`), and no trace event records it. m3-24 should confirm it on a real turn.
- `~/.zshenv` exports still reach the agent's commands, since zsh reads it for every shell. Documented at `docs/spec-decisions.md:2245`.
- Whether codex subagent threads inherit the override is unchecked.
- A test name still says "carries no config" (`CodexServerRequestsTest.kt:438`).

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] A workspace `.codex/rules` file or an operator exec policy rule cannot allow a denied command such as `git push`. Either they are ignored or they are checked before each session, with a test.
- [ ] `Guards.kt` denies `/usr/bin/git push` and `git -c x=y push`, and does not match a commit message that names `gh`. A parity test compares its deny list with `ClaudeSettingsFile`.
- [ ] `FOO=bar codex app-server` is refused when the config is read (`CommandWords.kt`), with a test.
- [ ] Each other finding is handled, or closed here with a one line reason. Findings that need a real Codex turn may point to the exit run.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29. Blocked by bl-128, the last of the chain bl-141, bl-128, bl-135 after m4-08. The m3-20 blocker is done and removed.

2026-11-24 04:03Z agent f886fcc7: bl-135 done. Exec policy rules: codex 0.167.0 can skip rules files only in `codex exec` (`--ignore-rules`), not the app server, so the runner now reads every `*.rules` file of `$CODEX_HOME/rules`, `/etc/codex/rules` and the `.codex/rules` folders from the workspace up to its project root before each session. A rule that allows a denied command, or one Loop cannot read as literals, fails the first turn with `exec_policy_rule: <file> ...`, and nothing starts. Guards: a program run by its path, git's own options (`-c x=y`, `-C .`), backslashes and a substitution as the git subcommand are denied, and a `git commit -m` message is ignored when matching. DeniedCommandsTest compares the list with ClaudeSettingsFile by reflection. `FOO=bar codex app-server` fails preflight. The PATH note is in the runner doc. The other findings are closed in docs/spec-decisions.md (bl-135 section). The checks that need a real turn go to the next Codex exit run.

2026-11-24 04:22Z loop: Loop opened pull request #36 for this issue: https://github.com/soulesidibe/Loop/pull/36

2026-11-24 04:36Z loop: Loop did not merge pull request #36 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.53.
  - alignment: aligned, The diff covers each acceptance criterion (rules check, guard matching, parity test, env assignment refusal) and closes the other findings with reasons in the docs.
  - impact: medium (floor medium: the diff changes 908 lines, over half the limit of 1000), 908 changed lines, a new Starlark-subset parser, and a hard session refusal that can block every Codex attempt when an operator rule or an unreadable rules file exists.
  - verification: partial, Unit tests cover the rule forms, guard matching and parity, but no real Codex turn checks rule loading, the parser is a hand-rolled approximation of Starlark, and the parity test reads source text.
  - rollback_cost: two_way, The change is code, tests and docs only, and it adds no persisted state or data format, so a revert undoes it.
  - error_detection: loud, A rule that allows a denied command, or one that cannot be read, fails the turn with an explicit exec_policy_rule reason, and an env assignment fails at preflight.
  Once approved, Loop runs the merge check again and merges.
