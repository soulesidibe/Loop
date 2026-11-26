---
tracker:
  kind: files
  provider:
    dir: .loop/issues
  active_states: [todo]
  terminal_states: [done, canceled]
  required_labels: [ready-for-agent]
polling:
  interval_ms: 30000
workspace:
  root: ./workspaces
  # bl-28: each workspace is a clone of the project, so an agent cannot move the operator's refs. See docs/adr/0002.
  git: clone
hooks:
  after_create: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-after-create.sh"
  before_run: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-before-run.sh"
  before_remove: sh "$(dirname "$LOOP_WORKFLOW")/scripts/hooks/worktree-before-remove.sh"
  # m4-30: a macOS notification once per notice. Only the identifier, the event and the command are used, always quoted.
  on_human_needed: 'terminal-notifier -title Loop -message "$LOOP_NOTICE_IDENTIFIER needs you, $LOOP_NOTICE_EVENT. Next: $LOOP_NOTICE_COMMAND"'
  timeout_ms: 60000
agent:
  max_concurrent_agents: 2
  max_turns: 10
  env:
    # bl-01: each attempt gets its own Gradle daemon pool, see docs/self-hosting.md.
    GRADLE_OPTS: "-Dorg.gradle.daemon.registry.base={workspace}/.scratch/gradle-daemons"
retries:
  max_attempts: 5
  max_continuations: 3
runner:
  kind: claude-code
runners:
  claude-code:
    command: claude
    turn_timeout_ms: 3600000
    read_timeout_ms: 120000
codex:
  # bl-95: above the longest silent wait of an agent, so a tool call near its 10 minute limit never trips it.
  stall_timeout_ms: 1200000
context:
  # m3-27: below the 25 percent rule, so the request that crosses the hard limit still lands under it.
  # The largest jump between two requests in past runs was about 27k tokens (bl-144).
  hard_limit: 0.21
# m3-01: Loop mode. A result reaches `done` only through a verified verdict and a merged pull request,
# see docs/self-hosting.md.
kinds:
  code:
    ready: { require_sections: [Acceptance criteria] }
    verify:
      checks:
        - { type: diff_policy }
        # --no-daemon: a host side check never shares a Gradle daemon with an agent (bl-01).
        - { type: command, run: "./gradlew --no-daemon build" }
        - { type: acceptance_commands }
      guards:
        # The checks and everything they run or read, and the decisions the work is judged against.
        # .loop/ is protected through .loop/specs/ only: the worktree hook keeps .loop/issues/ out of every
        # workspace under skip-worktree, and a protected path missing that way makes diff_policy unverified.
        protect:
          - scripts/
          # CI and release files hold write permissions (contents, id-token, attestations) that ./gradlew build
          # cannot verify, so every change under .github/ is human lane (bl-159).
          - .github/
          - WORKFLOW.md
          # The tracked build scripts by depth, never **/*.gradle.kts: Gradle generates ignored copies under
          # build-logic/build/, and diff_policy counts ignored files. A module nested deeper needs its own glob.
          - "*.gradle.kts"
          - "*/*.gradle.kts"
          - "plugins/*/*.gradle.kts"
          - gradle/
          # Sources only: a build writes ignored output under build-logic/build/ and build-logic/.gradle/.
          - build-logic/src/
          - gradle.properties
          - gradlew
          - gradlew.bat
          - .loop/specs/
          - docs/adr/
          - CONTEXT.md
        deny: [deleted_tests, added_skips, weaker_assertions]
    delivery:
      # Conditional auto merge (m2-28): every hard condition and a low merge risk band, else `loop approve`.
      policy: auto
      auto_merge:
        enabled: true
        max_diff_lines: 1000
      risk:
        reviewer: claude-sonnet-5-5
      pull_request:
        repo: soulesidibe/Loop
        token: $LOOP_PR_TOKEN
        merge:
          method: merge
          # m3-15: wait for the GitHub Actions runs of the merged head. build.yml runs on every push (and on
          # pull requests from forks) and takes around 2 to 4 minutes, so the default 45 minute ci_timeout_ms fits;
          # it has no paths-ignore, so a merged head always gets a run and the default ci_no_run: fail stays.
          ci: actions
      on_delivered:
        move_to: done
---
You are working on issue {{ issue.identifier }} of Loop, an issue execution harness written in Kotlin. You work alone and nobody answers questions during this run. Do the work, prove it, commit it, hand it off.

# The issue

- Identifier: {{ issue.identifier }}
- Title: {{ issue.title }}
- State: {{ issue.state }}
- Priority: {{ issue.priority }}
- Labels: {{ issue.labels | join: ", " }}

{{ issue.description }}

{% if attempt or loop.workspace_reused %}
# Earlier work on this issue

An earlier attempt may have left work on this issue in this workspace or on the current branch. Read your progress file if it exists, `git log` and `git status` on the current branch first, then build on what is there. Do not start over and do not redo steps already marked as passing. If there is no progress file, create it from what the branch already holds.
{% endif %}
{% if loop.retry_kind == "rotation" %}
# This session replaces a full one

