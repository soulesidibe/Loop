# Using skills and project instructions with Gemini in Loop

This guide covers everything **outside** Loop's codebase for running Gemini CLI alongside Claude Code and Codex on your projects: sharing `SKILL.md` skills, wiring project instructions, and adapting Claude-specific skills. Loop itself stays runner agnostic, rendering `WORKFLOW.md` and letting the runtime load whatever skills exist in the environment or workspace.

For Loop's own Gemini runner design, see [`.loop/specs/gemini-support.md`](../.loop/specs/gemini-support.md).

## 1. Share one source of truth for `SKILL.md` skills

Gemini CLI (`0.42.0`) supports the Agent Skills standard (`SKILL.md` with YAML frontmatter) via `gemini skills` (`list`, `enable`, `disable`, `link`, `install`) and discovers skills from:

- **Project:** `.agents/skills/` and `.gemini/skills/`
- **Global:** `~/.agents/skills/` and `~/.gemini/skills/`

To avoid maintaining separate copies across Claude Code, Codex, and Gemini:

- Keep portable skills under `.agents/skills/<name>/SKILL.md` in a repository or `~/.agents/skills/<name>/SKILL.md` globally.
- Link existing Claude skills into Gemini without copying them:

```bash
gemini skills link ~/.claude/skills/<name>
```

- Loop's Gemini runners pass `--skip-trust` for ephemeral issue worktrees so Gemini loads workspace `.agents/skills/` and `.gemini/skills/` automatically.

## 2. Share root project instructions (`AGENTS.md` and `GEMINI.md`)

In repositories that keep canonical instructions in `AGENTS.md` and point Claude Code at it via a one-line `CLAUDE.md` (`@AGENTS.md`), add a matching one-line `GEMINI.md` at the repository root:

```markdown
@./AGENTS.md
```

Alternatively, configure `"contextFileName": ["AGENTS.md", "GEMINI.md"]` in `.gemini/settings.json`. Using `GEMINI.md` keeps instructions out of `.gemini/settings.json` and avoids drift across runtimes.

## 3. Adapt Claude-specific skills for cross-runtime use

Skills written for Claude Code commonly rely on three Claude-specific features that need adjustment to work cleanly on Gemini (and Codex):

- **Slash invocation (`/tdd`, `/issue`):** in headless and ACP runs, slash commands are not expanded like interactive TUI commands. Reference the skill by name in the `WORKFLOW.md` prompt template so Gemini activates it through its skill tool or reads its `SKILL.md` directly.
- **Hardcoded tool names:** replace Claude-specific tool names (`Bash`, `Edit`, `Read`, `mcp__loop__move`) with runtime-neutral phrasing (for example, "use Loop's `move`, `comment`, and `submit_result` MCP tools when available, else `loop tool move` and `loop tool comment`").
- **Subagent delegation (`Agent` tool, `context: fork`, `SendMessage`):** skills that fan out to parallel Claude subagents (such as expert reviewers in an issue skill) will not behave identically on Gemini CLI. Until Loop's Koog manager and expert agents land in M6 (`docs/PLAN.md`), either:
  - flatten those subagent steps into sequential single-agent phases (portable across Claude, Codex, and Gemini), or
  - keep a thin Gemini-adapted variant only where subagent fan-out is essential.

## 4. Keep enforcement in Loop, not in skill frontmatter

Gemini CLI ignores Claude-specific skill frontmatter such as `allowed-tools` and Claude Code hooks:

- **Guardrails and verification** (blocking `git push`, `gh`, history rewrites, `loop approve`, `loop resume`, and holdout reads) belong in Loop's runner (`--admin-policy` TOML and ACP `session/request_permission`) and in `kinds.*.verify`, never in a skill.
- **Mandatory workflow rules** (progress file, evidence files, writing rules, handoff steps) belong in the `WORKFLOW.md` prompt template rather than an optional skill that a model might skip activating.
