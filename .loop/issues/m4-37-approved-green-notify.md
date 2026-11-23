---
id: m4-37
title: "The approved_green reaction tells me a pull request is ready to merge"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-36]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 134. Lane A, run on the claude-code runner. Not gating the exit.

- **Config:** `reactions.approved_green: { action: notify_human }`, parsed by m4-34.
- **Which pull requests:** the watched set of m4-34, the waits that hold a pull request. A pull request delivered to a handoff state without `merge` (no wait) is not watched in M4.
- **Approved:** the latest review per reviewer read by m4-36 holds at least one `APPROVED` on the current head and no `CHANGES_REQUESTED`.
- **Green:** under `ci: actions`, the `CiWait.verdict` of the head is green. Under `ci: none`, green means the merge check passed on this head, as m4-05 defines it. A wait holds a pull request under `ci: none` only after an auto merge fell back or a delivery failed, so that is when this case fires. A head pushed after the merge check passed is not green. GitHub Actions refuses every job for billing since 2026-11-16, so the `ci: actions` case is proven on recorded Actions answers.
- **Action:** one notification through the `HumanChannel` of m4-30, naming the issue, the pull request URL and which green applied. Once per head sha: a new head that is approved and green again notifies again.
- **Logic** in `loop.core.reactions`. `Orchestrator.kt` gets call sites only.

Out of this issue: merging on approval (the delivery policy decides that), channels beyond the terminal and the hook, and pull requests of issues delivered to a handoff state without a wait.

## Acceptance criteria

- [ ] Under `ci: actions`, an approved head with a green verdict notifies once through the `HumanChannel` with the pull request URL (test on recorded Actions and review answers, fake channel).
- [ ] Under `ci: none`, an approved head whose merge check passed notifies, and one whose merge check did not pass does not (tests).
- [ ] A head pushed after the merge check passed is not green and sends nothing (test).
- [ ] A pending or red verdict, or a `CHANGES_REQUESTED` review, sends nothing (tests).
- [ ] The same head never notifies twice; a new approved and green head notifies again (test).
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.reactions.*'` passes.
- [ ] The logic lives in `loop.core.reactions`, and the diff of `Orchestrator.kt` holds call sites only (judgment).
- [ ] `docs/spec-decisions.md` lists the four reactions as built with the pull requests not watched in M4, and the M4 line of `docs/PLAN.md` section 17 says so (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
