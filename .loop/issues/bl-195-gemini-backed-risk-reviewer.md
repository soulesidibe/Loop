---
id: bl-195
title: "Gemini-backed merge risk reviewer"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Follow-up from `.loop/specs/gemini-support.md` (merge risk reviewer decision).

## What

Today `riskReviewerFor` in `cli/src/main/kotlin/loop/cli/Host.kt` always runs the merge risk assessment through `ClaudeCodeRiskReviewer`, whatever `runner.kind` did the work (`m4-06`). When running issues on `runner.kind: acp` (`preset: agy`) under `delivery.policy: auto` with `pull_request.merge`, Claude Code must still be installed for the reviewer.

Add a Gemini-backed `RiskReviewer` (using `agy` in read-only mode or `acp` with read-only permission gating and holdout denial) so workflows running on Claude Code or Codex can use a Gemini model as their independent merge risk reviewer, and workflows on machines without Claude Code can run auto-merge risk assessments with Gemini.

Area: `cli/src/main/kotlin/loop/cli/Host.kt`, `plugins/runner-acp/`, `plugin-api/src/main/kotlin/loop/api/RiskReviewer.kt`.

## Acceptance criteria

- [ ] A Gemini-backed `RiskReviewer` runs read-only merge risk assessments with holdout denial and returns the structured five-dimension risk verdict expected by `MergeRiskAssessment`.
- [ ] `Host.riskReviewerFor` selects the Gemini reviewer when `kinds.code.delivery.risk.reviewer` names a `gemini-` model, while keeping `ClaudeCodeRiskReviewer` for Claude models.
- [ ] Focused unit and CLI tests plus `./gradlew --no-daemon ktlintCheck` pass.

## Comments

2026-11-24 gemini-support:

Filed from `.loop/specs/gemini-support.md` item 8.
