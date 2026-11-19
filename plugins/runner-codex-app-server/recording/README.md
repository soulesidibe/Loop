# Recording codex app server sessions

How the fixtures in `src/test/resources/real/` are made (m3-03). It needs the pinned codex CLI and a codex login, and every session but `--no-turn` makes a real model call on that account.

- `record.py` starts `codex app-server` in a throwaway workspace and drives it as Loop's runner does: `initialize`, `initialized`, `thread/start`, `turn/start`, each request sent once the answer before it arrived. It answers approvals with `--decision` (default `accept`) and user input questions with `--answer` (default the first option). It writes the raw server lines, its own lines, stderr and the settings to `--out`, `.scratch/codex-recordings` by default, which git ignores.
- `scrub.py` turns a raw server file into a fixture safe to publish, then runs the same check as the grep in the fixture folder's README.

Both use only Python's standard library.

## Steps

Check the CLI and the login:

```bash
codex --version
```

```bash
codex login status
```

Make a throwaway workspace:

```bash
mkdir -p /tmp/loop-codex-rec && cd /tmp/loop-codex-rec && git init -q && echo "Loop is an issue execution harness. This folder is a throwaway workspace for recording codex sessions." > README.md
```

From the repository root, record a scenario, `success`, `failure`, `approval`, `input-request` or `usage`:

```bash
python3 plugins/runner-codex-app-server/recording/record.py success --workspace /tmp/loop-codex-rec
```

It prints each message as it goes and the outcome at the end. `.scratch/codex-recordings/success.meta.json` holds the CLI version, the prompt and the settings, for the fixture table. Override a preset with `--prompt`, `--model`, `--approval-policy`, `--sandbox`, `--experimental`, `--thread-config '<json>'` or `--codex-config key=value`. `--no-turn` stops after `thread/start` and makes no model call.

Scrub it into the fixture folder:

```bash
python3 plugins/runner-codex-app-server/recording/scrub.py .scratch/codex-recordings/success.server.jsonl plugins/runner-codex-app-server/src/test/resources/real/success.jsonl --workspace /tmp/loop-codex-rec
```

Then read the fixture by hand, run the grep in `src/test/resources/real/README.md`, and add a row to its fixture table.

## The scenarios

| Scenario | Preset | What it should show |
|---|---|---|
| `success` | approval `never`, read-only sandbox, a one word reply | a turn that completes |
| `failure` | as `success`, with the model `loop-no-such-model` | a turn that fails, or `turn/start` answered with an error |
| `approval` | approval `untrusted`, read-only sandbox, create a file and run `ls` | `item/fileChange/requestApproval` and `item/commandExecution/requestApproval`, answered `accept` |
| `input-request` | `experimentalApi`, `-c features.default_mode_request_user_input=true`, a prompt asking for `request_user_input` | `item/tool/requestUserInput`, answered with the first option |
| `usage` | a short summary of `README.md` | `thread/tokenUsage/updated` and `account/rateLimits/updated` |

What the 2026-11-17 recording found:

- **The model.** Without `--model`, codex uses the `model` of `~/.codex/config.toml`, or its own default. `gpt-5.1-codex-max` is refused with a ChatGPT login (`modelNotFound`). `model/list` gives the models the login can use. `gpt-6.1-sol` was the default that day, and every scenario but `failure` was recorded with `--model gpt-6.1-sol`.
- **The input request.** With a prompt alone, codex asks its question as plain text and never sends `item/tool/requestUserInput`. The `input-request` preset turns on the under development feature `default_mode_request_user_input`, which makes it send the request. Codex then writes a `warning` naming the feature.
- **The approval.** Codex created the file with a shell command, so the `approval` session holds two command approvals and no file change approval.
