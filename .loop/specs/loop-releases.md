---
id: spec-loop-releases
title: Loop, ready to install builds through GitHub releases and Homebrew
labels: [ready-for-human]
---

## Problem statement

Loop is public since November 2026, but the only way to install it is to clone the repository and run `./gradlew installLoop`. That needs git, the Gradle build of the whole project (about 20 minutes with the tests) and a JDK 21 toolchain. The version is a bare git SHA, so a user cannot tell whether their build is recent or which change it holds.

## Solution

Tagged releases with real version numbers, each with a ready to run archive on GitHub, and a Homebrew tap that installs it with Java 21:

```sh
brew install soulesidibe/tap/loop
```

Linux users without Homebrew download the archive from the release, check it and put `bin/` on their `PATH`. Building from source stays possible, and self hosting keeps `installLoop`.

## User stories

1. As a new user on macOS, I want to install Loop with one Homebrew command, with Java 21 handled for me, so that I can try it without building it.
2. As a Linux user, I want a release archive with a checksum and a provenance attestation, so that I can install Loop and check that it came from this repository's CI.
3. As a user, I want `loop --version` to print a release number such as `0.1.0`, so that I know which release I run and can compare it with the changelog.
4. As the maintainer, I want to publish a release by pushing a tag and approving one step, so that a release is cheap and nothing is published by accident.
5. As the maintainer, I want an agent never to change the release machinery on its own, so that a merged branch cannot publish or leak a token.
6. As a user whose project needs another JDK, I want Loop's own Java 21 to stay out of my agent's environment, so that my project builds with its own toolchain.

## Implementation decisions

Reviewed by Fable 5.2 on 2026-11-20; its findings are folded in below.

### Versions

- Bare semver tags with no `v` prefix, the first one `0.1.0`. Pre 1.0, minor releases may break configuration.
- The version comes from `git describe --tags --match '[0-9]*' --abbrev=12`: `0.1.0` on a tag, `0.1.0-5-g3f2a9c1d7e4b` after it, `-dirty` appended for uncommitted tracked changes. With no tag reachable or a shallow clone without tags, it falls back to the 12 hex SHA of today, and to `unknown` without git.
- `0.1.0-5-g…` is a label, not a semver to compare: it sorts before `0.1.0` in semver. The docs say so.
- `build-info.properties` gains `commit=<full sha>`, so a trace still names the exact build. The readers of `version` (`BuildInfo.kt`, `LoopVersion.kt`) already accept the new shapes.
- `BuildInfoTest` asserts the 12 hex shape today (`cli/src/test/kotlin/loop/cli/BuildInfoTest.kt:76`); it changes in the same commit as `GitVersionSource` (`cli/build.gradle.kts` around line 44), or `./gradlew build` breaks.

### Java

- The release is the platform independent application distribution: `bin/loop`, `lib/*.jar`, `build-info.properties`, about 41 MB, needing Java 21.
- The start script prefers `$APP_HOME/runtime/bin/java` when it exists, then `JAVA_HOME`, then `java` on the `PATH`, and exports nothing. The agent inherits Loop's whole environment (`plugins/runner-process/.../HostEnvironment.kt:20-25`), so a `JAVA_HOME` set for Loop would make the user's project build and verify with Loop's JDK. The `runtime/` lookup avoids that, and is where a bundled runtime goes later.
- Bundling a runtime per platform (jlink) is deferred: four archives of about 90 MB for a gain Homebrew already gives on macOS.

### Archives

- The release archives are `loop-<version>.tar.gz` and `loop-<version>.zip`, top folder `loop-<version>/`, without `bin/loop.bat` (Windows is not supported). Gradle builds an uncompressed, unversioned `loop.tar` today, so the distribution configuration in `cli/build.gradle.kts` sets the gzip compression, the archive version and the exclusion.
- Each release also carries `SHA256SUMS` and a build provenance attestation (`actions/attest-build-provenance`). No signing beyond that for now.

### The release workflow

