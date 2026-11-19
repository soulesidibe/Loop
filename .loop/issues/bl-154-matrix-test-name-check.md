---
id: bl-154
title: "Check in the build that every test the conformance matrix names exists"
state: todo
priority: 3
labels: [ready-for-agent]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone. Found by the merge risk review of pull request #118 (m3-26), Loop's `claude-sonnet-5-5` reviewer, band low 1.00 before the CI floor, on 2026-11-18.

## What

m3-26 checked the conformance matrix in `docs/spec-decisions.md` with a one off Python script that was not committed: each backticked `.kt` path must be a file, and each backticked test name in the parentheses after a path must appear in that file as a test function. It found 151 paths and 165 names, none missing. Nothing keeps that true: a renamed or deleted test leaves the matrix pointing at nothing, and only the next audit finds it.

## Acceptance criteria

- [ ] A check that runs in `./gradlew --no-daemon build`, or a decision recorded here not to add one, fails when a path or test name of the matrix does not exist.
- [ ] The check fails on a matrix with one broken path and one broken name, and passes on the current matrix.

## Comments

2026-11-18 soule: filed from the m3-26 session.

2026-11-18 soule: triaged ready-for-agent in m3-29, no blocker. Its criteria stand.
