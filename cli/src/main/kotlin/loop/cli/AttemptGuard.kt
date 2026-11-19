package loop.cli

/**
 * The variable every attempt env sets, the agent's and its checks', and an operator shell never does (m2-24).
 * The operator commands that act for a human, approve and resume, refuse to run when it is present.
 */
internal const val ATTEMPT_ENV = "LOOP_ATTEMPT_ID"

/**
 * Whether [env] is the env of a Loop attempt: [ATTEMPT_ENV] is present, even empty. A guard behind the deny
 * rules of the generated Claude Code settings, which match literal command prefixes only.
 */
internal fun insideAttempt(env: Map<String, String>): Boolean = ATTEMPT_ENV in env
