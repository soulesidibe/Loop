package loop.tracker.files

import loop.api.TrackerAdapter
import loop.api.TrackerAdapterContract
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Runs the adapter neutral TrackerAdapter contract (spec 17.3) against the `files` tracker. */
class FilesTrackerContractTest : TrackerAdapterContract() {
    @TempDir
    lateinit var folder: Path

    override val activeState: String = "todo"

    override val otherState: String = "review"

    override fun trackerWith(seeds: List<Seed>): TrackerAdapter {
        val issues = folder.resolve("issues").createDirectories()
        for (seed in seeds) {
            issues.resolve("${seed.identifier}-slug.md").writeText(
                "---\nid: ${seed.identifier}\ntitle: \"${seed.title}\"\nstate: ${seed.state}\n" +
                    "priority: ${seed.priority ?: "null"}\nlabels: [${seed.labels.joinToString()}]\n---\n\n${seed.description}\n",
            )
        }
        return FilesTracker(issues, folder.resolve("locks"))
    }

    /** The issues folder does not exist, so listing it fails. */
    override fun unreachableTracker(): TrackerAdapter = FilesTracker(folder.resolve("missing"), folder.resolve("locks"))
}
