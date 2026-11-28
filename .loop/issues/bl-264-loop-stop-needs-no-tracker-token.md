---
id: bl-264
title: "loop stop and loop drain work without the tracker token"
state: todo
priority: 2
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Seen on 2026-11-27: `loop stop /Users/.../<pilot app>/WORKFLOW.md` from a shell without `$LOOP_<APP>_GITHUB_TOKEN` failed with "tracker.provider.token uses $LOOP_<APP>_GITHUB_TOKEN, which is not set", while `loop status` on the same workflow worked. The only way left to stop the run was a raw SIGTERM to its pid.

## What

`StopCommand` opens the project with `Access.WRITE` (cli/src/main/kotlin/loop/cli/StopCommand.kt:30), and `openProject` defers unset secrets only for `Access.READ` (`workflowLoader(deferSecrets = access == Access.READ)`, cli/src/main/kotlin/loop/cli/OpenProject.kt). So the GitHub tracker's token is resolved at load time, and a missing one fails the command, though `stop` never builds the tracker: it reads the run file, writes the stop marker, sends SIGTERM and reads the store. `DrainCommand` has the same shape and builds no tracker either. `SendBackCommand` and `AnswerCommand` also build no tracker; check them too. `ApproveCommand` and `ResumeCommand` do build it.

## Agent brief

Let a command that never builds the tracker load the workflow with the tracker secret deferred, while keeping its write access to the store and run files: separate "defer the tracker secret" from `Access`, or give these commands a way to ask for it. `OpenedProject.tracker()` already throws naming the variable when the deferred secret is unset, so a command that later needs the tracker still fails clearly. Do not weaken redaction: the redactor must still learn every secret that is set.

## Acceptance criteria

- [ ] A test: with a GitHub tracker workflow whose token variable is unset, `loop stop` stops a running run (stop marker, SIGTERM, the usual "Stopped run" line) and exits 0.
- [ ] The same for `loop drain`.
- [ ] `loop send-back` and `loop answer` either work without the token (if they never build the tracker) or the issue comment says why they need it.
- [ ] `loop approve` and `loop resume` still fail naming the variable when it is unset and the tracker is needed.
- [ ] A test that a set token is still redacted in the output of a command that defers it.
- [ ] `docs/` names which commands need the tracker token (the CLI or configuration page that lists commands).
- [ ] `./gradlew :cli:test` passes for the touched commands' tests.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
