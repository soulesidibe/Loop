package loop.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [target] through [write] into a temporary file of its own folder, then moves it over [target] atomically,
 * so a concurrent reader sees the old text or the new one, never a truncated or partial file.
 */
fun replaceAtomically(
    target: Path,
    write: (Path) -> Unit,
) {
    val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        write(temp)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}
