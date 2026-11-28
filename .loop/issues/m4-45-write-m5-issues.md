---
id: m4-45
title: "Write the M5 issues"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: [m4-44]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Like m3-29: the M5 issues are written from the plan and from what M4 taught, after updating the spec where M4 proved it wrong. M4 closes with this issue.

- **M5 is permissions hardening** (PLAN 11.2 and 17 M5): a sandbox for every worker in local mode, a worker token that cannot merge, branch protection, an audit after every attempt, approvals in the middle of a run. bl-28 is its seed, deferred to M5 in m3-29.
- **Carrying open M4 issues:** each open M4 issue is carried into M5 or closed, with its reason in a comment on it. Likely candidates, since they do not gate the exit: reactions (m4-34 to m4-37), observability (m4-38 to m4-42, m4-54) and the Android provider (m4-28).
- **Backlog:** the `deferred` issues waiting for M4 to close (bl-14, bl-116, bl-118 for check types; bl-60, bl-90, bl-91, bl-97 once Actions billing returns; bl-151) each get a keep, schedule or close line, as m3-29 did.
- **A gap left by M4:** PLAN 6.2 says a kind "sets the runner", and no M4 issue builds it. Kinds with their own runner are left out of M4 by the m3-29 decision, and recorded here as a gap in PLAN 6.2 and 20.
- **Spec:** a new M5 spec in `.loop/specs/`, and the M4 spec updated with what M4 changed, listed in a comment here.

## Acceptance criteria

- [ ] The M4 spec is updated with what M4 changed, and the changes are listed in a comment here.
- [ ] The M5 spec exists in `.loop/specs/`, with stories, implementation decisions and testing decisions.
- [ ] The M5 issues exist in the tracker, sliced vertically, with checkable acceptance criteria, runnable ones where a command checks them, and blockers.
- [ ] Each open M4 issue is carried into M5 or closed, with its reason in a comment on it.
- [ ] Each `deferred` backlog issue waiting for M4 gets a keep, schedule or close line in a comment on it.
- [ ] Kinds with their own runner are recorded as a gap in `docs/PLAN.md` sections 6.2 and 20 (judgment).
- [ ] `docs/PLAN.md` (status line, section 17 M4 and M5, section 19 risks, section 20 open questions) matches (judgment).

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-27 soule: the first M5 run must prove `ask`, `loop answer`, the per tracker limit and the resource tools on Codex, see bl-270 (from m4-44).

2026-11-27 soule: M5 decisions, before the spec.

- Sandbox: a sandbox plugin seam. macOS Seatbelt (`sandbox-exec`, a profile generated per attempt: the workspace writable, home secrets unreadable, a stripped environment) is built in M5; a container plugin comes later, for Linux and cloud mode.
- Worker token: a fine-grained personal access token per project, contents and pull requests write, no admin and no workflow scope, kept in the Keychain. Merging is blocked by branch protection, not by the token alone.
- Branch protection: in M5, on Loop's `main` and on the playground repo, with Loop's own merge path kept working; the M5 exit tests it. Private projects get it when they can.
- Guard model: in M5, as the last slice, after the hard layers (sandbox, token, protection, audit, approvals, deny list).
- Approvals in the middle of a run: denied after 30 minutes without an answer; the agent is told and goes on or ends with `needs_info`, and the notice stays.
- Secrets: agents get an allow listed environment only, hooks get only the `LOOP_` variables and what the workflow names for them, and the token readers (`security`, `gh auth token`, `git credential`) are blocked by the sandbox. Answers the m4-43 security notes.

2026-11-28 soule: the M4 spec (`.loop/specs/loop-m4.md`) is updated with what M4 changed. Each change is a "Changed in M4", "Added in M4", "Found in M4", "Found in the exit" or "Held" note on the existing text; nothing was deleted.

