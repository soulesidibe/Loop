package loop.tracker.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import loop.api.DeliveryError
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Pushes a verified commit of the project repository [projectDir] to a branch of the GitHub repository at [url]
 * (m2-25), with git, never forced. [url] is explicit, `https://<host>/<owner>/<repo>.git` ([pushUrl]); no remote name
 * is read.
 *
 * The project's `.git/config` is shared with every worktree, so an agent can write it. The push therefore never runs
 * in the project repository: it runs in a temporary bare repository Loop creates for it, which borrows the project's
 * objects through `objects/info/alternates`, and with neither the user's nor the system's config. Only Loop's own
 * settings on the command line apply ([pins]): the scheme of [url] is the only protocol allowed, no askpass, no ssh
 * command, no proxy, TLS checked, no tags, no hooks. Before pushing, git's resolved push URL must be [url] exactly, so
 * no `url.*.insteadOf` can redirect it.
 *
 * Every git process gets a minimal environment ([minimalEnv]: `PATH`, `HOME` and the locale) plus
 * `GIT_TERMINAL_PROMPT=0`, so git never waits for a password; each one is stopped after [timeout]. The push alone,
 * and the helpers git starts for it, also get [token] in [TOKEN_VARIABLE], read by a credential helper that answers
 * only for the host of [url], over its scheme. The token is never in an argument, a URL or a git config file.
 *
 * [fetch] brings the tip of a base branch from [url] into the project repository the same way (m2-26), then lands its
 * objects by file copy and writes `refs/loop/base/<base>` without the token.
 */
