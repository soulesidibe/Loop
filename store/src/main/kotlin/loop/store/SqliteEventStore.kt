package loop.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.AttemptId
import loop.api.AttemptLedger
import loop.api.Claim
import loop.api.Claims
import loop.api.DirtyResource
import loop.api.EventSink
import loop.api.Freeze
import loop.api.FrozenAcceptanceCommands
import loop.api.FrozenCommands
import loop.api.HumanTouch
import loop.api.LedgerAttempt
import loop.api.LedgerRow
import loop.api.LedgerSetup
import loop.api.LedgerTokens
import loop.api.ParkReason
import loop.api.ParkedIssue
import loop.api.ParkedIssues
import loop.api.ProjectId
import loop.api.QueuedRetry
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceLockStore
import loop.api.ResourceLockUpdate
import loop.api.ResourceRequest
import loop.api.RetryState
import loop.api.RetryStates
import loop.api.RunId
import loop.api.SessionId
import loop.api.TurnId
import loop.api.WaitReason
import loop.api.WaitingIssue
import loop.api.WaitingIssues
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.format.DateTimeParseException
import kotlin.time.Instant

/**
 * The machine level event store, one SQLite file at `<loopHome>/store.db` shared
 * by every project (ADR 0001). It also holds the Project state that survives a restart: parked issues (m1-07),
 * claims and retry states (m2-02), waiting issues (m2-04), frozen acceptance command lists (m2-09) and the ledger's
 * attempt rows (m2-19), the machine slots (m3-16) and the resource locks with their queue (m4-50) and dirty marks (m4-26). One connection per
 * instance, WAL mode, autocommit single statement inserts, so several Loop processes can write to the same file. A
 * write of several statements, the schema migration included, runs in a transaction that takes the write lock before
 * it reads (bl-41), so a command beside a running Loop waits on `busy_timeout` instead of failing on a stale snapshot.
 *
 * Every text a writer hands in goes through the [Redactor] before the insert (m1-11): event
 * data values, the issue identifier and the phase, a parked issue's identifier, state and
 * labels, a claim's identifier and runtime session id, a retry state's identifier and error, a waiting
 * issue's identifier and data values, a frozen command list's identifier, an attempt row's identifier, model,
 * reason and issue state, a resource lock's identifier and prepared values, a queue row's identifier and a dirty
 * mark's difference.
 * Ids are Loop's own and left alone. Reads return what was stored, never redact again.
 */
