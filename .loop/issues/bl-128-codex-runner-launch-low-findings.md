---
id: bl-128
title: "Low findings of the m3-17 merge risk review, the Codex runner launch"
state: done
priority: 2
labels: [ready-for-agent]
blocked_by: [bl-141]
---

## Parent

None. Backlog, not scheduled in a milestone. Found by the merge risk review of pull request #99 (m3-17), Sonnet and a Fable 5.2 recheck, both low 1.27, run 9e6be449 on 2026-11-16. Approved. Priority 2 because the first finding should be settled before a Codex turn runs for real (m3-18 onward, at the latest m3-24).

## What to look at

- `CodexAppServerRunner.kt:116` at head 96968e5 launches through `bash -lc`, which sources the login profile after the env policy was applied, so an export in the profile can restore a name the policy removed (an Anthropic or tracker token, for example). The test hides it with an empty `HOME` (`CodexAppServerRunnerTest.kt:307`). Launch without a login shell, or apply the policy after the profile, and test it with a profile that exports a removed name.
- The pinned codex version is not enforced. m3-02 assigned the refusal of a different major version to m3-17, m3-17 deferred it, and m3-18 does not list it. Add it to the issue that does the handshake, or here.
- The 10 MB maximum line size of the protocol is not enforced: `ProtocolFraming.kt:66`, and `readLine` in `ProcessGroupLauncher.kt:203` is unbounded.
- Frames are discarded in production (`CodexAppServerRunner.kt:85`) until the handshake issue lands, and a stall timeout set to 0 would let a silent server hang the attempt.
- The provider is selectable, but every run ends in `TurnFailed` before the handshake lands (`CodexAppServerRunner.kt:134` to `:145`). By design and documented; nothing should set `runner.kind: codex-app-server` until then.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] The Codex runner launches without a login shell, or applies the env policy after the profile. A test with a profile that exports a removed name shows the name stays removed.
- [ ] The refusal of a different major codex version is enforced in the handshake, or recorded here as handled elsewhere with its issue id.
- [ ] The 10 MB maximum line size is enforced in `ProtocolFraming.kt` and in `readLine` of `ProcessGroupLauncher.kt`, with a test for an oversize line.
- [ ] Each other finding is handled, or closed here with a one line reason.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29. Blocked by bl-141, the first of the chain bl-141, bl-128, bl-135 after m4-08, since all three edit the Codex runner.

2026-11-24 03:18Z agent 5ec5259b: bl-128 done on loop/bl-128 (d0c1631..ced2be5). Each finding:
  1. Login shell: already fixed by m3-20. The runner runs the command by argv with no shell. The test `m3-20 a name the env policy removed stays out even when the login profile exports it` writes `.bash_profile` and `.profile` that export the removed name GH_TOKEN, and it still passes. No code change needed.
  2. Pinned version: now enforced in StartupHandshake. The version is read from the `userAgent` of the initialize answer, which has the form `<originator>/<version> (...)`. If the major version differs from CodexCliVersion.PINNED, the start fails with `response_error: codex <v> is not of the pinned major version <m> (codex-cli <pinned>)`. If the userAgent has no readable version, it fails with `response_error: the initialize response names no codex version in its userAgent`. Either way the process group is killed before `initialized` is written. Another minor or patch version of the same major still starts, as m3-02 decided.
  3. 10 MB line: ProcessGroupLauncher now reads lines with BoundedLineReader, which keeps the same line breaks as readLine and keeps at most 10 MB of a line. A longer line is dropped and never held whole in memory. A `loop:` line on stderr takes its place, giving the stream and the size. This is the launcher's default for every runner. ProtocolFraming.decode also refuses a line over 10 MB as Malformed `line longer than 10 MB`. Tests cover a line at the limit, one byte over, and the size counted in bytes.
  4. Frames discarded and stall timeout 0: closed. Frames have been read by the handshake and the turn since m3-18 and m3-19. A silent server is bounded by the runner's own read_timeout_ms and turn_timeout_ms, and both refuse 0 (m3-19).
  5. Every run ending in TurnFailed: closed as obsolete. The handshake landed in m3-18 and turns run since m3-19.
  Docs: spec-decisions.md (the m3-18 section, the m3-02 pinned CLI line, the 17.5 item 8 row) and docs/plugins/runner-codex-app-server.md. Focused tests (the codex, runner-process, runner-command and runner-claude-code modules) and ktlintCheck pass.

2026-11-24 03:34Z loop: Loop opened pull request #35 for this issue: https://github.com/soulesidibe/Loop/pull/35
