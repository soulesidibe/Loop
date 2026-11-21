---
id: bl-157
title: "The GitHub tracker passes only maintainer text to the agent"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found on 2026-11-19 while preparing the public repository, before switching Loop's own backlog to GitHub issues.

## What

On a public repository anyone with a GitHub account can comment on any issue, and the author of an issue can edit its body at any time. Loop puts both in the prompt of an agent that runs commands and pushes branches.

- **Comments.** `GitHubTracker.completeIssue` (`plugins/tracker-github/src/main/kotlin/loop/tracker/github/GitHubTracker.kt`, around line 100) appends every comment of the issue under `## Comments`, whoever wrote it. The `required_labels` gate stops a stranger from starting an attempt, but not from adding text to an attempt a maintainer started.
- **Body.** `normalize` (around line 295) takes the title and body as they are. A maintainer can label a stranger's issue `ready-for-agent` after reading it, and the stranger can then rewrite the body before the next poll.

The GitHub API gives each issue and each comment an `author_association`: `OWNER`, `MEMBER`, `COLLABORATOR`, `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, `FIRST_TIMER`, `MANNEQUIN` or `NONE`. The recorded fixture `issue-comments-26.json` holds four comments with `NONE`.

Area: `GitHubTrackerSettings.kt` (the new setting), `GitHubTracker.kt` (`normalize`, `completeIssue`, `comment`), their tests in `plugins/tracker-github/src/test/kotlin/loop/tracker/github/` (`GitHubTrackerSettingsTest`, `GitHubTrackerTest`, `GitHubCommentsTest`), test fixtures under `src/test/resources/github/`, and `docs/plugins/tracker-github.md`. No core change: the orchestrator already skips an issue whose `blockedReason` is set and records it as `candidate_skipped` (`Orchestrator.kt` around line 605, `Issue.kt`).

Design, settled in triage on 2026-11-20:

- **Setting.** `tracker.provider.trusted_associations`, a list of association names, compared case insensitively. Default `[OWNER, MEMBER, COLLABORATOR]`. A value outside the eight names above, an empty list or a value that is not a list of strings is a `GitHubConfigError.Invalid` at load that names the setting and the bad value.
- **Untrusted author.** `normalize` reads the issue's `author_association`. When it is missing or outside the list, the issue keeps its labels and state but gets `blockedReason = "untrusted_author (<ASSOCIATION>)"` (`missing` when absent), so it is never dispatched and `loop trace` shows the reason. This covers the title and the body: only the author and collaborators with triage access can edit an issue, so a trusted author means trusted editors. Labels need triage access too, so the label gate already trusts maintainers only. No check of the edit history.
- **Untrusted comments.** `completeIssue` keeps only comments whose `author_association` is in the list. A comment with no association counts as untrusted. When some were left out, the section gets one line after the heading, `<n> comments from outside tracker.provider.trusted_associations are not shown.` (`1 comment` for one), and nothing of their text or author. An issue whose comments are all untrusted gets the heading and that line only. Loop's own comments are written with the configured token, whose owner is `OWNER` or a collaborator, so they stay in.
- **Existing line.** The `older comments are not shown` line for comments on earlier pages stays as is; both lines can appear.
- **Docs.** `docs/plugins/tracker-github.md` documents the setting, its default, the risk it closes (prompt injection by outside text on a public repository) and why the author check covers body edits.

Out of this issue: blockers (bl-158), pull request comments, filtering by login instead of association.

## Acceptance criteria

- [ ] With the default list, every comment of the recorded `issue-comments-26.json` (all `NONE`) is left out, and the description holds the heading and the `4 comments from outside tracker.provider.trusted_associations are not shown.` line only (test in `GitHubCommentsTest`).
- [ ] On a mixed fixture (`OWNER`, `COLLABORATOR`, `NONE`, `CONTRIBUTOR`, one with no association), only the `OWNER` and `COLLABORATOR` comments are kept, in their usual shape, and the line counts the other three; no text or login of a left out comment appears in the description (test).
- [ ] An issue authored by `NONE`, by `CONTRIBUTOR` and with no association, each with `ready-for-agent`, gets `blockedReason` `untrusted_author (NONE)`, `untrusted_author (CONTRIBUTOR)` and `untrusted_author (missing)`, from both `fetchIssuesByStates` and `fetchIssuesByIds`; an `OWNER` issue has none (test in `GitHubTrackerTest`).
- [ ] An orchestrator level test with a fake GitHub records `candidate_skipped` with the reason `untrusted_author (NONE)` for such an issue and starts no attempt (test, in the module where the GitHub tracker meets the orchestrator, for example `cli`'s `TrackerPluginsTest`).
- [ ] The default list is `OWNER`, `MEMBER`, `COLLABORATOR`; a workflow can widen it (`[OWNER, CONTRIBUTOR]` keeps a `CONTRIBUTOR` comment) or narrow it (`[OWNER]` drops a `COLLABORATOR` comment); an unknown value, an empty list and a non list are config errors at load naming `tracker.provider.trusted_associations` (tests in `GitHubTrackerSettingsTest`).
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test` passes.
- [ ] `docs/plugins/tracker-github.md` documents the setting, its default, the risk it closes and why the author check covers body edits (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-19 triage:

> *This was generated by AI during triage.*

Filed while preparing the public repository. Priority 1: it must land before Loop's own backlog moves to GitHub issues, and before Loop is promoted for use on public repositories.

2026-11-20 soule: triaged to ready-for-agent. The open question is settled: the author check is enough, since only the author and collaborators with triage access can edit an issue body. Design in the What section.

2026-11-21 12:42Z loop: Loop opened pull request #1 for this issue: https://github.com/soulesidibe/Loop/pull/1
