# `github` tracker adapter profile

The `github` tracker reads the issues of one GitHub repository through the GitHub REST API. This page is the adapter profile that section 11.2 of the spec asks every adapter to publish. The implementation is `GitHubTracker` in `plugins/tracker-github` (`loop.tracker.github`).

Written 2026-11-12 in issue m2-22, the read side, and wired into the host on 2026-11-13 in bl-23. The write side (moves as label changes, comments, label writes) came on 2026-11-13 in m2-23, pull request delivery in m2-25, and the base fetch and the merge in m2-26. Trusted authors came on 2026-11-20 in bl-157, blockers on 2026-11-21 in bl-158. Terms follow [`CONTEXT.md`](../../CONTEXT.md); spec section numbers refer to [`symphony/SPEC.md`](../symphony/SPEC.md); Loop's readings of the spec are in [`spec-decisions.md`](../spec-decisions.md#github-tracker-read-side-extension-m2-22).

## Contents

1. [Configuration](#configuration)
2. [Profile](#profile)
3. [Scope](#scope)
4. [Pagination](#pagination)
5. [Rate limits](#rate-limits)
6. [Normalization](#normalization)
7. [Comments](#comments)
8. [Trusted authors](#trusted-authors)
9. [Blockers](#blockers)
10. [States](#states)
11. [Writes](#writes)
12. [Pull request delivery](#pull-request-delivery)
13. [Errors](#errors)
14. [Known gaps](#known-gaps)

## Configuration

| Key | Value | Default | Notes |
|---|---|---|---|
| `tracker.kind` | `github` | required | Selects the `github` tracker plugin, `GitHubTrackerProvider` (m3-05). `WORKFLOW.md` loading checks `tracker.provider` against its [profile](#profile), then hands it to `GitHubTrackerSettings.parse`, and `loop run` builds `GitHubTracker` from the result. |
| `tracker.provider.repo` | `owner/name` | required | Letters, digits, `-`, `_` and `.` on each side of one `/`, such as `acme/example-app`. |
| `tracker.provider.token` | a GitHub token, or `$NAME` | required | The secret key. A whole value `$NAME` is read from the environment, see below. |
| `tracker.provider.api_url` | an `https` address | `https://api.github.com` | For GitHub Enterprise Server, `https://<host>/api/v3`. A trailing `/` is dropped; a query or fragment is refused. Plain `http` is refused unless the host is a loopback host (`localhost`, `127.0.0.0/8`, `::1`), the escape for a test's fake server (m2-25), since the token goes to this address. |
| `tracker.provider.labels` | list of labels | empty | Scope labels: only issues that carry every one are in scope, see [scope](#scope). |
| `tracker.provider.handoff_labels` | map of `review` and `needs_info` to a label | `review: needs-review`, `needs_info: needs-info` | The label a move to each handoff state adds, see [states](#states). A state left out keeps its default; any other key is refused. |
| `tracker.provider.trusted_associations` | list of GitHub author associations | `OWNER`, `MEMBER`, `COLLABORATOR` | Whose issues are dispatched and whose comments reach the agent, see [trusted authors](#trusted-authors). Compared case insensitively. Each value is one of `OWNER`, `MEMBER`, `COLLABORATOR`, `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, `FIRST_TIMER`, `MANNEQUIN` and `NONE`; an empty list is refused. |
| `tracker.active_states` | list of states | `open` | Profile default, see [states](#states). Preflight refuses a handoff state here. |
| `tracker.terminal_states` | list of states | `closed` | Profile default, see [states](#states). Preflight refuses a handoff state here. |
| `tracker.required_labels` | list of labels | empty | Applied by the orchestrator after normalization, compared case insensitively. A move to a handoff state removes every one, so it must not be empty: preflight refuses an empty list (bl-46), see [states](#states). |

Unknown `tracker.provider` keys are ignored, with a startup warning naming each (m3-05), see [profile](#profile).

**The token.** A fine grained personal access token with read and write access to the repository's issues ("Issues: Read and write"); read access alone is enough for a run that never writes, and every write then fails with a 403. Only `token` supports `$NAME`:

- The value must be exactly `$NAME` (a letter or `_`, then letters, digits or `_`), such as `token: $GITHUB_TOKEN`. A `$` inside a longer value is kept as written, and the value is then a literal token.
- An unset variable fails with `missing_tracker_secret` and the message `tracker.provider.token uses $GITHUB_TOKEN, which is not set`; an empty or blank one with `which is empty`. A missing or blank `token` key fails with `missing_tracker_secret` and `tracker.provider.token is required`. Messages name the key and the variable, never a value.
- A literal token in `WORKFLOW.md` works, but it is readable by any agent with access to the repository (spec 10.5), and it is copied into the tool config snapshot like any other literal value. Only a `$NAME` reference keeps the token out of the agent's reach; keeping a literal token away from the agent is out of scope. Use `$NAME`.
- A variable that holds whitespace or a control character inside, after trimming, fails with `invalid_tracker_config` and `tracker.provider.token uses $GITHUB_TOKEN, whose value holds whitespace or a control character`; a literal token like that with `tracker.provider.token must not hold whitespace or a control character`. No valid token holds one, and the message never quotes the value.
- Preflight reports these failures with their category as its code, such as `missing_tracker_secret`, on startup and on every tick, so `loop run` never starts with an unusable token.
- Loop already treats every `$NAME` under `tracker.provider` as a [secret](../spec-decisions.md#secret-redaction-extension-of-153-and-131-m1-11): it is redacted before anything is written, removed from the agent environment and left out of the tool config snapshot. The settings never print the token either. The agent acts on GitHub only through `loop tool move` and `loop tool comment`, which queue a request that Loop applies host side with the token when the turn ends (m2-23).

**Validation errors.** The [profile](#profile) check runs first, then `GitHubTrackerSettings.parse` returns a `GitHubConfigError`, each with its spec 11.4 category:

| Category | When | Message |
|---|---|---|
| `invalid_tracker_config` | `repo` missing | `tracker.provider.repo is required` |
| `invalid_tracker_config` | `repo`, `api_url` not a string, `labels` or `trusted_associations` not a list, `handoff_labels` not a map (profile check, m3-05) | `tracker.provider.<key> must be a string`, `must be a list`, `must be a map` |
| `invalid_tracker_config` | `repo` not `owner/name` | `tracker.provider.repo must be owner/name, got "<value>"` |
| `invalid_tracker_config` | `api_url` not an http or https address | `tracker.provider.api_url must be an http or https address, got "<value>"` |
| `invalid_tracker_config` | `api_url` over `http` to a host that is not a loopback host (m2-25) | `tracker.provider.api_url must be an https address, http is allowed only for a loopback host, got "<value>"` |
| `invalid_tracker_config` | `labels` a list holding something other than a string | `tracker.provider.labels must be a list of labels` |
| `invalid_tracker_config` | `handoff_labels` with a key other than `review` and `needs_info` | `tracker.provider.handoff_labels has "<key>", only review and needs_info have a handoff label` |
| `invalid_tracker_config` | a `handoff_labels` value that is not a non blank string | `tracker.provider.handoff_labels.<state> must be a label` |
| `invalid_tracker_config` | `trusted_associations` not a list, or a list holding something other than a string (bl-157) | `tracker.provider.trusted_associations must be a list of author associations, got "<value>"` |
| `invalid_tracker_config` | `trusted_associations` an empty list | `tracker.provider.trusted_associations must not be empty, list the author associations to trust` |
| `invalid_tracker_config` | a `trusted_associations` value that is not one of the eight author associations | `tracker.provider.trusted_associations has "<value>", which is not a GitHub author association, use OWNER, MEMBER, COLLABORATOR, CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, MANNEQUIN or NONE` |
| `invalid_tracker_config` | `token`, or the value of its `$NAME`, holds whitespace or a control character | see above |
| `missing_tracker_secret` | `token` missing or blank, or its `$NAME` unset or empty | see above |
| `missing_tracker_secret` | `token` not a string (profile check, m3-05) | `tracker.provider.token must be a string` |

Preflight also fails with `invalid_config` when a move to a handoff state could never take an issue out of the active set. It runs at startup and on every tick, so a reload that would cause one of these is rejected like any invalid edit, and Loop keeps the last good workflow:

| Problem | Message |
|---|---|
| A handoff state, `review` or `needs_info`, in `tracker.active_states` or `tracker.terminal_states` (bl-46) | `tracker.active_states has "review", a handoff state of the github tracker. A handoff state matches no issue, and a move to it would not stop the worker, so list only open and closed there` |
| `tracker.required_labels` empty, or only blank labels (bl-46) | `tracker.required_labels is empty, so every move to review or needs_info would be refused: the move removes the required labels to take the issue out of the active set. Add the label that routes an issue to Loop, for example ready-for-agent` |
| A handoff label that is one of `tracker.required_labels`, compared case insensitively (m2-23) | `tracker.provider.handoff_labels.<state> is "<label>", which is also in tracker.required_labels, so a move to <state> would remove it` |

Minimal example:

```yaml
tracker:
  kind: github
  provider:
    repo: acme/example-app
    token: $GITHUB_TOKEN
    handoff_labels:        # optional, these are the defaults
      review: needs-review
      needs_info: needs-info
    trusted_associations: [OWNER, MEMBER, COLLABORATOR]   # optional, the default
  active_states: [open]
  terminal_states: [closed]
  required_labels: [ready-for-agent]
```

## Profile

Since m3-05 the tracker is a plugin, `GitHubTrackerProvider`, registered in `META-INF/services/loop.api.TrackerProvider` and selected by `tracker.kind: github` (see [plugin profiles](../spec-decisions.md#plugin-profiles-extension-m3-05)). Its profile declares the keys of `tracker.provider`:

| Key | Type | Required or default | Secret | Limits |
|---|---|---|---|---|
| `tracker.provider.repo` | string | required | no | none |
| `tracker.provider.token` | string | required | yes | none |
| `tracker.provider.api_url` | string | `https://api.github.com` | no | none |
| `tracker.provider.labels` | list | empty | no | none |
| `tracker.provider.handoff_labels` | map | `review: needs-review`, `needs_info: needs-info` | no | none |
| `tracker.provider.trusted_associations` | list | `OWNER`, `MEMBER`, `COLLABORATOR` | no | none |

Loop checks `tracker.provider` against it on every load of `WORKFLOW.md`, at startup, in the preflight of every tick and on every reload, before `GitHubTrackerSettings.parse` reads it. A failure keeps the spec 11.4 categories of the [validation errors](#configuration): a missing or wrongly typed key fails with `invalid_tracker_config` naming it, the secret `token` missing or written `$NAME` with the variable unset or blank fails with `missing_tracker_secret` naming the variable, never a value. A literal token is left to the settings reader. A key the profile does not list is a startup warning, `tracker.provider.<key> is not a key of the github tracker, Loop ignores it`, never a failure, for forward compatibility. Its profile defaults for the states are `open` and `closed`.

## Scope

- The scope is the issues of `tracker.provider.repo` that carry every label of `tracker.provider.labels`. With no scope label, it is every issue of the repository.
- A scope label should stay on an issue for its whole life, such as `loop`. Do not use the routing label `ready-for-agent` as a scope label: the write side removes it on a move, and the issue would leave the scope, so it would never be cleaned up when it closes.
- Pull requests are never issues. The issues API returns them too; the adapter drops every item with a `pull_request` key, in lists and by id.
- **Fetch by states** asks `GET /repos/{owner}/{repo}/issues?state=<state>[&labels=<scope labels>]&sort=created&direction=asc&per_page=100`. GitHub filters by state and labels. The state is `open` or `closed`, or `all` when both are requested. Requested states are compared case insensitively; a list with neither `open` nor `closed`, the empty list included, returns no issue without a request (17.3).
- **Fetch by ids** asks `GET /repos/{owner}/{repo}/issues/{number}` once per distinct id, one after the other. An id that is not an issue number (digits, not starting with `0`) is omitted without a request, so an arbitrary text never reaches a request path. A 404 or 410 answer (not found or hidden, deleted) omits the issue only when the repository itself answers: the adapter then asks `GET /repos/{owner}/{repo}` once per call, and any failure of that request fails the call with its own error. So a token that lost access, or a repository that is gone, never reads as every running issue gone, which would cancel their Attempts. A 301 (a renamed repository, an issue transferred to another one) fails the call with `Status`, for the same reason. A pull request and an issue without every scope label: each is no longer visible in the scope (11.1). An empty list returns no issue without a request.
- Requests carry `Authorization: Bearer <token>`, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28` and `User-Agent: loop`. Redirects are never followed, and each request times out after 30 seconds.

## Pagination

- A list follows the `rel="next"` URL of each answer's `Link` header, as given: GitHub pages issues with a cursor (`after=`), not only with `page=`. Issues are returned in GitHub's order, oldest first, page after page (17.3 item 4).
- The call is atomic: any failure on any page fails the whole call, and no partial list is returned (11.1).
- Integrity checks, each a `Pagination` error (`tracker_pagination`):
  - a next page URL on another origin (scheme, host or port) than `api_url`, so the token is never sent anywhere else;
  - a next page URL already read in this call;
  - more than 100 pages (10,000 issues at 100 a page), the request limit of one call;
  - an issue number on two pages, which means the list changed while it was read and may have skipped an issue. The next poll reads it again.

## Rate limits

GitHub's REST limit is 5,000 requests an hour for a token. A poll costs one request per page of the list, plus one per candidate that has a blocker (see [blockers](#blockers)), a refresh one request per running issue, plus one when it has a blocker, a dispatch one request when the issue has comments (see [comments](#comments)), and a write one request per call it makes (see [writes](#writes)).

- A rate limit answer is a 429, or a 403 with `x-ratelimit-remaining: 0` or a `retry-after` header. The call fails with `RateLimited`, whose `retryAt` is, in this order: now plus `retry-after` seconds, the `x-ratelimit-reset` time when the remaining count is 0, or now plus one minute, as GitHub asks for a secondary limit that names no time.
- Until `retryAt`, the adapter sends no request: every call fails at once with the same `RateLimited`, writes included. GitHub may block an integration that keeps sending requests while limited. A write answered with a rate limit holds the reads the same way.
- A successful answer with `x-ratelimit-remaining: 0` also holds every request until its `x-ratelimit-reset` time. That call still succeeds.
- The adapter never sleeps inside a call, and never retries a request inside a call, a write least of all. The orchestrator's handling of a failed call (11.4) is the retry: a failed candidate fetch skips dispatch for that tick, a failed refresh keeps running workers running, a failed write is a `tool_request_failed` the next turn's guidance names.
- A 403 without these headers is a permission error, `Status`.

## Normalization

| Field | Source | Rule |
|---|---|---|
| `id` | `number` | The issue number as text, such as `454`. Required: a positive integer. |
| `identifier` | `tracker.provider.repo` and `number` | `<repo name>-<number>`, such as `example-app-454`, in the spelling of the configured repo name. Unique in the scope, and safe in shell commands, branch names and workspace keys. |
| `native_ref` | the issue | Non secret GitHub ids for provider native tools: `repo` (`owner/name`), `number`, `node_id` (the GraphQL id), `database_id` (the REST `id`) and `url` (`html_url`). A field GitHub does not give in the expected type is left out. |
| `title` | `title` | Trimmed, required, non empty. |
| `state` | `state` | Trimmed, required, non empty, as GitHub spells it (`open`, `closed`). |
| `priority` | labels | GitHub has no priority. A label `priority:<n>` (any case, spaces allowed after the colon, `n` digits) gives the priority; with several, the lowest `n` wins. Null when there is none. |
| `labels` | `labels` | Label names, trimmed, lowercased, blanks dropped, duplicates removed. A `priority:<n>` label is read into `priority` and not repeated here, so it cannot be a required label. A value that is not a list gives an empty list. |
| `blocked_by` | `issue_dependencies_summary` and the blocked_by list | The blockers of a candidate as GitHub lists them: `12` for an issue of the same repository, `owner/repo#12` for one in another. Empty when it has none, and for an issue that is not a candidate, see [blockers](#blockers). |
| `blocked_reason` | `author_association`, then the blockers | `untrusted_author (<ASSOCIATION>)`, or `untrusted_author (missing)` when GitHub gives none, when the issue's author is outside `tracker.provider.trusted_associations`, see [trusted authors](#trusted-authors). Otherwise the first blocker that holds it, such as `blocked_by #12 (open)`, or why its blockers cannot be read, see [blockers](#blockers). Null when nothing blocks it. |
| `dispatchable` | `blocked_reason` | True when `blocked_reason` is null. |
| `description` | `body` | Trimmed; empty or null is null. Comments are added at dispatch only, see [comments](#comments). |
| `created_at` | `created_at` | Parsed as an RFC 3339 instant; an unusable value is null. |
| `updated_at` | none | Not provided. |

A record is malformed when it has no positive integer `number`, or no non empty `title` or `state`, or is not a JSON object. **Fetch by states** skips it and reports it to the `onMalformed` callback, so Loop logs the omission and dispatches the others. **Fetch by ids** fails the whole call on a requested issue that is malformed, because an omission would read as "no longer visible" (11.1).

## Comments

Written in bl-06. Loop puts nothing in the prompt on its own: the prompt template renders `issue.description`, and a human's answer to an agent's question is a comment. So, once per dispatch, just before the prompt is rendered, the orchestrator calls `completeIssue`, and the adapter appends the issue's comments to its description, in the shape of a `files` tracker issue:

```
<body>

## Comments

2026-11-08 16:00Z soule: Yes, include the archived items.
  A second line of the same comment.
```

- **One request, never on a poll.** `GET /repos/{owner}/{repo}/issues/{number}/comments?per_page=100`. A list or a by id read records GitHub's `comments` count of each issue; when the latest read counted none, no request is sent and the issue is returned as is. An issue the adapter never read is asked for anyway, so no answer is missed. A comment Loop writes adds one to the count it holds (m2-23), so the next dispatch asks for the comments, on the last page past 100, even before a read counts the new one.
- **Newest last, newest kept.** Comments are sorted by `created_at`, oldest first. Past 100 comments the request asks for the last page (`&page=<n>`), and the section opens with `<k> older comments are not shown.`, so the newest answers always survive.
- **Trusted authors only.** A comment whose `author_association` is missing or outside `tracker.provider.trusted_associations` is left out, see [trusted authors](#trusted-authors). When any was left out, the section opens with one line that counts them, `3 comments from outside tracker.provider.trusted_associations are not shown.` (`1 comment ... is not shown.` for one), before the `older comments` line when both appear. Nothing of their text or author is shown. An issue whose comments are all left out gets the heading and that line only.
- **Each comment** is one block: `YYYY-MM-DD HH:MMZ <login>: <first line>` in UTC, then its other lines indented two spaces, so a heading inside a comment never opens a section. Line ends are normalized and blank lines dropped, like a `files` tracker comment. A deleted account is `ghost`. Blank comments are left out; with no other comment the issue is returned as is.
- **Failures.** Any error of the request is the call's error, with the categories of [errors](#errors); a body that is not a list, or a comment that is not an object or has no usable `created_at`, is `Malformed`. A rate limit holds this request like any other. The orchestrator then starts no agent: the attempt fails with `tracker error: <message>` and is retried with the normal backoff, so a prompt is never rendered without the answers.

## Trusted authors

Written in bl-157. On a public repository anyone with a GitHub account can open an issue and comment on any issue, and the author of an issue can edit its title and body at any time. Loop puts the issue text and its comments in the prompt of an agent that runs commands and pushes branches, so outside text there is a prompt injection: a stranger could ask the agent to leak what it can read or push something it should not. `tracker.required_labels` stops a stranger from starting an attempt, since only people with triage access can label, but not from adding text to an attempt a maintainer started.

GitHub gives each issue and each comment an `author_association`, the relation of its author to the repository: `OWNER`, `MEMBER` (of the owning organization), `COLLABORATOR`, `CONTRIBUTOR` (has a merged commit), `FIRST_TIME_CONTRIBUTOR`, `FIRST_TIMER`, `MANNEQUIN` or `NONE`. `tracker.provider.trusted_associations` lists the ones Loop trusts, by default `OWNER`, `MEMBER` and `COLLABORATOR`: the repository's owner, the members of its organization and its collaborators.

- **Issues.** An issue whose author is outside the list, or that has no `author_association`, keeps its labels and state but gets `blocked_reason` `untrusted_author (<ASSOCIATION>)`, such as `untrusted_author (NONE)`, or `untrusted_author (missing)`. It is never dispatched: the orchestrator records `candidate_skipped` with that reason, so `loop trace <number>` shows why it waits, and a running attempt of such an issue ends at its next turn boundary, like that of any blocked issue.
- **Why the author check covers body edits.** Only the author of an issue and people with triage access to the repository can edit its title and body. A trusted author means every possible editor is trusted too, so a maintainer who labels an outside issue `ready-for-agent` after reading it cannot have the body rewritten under them: that issue is blocked whatever its labels. Loop does not read the edit history. To work on an outside report, a maintainer opens a new issue in their own words.
- **Comments.** Only the comments of trusted authors reach the agent; the others are counted in one line, see [comments](#comments). Loop's own comments are written with the configured token, whose owner is the repository owner or a collaborator, so they stay in.
- **Widening and narrowing.** A private repository whose contributors are all trusted can add `CONTRIBUTOR`; a solo project can keep only `OWNER`. Adding `NONE` trusts every GitHub account and brings the risk back.

The tracker reads no pull request comments, so no check applies to them.

## Blockers

Written in bl-158. GitHub has native issue dependencies: an issue can be marked "blocked by" other issues, of the same repository or of another. The adapter reads them, so an issue waits for its blockers like a `files` tracker issue waits for its `blocked_by`. It reads nothing else: a `Blocked by: #12` line in the body is plain text, since the body is text people edit, and one source of truth is enough. Writing dependencies and the `blocking` direction are out of scope.

- **Which issues.** Blockers are read for a candidate only: an `open` issue, in scope, by a [trusted author](#trusted-authors), carrying every label of `tracker.required_labels`. Any other issue is never dispatched, so it costs no request and keeps an empty `blocked_by`. An untrusted issue keeps its `untrusted_author` reason and its blockers are not asked for.
- **No blocker, no request.** Every issue object carries `issue_dependencies_summary`. When its `total_blocked_by` is 0 the issue has no blocker and nothing more is sent.
- **One request otherwise.** `GET /repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by?per_page=100` lists the blockers as issue objects. A list reads them once every page of the list is read, so a list that fails on a page sends none. `blocked_by` gets their references in GitHub's order: `12` for an issue of the same repository, compared case insensitively, `owner/repo#12` for one in another, taken from the blocker's `repository_url`.
- **What resolves a blocker.** Only `state: closed` with `state_reason: completed`, compared case insensitively. An open blocker, one closed as `not_planned` or `duplicate`, and one closed with a missing or unknown `state_reason` keep the issue blocked. The first blocker that holds it, in GitHub's order, gives `blocked_reason`: `blocked_by #12 (open)`, `blocked_by #12 (closed, not_planned)`, `blocked_by #12 (closed)` for a closed blocker with no reason, or `blocked_by owner/repo#3 (open)`.
- **Fail closed.** Each case blocks that issue only, never the whole call, so one bad issue does not stop the poll:
  - the list holds fewer blockers than `total_blocked_by`, such as a blocker in a private repository the token cannot see, or more than 100 blockers: `blocked_by <n> unreadable`, even when every listed blocker resolved. A listed blocker that holds the issue names it first;
  - a summary that is present but not an object with a non negative integer `total_blocked_by`, `null` included: `blocked_by unreadable summary`, with no request;
  - an error answer to the blocker request, such as a 404 or a 500: `blocked_by unreadable (<status>)`;
  - a body that is not a list of issues, or a blocker with no positive `number`, no `state` or no `repository_url` ending in `/repos/<owner>/<repo>`: `blocked_by unreadable (malformed)`.
- **What does not block.** An issue with no `issue_dependencies_summary` field counts as having no blocker, since older GitHub Enterprise servers do not send it. A rate limit answer to the blocker request fails the call and holds requests like any other, see [rate limits](#rate-limits), and an unreachable GitHub fails the call with `Unavailable`. A failed call keeps running workers running, where a blocked running issue would be stopped.
- **A running issue.** A refresh reads the blockers of a running candidate the same way. When one appears or reopens, the attempt ends at its next turn boundary, like that of any blocked issue.
- **What the REST docs say about the two counts.** Read on 2026-11-21: "REST API endpoints for issues" gives `issue_dependencies_summary` four required integers, `blocked_by`, `blocking`, `total_blocked_by` and `total_blocking`, with no description, so the docs do not say which count is open only. "REST API endpoints for issue dependencies" documents the blocked_by list with `per_page` (default 30, max 100) and `page`, an array of issues as the answer, the statuses 200, 301, 404 and 410, and no state filter. Those pages are written for API version 2026-03-10; Loop sends 2022-11-28, which carries the same field. Checked against the live API the same day on three public issues: `total_blocked_by` counts every blocker, `blocked_by` only the open ones, and the list shows the closed blockers too, with their `state_reason`. The adapter relies only on `total_blocked_by` and the list, so it fails closed if GitHub ever lists only open blockers.

## States

| State | Role | Effect |
|---|---|---|
| `open` | active | Eligible for dispatch when the other rules pass. A move to `open` patches the issue state. |
| `closed` | terminal | Never dispatched; the workspace is cleaned up. A move to `closed` patches the issue state. |
| `review` | handoff | Made of labels on an `open` issue: a move adds `handoff_labels.review` (default `needs-review`) and removes every required label. |
| `needs_info` | handoff | The same with `handoff_labels.needs_info` (default `needs-info`). |

These four are the tracker's `definedStates`, so `loop tool move review` and `loop tool move needs_info` are accepted. GitHub itself has only `open` and `closed`: default `active_states` are `open`, default `terminal_states` are `closed`, the defaults 5.3.1 lets a profile document. An issue keeps GitHub's state when it is read, so an issue handed off is still `open`; a list reads issues by GitHub's state only, so a handoff state in `active_states` or `terminal_states` would match no issue, and the orchestrator would not count a move to it as a handoff. Preflight refuses one there, at startup and on reload (bl-46).

**A move to a handoff state** is a label change (plan 6.6, story 80):

1. The handoff label is added first, `POST /repos/{owner}/{repo}/issues/{number}/labels` with `{"labels":["needs-review"]}`. A label already there stays once.
2. Then every `tracker.required_labels` label is removed, one `DELETE /repos/{owner}/{repo}/issues/{number}/labels/{name}` each, the name escaped as a path segment. A 404 means the issue does not carry the label, and counts as success.
3. The issue leaves the required labels, so it leaves the active set and is no longer dispatched. The orchestrator sees its own move as a handoff although the issue is still `open`: the worker stops, the workspace is kept, the counters reset and no continuation turn runs, as with the `files` tracker ([spec decisions](../spec-decisions.md#github-tracker-write-side-extension-m2-23)).

The required labels are those of the effective workflow, read at each move, so a reload of `tracker.required_labels` reaches the next move. A move is refused, with nothing sent, when `tracker.required_labels` is empty, which preflight already refuses at startup and on reload (bl-46), (`Cannot move to review: tracker.required_labels is empty, so the issue would never leave the active states. Nothing was moved`) or holds the handoff label (`Cannot move to review: its label needs-review is also in tracker.required_labels, so the move would remove it. Nothing was moved`).

**Partial failure.** Both calls are idempotent. A failure after the add leaves the issue with the handoff label and a required label still on: the move fails, the issue is still eligible, and the next move attempt repeats both calls.

**Return path.** A human sends an issue back to Loop by restoring `ready-for-agent` (the required labels). Removing `needs-review` or `needs-info` is the human's job too: Loop never removes a handoff label, so an issue sent back with it still on is eligible again and keeps the label until a human removes it.

Agents never move to `open` or `closed` (Loop's prompt forbids it), but the tracker supports both: `PATCH /repos/{owner}/{repo}/issues/{number}` with `{"state":"closed"}` or `{"state":"open"}`. GitHub answers 422 `This issue was closed by another request. Reload it and try again.` to a PATCH that lands while it closes the issue itself, as `Closes #n` does about a second after a merge, reproduced on the playground in bl-68. So a 422 to the PATCH reads the issue once, `GET .../issues/{number}`: shown in the target state, the move succeeds; in another state, or when the read fails, the move fails with the 422.

## Writes

Every write sends the read headers plus `Content-Type: application/json`, once: a write is never retried inside a call, and a write respects the [rate limit](#rate-limits) hold of the read side. An id that is not an issue number fails with `NotFound` without a request.

| Write | Request | Notes |
|---|---|---|
| `moveIssue` to `open` or `closed` | `PATCH .../issues/{number}` with `{"state":...}` | See [states](#states). A 422 reads the issue once and succeeds when it shows the target state (bl-68). |
| `moveIssue` to `review` or `needs_info` | `POST .../issues/{number}/labels`, then one `DELETE .../issues/{number}/labels/{name}` per required label | See [states](#states). A state outside the four fails with `UndefinedState` without a request. |
| `addComment` | `POST .../issues/{number}/comments` with `{"body":...}` | The text as given, already redacted by the orchestrator. A blank text fails with `BlankComment`; a text over GitHub's limit of 65,536 characters, counted as code points like GitHub, fails with `Rejected` and is never cut: `The comment has <n> characters, over GitHub's limit of 65536. Nothing was posted, shorten it and comment again`. |
| `updateLabels` | one `POST .../labels` for the labels to add, then one `DELETE` per label to remove | Labels are trimmed, blanks ignored; a label given twice, or both added and removed, compared case insensitively, is added once and not removed. A removal 404 is success. |

`loop tool move` and `loop tool comment` queue their request in the attempt directory; Loop applies it host side through this tracker when the turn ends. A write that fails is recorded as `tool_request_failed` with the error message as its `reason`, and the next turn's guidance names it (m2-07).

## Pull request delivery

Written 2026-11-13 in m2-25, extended 2026-11-14 in m2-26. `GitHubDelivery` is the `DeliveryExecutor` of a GitHub repository: the orchestrator calls its `findPullRequest`, then its `push`, then its `openPullRequest` or `updatePullRequest`, then comments and moves the issue through the tracker. For the merge check of m2-26 it also calls `fetchBase`, `baseTip` and `mergePullRequest`; `fetchBase` also runs before the `diff_policy` checks of a verification (bl-32) and before the hooks of every attempt (bl-59). This section is the module's side; the delivery flow itself belongs to the host.

**The shared client.** `GitHubClient` holds what the tracker and delivery share: the read and write headers, the single send that is never retried, the [rate limit](#rate-limits) hold and the mapping of answers to `TrackerError`. `GitHubTracker` sends through one; `GitHubDelivery` builds its own with the same token, so a hold set by the tracker does not hold delivery requests, and the other way round (bl-50 shares it).

**The pull request.** `GitHubPullRequests` looks up, creates and updates, so a delivery replayed after a crash opens no second pull request:

1. `GET /repos/{owner}/{repo}/pulls?head={owner}:{branch}&base={base}&state=all&per_page=100`, the head and base encoded. The answer is filtered again by `head.ref` and `base.ref`. The open one wins, else the merged one (`merged_at` set) with the highest number, else the closed one with the highest number; the result carries its state and `head.sha`. The delivery patches an open one, counts one merged at the verified commit as delivered, and opens a new one otherwise.
2. `PATCH /repos/{owner}/{repo}/pulls/{number}` with `{"title":...,"body":...}`, and the result says it was updated (`created` false).
3. `POST /repos/{owner}/{repo}/pulls` with `{"title":...,"head":"<branch>","base":"<base>","body":...}`, `created` true. The base defaults to `main`.
4. A 422 to the create whose `message` or an `errors[].message` says `A pull request already exists for ...` (another delivery won the race) looks up exactly once more: an open one found is patched; none, the delivery fails for good. Any other 422, such as `No commits between main and <branch>`, fails for good with GitHub's messages joined.

A body over 65,536 characters, counted as code points like a comment, fails before any request. The result's number is `number` and its url `html_url`; a success without them, or that is not JSON, fails for good.

**The push.** `GitPush` pushes a commit of the project repository, the folder of `WORKFLOW.md`, to an explicit URL, `https://<host>/<owner>/<repo>.git`: the host comes from the API URL (below), the owner and name from `repo`. No remote name is read; the `remote` key of `delivery.pull_request` is only checked by the preflight. The project's `.git/config` is shared with every worktree and an agent can write it (`remote.origin.receivepack`, `pushurl`, `url.*.insteadOf`, `core.sshCommand`, `core.askPass`, `http.proxy`, `http.<url>.sslVerify` and the like), so the push never reads it:

1. In the project repository, `refs/heads/<branch>` must be at the verified commit (`git rev-parse --verify -q`): missing or elsewhere, nothing is pushed (`The branch loop/x is at <tip>, not the verified commit <sha>. Nothing was pushed`). `git rev-parse --git-common-dir` then gives its object folder, shared by its worktrees.
2. Loop creates a temporary bare repository (`git init --bare --template=`), whose `objects/info/alternates` names that object folder, so it holds the verified commit and nothing else of the project. The push and its checks run there with `GIT_CONFIG_NOSYSTEM=1`, `GIT_CONFIG_SYSTEM=/dev/null` and `GIT_CONFIG_GLOBAL=/dev/null`: no config file of the project, the user or the system applies. The folder is deleted afterwards.
3. With the same settings, git's resolved push URL (`git remote get-url --push --all` of a remote defined there with the URL) must be the URL itself, so no `url.*.insteadOf` or `pushInsteadOf` can redirect it: otherwise `git would push to <url>, not <url>. Nothing was pushed`.
4. Then it pushes, never forced and never with a `+` refspec, with Loop's settings pinned on the command line:

```
git --git-dir=<temporary> -c protocol.allow=never -c protocol.https.allow=always -c core.askPass= -c core.sshCommand=
  -c http.proxy= -c http.sslVerify=true -c push.followTags=false -c core.hooksPath=/dev/null
  -c credential.helper= -c credential.helper=<helper> push --no-verify --porcelain <url> <sha>:refs/heads/<branch>
```

- The URL must be `https`, with a plain host name and an optional port, no user, query or fragment, and the path `/<owner>/<repo>.git`; `http` only for a loopback host, the fake server of a test, and then `protocol.http.allow=always` instead.
- The token reaches git only through the credential helper, which answers `get` with `username=x-access-token` and `password=$LOOP_DELIVERY_TOKEN`. Of the git processes Loop runs, only the push gets that variable, and the processes git starts for it, such as the helper and `git-remote-https`, inherit it; every other one gets the minimal environment below without it. The token is never in an argument, a URL or a git config file. The first, empty `credential.helper=` clears every other helper.
- The helper answers only when git asks with the scheme of the URL (`protocol=https`) and its host, and answers nothing otherwise, so git finds no credential for another host or protocol. The host comes from the API URL: `https://api.github.com` gives `github.com`, an Enterprise server `https://<host>/api/v3` gives `<host>`, `https://api.<name>.ghe.com` gives `<name>.ghe.com`, with the port when the URL has one. A host that is not a plain host name with an optional port is refused before git starts.
- Every git process Loop runs for delivery, the push, `rev-parse`, `git init`, `git remote` and the preflight's `git remote get-url`, gets a minimal environment: `PATH`, `HOME` and the locale (`LANG`, `LANGUAGE`, `LC_*`) of the host, plus `GIT_TERMINAL_PROMPT=0`, so git never waits for a password. The token under its configured `$NAME`, the Anthropic keys and every `GIT_` variable of the host are left out. Each process is stopped after 5 minutes. No hook runs.
- Pushing a commit the remote branch already holds succeeds, git says it is up to date.
- A proxy, a custom certificate authority or any other git setting of the user does not apply to the push.

**The base fetch.** `GitPush.fetch(base)`, called by `fetchBase`, brings the tip of the configured base (`delivery.pull_request.base`, default `main`) into the project repository, from the same explicit URL and with the same isolation as the push:

1. `base` must be a safe branch name, by git's rules for a ref name and a little stricter: not empty, at most 255 characters, no whitespace, control character or any of `` ~^:?*[\ ``, no `..`, `@{` or `//`, not `@`, not starting with `-` or `/`, not ending with `/` or `.`, and no part that starts with `.` or ends with `.lock`. Otherwise nothing starts.
2. In the project repository, without the token and with `-c core.hooksPath=/dev/null -c core.fsmonitor=false`, git gives the object folder, the object format and the project's own tips of the base (`refs/loop/base/<base>` and `refs/heads/<base>`, when they exist).
3. Loop creates a temporary bare repository like the push's, borrowing the project's objects through `objects/info/alternates`, with no config file of the project, the user or the system. The project's tips of the base become refs there, so only missing objects travel. Git's resolved fetch URL (`git remote get-url --all`) must be the URL itself: otherwise `git would fetch from <url>, not <url>. Nothing was fetched`.
4. Then it fetches, the token in this process alone, through the same scoped credential helper:

```
git --git-dir=<temporary> <the pins of the push> -c credential.helper= -c credential.helper=<helper>
  -c fetch.unpackLimit=1 -c core.alternateRefsCommand=true -c fetch.writeCommitGraph=false -c gc.auto=0
  -c maintenance.auto=false fetch --quiet --no-tags --no-recurse-submodules --no-write-fetch-head
  <url> +refs/heads/<base>:refs/heads/loop-fetched
```

`fetch.unpackLimit=1` keeps what arrives a pack. `core.alternateRefsCommand=true` matters: by default git lists the refs of a borrowed repository by starting `git --git-dir=<project>/.git for-each-ref`, a git process that reads the project's config with the token in its environment; with it, no such process starts. `git rev-parse` in the temporary repository then reads the fetched commit id: that id, never a ref read back from the project, is what `fetchBase` returns.
5. The objects reach the project's object folder by plain file copy, never by a git process holding the token: each `objects/pack/*.pack`, then `*.rev`, then `*.idx`, so git never sees an index without its pack, and any loose object, each copied to a temporary name in the target folder and then moved into place at once. A file the project already has is left alone. The temporary repository is deleted afterwards.
6. Last, in the project repository, without the token and with no hook or fsmonitor: `git cat-file -t <sha>` must say `commit`, then `git update-ref refs/loop/base/<base> <sha>`.

So no git process that reads the project's config, the user's or the system's ever holds the token, and every git process of the fetch gets the minimal environment of the push. A fetch with nothing new still succeeds and proves the tip.

**The fetch mutex.** Since m4-49 (bl-67), the base fetches of one repository run one at a time. Two verifications at once used to fetch and write `refs/loop/base/<base>` together, and git's ref lock could fail one of them, which recorded `diff_base_unpinned` and blocked its auto merge. Everything after the read of the object folder in step 2, through step 6, now runs under one mutex per repository, held in the Loop process. Repositories are told apart by the real path of their object folder, the one step 2 reads (`git rev-parse --git-common-dir`), so two workspaces of one repository share a mutex and wait on each other, and two repositories never do. A second fetch waits until the first ends, which the 5 minute limit of each of its git processes bounds. Only the base fetch takes the mutex: the push and a git process an agent starts in its workspace do not.

**The base tip and the merge.** `GitHubPullRequests` reads the base and merges, through the shared client:

- `baseTip`: `GET /repos/{owner}/{repo}/git/ref/heads/{base}`, each part of the base encoded and its slashes kept, read right before a merge. The result is `object.sha`; an answer whose `ref` is not `refs/heads/<base>` or without a full commit id fails for good, a missing base is a 404.
- `mergePullRequest`: `PUT /repos/{owner}/{repo}/pulls/{number}/merge` with `{"sha":"<head sha>","merge_method":"merge"}` or `"squash"`, never `rebase`, which rewrites the commits that were checked. The `sha` is GitHub's guard: GitHub refuses the merge when the head of the pull request is no longer that commit. A sha that is not a full commit id is refused before any request. The result is the `sha` of the commit the merge made; a success without one fails for good.
- A 403 to the merge says the token may not merge and needs the contents write and the pull requests write permissions, with GitHub's message; a 405 says the pull request is not mergeable; a 409 says the head moved and the sha guard refused the merge. 401, 403, 404, 405, 409 and 422 are permanent, so the host records `delivery_failed` naming the reason. A 405 whose message says "Base branch was modified" and a 409 whose message says "Head branch was modified" are still `Rejected` but marked `retryOnce`: GitHub answers so when a branch moved while it merged, which its delayed update of a just pushed head can cause, and the host tries once more at the next tick before it counts the refusal as permanent; a 5xx answer, a rate limit or its hold (a 403 or 429 with `x-ratelimit-remaining: 0` or `retry-after`) and an unreachable GitHub are tried again at the next tick.

**Token permissions.** The delivery token needs, on the repository: contents write, for the push, the base fetch and the merge; pull requests write, to open, update and merge pull requests; issues write, for the comment and the label moves. Since m3-15, Actions read, to list the Actions runs of a merged head for the CI wait and the `ci` condition of an auto merge (`GET /repos/<repo>/actions/runs?head_sha=<sha>&per_page=100`); a 403 or 404 on that listing records `delivery_failed` naming "Actions: read". No Checks permission is needed. The preflight does not probe them: a fine grained token cannot be asked which permissions it has, so a missing one shows at the step that needs it. A 403 to the merge records `delivery_failed` with the message naming the missing permissions.

**Exposure in the workspace merge.** The fetch and the merge request above run nothing the repository names. When Loop merges the base into the work in the workspace, its git runs with hooks, fsmonitor, signature checks, gpg programs, automatic gc and maintenance and replace refs switched off on the command line, but git still applies the filters (`filter.<name>.clean`, `smudge`, `process`) and merge drivers (`merge.<name>.driver`) that `.gitattributes` names and a config defines, and they run as Loop's user, without the token. Disarming them is left to bl-55 and M5.

**Failures.** Delivery never throws, cancellation aside: a step returns a failed result with a `DeliveryError`. `Rejected` is permanent, the delivery waits for a new approval; `Unavailable` is tried again at the next tick.

| `DeliveryError` | When |
|---|---|
| `Rejected` | A branch missing or not at the verified commit; a resolved push or fetch URL that is not the URL; a base that is not a safe branch name or that the remote does not have; a fetched tip that is not a commit; a push the remote refuses (a porcelain line flagged `!`: not a fast forward, a protected branch); an authentication failure (git says authentication failed, could not read Username, terminal prompts disabled, permission denied, or 403); a GitHub answer such as 401, 403, 404, 405, 409 or 422; a success that cannot be read; a body over 65,536 characters; a merge sha that is not a full commit id |
| `Unavailable` | GitHub unreachable, a rate limit or its hold, a 5xx answer; any other git failure, such as a host that cannot be reached, a temporary repository that cannot be prepared, fetched objects that cannot be copied or are missing after the copy, a timeout, or a git that cannot start |

Messages quote GitHub's message or git's first five lines, each cut at 200 characters, and never the token: git's output is scrubbed of it.

## Errors

Every call returns a result whose failure is a `TrackerError`. The adapter does not throw for expected failures; cancellation still propagates.

| `TrackerError` | When | Spec category (11.4) | Message |
|---|---|---|---|
| `Unavailable` | GitHub cannot be reached, the connection fails, or no answer within 30 seconds | `tracker_request` | The I/O error's message, such as `GitHub did not answer within 30s` |
| `Status` | Any non success answer that is not a rate limit, and not a 404 or 410 of fetch by ids while the repository answers, nor a 404 of a label removal, nor an answer to a blocker request, which blocks its issue instead (see [blockers](#blockers)). A write answered 403 (a token without write access), 404 or 422 (a value GitHub refuses) is one too | `tracker_status` | `The tracker answered <status>: <GitHub's message>`, followed by each `errors[].message` joined with `: ` (bl-68), cut at 200 characters |
| `Malformed` | A body that is not JSON, a list body that is not an array, an issue body that is not an object, a requested issue that cannot be normalized | `tracker_response` | `Malformed issue at <url>` |
| `Pagination` | A next page link on another origin, a repeated link, more than 100 pages, an issue on two pages | `tracker_pagination` | `The tracker pages are inconsistent: <detail>` |
| `RateLimited` | A rate limit answer, or any call while requests are held | `tracker_rate_limited` | `The tracker rate limit is exhausted, requests resume at <time>` |
| `Rejected` | A comment over 65,536 characters, a move to a handoff state with no required label or whose label is a required label | None: no request is made | See [states](#states) and [writes](#writes) |
| `UndefinedState` | A move to a state other than the four defined ones | None: no request is made | `"<state>" is not a state of the tracker. Valid states: open, closed, review, needs_info` |
| `NotFound` | A write to an id that is not an issue number | None: no request is made | `No issue "<id>" in the tracker` |
| `BlankComment` | A comment with nothing in it once trimmed | None: no request is made | `The comment is empty` |

Configuration errors are `GitHubConfigError` with the categories `invalid_tracker_config` and `missing_tracker_secret`, see [configuration](#configuration). Preflight reports them with the category as its code. A `tracker.kind` the build does not know is the host's `invalid_config`, `tracker.kind "<kind>" is not supported, use files or github`.

No message carries the token: errors quote GitHub's `message` field, URLs and header values, never request headers.

## Known gaps

Recorded so later issues close them.

- **No real write run yet.** The writes are tested against responses written by hand after GitHub's documented shapes, since no agent sees a token with write access. The run against the playground repository is bl-40, done by a human.
- **The comment author is the token's account.** GitHub sets it; the author Loop gives a comment (`agent <id>`, `loop`) is not written, so an agent's comment reads as the token owner's in GitHub and under `## Comments`.
- **A human's return is not a human touch.** The ledger records a `human_touch` when an issue is dispatched again after its last attempt left it outside the active states (m2-19). An issue handed off by labels stays `open`, so restoring `ready-for-agent` is not counted.
- **`loop trace` takes the issue number**, such as `loop trace 26`, not the identifier `Hello-World-26`: it looks the issue up by id.
- **Blockers past 100.** The blocked_by list is read in one page of 100. An issue with more blockers stays blocked with `blocked_by <n> unreadable`.
- **Blockers only for candidates.** An issue that is closed or lacks a required label has an empty `blocked_by`, so `blocked_by` alone does not tell an issue with no blocker from one that was not asked.
- **Requests in a refresh are sequential**, one per running issue. With Loop's concurrency limits that is a handful of requests a tick.
- **No conditional requests.** The adapter does not send `If-None-Match`, so an unchanged list still costs its requests against the rate limit.
