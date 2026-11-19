package loop.core.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m2-15: the result an agent writes to `.loop/result.json`, PLAN section 7, parsed and validated. */
class SubmittedResultTest {
    private fun valid(text: String): SubmittedResult = SubmittedResult.parse(text).getOrThrow()

    private fun error(text: String): String {
        val failure = SubmittedResult.parse(text).exceptionOrNull()
        return checkNotNull(failure) { "expected an invalid result: $text" }.message.orEmpty()
    }

    @Test
    fun `a full section 7 result parses with its acceptance proofs`() {
        val result =
            valid(
                """
                {
                  "status": "done",
                  "deliverable": {
                    "type": "pull_request",
                    "branch": "loop/t-1",
                    "pr_url": null,
                    "paths": ["a.txt"],
                    "action_plan": null
                  },
                  "summary": "Added the file.",
                  "acceptance": [
                    { "criterion": "The file exists", "proof": "check", "ref": "a1.evidence-1.log" },
                    { "criterion": "Reads well", "proof": "human", "ref": null }
                  ],
                  "residual_human_steps": [],
                  "followups": [],
                  "notes_for_next_attempt": ""
                }
                """,
            )

        assertEquals(SubmittedResult.Status.Done, result.status)
        assertEquals("Added the file.", result.summary)
        assertEquals(
            listOf(
                SubmittedResult.Acceptance("The file exists", "check", "a1.evidence-1.log"),
                SubmittedResult.Acceptance("Reads well", "human", null),
            ),
            result.acceptance,
        )
    }

    @Test
    fun `only the status is required for done, unknown keys are ignored`() {
        val result = valid("""{ "status": "done", "extra": 1 }""")

        assertEquals(SubmittedResult.Status.Done, result.status)
        assertNull(result.summary)
        assertEquals(emptyList(), result.acceptance)
    }

    @Test
    fun `each status reads by its section 7 name`() {
        assertEquals(SubmittedResult.Status.NeedsInfo, valid("""{ "status": "needs_info", "summary": "Which?" }""").status)
        assertEquals(SubmittedResult.Status.Blocked, valid("""{ "status": "blocked", "summary": "No device" }""").status)
        assertEquals(SubmittedResult.Status.Failed, valid("""{ "status": "failed", "summary": "Gave up" }""").status)
    }

    @Test
    fun `text that is not json, or not an object, is invalid`() {
        assertTrue(error("not json").startsWith("the result is not valid JSON"), error("not json"))
        assertEquals("the result is not a JSON object", error("[1, 2]"))
        assertEquals("the result is not a JSON object", error(""" "done" """))
    }

    @Test
    fun `a missing or unknown status is invalid`() {
        assertEquals("status is required", error("{}"))
        assertEquals("status must be a string", error("""{ "status": 1 }"""))
        assertEquals(
            "status must be one of done, blocked, needs_info, failed, not \"finished\"",
            error("""{ "status": "finished" }"""),
        )
    }

    @Test
    fun `needs_info, blocked and failed need a summary that says why`() {
        assertEquals("summary is required when status is needs_info", error("""{ "status": "needs_info" }"""))
        assertEquals("summary is required when status is blocked", error("""{ "status": "blocked", "summary": "  " }"""))
        assertEquals("summary is required when status is failed", error("""{ "status": "failed", "summary": null }"""))
    }

    @Test
    fun `fields of the wrong type are invalid`() {
        assertEquals("summary must be a string", error("""{ "status": "done", "summary": 3 }"""))
        assertEquals("deliverable must be an object", error("""{ "status": "done", "deliverable": [] }"""))
        assertEquals(
            "deliverable.type must be one of pull_request, files, report, external_action, not \"zip\"",
            error("""{ "status": "done", "deliverable": { "type": "zip" } }"""),
        )
        assertEquals("deliverable.paths must be a list of strings", error("""{ "status": "done", "deliverable": { "paths": [1] } }"""))
        assertEquals("deliverable.branch must be a string", error("""{ "status": "done", "deliverable": { "branch": true } }"""))
        assertEquals("acceptance must be a list", error("""{ "status": "done", "acceptance": {} }"""))
        assertEquals("acceptance[0] must be an object", error("""{ "status": "done", "acceptance": ["x"] }"""))
        assertEquals(
            "acceptance[0].criterion is required",
            error("""{ "status": "done", "acceptance": [{ "proof": "check" }] }"""),
        )
        assertEquals(
            "acceptance[1].proof must be one of check, review, human, not \"vibes\"",
            error(
                """{ "status": "done", "acceptance": [
                   { "criterion": "a", "proof": "check" }, { "criterion": "b", "proof": "vibes" } ] }""",
            ),
        )
        assertEquals(
            "acceptance[0].ref must be a string",
            error("""{ "status": "done", "acceptance": [{ "criterion": "a", "proof": "check", "ref": 2 }] }"""),
        )
        assertEquals("residual_human_steps must be a list", error("""{ "status": "done", "residual_human_steps": "x" }"""))
        assertEquals("followups must be a list", error("""{ "status": "done", "followups": 1 }"""))
        assertEquals("notes_for_next_attempt must be a string", error("""{ "status": "done", "notes_for_next_attempt": [] }"""))
    }
}
