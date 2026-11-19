---
id: bl-07
title: "loop run stalls about 60 s in CLI tests under parallel Gradle load"
state: todo
priority: 3
labels: [ready-for-human]
blocked_by: []
---

## Parent

None. Backlog, not scheduled in a milestone.

## What

Found while building m1-09. In CLI tests that start a real `loop run` with a stub agent, the `loop run` JVM sometimes stops making progress right after `session started` for about 60 s, then finishes on its own. Seen 5 times, always while other Gradle builds ran on the same machine: 4 times as `loop run --once did not exit in 60s` (TraceCommandTest, ResumeCommandTest, RunCommandTest question round trip) and once as `the agent child outlived loop run` in RunCommandTest "SIGTERM to loop run kills the agent processes before the JVM exits" (the shutdown hook's 10 s ran out before the attempt unwound).

What is known:

- At the hang, `ps` shows the `loop run` JVM in state `RN` (running, niced). The Gradle daemon runs at nice 5, so the test JVMs inherit a low priority.
- The JVM answers neither `jcmd Thread.print` (attach handshake fails) nor SIGQUIT, which fits a JVM starved of CPU that cannot reach a safepoint more than a busy loop in Loop code.
- Not reproduced in 125 standalone runs of the same fixture, with and without load, nor in 5 class reruns on main and 5 on the m1-09 branch. Single test reruns pass.
- No cause found in the m1-09 diff by two reviewers.

## Acceptance criteria

- [ ] The cause is known: a diagnostic run passes `JAVA_OPTS="-XX:+UnlockDiagnosticVMOptions -XX:+SafepointTimeout -XX:SafepointTimeoutDelay=2000 -Xlog:safepoint"` to the `loop run` JVM in the fixture (it strips `JAVA_OPTS` today) and adds `ni,%cpu,rss` to the `ps` columns of `LoopFixture.dumpHang`.
- [ ] Either the code is fixed, or the tests stop depending on scheduling (for example a longer shutdown wait in tests), with the reason written down.

## Decisions (triage)

- Diagnosis comes first. No timeout changes until the diagnostic run (first criterion) has caught at least one stall, or 20 full parallel build runs pass without one, whichever comes first. Write the result in a comment.
- If the safepoint log shows CPU starvation of the niced test JVM and no Loop code at fault, a test-only fix is acceptable: longer waits in `LoopFixture` (for example 120 s for `await`) and a longer wait for the agent child to die in the SIGTERM test, with the reason in a code comment next to each value.
- The production shutdown grace in `RunCommand`'s shutdown hook stays 10 s. A test may not depend on raising it. If the diagnosis points at Loop code (a lock, a blocking call on the shutdown path, a busy loop), fix the code instead and keep the test waits as they are.
- Keep the diagnostic switches cheap to reuse: the fixture passes through a test-only `JAVA_OPTS` value it sets itself, never the inherited one, and the `ps` columns gain `ni,%cpu,rss` for good.
- Not a blocker of m1-12. The installed Loop build runs at normal priority outside Gradle, so the stall is a test harness risk, not a safety limit. Land it soon after, because Loop's own agents (2 at once) will run parallel Gradle builds and a flaky CLI test would cost them turns.
- Related to m1-31 (same theme, a test that flakes under load) but a different module and cause, no shared code. They can land in any order.

## Comments

2026-11-10 triage: ready-for-human, priority 3, diagnose with the safepoint log first, test-only longer waits allowed only if it shows CPU starvation, the 10 s production shutdown grace stays, not a blocker of m1-12.

2026-11-11 moved out of M1 to the backlog: was m1-33, now bl-07. Not needed to close M1.
