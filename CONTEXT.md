# Loop

Loop is a harness that takes issues that are ready for an agent from one or more trackers, has an agent work each one in isolation, verifies the result with checks the agent did not write, and delivers it only when the evidence holds.

## Language

**Issue**:
A unit of work read from a tracker, normalized to one shape whatever the tracker. It can be code or any other work an agent can do and someone can check.
_Avoid_: Ticket, task, job

**Kind**:
The category an issue belongs to (`code`, `content`, `research`, `ops`, or a custom one), which decides how it is worked, verified and delivered.
_Avoid_: Ticket kind, issue type

**Run**:
One working period of the Loop service, started by launching Loop. A run can span several process restarts when Loop is relaunched with its id; every attempt belongs to exactly one run. A run only groups what happened for tracing; it holds no issue state.
_Avoid_: Session (for this meaning), execution

**Project**:
One `WORKFLOW.md` and the state Loop keeps for it: claims, waiting and parked issues, pending approvals, budgets and the ledger. Project state survives every restart, whatever the run.
_Avoid_: Repo, workspace (for this meaning)

**Attempt**:
One execution of an agent on one issue, inside a run. Retries and continuations create new attempts.
_Avoid_: Run, run attempt, job

**Attempt directory**:
The folder Loop keeps for one attempt outside its workspace, `<LOOP_HOME>/attempts/<project_id>/<attempt_id>/`, holding the tool config snapshot and the queued tracker requests. Deleted when the attempt ends.
_Avoid_: Attempt folder, attempt workspace

**Attempt file**:
The gzip compressed JSONL file Loop keeps for one attempt beside its attempt directory, `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.jsonl.gz`, one record per line with its `type`, sequence number and timestamp, redacted before writing. It holds the turn 1 rendered prompt that `loop show --prompt` prints and every runner line in full, which `loop show --events` prints in place of the cut previews of the store. Kept when the attempt ends, until `observability.retention_days` (90 by default) after its last write.
_Avoid_: Attempt log, transcript