1. The exit wave ran on the second pilot app instead of the pilot app, whose release was in progress: one run delivered three code issues and one research issue (m4-43, spec-decisions "M4 exit wave"). Stories 124 and 140, the exit app decision, the workloads note.
2. "The exit is one wave": held, one run of build 56976ce, never restarted (m4-43).
3. Story 141: the manager skill was retired on 2026-11-27 with `loop doctor` and the M6 rows as gaps; the project copies are bl-271, the issue skill criterion bl-272, the tests only rows bl-270 (m4-44).
4. Story 103: the wake turn follows bl-170 option E, and a subagent's own lines no longer mark a wake (m4-03, bl-122).
5. Story 106: the model named in `risk.reviewer` picks the reviewer's runtime, Claude Code, Codex (bl-214) or `agy` (bl-205).
6. Added `agent.max_concurrent_verifications` (bl-188, spec-decisions "Build gate").
7. Several trackers: a kind's `move_to` is checked against the primary tracker's states (bl-266, from m4-43).
8. Research kind: sections come from `verify.require_sections`, and "sources reachable" is a documented `command` check, not built in (m4-23).
9. Resources: the dirty mark, and `resource_queued` reasons `held`, `limit` and `dirty` (m4-26).
10. Resources: a mutex per lock and a 10 minute core provider timeout (bl-208, in m4-28); the iOS `prepare_timeout_seconds` defaults to 300 (m4-27), raised to 900 in the wave (m4-43).
11. Resources, found in the exit: a failed prepare parks an issue (bl-268), one attempt held two simulators (bl-269).
12. Story 129: `loop.retry_kind` is `sent_back` and the reason comes as `loop.verify_findings` (m4-32, bl-221).
13. Story 133: only trusted author associations count for `changes_requested` (m4-36).
14. Reactions: `send_to_agent` is a retry in the kept workspace through the retry coordinator, the last slot parks the issue (m4-34), the bound survives a restart (bl-224); the glossary term is Reaction.
15. Reactions: `ci_failed` never fires under `ci: none` and warns at load (m4-35); `changes_requested` sends and escalates once per round (m4-36); `approved_green` notifies once per head (m4-37).
16. Found in the exit: generated files must not depend on the workspace path; a permission denial fails the attempt (bl-267); one off commands need the tracker token (bl-264); secrets reach agents and hooks until the M5 sandbox (m4-43, the M5 decisions above).
17. Testing: the iOS provider was proven on the second pilot app; Android stayed out of the exit (m4-43).
18. Testing, the exit: a peak of 0.1691 of a 1,000,000 token window; touches counted by hand beside the ledger, `loop report` saying 1 of 4 delivered with zero touches (m4-43, bl-64).
19. Testing: `ask`, `loop answer`, the per tracker limit and the resource tools on Codex are proven by tests only (m4-44, bl-270).
20. Out of scope, bl-28: clone workspaces were built as an opt in, `workspace.git: clone` (ADR 0002); the default is bl-245, the sandbox stays in M5.
21. Out of scope, runners: `runner-acp` was pulled ahead in `.loop/specs/gemini-support.md` (PLAN 17).

No M4 issue was added mid milestone or canceled: m4-46 to m4-54 were split off in m3-29 and the spec already lists them.

2026-11-28 soule: the M5 spec draft is in `.loop/specs/loop-m5.md` (stories 142 to 165, label `needs-triage` until the Fable review of the spec and the issues). The owner settled the drafter's 16 open choices one by one:

1. Merging under branch protection needs no second token: agents hold no credential, the worker token cannot push to the base or lift the ruleset.
2. No direct push to `main` for anyone once the ruleset is on, no bypass list; every change, tracker notes and issue files too, goes through a pull request.
3. An audit violation parks the issue at once, no retry.
4. The audit also runs on attempts that end without a result.
5. Preflight refuses a workflow with no `sandbox:` block; `kind: none` is the explicit way out.
6. Reads: the system folders readable, the home folder unreadable but for an allow list, the secret paths unreadable even inside it.
7. `loop tool mcp` reaches `loop run` over a Unix socket broker, with the rules of the MCP security guidance (short socket path, one exact Seatbelt rule, a token per attempt, every request treated as hostile).
8. The sandbox requires clone workspaces, and clone becomes the default (bl-245 item 2).
9. Checks, holdouts and hooks run in their own profiles; Loop's own host side git stays outside with its pins.
10. The network allow list goes through a local proxy, in M5 as the last slice before the audit's network part, allowed to slip without blocking the exit.
11. Approvals cover a network host or a command from `approvals.commands`, run by Loop, answered with `loop approve` or `loop deny --request`, at most 3 per attempt, `approvals.timeout_minutes`.
12. New config names: `keychain:<service>`, `agent.env_allow`, `hooks.env_allow`, `sandbox.*`, `approvals.*`, `allow_classic_token`, `guard.model`.
13. Preflight accepts `github_pat_` and `ghs_` tokens only, unless `allow_classic_token: true`.
14. The holdout exit seed runs on Codex too, closing bl-134.
15. The spec stays `needs-triage` until the Fable review passes and the issues are written.
16. PLAN 17 moves the guard model from M9 to M5, with a "moved to M5 (m4-45)" note on M9.
