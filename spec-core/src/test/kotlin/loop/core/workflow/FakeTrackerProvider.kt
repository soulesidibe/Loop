package loop.core.workflow

import loop.api.PluginProfile
import loop.api.TrackerAdapter
import loop.api.TrackerContext
import loop.api.TrackerProvider
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A tracker plugin for the loader tests (bl-23, m3-05). spec-core never sees a plugin, so it stands in for `github`;
 * it keeps the provider map it was handed in [seen] and answers with [answer].
 */
internal class FakeTrackerProvider(
    override val kind: String = "fake",
    override val profile: PluginProfile = PluginProfile(emptyList()),
    private val answer: (Map<String, Any?>, Map<String, String>) -> Any? = { _, _ -> Unit },
) : TrackerProvider {
    var seen: Map<String, Any?>? = null

    override val defaultActiveStates = listOf("open")
    override val defaultTerminalStates = listOf("closed")
    override val definedStates = listOf("open", "closed")

    override fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String>,
    ): Any? {
        seen = provider
        return answer(provider, env)
    }

    override fun create(
        settings: Any?,
        context: TrackerContext,
    ): TrackerAdapter = throw UnsupportedOperationException("never built here")
}

/** Writes a `WORKFLOW.md` in [folder] with the [tracker] lines under `tracker:` and a command runner. */
internal fun writeTrackerWorkflow(
    folder: Path,
    tracker: String,
): Path =
    folder.resolve("WORKFLOW.md").also {
        it.writeText(
            "---\ntracker:\n$tracker" +
                "runner:\n  kind: command\nrunners:\n  command:\n    command: ./agent.sh\n---\nWork on {{ issue.identifier }}\n",
        )
    }
