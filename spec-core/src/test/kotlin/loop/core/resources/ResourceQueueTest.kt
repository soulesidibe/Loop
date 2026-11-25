package loop.core.resources

import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.ResourceLock
import loop.api.ResourceLockRows
import loop.api.ResourceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m4-50: the queue of the resource locks grants in request order, first requested first granted, and never grants one
 * resource twice, whatever the project of the request. Each case of the table is a list of steps run in order on the
 * rows of one machine; after every step no kind and id has two locks, and a queue waits only on a held resource.
 */
class ResourceQueueTest {
    private val t0 = Instant.parse("2026-11-25T10:00:00Z")

    private sealed interface Step {
        /** The attempt [attempt] of project [project] asks for [kind] [id]; [expect] is `granted` or `queued <n>`. */
        data class Request(
            val attempt: String,
            val kind: String,
            val id: String,
            val expect: String,
            val project: String = "p1",
        ) : Step

        /** [attempt] releases its lock of [kind] [id]; [granted] is the attempt the lock goes to, null when none. */
        data class Release(
            val attempt: String,
            val kind: String,
            val id: String,
            val granted: String?,
        ) : Step

        /** Every lock and queue row of [attempt] goes; [released] are the resources it held, [granted] the attempts given them. */
        data class FreeAttempt(
            val attempt: String,
            val released: List<String>,
            val granted: List<String>,
        ) : Step

        /** Every lock and queue row of [project] goes; [released] as above, [granted] the attempts given them. */
        data class FreeProject(
            val project: String,
            val released: List<String>,
            val granted: List<String>,
        ) : Step

        /** The holders of the machine, `kind/id=attempt` in row order, and the queue, `kind/id<-attempt` oldest first. */
        data class Holds(
            val locks: List<String>,
            val queue: List<String> = emptyList(),
        ) : Step
    }

    private data class Case(
        val name: String,
        val steps: List<Step>,
    )

