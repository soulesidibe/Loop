---
id: m4-39
title: "loop query runs read only SQL over the store"
state: todo
priority: 3
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
