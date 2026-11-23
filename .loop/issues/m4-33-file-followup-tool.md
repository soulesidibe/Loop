---
id: m4-33
title: "file_followup for both runners, a kind may override the tracker"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-32]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 130 (PLAN 6.1 and 6.10). Lane A, runs on the claude-code runner. Comes after kind matching (m4-18) and the tracker registry (m4-11), so the kind override has something to override (finding M11 of the m3-29 review). Split after the Fable 5.2 review: the safe label write of the files tracker (bl-16) is m4-53, since a new file is written whole and never goes through `rewriteLabels`. Area: `plugin-api/.../TrackerAdapter.kt` and `plugin-api/src/testFixtures/.../TrackerAdapterContract.kt`, `plugins/tracker-files`, `plugins/tracker-github`, the Loop MCP server (`mcp/.../LoopMcpServer.kt`), the Codex client tools (`plugins/runner-codex-app-server/.../LoopClientTools.kt`), the host side tool path in `cli/`.

- **The tool:** `file_followup(title, body)` reaches both runners. The call is queued like `comment` and applied host side when the turn ends; the answer says so. It creates a follow up issue labelled `needs-triage`, whose body ends with a line naming the parent issue and the attempt. The new issue's namespaced id (m4-12) is in `followup_filed` and in a comment on the parent.
- **Which tracker:** the parent's tracker, unless the parent's kind says otherwise with `kinds.<kind>.followup.tracker: <tracker id>`. Preflight refuses an id that is not in the registry. A bare issue id means the primary tracker (m4-12, m4-47).
- **The tracker write:** `TrackerAdapter` gains an optional `createIssue(title, body, labels)`, failing with "not supported" by default like the other optional writes. The files tracker writes `<prefix>-<nn>-<slug>.md` with the next free number and a front matter that parses back (title quoted, `state` the first active state, `labels`, empty `blocked_by`). The prefix is a tracker setting that defaults to `bl` when absent; setting it in Loop's own `WORKFLOW.md`, which is protected, is listed under `residual_human_steps`. The GitHub tracker creates the issue with the label. The contract gains a create test.
- **Bounded:** at most a few follow ups per attempt (a constant, named in the docs); past it the call is a structured failure, so a looping agent cannot flood a tracker. Events `followup_filed` (parent, new id, tracker).
- The follow up logic lives in `loop.core.trackers`.
- The scripted call goes through `loop tool mcp` on stdio, as `ToolMcpCommandTest` does; no new `loop tool` command.

Out of this issue: the files tracker label write (m4-53), triage of the follow ups (M6), follow ups to trackers other than `files` and GitHub.

## Acceptance criteria

- [ ] A fake runner `file_followup`, sent through `loop tool mcp` on stdio, gets an answer that says it is queued; when the turn ends a `needs-triage` issue exists in the parent's `files` tracker whose front matter parses back and whose body names the parent and the attempt, `followup_filed` holds the new id, and a comment on the parent names it (CLI test).
- [ ] With `kinds.<kind>.followup.tracker` set, the follow up goes to that tracker, here GitHub on recorded responses, and preflight refuses an unknown tracker id (one test each).
- [ ] Without a prefix setting, the files tracker writes a `bl-<nn>` file (test).
- [ ] Past the per attempt limit, the call is a structured failure and the turn goes on (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.FileFollowupTest'` passes.
- [ ] Both runners advertise `file_followup` with a valid schema (tests).
- [ ] `./gradlew --no-daemon :mcp:test && ./gradlew --no-daemon :plugins:runner-codex-app-server:test` passes.
- [ ] The create test of the contract runs on both trackers (judgment).
- [ ] `./gradlew --no-daemon :plugins:tracker-files:test --tests 'loop.tracker.files.FilesTrackerContractTest' && ./gradlew --no-daemon :plugins:tracker-github:test --tests 'loop.tracker.github.GitHubTrackerContractTest'` passes.
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] The prefix setting for Loop's own `WORKFLOW.md` is listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md`, PLAN 6.1 and 6.10 describe `file_followup`, the kind override and the prefix default (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