    private val cases =
        listOf(
            Case(
                "a free resource is granted at once, the next requests queue in order and are granted in that order",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Request("a3", "avd", "api35", "queued 2"),
                    Step.Holds(listOf("avd/api35=a1"), listOf("avd/api35<-a2", "avd/api35<-a3")),
                    Step.Release("a1", "avd", "api35", granted = "a2"),
                    Step.Holds(listOf("avd/api35=a2"), listOf("avd/api35<-a3")),
                    Step.Release("a2", "avd", "api35", granted = "a3"),
                    Step.Release("a3", "avd", "api35", granted = null),
                    Step.Holds(emptyList()),
                ),
            ),
            Case(
                "a request again by the holder or by a queued attempt changes nothing and keeps its place",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Request("a3", "avd", "api35", "queued 2"),
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a3", "avd", "api35", "queued 2"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Holds(listOf("avd/api35=a1"), listOf("avd/api35<-a2", "avd/api35<-a3")),
                ),
            ),
            Case(
                "a lock is keyed by kind and id: one id of two kinds is two resources, one kind and id of two projects is one",
                listOf(
                    Step.Request("a1", "avd", "pixel", "granted", project = "p1"),
                    Step.Request("a2", "android-device", "pixel", "granted", project = "p1"),
                    Step.Request("b1", "avd", "pixel", "queued 1", project = "p2"),
                    Step.Holds(listOf("avd/pixel=a1", "android-device/pixel=a2"), listOf("avd/pixel<-b1")),
                    Step.Release("a1", "avd", "pixel", granted = "b1"),
                    Step.Holds(listOf("android-device/pixel=a2", "avd/pixel=b1")),
                ),
            ),
            Case(
                "freeing an attempt drops its queue rows and frees its locks, each going to the head of its own queue",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a1", "ios-simulator", "iphone-16", "granted"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Request("a3", "ios-simulator", "iphone-16", "queued 1"),
                    Step.Request("a2", "ios-simulator", "iphone-16", "queued 2"),
                    Step.Request("a4", "avd", "api35", "queued 2"),
                    Step.FreeAttempt("a1", released = listOf("avd/api35", "ios-simulator/iphone-16"), granted = listOf("a2", "a3")),
                    Step.Holds(
                        listOf("avd/api35=a2", "ios-simulator/iphone-16=a3"),
                        listOf("ios-simulator/iphone-16<-a2", "avd/api35<-a4"),
                    ),
                ),
            ),
            Case(
                "a queued attempt freed before its turn never gets the lock, the next one does",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Request("a3", "avd", "api35", "queued 2"),
                    Step.FreeAttempt("a2", released = emptyList(), granted = emptyList()),
                    Step.Request("a4", "avd", "api35", "queued 2"),
                    Step.Release("a1", "avd", "api35", granted = "a3"),
                    Step.Release("a3", "avd", "api35", granted = "a4"),
                ),
            ),
            Case(
                "freeing a project frees only its own rows: another project's holder and queue stay, in their order",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted", project = "p1"),
                    Step.Request("b1", "avd", "api35", "queued 1", project = "p2"),
                    Step.Request("a2", "avd", "api35", "queued 2", project = "p1"),
                    Step.Request("b2", "avd", "api35", "queued 3", project = "p2"),
                    Step.Request("b1", "display", "main", "granted", project = "p2"),
                    Step.FreeProject("p1", released = listOf("avd/api35"), granted = listOf("b1")),
                    Step.Holds(listOf("display/main=b1", "avd/api35=b1"), listOf("avd/api35<-b2")),
                ),
            ),
            Case(
                "a release by an attempt that holds nothing changes nothing",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a2", "avd", "api35", "queued 1"),
                    Step.Release("a2", "avd", "api35", granted = null),
                    Step.Release("a9", "avd", "other", granted = null),
                    Step.Holds(listOf("avd/api35=a1"), listOf("avd/api35<-a2")),
                ),
            ),
        )

    @Test
    fun `the queue grants in request order and never grants one resource twice`() {
        for (case in cases) {
            var rows = ResourceLockRows()
            var now = t0
            for ((index, step) in case.steps.withIndex()) {
                val at = "${case.name}, step ${index + 1}"
                now = now.plus(1.seconds)
                when (step) {
                    is Step.Request -> {
                        val requested = ResourceQueue.request(rows, request(step, now), now)
                        rows = requested.change.rows
                        val grant = requested.grant
                        val actual =
                            when (grant) {
                                is ResourceGrant.Granted -> "granted".also { assertEquals(step.attempt, grant.lock.attemptId.value, at) }
                                is ResourceGrant.Queued -> "queued ${grant.position}"
                            }
                        assertEquals(step.expect, actual, at)
                    }
                    is Step.Release -> {
                        val change = ResourceQueue.release(rows, step.kind, step.id, AttemptId(step.attempt), now)
                        rows = change.rows
                        assertEquals(listOfNotNull(step.granted), change.granted.map { it.attemptId.value }, at)
                        change.granted.forEach { assertEquals(now, it.grantedAt, at) }
                    }
                    is Step.FreeAttempt -> {
                        val change = ResourceQueue.freeAttempt(rows, AttemptId(step.attempt), now)
                        rows = change.rows
                        assertEquals(step.released, change.released.map { "${it.kind}/${it.resourceId}" }, at)
                        assertEquals(step.granted, change.granted.map { it.attemptId.value }, at)
                    }
                    is Step.FreeProject -> {
                        val change = ResourceQueue.freeProject(rows, ProjectId(step.project), now)
                        rows = change.rows
                        assertEquals(step.released, change.released.map { "${it.kind}/${it.resourceId}" }, at)
                        assertEquals(step.granted, change.granted.map { it.attemptId.value }, at)
                    }
                    is Step.Holds -> {
                        assertEquals(step.locks, rows.locks.map { "${it.kind}/${it.resourceId}=${it.attemptId.value}" }, at)
                        assertEquals(step.queue, rows.queue.map { "${it.kind}/${it.resourceId}<-${it.attemptId.value}" }, at)
                    }
                }
                assertInvariants(rows, at)
            }
        }
    }

    @Test
    fun `a promoted request keeps its holder ids and is granted at the time of the release`() {
        var rows = ResourceQueue.request(ResourceLockRows(), request(Step.Request("a1", "avd", "api35", ""), t0), t0).change.rows
        val asked = t0.plus(5.seconds)
        rows = ResourceQueue.request(rows, request(Step.Request("b1", "avd", "api35", "", project = "p2"), asked), asked).change.rows
        val freedAt = t0.plus(1.minutes)

        val change = ResourceQueue.release(rows, "avd", "api35", AttemptId("a1"), freedAt)

        val lock = change.granted.single()
        assertEquals(ResourceLock("avd", "api35", ProjectId("p2"), "issue-b1", "B-b1", AttemptId("b1"), freedAt), lock)
        assertEquals(listOf(lock), change.rows.locks)
        assertEquals(
            "a1",
            change.released
                .single()
                .attemptId.value,
        )
    }

    @Test
    fun `rows a crash left with a queue and no lock grant the head first, so a new request still queues behind it`() {
        val waiting = request(Step.Request("a2", "avd", "api35", ""), t0)
        val rows = ResourceLockRows(queue = listOf(waiting))
        val now = t0.plus(1.seconds)

        val requested = ResourceQueue.request(rows, request(Step.Request("a3", "avd", "api35", ""), now), now)

        assertIs<ResourceGrant.Queued>(requested.grant).also { assertEquals(1, it.position) }
        assertEquals(listOf("a2"), requested.change.granted.map { it.attemptId.value })
        assertEquals(
            listOf("a2"),
            requested.change.rows.locks
                .map { it.attemptId.value },
        )
    }

    private fun request(
        step: Step.Request,
        at: Instant,
    ) = ResourceRequest(
        kind = step.kind,
        resourceId = step.id,
        projectId = ProjectId(step.project),
        issueId = "issue-${step.attempt}",
        issueIdentifier = "${step.attempt.first().uppercase()}-${step.attempt}",
        attemptId = AttemptId(step.attempt),
        requestedAt = at,
    )

    private fun assertInvariants(
        rows: ResourceLockRows,
        at: String,
    ) {
        val keys = rows.locks.map { it.kind to it.resourceId }
        assertEquals(keys.distinct(), keys, "$at: one resource granted twice")
        rows.queue.forEach { waiting ->
            val held = rows.locks.any { it.kind == waiting.kind && it.resourceId == waiting.resourceId }
            assertEquals(true, held, "$at: ${waiting.attemptId.value} waits on a free resource")
            val holder = rows.locks.single { it.kind == waiting.kind && it.resourceId == waiting.resourceId }
            assertEquals(false, holder.attemptId == waiting.attemptId, "$at: the holder also waits")
        }
        val queued = rows.queue.map { Triple(it.kind, it.resourceId, it.attemptId) }
        assertEquals(queued.distinct(), queued, "$at: one attempt queued twice")
    }
}
