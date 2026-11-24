---
id: m4-30
title: "Notification hook and terminal channel: you are told when a run needs you"
state: todo
priority: 1
labels: [deferred]
blocked_by: [m4-26, m4-01]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 127 (PLAN 6.5 and 12). Lane A, runs on the claude-code runner. Only the channel implementation would fit lane B, and `terminal` is a core implementation (PLAN 12), so the whole issue is lane A (finding M6 of the m3-29 review).

Carries bl-109. Loop already runs overnight, and nothing tells the operator when a run needs them. On the night of 2026-11-15 approvals were handled only because an operator session polled `loop status` every minute; without it, 6 verified pull requests would have waited until morning. bl-109's directions, decided here:

- **`HumanChannel`:** m4-01 creates it empty in `plugin-api`. This issue gives it one method, `notify(notice)`, where a notice holds the issue (id and identifier), the event (`parked`, `approval_waiting`, `delivery_failed`, and `question` from m4-31), the reason, a URL when one exists (the pull request), and the time. A `HumanChannelContract` joins the contract fixtures in `plugin-api/src/testFixtures/kotlin/loop/api/`.
- **`terminal` channel:** the core implementation, in a new package `loop.core.channel`. It prints one line per notice to the `loop run` terminal (stderr, with the identifier, the event and the next command to type, for example `loop approve m4-30`). Selected by `channel: { kind: terminal }`, the default.
- **No Gradle edit:** `spec-core` tests do not see the `plugin-api` test fixtures (`spec-core/build.gradle.kts`), and Gradle files are protected. So `HumanChannelContract` runs against a fake channel in `plugin-api`'s own tests, and a `TerminalChannelTest` in `spec-core` repeats the contract's cases against the terminal channel. Running the shared contract against the terminal channel needs `testImplementation(testFixtures(project(":plugin-api")))` in `spec-core/build.gradle.kts`, a human step listed under `residual_human_steps`.
- **Notification hook:** `hooks.on_human_needed`, a host command like `hooks.on_token_threshold`, run with the notice as JSON on stdin and as `LOOP_NOTICE_*` env, so the operator wires `osascript`, `terminal-notifier`, a Telegram bot or a webhook today. A failing or slow hook records `hook_failed` and never stops the run, with the hooks' timeout.
- **Which events:** a park, an approval wait (a fresh one or the unapproved wait after `delivery_failed` or a fallen back auto merge), and a question (wired in m4-31). Not budget warnings or token thresholds, which have their own hooks.
- **Never twice:** one notice per wait or park, keyed by issue, reason and the wait's `since`. The key is kept in the wait or park record, so a restart of `loop run` does not notify again. A new wait after a resume notifies again.
- The orchestrator edit is limited to the calls where a park and a wait are recorded.
- **Human steps after the merge:** wire `hooks.on_human_needed` in Loop's own `WORKFLOW.md`, which is protected, and add the Gradle line above. A new glossary term for Human channel goes there too, with its exact text: `CONTEXT.md` is protected. All three are listed under `residual_human_steps`.
- Out of this issue: `ask` (m4-31), channels beyond the terminal (Telegram, Slack, HTTP: out of M4), notices on reactions (m4-34 to m4-37). Also out, though bl-109 named them: a dead run, a budget hold and a `needs_info` move are not notified in M4.

## Acceptance criteria

- [ ] A park and an approval wait each produce one terminal line and one hook run with the notice JSON on stdin (CLI test with the fake runner and a hook that writes its input to a file).
- [ ] The same wait does not notify twice across ticks or across a restart of `loop run`, and a new wait after a resume notifies again (tests).
- [ ] A failing and a hanging hook record `hook_failed` and the run goes on (one test each).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.HumanNoticeTest'` passes.
- [ ] `HumanChannelContract` runs against a fake channel in the `plugin-api` tests (judgment).
- [ ] `./gradlew --no-daemon :plugin-api:test` passes.
- [ ] `TerminalChannelTest` covers the contract's cases against the terminal channel, with no Gradle file changed (judgment).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.channel.*'` passes.
- [ ] Preflight accepts `channel: { kind: terminal }` and refuses an unknown channel kind (test).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] The `WORKFLOW.md` hook step, the Gradle line and the glossary text are listed under `residual_human_steps` in the submitted result (judgment).
- [ ] `docs/spec-decisions.md`, PLAN 6.5 and 12 describe `HumanChannel`, the terminal channel, `hooks.on_human_needed`, the never twice rule and the events not notified in M4 (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
