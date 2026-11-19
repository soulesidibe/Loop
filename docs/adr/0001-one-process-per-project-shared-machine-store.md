# One Loop process per project, sharing one machine-level store

Loop runs one process per project (one `WORKFLOW.md`), as the Symphony spec assumes, but all processes on a machine share a single store in a machine-level folder (`~/.loop/`) rather than a store inside each repository. Every record carries a `project_id`. Machine-wide concerns (device locks, the global daily budget, a global concurrency cap) are coordinated through transactions in that shared store, not through a host process.

## Considered options

- **One store per repository:** simplest, but a machine-wide budget or device lock has nothing to read from, and merging stores later is a migration.
- **One Loop host process for all projects:** coordinates in memory, but departs from the spec's process model and is more to build in M1.

## Consequences

Moving to a single host process later stays cheap, because the data is already shaped for several projects. The code must avoid process-wide singletons (config, store) so several orchestrators can share one process if that move happens.
