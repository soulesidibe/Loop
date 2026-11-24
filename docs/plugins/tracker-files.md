# `files` tracker adapter profile

The `files` tracker keeps each Issue as one Markdown file in a folder of the repository. This page is the adapter profile that section 11.2 of the spec asks every adapter to publish. The implementation is `FilesTracker` in `plugins/tracker-files` (`loop.tracker.files`).

Written 2026-11-06 in issue m1-02. Terms follow [`CONTEXT.md`](../../CONTEXT.md); spec section numbers refer to [`symphony/SPEC.md`](../symphony/SPEC.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Scope](#scope)
4. [Issue file format](#issue-file-format)
5. [Normalization](#normalization)
6. [States](#states)
7. [Sort order](#sort-order)
8. [Malformed records](#malformed-records)
9. [Writes and the agent tool](#writes-and-the-agent-tool), including [comments](#comments) and [labels](#labels)
10. [Errors](#errors)
11. [Known gaps](#known-gaps)

## Configuration

| Key | Value | Default | Notes |
|---|---|---|---|
| `tracker.kind` | `files` | required | `tracker.kind` missing or blank is a startup error (`tracker.kind is required`). |
| `tracker.provider.dir` | path to the issues folder, or `$VAR` | `.loop/issues` | A relative path resolves against the folder that holds `WORKFLOW.md`, not the current directory. A whole value `$NAME` is read from the environment first, see below. |
| `tracker.active_states` | list of states | `todo`, `in_progress` | Profile default, see [states](#states). |
| `tracker.terminal_states` | list of states | `done`, `canceled` | Profile default, see [states](#states). |
| `tracker.required_labels` | list of labels | empty | Compared case insensitively. |

The adapter has no secrets and no network access. Its only environment variable is an optional `$VAR` in `tracker.provider.dir`:

- The value must be exactly `$NAME` (a letter or `_`, then letters, digits or `_`), such as `dir: $ISSUES_DIR`. A `$` inside a longer string, such as `$HOME/issues`, is not expanded and stays a literal path.
- Loop reads the variable before it resolves the path, so a relative value still resolves against the folder that holds `WORKFLOW.md`.
- An unset or empty variable fails preflight with the error `missing_env_var`, and the message names the variable, never a value: `tracker.provider.dir uses $ISSUES_DIR, which is not set` (or `which is empty`). Dispatch is blocked until it is fixed.

The full `$VAR` rule is in [`spec-decisions.md`](../spec-decisions.md#var-resolution-scope-restriction-of-531-and-61-m1-03).

A folder that does not exist or cannot be read is not a startup error; it fails each tracker call with `Unavailable` (see [errors](#errors)).

Minimal example:

```yaml
tracker:
  kind: files
  provider: { dir: .loop/issues }
  active_states: [todo]
  required_labels: [ready-for-agent]
```

## Profile

Since m3-05 the tracker is a plugin, `FilesTrackerProvider`, registered in `META-INF/services/loop.api.TrackerProvider` and selected by `tracker.kind: files` (see [plugin profiles](../spec-decisions.md#plugin-profiles-extension-m3-05)). Its profile declares the keys of `tracker.provider`:

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `tracker.provider.dir` | string | `.loop/issues` | no | none |

Loop checks `tracker.provider` against it on every load of `WORKFLOW.md`, at startup, in the preflight of every tick and on every reload. A `dir` that is not a string fails with `invalid_tracker_config` and `tracker.provider.dir must be a string`. A key the profile does not list is a startup warning, `tracker.provider.<key> is not a key of the files tracker, Loop ignores it`, never a failure, for forward compatibility. Its profile defaults for the states are `todo`, `in_progress` and `done`, `canceled`, as above.

## Scope

- Every regular file directly in `tracker.provider.dir` whose name ends in `.md` and does not start with `.` is an Issue file. Subfolders are not read, so specs kept in `.loop/specs/` are never dispatched.
- There is no pagination and no request limit: every call lists and parses the whole folder, in file name order.
- A fetch with an empty list of states or ids returns no Issue without reading the folder (17.3), so it succeeds even when the folder is missing.
- The file name is a convention (`<id>-<slug>.md`, see [`agents/issue-tracker.md`](../agents/issue-tracker.md)). The adapter finds an Issue by its front matter `id`, not by its file name, except to decide whether a malformed file belongs to a requested id (see [malformed records](#malformed-records)).

## Issue file format

```markdown
---
id: m1-02
title: "Walking skeleton: one issue from todo to review, traced"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m1-01]
---

## What

The body, kept as the description.
```

The first line must be `---`, and the front matter ends at the next line that is exactly `---`. Lines may end with `\n` or `\r\n`.

## Normalization

| Field | Source | Rule |
|---|---|---|
| `id` | front matter `id` | Trimmed, required, non empty. |
| `identifier` | front matter `id` | Same value as `id`. The `files` tracker has no separate human key. |
| `native_ref` | none | Not exposed. The native record is the file itself. |
| `title` | front matter `title` | Trimmed, required, non empty. |
| `state` | front matter `state` | Trimmed, required, non empty. Spelling is kept as written; comparisons with configured states are case insensitive. |
| `priority` | front matter `priority` | An integer is passed through as is. Missing or non integer values normalize to null. No remapping, so values outside 1 to 4 sort with null ([answer 3](../spec-decisions.md#answers-to-the-five-ambiguities)). |
| `labels` | front matter `labels` | Lowercased. Absent or null gives an empty list. A value that is not a list of scalars makes the record malformed. |
| `blocked_by` | front matter `blocked_by` | A list of Issue ids. Absent or null gives an empty list. A value that is not a list of scalars makes the record malformed. |
| `area` | front matter `area` | A list of repository relative paths, or one path, normalized (m4-17, see [area lock](../spec-decisions.md#minimal-area-lock-extension-m4-17)). Absent, null or an empty list gives an empty list. A value that is not a list of scalars or a scalar, or an entry that is no repository relative path (blank, absolute, a `..` segment, a glob character, a backslash, a backtick, a control or format character, whitespace inside a segment, or the root `.`), or more than 64 entries, makes the record malformed. |
| `description` | the body after the front matter | Trimmed; an empty body is null. |
| `created_at` | none | Always null, see below. |
| `updated_at` | none | Not provided. |
| `dispatchable` | none yet | Derived from `blocked_by` in m1-09 ([extension](../spec-decisions.md#blocked_by-enforcement-in-the-files-tracker-extension-m1-09)). |

**Why `created_at` is null:** the only timestamp a file has is its modification time, and `git checkout`, `git clone` and branch switches reset it. Using it would reorder Issues every time the repository is checked out, so the adapter reports no creation time rather than a wrong one. Front matter has no `created_at` key either.

## States

The adapter defines exactly six states, in this spelling. `loop tool move` refuses any other value.

| State | Role | Effect |
|---|---|---|
| `todo` | active | Eligible for dispatch when the other rules pass. |
| `in_progress` | active | Eligible for dispatch; an agent moves its Issue here if it wants the tracker to show the work. |
| `review` | handoff state | Neither active nor terminal: the worker stops and the workspace is kept (8.5). |
| `needs_info` | handoff state | Same as `review`, for an Issue that needs an answer from a human. Moving back to `todo` resumes in the same workspace. |
| `done` | terminal | Never dispatched; the workspace is cleaned up. |
| `canceled` | terminal | Never dispatched; the workspace is cleaned up. |

Default `active_states` are `todo` and `in_progress`; default `terminal_states` are `done` and `canceled`. These defaults are what 5.3.1 requires a profile to document ([extension](../spec-decisions.md#files-adapter-profile-defaults-for-active-and-terminal-states-extension-of-531)). A `WORKFLOW.md` can narrow them, as the walking skeleton test does with `active_states: [todo]`.

An Issue is eligible when its state is in `active_states` and not in `terminal_states`, it carries every `required_labels` entry (case insensitive), and it is neither claimed nor running (8.2).

## Sort order

The adapter returns Issues in file name order. The orchestrator then sorts candidates by:

1. priority, 1 to 4 first, then null and every other value;
2. `created_at`, oldest first, null last;
3. `identifier`.

Since `created_at` is always null for this tracker, `files` Issues are in effect ordered by priority, then by `id`.

## Malformed records

A file is malformed when any of these holds:

- the first line is not `---`, or the front matter has no closing `---`;
- the front matter is empty, is not valid YAML, or is not a map;
- `id`, `title` or `state` is missing or empty;
- `labels` or `blocked_by` is present but not a list of scalars.
- `area` is present but neither a list of scalars nor a scalar, or holds an entry that is no repository relative path.

The two read calls treat a malformed file differently (11.1, [clarification](../spec-decisions.md#files-tracker-refresh-fails-on-malformed-records-clarification-of-111)):

- **Fetch by states** skips the file and reports it, so Loop logs the omission and dispatches the other Issues.
- **Fetch by ids** fails the whole call when a malformed file belongs to a requested id, because a missing Issue would read as "no longer visible" and release its claim. A malformed file belongs to id `X` when its name is `X.md` or starts with `X-`, or when a raw `id:` line in it names `X`. Malformed files that belong to no requested id are ignored.

## Writes and the agent tool

Loop does not move an Issue to `in_progress` on dispatch: the claim is held in memory ([deviation](../spec-decisions.md#no-tracker-write-on-dispatch-clarification-of-8-m1-02)). The writes are the agent tools `loop tool move <state>` and `loop tool comment <text>`, run by the agent from inside its Attempt, and in Loop mode the [readiness bounce](../spec-decisions.md#readiness-check-extension-m2-11) of an Issue that lacks a required section: one comment by `loop` and the `needs-info` [label](#labels), and the [delivery](../spec-decisions.md#delivery-policy-and-approval-extension-m2-24) of a verified result: the evidence summary comment by `loop` under the `human` policy, then the move to the handoff state once `loop approve` released it.

**Authorization boundary.** `loop tool move` and `loop tool comment` read `LOOP_WORKFLOW` and `LOOP_ATTEMPT_ID` from the environment Loop gives the agent process, build the same `files` tracker from that `WORKFLOW.md`, and write only when:

- for a move, the state is one of the six defined states; for a comment, the text is not empty;
- the machine level store has an `attempt_started` event for that Attempt, with the same Issue and Project, and no `attempt_finished` event.

On success `move` records an `issue_moved` event and prints `Moved <id> from <old> to <new>.`; `comment` records an `issue_commented` event and prints `Commented on <identifier>.` On a refusal nothing is written and nothing is recorded. Both tools hold a lock while they write, so concurrent writes are not lost. The lock file is `tracker-<hash of the issues folder path>.lock` in `<loop home>/locks`, the machine store the tool process already opens, so every process agrees on it. It is never in the issues folder, and not in the system temp folder, which an agent sandbox may redirect and other users may share.

| Refusal | Exit code |
|---|---|
| Missing argument | 2 |
| Rejected argument: a state not defined by the tracker (`move`), empty text (`comment`) | 2 |
| Not inside an Attempt (`LOOP_ATTEMPT_ID` unset) | 4 |
| Attempt finished, or Attempt of another Issue | 5 |

**How the file is written.** The adapter replaces the first `state:` line inside the front matter with `state: <new>` and keeps every other byte of the file as it was, line endings included. The file is never parsed and serialized again, so comments, key order and quoting survive. The new content goes to a temporary file in the same folder, which takes the original file's permissions and is then renamed over the original with an atomic move. A reader sees either the old file or the new one, never a partial one.

### Comments

`loop tool comment <text>` appends one entry at the end of the `## Comments` section of the Issue file in the main checkout. Use `--` before text that starts with `-`. The text is redacted before it is written. If the file has no `## Comments` section, it is created before `## Progress` when that section exists, else at the end of the file.

Entry format: the first line is the UTC time at minute precision, `agent`, the 8 character short Attempt id and the text; continuation lines are indented by 2 spaces and blank lines inside the text are dropped. Entries are separated by one blank line.

```markdown
## Comments

2026-11-08 16:00Z agent 7f3a1c2e: Should the export include archived items?
  The spec does not say.

2026-11-08 soule: No, active items only.
```

A human answers by hand, as `<date> <name>: <text>`; the time is optional. Nothing parses entries: the comments reach the next Attempt as raw text in the Issue description. `needs_info` and `review` are handoff states, so there is no dispatch and the workspace is kept. Moving the Issue back to `todo` resumes work in the same workspace, and the next Attempt sees the answer through the description.

### Labels

`updateLabels` (m2-11), the optional label write of `TrackerAdapter`, adds and removes labels on the front matter `labels` field. No agent tool calls it yet; Loop uses it for the readiness bounce.

- Labels are compared trimmed and case insensitively. A label already there is not added twice, removing a label that is not there is not an error, and blank labels are ignored. Existing labels keep their spelling; new ones are written as given, trimmed.
- The adapter replaces the front matter `labels` entry, a flow list such as `[ready-for-agent]` or a block list of `-` lines, with one flow list line, `labels: [ready-for-agent, needs-info]`. A label is written plain when YAML reads it back as the same text, and double quoted otherwise (`"p1: urgent"`). With no `labels` key, the line is added before the closing `---`.
- The entry is found by parsing the front matter, so `"labels":` and `labels :` are the same key and never get a second line. It runs from its key line to the line before the next key, without the blank lines and comment lines at its end, so a blank line or a column 0 `#` comment between the items is part of it.
- A comment on the entry is replaced with it: a trailing comment on the key line and a comment between the items are gone after the write. A comment after the last item stays. Keep a note that must survive on its own line before the `labels` key.
- Read back rule: before writing, the adapter parses the new text. It must give the intended labels in order, every other front matter field unchanged and the same body. When it does not, as for a front matter written as one flow map `{...}`, the write fails with `Malformed` and the file is byte for byte what it was. Nothing is written first and repaired after.
- Every other byte is kept, line endings included, and the file is written through a temporary file and an atomic rename under the same lock as moves and comments. When the label set does not change, nothing is written.

## Errors

Every call returns a result whose failure is a `TrackerError`. The adapter does not throw for expected failures.

| `TrackerError` | When | Spec category (11.4) | Message |
|---|---|---|---|
| `Unavailable` | The folder or a file cannot be listed or read (an I/O error) | `tracker_request` | The I/O error's message |
| `Malformed` | Fetch by ids hits a malformed file that belongs to a requested id; a move finds no `state:` line; a comment finds no front matter, or the body has an unclosed fence; a label write whose new text fails the [read back rule](#labels) | `tracker_response` | `Malformed issue at <path>` |
| `NotFound` | A move, a comment or a label write names an id no well formed file has, including the id of a file that does not parse | `tracker_response` | `No issue "<id>" in the tracker` |
| `BlankComment` | A comment whose text is blank once trimmed | None: a tool argument error, reported by `loop tool comment` with exit code 2 | The comment is empty |
| `UndefinedState` | A move names a state outside the six | None: a tool argument error, reported by `loop tool move` with exit code 2 | `"<state>" is not a state of the tracker. Valid states: todo, in_progress, review, needs_info, done, canceled` |

`missing_tracker_secret`, `tracker_status`, `tracker_pagination` and `tracker_rate_limited` never occur: the adapter has no secrets, no remote responses, no pages and no rate limit. Configuration errors (`tracker.kind` missing, an unset or empty `$VAR` in `dir`) are preflight errors of `WORKFLOW.md`, not adapter errors: they fail startup and block dispatch on a tick.

The orchestrator follows 11.4 on failure: a failed candidate fetch skips dispatch for that tick, a failed refresh keeps running workers running.

## Known gaps

Recorded so later issues close them; each is a difference from 4.1.1 or 11.3.

- Duplicate labels are not removed. Labels are trimmed and lowercased, and blank ones dropped, as 11.3 requires.
- `native_ref` is not on the normalized Issue. The `files` tracker has no native id apart from `id`, so none is needed (17.3).
- `blocked_by` entries are not trimmed.
