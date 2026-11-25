package loop.core.resources

import loop.api.AttemptId
import loop.api.DirtyResource
import loop.api.ProjectId
import loop.api.ResourceLockRows
import loop.api.ResourceRequest
import loop.core.events.ResourceQueued
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * m4-26: the machine wide limits of the resource locks, at most 2 Android devices and emulators together and at most 2
 * self managed iOS simulators, the dirty mark that keeps a resource from being lent, and the requests of a check that
 * holds any resource of a kind. Each case of the table is a list of steps run in order on the rows of one machine.
 */
class ResourceLimitsTest {
    private val t0 = Instant.parse("2026-11-25T10:00:00Z")

    private sealed interface Step {
        /** [attempt] of [project] asks for [kind] [id]; [expect] is `granted` or `queued <n> <reason>`. */
        data class Request(
            val attempt: String,
            val kind: String,
            val id: String,
            val expect: String,
            val project: String = "p1",
        ) : Step

        /** [holder] asks for any of [ids] of [kind]; [expect] is `granted <id>` or `queued <n> <reason>`. */
        data class RequestAny(
            val holder: String,
            val kind: String,
            val ids: List<String>,
            val expect: String,
        ) : Step

        /** [attempt] releases [kind] [id], marked dirty when [dirty]; [granted] are `attempt=kind/id` given a lock. */
        data class Release(
            val attempt: String,
            val kind: String,
            val id: String,
            val granted: List<String>,
            val dirty: Boolean = false,
        ) : Step

        /** The dirty mark of [kind] [id] goes; [granted] as above. */
        data class Clear(
            val kind: String,
            val id: String,
            val granted: List<String>,
        ) : Step

        /** The holders, `kind/id=attempt` in row order, and the queue, `kind/id<-attempt` oldest first. */
        data class Holds(
            val locks: List<String>,
            val queue: List<String> = emptyList(),
        ) : Step
    }

    private data class Case(
        val name: String,
        val steps: List<Step>,
    )

    private val limit = ResourceQueued.LIMIT
    private val held = ResourceQueued.HELD
    private val dirty = ResourceQueued.DIRTY

