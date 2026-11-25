---
id: m4-39
title: "loop query runs read only SQL over the store"
state: done
priority: 1
labels: [ready-for-agent]
blocked_by: [m4-38]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 136, PLAN 10.4. Lane C, a new CLI command. Not gating the exit. Blocked by m4-38 only because both register a command in `cli/src/main/kotlin/loop/cli/Main.kt` and read the store through `store`.

Premise, checked first: no Gradle edit needed. `sqlite-jdbc` is an `implementation` dependency of `store` (`store/build.gradle.kts`), so the read only connection is opened in `store` and `cli` calls it. If a Gradle edit turns out to be needed, stop and move the issue to `needs_info` naming it.

- **Command:** `loop query "<sql>" [--workflow WORKFLOW.md] [--format table|json]`, default `table`.
- **Read only for real:** the connection opens the store file with SQLite's read only mode (`mode=ro` or `SQLiteConfig.setReadOnly`), never through `SqliteEventStore.open`, which creates and migrates (`store/src/main/kotlin/loop/store/SqliteEventStore.kt`, around line 921). A write statement fails with SQLite's own error, exit 4, nothing changed. `ATTACH` and `load_extension` are refused too, since `ATTACH` can open another file for writing through a URI name.
- **Scope:** the whole machine store, all projects, since a query may compare them; the output says so on its first line, and the tables are listed with `loop query --tables`.
- **Limits:** at most 1,000 rows printed by default, `--limit` to change it, and a note when rows were left out. A statement timeout, so a bad join cannot hold the store lock against a running `loop run`.
- Secrets: the store holds redacted text only, so nothing more is redacted here.

Out of this issue: saved queries, a query over the JSONL ledger, and an HTTP API.

## Acceptance criteria

- [ ] Premise checked first: no Gradle edit needed, and `diff_policy` refutes a protected edit (judgment).
- [ ] `loop query "select count(*) from attempts"` prints the count on a seeded store (test).
- [ ] An `insert`, `update`, `delete`, `drop`, a `pragma` that writes, an `ATTACH` and a `load_extension` call each fail, and the store file is unchanged (one test each, comparing the file before and after).
- [ ] The query runs while another connection holds the store open for writing, as `loop run` does (test).
- [ ] `--format json`, `--limit` with the left out note, and `--tables` work (tests).
- [ ] A syntax error exits 2 with SQLite's message; a missing store says so, exit 1 (tests).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.QueryCommandTest'` passes.
- [ ] PLAN section 10.4 describes `loop query` as built, with its exit codes (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).

2026-11-25 19:20Z agent 03b01075: Turn 2: the refuted build failed on one test outside this issue, spec-core OrchestratorStallTest "turns fed from other threads always finish with the stall timer on", a 60s JUnit timeout while the test was starting a git process (WorkspaceGit through SessionBrief). The test runs 300 orchestrator rounds that each spawn git, so under the load of the full build it can pass 60s. This branch changes nothing under spec-core, conformance or plugin-api. Rerun alone with --rerun three times, it passed each time in about 35s. The test is not changed or skipped. Moving to review again so the build reruns. A follow up for the flake belongs in the backlog: the 300 git spawns against a 60s timeout.

2026-11-25 19:23Z loop: Loop opened pull request #76 for this issue: https://github.com/soulesidibe/Loop/pull/76

2026-11-25 19:26Z loop: Loop did not merge pull request #76, ci: Actions runs did not pass: build (failure). The pull request stays open and the result waits for a new approval.
  Failed runs:
  - build (failure): https://github.com/soulesidibe/Loop/actions/runs/45684771762
  Rerun the runs on GitHub or fix the branch, then approve again with loop approve.
