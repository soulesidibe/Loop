---
id: m4-18
title: "Kind map and matcher, code unchanged, ledger rows hold the kind"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-17]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 118 (PLAN 6.2). Lane A, runs on the claude-code runner. First issue of the kinds family, on the exit path: the exit needs one content or research issue next to the code issues.

Today `KindsConfig` holds one field, `code` (`spec-core/src/main/kotlin/loop/core/workflow/WorkflowConfig.kt`, around line 40), and the parser refuses any other kind name (`WorkflowFile.kt`, around line 469, "kinds.$it is not supported, use code"). About 48 main source lines read `kinds.code` or `kinds?.code`, and about 126 test lines build `KindsConfig(` or read `.code` (in `spec-core`, `cli` and `conformance`). The main lines are most of them in `WorkflowFile.kt`, the rest in `cli/.../Host.kt`, `merge/AutoMergeGate.kt`, `merge/Merges.kt`, `verify/Holdouts.kt`, `workflow/AcceptanceCommandsTrust.kt` and some 16 places in `Orchestrator.kt`. The ledger hard codes the kind: `LedgerMeter.kt`, around lines 128 to 141, writes `"code"` whenever `kinds:` is present.

- `kinds:` becomes a map of named kinds. Each kind holds the slots `code` holds today (`verify`, `ready`, `delivery`) and a `match` entry, as in PLAN 13. The match keys built in M4 are `default` (`match: { default: true }`), `label` (`match: { label: content }`), `tracker` (a tracker id of the registry of m4-11) and `state`. A match on a front matter field waits for a field map in the issue model: `Issue` (`plugin-api/.../Issue.kt`) has none.
- The matcher is a pure function in `loop.core.kinds` (the package already holds `Readiness.kt` and `MarkdownSections.kt`): an issue gets the first kind whose match holds, in the order the kinds are written, and the default kind otherwise. Preflight refuses two defaults, a kind with no match and no default, and an unknown match key.
- `code` is unchanged: a workflow whose only kind is `code` with no `match` behaves exactly as today, `code` being the default. Existing workflows need no edit. `KindsConfig.code` stays as an accessor of the kind named `code`, so existing call sites and tests compile unchanged.
- The resolved kind of each issue reaches the attempt: the orchestrator reads the issue's kind where it reads `kinds.code` today, through one lookup. Code only paths (auto merge, merge check, `pinnedBase`) keep reading the code kind's settings in this issue; an issue of another kind takes them up when its kind lands (m4-20 to m4-23).
- `LOOP_KIND` joins the `LOOP_*` variables of every hook (`workspace/HookEnv.kt`), so a hook can branch on the kind.
- Ledger lines and rows hold the matched kind, not the constant (needed by `loop report` in m4-38).
- Out of this issue: a match on a front matter field, context sources and per kind templates (m4-19), verification without git (m4-20), the `content` and `research` kinds themselves (m4-21 to m4-23), a per kind runner.
- Decided by soule on 2026-11-18: no match on a front matter field in M4.

## Acceptance criteria

