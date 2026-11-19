# Codex app server schema

The JSON schema of the codex app server protocol, written by the pinned codex CLI (m3-02). The build generates the module's Kotlin types from it.

- `codex_app_server_protocol.schemas.json`: the bundle `codex app-server generate-json-schema` writes, every type of the stable protocol (v1 handshake and envelope at the top, the rest under `v2`). Do not edit it.
- `codex-version`: the codex version that wrote it. The build refuses a schema whose version is not `codex-cli` in `gradle/libs.versions.toml`.
- `regenerate.sh`: writes both again.

## Regenerate

After changing `codex-cli` in `gradle/libs.versions.toml`, install that exact version and regenerate:

```bash
npm install -g @openai/codex@0.167.0
```

```bash
plugins/runner-codex-app-server/schema/regenerate.sh
```

The script refuses when the installed codex is not the pinned one. It runs `codex app-server generate-json-schema --out <temporary dir>` (stable methods only, no `--experimental`) and copies the bundle here. No account is needed. Then build the module:

```bash
./gradlew --no-daemon :plugins:runner-codex-app-server:build
```

`ProtocolGeneratorTest` lists the schema places left untyped as `JsonElement`. A new entry there after a regeneration is a shape the generator does not map yet.

## The generated types

`generateCodexProtocol` runs the generator in `src/generator` and writes the types to `build/generated/codex-protocol`, packages `loop.runner.codex.protocol` and `loop.runner.codex.protocol.v2`. They are never committed and ktlint skips them. The mapping is described in `ProtocolGenerator`'s KDoc and in `docs/spec-decisions.md`, section "Codex CLI, app server schema and MCP loading (m3-02)".

Read and write them with `loop.runner.codex.protocol.CodexJson.format`. Write a union through its base type, `ClientRequest.serializer()` for example, never through a variant's own serializer: only the base writes the `method` or `type` tag.
