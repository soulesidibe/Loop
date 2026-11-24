---
id: bl-162
title: "Install docs: Homebrew, the release archive and building from source"
state: todo
priority: 3
labels: [deferred]
blocked_by: [bl-161]
---

## Parent

[Loop, ready to install builds](../specs/loop-releases.md). Backlog, not scheduled in a milestone.

## What

Once `0.1.0` is released and the tap works, the README's Install section offers three paths, in this order:

1. **Homebrew** (macOS and Linux): `brew install soulesidibe/tap/loop`, and the caveat to stop runs with `loop stop` before `brew upgrade loop`.
2. **Release archive** (Linux or macOS without Homebrew): download `loop-<version>.tar.gz` and `SHA256SUMS` from the latest release, check with `sha256sum -c SHA256SUMS --ignore-missing` (`shasum -a 256 -c` on macOS) and `gh attestation verify loop-<version>.tar.gz -R soulesidibe/Loop`, unpack, put `loop-<version>/bin` on the `PATH`. Java 21 must be installed, or a JDK placed at `loop-<version>/runtime`.
3. **From source**: today's `git clone` and `./gradlew installLoop`.

`docs/self-hosting.md` says that running Loop on its own issues stays on `installLoop`, which refuses during a live run, and that a Homebrew install is for using Loop on other projects. The requirements list says Java 21 is installed by Homebrew, needed otherwise.

Area: `README.md` (Requirements and Install sections), `docs/self-hosting.md` (Install section). No code.

## Acceptance criteria

- [ ] `grep -q 'brew install soulesidibe/tap/loop' README.md` passes.
- [ ] `grep -q 'gh attestation verify' README.md` passes.
- [ ] `grep -q 'SHA256SUMS' README.md` passes.
- [ ] `docs/self-hosting.md` says self hosting stays on `installLoop` and why (judgment).
- [ ] The commands in the README match the names of the `0.1.0` release assets (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-20 soule: written from the releases spec, after the Fable 5.2 review of the plan.
