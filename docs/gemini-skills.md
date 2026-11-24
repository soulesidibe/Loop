# Gemini skills and project instructions

Loop stays runner agnostic: it renders `WORKFLOW.md` and lets the runtime load whatever skills exist in the environment or the workspace. This page covers the setup that lives outside Loop's code when a project runs Gemini CLI next to Claude Code and Codex: sharing `SKILL.md` skills, sharing project instructions, adapting Claude specific skills, and where enforcement belongs.

Written 2026-11-24 for the spec [`.loop/specs/gemini-support.md`](../.loop/specs/gemini-support.md), section out of scope. Terms follow [`CONTEXT.md`](../CONTEXT.md). Loop has no Gemini runner yet. Statements marked "pending Spike 0" wait on the spike in that spec and are corrected there once it runs.

## Contents

1. [Shared skills](#shared-skills)
2. [Shared project instructions](#shared-project-instructions)
3. [Claude specific skills](#claude-specific-skills)
4. [Enforcement stays in Loop](#enforcement-stays-in-loop)

## Shared skills

Gemini CLI 0.42.0 supports the Agent Skills standard (`SKILL.md` with YAML front matter) through `gemini skills` (`list`, `enable`, `disable`, `link`, `install`) and discovers skills in:

- **Project:** `.agents/skills/` and `.gemini/skills/`
- **Global:** `~/.agents/skills/` and `~/.gemini/skills/`

One copy serves Claude Code, Codex and Gemini:

- Portable skills live under `.agents/skills/<name>/SKILL.md` in a repository, or `~/.agents/skills/<name>/SKILL.md` globally.
- An existing Claude skill is linked into Gemini without a copy:

```bash
gemini skills link ~/.claude/skills/<name>
```

- Loop's Gemini runners will pass `--skip-trust` to issue workspaces. Whether that makes Gemini load the workspace `.agents/skills/` and `.gemini/skills/` on its own is pending Spike 0 (probe 6).

## Shared project instructions

A repository that keeps its instructions in `AGENTS.md`, with a one line `CLAUDE.md` (`@AGENTS.md`) for Claude Code, adds a matching one line `GEMINI.md` at its root:

```markdown
@./AGENTS.md
```

Gemini can also read `AGENTS.md` directly through its context file name setting in `.gemini/settings.json`. The key is `context.fileName` in current Gemini CLI settings and `contextFileName` in older ones; which one 0.42.0 reads is pending Spike 0. The one line `GEMINI.md` needs no settings file and avoids drift between runtimes.

## Claude specific skills

Skills written for Claude Code often rely on three Claude specific features that need adjusting to work on Gemini and Codex:

- **Slash invocation (`/tdd`, `/issue`):** headless and ACP runs do not expand slash commands like the interactive TUI. The `WORKFLOW.md` prompt template names the skill instead, so Gemini activates it through its skill tool or reads its `SKILL.md` directly.
- **Hardcoded tool names:** Claude specific names (`Bash`, `Edit`, `Read`, `mcp__loop__move`) become runtime neutral phrasing, for example "use Loop's `move`, `comment` and `submit_result` MCP tools when available, else `loop tool move` and `loop tool comment`".
- **Subagent delegation (`Agent` tool, `context: fork`, `SendMessage`):** skills that fan out to parallel Claude subagents (such as expert reviewers in an issue skill) do not behave the same on Gemini CLI. Until Loop's Koog manager and expert agents land in M6 (`docs/PLAN.md`), either:
  - flatten those subagent steps into sequential single agent phases, portable across Claude Code, Codex and Gemini, or
  - keep a thin Gemini variant only where the fan out is essential.

## Enforcement stays in Loop

Claude specific skill front matter such as `allowed-tools`, and Claude Code hooks, are not expected to have any effect on Gemini CLI (pending Spike 0). Nothing a skill declares is relied on for safety:

- **Guardrails and verification** (blocking `git push`, `gh`, history rewrites, `loop approve`, `loop resume` and holdout reads) belong in Loop's runners (the generated `--admin-policy` file and ACP `session/request_permission` answers) and in `kinds.*.verify`, never in a skill.
- **Mandatory workflow rules** (progress file, evidence files, writing rules, handoff steps) belong in the `WORKFLOW.md` prompt template, not in an optional skill a model might never activate.
