---
id: m4-46
title: "After a network hold, a session below the soft limit resumes"
state: todo
priority: 2
labels: [ready-for-agent]
blocked_by: [m4-08]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 108, second half. Lane A, runs on the claude-code runner. m4-08 makes a lost network a `runner_unavailable` hold that takes no attempt slot, and the next attempt starts fresh with the session brief. This issue lets that attempt continue the same Claude Code session when it is cheap to do so.

Area: a new `plugin-api` member that carries the session to resume into the next attempt (`plugin-api/src/main/kotlin/loop/api/AttemptContext.kt` or `AgentRunner.kt`, with a default that keeps today's behavior), `AgentRunnerContract` in the `plugin-api` test fixtures, the `--resume` launch in `plugins/runner-claude-code/.../ClaudeCodeRunner.kt`, the runtime session id the orchestrator keeps only in the claim today (`Orchestrator.kt`, around lines 1516 to 1526) and the last reported occupancy, a new file in `loop.core.orchestrator` (for example `NetworkResume.kt`, beside `RunnerHold.kt`), docs.

What is known:

- No resume across attempts exists today: the Claude Code runner resumes only inside one session (`ClaudeCodeRunner.kt`, KDoc around lines 179 to 182), and `AttemptContext` and `AgentRunner` have no resume field. The resume needs the new `plugin-api` member, the session id kept past the claim, and the last occupancy read at the next dispatch.
- **Resume, bounded as the spec says** (bl-151 is deferred): once the hold lifts, the next attempt continues the same Claude Code session with `--resume` only when the attempt failed as `runner_unavailable` and its last reported occupancy is below `context.soft_limit`; otherwise it starts a fresh session with the session brief, as a rotation does. The rule that a rotation never resumes is unchanged. Codex starts a fresh session with the brief in both cases.
- A runner that does not support the new member keeps today's behavior through its default, so a third party runner compiles and runs unchanged.

Lane A rule: the resume choice goes in the new file in `loop.core.orchestrator`; the `Orchestrator.kt` edit is limited to call sites.

Out of this issue: the classification and the hold (m4-08), a resume after any other failure, the wider resume question of bl-151, a resume for Codex.

## Acceptance criteria

- [ ] After a network hold lifts, a session below the soft limit is resumed with `--resume`, and one at or above it starts fresh with the session brief (tests on the spawned command line).
- [ ] A rotation never resumes, and a Codex session starts fresh with the brief after a hold (tests).
- [ ] The new `plugin-api` member has a default that keeps today's behavior, and `AgentRunnerContract` covers it (test with a runner that does not override it).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.orchestrator.NetworkResumeTest'` passes, a new test class of the resume choice.
- [ ] `./gradlew --no-daemon :plugins:runner-claude-code:test --tests 'loop.runner.claudecode.ClaudeCodeRunnerTest'` passes, with the resume launch case.
- [ ] `docs/spec-decisions.md` and `docs/self-hosting.md` describe the resume rule and the `plugin-api` member (judgment).
- [ ] The resume logic lives outside `Orchestrator.kt`, whose diff is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29, split from m4-08 after the Fable 5.2 review.
