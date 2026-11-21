---
id: bl-154
title: "Check in the build that every test the conformance matrix names exists"
state: done
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

2026-11-20 23:05Z agent b74e4257: ConformanceMatrixTest in the conformance module checks every path and test name of the matrix, and fails on one broken path and one broken name. The diff policy protects conformance/build.gradle.kts, so the test finds the repository root from its working folder and the task declares no new inputs. Effect: a :conformance:test that Gradle finds up to date or in its build cache skips the check after a change to docs/spec-decisions.md or to another module's tests alone. Proven: real doc broken, task UP-TO-DATE and green; with --rerun it fails naming both. The matrix intro says so. To close the gap, a human can add this to conformance/build.gradle.kts:
  val repoRoot = layout.projectDirectory.dir("..")
  tasks.test {
      inputs.file(repoRoot.file("docs/spec-decisions.md")).withPropertyName("specDecisions").withPathSensitivity(PathSensitivity.RELATIVE)
      inputs.files(fileTree(repoRoot) { include("*/src/test*/kotlin/**/*.kt", "plugins/*/src/test*/kotlin/**/*.kt") }).withPropertyName("testSources").withPathSensitivity(PathSensitivity.RELATIVE)
  }
  then drop the last sentence of the matrix intro paragraph added by bl-154.

2026-11-21 12:43Z loop: Loop opened pull request #6 for this issue: https://github.com/soulesidibe/Loop/pull/6
