---
id: bl-128
title: "Low findings of the m3-17 merge risk review, the Codex runner launch"
state: todo
priority: 2
labels: [deferred]
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
