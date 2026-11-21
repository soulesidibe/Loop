---
id: bl-149
title: "Low findings of the bl-136 merge risk review, grace deadline and working tree progress"
state: done
priority: 3
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found by Loop's own merge risk review of pull request #115 (bl-136, band low 1.40, `claude-sonnet-5-5`), run 7165c78c on 2026-11-17, during m3-27. Merged after `loop approve`, then reviewed after the fact by Fable 5.2.

## What to look at

- A failed git HEAD or working tree read is swallowed by `runCatching` and counts as progress with no log line, so a workspace whose git reads keep failing never parks as `split_issue` and nobody sees why. Log the failure on the operator log, at least once per attempt.
- A stall timer edge after the interrupt grace has no test.

## Fable 5.2 review after the merge, head 0aa1a1c (band low, nothing above low)

Every weakness in the fingerprint errs towards progress, so the worst case is a missed `split_issue` park, capped by `max_rotations`. Paths under `spec-core/src/main/kotlin/loop/core/`.

- One unreadable file disables the park (confirmed). `contentHash` throws on a listed file it cannot open (`verify/WorkspaceGit.kt:208`), `runCatching` turns the whole tree into null (`orchestrator/Orchestrator.kt:3445`), and null counts as progress. A `chmod 000` file or a root owned build output does it at every rotation, with nothing logged. Wider than the swallowed git failure above.
- Index only changes count as progress (confirmed). The digest includes the `XY` status letters (`WorkspaceGit.kt:199`), so `git add`, `git reset` or a stash with no content change resets the count. The docs describe the fingerprint as file content only.
- Unstable untracked files defeat the park (plausible). Any difference is progress (`orchestrator/RotationProgress.kt:46`), so a target repo whose `.gitignore` misses a test report folder, a log or `.scratch/` itself shows a changed tree at every rotation. Loop never enforces the `.scratch/` ignore.
- Unbounded, uncancellable cost (confirmed in code). `--untracked-files=all` output is held as one string and every changed or untracked file is hashed in full (`WorkspaceGit.kt:191` to `:204`), outside the 5 minute git watchdog, inside a blocking `withContext(io)`, at the first prompt of every attempt on an occupancy runner (`Orchestrator.kt:2383`, `:2853`). An unignored `node_modules` or a large artifact delays the prompt and each rotation, and a stop waits. A file swapped for a FIFO between `isRegularFile` and `newInputStream` blocks forever.
- Nested repos and submodules are opaque (confirmed). An entry that is a directory hashes no content (`WorkspaceGit.kt:201`), so edits inside a nested clone or a submodule read as no change. The only case that errs towards parking.
- `git status` runs clean filters from the agent writable `.git/config` as Loop's user, now at every rotation (`WorkspaceGit.kt:192`, plausible). The bl-55 class, with more exposure.
- Test gaps (confirmed): no orchestrator test for a readable HEAD with a failed tree read (`Orchestrator.kt:3444` to `:3445`); no fingerprint test for deleted, staged, symlinked or unreadable files; the deadline is proven under virtual time only.
- Clean: `collectTurn` (`Orchestrator.kt:3062` to `:3122`), the deadline first in a biased `select`, the timer cancelled in `finally`, the cap grace and stall branches unchanged.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] A failed git HEAD or working tree read is logged on the operator log once per attempt, and one unreadable file no longer turns the whole fingerprint into null. A test covers each.
- [ ] The fingerprint ignores index only changes (`git add`, `git reset`, a stash with no content change), with a test, and the docs say it reads file content only.
- [ ] The fingerprint read is bounded and cancellable, so an unignored `node_modules` or a FIFO cannot delay the prompt or a stop.
- [ ] Tests cover a readable HEAD with a failed tree read, and deleted, staged, symlinked and unreadable files.
- [ ] Each other finding is handled, or closed here with a one line reason.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29, no blocker.

2026-11-20 17:27Z agent d189d0a3: bl-149 done on loop/bl-149 (a82ab3e, fa86ab5).
  Handled:
  - A failed git read is now logged on the operator log once per fact and attempt: `level=warn event=progress outcome=failed fact=workspace|head|working_tree`, plus the reason. The read still counts as progress.
  - An unreadable file no longer turns the fingerprint into null. It reads as its size and modified time, so a change to it still shows.
  - The fingerprint reads file content only. Status letters are left out and each path counts once, so `git add`, `git reset` and a stash with its pop read the same. The docs say so.
  - The read is bounded and cancellable. `git status` is streamed and fails above 20000 entries. Files over 1 MiB read as size and modified time. HEAD and the working tree are read on their own daemon thread with a 30 second budget. A stop ends the wait at once and kills the read's git and hashing. A thread stuck on a FIFO open is abandoned, never awaited.
  - Nested repos and submodules now read as their HEAD plus their own fingerprint, two levels down.
  - New tests: a readable HEAD with a failed tree read, a HEAD read failure, deleted, staged, symlinked and unreadable files, index only changes, the byte and entry caps, a nested repo, a cancelled read, a read past its budget, and the stall timer edge after the interrupt grace.
  Closed:
  - Unstable untracked files: closed. It errs towards progress and `max_rotations` caps it. A target repo's ignores are its own `.gitignore`, and `.scratch/` is a convention of Loop's own WORKFLOW.md, not of the product.
  - Clean filters from the agent writable `.git/config`: closed. It is the same bl-55 class as every workspace git command, owned by bl-55 and M5, and git has no single switch that turns clean filters off.
  - Deadline proven under virtual time only: closed. The grace is a coroutine `delay`, so virtual time runs the same code a real clock does.
  - Residual: `git rm --cached` and a staged change whose work tree is back at HEAD still read as a change. It errs towards progress.
  Evidence: focused orchestrator and verify tests (871 passed), `loop.cli.ClaudeCodeRunCommandTest` (12 passed), ktlintCheck green.
  Note: during the run I ran a broad `pkill -f "Gradle Test Executor"` to stop a hung test run. It may have killed a test run of another workspace on the same machine.

2026-11-21 12:43Z loop: Loop opened pull request #4 for this issue: https://github.com/soulesidibe/Loop/pull/4
