package loop.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.RecordedEvent
import loop.api.ResourceDeclaration
import loop.api.ResourceLock
import loop.core.events.ResourceQueued
import loop.core.resources.ReadOnlyChecks
import loop.core.resources.ReleaseAnswer
import loop.core.resources.ResourceAnswer
import loop.core.resources.ResourceCommands
import loop.core.resources.ResourceLocks
import loop.mcp.ToolReply
import kotlin.time.Clock

/**
 * `request_resource` and `release_resource` of `loop tool mcp` (m4-25, PLAN 6.9 and 6.10), the first tools answered
 * inside the turn: this process takes, queues or marks the lock in the machine's store itself, through [ResourceLocks],
 * and answers at once, without the request queue that `submit_result`, `move` and `comment` use. It reads the resources
 * from the attempt's tool config snapshot, never `WORKFLOW.md`, and never runs a provider: `loop run` prepares a lock
 * on its next tick and writes the result on the lock, which the next call reads, and gives back a released one.
 *
 * Every answer is one JSON object. `status` is `granted` (with `prepared`, what the provider's prepare returned, and
 * `can` and `cannot`), `preparing` (granted, `loop run` has not prepared it yet), `queued` (with `position`, and `reason` since m4-26) or
 * `released`. A structured failure is a tool error whose `status` is `error`, with an `error` code and a `message`,
 * and the turn goes on: `unknown_resource`, `already_held`, `releasing`, `prepare_failed`, `not_held`,
 * `read_only_check_failed` (m4-26), or `store` when
 * the store refused the change.
 *
 * Since m4-26 `release_resource` first runs the resource's `read_only_check` here, through [commands], while the
 * resource is still up, in the folder of `WORKFLOW.md` with the agent's env and what its prepare returned as
 * `LOOP_RESOURCE_<KEY>` env: a failure is the tool error `read_only_check_failed`, with the `difference` and the
 * `restore` entries the agent must run, and the attempt keeps the lock. A pass marks the lock released, and `loop run` runs the provider's `release`, then the provider's own check.
 */
