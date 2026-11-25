package loop.core.replay

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.RunId
import loop.core.events.BaseFetched
import loop.core.events.CheckFinished
import loop.core.events.Delivery
import loop.core.events.EventDefinition
import loop.core.events.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** m4-41: the verdict `loop replay --verify` replays, read from the attempt's recorded events. */
class RecordedVerdictTest {
    private val attempt = AttemptId("a-1")

    private fun event(
        definition: EventDefinition,
        data: Map<String, String>,
        run: String = "r-1",
    ): RecordedEvent =
        definition.event(
            ts = Instant.parse("2026-11-25T10:00:00Z"),
            projectId = ProjectId("p"),
            data = data,
            runId = RunId(run),
            issueId = "t-1",
            issueIdentifier = "t-1",
            attemptId = attempt,
        )

    private fun verdict(
        verdict: String,
        fingerprint: String,
        mergeCheck: Boolean = false,
        run: String = "r-1",
    ) = event(
        Verdict,
        buildMap {
            put(Verdict.VERDICT, verdict)
            put(Verdict.FINGERPRINT, fingerprint)
            if (mergeCheck) put(Verdict.MERGE_CHECK, "true")
        },
        run,
    )

    @Test
    fun `the last verdict of the attempt is replayed, with its fingerprint and run`() {
        val events =
            listOf(
                event(CheckFinished, mapOf("check" to "0", "exit_code" to "1")),
                verdict("refuted", "git:aaa:e3b0c44298fc1c14:e3b0c44298fc1c14"),
                event(CheckFinished, mapOf("check" to "0", "exit_code" to "0")),
                verdict("verified", "git:bbb:e3b0c44298fc1c14:e3b0c44298fc1c14", run = "r-2"),
            )

        val recorded = RecordedVerdict.of(events)!!

        assertEquals("verified", recorded.verdict)
        assertEquals("git:bbb:e3b0c44298fc1c14:e3b0c44298fc1c14", recorded.fingerprint)
        assertEquals(RunId("r-2"), recorded.runId)
        assertNull(recorded.diffBase)
    }

    @Test
    fun `a merge check verdict is not replayed, the verification before it is`() {
        val events =
            listOf(
                verdict("verified", "git:bbb:x:y"),
                verdict("verified", "git:ccc:x:y", mergeCheck = true),
            )

        assertEquals("git:bbb:x:y", RecordedVerdict.of(events)!!.fingerprint)
    }

    @Test
    fun `the base fetched for the replayed verification is its diff base, never one fetched for an earlier one`() {
        val events =
            listOf(
                event(BaseFetched, mapOf(Delivery.SHA to "1111", Delivery.BASE to "main")),
                verdict("refuted", "git:aaa:x:y"),
                event(BaseFetched, mapOf(Delivery.SHA to "2222", Delivery.BASE to "main")),
                verdict("verified", "git:bbb:x:y"),
            )
        assertEquals("2222", RecordedVerdict.of(events)!!.diffBase)

        val noFetch = listOf(event(BaseFetched, mapOf(Delivery.SHA to "1111")), verdict("refuted", "git:a:x:y"), verdict("verified", "git:b:x:y"))
        assertNull(RecordedVerdict.of(noFetch)!!.diffBase)
    }

    @Test
    fun `an attempt with no verdict, or only a merge check one, has nothing to replay`() {
        assertNull(RecordedVerdict.of(listOf(event(CheckFinished, mapOf("check" to "0")))))
        assertNull(RecordedVerdict.of(listOf(verdict("verified", "git:c:x:y", mergeCheck = true))))
    }
}
