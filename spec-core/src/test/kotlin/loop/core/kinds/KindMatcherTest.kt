package loop.core.kinds

import loop.api.Issue
import loop.core.workflow.KindConfig
import loop.core.workflow.KindMatch
import loop.core.workflow.KindsConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m4-18: an issue gets the first kind whose match holds, in the order the kinds are written, and the default kind
 * otherwise. A match holds when every key it sets holds: the issue carries the label, comes from the tracker, or is
 * in the state.
 */
class KindMatcherTest {
    private fun kind(match: KindMatch) = KindConfig(checks = emptyList(), match = match)

    private fun issue(
        labels: List<String> = emptyList(),
        state: String = "todo",
    ) = Issue(
        id = "local:i-1",
        identifier = "i-1",
        title = "Issue",
        state = state,
        priority = null,
        labels = labels,
        blockedBy = emptyList(),
        description = null,
        createdAt = null,
    )

    /** The kinds of a project, in the order written: `code` the default, then the ones that match. */
    private val kinds =
        KindsConfig(
            linkedMapOf(
                "code" to kind(KindMatch.DEFAULT),
                "content" to kind(KindMatch(label = "content")),
                "research" to kind(KindMatch(tracker = "notes")),
                "triaged" to kind(KindMatch(state = "Triaged")),
                "both" to kind(KindMatch(label = "post", state = "draft")),
                "second" to kind(KindMatch(label = "content")),
            ),
        )

    private data class Case(
        val name: String,
        val issue: Issue,
        val tracker: String,
        val expected: String,
    )

    private val cases =
        listOf(
            Case("no match falls to the default", issue(), "local", "code"),
            Case("a label match", issue(labels = listOf("content")), "local", "content"),
            Case("a tracker id match", issue(), "notes", "research"),
            Case("a state match, compared trimmed and case folded", issue(state = " triaged "), "local", "triaged"),
            Case("every key of a match must hold, a label alone is not enough", issue(labels = listOf("post")), "local", "code"),
            Case("every key of a match must hold, both do", issue(labels = listOf("post"), state = "Draft"), "local", "both"),
            Case("the first match wins over a later one", issue(labels = listOf("content"), state = "triaged"), "notes", "content"),
            Case("the first match wins over a later kind of the same match", issue(labels = listOf("content")), "local", "content"),
            Case("a tracker match wins over a later state match", issue(state = "triaged"), "notes", "research"),
            Case("an unrelated label falls to the default", issue(labels = listOf("bug")), "local", "code"),
        )

    @Test
    fun `each issue gets the first kind whose match holds, else the default`() {
        cases.forEach { case ->
            assertEquals(case.expected, KindMatcher.kindOf(kinds, case.issue, case.tracker), case.name)
        }
    }

    @Test
    fun `the default kind need not be first or named code`() {
        val written =
            KindsConfig(
                linkedMapOf(
                    "code" to kind(KindMatch(label = "code")),
                    "content" to kind(KindMatch.DEFAULT),
                    "research" to kind(KindMatch(label = "research")),
                ),
            )

        assertEquals("content", KindMatcher.kindOf(written, issue(), "local"))
        assertEquals("code", KindMatcher.kindOf(written, issue(labels = listOf("code")), "local"))
        assertEquals("research", KindMatcher.kindOf(written, issue(labels = listOf("research")), "local"))
    }

    @Test
    fun `a workflow with only code and no match puts every issue under code`() {
        val only = KindsConfig(KindConfig(checks = emptyList()))

        assertEquals("code", KindMatcher.kindOf(only, issue(labels = listOf("content")), "notes"))
    }
}
