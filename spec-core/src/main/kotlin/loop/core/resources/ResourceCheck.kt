package loop.core.resources

/**
 * The resource plugins the host found (m4-24, PLAN 6.9), which the loader asks on every load whether a provider serves
 * each kind of `resources:`. spec-core never depends on a resource plugin: the host hands it this seam, as it hands it
 * the tracker and runner plugins.
 */
fun interface ResourceCheck {
    /**
     * Whether a provider serves [kind], or a failure whose message, without a final period, says why the providers
     * could not be found. The load then fails with `invalid_config`.
     */
    fun serves(kind: String): Result<Boolean>

    companion object {
        /** No resource plugin: every kind is a plain lock. */
        val NONE: ResourceCheck = ResourceCheck { Result.success(false) }
    }
}
