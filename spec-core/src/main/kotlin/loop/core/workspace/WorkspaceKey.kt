package loop.core.workspace

import java.security.MessageDigest

/**
 * Directory name of an issue's workspace, derived from `issue.identifier`.
 * See docs/spec-decisions.md, answer 1.
 */
@JvmInline
value class WorkspaceKey(
    val value: String,
) {
    companion object {
        private const val SUFFIX_LENGTH = 16

        fun of(identifier: String): WorkspaceKey {
            val sanitized = identifier.map { if (it.isAllowed()) it else '_' }.joinToString("")
            if (sanitized == identifier) return WorkspaceKey(identifier)
            return WorkspaceKey("$sanitized-${suffix(identifier)}")
        }

        private fun Char.isAllowed(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "._-"

        private fun suffix(identifier: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(identifier.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(SUFFIX_LENGTH)
    }
}
