package loop.api

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behavior every [ResourceProvider] must show (m4-24): [ResourceProvider.prepare], [ResourceProvider.release], the
 * read only check and [ResourceProvider.recover]. A provider's test extends this class and supplies providers that
 * share one machine, a fake one for a provider tested against fake `xcrun simctl` or `adb` scripts, so a second
 * provider built by [provider] plays a restart of Loop after a crash.
 */
public abstract class ResourceProviderContract {
    /** A new provider over the fixture's machine. Each call plays a new process of Loop on the same machine. */
    protected abstract fun provider(): ResourceProvider

    /** A resource of one of the provider's kinds that the fixture's machine can make usable. */
    protected abstract fun resource(): ResourceDeclaration

    /**
     * Whether the fixture's machine still holds something [ResourceProvider.prepare] made for [resource], such as a
     * booted simulator, read from the machine itself, never through the provider. Always false for a provider that
     * leaves nothing behind.
     */
    protected abstract fun leftBehind(resource: ResourceDeclaration): Boolean

    /**
     * Changes [resource] on the fixture's machine as a careless holder would, so the read only check has a difference
     * to report. False when the fixture cannot, which skips that case.
     */
    protected open fun dirty(resource: ResourceDeclaration): Boolean = false

    private fun holderOf(
        resource: ResourceDeclaration,
        kind: String = resource.kind,
    ) = ResourceHolder(ProjectId("contract-project"), kind, resource.id, "contract-attempt")

    @Test
    public fun `the provider serves at least one kind, the kind of the resource among them`() {
        val kinds = provider().kinds

        assertTrue(kinds.isNotEmpty(), "the provider serves no kind")
        assertTrue(kinds.none { it.isBlank() }, "the provider serves a blank kind: $kinds")
        assertTrue(resource().kind in kinds, "the provider does not serve ${resource().kind}, the kind of the fixture's resource: $kinds")
    }

    @Test
    public fun `prepare returns what the agent needs as non blank keys and values`() {
        val prepared = provider().prepare(resource())

        assertTrue(
            prepared.all { (key, value) -> key.isNotBlank() && value.isNotBlank() },
            "prepare returned a blank key or value: $prepared",
        )
    }

    @Test
    public fun `a released resource prepares again for the next holder, with the same keys`() {
        val provider = provider()
        val resource = resource()

        val first = provider.prepare(resource)
        provider.release(resource)
        val second = provider.prepare(resource)
        provider.release(resource)

        assertEquals(first.keys, second.keys, "the second prepare gave other keys")
    }

    @Test
    public fun `a resource prepared and released untouched passes the read only check`() {
        val provider = provider()
        val resource = resource()

        provider.prepare(resource)
        provider.release(resource)

        assertEquals(emptyList(), provider.readOnlyCheck(resource), "the read only check found differences on an untouched resource")
    }

    @Test
    public fun `a difference the holder left is reported by the read only check after release`() {
        val provider = provider()
        val resource = resource()
        provider.prepare(resource)
        assumeTrue(dirty(resource), "the fixture cannot change this resource")

        provider.release(resource)
        val differences = provider.readOnlyCheck(resource)

        assertTrue(differences.isNotEmpty(), "the read only check missed the difference the holder left")
        assertTrue(differences.none { it.isBlank() }, "the read only check gave a blank line: $differences")
    }

    @Test
    public fun `recover with no live holder of the resource removes what a crashed run left, and the resource prepares again`() {
        val resource = resource()
        provider().prepare(resource)

        val restarted = provider()
        restarted.recover(listOf(holderOf(resource, kind = "loop-contract-unserved-kind")))

        assertFalse(leftBehind(resource), "recover left what the crashed run prepared")
        restarted.prepare(resource)
        restarted.release(resource)
        assertEquals(emptyList(), restarted.readOnlyCheck(resource), "the read only check found differences after recover")
    }

    @Test
    public fun `recover leaves the resource of a live holder alone`() {
        val resource = resource()
        provider().prepare(resource)
        val before = leftBehind(resource)

        provider().recover(listOf(holderOf(resource)))

        assertEquals(before, leftBehind(resource), "recover changed the resource of a live holder")
    }

    @Test
    public fun `recover with no live lock at all succeeds`() {
        provider().recover(emptyList())

        assertFalse(leftBehind(resource()), "recover left something on a resource nobody prepared")
    }
}