**Human log**:
The plain sentence log of a Project, `<LOOP_HOME>/logs/<project_id>.log`, appended by every run next to the `key=value` operator log on stderr: one line per thing a person wants to follow (run start and end, polls that change something, attempts, turns with tokens and spend, moves, parks, retries, failed hooks, token thresholds, and the agent's reads, edits and commands). Redacted before writing. `loop log` prints it.
_Avoid_: Run log (the operator log is a log of the run too), activity log, transcript

**Config version**:
The SHA-256 of a `WORKFLOW.md` text. Every reload records the new and the previous one, and every attempt records the one it started with, which it keeps to its end. `loop trace` shows its first 8 characters.
_Avoid_: Config hash, workflow version

**Tool config snapshot**:
The secret free config of one attempt that Loop writes in the attempt directory and points to with `LOOP_TOOL_CONFIG`. `loop tool` reads it instead of `WORKFLOW.md`. Its error messages call it the attempt config.
_Avoid_: Tool config (on its own), workflow snapshot

**Tracker tools**:
The two tools an agent writes to its issue with, `move` and `comment`: `loop tool move <state>` and `loop tool comment <text>` on the command line, and since m2-17 the `move` and `comment` tools of Loop's MCP server, which Claude Code names `mcp__loop__move` and `mcp__loop__comment`. Both ways share one validation and queue the same request, with the same `issue_move_requested` or `issue_comment_requested` event and no `source`; Loop applies it host side when the turn ends. The command line stays for runners without MCP.
_Avoid_: Tracker write (for the request), MCP move (as if it were another request)

**Parked**:
An issue Loop will not dispatch again until a human resumes it or its tracker state changes, for example after too many failed attempts, after passing a spend budget, after too many attempts without progress, or after an `unverified` verdict.
_Avoid_: Stuck, failed, blocked

**Waiting**:
An issue Loop will not dispatch, whatever its tracker state, while it waits for something, written `Waiting(reason)`. So far the reasons are `verify`, the checks of the result the agent submitted are running, `approval`, a verified result waits for `loop approve` under the `human` delivery policy, and `merge`, an approved pull request waits for its merge check, its CI under `ci: actions` (m3-15) and its merge (m2-26). The wait is kept in the store, so a check interrupted by a restart runs again when Loop starts, an approval wait survives every restart, and a merge wait resumes in the phase it stored, without a second pull request, a second merge or a lost approval.
_Avoid_: Pending, blocked, in review

**Token threshold**:
A step of the attempt's tokens, every `budgets.token_threshold_every` (10 million by default), counted like the per attempt token budget. Crossing one records a `token_threshold` event, logs it and runs `hooks.on_token_threshold`; the attempt goes on.
_Avoid_: Warning, budget

**Spend budget**:
An opt in limit on what attempts spend, in tokens (the latest absolute totals, cache reads included) or dollars (the runner's reported cost), per attempt, per issue or per project in the last 24 hours, read from the ledger: `budgets.per_attempt_tokens`, `per_attempt_usd`, `per_issue_tokens`, `per_issue_usd`, `per_day_tokens` and `per_day_usd`, with no default. At 80 percent the attempt warns once per budget (`budget_warning`, a log line, `hooks.on_budget_warning`); past the limit it stops and the issue is parked with `budget`, naming the budget. The per attempt token budget was the token cap until m2-20. Distinct from context occupancy and the prompt budget.
_Avoid_: Token cap (the old name of the per attempt token budget), context limit

**Delivery policy**:
How a kind delivers a `verified` result, set by `delivery.policy`: `human` (the default) posts the evidence summary on the issue and waits as `Waiting(approval)` for `loop approve`, `auto` delivers at once. Delivering is the move to the handoff state, preceded by a pull request delivery when the kind sets `delivery.pull_request`. Since m2-28 `auto` needs the opt in `delivery.auto_merge.enabled`, and with a merge it is conditional auto merge: every auto merge condition and a low merge risk band, else it falls back to `human`.
_Avoid_: Merge policy, approval mode

**Pull request delivery**:
The delivery of a verified result as a pull request, opt in per kind with `delivery.pull_request` (m2-25): Loop looks up the pull request of `loop/<workspace key>` in every state, pushes the verified commit to that branch host side with its own token, opens or updates the pull request whose body is built from the verification's facts, comments its URL on the issue, then, as a last optional step, moves the issue. Each step is separate and idempotent; a pull request already merged at the verified commit counts as delivered. A step refused for good records `delivery_failed` and the result waits for a new approval. With it, a verified work tree that holds uncommitted or untracked files is refuted at verification, since a push carries only commits, and a work tree clean before the checks is restored to its commit after them, so a file a check writes is never blamed on the agent. The push goes to the explicit https URL of the repository from a temporary repository Loop owns, so no config an agent can write in the project repository steers it. It merges only when the kind also sets `delivery.pull_request.merge`, after a merge check.
_Avoid_: PR flow, publish, ship

**Merge check**:
What Loop does before it merges an approved pull request, opt in per kind with `delivery.pull_request.merge` (m2-26): it fetches the tip of the base host side into `refs/loop/base/<base>`, merges that commit into the branch in the issue's own workspace unless it is already there, and reruns every check and holdout on the result, `diff_policy` against the fetched base; when the base is already there, only `diff_policy` reruns, against it (bl-32). The merged head then becomes the delivered commit: it is pushed unforced, its GitHub Actions runs are waited for when the kind sets `ci: actions` (m3-15), and it is merged with a sha guard, once the base tip read right before the merge is still the one checked (at most 3 rounds). A conflict or a failing recheck merges nothing, comments the reason and sends the issue back to its agent with findings, and every verification of a pull request kind bases `diff_policy` on a base it fetched itself (bl-32), so commits that came from it are never the agent's diff and a moved local ref hides nothing. A stop in the middle of the check is resumed from the base it stored before merging. Every git command of the check runs with hooks, fsmonitor, signature checks, gpg programs and automatic maintenance off, a minimal environment, no token and a timeout.
_Avoid_: Rebase check, pre merge CI

**Auto merge condition**:
One hard veto of conditional auto merge (m2-28, story 100), read right before the merge of a pull request the `auto` policy approved: `verified`, `merge_check`, `ci`, `no_lost_tests`, `protected_paths`, `diff_size` and `diff_base_pinned`. Any one failing falls back to the `human` policy, the comment naming it.
_Avoid_: Merge gate (for one condition), rule, check (a check is part of the verify contract)

**Merge risk assessment**:
What follows the auto merge conditions (m2-28): facts computed in code set floors, a read only reviewer session gives one verdict per dimension (alignment, impact, verification, rollback cost, error detection), and Loop computes the weighted score and its band, low, medium or high. Auto merge needs a low band and no dimension at its worst verdict; a reviewer that fails falls back. Recorded as `merge_risk_assessed`.
_Avoid_: Risk score (the score is one part), review (for the whole assessment)

**Evidence summary**:
The comment Loop posts, as `loop`, on an issue whose verified result waits for approval: the attempt, the workspace fingerprint and each check that passed with its exit code, duration and evidence file, a holdout check saying only that it passed. Built from the verification's facts, never from the agent's words.
_Avoid_: Report, verification comment

**Handoff state**:
The tracker state an issue moves to when the agent's work is ready for someone else to look at, for example `Human Review`. On the GitHub tracker, `review` and `needs_info` are made of labels: the issue stays `open`, gains its handoff label and loses its required labels.
_Avoid_: Handoff (on its own), review state

**Check**:
One test of a kind's verify contract that Loop runs itself after the agent submits its result, for example a `command` run in the workspace, which passes on exit 0. The agent never writes or runs it for Loop.
_Avoid_: Test (on its own), validation, gate

**Holdout check**:
A check of a kind's `holdout:` list that the agent never sees: its script lives in the holdout folder, `<LOOP_HOME>/holdout/`, outside every workspace and the repository, and runs with `LOOP_HOLDOUT` set to that folder. Loop runs it after every visible check passed and counts it in the verdict like them. Its output, exit code and duration are never saved, its records say only that it passed, failed or could not run, a failure tells the agent only that a hidden check failed, and the Claude Code settings Loop generates deny reads and edits under the folder. This is a guardrail, not a sandbox: the agent runs as the same OS user.
_Avoid_: Hidden test, secret check, private check

**Readiness check**:
The test a kind's `ready:` contract makes in code before dispatch, in Loop mode: the issue's description must have every required section, such as `## Acceptance criteria`, with content. An issue that fails it is not dispatched; Loop comments once and adds the `needs-info` label.
_Avoid_: Triage (the judgment a triage agent adds on top), validation

**Verdict**:
What verification concludes about a submitted result from its checks: `verified` (every check passed, the only verdict that is delivered), `refuted` (a check failed) or `unverified` (no check, or a check could not run).
_Avoid_: Result, status, outcome

**Judgment call**:
An acceptance criterion with no command, which no check can pass. When a kind runs the commands of the acceptance criteria (`acceptance_commands`), Loop lists the judgment calls in the evidence for a human and never counts them as passed.
_Avoid_: Manual check, prose criterion

**Frozen command list**:
The acceptance commands of an Issue as Loop read them at its first dispatch, kept in the store as a digest and a count. Later attempts and restored waits verify against it: criteria that hold other commands are never run, the Issue parks `unverified` until `loop resume` accepts the new list.
_Avoid_: Command cache, snapshot

**Evidence**:
The saved full output of one check run, stdout and stderr merged as written, in a file beside the attempt file, `<LOOP_HOME>/attempts/<project_id>/<attempt_id>.evidence-<n>.log`. A header stamps it with the check, its command, exit code, duration and the workspace fingerprint, and the whole file is redacted before writing. Each verdict names the evidence it rests on; `loop show --evidence` prints it.
_Avoid_: Log, check output (for the saved file), proof

**Ledger**:
The measure of a Project's work: one JSONL line per turn in `<LOOP_HOME>/ledger/<project_id>.jsonl` and one row per finished attempt in the store, each with the tracker, kind, runner, model, template version, tokens, cost and wall time, and for the attempt its checks, guards, verdict and human touches. A measure nobody could report is null, never zero. Tokens come from the runner's absolute totals, never summed twice.
_Avoid_: Metrics, usage log, report (the report reads the ledger)

**Human touch**:
A moment a human had to act on an issue: `loop resume`, `loop approve`, or a change of the issue in the tracker that Loop sees (a move that unparks it, stops its attempt, wins over a verified move, or sends it back to the active states). Recorded as a `human_touch` event and counted once, in the next ledger row of its issue.
_Avoid_: Intervention, manual step
**Result file**:
The result an agent submits, in the PLAN section 7 shape, written to `.loop/result.json` in its workspace by a runner without tools. In Loop mode Loop removes a stale one at each attempt start, takes it once after a turn, saves it beside the evidence as `<attempt_id>.result-<n>.json` and acts on its `status`: `done` is verified like an intercepted move, `needs_info` asks the question, `blocked` and `failed` fail the attempt. Claude Code submits the same result with the `submit_result` tool of Loop's MCP server (`loop tool mcp`), which Loop queues and takes on the same path, with `source` = `mcp`.
_Avoid_: Output file, result.json (on its own)

**Workspace fingerprint**:
A short value that identifies the state of a workspace when a verification starts: for a git work tree, `git:<HEAD>:<diff hash>:<untracked hash>`, the commit, a hash of the uncommitted changes against it and a hash of the untracked files git does not ignore; for any other folder, `tree:<hash>` of every file. The result file `.loop/result.json` at the top of the workspace is never part of it; every other file under `.loop/` is. The same state always gives the same value and one changed file changes it.
_Avoid_: Checksum, snapshot, workspace hash

**Context source**:
A path in the workspace that a kind declares under `context.files` for its issues, such as a brand voice file or a folder of published posts. The prompt lists it as a pointer where the template places `loop.context_sources`, and the agent reads it on demand; its text is never inlined.
_Avoid_: Context file, attachment

**Prompt slot**:
One part of a rendered prompt, in the plan's priority order: the template and its rules, the issue, verify findings, the session brief, lessons and context sources. The size of each is recorded with every rendered prompt (m1-17); from M3 the slots fill the prompt budget in that order.
_Avoid_: Prompt section, prompt part

**Session brief**:
What a new agent session receives when it continues an attempt after a rotation: the goal, the workspace state, check results, counters and the agent's own notes, built from facts.
_Avoid_: Handoff, summary

**Secret**:
A credential value Loop knows from the host environment (a `$NAME` under `tracker.provider.*` other than a path, the `$NAME` of `delivery.pull_request.token`, or an Anthropic key), or a value matching a token pattern. Loop replaces it with a marker before writing anything. Paths are not secrets.
_Avoid_: Env ref (a `$NAME` reference, which can also be a path)
