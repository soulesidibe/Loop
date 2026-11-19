---
name: merge-risk
description: "Assess the merge risk of a Loop pull request on five dimensions (alignment, impact, verification, rollback cost, error detection), compute a weighted score and band, and say whether a Fable 5.2 recheck is required. Run on Sonnet 5.5 for every PR that reaches review, before any merge."
argument-hint: "[PR number] [issue id, e.g. m2-26]"
disable-model-invocation: true
---

Argument: `$ARGUMENTS` (a PR number, then the issue id when the PR body does not name it).

Adapted from Warp's `merge-risk` skill (warpdotdev/common-skills), itself derived from Matt Pocock's `pr` skill. It uses the same rubric as m2-28's merge risk assessment (the merge risk section of `docs/spec-decisions.md`), so the manual flow and the harness agree.

This is the maintainer's own review step for Loop's pull requests, with the models they use. Adapt the model choices to your setup before you use it elsewhere.

## Who runs it

The main session starts it as a subagent on Sonnet 5.5: Agent tool, `model: "sonnet"`, in the background, with this prompt:

> Read `.claude/skills/merge-risk/SKILL.md` and follow it for PR <n> (issue <id>). Read only: change no file, push nothing, comment nowhere. Return the report in the skill's output format.

This assessment does not hunt for bugs in depth. The Fable 5.2 recheck does that, and this skill decides when it runs (see "Fable recheck required").

## 1. Gather the inputs

- `gh pr view <n> --json title,body,baseRefName,headRefName,headRefOid,files,additions,deletions,statusCheckRollup,mergeable`
- `gh pr diff <n>`
- The issue file `.loop/issues/<id>-*.md`: its `What` and `Acceptance criteria`.
- The evidence: the PR body's acceptance table and notes, and any verification evidence Loop recorded for the attempt.

If the diff or the issue cannot be read, stop and say so. Never score from a partial picture.

Then check the head: the PR's `headRefOid` must equal the local `loop/<id>` head (`git rev-parse loop/<id>`) and `origin/loop/<id>`. If they differ, the PR and its CI are not about the code under review: stop before scoring and report the three shas, with the verdict "push first".

## 2. Compute the facts first

Write these down before judging anything. Each fact sets a floor: the dimension's verdict can be worse than the floor, never better.

| Fact | How | Floor |
|---|---|---|
| Protected path touched | Any of `WORKFLOW.md`, `scripts/`, `.github/`, `*.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `build-logic/`, `gradle/`, `.loop/` (except `.loop/issues/`, which is bookkeeping), `docs/adr/`, `CONTEXT.md` | impact at least medium |
| Sensitive path touched | Dependency or lock files, version catalogs, CI config, `**/migrations/**`, `*.sql`, anything handling tokens, credentials or permissions | impact at least medium |
| Safety code touched | Guards, policies, git isolation, workspace or token handling, delivery and merge code | impact at least medium |
| Large diff | More than 400 changed lines outside tests and docs | impact at least medium; more than 1000, impact large |
| Source changed, no test changed | Files under `src/main` changed and none under `src/test` | verification at least decent |
| Tests lost | A test file deleted, a test skipped or disabled, an assertion weakened | verification poor |
| CI not green | Any check pending, failed or missing | verification poor |
| One way change | A migration, data deleted or rewritten, a published artifact or release, a removed public API or config key without a fallback | rollback cost at least medium |

## 3. Judge the five dimensions

Read the diff and the code around it. Each verdict comes with short bullets that point to `path:line`.

- **Alignment** (poor, decent, good): every acceptance criterion has real proof in the diff or the evidence; the change stays inside the issue's area; any drive by change or behaviour assumption is stated in the PR.
- **Impact** (large, medium, small): scope and blast radius. Who and what breaks if this is wrong: other modules, the CLI's output, config compatibility, the tracker files, agents running under Loop.
- **Verification** (poor, decent, good): the new behaviour and its edge cases are tested, failure paths included; tests check behaviour, not just that code runs. An edge case only documented counts as a gap, with a link to the code.
- **Rollback cost** (high, medium, low): can a revert undo it fully? A two way door is low. A one way door (state written in a new format, data removed, something published) is high.
- **Error detection** (poor, decent, good, n/a): if this breaks in real use, will it show in `loop trace`, `loop log`, an event or a failing check before the user notices? Flag swallowed exceptions, silent fallbacks, ignored exit codes, `runCatching` without handling. Use `n/a` only when the change has no runtime path (docs only).

Apply the floors from step 2 after judging.

## 4. Score in plain arithmetic

- Points: worst verdict (poor, large, high) is 3, middle (decent, medium) is 2, best (good, small, low, n/a) is 1.
- Weights: impact 2, verification 2, rollback cost 1.5, alignment 1, error detection 1. Total 7.5.
- Score: sum of points times weight, divided by 7.5. Round to two decimals.
- Band: low below 1.50, medium from 1.50 to below 2.25, high from 2.25.

Show the calculation in the report. A worst verdict on any dimension blocks a merge even when the band is low: a one way door with everything else at best scores 1.40.

Large PRs always go to the user, by choice (2026-11-14). A diff over 1000 changed source lines sets impact to large, a worst verdict, and holds the score at 1.53 or more, so it never merges without the user, whatever the other dimensions say. Do not soften this floor because the change is opt in or well tested; say so in the impact bullets instead.

## 5. Fable recheck required

Say yes when any of these hold:

- The band is medium or high.
- Any dimension is at its worst verdict.
- A protected, sensitive or safety code path is touched.
- You found anything you would rate high or medium and are not sure of.

Otherwise say no.

## 6. Output

```markdown
**Merge risk: <low|medium|high>** (score <x.xx>)

Calculation: alignment <p>x1 + impact <p>x2 + verification <p>x2 + rollback <p>x1.5 + error detection <p>x1 = <sum> / 7.5 = <x.xx>

Facts: <each fact from step 2 that holds, with the paths; or "none">

**Alignment:** <verdict>
- <gap, with path:line>

**Impact:** <verdict>
- ...

**Verification:** <verdict>
- ...

**Rollback cost:** <verdict>
- ...

**Error detection:** <verdict>
- ...

**Findings:**
- <high|medium|low>: <finding, path:line>

**Fable recheck required:** <yes|no>, <which rule from step 5>

**Verdict:** <merge|send back|needs Fable recheck|needs the user>
```

List every dimension, even with no gaps. Keep bullets short.

## What the main session does with it

- Merge without asking only when: CI is green, the band is low, no dimension is at its worst verdict, every finding is low, and either no Fable recheck was required or it ran and found only low findings.
- Fable recheck required: run it (Agent tool, `model: "fable"`, background), giving it the risk report so it starts from the gaps.
- Any medium or high finding, from either pass: stop and bring it to the user.
- Low findings go into one `bl-<nn>` issue labelled `needs-triage`.
