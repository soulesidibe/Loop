# Gemini skills and project instructions

Loop stays runner agnostic: it renders `WORKFLOW.md` and lets the runtime load whatever skills exist in the environment or the workspace. This page covers the setup that lives outside Loop's code when a project runs Gemini next to Claude Code and Codex: the runtime to use, sharing `SKILL.md` skills, sharing project and global instructions, adapting Claude specific skills, and where enforcement belongs.

Written 2026-11-24 for the spec [`.loop/specs/gemini-support.md`](../.loop/specs/gemini-support.md), section out of scope. Terms follow [`CONTEXT.md`](../CONTEXT.md). Loop has no Gemini runner yet. Statements marked "pending Spike 0" wait on the spike in that spec and are corrected there once it runs.

## Contents

1. [Runtime](#runtime)
2. [Shared skills](#shared-skills)
3. [Shared instructions](#shared-instructions)
4. [Claude specific skills](#claude-specific-skills)
5. [Enforcement stays in Loop](#enforcement-stays-in-loop)

## Runtime

Gemini runs through the Antigravity CLI, `agy` (1.5.14 here). The older Gemini CLI, `gemini`, fails on a personal Google login with `IneligibleTierError` since 2026-11, whatever the model.

`agy models` lists the model ids, each carrying its effort level. Loop's Gemini work uses `gemini-4-neon-high` (Gemini 4 Neon, high effort), passed explicitly:

```bash
agy --model gemini-4-neon-high
```

## Shared skills

`agy` discovers skills (`SKILL.md` with YAML front matter) in:

- **Workspace:** `.agents/skills/` in each directory from the working directory up to the repository root, and folders listed in `.agents/skills.json`. `agy` also accepts `.agent/`, `_agents/` and `_agent/` as the customization root.
- **Global:** `~/.gemini/config/skills/`, and folders listed in `~/.gemini/config/skills.json`.

One copy serves Claude Code, Codex and Gemini. Rather than copying or linking skills, `~/.gemini/config/skills.json` points at the folders Claude Code already uses, each scanned one level deep:

```json
{
  "entries": [
    {"path": "/Users/<you>/.claude/skills", "include_only": ["triage", "write-a-skill"]},
    {"path": "/Users/<you>/PROJECTS/Loop/.claude/skills", "include_only": ["merge-risk"]}
  ]
}
```

- An entry pointing into a plugin cache names the plugin's version folder (for example `mattpocock-skills/1.3.1`), so it needs updating after a plugin upgrade.
- When two folders hold a skill of the same name, keep one with `include_only` or `exclude`.
- A skill with `disable-model-invocation: true` is hidden from the model's list, but `/name` still expands it in a session, print mode included. Observed with `agy` 1.5.16; agy's own docs list only `name` and `description` in the front matter.
- `agy` shows each skill's name and description; the full `SKILL.md` loads only when the skill is used.

## Shared instructions

`agy` loads `AGENTS.md` (and `GEMINI.md`) from each directory between the working directory and the repository root. A repository that keeps its instructions in `AGENTS.md`, with a one line `CLAUDE.md` (`@AGENTS.md`) for Claude Code, needs nothing more for `agy`.

Global instructions can live in `~/.gemini/config/GEMINI.md` (observed: `agy` 1.5.16 loads it as global rules; agy's docs name `~/.gemini/config/` as the global root without listing this file). It can include the Claude Code global rules instead of copying them, with agy's include syntax:

```markdown
@[Claude global rules](/Users/<you>/.claude/CLAUDE.md)
```

Each rule file is capped at 24,000 bytes after includes, and always-on rules share a 20,000 token budget.

## Claude specific skills

Skills written for Claude Code often rely on three Claude specific features that need adjusting to work on `agy` and Codex:

- **Slash invocation (`/tdd`, `/issue`):** a skill can be started with `/name` in an `agy` session. In a Loop run, the `WORKFLOW.md` prompt template names the skill instead, so the agent activates it or reads its `SKILL.md`. Loop's `agy` runners turn slash expansion off, since the prompt carries issue text Loop does not trust (`--disable-slash-commands` in print mode; under ACP, pending Spike 0).
- **Hardcoded tool names:** Claude specific names (`Bash`, `Edit`, `Read`, `mcp__loop__move`) become runtime neutral phrasing, for example "use Loop's `move`, `comment` and `submit_result` MCP tools when available, else `loop tool move` and `loop tool comment`".
- **Subagent delegation (`Agent` tool, `context: fork`, `SendMessage`):** skills that fan out to parallel Claude subagents (such as expert reviewers in an issue skill) do not carry over to `agy` as written. Until Loop's Koog manager and expert agents land in M6 (`docs/PLAN.md`), either:
  - flatten those subagent steps into sequential single agent phases, portable across Claude Code, Codex and `agy`, or
  - keep a thin variant where the fan out is essential, for example one that sends its reviews to Claude through `claude -p`.

## Enforcement stays in Loop

Claude specific skill front matter such as `allowed-tools`, and Claude Code hooks, are not expected to have any effect in `agy` (pending Spike 0). Nothing a skill declares is relied on for safety:

- **Guardrails and verification** (blocking `git push`, `gh`, history rewrites, `loop approve`, `loop resume` and holdout reads) belong in Loop's runners (generated deny rules or hooks in an isolated config, and ACP `session/request_permission` answers) and in `kinds.*.verify`, never in a skill.
- **Mandatory workflow rules** (progress file, evidence files, writing rules, handoff steps) belong in the `WORKFLOW.md` prompt template, not in an optional skill a model might never activate.
