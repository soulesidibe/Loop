# Recorded GitHub API responses

Real responses of the GitHub REST API, recorded with curl on 2026-11-12 from the public repository `octocat/Hello-World`, unauthenticated, with `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`. Each `<name>.json` is the body as received and `<name>.headers` keeps the status and the headers the adapter reads (`link`, `x-ratelimit-*`, `retry-after`).

| Name | Request |
|---|---|
| `open-issues-page-1` | `GET /repos/octocat/Hello-World/issues?state=open&per_page=3&sort=created&direction=asc` |
| `open-issues-page-2` | the `rel="next"` link of page 1. Its own `rel="next"` link was cut, so the recording ends at page 2 |
| `issue-26` | `GET /repos/octocat/Hello-World/issues/26` |
| `pull-request-1` | `GET /repos/octocat/Hello-World/issues/1`, a pull request seen through the issues API |
| `issue-not-found` | `GET /repos/octocat/Hello-World/issues/999999999` |
| `repository` | `GET /repos/octocat/Hello-World`, recorded on 2026-11-13 for the gone check of bl-22 |
| `issue-comments-26` | `GET /repos/octocat/Hello-World/issues/26/comments?per_page=100`, recorded on 2026-11-13 for bl-06: four comments, oldest first, one with `\r\n` line ends and one with an emoji |

Written by hand, after the shapes GitHub documents in "Rate limits for the REST API", because they cannot be recorded without exhausting a real limit:

| Name | Response |
|---|---|
| `rate-limited` | the primary limit: 403 with `x-ratelimit-remaining: 0` and `x-ratelimit-reset` |
| `secondary-rate-limited` | a secondary limit: 429 with `retry-after: 60` |

Written by hand for the write side (m2-23), after the shapes GitHub documents in "REST API endpoints for issues", "for labels" and "for issue comments", because no test may write to a real repository and no agent sees a token with write access. The real run against the playground is bl-40:

| Name | Response |
|---|---|
| `add-labels-26` | `POST /repos/octocat/Hello-World/issues/26/labels`: 200 with the issue's labels after the add |
| `remove-label-26` | `DELETE /repos/octocat/Hello-World/issues/26/labels/ready-for-agent`: 200 with the labels left |
| `label-not-found` | the same `DELETE` for a label the issue does not carry: 404 `Label does not exist` |
| `update-issue-26` | `PATCH /repos/octocat/Hello-World/issues/26` with `{"state":"closed"}`: 200 with the issue, closed |
| `create-comment-26` | `POST /repos/octocat/Hello-World/issues/26/comments`: 201 with the comment |
| `create-issue-27` | `POST /repos/octocat/Hello-World/issues` with a title, a body and the label `needs-triage`: 201 with the new issue 27, open, by the token's account (m4-33) |
| `write-forbidden` | a write with a read only token: 403 `Resource not accessible by personal access token` |
| `validation-failed` | a write GitHub refuses: 422 `Validation Failed` |

Written by hand for the CI wait (m3-15), after the shapes GitHub documents in "REST API endpoints for workflow runs", because the org's Actions minutes are spent until December 1 and no agent sees a token:

| Name | Response |
|---|---|
| `actions-runs` | `GET /repos/octocat/Hello-World/actions/runs?head_sha=<sha>&per_page=100`: 200 with three runs of one head, `build` on `push` twice (run 41, a second attempt, `success`, and run 40, `failure`) and `lint` on `pull_request` (run 12, `in_progress`), each with an empty `pull_requests` |
| `actions-forbidden` | the same listing with a token that lacks Actions read: 403 `Resource not accessible by personal access token` with `x-accepted-github-permissions: actions=read` |

Recorded for bl-68 on 2026-11-20 from the playground, `owner/loop-playground`, with its token, the headers kept to the status and the rate limit set of the hand written ones:

| Name | Response |
|---|---|
| `issue-closed-by-another-request` | `PATCH /repos/<repo>/issues/<n>` with `{"state":"closed"}` sent one second after GitHub merged a pull request whose body ends `Closes #<n>`: 422 `Validation Failed`, `This issue was closed by another request. Reload it and try again.` A read right after shows the issue closed |

Recorded for m4-07 (bl-98) on 2026-11-21 in m4-00, with `gh api -i`, from an archived private repository, its owner and name since replaced by `owner/repo` in the bodies and below, where GitHub refused to start run 43779352010 for a billing reason on 2026-11-16. Each `<name>.json` is the body as received, with every email address replaced by one at `example.com` (the commit author `loop@localhost` became `loop@example.com`), and `<name>.headers` keeps the status and the `x-ratelimit-*` headers. Never edit them by hand:

