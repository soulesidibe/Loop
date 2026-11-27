package loop.core.verify

import loop.api.CloneLayoutContract
import java.nio.file.Path

/** bl-254: spec-core's [CloneLayout] over the shared layouts, which the ACP runner's copy runs too. */
class CloneLayoutParityTest : CloneLayoutContract() {
    override fun refusal(
        workspace: Path,
        refStorage: (Path) -> List<String>,
    ): String? = CloneLayout.refusal(workspace, refStorage)

    override fun pins(realWorkspace: Path): Map<String, String> = CloneLayout.pins(realWorkspace)
}
