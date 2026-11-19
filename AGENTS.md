# Loop

Loop is an issue execution harness written in Kotlin. The plan is in `docs/PLAN.md`, the glossary in `CONTEXT.md`, the decisions in `docs/adr/`.

## Agent skills

### Issue tracker

Issues live as local files in Loop's own `files` tracker, `.loop/issues/`, and specs in `.loop/specs/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels, in each issue's `labels` front matter field. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the root and `docs/adr/`. See `docs/agents/domain.md`.
