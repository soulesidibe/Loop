---
id: bl-160
title: "Release workflow: a tag builds, attests and publishes the archives after approval"
state: todo
priority: 2
labels: [ready-for-human]
blocked_by: [bl-159]
---

## Parent

[Loop, ready to install builds](../specs/loop-releases.md). Backlog, not scheduled in a milestone.

## What

Lane: human. `.github/` is protected since bl-159.

- **`.github/workflows/release.yml`.** Runs on a pushed tag matching `[0-9]+.[0-9]+.[0-9]+`, and by hand (`workflow_dispatch`) as a dry run that publishes nothing.
- **Job `build`.** `permissions: contents: read`, checkout with `fetch-depth: 0` and `persist-credentials: false`, Java 21, `./gradlew build :cli:distTar :cli:distZip`. Unpacks the tarball and fails unless `loop --version` equals the tag (on a dry run, unless it is a non empty version). Uploads both archives as a workflow artifact.
- **Job `publish`.** Only on a tag. `needs: build`, `environment: release` (soule approves), `permissions: contents: write, id-token: write, attestations: write`. Downloads the artifact, attests both archives with `actions/attest-build-provenance`, writes `SHA256SUMS`, and creates the release with `gh release create <tag> --verify-tag --generate-notes` and the three files.
- **`build.yml`.** Skips tag pushes (`push: branches: ['**']` or `tags-ignore`), so a release builds once.
- Every action pinned to a commit SHA with its version in a comment, as in `build.yml`.
- **First release.** After a green dry run, push the tag `0.1.0` on main, approve the `publish` job, and check the release.

## Acceptance criteria

- [ ] A dry run from the Actions tab is green and publishes no release (judgment, run link in a comment).
- [ ] The `0.1.0` release holds `loop-0.1.0.tar.gz`, `loop-0.1.0.zip` and `SHA256SUMS`, and `gh attestation verify loop-0.1.0.tar.gz -R soulesidibe/Loop` passes on a downloaded copy (judgment, output in a comment).
- [ ] The unpacked `loop-0.1.0/bin/loop --version` prints `loop 0.1.0` (judgment).
- [ ] The tag push started no `build.yml` run (judgment).
- [ ] `./gradlew --no-daemon build` passes.

## Comments

2026-11-20 soule: written from the releases spec, after the Fable 5.2 review of the plan.
