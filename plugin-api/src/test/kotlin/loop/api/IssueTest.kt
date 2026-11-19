package loop.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueTest {
    private fun issue(blockedReason: String? = null) =
        Issue(
            id = "a-1",
            identifier = "a-1",
            title = "A",
            state = "todo",
            priority = null,
            labels = emptyList(),
            blockedBy = listOf("a-0"),
            description = null,
            createdAt = null,
            blockedReason = blockedReason,
        )

    @Test
    fun `an issue without a blocked reason is dispatchable`() {
        assertTrue(issue().dispatchable)
    }

    @Test
    fun `an issue with a blocked reason is not dispatchable and keeps the reason`() {
        val blocked = issue(blockedReason = "blocked_by a-0 (todo)")

        assertFalse(blocked.dispatchable)
        assertEquals("blocked_by a-0 (todo)", blocked.blockedReason)
    }
}
