package loop.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import loop.api.ProjectId
import loop.api.ResourceDeclaration
import loop.core.orchestrator.ToolConfigWriter
import loop.core.trackers.IssueIdDisplay
import loop.core.trackers.TrackerEntry
import loop.core.workflow.ConfigValue
import loop.core.workflow.ContextConfig
import loop.core.workflow.WorkflowFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The tool config snapshot format version this build writes and reads. */
const val TOOL_CONFIG_VERSION = 1

/** The snapshot key of `context.tool_output_tokens` (m3-14), which `loop tool mcp` trims its replies to. */
private const val TOOL_OUTPUT_TOKENS = "tool_output_tokens"

/** The snapshot keys of the primary tracker's id and of every tracker id (m4-47), which `loop tool` reads its issue by. */
private const val TRACKER_ID = "tracker_id"
private const val TRACKER_IDS = "tracker_ids"

/** The snapshot key of the resources of `resources:` (m4-25), which `loop tool mcp` grants: kind, id, can and cannot. */
private const val RESOURCES = "resources"

/** What `loop tool` reads from the snapshot: no tracker settings beyond the kind, no credential. */
data class ToolConfig(
    val projectId: ProjectId,
    /** The folder of `WORKFLOW.md`, to open the store. */
    val workflowDir: Path,
    val trackerKind: String,
    val definedStates: List<String>,
    /** `context.tool_output_tokens` of the attempt's workflow (m3-14); the default when the snapshot has none. */
    val toolOutputTokens: Int = ContextConfig().toolOutputTokens,
    /** The primary tracker's id (m4-47), null in a snapshot written before m4-47. */
    val trackerId: String? = null,
    /** Every tracker id of the project, the primary first (m4-47), null in a snapshot written before m4-47. */
    val trackerIds: List<String>? = null,
    /**
     * The resources of `resources:` (m4-25), each with its kind, id, can and cannot, never its restore or read only
     * commands, which only `loop run` runs; none in a snapshot written before m4-25.
     */
    val resources: List<ResourceDeclaration> = emptyList(),
) {
    /** How a tool reads its issue (m4-47), null for a snapshot written before m4-47, which names no tracker. */
    val ids: IssueIdDisplay?
        get() {
            val primary = trackerId ?: return null
            val all = trackerIds ?: return null
            return IssueIdDisplay(primary, all, namespaced = all.size > 1)
        }
}

/** Why a snapshot could not be used. */
sealed class ToolConfigError(
    message: String,
) : Exception(message) {
    /** The file is missing, unreadable or not a valid snapshot. */
    class Unreadable(
        sentence: String,
    ) : ToolConfigError(sentence)

    /** A snapshot of another format [version]. */
    class UnknownVersion(
        val version: String,
    ) : ToolConfigError("version $version")
}

