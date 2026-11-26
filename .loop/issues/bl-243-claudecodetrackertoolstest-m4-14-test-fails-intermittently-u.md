---
id: bl-243
title: "ClaudeCodeTrackerToolsTest m4-14 test fails intermittently under the full build"
state: todo
priority: null
labels: [needs-triage]
blocked_by: []
---

During Loop's `./gradlew --no-daemon build` check of bl-205 on 2026-11-26, `cli` test `ClaudeCodeTrackerToolsTest > m4-14 with a files and a GitHub tracker each agent's comment and move reach its issue's own tracker only()` failed: the GitHub issue's attempt ended after about 500 ms with `port_exit: exit code 1, no result line`, so the expected GitHub calls (POST comments, POST labels, DELETE label gh-ready) were an empty list. The files tracker's attempt in the same run succeeded. bl-205 changes no code on this path. Rerun alone three times with `./gradlew :cli:test --tests 'loop.cli.ClaudeCodeTrackerToolsTest' --rerun`, it passed every time. It is not the 60 second deadline pattern of bl-183: the fake Claude Code process exited 1 early, which suggests a race in the fake script or its fixture (two attempts in parallel, a shared file or port) rather than load alone. The stderr of the fake process is not in the failure message, so the first step is to capture it.

Follow up of bl-205 (default:bl-205), filed by Loop from attempt c679eaed-ecef-4be0-a703-44aa5233288c.
