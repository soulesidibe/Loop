package loop.runner.codex.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The generator of the codex app server protocol types (m3-02). */
class ProtocolGeneratorTest {
    private val schemaFile = Path.of(System.getProperty("loop.codexSchema"))

    private fun generate(schema: String) = ProtocolGenerator(Json.parseToJsonElement(schema).jsonObject, "1.2.3").generate()

    @Test
    fun `the committed schema leaves only the untagged unions untyped`() {
        val protocol = generate(schemaFile.readText())
        // Each holds members told apart only by their shape: a string or an array, or one of several objects. A new
        // entry after a schema update is a shape the generator does not map yet.
        assertEquals(
            listOf(
                "loop.runner.codex.protocol.JSONRPCMessage (anyOf)",
                "loop.runner.codex.protocol.McpElicitationEnumSchema (anyOf)",
                "loop.runner.codex.protocol.McpElicitationMultiSelectEnumSchema (anyOf)",
                "loop.runner.codex.protocol.McpElicitationPrimitiveSchema (anyOf)",
                "loop.runner.codex.protocol.McpElicitationSingleSelectEnumSchema (anyOf)",
                "loop.runner.codex.protocol.v2.ForcedChatgptWorkspaceIds (anyOf)",
                "loop.runner.codex.protocol.v2.FunctionCallOutputBody (anyOf)",
                "loop.runner.codex.protocol.v2.ResourceContent (anyOf)",
                "loop.runner.codex.protocol.v2.ThreadItemsListCursor (anyOf)",
                "loop.runner.codex.protocol.v2.ThreadListCwdFilter (anyOf)",
            ),
            protocol.untyped,
        )
    }

    @Test
    fun `a schema written by another codex version than the pinned one is refused`() {
        val dir = Files.createTempDirectory("codex-schema")
        val version = dir.resolve("codex-version")
        version.writeText("0.1.0\n")
        val error =
            assertFailsWith<IllegalArgumentException> {
                main(arrayOf(schemaFile.toString(), version.toString(), "0.2.0", dir.resolve("out").toString()))
            }
        assertContains(error.message!!, "regenerate.sh")
        assertFalse(Files.exists(dir.resolve("out")))
    }

    @Test
    fun `a tagged oneOf is a sealed interface with its tag as the discriminator`() {
        val schema =
            """
            {"definitions": {"Item": {"oneOf": [
              {"type": "object", "required": ["type", "text"],
               "properties": {"type": {"type": "string", "enum": ["agentMessage"]}, "text": {"type": "string"}}},
              {"type": "object", "required": ["type"], "properties": {"type": {"type": "string", "enum": ["done"]}}}
            ], "properties": {"at": {"type": ["integer", "null"], "format": "int64"}}}}}
            """.trimIndent()
        val item = generate(schema).files.single { it.path == "loop/runner/codex/protocol/Item.kt" }.content
        assertContains(item, "@kotlinx.serialization.json.JsonClassDiscriminator(\"type\")")
        assertContains(item, "@kotlinx.serialization.SerialName(\"agentMessage\")\n    data class AgentMessage(")
        assertContains(item, "val text: kotlin.String,")
        assertContains(item, "@kotlinx.serialization.SerialName(\"done\")\n    data class Done(")
        assertEquals(2, Regex("val at: kotlin.Long\\? = null,").findAll(item).count(), "the shared property is in each variant")
        assertFalse(item.contains("val type"), "the tag is the discriminator, not a property")
    }

    @Test
    fun `names follow Kotlin conventions whatever the case of the schema`() {
        assertEquals("OnRequest", pascal("on-request"))
        assertEquals("ItemAgentMessageDelta", pascal("item/agentMessage/delta"))
        assertEquals("HttpServer", pascal("HTTPServer"))
        assertEquals("ON_REQUEST", constantName("on-request"))
        assertEquals("V_2_0", constantName("2.0"))
        assertEquals("execpolicyAmendment", propertyName("execpolicy_amendment"))
        assertEquals("meta", propertyName("_meta"))
    }

    @Test
    fun `a description cannot close or open a comment`() {
        assertEquals(listOf("/**", " * a *&#47; b &#47;* c", " */"), kdoc("a */ b /* c"))
        assertEquals("\"a \\\"b\\\" \\\$c\"", literal("a \"b\" \$c"))
    }
}
