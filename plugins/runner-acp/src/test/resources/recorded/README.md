# Recorded Antigravity CLI (`agy`) ACP sessions

Real scrubbed ACP JSON-RPC 2.0 server transcripts recorded from `agy 1.5.16` with `--disable-telemetry --sandbox --gemini-dir <relative>` and replayed by `RecordedAcpSessionsTest`. Each `.jsonl` file holds the stdout lines the server wrote, one protocol frame per line.

## Fixtures

| File | CLI | Scenario | Recorded |
|---|---|---|---|
| `initialize_and_session_new.jsonl` | `agy 1.5.16` | `initialize`, `session/new`, and `session/set_model` (`gemini-3.8-flash-low`) | 2026-11-24 |
| `invalid_model.jsonl` | `agy 1.5.16` | `initialize`, `session/new`, and `session/set_model` (`gemini-no-such-model`), returning `-32602 Unknown model ID` | 2026-11-24 |
| `success_turn.jsonl` | `agy 1.5.16` | Full single turn on `gemini-3.8-flash-low` with `config_option_update`, `usage_update` (`used: 0` then `used: 10384`, `size: 1048576`), and `session/prompt` `result.usage` | 2026-11-24 |

## Check before committing

No fixture may hold a token, account id, email, machine host name, or home path. Run from the repository root and confirm it prints nothing:

```bash
grep -inoE "$HOME|\b$USER\b|$(hostname -s)|/Users/|/private/|/var/folders/|eyJ[A-Za-z0-9_-]{8,}\.|AIza[0-9A-Za-z_-]{35}|ya29\.[0-9A-Za-z_-]{20,}|sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" plugins/runner-acp/src/test/resources/recorded/*.jsonl | grep -v ':user@example.com$'
```