| Name | Request |
|---|---|
| `actions-run-43779352010` | `GET /repos/owner/repo/actions/runs/43779352010`: 200, a completed run with conclusion `failure` |
| `actions-run-43779352010-jobs` | `GET /repos/owner/repo/actions/runs/43779352010/jobs`: 200, its one job, `failure` with zero steps |
| `actions-runs-43779352010` | `GET /repos/owner/repo/actions/runs?head_sha=1c495c0359f1fb41aef31782e7221ff699f6cb8c&per_page=100`: 200, the listing the CI wait reads, with run 43779352010 alone, the same run as `actions-run-43779352010` |

Written by hand for the `merge_conflict` reaction (m4-34), after the shape GitHub documents in "REST API endpoints for pull requests" for `GET /repos/<owner>/<repo>/pulls/<number>`, because a conflicting pull request cannot be recorded from a repository no test may write to. Only the fields Loop reads and their neighbours are kept:

| Name | Response |
|---|---|
| `pull-request-7-dirty` | pull request 7, open, `mergeable` `false` and `mergeable_state` `dirty`: it conflicts with its base |
| `pull-request-7-unknown` | the same pull request while GitHub computes it: `mergeable` `null` and `mergeable_state` `unknown` |

Written by hand for the `ci_failed` reaction (m4-35), after the shapes GitHub documents in "REST API endpoints for workflow runs" and "for workflow jobs", because GitHub Actions refuses every job of Loop's repositories for billing since 2026-11-16, so no red run with steps can be recorded:

| Name | Response |
|---|---|
| `actions-runs-red` | `GET /repos/octocat/Hello-World/actions/runs?head_sha=<sha>&per_page=100`: 200 with two completed runs of one head, `build` on `push` (run 30433700, `failure`) and `lint` on `pull_request` (run 30433701, `success`) |
| `actions-run-30433700-jobs` | `GET /repos/octocat/Hello-World/actions/runs/30433700/jobs?per_page=100`: 200 with two jobs, `test` (`failure`, its step `Run ./gradlew test` failed and the next one skipped) and `assemble` (`success`) |

Written by hand for the `changes_requested` reaction (m4-36), after the shapes GitHub documents in "REST API endpoints for pull request reviews" and "for pull request review comments", because no review can be left on a repository no test may write to. The head of `pull-request-7-dirty`, `6dcb09b5b578`, is pull request 7's current head:

| Name | Response |
|---|---|
| `pull-request-7-reviews` | `GET /repos/octocat/Hello-World/pulls/7/reviews?per_page=100`: 200 with five reviews by collaborators, oldest first: 80 `COMMENTED` by octocat on the older head `e5bd3914e2e5`, 81 `CHANGES_REQUESTED` by monalisa on that older head, 82 `CHANGES_REQUESTED` by octocat on the current head, 83 `APPROVED` by hubot on the current head, 84 `COMMENTED` by octocat on the current head |
| `pull-request-7-review-comments` | `GET /repos/octocat/Hello-World/pulls/7/comments?sort=created&direction=desc&per_page=100`: 200 with three review comments by collaborators, newest first: 1003 and 1002 of review 82 on `src/main/kotlin/Lists.kt`, an outdated one with a null `line` and `original_line` 30 and one on line 12, then 1001 of review 81 on `README.md` line 3 |
| `reviews-forbidden` | the reviews listing with a token that lacks pull requests read: 403 `Resource not accessible by personal access token` with `x-accepted-github-permissions: pull_requests=read` |

Written by hand for the `approved_green` reaction (m4-37), after the same shapes, because no review can be left on a repository no test may write to and GitHub Actions refuses every job of Loop's repositories for billing since 2026-11-16, so no green run can be recorded. Both are on pull request 7's current head, `6dcb09b5b578`:

| Name | Response |
|---|---|
| `pull-request-7-reviews-approved` | `GET /repos/octocat/Hello-World/pulls/7/reviews?per_page=100`: 200 with five reviews by collaborators, oldest first: 80 `COMMENTED` by octocat and 81 `CHANGES_REQUESTED` by monalisa on the older head `e5bd3914e2e5`, then on the current head 83 `APPROVED` by hubot, 85 `APPROVED` by monalisa, which replaces her change request, and 86 `COMMENTED` by octocat |
| `actions-runs-green` | `GET /repos/octocat/Hello-World/actions/runs?head_sha=<sha>&per_page=100`: 200 with two completed runs of the current head, `build` on `push` (run 30433710, `success`) and `lint` on `pull_request` (run 30433711, `success`) |
