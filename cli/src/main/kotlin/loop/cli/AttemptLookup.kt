package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.store.SqliteEventStore
import java.nio.file.Path

/** The exit code of an attempt that no or several attempt ids match, in `loop show` and `loop replay`. */
internal const val EXIT_ATTEMPT: Int = 2

/** The exit code of a store or Attempt file that cannot be read, in `loop show` and `loop replay`. */
internal const val EXIT_STORE: Int = 3

/**
 * The one attempt id of [projectId] that is [typed] or starts with it (m1-13), else [fail] with [EXIT_ATTEMPT] when
 * none or several match, or [EXIT_STORE] when the store at [storeFile] cannot be read. A full id wins over longer ids
 * it happens to prefix.
 */
internal fun resolveAttempt(
    store: SqliteEventStore,
    projectId: ProjectId,
    typed: String,
    storeFile: Path,
    fail: (String, Int) -> Nothing,
): AttemptId {
    val matches =
        runBlocking { store.attemptIdsStartingWith(projectId, typed) }
            .getOrElse { fail("cannot read the store at $storeFile: ${it.sentence()}", EXIT_STORE) }
    matches.firstOrNull { it.value == typed }?.let { return it }
    return when (matches.size) {
        0 -> fail("no attempt \"$typed\" in project ${projectId.value}", EXIT_ATTEMPT)
        1 -> matches.single()
        else -> fail("\"$typed\" matches ${matches.size} attempts in project ${projectId.value}, give more of the id", EXIT_ATTEMPT)
    }
}
