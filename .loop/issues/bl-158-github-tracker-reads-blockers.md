---
id: bl-158
title: "The GitHub tracker reads blockers"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-157]
---

## Parent

None. Backlog, not scheduled in a milestone. Found on 2026-11-19 while weighing a switch of Loop's own backlog from the `files` tracker to GitHub issues.

## What

`GitHubTracker.normalize` (`plugins/tracker-github/src/main/kotlin/loop/tracker/github/GitHubTracker.kt`, around line 328) always sets `blockedBy = emptyList()`. On GitHub, every issue with the required labels is dispatched at once, whatever it waits for. The `files` tracker reads `blocked_by` and keeps an issue blocked until each blocker is `done`, failing closed on a missing blocker (`FilesTracker.withBlockedReason`). Loop's own backlog depends on that order: M4 chains most of its issues through `blocked_by`.

GitHub has native issue dependencies ("blocked by" and "blocking"). Checked against the live API on 2026-11-20:

- Every issue object of the REST API carries `issue_dependencies_summary` with `blocked_by`, `blocking`, `total_blocked_by` and `total_blocking`.
- `GET /repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by` lists the blocking issues as issue objects, with `number`, `state`, `state_reason` and `repository_url`.

Before relying on them, check in the current REST docs that `total_blocked_by` counts every blocker and `blocked_by` only the open ones, and record what the docs say in `docs/plugins/tracker-github.md`. No live issue with blockers was at hand to confirm it.

Area: `GitHubTracker.kt` (`list`, `fetchIssuesByIds`, `normalize` or a new step after it), a new file beside it for the blocker logic if it grows past a few functions (for example `GitHubBlockers.kt`), `FakeGitHub.kt` and test fixtures, `GitHubTrackerTest`, `docs/plugins/tracker-github.md`. No core change: the adapter sets `blockedBy` and `blockedReason`, the orchestrator only reads `blockedReason` (`Issue.kt`). This issue waits for bl-157, which edits the same file.

Design, settled in triage on 2026-11-20:

- **No blocker, no request.** When `issue_dependencies_summary.total_blocked_by` is 0, the issue has no blocker and no extra request is sent.
- **One request otherwise.** When it is above 0, one `GET .../issues/{number}/dependencies/blocked_by?per_page=100` lists the blockers. `blockedBy` becomes their references: the number for a blocker in the same repository, `owner/repo#number` for one in another.
- **What resolves a blocker.** Only `state: closed` with `state_reason: completed`. Open, `not_planned`, `duplicate`, a missing or unknown `state_reason` keep the issue blocked. The first blocker that holds it, in the order GitHub lists them, gives `blockedReason = "blocked_by #12 (open)"`, `"blocked_by #12 (closed, not_planned)"` or `"blocked_by owner/repo#3 (open)"`.
- **Unreadable blockers fail closed.** When the list holds fewer blockers than `total_blocked_by` (a private or cross repository blocker the token cannot see), the issue is blocked with `blockedReason = "blocked_by <n> unreadable"` even if every listed blocker resolved. An issue with no `issue_dependencies_summary` field counts as having no blocker, since older GitHub Enterprise servers do not send it. A summary that is present but malformed blocks with `blocked_by unreadable summary`.
- **A failed blocker request** (an error status, a malformed body) blocks only that issue with `blocked_by unreadable (<status>)` and does not fail the whole fetch, so one bad issue does not stop the poll. A rate limit answer follows the existing rate limit handling of `send`.
- **Order with bl-157.** An issue that is both untrusted and blocked shows the untrusted reason; its blockers are not requested.
- **Native dependencies only.** No `Blocked by: #12` line read from the body: one source of truth, and the body is text people edit.

Out of this issue: writing dependencies, the `blocking` direction, and moving Loop's own backlog to GitHub issues.

## Acceptance criteria

- [ ] An open issue with `total_blocked_by: 0` sends no dependency request (request count test with `FakeGitHub`).
- [ ] An open issue with an open blocker #12 is not dispatchable, with `blockedBy` `["12"]` and `blockedReason` `blocked_by #12 (open)`, from both `fetchIssuesByStates` and `fetchIssuesByIds` (test on a fixture shaped like the documented answer).
- [ ] The issue is dispatchable once every blocker is `closed` with `state_reason: completed`, and stays blocked when one is `closed` with `not_planned` or `duplicate`, the reason naming it (tests).
- [ ] A blocker in another repository appears as `owner/repo#n` in `blockedBy` and in the reason (test).
- [ ] With `total_blocked_by: 2` and one blocker listed, the issue is blocked with `blocked_by 1 unreadable`; an error answer to the blocker request blocks that issue only, the other issues of the poll unaffected (tests).
- [ ] The dependency requests per poll are counted in a test, at most one per candidate and none for candidates with no blocker or with an untrusted author.
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test` passes.
- [ ] `docs/plugins/tracker-github.md` documents how blockers are read, what resolves one, the fail closed cases and what the REST docs say about the two summary counts (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-19 triage:

> *This was generated by AI during triage.*

Filed while preparing the public repository. With bl-157, it gates moving Loop's own backlog to GitHub issues, planned after the M4 exit so the milestone keeps the `files` tracker.

2026-11-20 soule: triaged to ready-for-agent. Native dependencies only, no body line. Waits for bl-157, which edits `GitHubTracker.kt` too. Design in the What section.

2026-11-21 14:33Z agent 505addf8: Done in 73abf68 (code) and 1a86285 (docs). What I decided along the way:
  - Blockers are read only for candidates: open, in scope, trusted author, carrying every tracker.required_labels label. Closed or unlabeled issues cost no request, which matters for the rate limit at a 30 s poll.
  - A summary present but null blocks with "blocked_by unreadable summary". Only a missing key counts as no blocker.
  - A malformed blocker body gives "blocked_by unreadable (malformed)". A rate limit or an unreachable GitHub fails the call like any other request, so a refresh never stops a running attempt over a timeout.
  - When a listed blocker holds the issue, its reason comes before the unreadable count.
  - The REST docs give the four summary counts as integers with no description. A live check on 2026-11-21 against three public issues confirmed that total_blocked_by counts every blocker, blocked_by only the open ones, and that the list includes closed blockers. Recorded in the profile.

2026-11-21 15:01Z loop: Loop opened pull request #8 for this issue: https://github.com/soulesidibe/Loop/pull/8
