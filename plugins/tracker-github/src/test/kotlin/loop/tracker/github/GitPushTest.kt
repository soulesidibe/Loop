package loop.tracker.github

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import loop.api.DeliveryError
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * m2-25: the push half of delivery with real git, all in a temporary directory: the remote is a bare repository served
 * by `git http-backend` behind a loopback HTTP server ([GitSmartHttp]), at `http://127.0.0.1:<port>/acme/widgets.git`,
 * which asks for the token as basic credentials. No git process reads the user's or the system's config, none may
 * prompt, and none runs outside the temporary directory, so no test can reach a real remote.
 */
class GitPushTest {
    @TempDir
    lateinit var tmp: Path

    private val token = "ghp_m225PushToken0123456789abcdefghijklmn"

    private val remoteDir get() = tmp.resolve("remote.git")
    private val workDir get() = tmp.resolve("work")

    /** The environment of every git process here, the ones [GitPush] starts included. */
    private val env: Map<String, String> by lazy {
        mapOf(
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
            "HOME" to tmp.resolve("home").toString(),
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_AUTHOR_NAME" to "Loop Test",
            "GIT_AUTHOR_EMAIL" to "loop@example.com",
            "GIT_COMMITTER_NAME" to "Loop Test",
            "GIT_COMMITTER_EMAIL" to "loop@example.com",
        )
    }

    private lateinit var first: String

