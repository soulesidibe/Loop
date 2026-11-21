---
id: bl-159
title: "Release groundwork: versions from tags, a runtime folder for Java, release archives, .github protected"
state: done
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

2026-11-21 soule: done, plan reviewed by Fable 5.2 before the work.

- Versions: `GitVersion` (`build-logic/src/main/kotlin/loop/buildlogic/version/`, package `loop.buildlogic.version` since `.gitignore` ignores any `build/` folder) parses `git describe --tags --match '[0-9]*' --abbrev=12 --always --dirty` and `git rev-parse HEAD`. A tag that is not a semver falls back to the 12 hex SHA. `build-info.properties` gains `commit=<full sha>`. `GitVersionTest` (compiled into the cli tests from build-logic) covers a tag, after a tag, dirty, no tag, no git and a non semver tag; `BuildInfoTest` accepts every shape. A throwaway clone printed `5840760549fb`, `0.1.0-1-g5840760549fb`, `0.2.0`, `0.2.0-dirty`.
- Configuration cache: the archives name themselves after the version, so a build that includes them runs git while configuring and reconfigures after a new commit. Documented in the build script and `docs/self-hosting.md`.
- Runtime: the start script checks `$APP_HOME/runtime/bin/java`, then `JAVA_HOME`, then `PATH`, exports nothing, and the build fails if Gradle's template loses its JAVA_HOME check. `StartScriptTest` passes for runtime only (child sees JAVA_HOME unset), JAVA_HOME only, PATH only, and runtime plus JAVA_HOME (runtime wins, JAVA_HOME reaches the child unchanged).
- Archives: `:cli:distTar :cli:distZip` wrote `loop-ce33f44cb6a3-dirty.tar.gz` (gzip) and `loop-ce33f44cb6a3-dirty.zip`. Both have the single top folder `loop-ce33f44cb6a3-dirty/`, holding `build-info.properties`, `bin/loop` and `lib/`, and no `bin/loop.bat` (installDist keeps it). The unpacked `bin/loop --version` printed `loop ce33f44cb6a3-dirty`.
- `.github/` is in the `protect` list with its comment; `OwnWorkflowTest` and the protect list section of `docs/self-hosting.md` updated. `grep -qE '^ +- \.github/' WORKFLOW.md` passes.
- Repository settings, checked with `gh api`: environment `release` with soulesidibe as required reviewer, `prevent_self_review: false`, custom deployment policy allowing only tags `[0-9]*.[0-9]*.[0-9]*`. Ruleset "Release tags" (id 27794169): active, target tag, `refs/tags/[0-9]*`, rules creation, update, deletion, bypass the repository admin role only. A `workflow_dispatch` dry run from `main` cannot enter `release` (bl-160).
- The first full build failed on `InstallCommandTest`, which compared the whole `build-info.properties` to the version line; it now checks the copy is identical and holds a 40 hex commit. `./gradlew --no-daemon build` then passed on the branch merged with origin/main (bl-150, bl-154, bl-68).
