# Vendored Symphony spec

This folder holds the exact Symphony service specification Loop implements, copied from upstream without any change. Loop's answers to the spec's ambiguities, its extensions and its deviations live in [`../spec-decisions.md`](../spec-decisions.md), never in this folder.

## Provenance

| Field | Value |
|---|---|
| Upstream repository | [openai/symphony](https://github.com/openai/symphony) |
| Upstream path | `SPEC.md` |
| Pinned commit | `8001b52e3062495a16e520e4ceaf8f9de868c4d0` |
| Commit date | 2026-08-12 (18:19:47 UTC) |
| Source URL | https://github.com/openai/symphony/blob/8001b52e3062495a16e520e4ceaf8f9de868c4d0/SPEC.md |
| Spec status | Draft v1 (language agnostic) |
| Vendored on | 2026-11-06, in issue m1-01 |
| License | Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE), both fetched from the same commit |

## Files

| File | Content |
|---|---|
| `SPEC.md` | Upstream `SPEC.md` at the pinned commit, byte for byte |
| `LICENSE` | Upstream `LICENSE` at the pinned commit |
| `NOTICE` | Upstream `NOTICE` at the pinned commit |
| `README.md` | This file, written for Loop |

## Expected hashes

| Hash | Value |
|---|---|
| SHA-256 of `SPEC.md` | `c6638056502ecd2e60eb04e1f1a627a4964506fcdf4d39873e78aa5834de85d5` |
| Git blob SHA of `SPEC.md` (same as upstream) | `cd24131a1e2358cbfecc4f6efb028fc9fc6edefc` |
| Size | 92077 bytes |

## Verify the copy

Compare the vendored file with upstream at the pinned commit (needs the GitHub CLI, run from the repository root). It prints the same hash twice when the copy is intact:

```sh
gh api 'repos/openai/symphony/contents/SPEC.md?ref=8001b52e3062495a16e520e4ceaf8f9de868c4d0' -q .content | base64 -d | shasum -a 256; shasum -a 256 < docs/symphony/SPEC.md
```

Offline, `shasum -a 256 docs/symphony/SPEC.md` must print the SHA-256 above.

## Update the pin

Never edit `SPEC.md` by hand. To move Loop to a newer spec commit:

1. Find the commit: `gh api 'repos/openai/symphony/commits?path=SPEC.md&per_page=1' -q '.[0].sha'`.
2. Fetch `SPEC.md`, `LICENSE` and `NOTICE` at that commit into this folder with `gh api 'repos/openai/symphony/contents/<file>?ref=<sha>' -q .content | base64 -d > docs/symphony/<file>`.
3. Read the upstream diff between the old and the new commit, and decide how each change affects Loop.
4. Update the provenance table and the expected hashes in this file.
5. Update the pinned spec section of [`../spec-decisions.md`](../spec-decisions.md) and add an entry to its spec changes log. A pin change without a log entry is incomplete.
6. Run `./gradlew build`, so the conformance tests catch drift.