internal class ResourceTools(
    private val tool: AttemptTool,
    private val env: AttemptTool.Env,
    private val commands: ResourceCommands =
        // The folder of WORKFLOW.md, `workflow_dir` of the snapshot, where `loop run` runs the same check.
        ShellResourceCommands(cwd = { env.config.workflowDir }, base = { tool.rawEnv }),
) {
    private val resources: List<ResourceDeclaration> get() = env.config.resources

    /** One `request_resource` call for the resource named [name]. */
    suspend fun request(name: String): ToolReply {
        val resource = declared(name) ?: return unknown(name)
        return withLocks { locks, started ->
            val issueId = started.issueId ?: env.issueId
            val identifier = started.issueIdentifier ?: env.issueName
            val answer =
                locks
                    .ask(env.projectId, issueId, identifier, env.attemptId, resource.kind, resource.id) { project ->
                        // bl-204: a dead holder of another project is freed before this request answers queued.
                        projectRunLive(tool.loopHome, project)
                    }.getOrElse { return@withLocks storeFailure(resource, it) }
            answer(resource, answer)
        }
    }

    /** One `release_resource` call for the resource named [name]. */
    suspend fun release(name: String): ToolReply {
        val resource = declared(name) ?: return unknown(name)
        return withLocks { locks, _ ->
            checkBeforeRelease(locks, resource)?.let { return@withLocks it }
            val answer =
                locks
                    .requestRelease(env.attemptId, resource.kind, resource.id)
                    .getOrElse { return@withLocks storeFailure(resource, it) }
            when (answer) {
                is ReleaseAnswer.Marked ->
                    reply(
                        STATUS_RELEASED,
                        resource,
                        "Released ${resource.id}. Stop using it now: Loop gives it back and grants it to the next attempt in its queue.",
                    )
                is ReleaseAnswer.NotHeld -> {
                    val waits =
                        answer.position?.let {
                            " Your request waits at position $it of its queue; Loop drops it when your attempt ends."
                        }
                    failure(
                        "not_held",
                        resource,
                        "You do not hold ${resource.id}, or you released it already, so there is nothing to release.${waits.orEmpty()}",
                    )
                }
            }
        }
    }

    /**
     * The read only check of [resource] before its release (m4-26), when the attempt holds it prepared, so it is up:
     * the failure to answer, which keeps the lock, or null to release it. A lock not held or not prepared yet is left
     * to [ResourceLocks.requestRelease], which answers it.
     */
    private suspend fun checkBeforeRelease(
        locks: ResourceLocks,
        resource: ResourceDeclaration,
    ): ToolReply? {
        val rows = locks.rows().getOrElse { return storeFailure(resource, it) }
        val held =
            rows.locks.firstOrNull {
                it.attemptId == env.attemptId && it.kind == resource.kind && it.resourceId == resource.id && it.releaseRequestedAt == null
            }
        val prepared = held?.prepared ?: return null
        val difference = ReadOnlyChecks.run(resource, prepared, commands) ?: return null
        val restore =
            resource.restore
                .ifEmpty { null }
                ?.joinToString(prefix = " Run the restore entries, in order: ", separator = "; ", postfix = ".")
                .orEmpty()
        return failure(
            "read_only_check_failed",
            resource,
            "The read only check of ${resource.id} failed, so you keep it: $difference.$restore " +
                "Then call release_resource again; at the end of your attempt Loop runs the restore entries once itself.",
            "difference" to JsonPrimitive(difference),
            "restore" to JsonArray(resource.restore.map(::JsonPrimitive)),
        )
    }

    /** Opens the store, checks the attempt is live, and runs [block] with the lock calls of the attempt's run. */
    private suspend fun withLocks(block: suspend (ResourceLocks, RecordedEvent) -> ToolReply): ToolReply =
        tool.openStore(env).use { store ->
            val started = tool.liveAttempt(store, env)
            val locks =
                ResourceLocks(store, store, Clock.System, started.runId) { event, error ->
                    tool.warn("${event.kind} of ${event.attemptId?.value} was not recorded: ${error.sentence()}.")
                }
            block(locks, started)
        }

    private fun answer(
        resource: ResourceDeclaration,
        answer: ResourceAnswer,
    ): ToolReply =
        when (answer) {
            is ResourceAnswer.Granted ->
                reply(
                    STATUS_GRANTED,
                    resource,
                    "Granted ${resource.id}, ready: it is yours until you call release_resource or your attempt ends.",
                    "prepared" to
                        JsonObject(
                            answer.lock.prepared
                                .orEmpty()
                                .mapValues { JsonPrimitive(it.value) },
                        ),
                    "can" to JsonArray(resource.can.map(::JsonPrimitive)),
                    "cannot" to JsonArray(resource.cannot.map(::JsonPrimitive)),
                )
            is ResourceAnswer.Preparing ->
                reply(
                    STATUS_PREPARING,
                    resource,
                    "Granted, preparing: ${resource.id} is yours and Loop is preparing it. " +
                        "Call request_resource with the same name again in a few seconds to get what you need to use it.",
                )
            is ResourceAnswer.Queued ->
                reply(
                    STATUS_QUEUED,
                    resource,
                    "Queued at position ${answer.position}: ${why(answer.reason, resource)}. " +
                        "Call request_resource with the same name again later to check; your place is kept.",
                    "position" to JsonPrimitive(answer.position),
                    "reason" to JsonPrimitive(answer.reason),
                )
            is ResourceAnswer.AlreadyHeld ->
                failure(
                    "already_held",
                    resource,
                    "You already hold ${resource.id}, granted with ${describe(answer.lock)}. " +
                        "Use it, and call release_resource when you are done.",
                )
            is ResourceAnswer.Releasing ->
                failure(
                    "releasing",
                    resource,
                    "You released ${resource.id} and Loop is giving it back. Request it again once that is done.",
                )
            is ResourceAnswer.PrepareFailed ->
                failure(
                    "prepare_failed",
                    resource,
                    "Loop could not prepare ${resource.id}: ${answer.lock.prepareError}. It is released; request it again to retry.",
                )
        }

    /** Why a request waits (m4-26), by the `reason` of `resource_queued`. */
    private fun why(
        reason: String,
        resource: ResourceDeclaration,
    ): String =
        when (reason) {
            ResourceQueued.LIMIT ->
                "the machine already holds as many resources of this kind as its limit allows. " +
                    "If you hold one of them and do not need both at once, release it with release_resource, " +
                    "or two attempts may each wait for the other"
            ResourceQueued.DIRTY -> "the read only check of ${resource.id} failed, Loop lends it again once a check passes"
            else -> "another attempt holds ${resource.id}"
        }

    private fun describe(lock: ResourceLock): String =
        lock.prepared
            .orEmpty()
            .entries
            .joinToString(", ") { "${it.key} ${it.value}" }
            .ifEmpty { "nothing to use it" }

    private fun declared(name: String): ResourceDeclaration? = resources.firstOrNull { it.id == name.trim() }

    private fun unknown(name: String): ToolReply =
        failure(
            "unknown_resource",
            null,
            "\"${tool.redact(name)}\" is not a resource of the workflow. The resources are: ${resources.joinToString(", ") { it.id }}.",
        )

    private fun storeFailure(
        resource: ResourceDeclaration,
        error: Throwable,
    ): ToolReply = failure("store", resource, "The store refused the change: ${error.sentence()}. Nothing changed; call again.")

    private fun reply(
        status: String,
        resource: ResourceDeclaration,
        message: String,
        vararg fields: Pair<String, JsonElement>,
    ): ToolReply = ToolReply(json(status, resource, message, *fields))

    private fun failure(
        code: String,
        resource: ResourceDeclaration?,
        message: String,
        vararg fields: Pair<String, JsonElement>,
    ): ToolReply = ToolReply(json(STATUS_ERROR, resource, message, "error" to JsonPrimitive(code), *fields), isError = true)

    private fun json(
        status: String,
        resource: ResourceDeclaration?,
        message: String,
        vararg fields: Pair<String, JsonElement>,
    ): String {
        val ids = resource?.let { listOf("resource" to JsonPrimitive(it.id), "kind" to JsonPrimitive(it.kind)) }.orEmpty()
        val all = listOf("status" to JsonPrimitive(status)) + ids + fields + ("message" to JsonPrimitive(message))
        return tool.redact(JsonObject(all.toMap()).toString())
    }

    companion object {
        const val STATUS_GRANTED = "granted"
        const val STATUS_PREPARING = "preparing"
        const val STATUS_QUEUED = "queued"
        const val STATUS_RELEASED = "released"
        const val STATUS_ERROR = "error"
    }
}
