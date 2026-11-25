package loop.core.resources

import loop.api.ResourceDeclaration
import loop.core.workflow.WorkflowError

/**
 * Reads `resources:` of a `WORKFLOW.md` (m4-24, PLAN 6.9) from its front matter value as written (strings, lists, maps
 * or null): a list of entries, each with a `kind` and an `id`, unique in the workflow, and the optional `can`,
 * `cannot`, `restore` and `read_only_check`. Every failure is a [WorkflowError.InvalidConfig] naming the entry,
 * `resources[<index>]`, so the preflight names it. [check] tells which kinds a resource plugin serves: a kind none
 * serves is a plain lock, named once in [warnings], so a typo shows, as is an unknown key of an entry.
 */
internal class ResourcesParser(
    private val check: ResourceCheck,
    private val warnings: MutableList<String>,
) {
    /** The entries of the `resources:` value [resources], in the order written, none when it is absent. */
    fun parse(resources: Any?): List<ResourceDeclaration> {
        val list =
            when (resources) {
                null -> return emptyList()
                is List<*> -> resources
                else -> throw WorkflowError.InvalidConfig("$KEY must be a list")
            }
        val owners = mutableMapOf<String, String>()
        val declared =
            list.mapIndexed { index, item ->
                val name = "$KEY[$index]"
                val map = item.asMap() ?: throw WorkflowError.InvalidConfig("$name must be a map")
                val kind = map.required(name, "kind")
                val id = map.required(name, "id")
                owners[id]?.let { throw WorkflowError.InvalidConfig("$name.id \"$id\" is already the id of $it") }
                owners[id] = name
                warnings += map.keys.filter { it !in ENTRY_KEYS }.map { "$name.$it is not a key of a resource, Loop ignores it" }
                ResourceDeclaration(
                    kind = kind,
                    id = id,
                    can = map.strings(name, "can"),
                    cannot = map.strings(name, "cannot"),
                    restore = map.strings(name, "restore"),
                    readOnlyCheck = map.string(name, "read_only_check"),
                )
            }
        declared.map { it.kind }.distinct().forEach { kind ->
            val served =
                check
                    .serves(
                        kind,
                    ).getOrElse { throw WorkflowError.InvalidConfig(it.message ?: "cannot find the resource plugins") }
            if (!served) warnings += "$KEY kind \"$kind\" has no resource provider, Loop holds its resources as plain locks"
        }
        return declared
    }

    private companion object {
        const val KEY = "resources"
        val ENTRY_KEYS = setOf("kind", "id", "can", "cannot", "restore", "read_only_check")

        fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }

        fun Map<String, Any?>.string(
            name: String,
            key: String,
        ): String? =
            when (val value = this[key]) {
                null -> null
                is String -> value
                else -> throw WorkflowError.InvalidConfig("$name.$key must be a string")
            }

        fun Map<String, Any?>.required(
            name: String,
            key: String,
        ): String = string(name, key)?.trim()?.takeIf { it.isNotEmpty() } ?: throw WorkflowError.InvalidConfig("$name.$key is required")

        fun Map<String, Any?>.strings(
            name: String,
            key: String,
        ): List<String> =
            when (val value = this[key]) {
                null -> emptyList()
                is List<*> -> value.map { it as? String ?: throw WorkflowError.InvalidConfig("$name.$key must be a list of strings") }
                else -> throw WorkflowError.InvalidConfig("$name.$key must be a list of strings")
            }
    }
}
