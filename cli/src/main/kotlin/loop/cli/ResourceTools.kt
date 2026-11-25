package loop.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import loop.api.RecordedEvent
import loop.api.ResourceDeclaration
import loop.api.ResourceLock
import loop.core.resources.ReleaseAnswer
import loop.core.resources.ResourceAnswer
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
 * `can` and `cannot`), `preparing` (granted, `loop run` has not prepared it yet), `queued` (with `position`) or
 * `released`. A structured failure is a tool error whose `status` is `error`, with an `error` code and a `message`,
 * and the turn goes on: `unknown_resource`, `already_held`, `releasing`, `prepare_failed`, `not_held`, or `store` when
 * the store refused the change.
 */
internal class ResourceTools(
    private val tool: AttemptTool,
    private val env: AttemptTool.Env,
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
                    "Queued at position ${answer.position}: another attempt holds ${resource.id}. " +
                        "Call request_resource with the same name again later to check; your place is kept.",
                    "position" to JsonPrimitive(answer.position),
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
    ): ToolReply = ToolReply(json(STATUS_ERROR, resource, message, "error" to JsonPrimitive(code)), isError = true)

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
