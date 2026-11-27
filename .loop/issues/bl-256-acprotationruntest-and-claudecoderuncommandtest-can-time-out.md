---
id: bl-256
title: "AcpRotationRunTest and ClaudeCodeRunCommandTest can time out when loop tool starts slowly"
state: todo
priority: null
labels: [needs-triage]
blocked_by: []
---

Found while fixing bl-249. The full build of bl-249 failed once on `CodexRotationRunTest > m3-23 a Codex session over the hard limit has its turn interrupted, then rotates onto a new thread`, with `turn_timeout: no protocol message for 20000 ms`, while the machine had a 15 minute load average of about 169.

Cause, confirmed with timestamps: the scripted app server of that test runs `loop tool move review` inside its second turn and writes no protocol message meanwhile. That JVM took 7 to 12 seconds to start with 40 busy processes, and more than the 20 second `turn_timeout_ms` of the test under the load of the failed build. A throwaway `sleep 25` before the move reproduces the failure every time. bl-249 fixed that test: its scripted app server now runs the move in the background and writes an `item/agentMessage/delta` notification each second until the move ends.

The same exposure remains in two other cli tests, which set `turn_timeout_ms: 20000` and run `loop tool` inside a turn of a scripted agent: `cli/src/test/kotlin/loop/cli/AcpRotationRunTest.kt` (one `loop tool` call) and `cli/src/test/kotlin/loop/cli/ClaudeCodeRunCommandTest.kt` (eleven `loop tool` lines). Neither has failed yet as far as bl-249 saw. Check whether each runner counts that window as silence, and if so apply the same kind of keepalive in the scripted agent rather than a longer timeout.

Follow up of bl-249 (default:bl-249), filed by Loop from attempt 802baecd-8fd2-44d8-8e0c-65f4ebb1b082.