    private val cases =
        listOf(
            Case(
                "a third Android grant waits with reason limit while two are held, android-device and avd as one pool",
                listOf(
                    Step.Request("a1", "android-device", "pixel-8", "granted"),
                    Step.Request("a2", "avd", "api35", "granted"),
                    Step.Request("a3", "avd", "api34", "queued 1 $limit"),
                    Step.Request("a4", "android-device", "pixel-7", "queued 1 $limit", project = "p2"),
                    Step.Holds(listOf("android-device/pixel-8=a1", "avd/api35=a2"), listOf("avd/api34<-a3", "android-device/pixel-7<-a4")),
                    Step.Release("a2", "avd", "api35", granted = listOf("a3=avd/api34")),
                    Step.Release("a1", "android-device", "pixel-8", granted = listOf("a4=android-device/pixel-7")),
                    Step.Holds(listOf("avd/api34=a3", "android-device/pixel-7=a4")),
                ),
            ),
            Case(
                "a third iOS simulator grant waits with reason limit while two are held, and is granted when one releases",
                listOf(
                    Step.Request("a1", "ios-simulator", "iphone-16", "granted"),
                    Step.Request("a2", "ios-simulator", "iphone-15", "granted", project = "p2"),
                    Step.Request("a3", "ios-simulator", "ipad", "queued 1 $limit"),
                    Step.Request("a4", "ios-simulator", "iphone-16", "queued 1 $held"),
                    Step.Release("a2", "ios-simulator", "iphone-15", granted = listOf("a3=ios-simulator/ipad")),
                    Step.Holds(listOf("ios-simulator/iphone-16=a1", "ios-simulator/ipad=a3"), listOf("ios-simulator/iphone-16<-a4")),
                ),
            ),
            Case(
                "the pools are separate, and a kind of no pool has no limit",
                listOf(
                    Step.Request("a1", "ios-simulator", "iphone-16", "granted"),
                    Step.Request("a2", "ios-simulator", "iphone-15", "granted"),
                    Step.Request("a3", "avd", "api35", "granted"),
                    Step.Request("a4", "avd", "api34", "granted"),
                    Step.Request("a5", "display", "one", "granted"),
                    Step.Request("a6", "display", "two", "granted"),
                    Step.Request("a7", "display", "three", "granted"),
                ),
            ),
            Case(
                "limit waits are granted in request order across the pool, whatever the resource",
                listOf(
                    Step.Request("a1", "avd", "api35", "granted"),
                    Step.Request("a2", "avd", "api34", "granted"),
                    Step.Request("a3", "avd", "api33", "queued 1 $limit"),
                    Step.Request("a4", "avd", "api35", "queued 1 $held"),
                    Step.Request("a5", "avd", "api32", "queued 1 $limit"),
                    Step.Release("a2", "avd", "api34", granted = listOf("a3=avd/api33")),
                    Step.Release("a1", "avd", "api35", granted = listOf("a4=avd/api35")),
                    Step.Release("a3", "avd", "api33", granted = listOf("a5=avd/api32")),
                ),
            ),
            Case(
                "a dirty resource is never lent: it waits with reason dirty, then a cleared mark grants it",
                listOf(
                    Step.Request("a1", "ios-simulator", "iphone-16", "granted"),
                    Step.Request("a2", "ios-simulator", "iphone-16", "queued 1 $held"),
                    Step.Release("a1", "ios-simulator", "iphone-16", granted = emptyList(), dirty = true),
                    Step.Holds(emptyList(), listOf("ios-simulator/iphone-16<-a2")),
                    Step.Request("a3", "ios-simulator", "iphone-16", "queued 2 $dirty"),
                    Step.Clear("ios-simulator", "iphone-16", granted = listOf("a2=ios-simulator/iphone-16")),
                    Step.Holds(listOf("ios-simulator/iphone-16=a2"), listOf("ios-simulator/iphone-16<-a3")),
                ),
            ),
            Case(
                "a check takes any free resource of its kind, two checks get their own, a third waits and gets the first freed",
                listOf(
                    Step.RequestAny("c1", "ios-simulator", listOf("sim-1", "sim-2"), "granted sim-1"),
                    Step.RequestAny("c2", "ios-simulator", listOf("sim-1", "sim-2"), "granted sim-2"),
                    Step.RequestAny("c3", "ios-simulator", listOf("sim-1", "sim-2"), "queued 1 $held"),
                    Step.RequestAny("c3", "ios-simulator", listOf("sim-1", "sim-2"), "queued 1 $held"),
                    Step.Holds(
                        listOf("ios-simulator/sim-1=c1", "ios-simulator/sim-2=c2"),
                        listOf("ios-simulator/sim-1<-c3", "ios-simulator/sim-2<-c3"),
                    ),
                    Step.Release("c2", "ios-simulator", "sim-2", granted = listOf("c3=ios-simulator/sim-2")),
                    Step.Holds(listOf("ios-simulator/sim-1=c1", "ios-simulator/sim-2=c3")),
                ),
            ),
            Case(
                "a check's request counts against the limit like any grant",
                listOf(
                    Step.Request("a1", "ios-simulator", "iphone-16", "granted"),
                    Step.Request("a2", "ios-simulator", "iphone-15", "granted"),
                    Step.RequestAny("c1", "ios-simulator", listOf("sim-1", "sim-2"), "queued 1 $limit"),
                    Step.Release("a1", "ios-simulator", "iphone-16", granted = listOf("c1=ios-simulator/sim-1")),
                    Step.Holds(listOf("ios-simulator/iphone-15=a2", "ios-simulator/sim-1=c1")),
                ),
            ),
        )

