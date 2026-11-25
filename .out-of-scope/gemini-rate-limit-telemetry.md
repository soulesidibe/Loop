# Rate limit telemetry for the acp runner

The acp runner maps rate limit errors (429, 503, 529 and `rate_limit` codes) to `runner_unavailable`, but records no `rate_limit` events with quota levels, as the Codex and Claude Code runners do. Building it is out of scope for now.

## Why this is out of scope

There is nothing to read. ACP has no standard rate limit or quota notification, and the recorded `agy` 1.5.16 sessions carry no quota field in `usage_update`, the `session/prompt` responses or any other message.

Reopen it when `agy` reports quota, through an ACP extension or a field of its own. Then map it to the same `rate_limit` event the Codex runner emits.

## Prior requests

- bl-215, "Rate limit telemetry for the acp runner, once agy reports it"
