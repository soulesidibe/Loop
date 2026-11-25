package loop.api

/**
 * A resource provider plugin (m4-24, PLAN 6.9 and 12): it makes the machine resources of its [kinds] usable, such as
 * an iOS simulator or an Android emulator. Providers are found like runner providers, with Java's `ServiceLoader`, on
 * the classpath and in each JAR of `<LOOP_HOME>/plugins/`, so a module registers its provider with a
 * `META-INF/services/loop.api.ResourceProvider` resource naming its provider class, which needs a public no argument
 * constructor. Two providers serving one kind fail startup. A resource whose kind no provider serves is a plain lock:
 * Loop grants and releases it, and nothing is prepared.
 *
 * Loop calls [prepare] after it granted a resource and [release] after its holder released it, [readOnlyCheck] after
 * each release, and [recover] once at startup. The lock itself, the queue and the limits are Loop's, never the
 * provider's. [ResourceProviderContract] in the test fixtures is the behavior every provider must show.
 */
public interface ResourceProvider {
    /** The `resources[].kind` values this provider serves, never empty: a set, since one provider can serve `android-device` and `avd`. */
    public val kinds: Set<String>

    /**
     * Makes [resource], just granted to [attemptId], usable, booting it when needed, and returns what the agent needs to
     * use it, such as `udid` or `serial`: a small map of non blank keys and values, empty when it needs nothing. Throws
     * [ResourceException] when the resource cannot be made usable.
     *
     * [attemptId] is the holder of the lock (bl-208): an attempt id, or `check-<uuid>` and `recheck-<uuid>` for the
     * checks of Loop. A provider uses it where it names what it creates, such as a simulator or a boot record.
     *
     * A prepare must be idempotent (bl-208): Loop can call it again for the same holder when the outcome of a first
     * call was lost, canceled on shutdown or not written, and the second call reuses or cleans what the first made, so
     * one [release] gives everything back. Loop gives each call a timeout of its own, interrupts the thread when it
     * passes and moves on, so a provider stops on an interrupt.
     */
    public fun prepare(
        resource: ResourceDeclaration,
        attemptId: AttemptId,
    ): Map<String, String>

    /**
     * Gives back [resource] after its holder released it, undoing what [prepare] started, so the next holder can
     * prepare it again. Throws [ResourceException] when it cannot. It has the same timeout as [prepare].
     */
    public fun release(resource: ResourceDeclaration)

    /**
     * The provider's own read only check of [resource], run after each release beside the declared `read_only_check`
     * command: one line per difference from the state it was lent in, empty when it came back as lent. A difference
     * left by the holder survives [release], since release never restores.
     */
    public fun readOnlyCheck(resource: ResourceDeclaration): List<String>

    /**
     * Called once at startup with [liveHolders], the holders of every live lock on the machine, of every kind and
     * project, so the provider removes what a crashed run left on a resource of its kinds that no live holder has, and
     * leaves the others alone. Holders of kinds it does not serve are ignored.
     */
    public fun recover(liveHolders: List<ResourceHolder>)
}

/**
 * One entry of `resources:` in `WORKFLOW.md` (m4-24): its [kind], which selects the provider, its [id], unique in the
 * workflow, what it [can] and [cannot] test, free text shown to the agent, the shell commands of [restore], and the
 * shell command of [readOnlyCheck], null when none is declared.
 */
public data class ResourceDeclaration(
    public val kind: String,
    public val id: String,
    public val can: List<String> = emptyList(),
    public val cannot: List<String> = emptyList(),
    public val restore: List<String> = emptyList(),
    public val readOnlyCheck: String? = null,
)

/**
 * The holder of one live lock: the resource of [kind] and [resourceId] declared by the project [projectId], held by
 * [holder], the attempt or the check that took it.
 */
public data class ResourceHolder(
    public val projectId: ProjectId,
    public val kind: String,
    public val resourceId: String,
    public val holder: String,
)

/** A resource a provider cannot prepare or release; the message names the resource and why, never a secret value. */
public class ResourceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
