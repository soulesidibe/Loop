---
id: bl-269
title: "An attempt's queued resource request is granted after it got another one, so it holds both simulators"
state: todo
priority: 3
labels: [needs-triage]
blocked_by: []
---

## Parent

None. Backlog. Found in the m4-43 wave on Sigma, 2026-11-27, issue Sigma-199 attempt 68b8b710.

## What

The agent asked for `sim-a` (queued, held by Sigma-197) at 15:38:24, then for `sim-b` (granted 15:42:52). Its place in `sim-a`'s queue was kept, and `sim-a` was granted to it at 15:58:42. The attempt then held both of the machine's two simulators until it ended at 16:06:25, so any check needing one would have waited. The prompt asked agents to hold at most one, but nothing enforces it; checks already drop their other requests when one is granted (`any_of_kind`).

## Agent brief

When an attempt is granted a resource of a kind, drop its other queued requests of the same kind (with a `resource_queue_left` or similar event), unless the agent asks for more than one explicitly. Say it in the `request_resource` tool description.

## Acceptance criteria

- [ ] A test: queued on resource A, granted resource B of the same kind, then A frees: A goes to the next holder, not to this attempt.
- [ ] An explicit request for a second resource of the kind after holding one still queues.
- [ ] The tool description and the m4-25 docs state the rule.
- [ ] `./gradlew build` passes.
- [ ] No protected path is touched: scripts/, .github/, WORKFLOW.md, CONTEXT.md, *.gradle.kts, */*.gradle.kts, plugins/*/*.gradle.kts, gradle/, build-logic/src/, gradle.properties, gradlew*, .loop/specs/, docs/adr/.

## Comments
