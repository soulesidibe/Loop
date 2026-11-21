---
id: bl-177
title: "A check can opt in to be left to CI in the merge check"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: [bl-172]
---

## Parent

[Serialized delivery](../specs/serialized-delivery.md). Backlog, not scheduled in a milestone.

## What

The local merge check reruns the full `./gradlew build` (up to 29 minutes under load) on the same merged head that GitHub CI builds again. A blanket `hidden_only` mode was rejected (see the spec): CI counts a `skipped` run as passing and names no required workflow, it does not run acceptance commands or file checks, and a red CI goes to a human with no findings for the agent.

A narrower idea to weigh: a per check opt in, for example `merge_recheck: ci` on a `command` check, skipped in the merge check only when all of these hold, whatever the delivery policy:

- `ci: actions` and `ci_no_run: fail`.
- `.github/` is identical between the merged head and the fetched base (Loop's own `guards.protect` lists `.github/` since bl-159, but the auto merge `protected_paths` only bind auto merges, and a push run uses the workflow file at the merged head).
- A required run rule: a named workflow, event `push`, conclusion `success`, never `skipped` (`build.yml` makes a skipped `pull_request` run for same repository branches).
- On a red CI, the full local check runs then, so a semantic merge break still reaches the agent with findings.

Open questions: the m2-08 rerun rule has no CI equivalent, CI runs on ubuntu rather than the operator's machine, and `build.yml`'s 20 minute timeout is close to the build time.

## Acceptance criteria

- [ ] A decision recorded in this issue: build it with the preconditions above, change them, or `wontfix` (judgment).

## Comments

2026-11-21 soule: written from the serialized delivery design, after a Fable 5.2 review at high effort. To triage.
