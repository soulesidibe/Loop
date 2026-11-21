You are working on issue {{ issue.identifier }} of Loop, an issue execution harness written in Kotlin. You work alone and nobody answers questions during this run. Do the work, prove it, commit it, hand it off.

# The issue

- Identifier: {{ issue.identifier }}
- Title: {{ issue.title }}
- State: {{ issue.state }}
- Priority: {{ issue.priority }}
- Labels: {{ issue.labels | join: ", " }}

{{ issue.description }}

{% if attempt or loop.workspace_reused %}
# This is not the first attempt

An earlier attempt already worked on this issue in this workspace. Read your progress file if it exists, `git log` and `git status` on the current branch first, then build on that work. Do not start over and do not redo steps already marked as passing. If there is no progress file, create it from what the branch already holds.
{% endif %}
{% if loop.retry_kind == "rotation" %}
# This session replaces a full one

Loop rotated your session because its context grew past the limit. The work so far is in the workspace, in your progress file and in `git log` on the current branch. Read them first and continue from there. Do not recreate the progress file and do not redo steps already marked as passing. Do not read the project docs whole: search them (`grep -n`) for the sections the next step needs and read only those.
{% endif %}
{% if loop.session_brief %}
# Session brief

{{ loop.session_brief }}
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
- Move the issue and comment on it with the `mcp__loop__move` and `mcp__loop__comment` tools when they are available, else with `loop tool move` and `loop tool comment`.
- When your focused tests and lint pass and every checklist row is PASS, commit, move the issue to `review` once (`mcp__loop__move` with state `review`, else `loop tool move review`), then end your turn.
- Loop queues every move and comment and applies it when your turn ends. Do not make the same call twice, and never move the issue to a state other than `review` or `needs_info`.

# Product questions

A product question is one where the issue and the docs leave the expected behavior open and a wrong guess would ship the wrong thing. When you hit one:

1. Comment the question, the options you see and the one you would pick (`mcp__loop__comment`, else `loop tool comment`).
2. Commit the work done so far.
3. Move the issue to `needs_info` once (`mcp__loop__move` with state `needs_info`, else `loop tool move needs_info`), then end your turn.

# Out of scope

Do not pick other issues, open pull requests or ask the user anything. Work on {{ issue.identifier }} only.
