package loop.core.workspace

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Plain directory workspaces under [root], one per workspace key. An existing
 * directory is reused as is. The workspace path must be strictly inside the
 * root once symlinks are resolved (spec 9.5). Hooks are run by [WorkspaceManager].
 */
class Workspaces(
    private val root: Path,
) {
    fun prepare(key: WorkspaceKey): Result<Workspace> =
        try {
            Files.createDirectories(root.toAbsolutePath().normalize())
            val realRoot = root.toRealPath()
            val path = realRoot.resolve(key.value).normalize()
            checkInside(realRoot, path, key)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                checkInside(realRoot, path.toRealPath(), key)
                if (!Files.isDirectory(path)) {
                    throw WorkspaceError.Io("workspace path $path exists and is not a directory")
                }
                Result.success(Workspace(key, path, createdNow = false))
            } else {
                Files.createDirectory(path)
                Result.success(Workspace(key, path, createdNow = true))
            }
        } catch (e: WorkspaceError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(WorkspaceError.Io("cannot prepare workspace ${key.value}: ${e.message}", e))
        }

    /**
     * The existing workspace directory of [key], null when nothing is there. The same
     * containment rules as [prepare]; a non-directory entry, or a symlink, fails with
     * [WorkspaceError.Io], so removal never touches what Loop did not create.
     */
    fun locate(key: WorkspaceKey): Result<Path?> =
        try {
            if (!Files.isDirectory(root)) {
                Result.success(null)
            } else {
                val realRoot = root.toRealPath()
                val path = realRoot.resolve(key.value).normalize()
                checkInside(realRoot, path, key)
                when {
                    !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> Result.success(null)
                    else -> {
                        checkInside(realRoot, path.toRealPath(), key)
                        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            throw WorkspaceError.Io("workspace path $path is not a directory")
                        }
                        Result.success(path)
                    }
                }
            }
        } catch (e: WorkspaceError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(WorkspaceError.Io("cannot locate workspace ${key.value}: ${e.message}", e))
        }

    /**
     * Deletes the workspace directory of [key] and everything in it, if it is still
     * there (plan R13). Symlinks inside are deleted as entries, never followed; the
     * real path is checked to be inside the root right before deleting.
     */
    fun delete(key: WorkspaceKey): Result<Unit> {
        val path = locate(key).getOrElse { return Result.failure(it) } ?: return Result.success(Unit)
        return try {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(WorkspaceError.Io("cannot remove workspace ${key.value}: ${e.message}", e))
        }
    }

    private fun checkInside(
        realRoot: Path,
        path: Path,
        key: WorkspaceKey,
    ) {
        if (path == realRoot || !path.startsWith(realRoot)) {
            throw WorkspaceError.OutsideRoot("workspace ${key.value} is not inside the root $realRoot")
        }
    }
}

data class Workspace(
    val key: WorkspaceKey,
    val path: Path,
    val createdNow: Boolean,
)

sealed class WorkspaceError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The workspace path would not be strictly inside the workspace root. */
    class OutsideRoot(
        message: String,
    ) : WorkspaceError(message)

    /** The workspace path exists but is not a directory, or creating it failed. */
    class Io(
        message: String,
        cause: Throwable? = null,
    ) : WorkspaceError(message, cause)

    /** A fatal hook (`after_create` while preparing) failed or timed out; [result] has its output. */
    class Hook(
        message: String,
        val result: HookResult,
    ) : WorkspaceError(message)
}
