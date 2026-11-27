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