Loop rotated your session because its context grew past the limit. The work so far is in the workspace, in your progress file and in `git log` on the current branch. Read them first and continue from there. Do not recreate the progress file and do not redo steps already marked as passing. Do not read the project docs whole: search them (`grep -n`) for the sections the next step needs and read only those.
{% endif %}
{% if loop.session_brief %}
# Session brief

{{ loop.session_brief }}
{% endif %}
{% if loop.answer %}
# Your question was answered

You asked a human a question with `ask` in an earlier attempt. The question and the answer are below. Act on the answer and do not ask the same question again.

{{ loop.answer }}
{% endif %}
# Your workspace

- The current directory is a git worktree on the branch `loop/{{ issue.identifier }}`, already checked out by Loop's workspace hooks. Work and commit on this branch only.
- Before you change anything, sync with `refs/loop/base/main`, which may have moved since this branch was created. Loop fetched it before this run. Run `git merge-base --is-ancestor refs/loop/base/main HEAD`. On a fresh branch it passes and there is nothing to do. If it fails, your branch is behind `refs/loop/base/main`: run `git merge refs/loop/base/main` first. Use `refs/loop/base/main` as it is, never fetch or pull. Resolve any conflict keeping the intent of both sides, commit the merge with a message that starts with `{{ issue.identifier }}: `, then run the focused tests of the code the merge touched before anything else.
- The folder `.loop/issues/` is absent from this workspace by design. Never create it, recreate it or edit issue files anywhere. The issue text above is all you need.
- Read the project docs before you change code: `AGENTS.md`, `CONTEXT.md` (the glossary), `docs/adr/`, `docs/spec-decisions.md` and the parent spec linked in the issue (under `.loop/specs/`).

# How to work

1. Read the issue and the docs above until you know what done means.
2. Before any code, create the progress file `.scratch/{{ issue.identifier }}/progress.md`. The `.scratch/` folder is gitignored, so never commit it. Start it with an acceptance checklist: one row per acceptance criterion, the proof you will give, and a status of FAIL. A row turns to PASS only when its proof exists.
3. Write tests first for any logic. Run each new test and see it fail for the expected reason before you write the code that makes it pass.
4. No command may run longer than 10 minutes, in the foreground or the background, and never end your turn while a command still runs: Loop stops an agent that stays silent for 20 minutes. Run focused tests, `./gradlew :<module>:test --tests '<class or pattern>'`, never a whole module that takes longer (`:cli:test` does).
5. Keep evidence from real command output, never from memory or expectation. Save it under `.scratch/{{ issue.identifier }}/evidence/`, one file per command, for example `./gradlew :cli:test --tests 'loop.cli.RunCommandTest' 2>&1 | tee .scratch/{{ issue.identifier }}/evidence/cli-test.txt`. Point each checklist row at its evidence file.
6. Log your steps and decisions in the progress file as you go, so a later attempt can pick up where you stopped.
7. Technical questions are yours to decide. Pick the option that fits the docs and the existing code, record the decision and the reason in the progress file, and carry on.
8. Review your own work before handing off. Go through each acceptance criterion and check it against the evidence. Then check every text you wrote, code comments, docs, messages and commit messages, against the writing rules below. Fix what fails.
9. Never run the full `./gradlew build`: it takes about 15 minutes, longer than any single command may run, and Loop runs it itself to verify your result. Before you hand off, run the focused tests of everything you changed and `./gradlew ktlintCheck` (lint fails the full build), and save their output as evidence. They must pass. A criterion that says the full build passes is proven by Loop's verification: mark its row PASS with the note "verified by Loop".

# Writing rules

- Sentence case for headings, titles and messages.
- No em dashes and no en dashes anywhere. Use periods or commas.
- No co author line and no Claude or AI attribution in commits.

# Committing and handing off

- Commit on `loop/{{ issue.identifier }}` with messages that start with `{{ issue.identifier }}: `. Commit after each step whose focused tests pass, so the work so far survives a rotation of your session.
- Never create worktrees or branches, never switch branches, never push, never use `gh`, never rewrite history (no amend, rebase, reset or force).
- Move the issue and comment on it with Loop's MCP tools (`mcp__loop__move` / `mcp_loop_move` and `mcp__loop__comment` / `mcp_loop_comment`) when they are available, else with `loop tool move` and `loop tool comment`.
- When your focused tests and lint pass and every checklist row is PASS, commit, move the issue to `review` once (`mcp__loop__move` with state `review`, else `loop tool move review`), then end your turn.
- Loop queues every move and comment and applies it when your turn ends. Do not make the same call twice, and never move the issue to a state other than `review` or `needs_info`.

# Product questions

A product question is one where the issue and the docs leave the expected behavior open and a wrong guess would ship the wrong thing. When you hit one:

1. Comment the question, the options you see and the one you would pick (`mcp__loop__comment`, else `loop tool comment`).
2. Commit the work done so far.
3. Move the issue to `needs_info` once (`mcp__loop__move` with state `needs_info`, else `loop tool move needs_info`), then end your turn.

# Out of scope

Do not pick other issues, open pull requests or ask the user anything. Work on {{ issue.identifier }} only.
