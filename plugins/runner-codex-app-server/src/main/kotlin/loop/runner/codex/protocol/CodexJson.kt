package loop.runner.codex.protocol

import kotlinx.serialization.json.Json

/**
 * The JSON settings the generated protocol types are meant for (m3-02). A field the pinned schema does not know is
 * ignored, a missing optional field reads as null, and a null field is left out when writing, so the app server
 * applies its own default.
 *
 * Write a union through its base type, `ServerRequest.serializer()` or `AskForApproval.serializer()`, never through a
 * variant's own serializer: only the base writes the `method` or `type` tag.
 */
object CodexJson {
    val format: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
}
