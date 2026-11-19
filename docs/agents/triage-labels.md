# Triage labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the label strings used in this repo's issue tracker. Labels go in each issue's `labels` front matter field.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (for example "apply the AFK-ready triage label"), use the corresponding label string from this table.

## How the labels fit Loop's tracker

- `ready-for-agent`: Loop dispatches only `todo` issues with this label.
- `ready-for-human`: built by hand (before the bootstrap point); Loop never dispatches it.
- `needs-info` goes with the state `needs_info`; `wontfix` goes with the state `canceled`.
- `deferred`: a triaged issue whose brief is complete but which waits for the current milestone to close. Loop never dispatches it. Set it back to `ready-for-agent` to dispatch it. Not a skill role.
