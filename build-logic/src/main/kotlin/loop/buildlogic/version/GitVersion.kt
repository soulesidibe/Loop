package loop.buildlogic.version

import java.io.Serializable

/**
 * The build's version and commit from git (bl-159), kept free of the Gradle API: the cli test source set compiles
 * this folder too, so `GitVersionTest` checks the parsing without a Gradle build.
 *
 * The version comes from `git describe --tags --match '[0-9]*' --abbrev=12 --always --dirty`: a bare semver tag
 * such as `0.1.0` on the tag, `0.1.0-5-g<hex>` after it, the abbreviated commit with no tag reachable (or a clone
 * without tags), each with `-dirty` when tracked files have changes. `0.1.0-5-g<hex>` is a label, not a semver to
 * compare. A tag that is not a semver, or a value the cli would refuse, falls back to the abbreviated commit.
 */
object GitVersion {
    const val UNKNOWN: String = "unknown"

    /** The version label and the full commit, both [UNKNOWN] without git or a commit. */
    data class Result(
        val version: String,
        val commit: String,
    ) : Serializable

    /** [describe] is the trimmed output of the describe command, [fullSha] of `git rev-parse HEAD`; null on failure. */
    fun parse(
        describe: String?,
        fullSha: String?,
    ): Result {
        if (fullSha == null || !SHA.matches(fullSha)) return Result(UNKNOWN, UNKNOWN)
        val short = fullSha.take(SHORT)
        val match = describe?.let { DESCRIBE.matchEntire(it) } ?: return Result(short, fullSha)
        val (head, _, abbreviated, dirty) = match.destructured
        val version =
            when {
                // No tag reachable: describe --always prints the abbreviated commit.
                abbreviated.isEmpty() && HEX.matches(head) && fullSha.startsWith(head) -> head + dirty
                TAG.matches(head) && (abbreviated.isEmpty() || fullSha.startsWith(abbreviated)) ->
                    describe.takeIf { VALID.matches(it) }
                else -> null
            }
        return Result(version ?: (short + dirty), fullSha)
    }

    private const val SHORT = 12

    /** A sha1 or sha256 object name. */
    private val SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    private val HEX = Regex("[0-9a-f]{12,64}")

    /** Read from the end: an optional `-<count>-g<hex>`, then an optional `-dirty`. */
    private val DESCRIBE = Regex("(.+?)(?:-([0-9]+)-g([0-9a-f]{12,64}))?(-dirty)?")

    /** A bare semver with an optional pre-release, no `v` prefix and no build metadata. */
    private val TAG = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")

    /** The rule of the cli's `BuildInfo` and the codex runner's `LoopVersion`: a single printable token. */
    private val VALID = Regex("[A-Za-z0-9._-]{1,64}")
}
