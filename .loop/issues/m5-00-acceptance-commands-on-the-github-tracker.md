---
id: m5-00
title: "Acceptance commands on the GitHub tracker, when the tracker declares its issue text trusted"
state: todo
priority: 1
labels: [ready-for-agent]
blocked_by: []
area: [spec-core/src/main/kotlin/loop/core/workflow, spec-core/src/main/kotlin/loop/core/verify/Verification.kt, spec-core/src/main/kotlin/loop/core/trackers, spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt, plugin-api/src/main/kotlin/loop/api/TrackerProvider.kt, plugins/tracker-github, plugins/tracker-files, cli/src/test/kotlin/loop/cli, docs]
---

## Parent

[Loop, permissions hardening (M5)](../specs/loop-m5.md)

## What

The first of the issues that move Loop's own tracker to GitHub issues before the rest of M5 is written (m4-45, 2026-11-28). The others are bl-266 and bl-273, then the human switch m5-01.

Today `acceptance_commands` runs only on the `files` tracker, gated by the tracker's kind name in three places:

- Preflight: `untrustedAcceptanceCommands` in `spec-core/src/main/kotlin/loop/core/workflow/AcceptanceCommandsTrust.kt`, "until Loop checks who wrote an issue".
- The freeze at dispatch: `freezeAtDispatch` in `spec-core/src/main/kotlin/loop/core/orchestrator/Orchestrator.kt`, around line 3142.
- Verification: `Verification.prepare` in `spec-core/src/main/kotlin/loop/core/verify/Verification.kt`, around line 175.

The commands run on the host with `sh -lc`, so only issue text Loop trusts may give them. This issue replaces the kind name with a declaration by the tracker plugin, and lets the GitHub tracker declare trust under a narrow rule.

**The rule and its limits.** On GitHub (bl-157, `docs/plugins/tracker-github.md`, "Trusted authors"):

- An issue whose author is outside `tracker.provider.trusted_associations` is never dispatched.
- Only the author, or anyone with triage access or more, can edit an issue's title and body.
- So with no association broader than `OWNER`, `MEMBER` and `COLLABORATOR` in the list, a dispatched issue was written by a trusted author, and every human who can edit it holds triage access or more on the repository.

An association is not a permission, so the doc states the limits plainly:

- A read only collaborator counts as `COLLABORATOR`. With `[OWNER]` alone, a collaborator with triage can still edit.
- The author can edit between labeling and the first dispatch.
- Apps and Actions with issues write can edit.
- The tracker token, which an agent can still reach until the M5 sandbox, can edit. This is the same hole the `files` tracker has, see `docs/spec-decisions.md` "Trust" (m2-09).
- A transferred issue keeps its author but may carry edits made in another repository.

The frozen list makes any edit after the first dispatch park the issue instead of running.

- **A declaration, not a kind name.** The tracker plugin declares whether the issue text it hands over is trusted to give commands, for example as a member of `TrackerProvider` read with the settings, with a default that answers false. The exact shape is the implementer's choice. The `files` tracker declares true. The GitHub tracker declares true only when `trusted_associations` is a subset of `OWNER`, `MEMBER` and `COLLABORATOR`. `CONTRIBUTOR`, `NONE` or any other association makes it false.
- **Preflight** reads the declaration of each tracker a kind with `acceptance_commands` can take issues from, the primary and every entry of `trackers:`, as the kind's `match` allows. It refuses an untrusted one with `invalid_config` naming the tracker and the reason, for example `kinds.code.verify.checks[2] acceptance_commands needs a tracker whose issue text is trusted, and tracker "gh" trusts CONTRIBUTOR`. A kind matched to a trusted tracker alone passes beside an untrusted tracker.
- **The freeze at dispatch and verification** read the same declaration for the issue's own tracker. An issue id that names a tracker not in the workflow is refused, never taken as the primary's (`TrackersConfig.entryOf` falls back to the primary today); a bare id stays the primary's, as m4-12 reads it. So a GitHub issue's list is frozen at its first dispatch, as a `files` issue's is.
- **The frozen list** is keyed by the namespaced issue id (m4-12), so a GitHub issue and a `files` issue with the same native id never share a list. The `acceptance commands changed` park and `loop resume` work on a GitHub issue of the primary tracker. `loop resume` on an issue of an extra tracker is bl-273.
- **Parsing.** The criteria are read from the GitHub issue body as from a `files` issue (`AcceptanceCriteria`). The `## Comments` section the GitHub tracker appends never adds a criterion, since a later section of the same name is ignored and a comment's own lines are indented.
- **Docs:**
  - `docs/plugins/tracker-github.md` gains an "Acceptance commands" section with the rule, its reason and its limits.
  - `docs/agents/issue-tracker.md` ("Runnable criteria") drops "with the `files` tracker only".
  - `docs/spec-decisions.md` updates the m2-09 Config, Trust and Holdouts paragraphs, which name "a tracker other than `files`", and the m4-13 writes bullet if it names the gate.
