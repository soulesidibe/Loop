package loop.api

/**
 * The profile every runner and tracker plugin declares (m3-05, plan 12): its config [keys], with their type and
 * whether each is required or has a default, its [secrets], the keys whose value may be a `$NAME` reference to the
 * host env, and its [limits], the allowed range of integer keys such as timeouts. Loop checks the plugin's section
 * against it with [check] on every load of `WORKFLOW.md`, before the plugin reads the section itself.
 */
public class PluginProfile(
    public val keys: List<ProfileKey>,
    public val secrets: Set<String> = emptySet(),
    public val limits: Map<String, LongRange> = emptyMap(),
) {
    init {
        val names = keys.map { it.name }
        require(names.size == names.toSet().size) { "a profile lists each key once: $names" }
        require(secrets.all { it in names }) { "every secret is a key of the profile: $secrets" }
        require(limits.keys.all { name -> keys.any { it.name == name && it.type == ProfileType.INTEGER } }) {
            "every limit is on an integer key of the profile: ${limits.keys}"
        }
    }

    /**
     * Checks [config], a section as written (string, list, map or null values; an integer may also be a number),
     * whose keys are named `[section].<key>` in messages. Keys are checked in profile order and the first problem
     * wins: a required key missing or blank, a value of the wrong type, a `$NAME` secret unset or blank in [env], an
     * integer out of its limits. A literal secret is left to the plugin. A message never holds the value of a string,
     * list or map, only an integer out of its limits that is not a secret (bl-104). Keys the profile does not list
     * are reported, not refused, except under [UNDECLARED].
     */
    public fun check(
        section: String,
        config: Map<String, Any?>,
        env: Map<String, String>,
    ): ProfileReport {
        val problem = keys.firstNotNullOfOrNull { problemOf(it, "$section.${it.name}", config[it.name], env) }
        val known = keys.map { it.name }.toSet()
        val unknown = if (this === UNDECLARED) emptyList() else config.keys.filter { it !in known }.map { "$section.$it" }
        return ProfileReport(problem, unknown)
    }

    private fun problemOf(
        key: ProfileKey,
        path: String,
        value: Any?,
        env: Map<String, String>,
    ): ProfileProblem? {
        val secret = key.name in secrets
        if (value == null || (value is String && value.isBlank())) {
            return if (key.required) ProfileProblem.Missing(path, secret) else null
        }
        if (!key.type.accepts(value)) return ProfileProblem.WrongType(path, key.type, secret)
        if (secret && value is String) {
            ENV_REF.matchEntire(value.trim())?.groupValues?.get(1)?.let { name ->
                val resolved = env[name]
                if (resolved == null || resolved.isBlank()) return ProfileProblem.UnresolvedSecret(path, name, empty = resolved != null)
            }
        }
        val range = limits[key.name] ?: return null
        val number = ProfileType.integerOf(value) ?: return null
        return if (number in range) null else ProfileProblem.OutOfLimits(path, range, number, secret)
    }

    public companion object {
        /**
         * The profile of a runner plugin that declares none, one built against m3-04 (bl-104): it checks no key and
         * reports no key as unknown, so the plugin's own `validate` is the only check of its section.
         */
        @JvmField
        public val UNDECLARED: PluginProfile = PluginProfile(emptyList())

        /** A whole value `$NAME`, the rule of every `$VAR` in `WORKFLOW.md` (plan D3). */
        private val ENV_REF = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")
    }
}

/** One config key of a [PluginProfile]: its [name] in the section, its [type], and [required] or its [default], as documented. */
public data class ProfileKey(
    public val name: String,
    public val type: ProfileType,
    public val required: Boolean = false,
    /** The default as the plugin's doc writes it, null when the key has none or is [required]. */
    public val default: String? = null,
)

/** The type of a profile key, as written in `WORKFLOW.md`. */
public enum class ProfileType(
    /** The type in a message, such as `a string`. */
    public val description: String,
) {
    STRING("a string"),

    /** A whole number, written bare or quoted. */
    INTEGER("an integer"),
    LIST("a list"),
    MAP("a map"),

    /** `true` or `false` in any case, written bare or quoted (bl-72). */
    BOOLEAN("a boolean"),
    ;

    internal fun accepts(value: Any): Boolean =
        when (this) {
            STRING -> value is String
            INTEGER -> integerOf(value) != null
            LIST -> value is List<*>
            MAP -> value is Map<*, *>
            BOOLEAN -> booleanOf(value) != null
        }

    public companion object {
        /** [value] as a boolean: a boolean, or the string `true` or `false` in any case once trimmed; null otherwise. */
        public fun booleanOf(value: Any): Boolean? =
            when (value) {
                is Boolean -> value
                is String -> value.trim().lowercase().toBooleanStrictOrNull()
                else -> null
            }

        /** [value] as a whole number: an integral number, or a string of one once trimmed; null otherwise. */
        internal fun integerOf(value: Any): Long? =
            when (value) {
                is Long -> value
                is Int -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            }
    }
}

/** What [PluginProfile.check] found: the first [problem], null when none, and the [unknownKeys], each as `<section>.<key>`. */
public data class ProfileReport(
    public val problem: ProfileProblem?,
    public val unknownKeys: List<String>,
)

/** A section that does not follow its profile. [key] is `<section>.<key>`; [secret] is true for a key of the profile's secrets. */
public sealed class ProfileProblem(
    public val key: String,
    public val secret: Boolean,
    public val message: String,
) {
    /** A required key that is absent, null or blank. */
    public class Missing(
        key: String,
        secret: Boolean,
    ) : ProfileProblem(key, secret, "$key is required")

    /** A value that is not of the key's [type]. */
    public class WrongType(
        key: String,
        public val type: ProfileType,
        secret: Boolean,
    ) : ProfileProblem(key, secret, "$key must be ${type.description}")

    /** An integer outside its [range]. The message prints [value] unless the key is a [secret] (bl-104). */
    public class OutOfLimits(
        key: String,
        public val range: LongRange,
        public val value: Long,
        secret: Boolean = false,
    ) : ProfileProblem(key, secret, "$key must be between ${range.first} and ${range.last}" + if (secret) "" else ", got $value")

    /** A secret written `$NAME` whose variable [envName] is unset, or [empty] (blank) in the host env. */
    public class UnresolvedSecret(
        key: String,
        public val envName: String,
        public val empty: Boolean,
    ) : ProfileProblem(key, true, "$key uses \$$envName, which is ${if (empty) "empty" else "not set"}")
}
