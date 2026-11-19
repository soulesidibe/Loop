#!/bin/sh
# Regenerates the codex app server JSON schema from the pinned codex CLI (m3-02).
# Run from anywhere: plugins/runner-codex-app-server/schema/regenerate.sh
# The pinned version is codex-cli in gradle/libs.versions.toml. Install that exact version first:
#   npm install -g @openai/codex@<version>
# The build then regenerates the Kotlin types from the new schema.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
pinned=$(sed -n 's/^codex-cli = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
installed=$(codex --version | awk '{print $2}')
if [ "$installed" != "$pinned" ]; then
    echo "codex $installed is installed, gradle/libs.versions.toml pins $pinned" >&2
    exit 1
fi
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
codex app-server generate-json-schema --out "$out"
cp "$out/codex_app_server_protocol.schemas.json" "$here/codex_app_server_protocol.schemas.json"
printf '%s\n' "$pinned" > "$here/codex-version"
echo "schema regenerated from codex $pinned"
