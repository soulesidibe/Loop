# Fast forward of the base instead of the API merge

Loop merges a pull request through GitHub's merge API (m2-26). Pushing the checked merged head straight to the base as an unforced fast forward, an atomic compare and swap, is out of scope.

## Why this is out of scope

The fast forward would close the window where a commit lands on the base between Loop's base read and the merge, since GitHub's sha guard covers only the pull request's head. That window is small, documented in m2-26, and narrowed by bl-174's recheck of the merged head. Against it:

- `method: squash` cannot work this way.
- The token needs to push to the base, past branch rules, which conflicts with a worker token that cannot merge in M5.
- The history loses the merge commit GitHub makes, so first parent order changes.

## Prior requests

- bl-179, "Fast forward the base to the merged head instead of the API merge"