class GitPush(
    private val projectDir: Path,
    private val url: URI,
    private val token: String,
    private val env: Map<String, String> = System.getenv(),
    private val git: String = "git",
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
) {
    init {
        require(isPushUrl(url)) { "url must be https://<host>/<owner>/<repo>.git, http only for a loopback host, got \"$url\"" }
    }

    /** The host the credential helper answers for, with the port when [url] has one. */
    private val host: String = authority(url)

    /**
     * Pushes [sha] to `refs/heads/[branch]` of [url]. The local branch must be at [sha]: missing or elsewhere, it is
     * [DeliveryError.Rejected] and nothing is pushed. A commit the remote branch already holds succeeds. A push the
     * remote refuses (not a fast forward, a protected branch), an authentication failure and a resolved push URL that
     * is not [url] are [DeliveryError.Rejected]; any other git failure, a timeout and a git that cannot start are
     * [DeliveryError.Unavailable]. Messages quote git's first lines, never the token.
     */
    suspend fun push(
        sha: String,
        branch: String,
    ): Result<Unit> =
        try {
            verify(sha, branch)
            val objects = objects()
            temporary("push") { repository ->
                prepare(repository, objects, sha256 = sha.length == SHA256_LENGTH, what = "push")
                checkUrl(repository, push = true)
                send(repository, sha, branch)
            }
            Result.success(Unit)
        } catch (e: DeliveryError) {
            Result.failure(e)
        }

    /**
     * Fetches `refs/heads/[base]` of [url] into the project repository (m2-26) and writes `refs/loop/base/[base]` there
     * at the fetched commit, whose id it returns: the id git read in Loop's temporary repository, the proof, never a
     * ref read back from the project.
     *
     * The fetch runs like the push, in a temporary bare repository that borrows the project's objects, with no config
     * file and Loop's settings on the command line, after the same check of git's resolved fetch URL. Git's alternate
     * refs are switched off (`core.alternateRefsCommand`), since by default git starts a `for-each-ref` in the project
     * repository, which reads its config, with the token in its environment; the project's own tips of [base] are
     * given as refs of the temporary repository instead, so only missing objects travel. The objects then reach the
     * project's object folder by plain file copy, never by a git process holding the token. Only then, without the
     * token and with no hook, git checks in the project that the commit is there and writes the ref.
     *
     * The fetches of one repository run one at a time (bl-67): from the read of the project's tips to the write of the
     * ref, each holds the mutex of the repository's object folder ([fetchLock]), so two verifications never write the
     * same ref at once and neither fails on git's ref lock. Two workspaces of one repository share that folder, so
     * they wait on each other; two repositories do not.
     *
     * A [base] that is not a safe branch name ([isBaseName]), a base the remote does not have, an authentication
     * failure and a resolved fetch URL that is not [url] are [DeliveryError.Rejected]; any other git failure, a copy
     * that fails, a timeout and a git that cannot start are [DeliveryError.Unavailable]. Messages quote git's first
     * lines, never the token.
     */
    suspend fun fetch(base: String): Result<String> =
        try {
            if (!isBaseName(base)) {
                val shown = GitHubClient.short(base.map { if (it.isISOControl()) '?' else it }.joinToString(""))
                throw DeliveryError.Rejected("\"$shown\" is not a branch name Loop fetches. Nothing was fetched")
            }
            checkToken("Nothing was fetched")
            val objects = objects()
            val sha =
                fetchLock(objects).withLock {
                    val sha256 = objectFormat() == SHA256
                    val haves = listOf("$BASE_REFS$base", "refs/heads/$base").mapNotNull { projectCommit(it) }.distinct()
                    val fetched =
                        temporary("fetch") { repository ->
                            prepare(repository, objects, sha256, what = "fetch")
                            haves.forEachIndexed { index, have -> seed(repository, "$HAVE_REF$index", have) }
                            checkUrl(repository, push = false)
                            val fetched = receive(repository, base)
                            land(repository.resolve("objects"), objects)
                            fetched
                        }
                    record(base, fetched)
                    fetched
                }
            Result.success(sha)
        } catch (e: DeliveryError) {
            Result.failure(e)
        }

    /** Fails when the token could break a credential helper's answer; [nothing] ends the message. */
    private fun checkToken(nothing: String) {
        if (token.any { it.isWhitespace() || it.isISOControl() }) {
            throw DeliveryError.Rejected("The token holds whitespace or a control character. $nothing")
        }
    }

    /**
     * Runs [block] with the path of a bare repository to create, in a new temporary folder for the [what] (push or
     * fetch), deleted afterwards whatever happens.
     */
    private suspend fun <T> temporary(
        what: String,
        block: suspend (Path) -> T,
    ): T {
        val dir =
            try {
                withContext(Dispatchers.IO) { Files.createTempDirectory("loop-$what-") }
            } catch (e: IOException) {
                throw DeliveryError.Unavailable("Cannot create the folder of the $what: ${scrub(e.message ?: "$e")}")
            }
        try {
            return block(dir.resolve("$what.git"))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { dir.toFile().deleteRecursively() } }
        }
    }

    /** The object format of the project repository, `sha1` or `sha256`. */
    private suspend fun objectFormat(): String =
        when (val outcome = run(PROJECT_PINS + listOf("rev-parse", "--show-object-format"), projectDir)) {
            is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot read the object format at $projectDir: ${outcome.reason}")
            is Outcome.Exited ->
                if (outcome.code == 0) {
                    outcome.stdout.trim()
                } else {
                    throw DeliveryError.Unavailable("Cannot read the object format at $projectDir: ${outcome.output()}")
                }
        }

    /** The commit [ref] of the project repository points at, null when there is none or it cannot be read. */
    private suspend fun projectCommit(ref: String): String? {
        val outcome = run(PROJECT_PINS + listOf("rev-parse", "--verify", "-q", "$ref^{commit}"), projectDir)
        return (outcome as? Outcome.Exited)
            ?.takeIf { it.code == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { COMMIT_ID.matches(it) }
    }

    /** Points [ref] of the temporary [repository] at [sha], a commit it borrows from the project, so git need not fetch it. */
    private suspend fun seed(
        repository: Path,
        ref: String,
        sha: String,
    ) {
        val outcome = run(listOf("--git-dir=$repository") + pins() + listOf("update-ref", ref, sha), repository.parent, isolated = true)
        if (outcome !is Outcome.Exited || outcome.code != 0) {
            throw DeliveryError.Unavailable("Cannot prepare the fetch: ${outcome.describe()}")
        }
    }

    /**
     * Fetches `refs/heads/[base]` of [url] into [repository], the token in this process alone, and returns the commit
     * git read there; classes git's failure.
     */
    private suspend fun receive(
        repository: Path,
        base: String,
    ): String {
        val gitDir = "--git-dir=$repository"
        val args =
            listOf(gitDir) + pins() + credentialArgs(host, url.scheme) + FETCH_PINS +
                listOf(
                    "fetch",
                    "--quiet",
                    "--no-tags",
                    "--no-recurse-submodules",
                    "--no-write-fetch-head",
                    url.toString(),
                    "+refs/heads/$base:$FETCHED_REF",
                )
        val outcome =
            when (val result = run(args, repository.parent, isolated = true, withToken = true)) {
                is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot fetch $base from $url: ${result.reason}")
                is Outcome.Exited -> result
            }
        when {
            outcome.code == 0 -> Unit
            MISSING_REF.containsMatchIn(outcome.stderr) ->
                throw DeliveryError.Rejected("$url has no branch $base: ${outcome.output()}")
            AUTH_FAILURE.containsMatchIn(outcome.stderr) ->
                throw DeliveryError.Rejected("$url did not accept the token for a fetch of $base: ${outcome.output()}")
            else -> throw DeliveryError.Unavailable("Cannot fetch $base from $url, git exited ${outcome.code}: ${outcome.output()}")
        }
        val read = run(listOf(gitDir) + pins() + listOf("rev-parse", "--verify", "-q", FETCHED_REF), repository.parent, isolated = true)
        val sha = (read as? Outcome.Exited)?.takeIf { it.code == 0 }?.stdout?.trim()
        if (sha == null || !COMMIT_ID.matches(sha)) {
            throw DeliveryError.Unavailable("Cannot read the fetched tip of $base: ${read.describe()}")
        }
        return sha
    }

    /**
     * Copies the packs (`.pack`, then `.rev`, then `.idx`, so git never sees an index without its pack) and the loose
     * objects of the object folder [from] into the project's object folder [objects], by plain file copy: each file to
     * a temporary name, then moved into place at once. A file the project already has is left alone.
     */
    private suspend fun land(
        from: Path,
        objects: Path,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val packs = from.resolve("pack")
                if (Files.isDirectory(packs)) {
                    val files = entries(packs) { Files.isRegularFile(it) }
                    for (extension in PACK_FILES) {
                        files
                            .filter { it.fileName.toString().endsWith(extension) }
                            .forEach { copyOnce(it, objects.resolve("pack").resolve(it.fileName.toString())) }
                    }
                }
                for (dir in entries(from) { Files.isDirectory(it) && LOOSE_DIR.matches(it.fileName.toString()) }) {
                    entries(dir) { Files.isRegularFile(it) && LOOSE_FILE.matches(it.fileName.toString()) }
                        .forEach { copyOnce(it, objects.resolve(dir.fileName.toString()).resolve(it.fileName.toString())) }
                }
            }
        } catch (e: IOException) {
            throw DeliveryError.Unavailable("Cannot copy the fetched objects into $objects: ${scrub(e.message ?: "$e")}")
        }
    }

    /** The entries of the folder [dir] that pass [keep]. */
    private fun entries(
        dir: Path,
        keep: (Path) -> Boolean,
    ): List<Path> = Files.list(dir).use { list -> list.filter(keep).toList() }

    /** Copies [file] to [target] through a temporary name in its folder, unless [target] exists. */
    private fun copyOnce(
        file: Path,
        target: Path,
    ) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        val dir = Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(dir, "tmp_loop_", null)
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING)
            runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("r--r--r--")) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * In the project repository, without the token and with no hook: checks [sha] is a commit there now, then points
     * `refs/loop/base/[base]` at it.
     */
    private suspend fun record(
        base: String,
        sha: String,
    ) {
        when (val type = run(PROJECT_PINS + listOf("cat-file", "-t", sha), projectDir)) {
            is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot check the fetched commit $sha: ${type.reason}")
            is Outcome.Exited ->
                when {
                    type.code != 0 ->
                        throw DeliveryError.Unavailable("The fetched commit $sha is not in the repository at $projectDir: ${type.output()}")
                    type.stdout.trim() != "commit" ->
                        throw DeliveryError.Rejected("The tip of $base on $url is a ${type.stdout.trim()}, not a commit")
                }
        }
        val updated = run(PROJECT_PINS + listOf("update-ref", "$BASE_REFS$base", sha), projectDir)
        if (updated !is Outcome.Exited || updated.code != 0) {
            throw DeliveryError.Unavailable("Cannot write $BASE_REFS$base at $sha: ${updated.describe()}")
        }
    }

    /** Fails unless `refs/heads/[branch]` of the project repository is at [sha]. */
    private suspend fun verify(
        sha: String,
        branch: String,
    ) {
        if (!COMMIT_ID.matches(sha)) throw DeliveryError.Rejected("\"$sha\" is not a full commit id. Nothing was pushed")
        checkToken("Nothing was pushed")
        val tip =
            when (val outcome = run(listOf("rev-parse", "--verify", "-q", "refs/heads/$branch"), projectDir)) {
                is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot read the branch $branch: ${outcome.reason}")
                is Outcome.Exited ->
                    when (outcome.code) {
                        0 -> outcome.stdout.trim()
                        1 -> throw DeliveryError.Rejected(
                            "The branch $branch does not exist, so it is not at the verified commit $sha. Nothing was pushed",
                        )
                        else -> throw DeliveryError.Unavailable("Cannot read the branch $branch: ${outcome.output()}")
                    }
            }
        if (!tip.equals(sha, ignoreCase = true)) {
            throw DeliveryError.Rejected("The branch $branch is at $tip, not the verified commit $sha. Nothing was pushed")
        }
    }

    /** The object folder of the project repository, shared by its worktrees. */
    private suspend fun objects(): Path {
        val common =
            when (val outcome = run(PROJECT_PINS + listOf("rev-parse", "--git-common-dir"), projectDir)) {
                is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot find the repository at $projectDir: ${outcome.reason}")
                is Outcome.Exited ->
                    if (outcome.code == 0) {
                        outcome.stdout.trim()
                    } else {
                        throw DeliveryError.Unavailable("Cannot find the repository at $projectDir: ${outcome.output()}")
                    }
            }
        val objects = projectDir.resolve(common).resolve("objects").normalize()
        if (!Files.isDirectory(objects)) throw DeliveryError.Unavailable("The repository at $projectDir has no object folder $objects")
        return objects
    }

    /**
     * Creates the bare [repository] of the [what] (push or fetch), in the sha256 object format when [sha256], which
     * reads its objects from [objects] and nothing else of the project.
     */
    private suspend fun prepare(
        repository: Path,
        objects: Path,
        sha256: Boolean,
        what: String,
    ) {
        val format = if (sha256) listOf("--object-format=sha256") else emptyList()
        val args = listOf("init", "--bare", "--quiet", "--template=") + format + listOf(repository.toString())
        when (val outcome = run(args, repository.parent, isolated = true)) {
            is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot prepare the $what: ${outcome.reason}")
            is Outcome.Exited ->
                if (outcome.code != 0) throw DeliveryError.Unavailable("Cannot prepare the $what: ${outcome.output()}")
        }
        try {
            withContext(Dispatchers.IO) {
                val info = Files.createDirectories(repository.resolve("objects").resolve("info"))
                Files.writeString(info.resolve("alternates"), "$objects\n")
            }
        } catch (e: IOException) {
            throw DeliveryError.Unavailable("Cannot prepare the $what: ${scrub(e.message ?: "$e")}")
        }
    }

    /**
     * Fails unless git, with the settings of the push, would push to [url] itself when [push], or fetch from it
     * otherwise: no rewrite redirects it.
     */
    private suspend fun checkUrl(
        repository: Path,
        push: Boolean,
    ) {
        val what = if (push) "push" else "fetch"
        val gitDir = "--git-dir=$repository"
        val added = run(listOf(gitDir) + pins() + listOf("remote", "add", CHECK_REMOTE, url.toString()), repository.parent, isolated = true)
        if (added !is Outcome.Exited || added.code != 0) {
            throw DeliveryError.Unavailable("Cannot check the $what address $url: ${added.describe()}")
        }
        val getUrl = if (push) listOf("remote", "get-url", "--push", "--all") else listOf("remote", "get-url", "--all")
        val resolved = run(listOf(gitDir) + pins() + getUrl + CHECK_REMOTE, repository.parent, isolated = true)
        if (resolved !is Outcome.Exited || resolved.code != 0) {
            throw DeliveryError.Unavailable("Cannot check the $what address $url: ${resolved.describe()}")
        }
        val urls =
            resolved.stdout
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (urls != listOf(url.toString())) {
            val shown = urls.joinToString(", ") { GitHubClient.short(it) }
            val verb = if (push) "push to" else "fetch from"
            val nothing = if (push) "pushed" else "fetched"
            throw DeliveryError.Rejected("git would $verb $shown, not $url. Nothing was $nothing")
        }
    }

    /** Pushes [sha] to `refs/heads/[branch]` of [url] from [repository], never forced, and classes git's failure. */
    private suspend fun send(
        repository: Path,
        sha: String,
        branch: String,
    ) {
        val args =
            listOf("--git-dir=$repository") + pins() + credentialArgs(host, url.scheme) +
                listOf("push", "--no-verify", "--porcelain", url.toString(), "$sha:refs/heads/$branch")
        val outcome =
            when (val result = run(args, repository.parent, isolated = true, withToken = true)) {
                is Outcome.Failed -> throw DeliveryError.Unavailable("Cannot push $branch to $url: ${result.reason}")
                is Outcome.Exited -> result
            }
        val refused = outcome.stdout.lines().any { it.startsWith("!\t") }
        when {
            outcome.code == 0 && !refused -> Unit
            refused -> throw DeliveryError.Rejected("$url refused the push of $branch: ${outcome.output()}")
            AUTH_FAILURE.containsMatchIn(outcome.stderr) ->
                throw DeliveryError.Rejected("$url did not accept the token for $branch: ${outcome.output()}")
            else -> throw DeliveryError.Unavailable("Cannot push $branch to $url, git exited ${outcome.code}: ${outcome.output()}")
        }
    }

    /** Loop's settings for every git process of the push, see [pins]. */
    private fun pins(): List<String> = pins(url.scheme)

    /**
     * Runs git with [args] in [cwd] with [minimalEnv] and no prompt. [isolated] adds no user or system config, for
     * the push repository; the token variable is in the environment only when [withToken].
     */
    private suspend fun run(
        args: List<String>,
        cwd: Path,
        isolated: Boolean = false,
        withToken: Boolean = false,
    ): Outcome =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(listOf(git) + args).directory(cwd.toFile())
            builder.environment().clear()
            builder.environment().putAll(minimalEnv(env))
            builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
            if (isolated) builder.environment().putAll(NO_CONFIG_FILES)
            if (withToken) builder.environment()[TOKEN_VARIABLE] = token
            val process =
                try {
                    startProcess(builder)
                } catch (e: IOException) {
                    return@withContext Outcome.Failed("git could not start: ${scrub(e.message ?: "$e")}")
                }
            try {
                process.outputStream.close()
                val stdout = async { readOutput(process.inputStream) }
                val stderr = async { readOutput(process.errorStream) }
                val exited = runInterruptible { process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
                if (!exited) {
                    stop(process)
                    return@withContext Outcome.Failed("git did not finish within $timeout")
                }
                Outcome.Exited(process.exitValue(), scrub(stdout.await().getOrThrow()), scrub(stderr.await().getOrThrow()))
            } finally {
                if (process.isAlive) stop(process)
            }
        }

    // Keep a reader failure in its result so stopping git cannot fail the timeout's coroutine scope.
    private fun readOutput(stream: java.io.InputStream): Result<String> =
        try {
            Result.success(stream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            Result.failure(e)
        }

    /** Stops git and what it started, such as a credential helper or `git-remote-https`, so their pipes close. */
    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** [text] with the token replaced, in case a remote or a helper echoes it. */
    private fun scrub(text: String): String = if (token.isEmpty()) text else text.replace(token, "<token>")

    private sealed interface Outcome {
        data class Exited(
            val code: Int,
            val stdout: String,
            val stderr: String,
        ) : Outcome {
            /** Git's first lines, each cut short: refused refs first, then stderr without hints, then the rest of stdout. */
            fun output(): String {
                val (refused, other) = stdout.lines().partition { it.startsWith("!\t") }
                val errors = stderr.lines().filterNot { it.trimStart().startsWith("hint:") }
                val lines = (refused + errors + other).map { it.replace('\t', ' ').trim() }.filter { it.isNotEmpty() && it != "Done" }
                if (lines.isEmpty()) return "no output"
                val shown = lines.take(MAX_LINES).map { GitHubClient.short(it) }
                return shown.joinToString(" | ") + if (lines.size > MAX_LINES) " | ..." else ""
            }
        }

        data class Failed(
            val reason: String,
        ) : Outcome

        /** Why the git process failed, for a message. */
        fun describe(): String =
            when (this) {
                is Exited -> "git exited $code: ${output()}"
                is Failed -> reason
            }
    }

    override fun toString(): String = "GitPush(projectDir=$projectDir, url=$url)"

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.minutes

        /** The variable that holds the token in the environment of the push and of the helpers git starts for it, nowhere else. */
        internal const val TOKEN_VARIABLE: String = "LOOP_DELIVERY_TOKEN"

        /**
         * The host git asks credentials for when it pushes to the GitHub whose API is at [apiUrl]: `api.github.com`
         * gives `github.com`, an Enterprise server `https://<host>/api/v3` gives `<host>`, a GHE.com `api.<name>.ghe.com`
         * gives `<name>.ghe.com`. Lower case, with the port when the URL has one.
         */
        fun credentialHost(apiUrl: URI): String {
            val name = apiUrl.host.orEmpty().lowercase()
            val web = if (!apiUrl.path.orEmpty().startsWith("/api/") && name.startsWith(API_PREFIX)) name.removePrefix(API_PREFIX) else name
            return if (apiUrl.port >= 0) "$web:${apiUrl.port}" else web
        }

        /**
         * The push URL of [owner]/[repo] on the GitHub whose API is at [apiUrl]: `<scheme>://<host>/<owner>/<repo>.git`,
         * with the scheme of [apiUrl] and the host of [credentialHost]. Not checked: [GitPush] refuses one that is not
         * a [isPushUrl].
         */
        fun pushUrl(
            apiUrl: URI,
            owner: String,
            repo: String,
        ): URI = URI("${apiUrl.scheme.orEmpty().lowercase()}://${credentialHost(apiUrl)}/$owner/$repo.git")

        /**
         * True when [url] is a URL Loop pushes to: https, or http for a loopback host (a test's fake server), with a
         * host the credential helper can be scoped to, no user, query or fragment, and the path `/<owner>/<repo>.git`.
         */
        fun isPushUrl(url: URI): Boolean {
            val scheme = url.scheme ?: return false
            val schemeAllowed = scheme == "https" || (scheme == "http" && GitHubTrackerSettings.isLoopback(url))
            return schemeAllowed &&
                url.rawUserInfo == null &&
                url.rawQuery == null &&
                url.rawFragment == null &&
                url.host != null &&
                HOST.matches(authority(url)) &&
                REPO_PATH.matches(url.rawPath.orEmpty())
        }

        /** The host of [url] in lower case, with its port when it has one. */
        private fun authority(url: URI): String = url.host.orEmpty().lowercase() + if (url.port >= 0) ":${url.port}" else ""

        /**
         * The environment of every git process Loop runs for delivery: `PATH`, `HOME` and the locale (`LANG`,
         * `LANGUAGE`, `LC_*`) of [env], nothing else. No token under its configured name, no Anthropic key, no `GIT_`
         * variable that could point git at another repository or config.
         */
        fun minimalEnv(env: Map<String, String>): Map<String, String> = env.filterKeys { it in MINIMAL_ENV || it.startsWith(LOCALE_PREFIX) }

        /**
         * Loop's settings on the command line of the push for a [scheme] URL: only that protocol is allowed, no
         * askpass program, no ssh command, no proxy, TLS certificates checked, no tags, no hooks.
         */
        internal fun pins(scheme: String): List<String> =
            listOf(
                "protocol.allow=never",
                "protocol.$scheme.allow=always",
                "core.askPass=",
                "core.sshCommand=",
                "http.proxy=",
                "http.sslVerify=true",
                "push.followTags=false",
                "core.hooksPath=/dev/null",
            ).flatMap { listOf("-c", it) }

        /**
         * Answers git's `get` with the token as the password of `x-access-token`, as GitHub expects, only when git asks
         * for [host] over [protocol]; answers nothing otherwise, so git finds no credential for any other host or
         * protocol. [host] must match [HOST] and [protocol] be https or http, so neither can break out of the quotes.
         */
        internal fun credentialHelper(
            host: String,
            protocol: String = HTTPS,
        ): String {
            require(HOST.matches(host)) { "host must be a host name with an optional port, got \"$host\"" }
            require(protocol == HTTPS || protocol == HTTP) { "protocol must be https or http, got \"$protocol\"" }
            return "!f() { test \"\$1\" = get || exit 0; p=; h=; " +
                "while IFS= read -r l; do test -z \"\$l\" && break; case \"\$l\" in protocol=*) p=\"\${l#protocol=}\";; " +
                "host=*) h=\"\${l#host=}\";; esac; done; " +
                "test \"\$p\" = $protocol && test \"\$h\" = '${host.lowercase()}' || exit 0; " +
                "echo username=x-access-token; echo \"password=\$$TOKEN_VARIABLE\"; }; f"
        }

        /**
         * The first, empty `credential.helper` clears every helper of any config, then Loop's helper for [host] over
         * [protocol] is the only one.
         */
        internal fun credentialArgs(
            host: String,
            protocol: String = HTTPS,
        ): List<String> = listOf("-c", "credential.helper=", "-c", "credential.helper=${credentialHelper(host, protocol)}")

        /** True when [host] is a host name with an optional port, one the credential helper can be scoped to. */
        fun isCredentialHost(host: String): Boolean = HOST.matches(host)

        /** A host name, with an optional port: nothing a shell would read as more than a word. */
        private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?")

        private const val API_PREFIX = "api."

        private const val HTTPS = "https"

        private const val HTTP = "http"

        /** `/<owner>/<repo>.git`, as GitHub names a repository. */
        private val REPO_PATH = Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\.git")

        private val MINIMAL_ENV = setOf("PATH", "HOME", "LANG", "LANGUAGE")

        private const val LOCALE_PREFIX = "LC_"

        /** Neither the user's nor the system's git config, for the processes of the push repository. */
        private val NO_CONFIG_FILES =
            mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_SYSTEM" to "/dev/null", "GIT_CONFIG_GLOBAL" to "/dev/null")

        /** The remote the push repository defines only to ask git where a push would go. */
        private const val CHECK_REMOTE = "loop-push"

        /**
         * True when [name] is a branch name Loop fetches and writes as `refs/loop/base/<name>`, by git's rules for a
         * ref name and a little stricter: not empty, at most 255 characters, no whitespace, control character or any of
         * `~^:?*[\`, no `..`, `@{` or `//`, not `@`, not starting with `-` or `/`, not ending with `/` or `.`, and no
         * part that starts with `.` or ends with `.lock`.
         */
        fun isBaseName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= MAX_BASE_LENGTH &&
                name.none { it.isWhitespace() || it.isISOControl() || it in FORBIDDEN_IN_REF } &&
                listOf("..", "@{", "//").none { it in name } &&
                name != "@" &&
                !name.startsWith("-") &&
                !name.startsWith("/") &&
                !name.endsWith("/") &&
                !name.endsWith(".") &&
                name.split('/').none { it.startsWith(".") || it.endsWith(".lock") }

        private const val MAX_BASE_LENGTH = 255

        private const val FORBIDDEN_IN_REF = "~^:?*[\\"

        /**
         * One mutex per repository for [fetch], keyed by the real path of its object folder, which every worktree of
         * the repository shares. A repository's entry stays for the life of the process: Loop serves a few.
         */
        private val fetchLocks = ConcurrentHashMap<Path, Mutex>()

        /** The fetch mutex of the repository whose object folder is [objects], by its real path, else its normalized one. */
        private fun fetchLock(objects: Path): Mutex {
            val key =
                try {
                    objects.toRealPath()
                } catch (_: IOException) {
                    objects.toAbsolutePath().normalize()
                }
            return fetchLocks.computeIfAbsent(key) { Mutex() }
        }

        /** Where the project repository keeps the fetched tip of each base. */
        private const val BASE_REFS = "refs/loop/base/"

        /** The ref of the temporary repository the fetch writes the base to. */
        private const val FETCHED_REF = "refs/heads/loop-fetched"

        /** The refs of the temporary repository that point at the project's own tips of the base. */
        private const val HAVE_REF = "refs/heads/loop-have-"

        /**
         * Loop's settings for a project side git process: no hook, no fsmonitor command. It runs without the token, but
         * still runs nothing a config names.
         */
        private val PROJECT_PINS = listOf("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false")

        /**
         * Loop's settings for the fetch, beside [pins]: objects stay a pack, no alternate refs command (by default git
         * runs `for-each-ref` in the borrowed repository, reading its config), no automatic maintenance.
         */
        private val FETCH_PINS =
            listOf(
                "fetch.unpackLimit=1",
                "core.alternateRefsCommand=true",
                "fetch.writeCommitGraph=false",
                "gc.auto=0",
                "maintenance.auto=false",
            ).flatMap { listOf("-c", it) }

        /** The files of a pack, in the order they are copied: the index last. */
        private val PACK_FILES = listOf(".pack", ".rev", ".idx")

        private val LOOSE_DIR = Regex("[0-9a-f]{2}")

        private val LOOSE_FILE = Regex("[0-9a-f]{38}|[0-9a-f]{62}")

        private const val SHA256 = "sha256"

        /** What git prints when the remote has no such branch. */
        private val MISSING_REF = Regex("couldn't find remote ref", RegexOption.IGNORE_CASE)

        private const val SHA256_LENGTH = 64

        private val COMMIT_ID = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")

        /** What git and GitHub print when the token is refused or lacks access. */
        private val AUTH_FAILURE =
            Regex(
                "authentication failed|could not read username|could not read password|terminal prompts disabled|" +
                    "permission denied|permission to .* denied|\\b403\\b",
                RegexOption.IGNORE_CASE,
            )

        private const val MAX_LINES = 5
    }
}