- [ ] A table test of the matcher covers default, label, tracker id and state matches, first match wins, and no match falling to the default (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.kinds.KindMatcherTest'` passes, a new test class of the matcher.
- [ ] Preflight refuses two default kinds, a kind with neither `match` nor default, and an unknown match key (a front matter field key included), one test each, and accepts a second kind name next to `code` (tests).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.WorkflowFileTest'` passes, with the new preflight cases.
- [ ] A workflow with only `code` and no `match` parses to the same config as before, and `KindsConfig.code` still compiles at every existing call site (test).
- [ ] `./gradlew --no-daemon :spec-core:test` passes, the existing suites unchanged.
- [ ] A CLI test on a temporary project with two kinds dispatches a labelled issue under the second kind and an unlabelled one under `code`, and the ledger rows of each hold its kind (test).
- [ ] Every hook gets `LOOP_KIND` (test on `hookEnv`).
- [ ] The orchestrator edit is limited to call sites: the matcher and the kind lookup live in `loop.core.kinds`, and `Orchestrator.kt` only calls them (judgment).
- [ ] `docs/spec-decisions.md` and PLAN 6.2 describe the kind map, the matcher order, the match keys and `LOOP_KIND` (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 00:07Z loop: Loop opened pull request #52 for this issue: https://github.com/soulesidibe/Loop/pull/52

2026-11-25 00:10Z loop: Loop did not merge pull request #52 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: partial, Core asks are met, but it adds an unrequested refusal of delivery.pull_request on non-code kinds and edits ApproveCommand.kt, and the diff is cut so the parser and HookEnv changes could not be read.
  - impact: medium (floor medium: the diff changes 512 lines outside tests and docs, over half the limit of 1000), It changes the kind resolution that every attempt, verification and delivery goes through, plus preflight parsing and hook env, but code-only workflows keep their old behavior (floor medium).
  - verification: strong, It adds a matcher table test, preflight cases, a HookEnv test and an end-to-end CLI test that checks per-kind checks, LOOP_KIND and ledger rows.
  - rollback_cost: two_way, A revert undoes it. The ledger rows written with non-code kind names are additive data and only matter to later reports.
  - error_detection: loud, Bad kind configs fail preflight with invalid_config and named keys. The only quiet spot is the matcher falling back to code when no default is found, which preflight prevents.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 6 of 17 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.

2026-11-25 00:30Z soule: sent back before merge, after the merge risk reviews of pull request #52 (Sonnet 5.5 low 1.40, Fable 5.2 medium 1.80). Fix these on the same branch, then submit again:
  - **Medium. Pin the kind on the waits.** The attempt pins the kind at dispatch (`Orchestrator.kt` around line 1635), but `deliverApproved` resolves it again from the live issue (around lines 4865 to 4872), and so do the restored verify wait (around line 5232) and `ApproveCommand.kt` (around line 138). The agent can change the live state itself with `loop tool move` to another active state (`intercepts` around line 2104 only catches moves out of the active states), and a human can move it or edit a label during the wait. With two kinds this skips the pull request (an issue dispatched as `code` resolves to a kind without `pull_request`, gets a plain move recorded as verified and approved, no pull request, no merge check, work stranded on the branch), or forces one (an issue dispatched under a non code kind resolves to `code` and `deliverPullRequest` runs on work never checked by the code kind, bypassing the preflight refusal). Write the kind name on the verify and approval waits at dispatch, resolve by that name at those three places, fall back to the live lookup only for waits stored without the key, and keep the wait (with a warning) when the name left the config. Add a test: two kinds with a state match, the state moved before submit, delivery follows the dispatched kind; and one for a restored verify wait after a restart.
  - **Low. Code only paths read `kinds.code` for any issue** (`Orchestrator.kt` around lines 4103, 4927 and 5097, `AutoMergeGate.kt` around lines 123 and 239). Once the kind is pinned, assert or document that only code issues reach them.
  - **Low. An agent can move its issue to a laxer kind.** With `match.state` (or labels the agent can edit), the next attempt is dispatched under that kind's checks. Document it in `docs/spec-decisions.md` "Kind map and matcher", and consider pinning the kind per issue across attempts or refusing an agent move that changes the kind.
  - **Low. `on_delivered.move_to` terminal state check covers `code` only** (`WorkflowFile.kt` around line 261). Check it for every kind.
  - **Low. A `kinds.code.match` key, ignored before, is now parsed and validated.** Say so in the docs (no workflow in the repo has one).
  - **Low. State matching is case folded.** Keep it consistent with the tracker's `normalized()` state comparison and test it.

2026-11-25 00:32Z agent f600a3ad: Fixed the send back of pull request #52 on the same branch (commits b9bb340, 451440b, 8ee3115).
  - Medium, kind pinned on the waits: the verify and approval waits now hold the kind the issue was dispatched under (wait key `kind`), and the merge wait keeps it. deliverApproved, the restored verify wait and `loop approve` resolve by that name through `KindLookup.pinned`. A wait stored without the key falls back to the live lookup. A wait whose kind left the config is kept, with a warning (once per issue in the log, on stderr for `loop approve`). Tests: KindPinOrchestratorTest (state moved by a human before submit, both directions; restored verify wait pinned, without a name, kind gone; approved wait kind gone), KindLookupTest, WaitsTest, ApproveCommandTest.
  - Low, code only paths: documented in AutoMergeGate, Merges, deliverPullRequest and recheckMerge that only code issues reach them. pinnedBase now reads the issue's kind delivery, like attemptBase.
  - Low, agent move to a laxer kind: an agent move to an active state that would change the issue's kind is refused with tool_request_failed (tested). Documented in "Kind map and matcher".
  - Low: the on_delivered.move_to terminal check now runs for every kind (WorkflowFileTest). The docs say a `kinds.code.match` is now parsed. State matching is trimmed and lowercased like the tracker's states, and both the docs and KindMatcherTest cover it.
  One existing assertion changed: LoopModeWaitingTest now expects `kind=code` in the verify wait data. spec-core has 1700 tests and 0 failures. The focused cli tests, conformance and ktlintCheck are green.

2026-11-25 00:35Z loop: Loop updated pull request #52 for this issue: https://github.com/soulesidibe/Loop/pull/52

2026-11-25 00:38Z loop: Loop did not merge pull request #52 on its own: the delivery policy is auto, but the auto merge needs every condition and a low merge risk band, so it waits for an operator's approval.
  Merge risk: band medium, score 1.40.
  - alignment: partial, Core asks are met, but the diff adds unrequested behavior (refusal of delivery.pull_request on non-code kinds, refusal of agent moves that change the kind, ApproveCommand edits), and 16 of 26 files, including the parser and HookEnv, were cut from the diff and could not be read.
  - impact: medium (floor medium: the diff changes 668 lines outside tests and docs, over half the limit of 1000), The change touches the kind resolution that every attempt, verification and delivery goes through, plus wait data, preflight parsing and hook env, and the diff changes 668 lines outside tests and docs (floor medium), but code-only workflows keep their old behavior.
  - verification: strong, It adds a matcher table test, preflight cases, a HookEnv test, pin tests for moved state and restored waits, approve command tests and an end to end CLI test that checks per kind checks, LOOP_KIND and ledger rows.
  - rollback_cost: two_way, A revert undoes the code, and the extra kind key on stored waits and the non code kind names in ledger rows are additive data that older code ignores.
  - error_detection: loud, Bad kind configs fail preflight with named keys, an agent move that changes the kind is refused with tool_request_failed, and a pinned kind that left the config logs a warning, but the matcher falls back to code quietly when it finds no default, which preflight prevents.
  - band floor medium: the reviewer's diff was cut to fit its prompt limit, 16 of 26 changed files left out in whole or in part
  Once approved, Loop runs the merge check again and merges.
