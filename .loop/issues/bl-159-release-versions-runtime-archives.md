---
id: bl-159
title: "Release groundwork: versions from tags, a runtime folder for Java, release archives, .github protected"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: []
---

## Parent

[Loop, ready to install builds](../specs/loop-releases.md). Backlog, not scheduled in a milestone.

## What

Lane: human. Every change is a protected path (`cli/build.gradle.kts`, `WORKFLOW.md`) or a repository setting.

1. **Versions from tags.** `GitVersionSource` (`cli/build.gradle.kts` around line 44) uses `git describe --tags --match '[0-9]*' --abbrev=12`: `0.1.0` on a tag, `0.1.0-5-g<12 hex>` after it, `-dirty` for uncommitted tracked changes. No tag reachable or no tags fetched falls back to the 12 hex SHA, no git to `unknown`, never failing the build. `generateBuildInfo` adds `commit=<full sha>`. Update `BuildInfoTest.kt:76` in the same commit, and the comments of `BuildInfo.kt` and `LoopVersion.kt` that describe the version.
2. **A runtime folder for Java.** The generated start script prefers `$APP_HOME/runtime/bin/java` when it is executable, then `JAVA_HOME`, then `java` on the `PATH`, and exports nothing (a custom template or an edit of the `startScripts` output). Agents inherit Loop's environment, so Loop must never set `JAVA_HOME` for them.
3. **Release archives.** `distTar` writes `loop-<version>.tar.gz` (gzip), `distZip` writes `loop-<version>.zip`, both with the top folder `loop-<version>/` and without `bin/loop.bat`. `installDist` and `installLoop` stay as they are.
4. **`.github/` protected.** Add `.github/` to the `protect` list of `WORKFLOW.md` (`kinds.code.verify.guards`), with a comment: CI and release files hold write permissions that `./gradlew build` cannot verify. Update `OwnWorkflowTest` if it asserts the list, and the protect list section of `docs/self-hosting.md`.
5. **Repository settings.** A GitHub environment `release` with soule as required reviewer, limited to version tags. A tag ruleset on `[0-9]*` that lets only soule create, update or delete such tags.
6. **Docs.** `docs/self-hosting.md` (Version section) and `README.md` describe the new version shapes, that `0.1.0-5-g…` is a label and not a semver to compare, and the `runtime/` lookup.

## Acceptance criteria

- [ ] `./gradlew --no-daemon :cli:test --tests 'loop.cli.BuildInfoTest'` passes, accepting a tag version, an after tag version and the SHA fallback.
- [ ] A unit test of the `git describe` parsing covers a tag, an after tag commit, a dirty tree, no tag and no git (test).
- [ ] With a fake executable `runtime/bin/java` in an installed distribution and `JAVA_HOME` unset, `bin/loop --version` uses it; with only `JAVA_HOME`, or only `java` on the `PATH`, it uses that; the script never exports `JAVA_HOME` (test or a recorded manual run, judgment).
- [ ] `./gradlew --no-daemon :cli:distTar :cli:distZip` passes and writes `cli/build/distributions/loop-<version>.tar.gz` and `.zip`, whose top folder is `loop-<version>/` and which hold no `bin/loop.bat` (judgment, listed in a comment).
- [ ] `grep -qE '^ +- \.github/' WORKFLOW.md` passes.
- [ ] The `release` environment and the tag ruleset exist, checked with `gh api` and noted in a comment (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-20 soule: written from the releases spec, after the Fable 5.2 review of the plan.