    private val gitServer by lazy { GitSmartHttp(remoteDir, password = token) }

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/acme/widgets.git/", gitServer)
            start()
        }
    }

    /** The push URL of the fake GitHub, as [GitPush.pushUrl] gives it for an API on the same loopback server. */
    private val url: URI by lazy {
        GitPush.pushUrl(URI("http://${server.address.address.hostAddress}:${server.address.port}/api"), "acme", "widgets")
    }

    @BeforeEach
    fun setUp() {
        tmp.resolve("home").createDirectories()
        git(tmp, "init", "--quiet", "--bare", "remote.git")
        git(tmp, "init", "--quiet", "--initial-branch=main", "work")
        first = commit("first")
        git(workDir, "branch", "loop/k1", first)
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun pusher(
        pushUrl: URI = url,
        gitCommand: String = "git",
        timeout: Duration = GitPush.DEFAULT_TIMEOUT,
        environment: Map<String, String> = env,
    ) = GitPush(workDir, pushUrl, token, environment, gitCommand, timeout)

    /** The requests that reached the git server with the token as basic credentials. */
    private fun authenticated(): List<String> = gitServer.requests.filter { it.second != null }.map { it.first }

    @Test
    fun `a timeout is unavailable when both readers see closed streams`() =
        runTest {
            val process = ClosedStreamProcess()
            val error =
                GitPush(workDir, url, token, env, startProcess = { process })
                    .push(first, "loop/k1")
                    .exceptionOrNull()

            assertIs<DeliveryError.Unavailable>(error)
            assertTrue("did not finish within" in error.message!!, error.message)
            assertEquals(2, process.failures.get())
        }

    @Test
    fun `a push sends the verified commit to the remote branch`() =
        runTest {
            val result = pusher().push(first, "loop/k1")

            assertTrue(result.isSuccess, result.toString())
            assertEquals(first, remoteRef("loop/k1"))
        }

    @Test
    fun `pushing a commit the remote branch already holds succeeds`() =
        runTest {
            assertTrue(pusher().push(first, "loop/k1").isSuccess)

            val again = pusher().push(first, "loop/k1")

            assertTrue(again.isSuccess, again.toString())
            assertEquals(first, remoteRef("loop/k1"))
        }

    @Test
    fun `a branch that is not at the verified commit is rejected and pushes nothing`() =
        runTest {
            val second = commit("second")

            val moved = pusher().push(second, "loop/k1").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(moved)
            for (part in listOf("loop/k1", first, second)) assertTrue(part in moved.message!!, moved.message)
            assertNull(remoteRef("loop/k1"))

            val missing = pusher().push(first, "loop/missing").exceptionOrNull()
            assertIs<DeliveryError.Rejected>(missing)
            assertTrue("loop/missing" in missing.message!!, missing.message)
            assertNull(remoteRef("loop/missing"))
        }

    @Test
    fun `a push that is not a fast forward is rejected and leaves the remote branch alone`() =
        runTest {
            git(workDir, "checkout", "--quiet", "-b", "side")
            val side = commit("side")
            git(workDir, "branch", "--force", "loop/k1", side)
            assertTrue(pusher().push(side, "loop/k1").isSuccess)
            git(workDir, "checkout", "--quiet", "main")
            val other = commit("other")
            git(workDir, "branch", "--force", "loop/k1", other)

            val error = pusher().push(other, "loop/k1").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("rejected" in error.message!!, error.message)
            assertEquals(side, remoteRef("loop/k1"))
        }

    @Test
    fun `a server that cannot be reached is unavailable`() =
        runTest {
            val error = pusher(pushUrl = URI("http://127.0.0.1:1/acme/widgets.git")).push(first, "loop/k1").exceptionOrNull()

            assertIs<DeliveryError.Unavailable>(error)
            assertTrue("127.0.0.1:1" in error.message!!, error.message)
        }

    @Test
    fun `a sha that is not a full commit id is rejected without starting git`() =
        runTest {
            val log = tmp.resolve("argv.log")
            val wrapper = wrapper(log)
            for (sha in listOf("HEAD", "+$first", first.take(12), "-$first")) {
                assertIs<DeliveryError.Rejected>(pusher(gitCommand = wrapper.toString()).push(sha, "loop/k1").exceptionOrNull(), sha)
            }
            assertFalse(log.exists(), "git was started")
        }

    @Test
    fun `the token is in no argument and no repository file, and the push alone gets it, with a minimal environment`() =
        runTest {
            val log = tmp.resolve("argv.log")
            val envs = tmp.resolve("envs").createDirectories()
            // The host env of a real run: the token under its configured name, the Anthropic keys, git variables.
            val host =
                env +
                    mapOf(
                        "LOOP_PR_TOKEN" to token,
                        "ANTHROPIC_API_KEY" to "sk-ant-m225-api-key-0123456789",
                        "ANTHROPIC_AUTH_TOKEN" to "sk-ant-m225-auth-token-0123456789",
                        "GIT_DIR" to tmp.resolve("elsewhere").toString(),
                        "GIT_ASKPASS" to tmp.resolve("bin/askpass").toString(),
                        "LANG" to "C.UTF-8",
                        "LC_ALL" to "C",
                    )

            val result = pusher(gitCommand = wrapper(log, envs).toString(), environment = host).push(first, "loop/k1")

            assertTrue(result.isSuccess, result.toString())
            assertEquals(first, remoteRef("loop/k1"))
            val lines = log.readText().lines().filter { it.isNotBlank() }
            assertFalse(token in log.readText(), "the token is in an argument")
            val revParse = lines.first { "rev-parse --verify" in it }
            val push = lines.single { " push " in it }
            assertTrue(revParse.startsWith("token=unset "), revParse)
            assertTrue(push.startsWith("token=set "), push)
            assertTrue(" --porcelain $url $first:refs/heads/loop/k1" in push, push)
            assertFalse("--force" in push || " -f " in push || "+$first" in push, push)
            assertFalse(workDir.toString() in push, "the push runs in Loop's own repository, not the project's: $push")
            for (pin in GitPush.pins("http").filter { it != "-c" }) assertTrue("-c $pin " in push, "$pin is not pinned: $push")
            assertEquals(lines.size, Files.list(envs).use { it.count() }.toInt())
            Files.list(envs).use { it.toList() }.forEach { file ->
                val seen =
                    file
                        .readText()
                        .lines()
                        .drop(1)
                        .filter { "=" in it }
                        .associate { it.substringBefore('=') to it.substringAfter('=') }
                val unexpected =
                    seen.keys.filterNot {
                        it in setOf("PATH", "HOME", "LANG", "GIT_TERMINAL_PROMPT", GitPush.TOKEN_VARIABLE) ||
                            it.startsWith("LC_") ||
                            it.startsWith("GIT_CONFIG_") ||
                            it in SHELL_VARIABLES
                    }
                assertEquals(emptyList(), unexpected, "git got more than a minimal environment: $file")
                assertEquals("C.UTF-8", seen["LANG"], "the locale is kept")
                for (secret in listOf("sk-ant-m225-api-key-0123456789", "sk-ant-m225-auth-token-0123456789")) {
                    assertFalse(secret in file.readText(), "an Anthropic key reached git: $file")
                }
                val isPush = " push " in file.readText().lines().first()
                assertEquals(isPush, token in file.readText(), "the token reaches the push alone: $file")
            }
            for (dir in listOf(workDir.resolve(".git"), remoteDir)) {
                Files.walk(dir).use { files ->
                    files.filter { it.isRegularFile() }.forEach { file ->
                        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token), "the token is in $file")
                    }
                }
            }
        }

    @Test
    fun `a hostile repository config written from a worktree runs nothing, sends nothing elsewhere and leaks no token`() =
        runTest {
            val ran = tmp.resolve("ran").createDirectories()
            val evil =
                script(
                    "evil",
                    """
                    env > '$ran'/env-${'$'}${'$'}.txt
                    echo "${'$'}*" > '$ran'/argv-${'$'}${'$'}.txt
                    exit 1
                    """,
                )
            val hooks = tmp.resolve("hooks").createDirectories()
            for (hook in listOf("pre-push", "post-update", "reference-transaction")) {
                Files.copy(evil, hooks.resolve(hook))
            }
            val elsewhere = tmp.resolve("elsewhere.git")
            git(tmp, "init", "--quiet", "--bare", "elsewhere.git")
            val proxy = Listener()
            git(workDir, "remote", "add", "origin", remoteDir.toString())
            git(workDir, "worktree", "add", "--quiet", "-b", "agent", "../wt")
            val worktree = tmp.resolve("wt")
            val base = "http://${server.address.address.hostAddress}:${server.address.port}/"
            // What an agent can write from its worktree: the project repository's config is shared.
            val hostile =
                listOf(
                    "remote.origin.url" to elsewhere.toString(),
                    "remote.origin.pushurl" to "file://$elsewhere",
                    "remote.origin.receivepack" to evil.toString(),
                    "remote.$url.pushurl" to "file://$elsewhere",
                    "remote.$url.receivepack" to evil.toString(),
                    "url.ext::$evil %S .insteadOf" to base,
                    "url.file://$elsewhere.pushInsteadOf" to url.toString(),
                    "protocol.ext.allow" to "always",
                    "protocol.file.allow" to "always",
                    "core.sshCommand" to evil.toString(),
                    "core.askPass" to evil.toString(),
                    "core.fsmonitor" to evil.toString(),
                    "core.hooksPath" to hooks.toString(),
                    "credential.helper" to "!$evil",
                    "credential.$base.helper" to "!$evil",
                    "http.proxy" to "http://127.0.0.1:${proxy.port}",
                    "http.$base.proxy" to "http://127.0.0.1:${proxy.port}",
                    "http.sslVerify" to "false",
                    "push.followTags" to "true",
                )
            for ((key, value) in hostile) git(worktree, "config", "--add", key, value)

            val result =
                try {
                    pusher().push(first, "loop/k1")
                } finally {
                    proxy.close()
                }

            assertTrue(result.isSuccess, result.toString())
            assertEquals(first, remoteRef("loop/k1"))
            assertEquals(emptyList(), Files.list(ran).use { it.toList() }, "a command of the config ran")
            assertEquals(0, proxy.connections.get(), "something went through the proxy")
            assertEquals("", git(tmp, "--git-dir=elsewhere.git", "for-each-ref"), "something was pushed elsewhere")
            assertTrue(
                gitServer.requests.all {
                    it.first.startsWith("GET /acme/widgets.git/") ||
                        it.first.startsWith("POST /acme/widgets.git/")
                },
            )
            Files.walk(tmp).use { files ->
                files.filter { it.isRegularFile() }.forEach { file ->
                    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token), "the token is in $file")
                }
            }
        }

    @Test
    fun `a push url that a rewrite would redirect is rejected and nothing is sent`() =
        runTest {
            val elsewhere = tmp.resolve("elsewhere.git")
            git(tmp, "init", "--quiet", "--bare", "elsewhere.git")
            // A config Loop cannot see coming, given to every git process: its push would go elsewhere.
            val rewriting =
                script(
                    "rewriting-git",
                    """
                    exec '$realGit' -c 'url.file://$elsewhere.pushInsteadOf=$url' "$@"
                    """,
                )

            val error = pusher(gitCommand = rewriting.toString()).push(first, "loop/k1").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("file://$elsewhere" in error.message!! && "Nothing was pushed" in error.message!!, error.message)
            assertEquals("", git(tmp, "--git-dir=elsewhere.git", "for-each-ref"))
            assertEquals(emptyList(), gitServer.requests)
            assertNull(remoteRef("loop/k1"))
        }

    @Test
    fun `only an https url, or http for a loopback host, of a repository is a push url`() {
        assertEquals(URI("https://github.com/acme/widgets.git"), GitPush.pushUrl(URI("https://api.github.com"), "acme", "widgets"))
        assertEquals(URI("https://ghe.example.com/acme/w.git"), GitPush.pushUrl(URI("https://ghe.example.com/api/v3"), "acme", "w"))
        for (good in listOf("https://github.com/acme/widgets.git", "https://ghe.example.com:8443/a/b.git", "http://127.0.0.1:9/a/b.git")) {
            assertTrue(GitPush.isPushUrl(URI(good)), good)
        }
        for (bad in listOf(
            "http://github.com/acme/widgets.git",
            "ssh://github.com/acme/widgets.git",
            "file:///tmp/acme/widgets.git",
            "https://x-access-token@github.com/acme/widgets.git",
            "https://github.com/acme/widgets.git?x=1",
            "https://github.com/acme/widgets",
            "https://github.com/../widgets.git/x.git",
        )) {
            assertFalse(GitPush.isPushUrl(URI(bad)), bad)
            assertTrue(runCatching { GitPush(workDir, URI(bad), token, env) }.isFailure, bad)
        }
    }

    @Test
    fun `the credential helper hands the token to git as x-access-token, for the configured host over https only`() {
        val given = fill(GitPush.credentialArgs("github.com"), "protocol=https\nhost=github.com\n\n")

        assertEquals(0, given.first, given.second)
        val lines = given.second.lines()
        assertTrue("username=x-access-token" in lines, given.second)
        assertTrue("password=$token" in lines, "the helper gave no password")

        val enterprise = fill(GitPush.credentialArgs("ghe.example.com:8443"), "protocol=https\nhost=ghe.example.com:8443\n\n")
        assertTrue("password=$token" in enterprise.second.lines(), "the helper gave no password to the configured host")

        val loopback = fill(GitPush.credentialArgs("127.0.0.1:8080", "http"), "protocol=http\nhost=127.0.0.1:8080\n\n")
        assertTrue("password=$token" in loopback.second.lines(), "the helper gave no password to the fake server")
        val otherScheme = fill(GitPush.credentialArgs("127.0.0.1:8080", "http"), "protocol=https\nhost=127.0.0.1:8080\n\n")
        assertFalse(token in otherScheme.second, "the helper gave the token over another protocol")

        for (asked in listOf(
            "protocol=https\nhost=evil.example.com\n",
            "protocol=http\nhost=github.com\n",
            "protocol=https\nhost=github.com.evil.example\n",
            "protocol=https\nhost=api.github.com\n",
            "host=github.com\nprotocol=ssh\n",
        )) {
            val refused = fill(GitPush.credentialArgs("github.com"), "$asked\n")
            assertFalse(token in refused.second, "the helper gave the token for $asked")
            assertTrue(refused.first != 0, "git found a credential for $asked: ${refused.second}")
        }
    }

    @Test
    fun `the credential host is the web host of the api url`() {
        assertEquals("github.com", GitPush.credentialHost(java.net.URI("https://api.github.com")))
        assertEquals("ghe.example.com", GitPush.credentialHost(java.net.URI("https://ghe.example.com/api/v3")))
        assertEquals("octo.ghe.com", GitPush.credentialHost(java.net.URI("https://api.octo.ghe.com/")))
        assertEquals("127.0.0.1:8080", GitPush.credentialHost(java.net.URI("http://127.0.0.1:8080/api")))
        assertEquals("ghe.example.com", GitPush.credentialHost(java.net.URI("https://GHE.example.com/api/v3")))
    }

    @Test
    fun `a credential host that could break out of the helper is refused`() {
        for (bad in listOf("", "github.com'; echo x", "github.com\$(id)", "a b", "github.com/x")) {
            assertTrue(runCatching { GitPush.credentialArgs(bad) }.isFailure, "accepted \"$bad\"")
        }
        for (bad in listOf("ssh", "ext", "https; echo x", "")) {
            assertTrue(runCatching { GitPush.credentialArgs("github.com", bad) }.isFailure, "accepted the protocol \"$bad\"")
        }
    }

    /** What `git credential fill` gives for [stdin] with Loop's helper [args] alone and the token in the environment. */
    private fun fill(
        args: List<String>,
        stdin: String,
    ): Pair<Int, String> = run(listOf(realGit) + args + listOf("credential", "fill"), tmp, env + (GitPush.TOKEN_VARIABLE to token), stdin)

    @Test
    fun `an authentication failure is rejected and its message never shows the token`() =
        runTest {
            val wrapper =
                script(
                    "auth-fail",
                    """
                    case " $* " in
                      *" push "*)
                        echo "remote: Invalid username or password." >&2
                        echo "fatal: Authentication failed for 'https://github.com/o/r.git/' with ${'$'}${GitPush.TOKEN_VARIABLE}" >&2
                        exit 128 ;;
                    esac
                    exec '$realGit' "$@"
                    """,
                )

            val error = pusher(gitCommand = wrapper.toString()).push(first, "loop/k1").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("Authentication failed" in error.message!!, error.message)
            assertFalse(token in error.message!!, "the message shows the token")
        }

    @Test
    fun `a git that does not start, or does not finish in time, is unavailable`() =
        runTest {
            val missing = pusher(gitCommand = tmp.resolve("no-such-git").toString()).push(first, "loop/k1").exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(missing)

            val slow =
                script(
                    "slow",
                    """
                    case " $* " in *" push "*) sleep 30 ;; esac
                    exec '$realGit' "$@"
                    """,
                )
            val started = System.nanoTime()
            val late = pusher(gitCommand = slow.toString(), timeout = 1.seconds).push(first, "loop/k1").exceptionOrNull()
            assertIs<DeliveryError.Unavailable>(late)
            assertTrue((System.nanoTime() - started) / 1_000_000_000 < 20, "the timeout did not stop git")
            assertNull(remoteRef("loop/k1"))
        }

    @Test
    fun `a base fetch lands the remote base tip in the project and writes refs loop base at it`() =
        runTest {
            val tip = upstream()

            val result = pusher().fetch("main")

            assertEquals(tip, result.getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertEquals("commit", git(workDir, "cat-file", "-t", tip))
            assertEquals("upstream", git(workDir, "show", "$tip:upstream.txt"))
            assertTrue(authenticated().isNotEmpty(), "the server never got the token")
            assertEquals(first, git(workDir, "rev-parse", "refs/heads/main"), "the project's own branch moved")
            assertEquals("", git(workDir, "status", "--porcelain"), "the work tree changed")

            val next = upstream(message = "next")
            assertEquals(next, pusher().fetch("main").getOrThrow())
            assertEquals(next, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertEquals(next, pusher().fetch("main").getOrThrow(), "a fetch with nothing new still proves the tip")
            git(workDir, "fsck", "--no-progress", "--connectivity-only")
        }

    @Test
    fun `a base fetch with slashes in the base writes the ref of that base`() =
        runTest {
            val tip = upstream(branch = "release/2026.11")

            assertEquals(tip, pusher().fetch("release/2026.11").getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/release/2026.11"))
        }

    @Test
    fun `two fetches in two workspaces of one repository run one after the other and both pin the base`() =
        runTest {
            val tip = upstream()
            git(workDir, "worktree", "add", "--quiet", "--detach", tmp.resolve("ws-a").toString())
            git(workDir, "worktree", "add", "--quiet", "--detach", tmp.resolve("ws-b").toString())
            val race = FetchRace(wait = 2.seconds)

            val (a, b) =
                coroutineScope {
                    val a = async { race.pusher("a", tmp.resolve("ws-a")).fetch("main") }
                    val b = async { race.pusher("b", tmp.resolve("ws-b")).fetch("main") }
                    a.await() to b.await()
                }

            assertEquals(tip, a.getOrThrow())
            assertEquals(tip, b.getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertEquals(1, race.timedOut(), "both network fetches ran at once: ${race.starts}")
            val order = race.starts.joinToString("")
            assertTrue(order.matches(Regex("a+b+|b+a+")), "the git processes of the two fetches interleaved: $order")
        }

    @Test
    fun `fetches of two different repositories do not wait on each other`() =
        runTest {
            val tip = upstream()
            git(tmp, "clone", "--quiet", "--no-local", "work", "project-2")
            val race = FetchRace(wait = 10.seconds)

            val (a, b) =
                coroutineScope {
                    val a = async { race.pusher("a", workDir).fetch("main") }
                    val b = async { race.pusher("b", tmp.resolve("project-2")).fetch("main") }
                    a.await() to b.await()
                }

            assertEquals(tip, a.getOrThrow())
            assertEquals(tip, b.getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertEquals(tip, git(tmp.resolve("project-2"), "rev-parse", "refs/loop/base/main"))
            assertEquals(0, race.timedOut(), "one fetch waited for the other: ${race.starts}")
        }

    /**
     * Two fetches, tagged `a` and `b`, that see each other: right before its network `git fetch` starts, each one
     * signals it and waits up to [wait] for the other's. [starts] holds the tag of every git process they start, but
     * the read of the common folder, which tells repositories apart before any lock.
     */
    private inner class FetchRace(
        private val wait: Duration,
    ) {
        val starts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val fetching = mapOf("a" to CountDownLatch(1), "b" to CountDownLatch(1))
        private val waits = Collections.synchronizedList(mutableListOf<Boolean>())

        fun pusher(
            tag: String,
            projectDir: Path,
        ) = GitPush(projectDir, url, token, env, startProcess = { builder -> start(tag, builder) })

        /** How many fetches gave up waiting for the other one. */
        fun timedOut(): Int = waits.count { !it }

        private fun start(
            tag: String,
            builder: ProcessBuilder,
        ): Process {
            val args = builder.command()
            if ("--git-common-dir" !in args) starts += tag
            if ("fetch" in args) {
                fetching.getValue(tag).countDown()
                val other = fetching.getValue(if (tag == "a") "b" else "a")
                waits += other.await(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
            return builder.start()
        }
    }

    @Test
    fun `the token reaches the base fetch alone, no git process holding it reads the project, and the project side runs no hook`() =
        runTest {
            val tip = upstream()
            val log = tmp.resolve("argv.log")
            val envs = tmp.resolve("envs").createDirectories()
            val traces = tmp.resolve("traces").createDirectories()
            val host =
                env +
                    mapOf(
                        "LOOP_PR_TOKEN" to token,
                        "ANTHROPIC_API_KEY" to "sk-ant-m226-api-key-0123456789",
                        "GIT_DIR" to tmp.resolve("elsewhere").toString(),
                        "LANG" to "C.UTF-8",
                    )

            val result = pusher(gitCommand = tracingWrapper(log, envs, traces).toString(), environment = host).fetch("main")

            assertEquals(tip, result.getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertFalse(token in log.readText(), "the token is in an argument")
            val lines = log.readText().lines().filter { it.isNotBlank() }
            val fetch = lines.single { " fetch " in it }
            assertTrue(fetch.startsWith("token=set "), fetch)
            assertEquals(listOf(fetch), lines.filter { it.startsWith("token=set ") }, "the token reached another git process")
            for (part in listOf(
                "-c fetch.unpackLimit=1 ",
                "-c core.alternateRefsCommand=",
                " --no-tags ",
                " --no-recurse-submodules ",
                " --no-write-fetch-head ",
                " $url +refs/heads/main:",
            )) {
                assertTrue(part in fetch, "$part is missing: $fetch")
            }
            for (pin in GitPush.pins("http").filter { it != "-c" }) assertTrue("-c $pin " in fetch, "$pin is not pinned: $fetch")
            assertFalse(workDir.toString() in fetch, "the fetch runs in Loop's own repository, not the project's: $fetch")
            for (projectSide in listOf("cat-file -t $tip", "update-ref refs/loop/base/main $tip")) {
                val line = lines.single { projectSide in it }
                assertTrue(line.startsWith("token=unset "), line)
                assertTrue("-c core.hooksPath=/dev/null -c core.fsmonitor=false " in line, line)
            }
            // What the fetch itself started, read from git's own trace: never a process in the project repository.
            val fetchTrace =
                Files.list(envs).use { it.toList() }.single { " fetch " in it.readText().lines().first() }.let { file ->
                    traces.resolve(file.fileName.toString().replace("env-", "trace-"))
                }
            val started = fetchTrace.readText().lines().filter { "run_command:" in it }
            assertTrue(started.isNotEmpty(), "the trace shows nothing the fetch started")
            for (line in started) {
                val command = line.substringAfter("run_command:").substringAfterLast("; ").trim()
                assertFalse("for-each-ref" in line, "the fetch listed the refs of the project: $line")
                assertFalse("work/.git" in command && !command.startsWith("true "), "the fetch started a git process in the project: $line")
            }
            Files.list(envs).use { it.toList() }.forEach { file ->
                val seen =
                    file
                        .readText()
                        .lines()
                        .drop(1)
                        .filter { "=" in it }
                        .associate { it.substringBefore('=') to it.substringAfter('=') }
                val unexpected =
                    seen.keys.filterNot {
                        it in setOf("PATH", "HOME", "LANG", "GIT_TERMINAL_PROMPT", GitPush.TOKEN_VARIABLE) ||
                            it.startsWith("LC_") ||
                            it.startsWith("GIT_CONFIG_") ||
                            it in SHELL_VARIABLES
                    }
                assertEquals(emptyList(), unexpected, "git got more than a minimal environment: $file")
                assertFalse("sk-ant-m226-api-key-0123456789" in file.readText(), "an Anthropic key reached git: $file")
                val isFetch = " fetch " in file.readText().lines().first()
                assertEquals(isFetch, token in file.readText(), "the token reaches the fetch alone: $file")
            }
        }

    @Test
    fun `a hostile project and user config runs nothing during a base fetch, sends nothing elsewhere and leaks no token`() =
        runTest {
            val tip = upstream()
            val ran = tmp.resolve("ran").createDirectories()
            val evil =
                script(
                    "evil",
                    """
                    env > '$ran'/env-${'$'}${'$'}.txt
                    echo "${'$'}*" > '$ran'/argv-${'$'}${'$'}.txt
                    exit 1
                    """,
                )
            val hooks = tmp.resolve("hooks").createDirectories()
            for (hook in listOf("reference-transaction", "post-checkout", "post-merge", "pre-auto-gc")) {
                Files.copy(evil, hooks.resolve(hook))
            }
            val elsewhere = tmp.resolve("elsewhere.git")
            git(tmp, "init", "--quiet", "--bare", "elsewhere.git")
            val proxy = Listener()
            git(workDir, "worktree", "add", "--quiet", "-b", "agent", "../wt")
            val worktree = tmp.resolve("wt")
            val base = "http://${server.address.address.hostAddress}:${server.address.port}/"
            val hostile =
                listOf(
                    "remote.$url.url" to elsewhere.toString(),
                    "remote.$url.uploadpack" to evil.toString(),
                    "url.ext::$evil %S .insteadOf" to base,
                    "url.file://$elsewhere.insteadOf" to url.toString(),
                    "protocol.ext.allow" to "always",
                    "protocol.file.allow" to "always",
                    "core.sshCommand" to evil.toString(),
                    "core.askPass" to evil.toString(),
                    "core.fsmonitor" to evil.toString(),
                    "core.hooksPath" to hooks.toString(),
                    "core.alternateRefsCommand" to evil.toString(),
                    "credential.helper" to "!$evil",
                    "credential.$base.helper" to "!$evil",
                    "http.proxy" to "http://127.0.0.1:${proxy.port}",
                    "http.$base.proxy" to "http://127.0.0.1:${proxy.port}",
                    "http.sslVerify" to "false",
                    "fetch.recurseSubmodules" to "true",
                    "gc.auto" to "1",
                )
            // What an agent can write from its worktree, the project's shared config, and a hostile user config.
            for ((key, value) in hostile) git(worktree, "config", "--add", key, value)
            val userConfig = tmp.resolve("home").resolve(".gitconfig")
            for ((key, value) in hostile.filter { it.first.startsWith("url.") || it.first.startsWith("http.") }) {
                git(tmp, "config", "--file", userConfig.toString(), "--add", key, value)
            }

            val result =
                try {
                    pusher().fetch("main")
                } finally {
                    proxy.close()
                }

            assertEquals(tip, result.getOrThrow())
            assertEquals(tip, git(workDir, "rev-parse", "refs/loop/base/main"))
            assertEquals(emptyList(), Files.list(ran).use { it.toList() }, "a command of the config ran")
            assertEquals(0, proxy.connections.get(), "something went through the proxy")
            assertTrue(authenticated().isNotEmpty(), "the server never got the token")
            assertTrue(
                gitServer.requests.all {
                    it.first.startsWith("GET /acme/widgets.git/") ||
                        it.first.startsWith("POST /acme/widgets.git/")
                },
            )
            Files.walk(tmp).use { files ->
                files.filter { it.isRegularFile() }.forEach { file ->
                    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token), "the token is in $file")
                }
            }
        }

    @Test
    fun `a base the remote does not have is rejected and writes no ref`() =
        runTest {
            upstream()

            val error = pusher().fetch("nope").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("nope" in error.message!!, error.message)
            assertFalse(token in error.message!!, error.message)
            assertNull(projectRef("refs/loop/base/nope"))
        }

    @Test
    fun `a base that is not a safe branch name is rejected without starting git`() =
        runTest {
            val log = tmp.resolve("argv.log")
            val wrapper = wrapper(log)
            for (bad in listOf(
                "",
                "-main",
                "--upload-pack=x",
                "a..b",
                "a b",
                "a:b",
                "a\tb",
                "main\n",
                "a~1",
                "a^",
                "a*",
                "a?",
                "a[x",
                "a\\b",
                "@",
                "a@{u}",
                "/main",
                "main/",
                "a//b",
                "main.",
                ".hidden",
                "a/.b",
                "main.lock",
                "a/b.lock/c",
                "x".repeat(300),
            )) {
                val error = pusher(gitCommand = wrapper.toString()).fetch(bad).exceptionOrNull()
                assertIs<DeliveryError.Rejected>(error, "accepted \"$bad\"")
            }
            assertFalse(log.exists(), "git was started")
            for (good in listOf("main", "release/2026.11", "feature/a-b_c", "v1.2")) assertTrue(GitPush.isBaseName(good), good)
        }

    @Test
    fun `a base fetch from a server that cannot be reached is unavailable`() =
        runTest {
            val error = pusher(pushUrl = URI("http://127.0.0.1:1/acme/widgets.git")).fetch("main").exceptionOrNull()

            assertIs<DeliveryError.Unavailable>(error)
            assertTrue("127.0.0.1:1" in error.message!!, error.message)
            assertNull(projectRef("refs/loop/base/main"))
        }

    @Test
    fun `a base fetch with a token the server refuses is rejected and never shows the token`() =
        runTest {
            upstream()
            val wrong = "ghp_m226WrongToken0123456789abcdefghijklm"

            val error = GitPush(workDir, url, wrong, env).fetch("main").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertFalse(wrong in error.message!! || token in error.message!!, error.message)
            assertNull(projectRef("refs/loop/base/main"))
        }

    @Test
    fun `a fetch url that a rewrite would redirect is rejected and nothing is fetched`() =
        runTest {
            upstream()
            val elsewhere = tmp.resolve("elsewhere.git")
            git(tmp, "init", "--quiet", "--bare", "elsewhere.git")
            val rewriting =
                script(
                    "rewriting-fetch-git",
                    """
                    exec '$realGit' -c 'url.file://$elsewhere.insteadOf=$url' "$@"
                    """,
                )

            val error = pusher(gitCommand = rewriting.toString()).fetch("main").exceptionOrNull()

            assertIs<DeliveryError.Rejected>(error)
            assertTrue("file://$elsewhere" in error.message!! && "Nothing was fetched" in error.message!!, error.message)
            assertEquals(emptyList(), gitServer.requests)
            assertNull(projectRef("refs/loop/base/main"))
        }

    @Test
    fun `a git that does not finish the base fetch in time is unavailable`() =
        runTest {
            upstream()
            val slow =
                script(
                    "slow-fetch",
                    """
                    case " $* " in *" fetch "*) sleep 30 ;; esac
                    exec '$realGit' "$@"
                    """,
                )
            val started = System.nanoTime()

            val error = pusher(gitCommand = slow.toString(), timeout = 1.seconds).fetch("main").exceptionOrNull()

            assertIs<DeliveryError.Unavailable>(error)
            assertTrue((System.nanoTime() - started) / 1_000_000_000 < 20, "the timeout did not stop git")
            assertNull(projectRef("refs/loop/base/main"))
        }

    /**
     * Gives the bare remote the branch [branch] one commit ahead of the project's `main`, a commit the project does not
     * have, made in a separate repository, and returns its id.
     */
    private fun upstream(
        branch: String = "main",
        message: String = "upstream",
    ): String {
        val other = tmp.resolve("other")
        if (!other.exists()) git(tmp, "init", "--quiet", "other")
        if (remoteRef(branch) != null) {
            git(other, "fetch", "--quiet", remoteDir.toString(), "refs/heads/$branch")
        } else {
            git(other, "fetch", "--quiet", workDir.toString(), "refs/heads/main")
        }
        git(other, "checkout", "--quiet", "-B", "work", "FETCH_HEAD")
        other.resolve("upstream.txt").writeText("$message\n")
        git(other, "add", "upstream.txt")
        git(other, "commit", "--quiet", "-m", message)
        val sha = git(other, "rev-parse", "HEAD")
        git(tmp, "--git-dir=remote.git", "fetch", "--quiet", other.toString(), "+work:refs/heads/$branch")
        return sha
    }

    /** The commit [ref] of the project repository points at, null when there is none. */
    private fun projectRef(ref: String): String? {
        val (code, out) = run(listOf(realGit, "rev-parse", "--verify", "-q", ref), workDir, env)
        return if (code == 0) out.trim() else null
    }

    /**
     * A git that logs like [wrapper] with [envs], and also has git trace every process it starts into a file of
     * [traces] named like its environment file, then runs the real git.
     */
    private fun tracingWrapper(
        log: Path,
        envs: Path,
        traces: Path,
    ): Path =
        script(
            "tracing-git",
            """
            if [ -n "${'$'}${GitPush.TOKEN_VARIABLE}" ]; then t=set; else t=unset; fi
            printf 'token=%s argv=%s\n' "${'$'}t" "$*" >> '$log'
            f='$envs'/env-${'$'}${'$'}.txt; echo "argv ${'$'}*" > "${'$'}f"; env >> "${'$'}f"
            GIT_TRACE='$traces'/trace-${'$'}${'$'}.txt; export GIT_TRACE
            exec '$realGit' "$@"
            """,
        )

    /**
     * A git that appends `token=set|unset argv=<arguments>` to [log] and, when [envs] is given, writes its argv then its
     * environment to a new file there, then runs the real git.
     */
    private fun wrapper(
        log: Path,
        envs: Path? = null,
    ): Path =
        script(
            "logging-git",
            """
            if [ -n "${'$'}${GitPush.TOKEN_VARIABLE}" ]; then t=set; else t=unset; fi
            printf 'token=%s argv=%s\n' "${'$'}t" "$*" >> '$log'
            ${if (envs == null) "" else "f='$envs'/env-${'$'}${'$'}.txt; echo \"argv ${'$'}*\" > \"${'$'}f\"; env >> \"${'$'}f\""}
            exec '$realGit' "$@"
            """,
        )

    /** Accepts and counts connections on a loopback port, as a proxy would get them, and answers none. */
    private class Listener : AutoCloseable {
        private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val connections = AtomicInteger()
        private val thread =
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    runCatching { socket.accept().use { connections.incrementAndGet() } }
                }
            }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    private fun script(
        name: String,
        body: String,
    ): Path {
        val file = tmp.resolve("bin").createDirectories().resolve(name)
        file.writeText("#!/bin/sh\n" + body.trimIndent() + "\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file
    }

    /** The real git on `PATH`, resolved when the test runs. */
    private val realGit: String by lazy {
        (System.getenv("PATH") ?: "")
            .split(java.io.File.pathSeparator)
            .map { Path.of(it, "git") }
            .firstOrNull { it.isExecutable() }
            ?.toString()
            ?: "/usr/bin/git"
    }

    private fun commit(message: String): String {
        workDir.resolve("file.txt").writeText("$message\n")
        git(workDir, "add", "file.txt")
        git(workDir, "commit", "--quiet", "-m", message)
        return git(workDir, "rev-parse", "HEAD")
    }

    /** The commit at `refs/heads/[branch]` of the bare remote, null when there is none. */
    private fun remoteRef(branch: String): String? {
        val (code, out) =
            run(
                listOf(realGit, "--git-dir=${remoteDir.fileName}", "rev-parse", "--verify", "-q", "refs/heads/$branch"),
                tmp,
                env,
            )
        return if (code == 0) out.trim() else null
    }

    private companion object {
        /** What `sh` itself adds to the environment it prints. */
        val SHELL_VARIABLES = setOf("PWD", "SHLVL", "_", "OLDPWD")
    }

    /** Runs git in [cwd], which is always inside [tmp]; fails the test on a non zero exit. Returns stdout, trimmed. */
    private fun git(
        cwd: Path,
        vararg args: String,
    ): String {
        val (code, out) = run(listOf(realGit) + args, cwd, env)
        if (code != 0) fail("git ${args.joinToString(" ")} exited $code\n$out")
        return out.trim()
    }

    /** Runs [command] in [cwd] with exactly [environment]; the exit code and the output, stderr after stdout. */
    private fun run(
        command: List<String>,
        cwd: Path,
        environment: Map<String, String>,
        stdin: String = "",
    ): Pair<Int, String> {
        check(cwd.toAbsolutePath().startsWith(tmp.toAbsolutePath())) { "a test git ran outside the temporary directory: $cwd" }
        val builder = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(environment)
        val process = builder.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("${command.joinToString(" ")} did not exit in 30s")
        }
        return process.exitValue() to output
    }
}

private class ClosedStreamProcess : Process() {
    private val stopped = java.util.concurrent.CountDownLatch(1)
    private val reading = java.util.concurrent.CountDownLatch(2)
    val failures =
        java.util.concurrent.atomic
            .AtomicInteger()

    private fun stream() =
        object : java.io.InputStream() {
            override fun read(): Int {
                reading.countDown()
                check(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
                failures.incrementAndGet()
                throw java.io.IOException("Stream closed")
            }
        }

    override fun getInputStream() = stream()

    override fun getErrorStream() = stream()

    override fun getOutputStream() = java.io.ByteArrayOutputStream()

    override fun waitFor(): Int {
        check(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return 1
    }

    override fun waitFor(
        timeout: Long,
        unit: java.util.concurrent.TimeUnit,
    ): Boolean {
        check(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return false
    }

    override fun exitValue() = 1

    override fun isAlive() = stopped.count != 0L

    override fun descendants(): java.util.stream.Stream<ProcessHandle> =
        java.util.stream.Stream
            .empty()

    override fun destroy() {
        stopped.countDown()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}
