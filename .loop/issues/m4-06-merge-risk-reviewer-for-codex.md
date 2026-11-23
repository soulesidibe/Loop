---
id: m4-06
title: "A merge risk reviewer for Codex results, never run in the operator's checkout"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-05]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 106. Lane A, runs on the claude-code runner. Carries bl-145 and bl-100. Touches the auto merge gate: proven on recorded answers and a fake reviewer, since Actions refuses jobs since 2026-11-16.

Area: `riskReviewerFor` in `cli/src/main/kotlin/loop/cli/Host.kt` (around lines 347 to 363), `plugins/runner-claude-code/.../ClaudeCodeRiskReviewer.kt` and its test, the fallback comment in `AutoMergeGate.kt`, preflight, `.claude/skills/merge-risk/SKILL.md`, docs. No orchestrator edit is expected.

What is known:

- **bl-145.** `riskReviewerFor` returns a reviewer only when `runner.kind` is `claude-code`. Under `codex-app-server` it returns null and `AutoMergeGate` falls back with "this Loop has no risk reviewer for its runner", so a Codex result always waits for `loop approve` (bl-63, pull request #113).
- **Decision for this issue,** among bl-145's options (its first option, confirmed by soule on 2026-11-18): the reviewer is a separate concern from the runner that did the work. The workflow already names it (`delivery.risk.reviewer: claude-sonnet-5-5`), so Loop runs the configured reviewer through the Claude Code command (`runners.claude-code.command`, else the default `claude`) whatever `runner.kind` is. A Codex reviewer and a reviewer section of its own are not built. When no reviewer can run (the command is missing at preflight), preflight warns under `policy: auto`, and the fallback comment says that the reviewer setup, not the result, is why the merge waits.
- **bl-100.** During the overnight run of 2026-11-16 a Sonnet merge risk reviewer, started by the operator's session with the `merge-risk` skill, ran `git checkout --detach` and `git checkout main` in the operator's main checkout, which holds the live `WORKFLOW.md` a running Loop reads. Loop's own reviewer already runs with the issue workspace as its directory (`ClaudeCodeRiskReviewer.kt`, `.directory(request.workspace...)`) and without Bash (`WRITE_TOOLS`). Pin both with tests, so no change can move it to the project root. The skill gets the same rule in words: read only, never `git checkout`, `git switch`, `git reset` or `git stash` in the operator's checkout; read with `git diff` and `git show <ref>:<path>`, or work in a separate `git worktree` of the pull request head.

Out of this issue: a Codex native reviewer, a reviewer section in the workflow, the merge risk scoring itself.

## Acceptance criteria

- [ ] With `runner.kind: codex-app-server` and `delivery.risk.reviewer` set, `riskReviewerFor` returns the Claude Code reviewer with the configured model (test).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.DeliveryExecutorsTest'` passes, with the Codex reviewer case.
- [ ] A Codex result under `policy: auto` with every condition held and a low band from a fake reviewer auto merges on recorded answers (orchestrator or CLI test).
- [ ] Under `policy: auto`, preflight warns when the reviewer command cannot be found, and the fallback comment names the reviewer setup as the reason (tests).
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.ClaudeCodeRiskReviewerTest'` passes, with tests that the reviewer's directory is the issue workspace and never the project root, and that Bash stays among the disallowed tools.
- [ ] `grep -qE 'never.*git checkout' .claude/skills/merge-risk/SKILL.md` passes, the line stating that a reviewer never checks out in the operator's checkout and how it reads instead.
- [ ] `docs/spec-decisions.md` says how the reviewer is chosen whatever the runner (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
