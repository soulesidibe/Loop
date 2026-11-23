---
id: m4-38
title: "loop report --since, with touches marked not reliable and no cost shown as not reported"
state: todo
priority: 3
labels: [deferred]
blocked_by: [bl-147, m4-01, m4-13, m4-18, m4-33]
---

## Parent

[Loop, M4](../specs/loop-m4.md)

## What

Story 135, PLAN 6.8 and 10.4. Lane C, a new CLI command. Not gating the exit.

Premise, checked first: no Gradle edit needed. `cli` already depends on `store`; `sqlite-jdbc` is an `implementation` dependency of `store` (`store/build.gradle.kts`), so the report's queries live in `store` and `cli` calls them. If a Gradle edit turns out to be needed, stop and move the issue to `needs_info` naming it.

- **Source:** the `attempts` table of the store (`store/src/main/kotlin/loop/store/SqliteEventStore.kt`, around line 868), which holds `tracker`, `kind`, `runner`, `model`, `template_version`, `cost_usd`, `tokens`, `verdict`, `outcome`, `reason` and `human_touches` per attempt, plus the `verdict` events for refutation reasons. Loop's own project only, the one of the `WORKFLOW.md` given.
- **Delivered:** an attempt row ends before the delivery (approval, merge check, merge), so no column says an issue was delivered. An issue counts as delivered when an `issue_moved` event with `by` = `host` and `verified` = `true` falls in the window; its cost is the sum of its attempts' `cost_usd`, whatever their date.
- **Blockers, why:** bl-147, the ledger records `model` null when the runner config names no model, so a comparison by model needs it fixed first. m4-13 puts the tracker id in ledger rows, m4-18 the matched kind; `LedgerMeter.kt` hard codes the kind `code` today (`spec-core/src/main/kotlin/loop/core/orchestrator/LedgerMeter.kt`, around line 129). m4-01 because lane C issues come after the scaffolding. m4-33 because the lane A chain adds commands next to this one in `cli/src/main/kotlin/loop/cli/Main.kt` (`loop answer`, `loop send-back`) and events in `EventDefinitions.kt` and `TraceLines.kt`; observability does not gate the exit, so waiting costs nothing.
- **Output:** issues delivered by kind and tracker, delivered with zero human touches, cost per delivered issue, refutation reasons, and a comparison by runner, model and template, over `--since <duration>` (`7d`, `24h`), default 7 days.
- **Human touches are not reliable:** bl-64 stays needs-info, and the approve touch is rarely counted (a delivered issue with no rework has no later row). The touch figures print with a "not reliable, see bl-64" mark, never as a plain number.
- **A runner that reports no cost** (Codex today) shows its cost as "not reported", never zero, and is left out of the cost averages, which say how many attempts they rest on.
- Related: bl-107 (runner output cut at 2,000 characters hides each request's usage) does not block this; the report reads attempt totals.
- Read only: opens the store with `Redactor.NONE`, writes nothing.

Out of this issue: `loop query` (m4-39), a machine wide report across projects, and settling bl-64.

## Acceptance criteria

- [ ] Premise checked first: no Gradle edit needed, and `diff_policy` refutes a protected edit (judgment).
- [ ] `loop report --since 7d` prints delivered issues by kind and tracker, zero touch deliveries, cost per delivered issue, refutation reasons and the runner, model and template comparison, on a seeded store (test).
- [ ] An issue counts as delivered only through a verified host `issue_moved` in the window, and a verified attempt that was never delivered is not counted (tests).
- [ ] Touch figures carry the "not reliable, see bl-64" mark (test).
- [ ] An attempt with a null `cost_usd` shows "not reported", is left out of the cost averages, and the averages state their attempt count (test).
- [ ] A bad `--since` value exits 2 with a usage line, and an empty window prints a "nothing delivered since" line, exit 0 (tests).
- [ ] The command writes nothing to the store (test comparing the store before and after).
- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.ReportCommandTest'` passes.
- [ ] PLAN section 10.4 describes `loop report` as built, with its exit codes and the delivered rule (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-18 soule: written in m3-29.

2026-11-18 soule: revised after the Fable 5.2 review of the M4 issues (m3-29).
