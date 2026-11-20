---
id: bl-161
title: "Homebrew tap: brew install soulesidibe/tap/loop, with Java 21 and self updates"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: [bl-160]
---

## Parent

[Loop, ready to install builds](../specs/loop-releases.md). Backlog, not scheduled in a milestone.

## What

Lane: human. The work is in a new public repository, `soulesidibe/homebrew-tap`, which Loop does not work.

- **`Formula/loop.rb`.** `desc`, `homepage`, `license "Apache-2.0"`, `url` and `sha256` of the release `loop-<version>.tar.gz`, `depends_on "openjdk@21"`. `install` puts the archive under `libexec`, links `libexec/runtime` to `Language::Java.java_home("21")`, and links `bin/loop` to `libexec/bin/loop`. No wrapper and no `JAVA_HOME`.
- **`test do`.** Runs `env -u JAVA_HOME PATH=/usr/bin:/bin #{libexec}/bin/loop --version` and asserts the version, so the launcher works with no Java on the `PATH`, as when an agent calls it.
- **`livecheck`** with `strategy :github_latest`.
- **`caveats`.** Stop runs with `loop stop` before `brew upgrade loop`: running agents call the launcher through its versioned Cellar path, which the upgrade cleanup deletes.
- **Self update.** `.github/workflows/bump.yml` in the tap, on a daily schedule and by hand: reads the latest release of `soulesidibe/Loop`, and when it is newer than the formula rewrites `url` and `sha256` (from the release's `SHA256SUMS`, checked against the downloaded file) and commits with the tap's own `GITHUB_TOKEN` (`contents: write`). No token of the Loop repository writes to the tap.
- **Tap README.** The install command and a link to Loop.

## Acceptance criteria

- [ ] On a Mac with no other JDK on the `PATH`, `brew install soulesidibe/tap/loop` then `loop --version` prints `loop 0.1.0` (judgment, output in a comment).
- [ ] `brew test soulesidibe/tap/loop` and `brew audit --strict --online soulesidibe/tap/loop` pass (judgment).
- [ ] A Loop run from the Homebrew install on a scratch project with the `command` runner records an agent `loop tool` call, so the launcher on the agent's `PATH` works (judgment).
- [ ] The bump workflow, run by hand with the formula set back one version, commits the right `url` and `sha256` to the tap's main branch (judgment).

## Comments

2026-11-20 soule: written from the releases spec, after the Fable 5.2 review of the plan.
