# Merge check left to CI

Loop's local merge check always runs the full checks on the merged head, even when GitHub CI builds the same head again. Leaving a check to CI, either as a blanket `hidden_only` mode or as a per check opt in such as `merge_recheck: ci`, is out of scope.

## Why this is out of scope

The blanket mode was rejected in the serialized delivery spec: CI counts a `skipped` run as passing and names no required workflow, so it can read green with nothing built. CI does not run acceptance commands or file checks, and a red CI goes to a human with no findings for the agent.

The narrower per check opt in would need every one of these to hold: `ci: actions` with `ci_no_run: fail`, an unchanged `.github/` between the merged head and the base, a required run rule (named workflow, event `push`, conclusion `success`, never `skipped`), and the full local check after any red CI. Even then:

- CI runs on ubuntu, not on the operator's machine, so it does not check the same environment.
- The m2-08 rerun rule has no CI equivalent.
- `build.yml`'s 20 minute timeout is close to the build time, so a timeout would read as a red CI.

The time saved does not cover that. bl-176 already removes the merge rounds that cost the most, the ones caused by a base move in `recheck_skip_paths` only.

## Prior requests

- bl-177, "A check can opt in to be left to CI in the merge check"
