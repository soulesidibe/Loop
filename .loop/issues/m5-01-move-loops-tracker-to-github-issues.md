---
id: m5-01
title: "Human: move Loop's own tracker to GitHub issues, the backlog kept in files"
state: todo
priority: 1
labels: [ready-for-human]
blocked_by: [m5-00, bl-266, bl-273]
---

## Parent

[Loop, permissions hardening (M5)](../specs/loop-m5.md)

## What

Lane: human. The switch that moves Loop's own tracker to GitHub issues before the rest of M5 is written (m4-45, 2026-11-28). Every step edits a protected path (`WORKFLOW.md`), the GitHub repository's settings, or Loop's running state, so soule does it.

Why: branch protection on Loop's `main` will refuse every direct push, the owner's included (the owner's choice 2 of 2026-11-28). With the `files` tracker, every tracker note and issue file would then reach `main` through a pull request. With GitHub issues, the tracker state lives off the code branch. Specs stay in `.loop/specs/`.

Why the three blockers:

- **m5-00** lets `acceptance_commands` run on a GitHub issue, so the M5 issues keep their runnable criteria.
- **bl-266** lets the `code` kind name a terminal state per tracker (`closed` on GitHub, `done` in files). Only `code` may deliver a pull request, so one kind per tracker would not load.
- **bl-273** lets `loop resume` accept a changed list for an issue of the files tracker once it is no longer the primary.

**The files tracker keeps the id `default`.** Store rows have been written as `default:<id>` since m4-12 ("Namespaced issue ids", `docs/spec-decisions.md`). With the id kept, nothing is orphaned:

- frozen acceptance lists;
- waits and claims;
- the ledger;
- `loop trace default:bl-nn`.

A backlog issue's workspace key and branch change from `loop/<identifier>` to the extra tracker's form (`loop/default_bl-nn-<hash>`, `NamespacedId.workspaceKey`). So no backlog issue may hold a kept workspace or an open pull request at the switch.

Steps, in this order:

1. **Settle what is in flight.**
   - bl-13, bl-47 and bl-64 (`needs_info`) are answered, parked or closed, so none keeps a workspace or branch.
   - Stop Loop's run with no claimed, waiting or queued issue and no open Loop pull request. `loop status` shows nothing in flight.
2. **Reinstall first.** Install Loop from a `main` that holds m5-00, bl-266 and bl-273, before editing `WORKFLOW.md`. This is the reinstall lesson: a workflow edit that needs a newer build stops dispatch otherwise.
3. **Labels on Loop's GitHub repository:**
   - The triage labels `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix` and `deferred`.
   - The handoff label `needs-review`. `needs-info` doubles as the `needs_info` handoff label, the tracker's default.
   - Priority labels `priority:1` to `priority:4`, so a GitHub issue sorts as a `files` issue does: an issue with no priority sorts last (`SlotPlan.kt`, around line 116), behind the ready backlog.
   - Only the owner applies labels, so `ready-for-agent` stays the dispatch gate.
4. **`WORKFLOW.md`, the trackers:**
   - The primary `tracker:` becomes `kind: github` with `id: gh`, the repository, and `required_labels: [ready-for-agent]`.
   - `trusted_associations: [OWNER]`, the narrowest list.
   - The token as a `$NAME` secret, the same variable as the pull request token (`LOOP_PR_TOKEN`). One off commands need it in their environment until the Keychain slice (m5-13).
   - The `files` tracker becomes an entry of `trackers:` with `id: default`, `provider.dir: .loop/issues`, today's states and `required_labels: [ready-for-agent]`.
   - Check that the current token has issues write; m5-02's fine grained token will.
5. **`WORKFLOW.md`, the `code` kind.** `on_delivered.move_to: { gh: closed, default: done }` (bl-266), `acceptance_commands` kept. The prompt changes:
   - It names "the branch Loop checked out (`git branch --show-current`)" instead of `loop/{{ issue.identifier }}`, since a backlog issue's branch is no longer `loop/<identifier>`.
   - It keeps the `.loop/issues/` rule for every issue.
   - It says a GitHub issue names its parent spec under `## Parent` with a path, as a file issue does.
   - A GitHub issue delivers with `Closes #n`, a file issue with `Refs <identifier>`, as today.
6. **`OwnWorkflowTest` and the golden prompt copy** (`spec-core/src/test/resources/prompt/own-template.md`) follow the new workflow. The test gives the tracker token the way it gives the pull request token today.
7. **Docs.** `docs/agents/issue-tracker.md` describes the two trackers:
   - New issues, backlog ones included, go on GitHub. Milestone issues carry the `m<n>-<nn>:` prefix in the title.
   - Native "blocked by" links replace `blocked_by`, and a column 0 `Area:` line replaces `area`. A malformed `Area:` line is ignored with a warning and the issue runs with no area, so check it.
   - A `Blocked by default:bl-nn` line waits for a file issue.
   - The return path: a human restores `ready-for-agent` and removes the handoff label.
   - The no private names rule applies to every issue body and comment before it is posted.
   - The `bl-` backlog left in files only shrinks.

   `docs/agents/triage-labels.md` says the labels are GitHub labels for `gh` and front matter labels for `default`. `AGENTS.md` points at both.
8. **First run on the new tracker.** Restart Loop's run and confirm in `loop status` that both trackers are polled and no issue is skipped as malformed.
9. **The M5 issues.** m5-02 to m5-36 are created on GitHub from the spec's "Slicing" section (the m4-45 session does it). Each title opens with its id and each has its `priority:` label. Blockers are native links and a `Blocked by default:bl-nn` line where needed. Every issue starts with `needs-triage` until the Fable 5.2 review of the spec and the issues passes.

Out of this issue: moving the open backlog to GitHub, and the Keychain token (m5-13).

## Acceptance criteria

- [ ] bl-13, bl-47 and bl-64 hold no kept workspace or branch, and the run was drained before the switch, with `loop status` showing nothing in flight (judgment, output in a comment here).
- [ ] The reinstall came before the `WORKFLOW.md` edit (judgment, noted in a comment here).
- [ ] The labels exist on Loop's GitHub repository (judgment, `gh label list` output in a comment here).
- [ ] `grep -qE '^  kind: github' WORKFLOW.md && grep -qE 'id: default' WORKFLOW.md` passes.
- [ ] `grep -q 'default: done' WORKFLOW.md` passes.
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.OwnWorkflowTest'` passes.
- [ ] `grep -q 'default:bl-' docs/agents/issue-tracker.md` passes.
- [ ] `docs/agents/issue-tracker.md`, `docs/agents/triage-labels.md` and `AGENTS.md` describe the two trackers (judgment).
- [ ] The restarted run polls both trackers, with the `loop status` output in a comment here (judgment).
- [ ] m5-02 to m5-36 exist on GitHub, linked from a comment on m4-45 (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-28 soule: written in m4-45, after the owner chose to move M5 to GitHub issues first. Revised after the Fable 5.2 review of the spec and the switch issues: the files tracker keeps the id `default`, one `code` kind with a move map, `loop resume` first (bl-273), priority labels.
