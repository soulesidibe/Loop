package loop.core.workflow

import loop.api.PluginProfile
import loop.api.ProfileProblem

/**
 * bl-70: the env to check [section], the plugin section at [key], with. It is [env] itself unless [deferSecrets]
 * is set and a secret of [profile] written `$NAME` has its variable unset or blank: each such secret is added to
 * [deferred], and the returned copy of [env] maps its variable to [DEFERRED_SECRET], a stand in that is never a
 * credential, so the rest of the section is still checked. Shared by the runner sections and every tracker (m4-11).
 */
internal fun secretCheckEnv(
    env: Map<String, String>,
    deferSecrets: Boolean,
    profile: PluginProfile,
    key: String,
    section: Map<String, Any?>,
    deferred: MutableList<DeferredSecret>,
): Map<String, String> {
    if (!deferSecrets) return env
    val missing =
        profile.secrets.sorted().mapNotNull { secret ->
            val name =
                (section[secret] as? String)
                    ?.trim()
                    ?.let { SECRET_REF.matchEntire(it) }
                    ?.groupValues
                    ?.get(1)
            val value = name?.let { env[it] }
            if (name == null || !value.isNullOrBlank()) null else DeferredSecret("$key.$secret", name, empty = value != null)
        }
    if (missing.isEmpty()) return env
    deferred += missing
    return env + missing.associate { it.name to DEFERRED_SECRET }
}

/**
 * m3-05: checks [section], the plugin section at [key] as written, against [profile], throwing the error [failure]
 * makes of the first problem. Returns one warning per key the profile does not list, naming [plugin].
 */
internal fun checkPluginProfile(
    profile: PluginProfile,
    key: String,
    section: Map<String, Any?>,
    plugin: String,
    checkEnv: Map<String, String>,
    failure: (ProfileProblem) -> WorkflowError,
): List<String> {
    val report = profile.check(key, section, checkEnv)
    report.problem?.let { throw failure(it) }
    return report.unknownKeys.map { "$it is not a key of $plugin, Loop ignores it" }
}

/** bl-70: what an unset secret reads as while a load with deferred secrets checks the rest of its section. */
private const val DEFERRED_SECRET = "loop-deferred-secret"

private val SECRET_REF = Regex("""^\$([A-Za-z_][A-Za-z0-9_]*)$""")
