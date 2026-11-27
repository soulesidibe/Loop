package loop.core.waiting

import kotlinx.coroutines.test.runTest
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RunId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.api.WaitingIssues
import loop.core.orchestrator.FakeWaitingIssues
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** bl-79: the waits of a project, their typed records and their transitions, against an in-memory store. */
class WaitsTest {
    private val project = ProjectId("p1")
    private val since = Instant.parse("2026-11-15T09:00:00Z")
    private val later = Instant.parse("2026-11-15T10:00:00Z")

    private fun record(
        reason: WaitReason,
        data: Map<String, String>,
        issueId: String = "id-1",
    ) = WaitingIssue(project, issueId, "t-${issueId.removePrefix("id-")}", reason, AttemptId("a1"), RunId("r1"), since, data)

    /** An approval record as today's Loop writes it, with literal keys. */
    private val approvalData =
        mapOf(
            "to" to "review",
            "fingerprint" to "git:abc:e:e",
            "checks" to "- check 0, ./gradlew test: passed\n\n- holdout 1: passed",
            "judgment_calls" to "2",
            "approved_by" to "soule",
            "approved_at" to since.toString(),
            "pull_request" to "https://github.com/acme/widgets/pull/7",
        )

    /** A merge record in phase `merge` as today's Loop writes it, with literal keys. */
    private val mergeData =
        approvalData +
            mapOf(
                "pull_request_number" to "7",
                "phase" to "merge",
                "base_sha" to "base1",
                "verified_fingerprint" to "git:abc:e:e",
                "merged_head" to "head2",
                "round" to "2",
                "merge_retry" to "true",
            )

    @Test
    fun `a verify record written before bl-79 restores to a typed verify wait`() {
        val wait = assertIs<Wait.Verify>(Wait.of(record(WaitReason.Verify, mapOf("to" to "review"))))
        assertEquals("review", wait.to)
        assertEquals("t-1", wait.identifier)
        assertEquals(AttemptId("a1"), wait.attemptId)
    }

    @Test
    fun `an approval record written before bl-79 restores to a typed approval wait`() {
        val wait = assertIs<Wait.Approval>(Wait.of(record(WaitReason.Approval, approvalData)))
        assertEquals("review", wait.to)
        assertEquals("git:abc:e:e", wait.fingerprint)
        assertEquals(listOf("- check 0, ./gradlew test: passed", "- holdout 1: passed"), wait.checks)
        assertEquals(2, wait.judgmentCalls)
        assertEquals("https://github.com/acme/widgets/pull/7", wait.pullRequest)
        assertEquals("soule", wait.approvedBy)
        assertTrue(wait.approved)
    }

