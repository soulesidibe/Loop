# Gemini quota usage before it runs out

The acp runner cannot show how much of a Gemini quota is used, as the Codex runner does with its used percent per window. That is out of scope while `agy` reports nothing to read.

## Why this is out of scope

ACP has no standard rate limit or quota notification. The recorded `agy` 1.5.16 sessions carry only context occupancy (`used`, `size`) in `usage_update` and token counts in the `session/prompt` results, and `agy` has no usage or quota command.

What `agy` does report is the reset time once a quota is exhausted: Google's 429 carries `quotaResetDelay` and `quotaResetTimeStamp`. Holding until that time is in scope and tracked in bl-215, reopened on 2026-11-26.

Revisit the used percent when `agy` reports quota levels, through an ACP extension or a field of its own.

## Prior requests

- bl-215, first filed as "Rate limit telemetry for the acp runner, once agy reports it", reopened narrowed to the reset time hold