- `.github/workflows/release.yml`, run by a tag matching `[0-9]+.[0-9]+.[0-9]+`, and by hand (`workflow_dispatch`) as a dry run that builds and attests nothing and publishes nothing.
- Two jobs. `build`: read only permissions, full history (`fetch-depth: 0`), `./gradlew build` and the archives, then a refusal unless the unpacked `loop --version` equals the tag; uploads the archives as a workflow artifact. `publish`: needs `build`, runs in a GitHub environment `release` with the maintainer as required reviewer, holds `contents: write`, `id-token: write` and `attestations: write`, attests, writes `SHA256SUMS` and creates the release with generated notes.
- A tag ruleset lets only the maintainer create or delete version tags.
- `build.yml` skips tag pushes, so a release does not build twice.
- Actions stay pinned to commit SHAs, as in `build.yml`.

### Guarding the release machinery

- `.github/` joins the `protect` list of Loop's own `WORKFLOW.md`. Auto merge is on for the `code` kind, and a workflow with write permissions cannot be verified by `./gradlew build`, so no agent changes CI or release files. A protected path refutes the diff, so every change under `.github/` is human lane from then on.

### Homebrew

- A tap, `soulesidibe/homebrew-tap`, with `Formula/loop.rb`; `brew install soulesidibe/tap/loop`. Homebrew core has no formula named `loop`; moving to core is for later, and its reviewers will ask about the bundled sqlite-jdbc native libraries.
- The formula downloads the release `.tar.gz`, installs it under `libexec`, links `libexec/runtime` to the JDK home of `openjdk@21` (`Language::Java.java_home("21")`, which resolves the macOS and Linux layouts), and links `bin/loop` to `libexec/bin/loop`. No wrapper sets `JAVA_HOME`.
- Its test runs `env -u JAVA_HOME PATH=/usr/bin:/bin #{libexec}/bin/loop --version` and checks the version, so the launcher works without any Java on the `PATH`, as it must when an agent calls it.
- `livecheck` with `strategy :github_latest`.
- Caveats: stop runs with `loop stop` before `brew upgrade loop`. A run's agents call the launcher through its versioned Cellar path, which the upgrade's cleanup deletes; `installLoop` refuses during a live run, Homebrew cannot.
- The tap updates itself: a workflow in the tap repository, on a schedule and by hand, reads the latest Loop release, and when it is newer rewrites the formula's `url` and `sha256` and commits with the tap's own `GITHUB_TOKEN`. No token of the Loop repository can write to the tap.

### Lanes

Everything under `.github/`, the build scripts and the tap repository is human lane (protected paths, or a repository Loop does not work). Only the install documentation is agent work, once a release exists to document.

## Issues

- bl-159 (ready-for-human): versions from tags, the `runtime/` lookup, the release archives, `.github/` protected, the repository settings (environment, tag ruleset).
- bl-160 (ready-for-human, after bl-159): `release.yml` and `build.yml` skipping tags, then the first tag `0.1.0`.
- bl-161 (ready-for-human, after bl-160): the tap repository, the formula and its self update workflow.
- bl-162 (ready-for-agent, after bl-161): the README and `docs/self-hosting.md` install paths.

## Testing decisions

- The version logic is tested through `BuildInfoTest` and a unit test of the parsing of `git describe` output (tag, after tag, dirty, no tag, no git).
- The `runtime/` lookup is tested on the generated start script: with a fake `runtime/bin/java`, with only `JAVA_HOME`, with only `PATH`.
- The release workflow is proven by its dry run, then by the `0.1.0` release.
- The formula is proven by `brew install --build-from-source`, `brew test` and `brew audit --strict` on the tap.

## Out of scope

- Bundled runtimes (jlink) and native images.
- Windows.
- Homebrew core, other package managers (apt, Nix, SDKMAN).
- Automatic updates inside Loop, and a changelog file beyond the generated release notes.

## Further notes

- Self hosting stays on `installLoop`, which refuses during a live run; the docs keep it as the way to run Loop on its own issues.
- No code outside the docs assumes `~/.loop/install`, the source tree or a writable install folder (checked in the Fable review).