    @Test
    fun `the limits, the dirty mark and the requests of checks, in request order`() {
        for (case in cases) {
            var rows = ResourceLockRows()
            var now = t0
            for ((index, step) in case.steps.withIndex()) {
                val at = "${case.name}, step ${index + 1}"
                now = now.plus(1.seconds)
                when (step) {
                    is Step.Request -> {
                        val requested = ResourceQueue.request(rows, request(step.attempt, step.kind, step.id, step.project, now), now)
                        rows = requested.change.rows
                        assertEquals(step.expect, describe(requested.grant, withId = false), at)
                    }
                    is Step.RequestAny -> {
                        val template = request(step.holder, step.kind, "", "p1", now)
                        val requested = ResourceQueue.requestAny(rows, template, step.ids, now)
                        rows = requested.change.rows
                        assertEquals(step.expect, describe(requested.grant, withId = true), at)
                    }
                    is Step.Release -> {
                        val mark = if (step.dirty) DirtyResource(step.kind, step.id, ProjectId("p1"), "provider", "changed", now) else null
                        val change = ResourceQueue.release(rows, step.kind, step.id, AttemptId(step.attempt), now, mark)
                        rows = change.rows
                        assertEquals(step.granted, change.granted.map { "${it.attemptId.value}=${it.kind}/${it.resourceId}" }, at)
                    }
                    is Step.Clear -> {
                        val change = ResourceQueue.clearDirty(rows, step.kind, step.id, now)
                        rows = change.rows
                        assertEquals(step.granted, change.granted.map { "${it.attemptId.value}=${it.kind}/${it.resourceId}" }, at)
                    }
                    is Step.Holds -> {
                        assertEquals(step.locks, rows.locks.map { "${it.kind}/${it.resourceId}=${it.attemptId.value}" }, at)
                        assertEquals(step.queue, rows.queue.map { "${it.kind}/${it.resourceId}<-${it.attemptId.value}" }, at)
                    }
                }
                assertWithinLimits(rows, at)
            }
        }
    }

    @Test
    fun `the limits are named constants and android-device and avd share one pool`() {
        assertEquals(2, ResourceLimits.ANDROID)
        assertEquals(2, ResourceLimits.IOS_SIMULATORS)
        assertEquals(ResourceLimits.poolOf("avd"), ResourceLimits.poolOf("android-device"))
        assertEquals(ResourceLimits.IOS_SIMULATORS, ResourceLimits.poolOf("ios-simulator")?.limit)
        assertEquals(null, ResourceLimits.poolOf("display"))
    }

    @Test
    fun `a dirty mark stays when its holder's project is freed, and a release of a dirty resource keeps one mark`() {
        val now = t0
        var rows = ResourceQueue.request(ResourceLockRows(), request("a1", "avd", "api35", "p1", now), now).change.rows
        val mark = DirtyResource("avd", "api35", ProjectId("p1"), "read_only_check", "exit code 1", now)
        rows = ResourceQueue.release(rows, "avd", "api35", AttemptId("a1"), now, mark).rows
        rows = ResourceQueue.freeProject(rows, ProjectId("p1"), now).rows

        assertEquals(listOf(mark), rows.dirty)
        val asked = ResourceQueue.request(rows, request("a2", "avd", "api35", "p2", now), now)
        assertIs<ResourceGrant.Queued>(asked.grant).also { assertEquals(dirty, it.reason) }
    }

    private fun describe(
        grant: ResourceGrant,
        withId: Boolean,
    ): String =
        when (grant) {
            is ResourceGrant.Granted -> if (withId) "granted ${grant.lock.resourceId}" else "granted"
            is ResourceGrant.Queued -> "queued ${grant.position} ${grant.reason}"
        }

    private fun request(
        attempt: String,
        kind: String,
        id: String,
        project: String,
        at: Instant,
    ) = ResourceRequest(
        kind = kind,
        resourceId = id,
        projectId = ProjectId(project),
        issueId = "issue-$attempt",
        issueIdentifier = "T-$attempt",
        attemptId = AttemptId(attempt),
        requestedAt = at,
    )

    private fun assertWithinLimits(
        rows: ResourceLockRows,
        at: String,
    ) {
        val keys = rows.locks.map { it.kind to it.resourceId }
        assertEquals(keys.distinct(), keys, "$at: one resource granted twice")
        for (pool in ResourceLimits.POOLS) {
            val count = rows.locks.count { it.kind in pool.kinds }
            assertEquals(true, count <= pool.limit, "$at: ${pool.name} holds $count")
        }
        val dirty = rows.dirty.map { it.kind to it.resourceId }
        assertEquals(emptyList(), keys.filter { it in dirty }, "$at: a dirty resource was lent")
    }
}