/** Reads the snapshot at [path]; a failure is a [ToolConfigError]. */
fun readToolConfig(path: Path): Result<ToolConfig> =
    try {
        val json = Json.parseToJsonElement(path.readText()).jsonObject
        val version = json["version"]?.jsonPrimitive?.content ?: throw ToolConfigError.Unreadable("it has no version")
        if (version != TOOL_CONFIG_VERSION.toString()) throw ToolConfigError.UnknownVersion(version)
        Result.success(
            ToolConfig(
                projectId = ProjectId(json.string("project_id")),
                workflowDir = Path(json.string("workflow_dir")),
                trackerKind = json.string("tracker_kind"),
                definedStates =
                    (json["defined_states"] as? JsonArray ?: throw ToolConfigError.Unreadable("it has no defined_states"))
                        .map { it.jsonPrimitive.content },
                // m3-14: optional, so a snapshot of an older Loop of the same version still reads.
                toolOutputTokens =
                    json[TOOL_OUTPUT_TOKENS]?.let { value ->
                        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                            ?: throw ToolConfigError.Unreadable("its $TOOL_OUTPUT_TOKENS is not a positive integer")
                    } ?: ContextConfig().toolOutputTokens,
                // m4-47: optional too, so a snapshot of a Loop before m4-47 still reads.
                trackerId = (json[TRACKER_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                trackerIds = (json[TRACKER_IDS] as? JsonArray)?.map { it.jsonPrimitive.content },
                // m4-25: optional too, so a snapshot of a Loop before m4-25 still reads, with no resource.
                resources = json[RESOURCES]?.let(::resources).orEmpty(),
            ),
        )
    } catch (e: ToolConfigError) {
        Result.failure(e)
    } catch (e: NoSuchFileException) {
        Result.failure(ToolConfigError.Unreadable("the file does not exist"))
    } catch (e: IOException) {
        Result.failure(ToolConfigError.Unreadable(e.sentence()))
    } catch (e: SerializationException) {
        Result.failure(ToolConfigError.Unreadable("it is not valid JSON"))
    } catch (e: IllegalArgumentException) {
        Result.failure(ToolConfigError.Unreadable("it is not a valid attempt config"))
    }

private fun resources(value: JsonElement): List<ResourceDeclaration> {
    val entries = value as? JsonArray ?: throw ToolConfigError.Unreadable("its $RESOURCES is not a list")
    return entries.map { entry ->
        val resource = entry as? JsonObject ?: throw ToolConfigError.Unreadable("its $RESOURCES holds an entry that is not a map")
        ResourceDeclaration(
            kind = resource.string("kind"),
            id = resource.string("id"),
            can = resource.strings("can"),
            cannot = resource.strings("cannot"),
        )
    }
}

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ToolConfigError.Unreadable("its $key is not a list of strings") }
        .orEmpty()

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolConfigError.Unreadable("it has no $key")

/**
 * Writes `tool-config.json` (m1-23): `version`, `project_id`, `workflow_dir`, `tracker_kind`,
 * `defined_states`, `tool_output_tokens` (m3-14), `tracker_id` and `tracker_ids` (m4-47), `resources` (m4-25), the
 * kind, id, can and cannot of each entry of `resources:`, and `provider`, the non
 * credential `provider` fields with `dir` resolved to an absolute path. `tracker_kind`, `defined_states` and `provider`
 * are those of the issue's own tracker (m4-14); `tracker_id` stays the primary's, which bare ids are read by. A value
 * that is exactly `$NAME`, at any depth inside `provider` and other than the `dir` path key, is a credential and is
 * omitted, never replaced with a placeholder. A literal value is copied as written. Written to a temp file, then moved
 * in place.
 */
object ToolConfigSnapshotWriter : ToolConfigWriter {
    override fun write(
        target: Path,
        projectId: ProjectId,
        workflow: WorkflowFile,
        tracker: TrackerEntry,
        definedStates: List<String>,
    ) {
        val provider =
            tracker.provider
                .mapNotNull { (key, value) ->
                    if (key == DIR_KEY) key to JsonPrimitive(tracker.dir.toString()) else value.toJson()?.let { key to it }
                }.toMap()
        val snapshot =
            JsonObject(
                mapOf(
                    "version" to JsonPrimitive(TOOL_CONFIG_VERSION),
                    "project_id" to JsonPrimitive(projectId.value),
                    "workflow_dir" to JsonPrimitive(workflow.path.parent.toString()),
                    "tracker_kind" to JsonPrimitive(tracker.kind),
                    "defined_states" to JsonArray(definedStates.map(::JsonPrimitive)),
                    TOOL_OUTPUT_TOKENS to JsonPrimitive(workflow.config.context.toolOutputTokens),
                    TRACKER_ID to JsonPrimitive(workflow.trackerId),
                    TRACKER_IDS to JsonArray(workflow.trackers.ids.map(::JsonPrimitive)),
                    RESOURCES to JsonArray(workflow.config.resources.map(::resourceJson)),
                    "provider" to JsonObject(provider),
                ),
            )
        val temp = target.resolveSibling("${target.fileName}.tmp")
        temp.writeText(Json.encodeToString(JsonElement.serializer(), snapshot))
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun resourceJson(resource: ResourceDeclaration): JsonObject =
        JsonObject(
            mapOf(
                "kind" to JsonPrimitive(resource.kind),
                "id" to JsonPrimitive(resource.id),
                "can" to JsonArray(resource.can.map(::JsonPrimitive)),
                "cannot" to JsonArray(resource.cannot.map(::JsonPrimitive)),
            ),
        )

    /** The value as JSON, null when it is a credential reference (exactly `$NAME`). */
    private fun ConfigValue.toJson(): JsonElement? =
        when (this) {
            is ConfigValue.Str -> if (CREDENTIAL_REF.matches(value)) null else JsonPrimitive(value)
            is ConfigValue.ListValue -> JsonArray(items.mapNotNull { it.toJson() })
            is ConfigValue.MapValue -> JsonObject(entries.mapNotNull { (k, v) -> v.toJson()?.let { k to it } }.toMap())
            ConfigValue.Null -> JsonNull
        }

    private const val DIR_KEY = "dir"
    private val CREDENTIAL_REF = Regex("""\$[A-Za-z_][A-Za-z0-9_]*""")
}