- Keep the existing test names: the `deleted_tests` guard counts them (`AcceptanceCommandsVerifyTest`).
- Out of this issue: the move map by tracker (bl-266), `loop resume` on extra trackers (bl-273), the switch itself (m5-01), and any read of GitHub's edit history.

Protected paths, which the diff must not touch: `scripts/`, `.github/`, `WORKFLOW.md`, `CONTEXT.md`, `*.gradle.kts`, `*/*.gradle.kts`, `plugins/*/*.gradle.kts`, `gradle/`, `build-logic/src/`, `gradle.properties`, `gradlew*`, `.loop/specs/`, `docs/adr/`. No residual human step is expected: `spec-core` tests use a fake tracker provider, and the GitHub cases run in `cli`, which already has the GitHub tracker and its test fixtures on its test classpath.

## Acceptance criteria

- [ ] In `AcceptanceCommandsTrustTest`, with a fake tracker provider: a tracker that declares true passes preflight, one that declares false fails with `invalid_config` naming the tracker, a kind matched to the trusted tracker alone passes beside an untrusted extra tracker, and a provider that declares nothing counts as false (one test each).
- [ ] In `GitHubTrackerProviderTest`: the declaration is true for the default `trusted_associations` and for `[OWNER]`, and false with `CONTRIBUTOR` or `NONE` added (one test each).
- [ ] The core decides from the declaration, never from a kind name, in preflight, the freeze at dispatch and verification (judgment).
- [ ] A new `cli` test on recorded GitHub answers, `loop.cli.GitHubAcceptanceCommandsTest`: a trusted author's issue runs its runnable criteria as `command` checks at verification, with its judgment calls in the evidence.
- [ ] The same test class: a body whose commands change between the first dispatch and the first verification parks `unverified` with `acceptance commands changed`, and `loop resume` accepts the new list.
- [ ] The same test class: a GitHub issue and a `files` issue with the same native id keep separate frozen lists, and a trusted comment holding `## Acceptance criteria` adds no command.
- [ ] `./gradlew --no-daemon :spec-core:test --tests 'loop.core.workflow.AcceptanceCommandsTrustTest' --tests 'loop.core.orchestrator.AcceptanceCommandsVerifyTest'` passes.
- [ ] `./gradlew --no-daemon :plugins:tracker-github:test --tests 'loop.tracker.github.GitHubTrackerProviderTest'` passes.
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.GitHubAcceptanceCommandsTest' --tests 'loop.cli.AcceptanceCommandsCommandTest'` passes.
- [ ] `grep -q 'Acceptance commands' docs/plugins/tracker-github.md` passes.
- [ ] The docs state the rule and its limits, and `docs/agents/issue-tracker.md` and `docs/spec-decisions.md` no longer say the `files` tracker only (judgment).
- [ ] The orchestrator edit is limited to call sites (judgment).
- [ ] `./gradlew --no-daemon build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments

2026-11-28 soule: written in m4-45. The owner chose to move M5 to GitHub issues before writing the rest of the M5 issues; this issue, bl-266, bl-273 and m5-01 run first, on the `files` tracker. Revised after the Fable 5.2 review of the spec and the switch issues.
