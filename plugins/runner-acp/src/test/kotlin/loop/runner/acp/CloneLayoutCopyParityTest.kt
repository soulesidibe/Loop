package loop.runner.acp

import loop.api.CloneLayoutContract
import java.nio.file.Path

/** bl-254: the ACP runner's [CloneLayoutCopy] over the shared layouts, which spec-core's `CloneLayout` runs too. */
class CloneLayoutCopyParityTest : CloneLayoutContract() {
    override fun refusal(
        workspace: Path,
        refStorage: (Path) -> List<String>,
    ): String? = CloneLayoutCopy.refusal(workspace, refStorage)

    override fun pins(realWorkspace: Path): Map<String, String> = ProtectedRootsCheck.clonePins(realWorkspace)
}
