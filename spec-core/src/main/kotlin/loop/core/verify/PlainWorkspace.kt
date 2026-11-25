package loop.core.verify

import java.nio.file.Path

/**
 * Verification of a `workspace: plain` kind (m4-20): its fingerprint is always `tree:<hash>`, it never lists uncommitted
 * files or restores anything, and a verified result is delivered only while the workspace is still at its fingerprint.
 */
object PlainWorkspace {
    /** The step a refused delivery names, the one of a pull request delivery whose fingerprint does not hold. */
    const val STEP_FINGERPRINT = "fingerprint"

    /** The fingerprint of [workspace]: `tree:` when [plain], else [Fingerprinter.of], `git:` for a work tree. */
    fun fingerprint(
        fingerprinter: Fingerprinter,
        workspace: Path,
        plain: Boolean,
    ): Result<String> = if (plain) fingerprinter.tree(workspace) else fingerprinter.of(workspace)

    /**
     * Why the verified result at [verified] cannot be delivered from the workspace [located], null when it is still at
     * that fingerprint: the workspace cannot be located or is gone, its fingerprint cannot be taken, or a file changed
     * after the verdict.
     */
    fun stale(
        fingerprinter: Fingerprinter,
        located: Result<Path?>,
        verified: String?,
    ): String? {
        val workspace =
            located.getOrElse { error -> return "cannot locate the workspace: ${describe(error)}" } ?: return "the workspace is gone"
        val now =
            fingerprinter.tree(workspace).getOrElse { error ->
                return "cannot take the workspace fingerprint: ${describe(error)}"
            }
        return if (now == verified) null else "the workspace is at $now, not at the verified $verified"
    }

    private fun describe(error: Throwable): String = error.message ?: error::class.simpleName ?: "unknown error"
}
