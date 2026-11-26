package loop.runner.acp

/**
 * The `agy` version rule of `preset: agy`, shared by [AgyPreflight] and the `initialize` check of [AcpHandshake]:
 * the version must be on the major of [AcpSettings.PINNED_AGY_VERSION] and no older than
 * [AcpSettings.MIN_AGY_VERSION]. A prerelease, such as `1.5.18-rc.1`, is older than its release.
 */
internal object AgyVersion {
    sealed interface Problem {
        /** [found] is not on the pinned major, or names no major at all. */
        data class WrongMajor(
            val found: String,
        ) : Problem

        /** [found] is on the pinned major but older than the minimum, or its minor or patch cannot be read. */
        data class TooOld(
            val found: String,
        ) : Problem
    }

    private val VERSION = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""")

    private data class Parsed(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: Boolean,
    ) : Comparable<Parsed> {
        override fun compareTo(other: Parsed): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { if (it.prerelease) 0 else 1 })
    }

    /** The problem with [found], or null when it may run. */
    fun check(
        found: String,
        pinned: String = AcpSettings.PINNED_AGY_VERSION,
        minimum: String = AcpSettings.MIN_AGY_VERSION,
    ): Problem? {
        val pinnedMajor = pinned.substringBefore('.').toInt()
        val foundMajor = found.trim().substringBefore('.').toIntOrNull()
        if (foundMajor != pinnedMajor) return Problem.WrongMajor(found)
        val parsed = parse(found) ?: return Problem.TooOld(found)
        val floor = requireNotNull(parse(minimum)) { "the minimum agy version $minimum is not a version" }
        return if (parsed < floor) Problem.TooOld(found) else null
    }

    private fun parse(version: String): Parsed? {
        val match = VERSION.matchEntire(version.trim()) ?: return null
        val (major, minor, patch, prerelease) = match.destructured
        return Parsed(
            major = major.toIntOrNull() ?: return null,
            minor = minor.toIntOrNull() ?: return null,
            patch = if (patch.isEmpty()) 0 else patch.toIntOrNull() ?: return null,
            prerelease = prerelease.isNotEmpty(),
        )
    }
}
