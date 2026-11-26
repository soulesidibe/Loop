---
id: bl-240
title: "The acp runner echoes JSON-RPC error bodies, account fields included, as runner output"
state: todo
priority: null
labels: [needs-triage]
blocked_by: []
---

The acp runner emits every stdout line of the agent as a RunnerEvent.Output before it decodes the frame (AcpRunner.kt, the ProcessEvent.Line branch). The orchestrator redacts only known secrets in those lines, so a JSON-RPC error whose data carries a Google error body, for example the metadata of an exhausted Gemini quota, can bring an account id or an email into the attempt's runner output and its record file. bl-215 keeps them out of the rate_limit event and the turn_failed reason it adds, but did not change the raw echo, which applies to every protocol line. Worth deciding whether error frames (or their data) should be redacted or left out of runner output, and whether the same holds for the other runners.

Follow up of bl-215 (default:bl-215), filed by Loop from attempt 12027e80-73d8-4f2f-b8bb-edcdb6eab7e7.
