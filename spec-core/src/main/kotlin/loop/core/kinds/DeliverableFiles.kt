package loop.core.kinds

import loop.core.verify.ProtectedPaths
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** One file of a deliverable (m4-21): its [path] relative to the workspace, `/` separated, its [size] and [sha256]. */
data class DeliverableFile(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** What verification gets for a kind with `deliverable.files` (m4-21), see [DeliverableFiles.listing]. */
sealed interface DeliverableListing {
    /** The globs of `deliverable.files`, as written. */
    val globs: List<String>

    /**
     * The [files] that match, in path order. For a kind with `deliverable.report` (m4-23), [report] is its path, [files]
     * holds the report when it is there, and [problems] what reading it found: an empty report, one that is not UTF-8
     * text or too large, or each required section it misses, one sentence each, naming the report.
     */
    data class Listed(
        override val globs: List<String>,
        val files: List<DeliverableFile>,
        val report: String? = null,
        val problems: List<String> = emptyList(),
    ) : DeliverableListing {
        /** True when no file matches: the result is refuted with the finding "no deliverable file", or a missing report. */
        val missing: Boolean get() = files.isEmpty()

        /** True when the deliverable refutes the result: [missing], or a report with [problems] (m4-23). */
        val refuted: Boolean get() = missing || problems.isNotEmpty()
    }

    /** The workspace could not be listed, for the [reason] given. */
    data class Failed(
        override val globs: List<String>,
        val reason: String,
    ) : DeliverableListing
}

/**
 * The files deliverable of a kind (m4-21): behavior follows `deliverable.files`, whatever the kind is called. The globs
 * are anchored at the workspace root like those of `guards.protect`: `*` and `?` stay in one folder, `**` crosses any
 * number of them, and a glob that names a folder matches nothing under it.
 */
object DeliverableFiles {
    /** The [paths] that match at least one of the [globs], once each, in path order. A pure function. */
    fun matching(
        paths: Iterable<String>,
        globs: List<String>,
    ): List<String> {
        val matcher = ProtectedPaths(globs, coversFolders = false)
        return paths.filter { matcher.match(it) != null }.distinct().sorted()
    }

    /**
     * The files of [workspace] that match [globs], in path order, each with its size and sha256 digest. Only regular
     * files are listed, never through a symlink, and never a `.git` entry, git's own state. Each glob is walked from its
     * literal folder, so `out/` followed by a glob reads only `out`; a folder that is not there lists nothing. Throws
     * when [workspace] itself cannot be read.
     */
    fun list(
        workspace: Path,
        globs: List<String>,
    ): List<DeliverableFile> {
        if (!Files.isDirectory(workspace)) throw NoSuchFileException(workspace.toString(), null, "the workspace is not a folder")
        val matcher = ProtectedPaths(globs, coversFolders = false)
        val found = sortedMapOf<String, DeliverableFile>()
        for (start in globs.mapNotNull { startOf(workspace, it) }.distinct()) {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult = if (dir.fileName?.toString() == GIT) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!attrs.isRegularFile || file.fileName.toString() == GIT) return FileVisitResult.CONTINUE
                        val path = workspace.relativize(file).joinToString("/")
                        if (path !in found && matcher.match(path) != null) found[path] = read(file, path)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return found.values.toList()
    }

    /**
     * The report of [workspace] at [report] (m4-23), listed like a deliverable file and read once, so its size, digest and
     * text come from the same bytes, never through a symlink: missing, it lists no file; present, it is checked for
     * being UTF-8 text under [REPORT_MAX_BYTES], not blank, and holding each of the [sections] (`verify.require_sections`).
     * Throws when [workspace] or the report cannot be read.
     */
    fun report(
        workspace: Path,
        report: String,
        sections: List<String>,
    ): DeliverableListing.Listed {
        val globs = listOf(report)
        val file = list(workspace, globs).singleOrNull() ?: return DeliverableListing.Listed(globs, emptyList(), report)
        val bytes = Files.newInputStream(workspace.resolve(file.path), LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(REPORT_MAX_BYTES + 1) }
        val read = DeliverableFile(file.path, bytes.size.toLong(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        val listed = { problem: String -> DeliverableListing.Listed(globs, listOf(read), report, listOf("$report $problem")) }
        if (bytes.size > REPORT_MAX_BYTES) return listed("is over $REPORT_MAX_BYTES bytes, too large to read")
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return listed("is not UTF-8 text")
            }
        if (text.isBlank()) return listed("is empty")
        val problems = RequiredSections.missing(text, sections).map { "$report misses the section $it" }
        return DeliverableListing.Listed(globs, listOf(read), report, problems)
    }

    /**
     * The listing verification records with the verdict of an issue of [kind] worked in [workspace], null when the kind
     * declares no `deliverable.files` and no `deliverable.report`; for a report, the [report] read. A workspace or a
     * report that cannot be read is [DeliverableListing.Failed], never a throw.
     */
    fun listing(
        kind: IssueKind?,
        workspace: Path,
    ): DeliverableListing? {
        val deliverable = kind?.config?.deliverable ?: return null
        val globs = deliverable.globs
        if (globs.isEmpty()) return null
        return try {
            deliverable.report?.let { report(workspace, it, kind.config.requireSections) }
                ?: DeliverableListing.Listed(globs, list(workspace, globs))
        } catch (e: IOException) {
            DeliverableListing.Failed(globs, "cannot list the deliverable files: ${e.describe()}")
        } catch (e: SecurityException) {
            DeliverableListing.Failed(globs, "cannot list the deliverable files: ${e.describe()}")
        }
    }

    /**
     * The folder of [workspace] that holds every match of [glob]: its segments before the first one with a wildcard,
     * the last one aside. Null when that folder is not there, is reached through a symlink or is not a folder.
     */
    private fun startOf(
        workspace: Path,
        glob: String,
    ): Path? {
        val segments =
            glob
                .trim()
                .removePrefix("/")
                .split('/')
                .dropLast(1)
        var folder = workspace
        for (segment in segments.takeWhile { '*' !in it && '?' !in it }) {
            folder = folder.resolve(segment)
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return null
        }
        return folder
    }

    /** The size and sha256 digest of [file], read once, never through a symlink. */
    private fun read(
        file: Path,
        path: String,
    ): DeliverableFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return DeliverableFile(path, size, HexFormat.of().formatHex(digest.digest()))
    }

    private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "unknown error"

    /** The largest report verification reads (m4-23), 8 MiB; a larger one refutes the result. */
    const val REPORT_MAX_BYTES: Int = 8 * 1024 * 1024

    private const val GIT = ".git"
    private const val BUFFER = 64 * 1024
}
