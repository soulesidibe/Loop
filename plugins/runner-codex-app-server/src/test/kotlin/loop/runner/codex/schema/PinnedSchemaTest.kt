package loop.runner.codex.schema

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** m3-18: the schema check behind the startup payload test refuses what the pinned app server would not take. */
class PinnedSchemaTest {
    private val schema = PinnedSchema.load()

    @Test
    fun `a valid initialize request has no problem`() {
        val request = """{"method":"initialize","id":1,"params":{"clientInfo":{"name":"loop","version":"1"}}}"""

        assertEquals(emptyList(), schema.problems("ClientRequest", Json.parseToJsonElement(request)))
    }

    @Test
    fun `a missing required member, a value outside an enum and a wrong type are problems`() {
        assertProblem("InitializeParams", """{"clientInfo":{"name":"loop"}}""", "$.clientInfo.version is required")
        // An optional member is `anyOf` its type and null, so its problem shows at the member.
        assertProblem("v2/ThreadStartParams", """{"sandbox":"full"}""", "$.sandbox matches no option of anyOf")
        assertProblem("v2/ThreadStartParams", """{"cwd":7}""", "$.cwd is not of type string")
        assertProblem("v2/TurnStartParams", """{"input":[]}""", "$.threadId is required")
    }

    @Test
    fun `a union value that matches no variant is a problem`() {
        assertProblem("v2/ThreadStartParams", """{"approvalPolicy":"sometimes"}""", "$.approvalPolicy matches no option")
        assertProblem(
            "v2/TurnStartParams",
            """{"threadId":"t","input":[],"sandboxPolicy":{"type":"none"}}""",
            "$.sandboxPolicy matches no option",
        )
        assertProblem("v2/TurnStartParams", """{"threadId":"t","input":[{"type":"text"}]}""", "$.input[0] matches 0 options of oneOf")
        assertProblem("ClientRequest", """{"method":"thread/begin","id":2,"params":{}}""", "$ matches 0 options of oneOf")
    }

    @Test
    fun `a member the schema does not declare is a problem, so a misspelled policy field shows`() {
        assertProblem("v2/ThreadStartParams", """{"approvalPolicyy":"never"}""", "$.approvalPolicyy is not a member the schema declares")
    }

    private fun assertProblem(
        definition: String,
        json: String,
        expected: String,
    ) {
        val problems = schema.problems(definition, Json.parseToJsonElement(json))
        assertTrue(problems.any { it.startsWith(expected) }, "no problem starting with \"$expected\" in $problems")
    }
}