class SqliteEventStore private constructor(
    private val connection: Connection,
    private val redactor: Redactor,
    private val ids: IssueIdForms,
) : EventSink,
    ParkedIssues,
    Claims,
    RetryStates,
    WaitingIssues,
    AttemptLedger,
    FrozenAcceptanceCommands,
    ResourceLockStore,
    AutoCloseable {
    override suspend fun record(event: RecordedEvent): Result<Unit> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(INSERT).use { st ->
                        st.setString(1, event.ts.toString())
                        st.setNullable(2, event.runId?.value)
                        st.setString(3, event.projectId.value)
                        st.setNullable(4, event.issueId)
                        st.setNullable(5, event.issueIdentifier?.let(redactor::redact))
                        st.setNullable(6, event.attemptId?.value)
                        st.setNullable(7, event.sessionId?.value)
                        st.setNullable(8, event.turnId?.value)
                        st.setString(9, event.kind)
                        st.setNullable(10, event.phase?.let(redactor::redact))
                        // Redacted before the json encoding, so an escaped quote cannot split a value.
                        st.setNullable(11, encodeData(event.data.mapValues { redactor.redact(it.value) }))
                        st.executeUpdate()
                    }
                }
                Unit
            }
        }

    /** Every event of [issueId] in [projectId], in recording order. */
    suspend fun eventsForIssue(
        projectId: ProjectId,
        issueId: String,
    ): Result<List<StoredEvent>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_BY_ISSUE).use { st ->
                        st.setString(1, projectId.value)
                        st.setIssue(2, issueId)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toStoredEvent(ids)) }
                        }
                    }
                }
            }
        }

    /**
     * Every event of [runId] in [projectId], run level events included, in recording order (m1-09,
     * read by `loop stop`). No index covers `run_id`, so this scans the project's events.
     */
    suspend fun eventsForRun(
        projectId: ProjectId,
        runId: RunId,
    ): Result<List<StoredEvent>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_BY_RUN).use { st ->
                        st.setString(1, projectId.value)
                        st.setString(2, runId.value)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toStoredEvent(ids)) }
                        }
                    }
                }
            }
        }

    /**
     * Every event of [projectId] whose kind is in [kinds], in recording order (m1-14, read by `loop status`,
     * which needs a few kinds over every run and never the runner output). No kinds means no events.
     */
    suspend fun eventsOfKinds(
        projectId: ProjectId,
        kinds: Set<String>,
    ): Result<List<StoredEvent>> {
        if (kinds.isEmpty()) return Result.success(emptyList())
        val ordered = kinds.toList()
        return withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    val marks = ordered.joinToString(", ") { "?" }
                    connection.prepareStatement("$SELECT_BY_PROJECT AND kind IN ($marks) ORDER BY seq").use { st ->
                        st.setString(1, projectId.value)
                        ordered.forEachIndexed { index, kind -> st.setString(index + 2, kind) }
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toStoredEvent(ids)) }
                        }
                    }
                }
            }
        }
    }

    /** Every event of [attemptId], whatever its project, in recording order. */
    suspend fun eventsForAttempt(attemptId: AttemptId): Result<List<StoredEvent>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_BY_ATTEMPT).use { st ->
                        st.setString(1, attemptId.value)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toStoredEvent(ids)) }
                        }
                    }
                }
            }
        }

    /**
     * The distinct attempt ids of [projectId] that start with [prefix], sorted (m1-13, read by `loop show`
     * to accept the short ids `loop trace` prints). The prefix is compared as plain text, no wildcards.
     */
    suspend fun attemptIdsStartingWith(
        projectId: ProjectId,
        prefix: String,
    ): Result<List<AttemptId>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_ATTEMPT_IDS_BY_PREFIX).use { st ->
                        st.setString(1, projectId.value)
                        st.setInt(2, prefix.length)
                        st.setString(3, prefix)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(AttemptId(rs.getString(1))) }
                        }
                    }
                }
            }
        }

    override suspend fun park(issue: ParkedIssue): Result<Unit> =
        upsert(UPSERT_PARKED, PARKED_TABLE, issue.projectId, issue.issueId) { st ->
            st.setString(3, redactor.redact(issue.identifier))
            st.setString(4, issue.reason.wire)
            st.setString(5, redactor.redact(issue.state))
            st.setString(6, encodeLabels(issue.labels.mapTo(mutableSetOf(), redactor::redact)))
            st.setString(7, issue.parkedAt.toString())
            st.setNullable(8, issue.runId?.value)
        }

    override suspend fun list(projectId: ProjectId): Result<List<ParkedIssue>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_PARKED).use { st ->
                        st.setString(1, projectId.value)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toParkedIssue(ids)) }
                        }
                    }
                }
            }
        }

    /**
     * Removes the parked row of [issueId] in [projectId] and returns it, in one write transaction (bl-41), so `loop
     * resume` beside a running Loop waits on `busy_timeout` instead of failing on a stale read snapshot.
     */
    override suspend fun unpark(
        projectId: ProjectId,
        issueId: String,
    ): Result<ParkedIssue?> =
        transaction {
            val found =
                connection.prepareStatement(SELECT_PARKED_ONE).use { st ->
                    st.setString(1, projectId.value)
                    st.setIssue(2, issueId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.toParkedIssue(ids) else null }
                }
            if (found != null) {
                connection.prepareStatement(DELETE_PARKED).use { st ->
                    st.setString(1, projectId.value)
                    st.setIssue(2, issueId)
                    st.executeUpdate()
                }
            }
            found
        }

    override suspend fun claim(claim: Claim): Result<Unit> =
        upsert(UPSERT_CLAIM, CLAIMS_TABLE, claim.projectId, claim.issueId) { st ->
            st.setString(3, redactor.redact(claim.identifier))
            st.setString(4, claim.attemptId.value)
            st.setNullable(5, claim.runId?.value)
            st.setString(6, claim.claimedAt.toString())
            st.setNullable(7, claim.sessionId?.value)
            st.setNullable(8, claim.runtimeSessionId?.let(redactor::redact))
        }

    override suspend fun claims(projectId: ProjectId): Result<List<Claim>> = select(SELECT_CLAIMS, projectId) { it.toClaim(ids) }

    override suspend fun release(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> =
        update(DELETE_CLAIM) { st ->
            st.setString(1, projectId.value)
            st.setIssue(2, issueId)
        }

    override suspend fun saveRetryState(state: RetryState): Result<Unit> =
        upsert(UPSERT_RETRY_STATE, RETRY_TABLE, state.projectId, state.issueId) { st ->
            st.setString(3, redactor.redact(state.identifier))
            val retry = state.retry
            if (retry == null) st.setNull(4, Types.INTEGER) else st.setInt(4, retry.attempt)
            st.setNullable(5, retry?.dueAt?.toString())
            st.setNullable(6, retry?.error?.let(redactor::redact))
            st.setInt(7, state.failures)
            st.setInt(8, state.noProgress)
        }

    override suspend fun deleteRetryState(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> =
        update(DELETE_RETRY_STATE) { st ->
            st.setString(1, projectId.value)
            st.setIssue(2, issueId)
        }

    override suspend fun retryStates(projectId: ProjectId): Result<List<RetryState>> =
        select(SELECT_RETRY_STATES, projectId) { it.toRetryState(ids) }

    override suspend fun startWaiting(issue: WaitingIssue): Result<Unit> =
        upsert(UPSERT_WAITING, WAITING_TABLE, issue.projectId, issue.issueId) { st ->
            st.setString(3, redactor.redact(issue.identifier))
            st.setString(4, issue.reason.wire)
            st.setNullable(5, issue.attemptId?.value)
            st.setNullable(6, issue.runId?.value)
            st.setString(7, issue.since.toString())
            st.setNullable(8, encodeData(issue.data.mapValues { redactor.redact(it.value) }))
        }

    override suspend fun waitingIssues(projectId: ProjectId): Result<List<WaitingIssue>> =
        select(SELECT_WAITING, projectId) { it.toWaitingIssue(ids) }

    override suspend fun endWaiting(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> =
        update(DELETE_WAITING) { st ->
            st.setString(1, projectId.value)
            st.setIssue(2, issueId)
        }

    /**
     * Marks an approval wait approved in one write transaction (m2-24), so `loop approve` and a running Loop that
     * ends the wait at the same time never leave half of each. See [WaitingIssues.approve].
     */
    override suspend fun approve(
        projectId: ProjectId,
        issueId: String,
        by: String,
        at: Instant,
    ): Result<WaitingIssue?> =
        transaction {
            val found =
                connection.prepareStatement(SELECT_WAITING_ONE).use { st ->
                    st.setString(1, projectId.value)
                    st.setIssue(2, issueId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.toWaitingIssue(ids) else null }
                }
            if (found == null || found.reason != WaitReason.Approval || found.approved) {
                found
            } else {
                val data =
                    found.data + mapOf(WaitingIssue.APPROVED_BY to redactor.redact(by), WaitingIssue.APPROVED_AT to at.toString())
                connection.prepareStatement(UPDATE_WAITING_DATA).use { st ->
                    st.setNullable(1, encodeData(data))
                    st.setString(2, projectId.value)
                    st.setIssue(3, issueId)
                    st.executeUpdate()
                }
                found.copy(data = data)
            }
        }

    /**
     * Marks an ask wait answered in one write transaction (m4-52), so `loop answer` and a running Loop that ends the
     * wait at the same time never leave half of each. The answer and who gave it are redacted. See [WaitingIssues.answer].
     */
    override suspend fun answer(
        projectId: ProjectId,
        issueId: String,
        by: String,
        answer: String,
        at: Instant,
    ): Result<WaitingIssue?> =
        transaction {
            val found =
                connection.prepareStatement(SELECT_WAITING_ONE).use { st ->
                    st.setString(1, projectId.value)
                    st.setIssue(2, issueId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.toWaitingIssue(ids) else null }
                }
            if (found == null || found.reason != WaitReason.Ask || found.data[WaitingIssue.ANSWERED_AT] != null) {
                found
            } else {
                val data = found.data + WaitingIssue.answerData(redactor.redact(by), redactor.redact(answer), at)
                connection.prepareStatement(UPDATE_WAITING_DATA).use { st ->
                    st.setNullable(1, encodeData(data))
                    st.setString(2, projectId.value)
                    st.setIssue(3, issueId)
                    st.executeUpdate()
                }
                found.copy(data = data)
            }
        }

    /**
     * Writes the row of [attempt] (m2-19). The same statement counts its number among the rows of its issue and
     * its human touches: the `human_touch` events of the issue after the last one an earlier row counted, so a
     * touch counts once, in the next row of its issue to be written. A row already there for the attempt id
     * is kept as it is.
     */
    override suspend fun recordAttempt(attempt: LedgerAttempt): Result<Unit> =
        update(INSERT_ATTEMPT) { st ->
            val setup = attempt.setup
            val tokens = attempt.tokens
            var index = 0

            fun next() = ++index
            st.setString(next(), attempt.attemptId.value)
            st.setString(next(), attempt.projectId.value)
            st.setString(next(), ids.read(attempt.issueId))
            st.setString(next(), redactor.redact(attempt.identifier))
            st.setNullable(next(), attempt.runId?.value)
            st.setString(next(), setup.tracker)
            st.setNullable(next(), setup.kind)
            st.setString(next(), setup.runner)
            st.setNullable(next(), setup.model?.let(redactor::redact))
            st.setString(next(), setup.templateVersion)
            st.setNullable(next(), setup.configVersion)
            st.setString(next(), attempt.startedAt.toString())
            st.setString(next(), attempt.finishedAt.toString())
            st.setLong(next(), attempt.wallMs)
            st.setString(next(), attempt.outcome)
            st.setNullable(next(), attempt.reason?.let(redactor::redact))
            st.setInt(next(), attempt.turns)
            st.setNullable(next(), attempt.issueState?.let(redactor::redact))
            listOf(tokens?.input, tokens?.output, tokens?.cacheRead, tokens?.cacheCreation, tokens?.total).forEach {
                st.setNullableLong(next(), it)
            }
            if (attempt.costUsd == null) st.setNull(next(), Types.REAL) else st.setDouble(next(), attempt.costUsd!!)
            st.setNullableLong(next(), attempt.checks?.toLong())
            st.setNullableLong(next(), attempt.guards?.toLong())
            st.setNullable(next(), attempt.verdict)
            // The counted columns: the number, the touches and the last touch counted, each scoped to the issue.
            // m4-12: both forms of the issue id, so a row written before m4-12 counts too.
            repeat(COUNT_SCOPES) {
                st.setString(next(), attempt.projectId.value)
                st.setIssue(next(), attempt.issueId)
                next()
            }
        }

    override suspend fun attemptRows(
        projectId: ProjectId,
        issueId: String,
    ): Result<List<LedgerRow>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(SELECT_ATTEMPTS).use { st ->
                        st.setString(1, projectId.value)
                        st.setIssue(2, issueId)
                        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toLedgerRow(ids)) } }
                    }
                }
            }
        }

    /**
     * The rows of [projectId] that finished after [since] (m2-20). The text comparison in SQL only narrows the read:
     * an instant is stored with a varying count of fraction digits, so it starts a second early, on a whole second,
     * and the exact comparison is made on the parsed instants.
     */
    override suspend fun attemptRowsSince(
        projectId: ProjectId,
        since: Instant,
    ): Result<List<LedgerRow>> =
        withContext(Dispatchers.IO) {
            sql {
                val from = Instant.fromEpochSeconds(since.epochSeconds - 1).toString()
                val rows =
                    synchronized(connection) {
                        connection.prepareStatement(SELECT_ATTEMPTS_SINCE).use { st ->
                            st.setString(1, projectId.value)
                            st.setString(2, from)
                            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toLedgerRow(ids)) } }
                        }
                    }
                rows.filter { it.attempt.finishedAt > since }
            }
        }

    override suspend fun freeze(commands: FrozenCommands): Result<Freeze> =
        transaction {
            val written =
                connection.prepareStatement(INSERT_FROZEN).use { st ->
                    st.setString(1, commands.projectId.value)
                    st.setString(2, storedId(FROZEN_TABLE, commands.projectId, commands.issueId))
                    st.setString(3, redactor.redact(commands.identifier))
                    st.setString(4, commands.digest)
                    st.setInt(5, commands.count)
                    st.setString(6, commands.frozenAt.toString())
                    st.executeUpdate() == 1
                }
            Freeze(checkNotNull(selectFrozen(commands.projectId, commands.issueId)), written)
        }

    override suspend fun markChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
        count: Int,
    ): Result<Unit> =
        update(MARK_FROZEN_CHANGED) { st ->
            st.setString(1, digest)
            st.setInt(2, count)
            st.setString(3, projectId.value)
            st.setIssue(4, issueId)
        }

    override suspend fun clearChanged(
        projectId: ProjectId,
        issueId: String,
    ): Result<Unit> =
        update(CLEAR_FROZEN_CHANGED) { st ->
            st.setString(1, projectId.value)
            st.setIssue(2, issueId)
        }

    override suspend fun acceptChanged(
        projectId: ProjectId,
        issueId: String,
        digest: String,
    ): Result<FrozenCommands?> =
        transaction {
            val accepted =
                connection.prepareStatement(ACCEPT_FROZEN_CHANGED).use { st ->
                    st.setString(1, projectId.value)
                    st.setIssue(2, issueId)
                    st.setString(4, digest)
                    st.executeUpdate() == 1
                }
            if (accepted) selectFrozen(projectId, issueId) else null
        }

    /** The frozen list of [issueId] in [projectId], null when the issue has none (m2-09, read by `loop resume`). */
    suspend fun frozen(
        projectId: ProjectId,
        issueId: String,
    ): Result<FrozenCommands?> =
        withContext(Dispatchers.IO) {
            sql { synchronized(connection) { selectFrozen(projectId, issueId) } }
        }

    /** The frozen list of one issue, read on the caller's connection lock. */
    private fun selectFrozen(
        projectId: ProjectId,
        issueId: String,
    ): FrozenCommands? =
        connection.prepareStatement(SELECT_FROZEN).use { st ->
            st.setString(1, projectId.value)
            st.setIssue(2, issueId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toFrozenCommands(ids) else null }
        }

    /**
     * Claims a machine slot for [slot] (m3-16) in one `BEGIN IMMEDIATE` transaction, so of several processes wanting
     * the last slot at once exactly one gets it: it reads every slot of the machine, deletes the ones [live] rejects
     * (their process is gone, so they never count again), and writes [slot] when fewer than [cap] remain. True when
     * written. [live] runs under the write lock and must not touch the store.
     */
    suspend fun claimMachineSlot(
        slot: MachineSlot,
        cap: Int,
        live: (MachineSlot) -> Boolean,
    ): Result<Boolean> =
        transaction {
            val (held, dead) = selectSlots().partition(live)
            dead.forEach { deleteSlot(it.projectId, it.holder) }
            if (held.size >= cap) return@transaction false
            connection.prepareStatement(INSERT_SLOT).use { st ->
                st.setString(1, slot.projectId.value)
                st.setString(2, slot.holder)
                st.setString(3, slot.runId.value)
                st.setLong(4, slot.pid)
                st.setString(5, slot.claimedAt.toString())
                st.executeUpdate()
            }
            true
        }

    /** Deletes the machine slot of [holder] in [projectId] (m3-16), at the end of its attempt or merge check. */
    suspend fun releaseMachineSlot(
        projectId: ProjectId,
        holder: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            sql { synchronized(connection) { deleteSlot(projectId, holder) } }
        }

    /** Every machine slot row of every project (m3-16), live or not: the caller decides which count. */
    suspend fun machineSlots(): Result<List<MachineSlot>> =
        withContext(Dispatchers.IO) {
            sql { synchronized(connection) { selectSlots() } }
        }

    /**
     * The attempt rows of every project that finished after [since] (m3-16), for the machine day budget. Narrowed in
     * SQL a second early and compared exactly on the parsed instants, like [attemptRowsSince].
     */
    suspend fun machineAttemptRowsSince(since: Instant): Result<List<LedgerRow>> =
        withContext(Dispatchers.IO) {
            sql {
                val from = Instant.fromEpochSeconds(since.epochSeconds - 1).toString()
                val rows =
                    synchronized(connection) {
                        connection.prepareStatement(SELECT_MACHINE_ATTEMPTS_SINCE).use { st ->
                            st.setString(1, from)
                            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toLedgerRow(ids)) } }
                        }
                    }
                rows.filter { it.attempt.finishedAt > since }
            }
        }

    override suspend fun resourceLockRows(): Result<ResourceLockRows> =
        withContext(Dispatchers.IO) {
            sql { synchronized(connection) { selectResourceRows() } }
        }

    /**
     * Reads every lock and queue row of the machine (m4-50) and writes the rows [change] returns in their place, in one
     * `BEGIN IMMEDIATE` transaction, so of several processes changing them at once each sees the rows the one before it
     * wrote. Rows equal to those read are not written again. A queue row's place is its index in the list returned. A
     * change that holds two locks of one kind and resource id, or one attempt twice in one queue, fails whole on the
     * table's key and writes nothing.
     */
    override suspend fun <T> updateResourceLocks(change: (ResourceLockRows) -> ResourceLockUpdate<T>): Result<T> =
        transaction {
            val before = selectResourceRows()
            val update = change(before)
            if (update.rows != before) writeResourceRows(update.rows)
            update.value
        }

    /** Every lock row, then every queue row in its place, read on the caller's connection lock. */
    private fun selectResourceRows(): ResourceLockRows {
        val locks =
            connection.prepareStatement(SELECT_RESOURCE_LOCKS).use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toResourceLock()) } }
            }
        val queue =
            connection.prepareStatement(SELECT_RESOURCE_QUEUE).use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toResourceRequest()) } }
            }
        val dirty =
            connection.prepareStatement(SELECT_RESOURCE_DIRTY).use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDirtyResource()) } }
            }
        return ResourceLockRows(locks, queue, dirty)
    }

    /** Replaces every lock and queue row with [rows], on the caller's connection lock, inside its transaction. */
    private fun writeResourceRows(rows: ResourceLockRows) {
        connection.createStatement().use { st ->
            st.executeUpdate("DELETE FROM resource_locks")
            st.executeUpdate("DELETE FROM resource_queue")
            st.executeUpdate("DELETE FROM resource_dirty")
        }
        connection.prepareStatement(INSERT_RESOURCE_LOCK).use { st ->
            for (lock in rows.locks) {
                st.setString(1, lock.kind)
                st.setString(2, lock.resourceId)
                st.setString(3, lock.projectId.value)
                st.setString(4, lock.issueId)
                st.setString(5, redactor.redact(lock.issueIdentifier))
                st.setString(6, lock.attemptId.value)
                st.setString(7, lock.grantedAt.toString())
                // m4-25: an empty map is a prepare that returned nothing, kept apart from null, a prepare not run yet.
                st.setNullable(8, lock.prepared?.let { prepared -> encodeMap(prepared.mapValues { (_, value) -> redactor.redact(value) }) })
                st.setNullable(9, lock.prepareError?.let(redactor::redact))
                st.setInt(10, if (lock.answered) 1 else 0)
                st.setNullable(11, lock.releaseRequestedAt?.toString())
                st.executeUpdate()
            }
        }
        connection.prepareStatement(INSERT_RESOURCE_REQUEST).use { st ->
            for ((place, request) in rows.queue.withIndex()) {
                st.setString(1, request.kind)
                st.setString(2, request.resourceId)
                st.setString(3, request.projectId.value)
                st.setString(4, request.issueId)
                st.setString(5, redactor.redact(request.issueIdentifier))
                st.setString(6, request.attemptId.value)
                st.setString(7, request.requestedAt.toString())
                st.setLong(8, place.toLong())
                st.setInt(9, if (request.anyOfKind) 1 else 0)
                st.executeUpdate()
            }
        }
        connection.prepareStatement(INSERT_RESOURCE_DIRTY).use { st ->
            for (mark in rows.dirty) {
                st.setString(1, mark.kind)
                st.setString(2, mark.resourceId)
                st.setString(3, mark.projectId.value)
                st.setString(4, mark.check)
                st.setString(5, redactor.redact(mark.difference))
                st.setString(6, mark.markedAt.toString())
                st.executeUpdate()
            }
        }
    }

    /** Every slot row, read on the caller's connection lock. */
    private fun selectSlots(): List<MachineSlot> =
        connection.prepareStatement(SELECT_SLOTS).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MachineSlot(
                                projectId = ProjectId(rs.getString("project_id")),
                                holder = rs.getString("holder"),
                                runId = RunId(rs.getString("run_id")),
                                pid = rs.getLong("pid"),
                                claimedAt = Instant.parse(rs.getString("claimed_at")),
                            ),
                        )
                    }
                }
            }
        }

    /** Deletes one slot row, on the caller's connection lock. */
    private fun deleteSlot(
        projectId: ProjectId,
        holder: String,
    ) {
        connection.prepareStatement(DELETE_SLOT).use { st ->
            st.setString(1, projectId.value)
            st.setString(2, holder)
            st.executeUpdate()
        }
    }

    /** Binds the two forms of [issueId] (m4-12) at [index] and the next index, for an `issue_id IN (?, ?)`. */
    private fun PreparedStatement.setIssue(
        index: Int,
        issueId: String,
    ) {
        val (id, bare) = ids.of(issueId)
        setString(index, id)
        setString(index + 1, bare)
    }

    /**
     * The id a write of [issueId] in [projectId] keys its row of [table] with (m4-12): the bare id a row written before
     * m4-12 already holds, so that row is updated and never rewritten, else the id the core carries. Read on the
     * caller's connection lock, inside its transaction.
     */
    private fun storedId(
        table: String,
        projectId: ProjectId,
        issueId: String,
    ): String {
        val (id, bare) = ids.of(issueId)
        if (bare == id) return id
        val held =
            connection.prepareStatement("SELECT 1 FROM $table WHERE project_id = ? AND issue_id = ?").use { st ->
                st.setString(1, projectId.value)
                st.setString(2, bare)
                st.executeQuery().use { it.next() }
            }
        return if (held) bare else id
    }

    /**
     * The upsert [sql] of one issue's row of [table] in one write transaction, its project and issue id bound first by
     * [storedId] and its other parameters, from the third on, by [bind].
     */
    private suspend fun upsert(
        sql: String,
        table: String,
        projectId: ProjectId,
        issueId: String,
        bind: (PreparedStatement) -> Unit,
    ): Result<Unit> =
        transaction {
            connection.prepareStatement(sql).use { st ->
                st.setString(1, projectId.value)
                st.setString(2, storedId(table, projectId, issueId))
                bind(st)
                st.executeUpdate()
            }
        }.map { }

    /**
     * Runs [block] in one write transaction on the locked connection, committed when it returns and rolled back when
     * it throws (bl-41). See [immediate].
     */
    private suspend fun <T> transaction(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            sql { synchronized(connection) { immediate(connection, block) } }
        }

    /** Runs the one statement [sql], its parameters set by [bind]. */
    private suspend fun update(
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(sql).use { st ->
                        bind(st)
                        st.executeUpdate()
                    }
                }
                Unit
            }
        }

    /** The rows of [sql], whose one parameter is [projectId], each read by [row]. */
    private suspend fun <T> select(
        sql: String,
        projectId: ProjectId,
        row: (ResultSet) -> T,
    ): Result<List<T>> =
        withContext(Dispatchers.IO) {
            sql {
                synchronized(connection) {
                    connection.prepareStatement(sql).use { st ->
                        st.setString(1, projectId.value)
                        st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
                    }
                }
            }
        }

    override fun close() {
        synchronized(connection) { connection.close() }
    }

    companion object {
        const val FILE_NAME: String = "store.db"
        const val SCHEMA_VERSION: Int = 10

        private const val COLUMNS =
            "ts, run_id, project_id, issue_id, issue_identifier, attempt_id, session_id, turn_id, kind, phase, data"
        private const val INSERT = "INSERT INTO events ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val SELECT_BY_ISSUE =
            "SELECT seq, $COLUMNS FROM events WHERE project_id = ? AND issue_id IN (?, ?) ORDER BY seq"
        private const val SELECT_BY_RUN =
            "SELECT seq, $COLUMNS FROM events WHERE project_id = ? AND run_id = ? ORDER BY seq"
        private const val SELECT_BY_PROJECT = "SELECT seq, $COLUMNS FROM events WHERE project_id = ?"
        private const val SELECT_BY_ATTEMPT = "SELECT seq, $COLUMNS FROM events WHERE attempt_id = ? ORDER BY seq"
        private const val SELECT_ATTEMPT_IDS_BY_PREFIX =
            "SELECT DISTINCT attempt_id FROM events WHERE project_id = ? AND attempt_id IS NOT NULL " +
                "AND substr(attempt_id, 1, ?) = ? ORDER BY attempt_id"

        private const val PARKED_TABLE = "parked_issues"
        private const val CLAIMS_TABLE = "claims"
        private const val RETRY_TABLE = "retry_states"
        private const val WAITING_TABLE = "waiting_issues"
        private const val FROZEN_TABLE = "acceptance_commands"

        private const val PARKED_COLUMNS = "project_id, issue_id, identifier, reason, state, labels, parked_at, run_id"
        private const val UPSERT_PARKED =
            "INSERT INTO parked_issues ($PARKED_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, issue_id) DO UPDATE SET identifier = excluded.identifier, " +
                "reason = excluded.reason, state = excluded.state, labels = excluded.labels, " +
                "parked_at = excluded.parked_at, run_id = excluded.run_id"
        private const val SELECT_PARKED =
            "SELECT $PARKED_COLUMNS FROM parked_issues WHERE project_id = ? ORDER BY parked_at, issue_id"
        private const val SELECT_PARKED_ONE =
            "SELECT $PARKED_COLUMNS FROM parked_issues WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val DELETE_PARKED = "DELETE FROM parked_issues WHERE project_id = ? AND issue_id IN (?, ?)"

        private const val CLAIM_COLUMNS =
            "project_id, issue_id, identifier, attempt_id, run_id, claimed_at, session_id, runtime_session_id"
        private const val UPSERT_CLAIM =
            "INSERT INTO claims ($CLAIM_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, issue_id) DO UPDATE SET identifier = excluded.identifier, " +
                "attempt_id = excluded.attempt_id, run_id = excluded.run_id, claimed_at = excluded.claimed_at, " +
                "session_id = excluded.session_id, runtime_session_id = excluded.runtime_session_id"
        private const val SELECT_CLAIMS = "SELECT $CLAIM_COLUMNS FROM claims WHERE project_id = ? ORDER BY claimed_at, issue_id"
        private const val DELETE_CLAIM = "DELETE FROM claims WHERE project_id = ? AND issue_id IN (?, ?)"

        private const val RETRY_COLUMNS = "project_id, issue_id, identifier, attempt, due_at, error, failures, no_progress"
        private const val UPSERT_RETRY_STATE =
            "INSERT INTO retry_states ($RETRY_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, issue_id) DO UPDATE SET identifier = excluded.identifier, " +
                "attempt = excluded.attempt, due_at = excluded.due_at, error = excluded.error, " +
                "failures = excluded.failures, no_progress = excluded.no_progress"
        private const val SELECT_RETRY_STATES = "SELECT $RETRY_COLUMNS FROM retry_states WHERE project_id = ? ORDER BY issue_id"
        private const val DELETE_RETRY_STATE = "DELETE FROM retry_states WHERE project_id = ? AND issue_id IN (?, ?)"

        private const val WAITING_COLUMNS = "project_id, issue_id, identifier, reason, attempt_id, run_id, since, data"
        private const val UPSERT_WAITING =
            "INSERT INTO waiting_issues ($WAITING_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, issue_id) DO UPDATE SET identifier = excluded.identifier, " +
                "reason = excluded.reason, attempt_id = excluded.attempt_id, run_id = excluded.run_id, " +
                "since = excluded.since, data = excluded.data"
        private const val SELECT_WAITING =
            "SELECT $WAITING_COLUMNS FROM waiting_issues WHERE project_id = ? ORDER BY since, issue_id"
        private const val DELETE_WAITING = "DELETE FROM waiting_issues WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val SELECT_WAITING_ONE = "SELECT $WAITING_COLUMNS FROM waiting_issues WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val UPDATE_WAITING_DATA = "UPDATE waiting_issues SET data = ? WHERE project_id = ? AND issue_id IN (?, ?)"

        private const val ATTEMPT_COLUMNS =
            "attempt_id, project_id, issue_id, identifier, run_id, tracker, kind, runner, model, template_version, " +
                "config_version, started_at, finished_at, wall_ms, outcome, reason, turns, issue_state, input_tokens, " +
                "output_tokens, cache_read_input_tokens, cache_creation_input_tokens, tokens, cost_usd, checks, guards, verdict"
        private const val ATTEMPT_VALUES = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"

        /** How many times [INSERT_ATTEMPT] binds the project and issue id pair after the row's own values. */
        private const val COUNT_SCOPES = 4
        private const val ISSUE_TOUCHES =
            "FROM events WHERE project_id = ? AND issue_id IN (?, ?) AND kind = '${HumanTouch.EVENT}'"
        private const val INSERT_ATTEMPT =
            "INSERT INTO attempts ($ATTEMPT_COLUMNS, attempt_number, human_touches, touch_seq) " +
                "SELECT $ATTEMPT_VALUES, " +
                "(SELECT COUNT(*) FROM attempts WHERE project_id = ? AND issue_id IN (?, ?)) + 1, " +
                "(SELECT COUNT(*) $ISSUE_TOUCHES AND seq > " +
                "(SELECT COALESCE(MAX(touch_seq), 0) FROM attempts WHERE project_id = ? AND issue_id IN (?, ?))), " +
                "(SELECT COALESCE(MAX(seq), 0) $ISSUE_TOUCHES) " +
                // The WHERE lets SQLite parse the upsert clause after a SELECT.
                "WHERE true ON CONFLICT (attempt_id) DO NOTHING"
        private const val SELECT_ATTEMPTS =
            "SELECT $ATTEMPT_COLUMNS, attempt_number, human_touches FROM attempts " +
                "WHERE project_id = ? AND issue_id IN (?, ?) ORDER BY seq"
        private const val SELECT_ATTEMPTS_SINCE =
            "SELECT $ATTEMPT_COLUMNS, attempt_number, human_touches FROM attempts " +
                "WHERE project_id = ? AND finished_at >= ? ORDER BY seq"
        private const val SLOT_COLUMNS = "project_id, holder, run_id, pid, claimed_at"
        private const val SELECT_SLOTS = "SELECT $SLOT_COLUMNS FROM machine_slots ORDER BY claimed_at, rowid"
        private const val INSERT_SLOT = "INSERT INTO machine_slots ($SLOT_COLUMNS) VALUES (?, ?, ?, ?, ?)"
        private const val DELETE_SLOT = "DELETE FROM machine_slots WHERE project_id = ? AND holder = ?"
        private const val SELECT_MACHINE_ATTEMPTS_SINCE =
            "SELECT $ATTEMPT_COLUMNS, attempt_number, human_touches FROM attempts WHERE finished_at >= ? ORDER BY seq"
        private const val RESOURCE_LOCK_COLUMNS =
            "kind, resource_id, project_id, issue_id, identifier, attempt_id, granted_at, prepared, " +
                "prepare_error, answered, release_requested_at"
        private const val SELECT_RESOURCE_LOCKS = "SELECT $RESOURCE_LOCK_COLUMNS FROM resource_locks ORDER BY rowid"
        private const val INSERT_RESOURCE_LOCK =
            "INSERT INTO resource_locks ($RESOURCE_LOCK_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val RESOURCE_QUEUE_COLUMNS =
            "kind, resource_id, project_id, issue_id, identifier, attempt_id, requested_at, place, any_of_kind"
        private const val SELECT_RESOURCE_QUEUE = "SELECT $RESOURCE_QUEUE_COLUMNS FROM resource_queue ORDER BY place"
        private const val INSERT_RESOURCE_REQUEST =
            "INSERT INTO resource_queue ($RESOURCE_QUEUE_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val RESOURCE_DIRTY_COLUMNS = "kind, resource_id, project_id, check_name, difference, marked_at"
        private const val SELECT_RESOURCE_DIRTY = "SELECT $RESOURCE_DIRTY_COLUMNS FROM resource_dirty ORDER BY rowid"
        private const val INSERT_RESOURCE_DIRTY = "INSERT INTO resource_dirty ($RESOURCE_DIRTY_COLUMNS) VALUES (?, ?, ?, ?, ?, ?)"
        private const val FROZEN_COLUMNS = "project_id, issue_id, identifier, digest, count, frozen_at, changed_digest, changed_count"
        private const val INSERT_FROZEN =
            "INSERT INTO acceptance_commands (project_id, issue_id, identifier, digest, count, frozen_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (project_id, issue_id) DO NOTHING"
        private const val SELECT_FROZEN = "SELECT $FROZEN_COLUMNS FROM acceptance_commands WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val MARK_FROZEN_CHANGED =
            "UPDATE acceptance_commands SET changed_digest = ?, changed_count = ? WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val CLEAR_FROZEN_CHANGED =
            "UPDATE acceptance_commands SET changed_digest = NULL, changed_count = NULL WHERE project_id = ? AND issue_id IN (?, ?)"
        private const val ACCEPT_FROZEN_CHANGED =
            "UPDATE acceptance_commands SET digest = changed_digest, count = changed_count, " +
                "changed_digest = NULL, changed_count = NULL WHERE project_id = ? AND issue_id IN (?, ?) AND changed_digest = ?"

        private val SCHEMA =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS events (
                    seq INTEGER PRIMARY KEY,
                    ts TEXT NOT NULL,
                    run_id TEXT,
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT,
                    issue_identifier TEXT,
                    attempt_id TEXT,
                    session_id TEXT,
                    turn_id TEXT,
                    kind TEXT NOT NULL,
                    phase TEXT,
                    data TEXT
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS events_project_issue ON events (project_id, issue_id, seq)",
                "CREATE INDEX IF NOT EXISTS events_attempt ON events (attempt_id)",
                // Version 2 (m1-07). Every statement is idempotent, so any older version upgrades by running them all.
                """
                CREATE TABLE IF NOT EXISTS parked_issues (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    state TEXT NOT NULL,
                    labels TEXT NOT NULL,
                    parked_at TEXT NOT NULL,
                    run_id TEXT,
                    PRIMARY KEY (project_id, issue_id)
                )
                """.trimIndent(),
                // Version 3 (m2-02): the claims and the retry queue with its counters.
                """
                CREATE TABLE IF NOT EXISTS claims (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    run_id TEXT,
                    claimed_at TEXT NOT NULL,
                    session_id TEXT,
                    runtime_session_id TEXT,
                    PRIMARY KEY (project_id, issue_id)
                )
                """.trimIndent(),
                // A queued retry has its attempt and due_at; error is null for a continuation.
                """
                CREATE TABLE IF NOT EXISTS retry_states (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    attempt INTEGER,
                    due_at TEXT,
                    error TEXT,
                    failures INTEGER NOT NULL,
                    no_progress INTEGER NOT NULL,
                    PRIMARY KEY (project_id, issue_id),
                    CHECK ((attempt IS NULL) = (due_at IS NULL))
                )
                """.trimIndent(),
                // Version 4 (m2-04): the waiting issues; data is a JSON object of strings, null when empty.
                """
                CREATE TABLE IF NOT EXISTS waiting_issues (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    attempt_id TEXT,
                    run_id TEXT,
                    since TEXT NOT NULL,
                    data TEXT,
                    PRIMARY KEY (project_id, issue_id)
                )
                """.trimIndent(),
                // Version 5 (m2-09): the acceptance command list of each issue, frozen at its first dispatch, as a
                // digest and a count, never the commands, which can hold a secret. changed_* wait for loop resume.
                """
                CREATE TABLE IF NOT EXISTS acceptance_commands (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    digest TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    frozen_at TEXT NOT NULL,
                    changed_digest TEXT,
                    changed_count INTEGER,
                    PRIMARY KEY (project_id, issue_id),
                    CHECK ((changed_digest IS NULL) = (changed_count IS NULL))
                )
                """.trimIndent(),
                // Version 6 (m2-19): the ledger's attempt rows. A null measure is one nobody could report, never zero.
                """
                CREATE TABLE IF NOT EXISTS attempts (
                    seq INTEGER PRIMARY KEY,
                    attempt_id TEXT NOT NULL UNIQUE,
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL CHECK (issue_id <> ''),
                    identifier TEXT NOT NULL,
                    run_id TEXT,
                    tracker TEXT NOT NULL,
                    kind TEXT,
                    runner TEXT NOT NULL,
                    model TEXT,
                    template_version TEXT NOT NULL,
                    config_version TEXT,
                    started_at TEXT NOT NULL,
                    finished_at TEXT NOT NULL,
                    wall_ms INTEGER NOT NULL,
                    outcome TEXT NOT NULL,
                    reason TEXT,
                    turns INTEGER NOT NULL,
                    issue_state TEXT,
                    input_tokens INTEGER,
                    output_tokens INTEGER,
                    cache_read_input_tokens INTEGER,
                    cache_creation_input_tokens INTEGER,
                    tokens INTEGER,
                    cost_usd REAL,
                    checks INTEGER,
                    guards INTEGER,
                    verdict TEXT,
                    attempt_number INTEGER NOT NULL,
                    human_touches INTEGER NOT NULL,
                    touch_seq INTEGER NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS attempts_project_issue ON attempts (project_id, issue_id, seq)",
                // Version 7 (m3-16): the machine slots, one row per attempt or merge check of a live process.
                """
                CREATE TABLE IF NOT EXISTS machine_slots (
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    holder TEXT NOT NULL CHECK (holder <> ''),
                    run_id TEXT NOT NULL,
                    pid INTEGER NOT NULL,
                    claimed_at TEXT NOT NULL,
                    PRIMARY KEY (project_id, holder)
                )
                """.trimIndent(),
                // Version 8 (m4-50): the resource locks, keyed by kind and resource id and never by project, so every
                // project of the machine shares them, and the requests that wait, in their place. prepared is a JSON
                // object of strings, null until the provider's prepare ran (m4-25). Version 9 (m4-25) adds the last
                // three columns, see ADDED_COLUMNS.
                """
                CREATE TABLE IF NOT EXISTS resource_locks (
                    kind TEXT NOT NULL CHECK (kind <> ''),
                    resource_id TEXT NOT NULL CHECK (resource_id <> ''),
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL,
                    identifier TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    granted_at TEXT NOT NULL,
                    prepared TEXT,
                    prepare_error TEXT,
                    answered INTEGER NOT NULL DEFAULT 0,
                    release_requested_at TEXT,
                    PRIMARY KEY (kind, resource_id)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS resource_queue (
                    kind TEXT NOT NULL CHECK (kind <> ''),
                    resource_id TEXT NOT NULL CHECK (resource_id <> ''),
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    issue_id TEXT NOT NULL,
                    identifier TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    requested_at TEXT NOT NULL,
                    place INTEGER NOT NULL,
                    any_of_kind INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (kind, resource_id, attempt_id)
                )
                """.trimIndent(),
                // Version 10 (m4-26): the dirty marks, one per resource whose read only check failed, machine wide like
                // the locks, and any_of_kind on the queue rows of a check that holds any resource of a kind.
                """
                CREATE TABLE IF NOT EXISTS resource_dirty (
                    kind TEXT NOT NULL CHECK (kind <> ''),
                    resource_id TEXT NOT NULL CHECK (resource_id <> ''),
                    project_id TEXT NOT NULL CHECK (project_id <> ''),
                    check_name TEXT NOT NULL,
                    difference TEXT NOT NULL,
                    marked_at TEXT NOT NULL,
                    PRIMARY KEY (kind, resource_id)
                )
                """.trimIndent(),
            )

        /**
         * Opens (and creates when missing) the store under [loopHome]. Fails with
         * [StoreError.LoopHomeInsideProject] when [loopHome] is [projectRoot] or inside it.
         * Every write goes through [redactor]; a read only command passes [Redactor.NONE].
         */
        fun open(
            loopHome: Path,
            projectRoot: Path,
            redactor: Redactor,
            ids: IssueIdForms = IssueIdForms.AS_STORED,
        ): Result<SqliteEventStore> {
            val home = canonical(loopHome)
            if (home.startsWith(canonical(projectRoot))) {
                return Result.failure(StoreError.LoopHomeInsideProject(loopHome, projectRoot))
            }
            return try {
                Files.createDirectories(home)
                val connection = DriverManager.getConnection("jdbc:sqlite:${home.resolve(FILE_NAME)}")
                try {
                    configure(connection)
                    migrate(connection)
                    Result.success(SqliteEventStore(connection, redactor, ids))
                } catch (e: Exception) {
                    connection.close()
                    throw e
                }
            } catch (e: Exception) {
                Result.failure(e as? StoreError ?: StoreError.Unavailable(e))
            }
        }

        /** Real path of [path], or of its nearest existing ancestor plus the rest, so symlinks compare equal. */
        private fun canonical(path: Path): Path {
            val absolute = path.toAbsolutePath().normalize()
            var existing: Path? = absolute
            while (existing != null && !Files.exists(existing)) existing = existing.parent
            if (existing == null) return absolute
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
        }

        private fun configure(connection: Connection) {
            connection.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                walMode(st)
                st.execute("PRAGMA synchronous = NORMAL")
            }
        }

        /**
         * Switches the store to WAL. Switching a file still in rollback mode needs a lock SQLite does not wait for on
         * `busy_timeout`, so of two processes creating the store at once (two `loop run` of a machine, m3-16) the second
         * failed at once with `SQLITE_BUSY`. It is tried again until `busy_timeout` has passed, like any other lock wait.
         */
        private fun walMode(statement: Statement) {
            val deadline = System.nanoTime() + BUSY_TIMEOUT_MS * 1_000_000L
            while (true) {
                try {
                    statement.execute("PRAGMA journal_mode = WAL")
                    return
                } catch (e: SQLException) {
                    val busy = e.errorCode == SQLITE_BUSY || e.message.orEmpty().contains("SQLITE_BUSY")
                    if (!busy || System.nanoTime() > deadline) throw e
                    Thread.sleep(WAL_RETRY_MS)
                }
            }
        }

        private const val BUSY_TIMEOUT_MS = 5000L
        private const val WAL_RETRY_MS = 50L
        private const val SQLITE_BUSY = 5

        /**
         * Brings the schema to [SCHEMA_VERSION]. A store already there takes no write lock. An older one is read again
         * and migrated inside one write transaction (bl-41), so of two processes opening it at once, one migrates and
         * the other waits on `busy_timeout`, then finds it current.
         */
        private fun migrate(connection: Connection) {
            if (checkedVersion(connection) == SCHEMA_VERSION) return
            immediate(connection) {
                if (checkedVersion(connection) < SCHEMA_VERSION) {
                    connection.createStatement().use { st ->
                        SCHEMA.forEach { st.execute(it) }
                        addMissingColumns(st)
                        st.execute("PRAGMA user_version = $SCHEMA_VERSION")
                    }
                }
            }
        }

        /**
         * Columns added to a table after its first version, as table, column and definition. `CREATE TABLE IF NOT
         * EXISTS` leaves a table of an older store as it was, so each is added when the table lacks it.
         */
        private val ADDED_COLUMNS =
            listOf(
                // Version 9 (m4-25): why prepare failed, whether the holder was answered granted, and when it released.
                Triple("resource_locks", "prepare_error", "TEXT"),
                Triple("resource_locks", "answered", "INTEGER NOT NULL DEFAULT 0"),
                Triple("resource_locks", "release_requested_at", "TEXT"),
                // Version 10 (m4-26): a queue row of a check's group, which waits on every resource of its kind.
                Triple("resource_queue", "any_of_kind", "INTEGER NOT NULL DEFAULT 0"),
            )

        private fun addMissingColumns(statement: Statement) {
            for ((table, column, definition) in ADDED_COLUMNS) {
                val present =
                    statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
                    }
                if (column !in present) statement.execute("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }

        /** The schema version of the store, refused when newer than [SCHEMA_VERSION]. */
        private fun checkedVersion(connection: Connection): Int {
            val version =
                connection.createStatement().use { st ->
                    st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            if (version > SCHEMA_VERSION) {
                throw StoreError.Unavailable(
                    IllegalStateException("Store schema version $version is newer than $SCHEMA_VERSION"),
                )
            }
            return version
        }

        /**
         * Runs [block] in one transaction opened with `BEGIN IMMEDIATE` on [connection], which the caller holds
         * (bl-41). It takes the write lock before the first read, waiting on `busy_timeout` like any write, so a read
         * then write never fails on a stale read snapshot (`SQLITE_BUSY_SNAPSHOT`) when another connection commits
         * in between. Committed when [block] returns, rolled back when it throws, its error kept.
         */
        private fun <T> immediate(
            connection: Connection,
            block: () -> T,
        ): T {
            connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            return try {
                block().also { connection.createStatement().use { it.execute("COMMIT") } }
            } catch (e: Throwable) {
                // A failed rollback must not hide the error that caused it.
                runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
                throw e
            }
        }

        private inline fun <T> sql(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: SQLException) {
                Result.failure(StoreError.Unavailable(e))
            } catch (e: SerializationException) {
                Result.failure(StoreError.Corrupt(e))
            } catch (e: IllegalArgumentException) {
                // Also covers a bad ts: kotlin.time.Instant.parse throws IllegalArgumentException.
                Result.failure(StoreError.Corrupt(e))
            } catch (e: DateTimeParseException) {
                Result.failure(StoreError.Corrupt(e))
            }

        private fun PreparedStatement.setNullable(
            index: Int,
            value: String?,
        ) {
            if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
        }

        private fun PreparedStatement.setNullableLong(
            index: Int,
            value: Long?,
        ) {
            if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
        }

        /** The column [name] as a Long, null when SQL NULL. */
        private fun ResultSet.longOrNull(name: String): Long? = getLong(name).takeUnless { wasNull() }

        private fun ResultSet.toLedgerRow(ids: IssueIdForms): LedgerRow {
            val input = longOrNull("input_tokens")
            val tokens =
                input?.let {
                    LedgerTokens(
                        input = it,
                        output = getLong("output_tokens"),
                        cacheRead = getLong("cache_read_input_tokens"),
                        cacheCreation = getLong("cache_creation_input_tokens"),
                    )
                }
            val cost = getDouble("cost_usd").takeUnless { wasNull() }
            val attempt =
                LedgerAttempt(
                    projectId = ProjectId(getString("project_id")),
                    runId = getString("run_id")?.let(::RunId),
                    issueId = ids.read(getString("issue_id")),
                    identifier = getString("identifier"),
                    attemptId = AttemptId(getString("attempt_id")),
                    setup =
                        LedgerSetup(
                            tracker = getString("tracker"),
                            kind = getString("kind"),
                            runner = getString("runner"),
                            model = getString("model"),
                            templateVersion = getString("template_version"),
                            configVersion = getString("config_version"),
                        ),
                    startedAt = Instant.parse(getString("started_at")),
                    finishedAt = Instant.parse(getString("finished_at")),
                    outcome = getString("outcome"),
                    reason = getString("reason"),
                    turns = getInt("turns"),
                    issueState = getString("issue_state"),
                    tokens = tokens,
                    costUsd = cost,
                    checks = longOrNull("checks")?.toInt(),
                    guards = longOrNull("guards")?.toInt(),
                    verdict = getString("verdict"),
                )
            return LedgerRow(attempt, number = getInt("attempt_number"), humanTouches = getInt("human_touches"))
        }

        /** [data] as a JSON object of strings, an empty one included. */
        private fun encodeMap(data: Map<String, String>): String = JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString()

        private fun encodeData(data: Map<String, String>): String? =
            if (data.isEmpty()) null else JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString()

        private fun decodeData(text: String?): Map<String, String> =
            if (text == null) {
                emptyMap()
            } else {
                Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }

        private fun encodeLabels(labels: Set<String>): String = JsonArray(labels.sorted().map { JsonPrimitive(it) }).toString()

        private fun decodeLabels(text: String): Set<String> =
            Json
                .parseToJsonElement(text)
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

        private fun ResultSet.toParkedIssue(ids: IssueIdForms): ParkedIssue =
            ParkedIssue(
                projectId = ProjectId(getString("project_id")),
                issueId = ids.read(getString("issue_id")),
                identifier = getString("identifier"),
                reason = ParkReason(getString("reason")),
                state = getString("state"),
                labels = decodeLabels(getString("labels")),
                parkedAt = Instant.parse(getString("parked_at")),
                runId = getString("run_id")?.let(::RunId),
            )

        private fun ResultSet.toClaim(ids: IssueIdForms): Claim =
            Claim(
                projectId = ProjectId(getString("project_id")),
                issueId = ids.read(getString("issue_id")),
                identifier = getString("identifier"),
                attemptId = AttemptId(getString("attempt_id")),
                runId = getString("run_id")?.let(::RunId),
                claimedAt = Instant.parse(getString("claimed_at")),
                sessionId = getString("session_id")?.let(::SessionId),
                runtimeSessionId = getString("runtime_session_id"),
            )

        private fun ResultSet.toRetryState(ids: IssueIdForms): RetryState {
            val dueAt = getString("due_at")
            val attempt = getInt("attempt")
            val retry = dueAt?.let { QueuedRetry(attempt, Instant.parse(it), getString("error")) }
            return RetryState(
                projectId = ProjectId(getString("project_id")),
                issueId = ids.read(getString("issue_id")),
                identifier = getString("identifier"),
                retry = retry,
                failures = getInt("failures"),
                noProgress = getInt("no_progress"),
            )
        }

        private fun ResultSet.toWaitingIssue(ids: IssueIdForms): WaitingIssue =
            WaitingIssue(
                projectId = ProjectId(getString("project_id")),
                issueId = ids.read(getString("issue_id")),
                identifier = getString("identifier"),
                reason = WaitReason(getString("reason")),
                attemptId = getString("attempt_id")?.let(::AttemptId),
                runId = getString("run_id")?.let(::RunId),
                since = Instant.parse(getString("since")),
                data = decodeData(getString("data")),
            )

        private fun ResultSet.toFrozenCommands(ids: IssueIdForms): FrozenCommands {
            val changedDigest = getString("changed_digest")
            val changedCount = getInt("changed_count")
            return FrozenCommands(
                projectId = ProjectId(getString("project_id")),
                issueId = ids.read(getString("issue_id")),
                identifier = getString("identifier"),
                digest = getString("digest"),
                count = getInt("count"),
                frozenAt = Instant.parse(getString("frozen_at")),
                changedDigest = changedDigest,
                changedCount = changedDigest?.let { changedCount },
            )
        }

        private fun ResultSet.toResourceLock(): ResourceLock =
            ResourceLock(
                kind = getString("kind"),
                resourceId = getString("resource_id"),
                projectId = ProjectId(getString("project_id")),
                issueId = getString("issue_id"),
                issueIdentifier = getString("identifier"),
                attemptId = AttemptId(getString("attempt_id")),
                grantedAt = Instant.parse(getString("granted_at")),
                prepared = getString("prepared")?.let(::decodeData),
                prepareError = getString("prepare_error"),
                answered = getInt("answered") != 0,
                releaseRequestedAt = getString("release_requested_at")?.let(Instant::parse),
            )

        private fun ResultSet.toResourceRequest(): ResourceRequest =
            ResourceRequest(
                kind = getString("kind"),
                resourceId = getString("resource_id"),
                projectId = ProjectId(getString("project_id")),
                issueId = getString("issue_id"),
                issueIdentifier = getString("identifier"),
                attemptId = AttemptId(getString("attempt_id")),
                requestedAt = Instant.parse(getString("requested_at")),
                anyOfKind = getInt("any_of_kind") != 0,
            )

        private fun ResultSet.toDirtyResource(): DirtyResource =
            DirtyResource(
                kind = getString("kind"),
                resourceId = getString("resource_id"),
                projectId = ProjectId(getString("project_id")),
                check = getString("check_name"),
                difference = getString("difference"),
                markedAt = Instant.parse(getString("marked_at")),
            )

        private fun ResultSet.toStoredEvent(ids: IssueIdForms): StoredEvent =
            StoredEvent(
                seq = getLong("seq"),
                event =
                    RecordedEvent(
                        ts = Instant.parse(getString("ts")),
                        projectId = ProjectId(getString("project_id")),
                        kind = getString("kind"),
                        runId = getString("run_id")?.let(::RunId),
                        issueId = getString("issue_id")?.let(ids::read),
                        issueIdentifier = getString("issue_identifier"),
                        attemptId = getString("attempt_id")?.let(::AttemptId),
                        sessionId = getString("session_id")?.let(::SessionId),
                        turnId = getString("turn_id")?.let(::TurnId),
                        phase = getString("phase"),
                        data = decodeData(getString("data")),
                    ),
            )
    }
}

/**
 * One machine slot (m3-16): a row of the shared store held by one attempt or merge check, [holder] being its own id,
 * of the `loop run` of [projectId] whose run is [runId] in the process [pid]. It counts toward the machine cap only
 * while the run file of its project names that run and that process, alive.
 */
data class MachineSlot(
    val projectId: ProjectId,
    val holder: String,
    val runId: RunId,
    val pid: Long,
    val claimedAt: Instant,
)

/** A recorded event with the sequence number the database assigned to it. */
data class StoredEvent(
    val seq: Long,
    val event: RecordedEvent,
)

/** Why the store could not be opened or used. */
sealed class StoreError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The store would live inside a project, which ADR 0001 forbids. */
    class LoopHomeInsideProject(
        val loopHome: Path,
        val projectRoot: Path,
    ) : StoreError("Loop home $loopHome is inside the project $projectRoot")

    /** The database could not be opened, read or written. */
    class Unavailable(
        cause: Throwable,
    ) : StoreError(cause.message ?: "Store unavailable", cause)

    /** A stored row cannot be decoded back into an event. */
    class Corrupt(
        cause: Throwable,
    ) : StoreError("Corrupt event row: ${cause.message}", cause)
}
