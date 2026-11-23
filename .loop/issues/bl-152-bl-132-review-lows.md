---
id: bl-152
title: "Low findings of the bl-132 merge risk reviews, usage at a stop and the rotated issue re-read"
state: done
priority: 3
labels: [ready-for-agent]
blocked_by: [m4-04]
---

## Parent

None. Backlog, not scheduled in a milestone. Found by the merge risk reviews of pull request #117 (bl-132), Loop's own `claude-sonnet-5-5` reviewer (band low 1.40) and a Fable 5.2 review before the approval (band low, nothing blocking), run 5c1fff40 on 2026-11-18, during m3-27. Lines at head 880e3df.

## What to look at

- A wake's usage counts only if its `result` line arrives within the 5 second stop grace (`ClaudeCodeRunner.kt:323`, plausible). A wake in the middle of a model call when a rotation stops the session is killed, and its usage is lost with no trace event, unlike `interrupt_grace_expired`.
- The stop at the attempt's end still calls `stop()` without the usage callback (`Orchestrator.kt:2423`, confirmed). A wake that ended while idle after the last turn (review, max turns, failure) is read and discarded, so its spend never reaches the budgets or the ledger.
- No test covers a budget passed by usage read at the stop (`Orchestrator.kt:2902`, confirmed). On that path, and on a stop or render failure, the wake's usage is in the attempt totals but in no turn line, so the ledger's turn lines sum to less than the attempt total.
- When the stop throws after reporting usage, a budget breach set by that usage is ignored (`Orchestrator.kt:2898`, confirmed). The attempt ends as a `session_stop_failed` error retry, and the next attempt's check before its first turn catches the budget.
- The tracker re-read runs after `pendingRotation` is set and outside any guard (`Orchestrator.kt:2923`, plausible). A `loop stop` or a reconciliation cancel during it loses `session_rotated` from the trace, the gap bl-132 closed for render failures, now over up to three GitHub requests instead of a local git read.
- The re-read fallback is the dispatch copy, not the fresher issue the turn end read moments earlier; an issue that became ineligible between the two reads still gets a new session for one turn; a failed read logs two warnings, `tracker` and `rotation_issue` (`Orchestrator.kt:2970`).
- Docs: the `completeIssue` KDoc still says it runs once per dispatch and a failure starts no attempt (`plugin-api/.../TrackerAdapter.kt:39`), and `docs/spec-decisions.md` (the runner API near line 918, the stop near line 2088) does not mention `stop(usage)`.
- `AgentRunnerContract` does not exercise `stop(usage)`, so a runner that overrides it is unchecked.
- Sonnet also noted that a failed issue refresh only warns. That is the documented fallback, kept here for the record.

## Acceptance criteria

- [ ] `./gradlew --no-daemon build` passes.
- [ ] `session_rotated` is in the trace when `loop stop` or a reconciliation cancel lands during the tracker re-read after `pendingRotation` is set. A test covers it.
- [ ] The rotated issue re-read falls back to the issue the turn end read, not the dispatch copy, and a failed read logs one warning, not two.
- [ ] The `completeIssue` KDoc and `docs/spec-decisions.md` describe `stop(usage)`, and `AgentRunnerContract` exercises it.

## Comments

2026-11-18 soule: triaged ready-for-agent in m3-29. Findings 1 to 4 (the wake usage lost at a stop, the discarded wake after the last turn, the untested budget read, the budget breach ignored when the stop throws) moved to m4-04. The rest stays here, blocked by m4-04 so they never run side by side.

2026-11-23 11:54Z loop: Loop opened pull request #26 for this issue: https://github.com/soulesidibe/Loop/pull/26

2026-11-23 16:10Z loop: Loop did not merge pull request #26: main at ca9029cab5b3601fdabcc1c086ec18d86f2da31b was merged into loop/bl-152, but the checks failed on the result (a check failed). The merge commit stays on the branch. The next attempt fixes the result and submits it again.

2026-11-23 18:48Z loop: Loop updated pull request #26 for this issue: https://github.com/soulesidibe/Loop/pull/26

2026-11-23 19:15Z loop: Loop did not merge pull request #26: main at 4b8690060aadfc3a21a178bd1d8e5a81ffa18b49 was merged into loop/bl-152, but the checks failed on the result (a check failed). The merge commit stays on the branch. The next attempt fixes the result and submits it again.

2026-11-23 21:49Z loop: Loop updated pull request #26 for this issue: https://github.com/soulesidibe/Loop/pull/26

2026-11-23 22:01Z loop: Loop could not merge pull request #26: merging main at ef6e7fdd60a3fe12b8839acd2dd8969a174b0931 into loop/bl-152 conflicts (conflict in docs/spec-decisions.md). The merge was aborted and the branch is unchanged. The next attempt merges refs/loop/base/main into its branch, resolves the conflict and submits the result again.

2026-11-23 23:05Z loop: Loop updated pull request #26 for this issue: https://github.com/soulesidibe/Loop/pull/26
