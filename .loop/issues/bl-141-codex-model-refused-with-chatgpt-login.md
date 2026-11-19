---
id: bl-141
title: "Codex runner turns fail when the operator's codex config names a model the login refuses"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-08]
---

## Parent

None. Backlog, not scheduled in a milestone. Found while recording the m3-03 fixtures on 2026-11-17, codex-cli 0.167.0 with a ChatGPT Plus login.

## What to look at

- The Codex runner sends no `model` in `thread/start` or `turn/start`, and `runners.codex` has no `model` key (`CodexAppServerSettings` in `CodexAppServerRunner.kt`). The thread takes the `model` of `$CODEX_HOME/config.toml`, else codex's default.
- On the operator's machine, `~/.codex/config.toml` sets `model = "gpt-5.1-codex-max"`. With a ChatGPT login, codex refuses it: a `warning` that its metadata is missing, then an `error` with `codexErrorInfo` `modelNotFound` ("not supported when using Codex with a ChatGPT account") and `turn/completed` with status `failed`. So every Codex runner turn on that machine fails at once. The recorded `failure.jsonl` fixture shows the same refusal with a made up model name.
- `model/list` on the same login lists `gpt-6.1-sol` (the default), `gpt-6-astra`, `gpt-6.1-terra`, `gpt-6-sol`, `gpt-6-luna`, `gpt-5.6-sol`, `gpt-5.6-terra` and `gpt-5.6-luna`.
- Options: a `model` key in `runners.codex`, passed in `thread/start`, so a workflow names the model it was checked with; or a check at session start, `model/list` against the thread's model, that fails with a clear reason instead of a failed turn; or both. A workaround today is `command: codex app-server -c model=gpt-6.1-sol`, or changing the operator's config.
- A turn failed this way is not the agent's fault. Check which category `TurnFailed` gets for `modelNotFound`, so the attempt is not retried as if the work failed.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] A `model` key in `runners.codex` is passed in `thread/start`, or a check at session start compares the thread's model with `model/list` and fails with a clear reason. A test uses a recorded refusal fixture.
- [ ] A turn that failed with `modelNotFound` is not retried as if the agent's work failed. A test asserts which failure category it gets.
- [ ] `docs/plugins/runner-codex-app-server.md` names the `model` key and the `-c model=` workaround. Judgment call.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29. Blocked by m4-08, which defines the `runner_unavailable` category a refused model belongs to. Chained with bl-128 and bl-135, which edit the same Codex runner files.
