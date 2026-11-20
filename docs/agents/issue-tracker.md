# Issue tracker: Loop's files tracker

Issues for this repo live in Loop's own `files` tracker, which Loop reads to dispatch agents.

## Conventions

- Pull request numbers up to #119 named in issues and specs (for example "pull request #115") belong to the old private repository, which this one replaced in November 2026. Do not look them up here.
- One file per issue: `.loop/issues/<id>-<slug>.md`, in the main checkout, versioned in git.
- Front matter: `id`, `title`, `state`, `priority`, `labels`, `blocked_by` (a list of issue ids).
- Body sections: `## Parent` (link to the spec), `## What`, `## Acceptance criteria`, `## Comments`.
- States: `todo`, `in_progress` (active); `review`, `needs_info` (handoff states); `done`, `canceled` (terminal).
- Acceptance criteria are checkable, each with the command that checks it where one exists. Judgment calls are marked as prose. See [runnable criteria](#runnable-criteria).
- Comments are appended under `## Comments`. Agents only write through `loop tool comment` and `loop tool move`, never by editing the file.
- Agent comment: `2026-11-08 16:00Z agent 7f3a1c2e: <text>` (UTC, short Attempt id), continuation lines indented by 2 spaces, entries separated by one blank line. Human answer, written by hand: `2026-11-08 soule: <text>`, time optional. Details in [`plugins/tracker-files.md`](../plugins/tracker-files.md#comments).
- Specs live in `.loop/specs/<slug>.md`, never in `.loop/issues/`, so Loop never dispatches them.

## Runnable criteria

Loop runs the command of a criterion as a check when the kind declares `{ type: acceptance_commands }` (m2-09), with the `files` tracker only. A criterion is runnable when its line opens with one backticked command followed by the word `passes`:

```markdown
## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] `./gradlew :cli:test --tests "loop.cli.TraceCommandTest"` passes, with the new hook line test.
- [ ] `scripts/check-docs.sh` passes.
- [ ] `TraceCommandTest` passes.
- [ ] The convention is stated in `docs/agents/issue-tracker.md`.
```

- The first three lines are runnable: Loop runs each command in the workspace as a `command` check. Text after `passes` is free, other backticked words in it included.
- The fourth and fifth lines are judgment calls: Loop lists them in the evidence for a human and never counts them as passed.
- The span must look like a command: it holds a space, or starts with `./`, `../`, `/` or a path segment such as `scripts/`. A bare name such as `` `TraceCommandTest` `` or `` `make` `` is a judgment call, so write `` `make test` `` or `` `./gradlew test` ``.
- `passes` is a whole word, written as is: `pass`, `passed` or `passes-through` is no command. When the kind declares `acceptance_commands` and the criteria hold no command, Loop records `acceptance_commands_empty` with the judgment call count, so such a typo shows in `loop trace`.
- A criterion is one column 0 list item (`-`, `*`, `+`, `1.` or `1)`, with or without `[ ]` or `[x]`) of the first section whose heading opens with `Acceptance criteria` (`## Acceptance criteria:` and `## Acceptance criteria (m2-09)` count too), with its indented continuation lines. A nested item is part of its parent and never holds a command of its own. Prose, rules such as `---` or `- - -`, column 0 fenced blocks and headings in the section are not criteria, and a later section of the same name, in a comment for example, is ignored.
- Only the opening span counts. A backticked path, name or config value anywhere else, or a command not followed by `passes`, is prose: `` `docs/x.md` is updated `` and `` the build passes: `./gradlew build` `` are judgment calls.
- Write the command as you would type it in the workspace, between single backticks, with no backtick inside: a span between double backticks is a judgment call. It runs with `sh -lc`, and one that is not found or times out parks the Issue as `unverified`, so write only commands that run in every workspace.
- One command per criterion. Split a criterion that needs two commands, or join them with `&&` inside the span.
- Loop freezes the command list of an Issue at its first dispatch. When the criteria later hold other commands, the next verification runs none of them and parks the Issue as `unverified` with the reason `acceptance commands changed`. A human checks the change, then runs `loop resume <id>`, which prints the new commands and accepts them, only while the Issue still holds the list Loop found. A resume of an Issue parked for another reason accepts nothing.

## When a skill says "publish to the issue tracker"

- A spec: create `.loop/specs/<slug>.md`.
- An issue: create `.loop/issues/<id>-<slug>.md` with `state: todo`, the next free id, and its triage label. Publish blockers first so `blocked_by` uses real ids.
- An issue not scheduled in any milestone: use the `bl-<nn>` id prefix (backlog) and write "None. Backlog, not scheduled in a milestone." under `## Parent`. Milestone issues use `m<n>-<nn>`.

## When a skill says "fetch the relevant ticket"

Read the file in `.loop/issues/` (or `.loop/specs/`) with the given id or path.