    @Test
    fun `a merge record written before bl-79 restores to the same typed phase`() {
        val merging = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData)))
        assertEquals(MergePhase.Merge, merging.phase)
        assertEquals(7L, merging.pullRequestNumber)
        assertEquals("7", merging.numberText)
        assertEquals("base1", merging.baseSha)
        assertEquals("head2", merging.mergedHead)
        assertEquals("git:abc:e:e", merging.verifiedFingerprint)
        assertEquals(2, merging.round)
        assertTrue(merging.retried)
        assertEquals("soule", merging.approvedBy)

        val checking = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("phase" to "check"))))
        assertEquals(MergePhase.Check, checking.phase)
    }

    @Test
    fun `a merge record missing its merge keys reads with the defaults Loop always used`() {
        val wait = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, approvalData)))
        assertEquals(MergePhase.Check, wait.phase)
        assertEquals(1, wait.round)
        assertNull(wait.mergedHead)
        assertNull(wait.pullRequestNumber)
        assertEquals("", wait.numberText)
        assertFalse(wait.retried)
        assertNull(assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mapOf("merged_head" to "")))).mergedHead)
    }

    @Test
    fun `every record writes back exactly what it was read with, unknown keys and reasons included`() {
        val records =
            listOf(
                record(WaitReason.Verify, mapOf("to" to "review", "later_key" to "x")),
                record(WaitReason.Approval, approvalData + ("later_key" to "x")),
                record(WaitReason.Merge, mergeData + ("later_key" to "x")),
                record(WaitReason("later_reason"), mapOf("anything" to "kept")),
            )
        for (stored in records) assertEquals(stored, Wait.of(stored).toRecord())
        assertIs<Wait.Other>(Wait.of(records.last()))
    }

    @Test
    fun `an approved delivery becomes a merge wait in phase check, round 1, its approval kept`() {
        val approval = assertIs<Wait.Approval>(Wait.of(record(WaitReason.Approval, approvalData)))
        val merge = approval.merging(7, "https://github.com/acme/widgets/pull/7", later)
        assertEquals(WaitReason.Merge, merge.reason)
        assertEquals(later, merge.since)
        assertEquals(
            approvalData +
                mapOf(
                    "pull_request" to "https://github.com/acme/widgets/pull/7",
                    "pull_request_number" to "7",
                    "phase" to "check",
                    "base_sha" to "",
                    "verified_fingerprint" to "git:abc:e:e",
                    "merged_head" to "",
                    "round" to "1",
                ),
            merge.toRecord().data,
        )
    }

    @Test
    fun `a clean check moves the merge wait to phase merge at the merged head`() {
        val merge = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("phase" to "check"))))
        val next = merge.intending("base9").checked("base9", "head9")
        assertEquals(MergePhase.Merge, next.phase)
        assertEquals("base9", next.baseSha)
        assertEquals("head9", next.mergedHead)
        assertEquals(
            "git:head9:${loop.core.verify.WorkspaceFingerprint.EMPTY_HASH}:${loop.core.verify.WorkspaceFingerprint.EMPTY_HASH}",
            next.fingerprint,
        )
        assertEquals(2, next.round)
        assertFalse(next.retried, "a passing check forgets a refused merge")
        assertEquals(mergeData.keys - "merge_retry", next.toRecord().data.keys)
    }

    @Test
    fun `a base that moved sends the merge wait back to phase check for the next round`() {
        val merge = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData)))
        val again = merge.again(3)
        assertEquals(MergePhase.Check, again.phase)
        assertEquals(3, again.round)
        assertEquals(mergeData + mapOf("phase" to "check", "round" to "3"), again.toRecord().data)
        assertTrue(merge.numbered(9).let { it.pullRequestNumber == 9L && it.toRecord().data == mergeData + ("pull_request_number" to "9") })
        val first = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData - "merge_retry")))
        assertEquals(mergeData, first.retrying().toRecord().data)
    }

    @Test
    fun `m3-15, a clean check under ci actions waits in phase ci from the push, and the record restores it`() {
        val merge = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("phase" to "check"))))
        val ci = merge.checked("base9", "head9").waitingForCi(later)
        assertEquals(MergePhase.Ci, ci.phase)
        assertEquals(later, ci.ciStartedAt)
        assertEquals(0, ci.ciWaitedMs)
        assertEquals("ci", ci.toRecord().data["phase"])
        assertEquals(later.toString(), ci.toRecord().data["ci_started_at"])
        assertEquals("0", ci.toRecord().data["ci_waited_ms"])

        val restored = assertIs<Wait.Merge>(Wait.of(ci.waited(42_000).toRecord()))
        assertEquals(MergePhase.Ci, restored.phase)
        assertEquals(later, restored.ciStartedAt)
        assertEquals(42_000, restored.ciWaitedMs)
        assertEquals("head9", restored.mergedHead)

        val passed = restored.ciPassed()
        assertEquals(MergePhase.Merge, passed.phase)
        assertEquals(later, passed.ciStartedAt, "the merge phase knows the head was pushed for the CI wait")
    }

    @Test
    fun `m3-15, the ci keys are reset per round, by a new check and by a refusal`() {
        val ci = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData))).waitingForCi(later).waited(9_000)
        val again = ci.again(3)
        assertEquals(MergePhase.Check, again.phase)
        assertNull(again.ciStartedAt)
        assertEquals(0, again.ciWaitedMs)
        assertFalse(again.toRecord().data.containsKey("ci_started_at"))
        assertFalse(again.toRecord().data.containsKey("ci_waited_ms"))
        assertNull(ci.checked("b", "h").ciStartedAt)
        val refused = ci.refused(later).toRecord().data
        assertFalse(refused.containsKey("ci_started_at") || refused.containsKey("ci_waited_ms") || refused.containsKey("phase"))
        assertNull(assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("ci_started_at" to "garbage")))).ciStartedAt)
    }

    @Test
    fun `bl-174, the candidate head is kept by a passing check and dropped by a new round, a refusal and a new approval`() {
        val check = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("phase" to "check"))))
        val candidate = check.candidate("head9")
        assertEquals("head9", candidate.candidateHead)
        assertFalse(candidate.pushedEarly("head9"), "stored before the push")
        val pushed = candidate.candidatePushed()
        assertTrue(pushed.pushedEarly("HEAD9"))
        assertFalse(pushed.pushedEarly("head8"))
        assertEquals(mapOf("candidate_head" to "head9", "candidate_pushed" to "true"), pushed.toRecord().data - mergeData.keys)
        assertFalse(pushed.candidate("head10").pushedEarly("head10"), "a new candidate is not pushed yet")

        val checked = pushed.checked("base9", "head9")
        assertTrue(checked.pushedEarly("head9"), "the push that starts the CI wait knows it was pushed early")
        assertTrue(Wait.of(checked.waitingForCi(later).toRecord()).let { it is Wait.Merge && it.pushedEarly("head9") })

        val again = checked.again(3).toRecord().data
        assertFalse(again.containsKey("candidate_head") || again.containsKey("candidate_pushed"))
        val refused = checked.refused(later)
        assertFalse(refused.toRecord().data.containsKey("candidate_head") || refused.toRecord().data.containsKey("candidate_pushed"))
        val approved =
            assertIs<Wait.Approval>(
                Wait.of(
                    record(
                        WaitReason.Approval,
                        approvalData + pushed.toRecord().data.filterKeys { it.startsWith("candidate_") },
                    ),
                ),
            )
        assertNull(approved.merging(7, "https://github.com/acme/widgets/pull/7", later).candidateHead)
    }

    @Test
    fun `a refused merge falls back to an unapproved approval wait at the fingerprint it holds`() {
        val merge = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData + ("fingerprint" to "git:head2:e:e"))))
        val refused = merge.refused(later)
        assertEquals(WaitReason.Approval, refused.reason)
        assertEquals(later, refused.since)
        assertFalse(refused.approved)
        assertEquals(
            approvalData - "approved_by" - "approved_at" + ("fingerprint" to "git:head2:e:e"),
            refused.toRecord().data,
        )
    }

    @Test
    fun `a workflow that no longer merges gives back the approved approval wait`() {
        val merge = assertIs<Wait.Merge>(Wait.of(record(WaitReason.Merge, mergeData)))
        val unmerged = merge.unmerged()
        assertEquals(WaitReason.Approval, unmerged.reason)
        assertEquals(since, unmerged.since)
        assertTrue(unmerged.approved)
        assertEquals(approvalData, unmerged.toRecord().data)
    }

    @Test
    fun `an approval wait keeps a commented pull request and loses its approval`() {
        val approval = assertIs<Wait.Approval>(Wait.of(record(WaitReason.Approval, approvalData - "pull_request")))
        assertEquals(approvalData, approval.commented("https://github.com/acme/widgets/pull/7").toRecord().data)
        assertEquals(approvalData - "approved_by" - "approved_at" - "pull_request", approval.unapproved().toRecord().data)
        assertFalse(approval.unapproved().approved)
    }

    @Test
    fun `new waits are written with today's keys`() {
        val verify = Wait.verify(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review")
        assertEquals(record(WaitReason.Verify, mapOf("to" to "review")), verify.toRecord())
        val human = Wait.approval(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review", "git:abc:e:e")
        assertEquals(record(WaitReason.Approval, mapOf("to" to "review", "fingerprint" to "git:abc:e:e")), human.toRecord())
        val auto =
            Wait.approval(
                project,
                "id-1",
                "t-1",
                AttemptId("a1"),
                RunId("r1"),
                since,
                "review",
                "git:abc:e:e",
                checks = "- check 0, ./gradlew test: passed\n\n- holdout 1: passed",
                judgmentCalls = 2,
                approvedBy = "soule",
                approvedAt = since,
                pullRequest = "https://github.com/acme/widgets/pull/7",
            )
        assertEquals(record(WaitReason.Approval, approvalData), auto.toRecord())
    }

    @Test
    fun `a saved wait keeps its issue busy and is in the store, an ended one is gone from both`() =
        runTest {
            val store = FakeWaitingIssues()
            val waits = Waits(store, project)
            val verify = Wait.verify(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review")

            assertTrue(waits.save(verify).isSuccess)
            assertTrue(waits.isBusy("id-1"))
            assertEquals(setOf("id-1"), waits.busy)
            assertFalse(waits.awaitsApproval("id-1"))
            assertEquals(listOf(verify.toRecord()), store.waitingIssues(project).getOrThrow())

            assertTrue(waits.end(verify).isSuccess)
            assertFalse(waits.isBusy("id-1"))
            assertEquals(emptyList(), store.waitingIssues(project).getOrThrow())
        }

    @Test
    fun `the wait in memory decides when the store cannot be written`() =
        runTest {
            val store = FakeWaitingIssues().also { it.writeFailure = IOException("disk full") }
            val waits = Waits(store, project)
            val approval = Wait.approval(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review", "git:abc:e:e")

            assertTrue(waits.save(approval).isFailure)
            assertTrue(waits.awaitsApproval("id-1"))
            assertTrue(waits.current(WaitReason.Approval).isEmpty(), "this tick's read never shows an unwritten wait")

            assertTrue(waits.end(approval).isFailure)
            assertFalse(waits.isBusy("id-1"))
        }

    @Test
    fun `a wait moving from approval to merge and back changes what its issue waits for`() =
        runTest {
            val store = FakeWaitingIssues()
            val waits = Waits(store, project)
            val approval = assertIs<Wait.Approval>(Wait.of(record(WaitReason.Approval, approvalData)))
            waits.save(approval)
            assertTrue(waits.awaitsApproval("id-1"))

            val merge = approval.merging(7, "https://github.com/acme/widgets/pull/7", later)
            waits.save(merge)
            assertFalse(waits.awaitsApproval("id-1"))
            assertTrue(waits.isBusy("id-1"))
            assertEquals(listOf<Wait>(merge), waits.current(WaitReason.Merge))

            waits.save(merge.refused(later))
            assertTrue(waits.awaitsApproval("id-1"))
            assertEquals(listOf(merge.refused(later).toRecord()), store.waitingIssues(project).getOrThrow())
        }

    @Test
    fun `one read per tick shows the stored waits, holds the approval and merge ones, and follows later writes`() =
        runTest {
            val verify = record(WaitReason.Verify, mapOf("to" to "review"), issueId = "id-1")
            val approval = record(WaitReason.Approval, approvalData, issueId = "id-2")
            val merge = record(WaitReason.Merge, mergeData, issueId = "id-3")
            val store = FakeWaitingIssues(verify, approval, merge)
            val waits = Waits(store, project)

            val read = waits.read().getOrThrow()
            assertEquals(listOf(verify, approval, merge), read.map { it.toRecord() })
            assertEquals(setOf("id-2", "id-3"), waits.busy, "a verify wait is resumed by its own path, not held by a read")
            assertTrue(waits.awaitsApproval("id-2"))

            val merging = assertIs<Wait.Approval>(Wait.of(approval)).merging(8, "https://github.com/acme/widgets/pull/8", later)
            waits.save(merging)
            waits.end(Wait.of(verify))
            assertEquals(listOf("id-2", "id-3"), waits.current(WaitReason.Merge).map { it.issueId }, "in the order a read gives")
            assertEquals(emptyList(), waits.current(WaitReason.Verify))

            store.readFailure = IOException("disk gone")
            assertTrue(waits.read().isFailure)
            assertEquals(emptyList(), waits.current(), "a failed read shows nothing for this tick")
            assertTrue(waits.isBusy("id-2"), "and changes nothing in memory")
        }

    @Test
    fun `a store that throws is a failed read or write, never a crash`() =
        runTest {
            val throwing =
                object : WaitingIssues by FakeWaitingIssues() {
                    override suspend fun waitingIssues(projectId: ProjectId): Result<List<WaitingIssue>> = throw IOException("boom")

                    override suspend fun startWaiting(issue: WaitingIssue): Result<Unit> = throw IOException("boom")
                }
            val waits = Waits(throwing, project)
            assertEquals("boom", waits.read().exceptionOrNull()?.message)
            assertEquals("boom", waits.save(Wait.verify(project, "id-1", "t-1", null, null, since, "review")).exceptionOrNull()?.message)
            assertTrue(waits.isBusy("id-1"))
        }

    @Test
    fun `hold and forget change memory only`() =
        runTest {
            val store = FakeWaitingIssues()
            val waits = Waits(store, project)
            val verify = Wait.verify(project, "id-1", "t-1", null, null, since, "review")
            waits.hold(verify)
            assertTrue(waits.isBusy("id-1"))
            assertEquals(emptyList(), store.waitingIssues(project).getOrThrow())
            waits.forget("id-1")
            assertFalse(waits.isBusy("id-1"))
        }

    @Test
    fun `merge checks are counted while they run`() {
        val waits = Waits(FakeWaitingIssues(), project)
        assertEquals(0, waits.checks)
        waits.checkStarted("id-1")
        assertTrue(waits.isChecking("id-1"))
        assertEquals(1, waits.checks)
        waits.checkEnded("id-1")
        assertFalse(waits.isChecking("id-1"))
        assertEquals(0, waits.checks)
    }

    @Test
    fun `a running merge phase is checking but counts as no merge check, it holds no agent slot (bl-173)`() {
        val waits = Waits(FakeWaitingIssues(), project)
        waits.checkStarted("id-1", holdsSlot = false)
        assertTrue(waits.isChecking("id-1"))
        assertEquals(0, waits.checks)
        waits.checkStarted("id-2")
        assertEquals(1, waits.checks)
        waits.checkEnded("id-1")
        assertFalse(waits.isChecking("id-1"))
        assertEquals(1, waits.checks)
    }

    @Test
    fun `an approval goes through the store's own approve, in one call`() =
        runTest {
            val calls = mutableListOf<String>()
            val fake = FakeWaitingIssues(record(WaitReason.Approval, approvalData - "approved_by" - "approved_at"))
            val store =
                object : WaitingIssues by fake {
                    override suspend fun startWaiting(issue: WaitingIssue): Result<Unit> {
                        calls += "startWaiting"
                        return fake.startWaiting(issue)
                    }

                    override suspend fun approve(
                        projectId: ProjectId,
                        issueId: String,
                        by: String,
                        at: Instant,
                    ): Result<WaitingIssue?> {
                        calls += "approve"
                        val found = fake.records.getValue(projectId.value to issueId)
                        val approved =
                            found.copy(
                                data =
                                    found.data + mapOf(WaitingIssue.APPROVED_BY to by, WaitingIssue.APPROVED_AT to at.toString()),
                            )
                        fake.records[projectId.value to issueId] = approved
                        return Result.success(approved)
                    }
                }
            val waits = Waits(store, project)

            val approved = assertIs<Wait.Approval>(waits.approve("id-1", "soule", since).getOrThrow())
            assertTrue(approved.approved)
            assertEquals("soule", approved.approvedBy)
            assertEquals(listOf("approve"), calls)
            assertNull(Waits(FakeWaitingIssues(), project).approve("id-9", "soule", since).getOrThrow())
        }

    @Test
    fun `a verify and an approval wait hold the kind they were dispatched under, through the merge and back (m4-18)`() {
        val verify = Wait.verify(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review", kind = "content")
        assertEquals("content", verify.kind)
        assertEquals(mapOf("to" to "review", "kind" to "content"), verify.toRecord().data)

        val approval =
            Wait.approval(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, "review", "git:abc:e:e", kind = "code")
        assertEquals("code", approval.kind)
        val merge = approval.merging(7, "https://github.com/acme/widgets/pull/7", later)
        assertEquals("code", merge.kind)
        assertEquals("code", merge.refused(later).kind)
        assertEquals("code", merge.unmerged().kind)
    }

    @Test
    fun `a wait stored before m4-18 holds no kind`() {
        assertNull(Wait.of(record(WaitReason.Verify, mapOf("to" to "review"))).kind)
        assertNull(Wait.of(record(WaitReason.Approval, approvalData)).kind)
        assertNull(Wait.verify(project, "id-1", "t-1", null, null, since, "review").kind)
    }

    @Test
    fun `m4-31 an ask wait holds the state it was asked in and its question kind, and its record restores it`() {
        val ask =
            Wait.ask(
                project,
                "id-1",
                "t-1",
                AttemptId("a1"),
                RunId("r1"),
                since,
                state = "todo",
                questionKind = "product",
                kind = "code",
            )

        assertEquals(WaitReason.Ask, ask.reason)
        assertEquals("ask", WaitReason.Ask.wire)
        assertEquals(mapOf("state" to "todo", "question_kind" to "product", "kind" to "code"), ask.toRecord().data)
        val restored = assertIs<Wait.Ask>(Wait.of(ask.toRecord()))
        assertEquals("todo", restored.state)
        assertEquals("product", restored.questionKind)
        assertEquals("code", restored.kind)
    }

    @Test
    fun `m4-31 an ask wait ends when its issue is gone, left the active states or a human moved it`() {
        val ask = Wait.ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "human")

        assertFalse(ask.humanMoved(state = "todo", active = true))
        assertFalse(ask.humanMoved(state = "Todo", active = true), "the same state, written another way")
        assertTrue(ask.humanMoved(state = "in_progress", active = true))
        assertTrue(ask.humanMoved(state = "review", active = false))
        assertTrue(ask.humanMoved(state = null, active = false), "gone from the tracker")
    }

    @Test
    fun `m4-31 one read holds the stored ask waits, so a restart keeps their issues waiting`() =
        runTest {
            val store = FakeWaitingIssues()
            val ask = Wait.ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "design")
            Waits(store, project).save(ask)

            val restarted = Waits(store, project)
            restarted.read()

            assertTrue(restarted.isBusy("id-1"))
            assertEquals(listOf<Wait>(ask), restarted.current(WaitReason.Ask))
        }

    @Test
    fun `m4-52 an answer goes through the store's own answer, and the answered wait holds the answer and who gave it`() =
        runTest {
            val calls = mutableListOf<String>()
            val asked =
                Wait
                    .ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "product")
                    .asking("Question for a human (product): Archived items?")
            val fake = FakeWaitingIssues(asked.toRecord())
            val store =
                object : WaitingIssues by fake {
                    override suspend fun answer(
                        projectId: ProjectId,
                        issueId: String,
                        by: String,
                        answer: String,
                        at: Instant,
                    ): Result<WaitingIssue?> {
                        calls += "answer"
                        return fake.answer(projectId, issueId, by, answer, at)
                    }
                }
            val waits = Waits(store, project)

            val answered = assertIs<Wait.Ask>(waits.answer("id-1", "soule", "Leave them out.", later).getOrThrow())

            assertTrue(answered.answered)
            assertEquals("Leave them out.", answered.answer)
            assertEquals("soule", answered.answeredBy)
            assertEquals("Question for a human (product): Archived items?", answered.question)
            assertEquals(listOf("answer"), calls)
            assertNull(Waits(FakeWaitingIssues(), project).answer("id-9", "soule", "Yes.", later).getOrThrow())
        }

    @Test
    fun `m4-52 an answered ask wait is no longer held, so its issue dispatches again, and it is found as answered`() =
        runTest {
            val store = FakeWaitingIssues()
            val waits = Waits(store, project)
            val ask = Wait.ask(project, "id-1", "t-1", AttemptId("a1"), RunId("r1"), since, state = "todo", questionKind = "human")
            waits.save(ask)
            assertTrue(waits.isBusy("id-1"))
            assertNull(waits.answered("id-1"))

            // `loop answer`, another process, answers it in the store; the next read of this process sees it.
            store.answer(project, "id-1", "soule", "Yes.", later).getOrThrow()
            waits.read()

            assertFalse(waits.isBusy("id-1"))
            assertEquals("Yes.", waits.answered("id-1")?.answer)
            // A restart, whose resume holds every wait that is not a verify, does not hold it either.
            val restarted = Waits(store, project)
            restarted.read()
            restarted.current().forEach(restarted::hold)
            assertFalse(restarted.isBusy("id-1"))
        }

    @Test
    fun `m4-32 a send back goes through the store's own sendBack, drops the approval and holds who and why`() =
        runTest {
            val calls = mutableListOf<String>()
            val fake = FakeWaitingIssues(record(WaitReason.Approval, approvalData))
            val store =
                object : WaitingIssues by fake {
                    override suspend fun sendBack(
                        projectId: ProjectId,
                        issueId: String,
                        by: String,
                        reason: String,
                        at: Instant,
                    ): Result<WaitingIssue?> {
                        calls += "sendBack"
                        return fake.sendBack(projectId, issueId, by, reason, at)
                    }
                }
            val waits = Waits(store, project)

            val sent = assertIs<Wait.Approval>(waits.sendBack("id-1", "soule", "Use Save.", later).getOrThrow())

            assertTrue(sent.sentBack)
            assertFalse(sent.approved, "the send back drops the approval")
            assertEquals("soule", sent.sentBackBy)
            assertEquals("Use Save.", sent.sentBackReason)
            assertEquals("review", sent.to)
            assertEquals(listOf("sendBack"), calls)
            // A second send back and an approve leave the sent back wait as it is.
            assertEquals(sent, waits.sendBack("id-1", "other", "Again.", later).getOrThrow())
            assertEquals(sent, waits.approve("id-1", "other", later).getOrThrow())
            assertNull(Waits(FakeWaitingIssues(), project).sendBack("id-9", "soule", "Yes.", later).getOrThrow())
        }

    @Test
    fun `m4-32 a sent back approval wait is no longer held, so its issue dispatches again, and it is found as sent back`() =
        runTest {
            val store = FakeWaitingIssues()
            val waits = Waits(store, project)
            val approval = Wait.of(record(WaitReason.Approval, approvalData - "approved_by" - "approved_at"))
            waits.save(approval)
            assertTrue(waits.awaitsApproval("id-1"))
            assertNull(waits.sentBack("id-1"))

            // `loop send-back`, another process, marks it in the store; the next read of this process sees it.
            store.sendBack(project, "id-1", "soule", "Use Save.", later).getOrThrow()
            waits.read()

            assertFalse(waits.isBusy("id-1"))
            assertEquals("Use Save.", waits.sentBack("id-1")?.sentBackReason)
            // A restart, whose resume holds every wait that is not a verify, does not hold it either.
            val restarted = Waits(store, project)
            restarted.read()
            restarted.current().forEach(restarted::hold)
            assertFalse(restarted.isBusy("id-1"))
        }

    /** `id-1` approved in the store and read by this process, as a tick sees it before a delivery (bl-250). */
    private suspend fun approvedRead(): Triple<FakeWaitingIssues, Waits, Wait.Approval> {
        val store = FakeWaitingIssues(record(WaitReason.Approval, approvalData))
        val waits = Waits(store, project)
        val read = waits.read().getOrThrow().single() as Wait.Approval
        return Triple(store, waits, read)
    }

    @Test
    fun `bl-250 an unchanged approval wait is replaced or ended through the store's own conditional write`() =
        runTest {
            val (store, waits, read) = approvedRead()
            val commented = read.commented("https://github.com/acme/widgets/pull/8")

            assertTrue(waits.replaceApproval(read, commented).getOrThrow())
            assertEquals(commented.toRecord(), store.records.values.single())
            assertEquals(listOf<Wait>(commented), waits.current())
            assertTrue(waits.awaitsApproval("id-1"))

            assertTrue(waits.replaceApproval(read, null).getOrThrow())
            assertEquals(emptyMap(), store.records)
            assertEquals(emptyList(), waits.current())
            assertFalse(waits.isBusy("id-1"))
        }

    @Test
    fun `bl-250 a send back committed since the read wins, the stored wait is kept and adopted in memory`() =
        runTest {
            val (store, waits, read) = approvedRead()
            val sent = store.sendBack(project, "id-1", "soule", "Use Save.", later).getOrThrow()

            assertFalse(waits.replaceApproval(read, read.unapproved()).getOrThrow())
            assertEquals(sent, store.records.values.single())
            assertEquals("Use Save.", waits.sentBack("id-1")?.sentBackReason)
            assertFalse(waits.isBusy("id-1"), "a sent back wait lets its issue dispatch")

            assertFalse(waits.replaceApproval(read, null).getOrThrow())
            assertEquals(sent, store.records.values.single())
        }

    @Test
    fun `bl-250 a wait gone since the read writes nothing and lets its issue go`() =
        runTest {
            val (store, waits, read) = approvedRead()
            store.endWaiting(project, "id-1").getOrThrow()

            assertFalse(waits.replaceApproval(read, read.commented("https://github.com/acme/widgets/pull/8")).getOrThrow())
            assertEquals(emptyMap(), store.records)
            assertFalse(waits.isBusy("id-1"))
            assertEquals(emptyList(), waits.current())
        }

    @Test
    fun `bl-250 a failed conditional write changes nothing in the store, memory follows the write as save and end do`() =
        runTest {
            val (store, waits, read) = approvedRead()
            store.writeFailure = IllegalStateException("disk full")

            assertTrue(waits.replaceApproval(read, read.unapproved()).isFailure)
            assertTrue(waits.awaitsApproval("id-1"))
            assertEquals(listOf<Wait>(read), waits.current())
            assertTrue(waits.replaceApproval(read, null).isFailure)
            assertFalse(waits.isBusy("id-1"))
        }

    @Test
    fun `bl-250 the re-read before a delivery step is true while the wait is still approved, false once sent back`() =
        runTest {
            val (store, waits, read) = approvedRead()
            assertTrue(waits.stillApproved(read).getOrThrow())
            // Loop's own write of the commented URL is not a decision.
            store.startWaiting(read.commented("https://github.com/acme/widgets/pull/8").toRecord()).getOrThrow()
            assertTrue(waits.stillApproved(read).getOrThrow())

            store.sendBack(project, "id-1", "soule", "Use Save.", later).getOrThrow()
            assertFalse(waits.stillApproved(read).getOrThrow())
            assertEquals("Use Save.", waits.sentBack("id-1")?.sentBackReason)
            assertFalse(waits.isBusy("id-1"))

            store.readFailure = IllegalStateException("locked")
            assertTrue(waits.stillApproved(read).isFailure)
        }
}
