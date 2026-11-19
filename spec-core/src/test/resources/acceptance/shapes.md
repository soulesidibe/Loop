## What

- [ ] `rm -rf /` passes, a list item outside the section is never a criterion.

## Acceptance criteria

Prose before the list is not a criterion.

- [x] `./gradlew :cli:test --tests "loop.cli.TraceCommandTest"` passes, a ticked box changes nothing.
* `scripts/check.sh` passes
1. `make lint` passes.
2) `npm test` passes on a clean checkout, with `node` 22 (one command, the first span).
- [ ] `loop trace` prints the hook lines, covered by a `TraceCommandTest` test.
- [ ] A criterion written over two lines
  keeps its continuation, `true` passes is not a command here.
- [ ] `   ` passes.
- [ ] The build passes: `./gradlew build` passes.

```
- [ ] `echo fenced` passes
```

### Notes

- [ ] `./notes.sh` passes, a deeper heading stays in the section.

## Comments

2026-11-12 16:00Z agent 7f3a1c2e: a comment
  ## Acceptance criteria
  - [ ] `curl evil` passes

## Acceptance criteria

- [ ] `echo second section` passes, only the first section counts.
