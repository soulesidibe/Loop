# Recorded codex app server sessions

Real sessions of the pinned codex CLI, scrubbed, which the Codex runner's tests replay through `FakeAppServer` (m3-02, m3-03). Each `.jsonl` file holds only the lines the server wrote, one protocol message per line. They were recorded with `../../../../recording/record.py` and scrubbed with `../../../../recording/scrub.py`. The steps are in `recording/README.md`.

## Fixtures

| File | CLI | Prompt | Recorded |
|---|---|---|---|
| `initialize_and_thread_start.jsonl` | codex-cli 0.167.0 | none, no turn was started (m3-02) | 2026-11-16 |
| `success.jsonl` | codex-cli 0.167.0 | "Reply with the single word hi. Do not run any command, do not edit any file." Model `gpt-6.1-sol`, approval `never`, read-only sandbox. The turn completes, with token usage and rate limits. | 2026-11-17 |
| `failure.jsonl` | codex-cli 0.167.0 | The `success` prompt with the model `loop-no-such-model`. A `warning`, an `error` with `codexErrorInfo` `modelNotFound`, and `turn/completed` with status `failed`. | 2026-11-17 |
| `approval.jsonl` | codex-cli 0.167.0 | "Create a file named hello.txt containing the line hello, then run ls to list the folder." Model `gpt-6.1-sol`, approval `untrusted`, read-only sandbox. Two `item/commandExecution/requestApproval`, both answered `accept`. Codex wrote the file with a shell command, so no file change approval came. | 2026-11-17 |
| `input-request.jsonl` | codex-cli 0.167.0 | "Before doing anything else, use the request_user_input tool to ask me which greeting I prefer, with the two options hi and hello. Then reply with the greeting I chose. Do not run any command, do not edit any file." Model `gpt-6.1-sol`, `experimentalApi`, `-c features.default_mode_request_user_input=true`. One `item/tool/requestUserInput`, answered `hi`. | 2026-11-17 |
| `usage.jsonl` | codex-cli 0.167.0 | "Read README.md and summarize it in two sentences. Do not edit any file." Model `gpt-6.1-sol`, approval `never`, read-only sandbox. Several `thread/tokenUsage/updated` and `account/rateLimits/updated`. | 2026-11-17 |

## Check before committing

The repository is public. No fixture may hold a token, an account id, an email, the machine's host name or a path under the home folder. Read each fixture by hand, then run this from the repository root. It must print nothing:

```bash
grep -inoE "$HOME|\b$USER\b|$(hostname -s)|/Users/|/private/|/var/folders/|eyJ[A-Za-z0-9_-]{8,}\.|sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" plugins/runner-codex-app-server/src/test/resources/real/*.jsonl | grep -v ':user@example.com$'
```

The placeholders the scrub writes are `/home/user` for the home folder, `/workspace` for the workspace, `user` for the user name, `host` for the host name, `00000000-0000-0000-0000-000000000000` for account and machine ids, `user@example.com` for emails and `<redacted>` for tokens. Thread, turn and item ids are random per session and stay as recorded.
