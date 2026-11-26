# Recorded Antigravity CLI (`agy`) ACP sessions

Real scrubbed ACP JSON-RPC 2.0 server transcripts recorded from `agy 1.5.16` with `--disable-telemetry --sandbox --gemini-dir <relative>` and replayed by `RecordedAcpSessionsTest`. Each `.jsonl` file holds the stdout lines the server wrote, one protocol frame per line.

Loop refuses an `agy` older than 1.5.18 since 2026-11-26, so the replay rewrites the recorded `agentInfo.version` to the pinned version, and one test replays the untouched 1.5.16 frame to check the refusal. Re-record on the pinned version when the protocol frames change.

## Fixtures

| File | CLI | Scenario | Recorded |
|---|---|---|---|
| `initialize_and_session_new.jsonl` | `agy 1.5.16` | `initialize`, `session/new`, and `session/set_model` (`gemini-3.8-flash-low`) | 2026-11-24 |
| `invalid_model.jsonl` | `agy 1.5.16` | `initialize`, `session/new`, and `session/set_model` (`gemini-no-such-model`), returning `-32602 Unknown model ID` | 2026-11-24 |
| `success_turn.jsonl` | `agy 1.5.16` | Full single turn on `gemini-3.8-flash-low` with `config_option_update`, `usage_update` (`used: 0` then `used: 10384`, `size: 1048576`), and `session/prompt` `result.usage` | 2026-11-24 |
| `permission_request.jsonl` | `agy 1.5.16` | Two `session/request_permission` requests in one turn (`cat hello.txt` allowed with `allow_once`, `cat /tmp/loop-holdout/secret.txt` declined with `reject_once`) | 2026-11-24 |
| `cancelled_turn.jsonl` | `agy 1.5.16` | Turn interrupted by `session/cancel` returning `stopReason: "cancelled"` with partial `result.usage` | 2026-11-24 |
| `session_load_missing.jsonl` | `agy 1.5.16` | `initialize` and `session/load` for an unknown session id, returning `-32002 Session not found` | 2026-11-24 |
| `bare_model_turn.jsonl` | `agy 1.5.16` | Full single turn on bare model id `gemini-pro-orchestrator-neon` (no `-thought_level` suffix) | 2026-11-24 |

## Check before committing

No fixture may hold a token, account id, email, machine host name, or home path. Run from the repository root and confirm it prints nothing:

```bash
grep -inoE "$HOME|\b$USER\b|$(hostname -s)|/Users/|/private/|/var/folders/|eyJ[A-Za-z0-9_-]{8,}\.|AIza[0-9A-Za-z_-]{35}|ya29\.[0-9A-Za-z._-]{20,}|sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" plugins/runner-acp/src/test/resources/recorded/*.jsonl | grep -v ':user@example.com$'
```
