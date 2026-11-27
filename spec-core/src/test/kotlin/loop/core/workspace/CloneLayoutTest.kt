package loop.core.workspace

import kotlinx.coroutines.runBlocking
import loop.api.AttemptLedger
import loop.api.DeliveryExecutor
import loop.api.Issue
import loop.api.ProjectId
import loop.api.Redactor
import loop.core.delivery.CloneDelivery
import loop.core.orchestrator.WorkspaceFacts
import loop.core.verify.CloneRoots
import loop.core.verify.CloneScope
import loop.core.verify.WorkspaceFingerprinter
import loop.core.verify.WorkspaceGit
import loop.core.workflow.HooksConfig
import loop.core.workflow.SessionBrief
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * bl-253: in `workspace.git: clone`, Loop's own git never follows the clone's `.git` to the project. Each hostile layout
 * of the issue's test recipes is first proven live on a fixture of its own, with an unguarded git that changes the
 * project, then refused on a fresh fixture by [WorkspaceClones.state] and by every git [WorkspaceGit] runs in the
 * clone, the project unchanged byte for byte. Real git, no user or system config. Each proof is printed as a line
 * starting with `PROOF`, kept in the test report.
 */
@OptIn(ExperimentalPathApi::class)
class CloneLayoutTest {
    private val folder: Path = createTempDirectory("loop-clone-layout").toRealPath()
    private val home: Path = Files.createDirectories(folder.resolve("home"))
    private val key = WorkspaceKey("t-1")
    private val hostEnv = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to home.toString(), "LANG" to "C")

    /** What a git process printed and its exit code. */
    private data class Ran(
        val code: Int,
        val out: String,
    )

    private fun exec(
        command: List<String>,
        extra: Map<String, String> = emptyMap(),
    ): Ran {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["HOME"] = home.toString()
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment().putAll(extra)
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        return Ran(process.waitFor(), out.trim())
    }

    /** Plain git in [dir], the test's own, failing the test when it fails unless [ok] is false. */
    private fun sh(
        dir: Path,
        vararg args: String,
        ok: Boolean = true,
    ): String {
        val ran = exec(listOf("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
        if (ok) check(ran.code == 0) { "git ${args.toList()} in $dir failed: ${ran.out}" }
        return ran.out
    }

    private fun proof(
        name: String,
        text: String,
    ) = println("PROOF $name: $text")

    private fun sha256(path: Path): String =
        if (Files.exists(path)) HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) else "none"

    /** What of the project a run must leave alone: config, packed refs and refs, and its work tree and index. */
    private data class Snapshot(
        val config: String,
        val packedRefs: String,
        val refs: String,
        val files: Map<String, String>,
        val index: String,
        val status: String,
    ) {
        val tree get() = Triple(files, index, status)
    }

    /**
     * A project with an `origin` remote and its remote tracking ref, a branch `side` it has not checked out that adds
     * `h.txt`, an untracked file, and the folder its clone workspaces live in, outside the project unless [rootInside].
     */
    private inner class Fixture(
        name: String,
        rootInside: Boolean = false,
        packed: Boolean = false,
    ) {
        val project: Path = Files.createDirectories(folder.resolve(name).resolve("project"))
        val root: Path =
            Files.createDirectories(if (rootInside) project.resolve(".ws") else folder.resolve(name).resolve("workspaces"))
        val workspace: Path = root.resolve(key.value)
        val git = WorkspaceGit(hostEnv, clones = CloneRoots.of(CloneScope(root, project)))
        val clones = WorkspaceClones(project, git)
        val dotGit: Path get() = workspace.resolve(".git")
        val first: String
        val side: String

        init {
            sh(project, "init", "-q", "--initial-branch=main")
            first = commit(project, "README.md", "A project.\n")
            sh(project, "remote", "add", "origin", "https://example.com/project.git")
            sh(project, "update-ref", "refs/remotes/origin/main", "HEAD")
            sh(project, "checkout", "-q", "-b", "side")
            side = commit(project, "h.txt", "from the base\n")
            sh(project, "checkout", "-q", "main")
            project.resolve("untracked.txt").writeText("the operator's own file\n")
            if (packed) sh(project, "pack-refs", "--all")
        }

        /** The clone, made by Loop's own code, then checked once as reuse checks it. */
        fun create() {
            Files.createDirectories(workspace)
            clones.create(workspace, key, null)
            assertIs<CloneState.Clone>(clones.state(workspace))
            clones.reuse(workspace, key)
        }

        fun manager() =
            WorkspaceManager(
                Workspaces(root),
                HooksConfig(),
                HookRunner { hook, _, _, _, timeoutMs, _ ->
                    HookResult(hook, 0, timedOut = false, timeoutMs = timeoutMs, durationMs = 1, output = "")
                },
                clones,
            )

        fun snapshot(): Snapshot {
            val files =
                Files.walk(project).use { walk ->
                    walk
                        .filter { Files.isRegularFile(it) }
                        .filter { !it.startsWith(project.resolve(".git")) && !it.startsWith(root) }
                        .toList()
                        .associate { project.relativize(it).toString() to sha256(it) }
                }
            return Snapshot(
                config = sha256(project.resolve(".git/config")),
                packedRefs = sha256(project.resolve(".git/packed-refs")),
                refs = sh(project, "for-each-ref"),
                files = files,
                index = sha256(project.resolve(".git/index")),
                status = sh(project, "--no-optional-locks", "status", "--porcelain", "--untracked-files=all"),
            )
        }

        /** Git in the clone with Loop's command line pins, and with its environment pins when [pinned], no check. */
        fun unguarded(
            vararg args: String,
            pinned: Boolean = true,
        ): Ran {
            val env =
                mutableMapOf(WorkspaceGit.NO_LAZY_FETCH to "1") +
                    WorkspaceGit.LOOP_IDENTITY +
                    if (pinned) {
                        mapOf(
                            WorkspaceGit.GIT_DIR to dotGit.toString(),
                            WorkspaceGit.GIT_COMMON_DIR to dotGit.toString(),
                            WorkspaceGit.GIT_WORK_TREE to workspace.toString(),
                        )
                    } else {
                        emptyMap()
                    }
            val command = listOf("git") + WorkspaceGit.PINS + listOf("-C", workspace.toString()) + WorkspaceGit.MORE_PINS + args
            return exec(command, env)
        }

        fun hostileCommondir(headAtMain: Boolean = false) {
            dotGit.resolve("commondir").writeText("${project.resolve(".git")}\n")
            if (headAtMain) dotGit.resolve("HEAD").writeText("ref: refs/heads/main\n")
        }

        fun hostileRefStorage() {
            val config = dotGit.resolve("config").toString()
            sh(folder, "config", "--file", config, "core.repositoryformatversion", "1")
            sh(folder, "config", "--file", config, "extensions.refStorage", "files://${project.resolve(".git")}")
        }

        fun link(entry: String) {
            dotGit.resolve(entry).deleteRecursively()
            Files.createSymbolicLink(dotGit.resolve(entry), project.resolve(".git").resolve(entry))
        }
    }

    private fun commit(
        dir: Path,
        file: String,
        text: String,
    ): String {
        dir.resolve(file).writeText(text)
        sh(dir, "add", file)
        sh(dir, "commit", "-q", "-m", "change $file")
        return sh(dir, "rev-parse", "HEAD")
    }

    private val issue =
        Issue(
            id = "id-t-1",
            identifier = "t-1",
            title = "Clone layout",
            state = "todo",
            priority = 2,
            labels = emptyList(),
            blockedBy = emptyList(),
            description = "## Acceptance criteria\n\n- [ ] Done.",
            createdAt = null,
        )

    private fun brief(git: WorkspaceGit) =
        SessionBrief(
            git = git,
            ledger = AttemptLedger.NONE,
            events = { Result.success(emptyList()) },
            savedResult = { throw IOException("no saved result") },
            redactor = Redactor.NONE,
        )

    /** A delivery executor that records any call, so a refused import shows nothing was pushed. */
    private fun recordingExecutor(calls: MutableList<String>): DeliveryExecutor =
        Proxy.newProxyInstance(DeliveryExecutor::class.java.classLoader, arrayOf(DeliveryExecutor::class.java)) { _, method, _ ->
            calls += method.name
            throw UnsupportedOperationException(method.name)
        } as DeliveryExecutor

    /**
     * Criterion 3's operations on the clone of [fx], each refused with a reason holding [needle]: the merge check's
     * merge, the fingerprint read, the session brief, the workspace facts, `writeBase` and the clone delivery import.
     */
    private fun assertEveryOperationRefuses(
        fx: Fixture,
        needle: String,
    ) {
        val merge = assertFailsWith<IOException> { fx.git.merge(fx.workspace, fx.side, "Merge the base") }
        assertContains(merge.message!!, needle)
        val fingerprint = WorkspaceFingerprinter(fx.git).of(fx.workspace)
        assertContains(fingerprint.exceptionOrNull()?.message.orEmpty(), needle, message = "fingerprint: $fingerprint")
        val text = runBlocking { brief(fx.git).build(ProjectId("p1"), issue, fx.workspace, "main", findings = null) }
        val workspacePart = text.lines().firstOrNull { it.startsWith("Workspace state: not available") }
        assertContains(workspacePart.orEmpty(), needle, message = text)
        val facts = WorkspaceFacts.readFacts(fx.git, fx.workspace, WorkspaceGit.Cancellation())
        assertTrue(facts.failures.values.any { needle in it }, "facts: $facts")
        val base = assertFailsWith<IOException> { fx.clones.writeBase(fx.workspace, key, "main", fx.first) }
        assertContains(base.message!!, needle)
        val import = assertFailsWith<IOException> { fx.clones.import(fx.workspace, fx.first, "loop/t-1") }
        assertContains(import.message!!, needle)
        val calls = mutableListOf<String>()
        val delivery = CloneDelivery(recordingExecutor(calls), fx.clones) { Result.success(fx.workspace) }
        val pushed = runBlocking { delivery.push(fx.first, "loop/t-1") }
        assertContains(pushed.exceptionOrNull()?.message.orEmpty(), needle)
        assertEquals(emptyList(), calls, "nothing was pushed")
        proof(
            "guarded ${needle.substringAfterLast('/')}",
            "merge, fingerprint, brief, facts, writeBase, import and delivery refused: ${merge.message}",
        )
    }

    @Test
    fun `a hostile commondir is foreign, prepare fails naming it, and the project is unchanged`() {
        val live = Fixture("commondir-live")
        live.create()
        live.hostileCommondir()
        // Loop's command line pins only: with the environment pins, git reads the clone's own config.
        assertEquals("origin", live.unguarded("remote", pinned = false).out)
        assertEquals(0, live.unguarded("remote", "remove", "origin", pinned = false).code)
        assertEquals("", sh(live.project, "remote"))
        assertEquals("", sh(live.project, "for-each-ref", "refs/remotes"))
        proof(
            "commondir live",
            "unguarded remote printed origin, remote remove origin deleted it and refs/remotes/origin/main from the project",
        )

        val fx = Fixture("commondir")
        fx.create()
        val before = fx.snapshot()
        fx.hostileCommondir()
        val commondir = fx.dotGit.resolve("commondir").toString()

        val state = assertIs<CloneState.Foreign>(fx.clones.state(fx.workspace))
        assertContains(state.reason, commondir)
        val prepared = runBlocking { fx.manager().prepare(key, emptyMap()) }
        assertContains(prepared.exceptionOrNull()?.message.orEmpty(), commondir)
        assertEquals(before, fx.snapshot())
        proof("commondir guarded", "state: ${state.reason}; prepare: ${prepared.exceptionOrNull()?.message}")
    }

    @Test
    fun `a link to the project at the config, the refs or the packed refs is foreign, reuse fails, the project unchanged`() {
        for (entry in listOf("config", "refs", "packed-refs")) {
            val packed = entry == "packed-refs"
            val live = Fixture("link-$entry-live", packed = packed)
            live.create()
            live.link(entry)
            when (entry) {
                "config" -> {
                    assertEquals(0, live.unguarded("remote", "remove", "origin").code)
                    assertEquals("", sh(live.project, "remote"))
                }
                "refs" -> {
                    assertEquals(0, live.unguarded("update-ref", "refs/loop/base/main", live.first).code)
                    assertEquals(live.first, sh(live.project, "rev-parse", "refs/loop/base/main"))
                }
                else -> {
                    val packedRefs = live.project.resolve(".git/packed-refs")
                    assertContains(packedRefs.readText(), "refs/remotes/origin/main")
                    assertEquals(0, live.unguarded("update-ref", "-d", "refs/remotes/origin/main").code)
                    assertFalse("refs/remotes/origin/main" in packedRefs.readText(), "the project's packed-refs was rewritten")
                }
            }
            proof("link $entry live", "an unguarded git through .git/$entry changed the project")

            val fx = Fixture("link-$entry", packed = packed)
            fx.create()
            val before = fx.snapshot()
            fx.link(entry)
            val link = fx.dotGit.resolve(entry).toString()

            val state = assertIs<CloneState.Foreign>(fx.clones.state(fx.workspace))
            assertContains(state.reason, link)
            val reuse = assertFailsWith<IOException> { fx.clones.reuse(fx.workspace, key) }
            assertContains(reuse.message!!, link)
            val prepared = runBlocking { fx.manager().prepare(key, emptyMap()) }
            assertContains(prepared.exceptionOrNull()?.message.orEmpty(), link)
            assertEquals(before, fx.snapshot())
            proof("link $entry guarded", "state: ${state.reason}; reuse: ${reuse.message}")
        }
    }

    @Test
    fun `a commondir set after reuse is refused by every operation and no project ref changes`() {
        val live = Fixture("late-commondir-live")
        live.create()
        val main = sh(live.project, "rev-parse", "main")
        live.hostileCommondir(headAtMain = true)
        assertEquals(0, live.unguarded("merge", "--no-ff", "--no-edit", "--no-verify", "-m", "m", live.side).code)
        val moved = sh(live.project, "rev-parse", "main")
        assertNotEquals(main, moved)
        assertNotEquals(0, exec(listOf("git", "-C", live.project.toString(), "cat-file", "-e", "$moved^{commit}")).code)
        proof(
            "late commondir live",
            "an unguarded merge with the environment pins moved the project's main from $main to $moved, a commit it does not have",
        )

        val fx = Fixture("late-commondir")
        fx.create()
        val before = fx.snapshot()
        fx.hostileCommondir(headAtMain = true)
        assertEveryOperationRefuses(fx, fx.dotGit.resolve("commondir").toString())
        assertEquals(before, fx.snapshot())
    }

    @Test
    fun `an extensions refStorage naming the project is refused at reuse and when set late, no project ref changes`() {
        val live = Fixture("refstorage-live")
        live.create()
        live.hostileRefStorage()
        assertEquals(0, live.unguarded("update-ref", "refs/loop/base/main", live.first).code)
        assertEquals(live.first, sh(live.project, "rev-parse", "refs/loop/base/main"))
        proof("refStorage live", "with the three pins, update-ref refs/loop/base/main in the clone wrote the project")

        val atReuse = Fixture("refstorage-reuse")
        atReuse.create()
        val before = atReuse.snapshot()
        atReuse.hostileRefStorage()
        val state = assertIs<CloneState.Foreign>(atReuse.clones.state(atReuse.workspace))
        assertContains(state.reason, "extensions.refStorage")
        val reuse = assertFailsWith<IOException> { atReuse.clones.reuse(atReuse.workspace, key) }
        assertContains(reuse.message!!, "extensions.refStorage")
        val prepared = runBlocking { atReuse.manager().prepare(key, emptyMap()) }
        assertContains(prepared.exceptionOrNull()?.message.orEmpty(), "extensions.refStorage")
        assertEquals(before, atReuse.snapshot())
        proof("refStorage guarded at reuse", "state: ${state.reason}")

        val late = Fixture("refstorage-late")
        late.create()
        val lateBefore = late.snapshot()
        late.hostileRefStorage()
        assertEveryOperationRefuses(late, "extensions.refStorage")
        assertEquals(lateBefore, late.snapshot())
    }

    @Test
    fun `a MERGE_MSG hard linked to a project file is foreign, the merge refuses, the project file unchanged`() {
        fun conflicted(name: String): Pair<Fixture, String> {
            val fx = Fixture(name)
            sh(fx.project, "checkout", "-q", "-b", "clash")
            val clash = commit(fx.project, "README.md", "the base says this\n")
            sh(fx.project, "checkout", "-q", "main")
            fx.create()
            commit(fx.workspace, "README.md", "the clone says that\n")
            return fx to clash
        }

        val (live, liveClash) = conflicted("hardlink-live")
        val liveVictim = live.project.resolve(".git/victim").apply { writeText("the operator's\n") }
        Files.createLink(live.dotGit.resolve("MERGE_MSG"), liveVictim)
        assertNotEquals(0, live.unguarded("merge", "--no-ff", "--no-edit", "--no-verify", "-m", "m", liveClash).code)
        assertNotEquals("the operator's\n", liveVictim.readText())
        proof(
            "hard link live",
            "an unguarded merge rewrote the project file through .git/MERGE_MSG: ${liveVictim.readText().lines().first()}",
        )

        val (fx, clash) = conflicted("hardlink")
        val victim = fx.project.resolve(".git/victim").apply { writeText("the operator's\n") }
        val hash = sha256(victim)
        Files.createLink(fx.dotGit.resolve("MERGE_MSG"), victim)
        val mergeMsg = fx.dotGit.resolve("MERGE_MSG").toString()

        val state = assertIs<CloneState.Foreign>(fx.clones.state(fx.workspace))
        assertContains(state.reason, mergeMsg)
        val merge = assertFailsWith<IOException> { fx.git.merge(fx.workspace, clash, "Merge the base") }
        assertContains(merge.message!!, mergeMsg)
        assertEquals(hash, sha256(victim))
        proof("hard link guarded", "state: ${state.reason}; merge: ${merge.message}")
    }

    @Test
    fun `a git file naming the project or a missing git after reuse is refused, project refs and index unchanged`() {
        val file = Fixture("gitdir-file")
        file.create()
        val before = file.snapshot()
        file.dotGit.deleteRecursively()
        file.dotGit.writeText("gitdir: ${file.project.resolve(".git")}\n")
        assertEquals(
            file.project.resolve(".git").toString(),
            exec(listOf("git", "-C", file.workspace.toString(), "rev-parse", "--absolute-git-dir")).out,
        )
        proof("gitdir file live", "an unguarded git in the clone found the project's repository")
        assertEveryOperationRefuses(file, file.dotGit.toString())
        val after = file.snapshot()
        assertEquals(before.refs, after.refs)
        assertEquals(before.index, after.index)

        val missing = Fixture("git-missing", rootInside = true)
        missing.create()
        val missingBefore = missing.snapshot()
        missing.dotGit.deleteRecursively()
        assertEquals(
            missing.project.resolve(".git").toString(),
            exec(listOf("git", "-C", missing.workspace.toString(), "rev-parse", "--absolute-git-dir")).out,
        )
        proof("missing .git live", "an unguarded git in the clone walked up to the project's repository")
        assertEveryOperationRefuses(missing, missing.dotGit.toString())
        val missingAfter = missing.snapshot()
        assertEquals(missingBefore.refs, missingAfter.refs)
        assertEquals(missingBefore.index, missingAfter.index)
    }

    @Test
    fun `a core worktree naming the project never lets Loop's git touch the project's files`() {
        for (variant in listOf("config", "config.worktree")) {
            fun setWorktree(fx: Fixture) {
                if (variant == "config") {
                    sh(fx.workspace, "config", "core.worktree", fx.project.toString())
                } else {
                    sh(fx.workspace, "config", "extensions.worktreeConfig", "true")
                    sh(fx.workspace, "config", "--worktree", "core.worktree", fx.project.toString())
                }
            }

            val live = Fixture("worktree-$variant-live")
            live.create()
            setWorktree(live)
            assertContains(live.unguarded("ls-files", "--others", "--exclude-standard", pinned = false).out, "untracked.txt")
            assertEquals(0, live.unguarded("merge", "--no-ff", "--no-edit", "--no-verify", "-m", "m", live.side, pinned = false).code)
            assertTrue(live.project.resolve("h.txt").exists(), "the unguarded merge wrote h.txt into the project")
            proof(
                "core.worktree $variant live",
                "unguarded ls-files listed the project's untracked.txt, an unguarded merge wrote h.txt into the project",
            )

            val fx = Fixture("worktree-$variant")
            sh(fx.project, "checkout", "-q", "-b", "clash")
            val clash = commit(fx.project, "README.md", "the base says this\n")
            sh(fx.project, "checkout", "-q", "main")
            fx.create()
            commit(fx.workspace, "README.md", "the clone says that\n")
            setWorktree(fx)
            val before = fx.snapshot()

            assertIs<CloneState.Clone>(fx.clones.state(fx.workspace))
            fx.clones.reuse(fx.workspace, key)
            WorkspaceFingerprinter(fx.git).of(fx.workspace).getOrThrow()
            assertIs<WorkspaceGit.MergeOutcome.Merged>(fx.git.merge(fx.workspace, fx.side, "Merge the base"))
            assertTrue(fx.workspace.resolve("h.txt").exists(), "the merge landed in the clone")
            assertFalse(fx.project.resolve("h.txt").exists())
            assertIs<WorkspaceGit.MergeOutcome.Conflict>(fx.git.merge(fx.workspace, clash, "Merge the base"))
            assertEquals("the clone says that\n", fx.workspace.resolve("README.md").readText())
            val text = runBlocking { brief(fx.git).build(ProjectId("p1"), issue, fx.workspace, "main", findings = null) }
            assertFalse("untracked.txt" in text, "the brief read the clone's tree, not the project's")
            val facts = WorkspaceFacts.readFacts(fx.git, fx.workspace, WorkspaceGit.Cancellation())
            assertEquals(emptyMap(), facts.failures)
            fx.clones.import(fx.workspace, fx.git.head(fx.workspace), "loop/t-1")
            assertEquals(before.tree, fx.snapshot().tree)
            proof(
                "core.worktree $variant guarded",
                "reuse, fingerprint, merge, conflict abort, brief, facts and import left the project's files, index and status unchanged",
            )
        }
    }

    @Test
    fun `create in clone mode still makes a working clone, its project git unpinned`() {
        val fx = Fixture("create")
        val seen = mutableListOf<Pair<List<String>, Map<String, String>>>()
        val recording =
            WorkspaceGit(hostEnv, clones = CloneRoots.of(CloneScope(fx.root, fx.project)), startProcess = { builder ->
                seen += builder.command().toList() to builder.environment().toMap()
                builder.start()
            })
        val clones = WorkspaceClones(fx.project, recording)
        Files.createDirectories(fx.workspace)

        clones.create(fx.workspace, key, null)
        clones.reuse(fx.workspace, key)

        assertEquals("loop/t-1", sh(fx.workspace, "symbolic-ref", "--short", "HEAD"))
        assertEquals("", sh(fx.workspace, "remote"))
        assertTrue(fx.workspace.resolve("README.md").exists())
        val pins = listOf(WorkspaceGit.GIT_DIR, WorkspaceGit.GIT_COMMON_DIR, WorkspaceGit.GIT_WORK_TREE)
        val inProject = seen.filter { (command, _) -> fx.project.toString() in command }
        assertTrue(inProject.any { (command, _) -> "clone" in command }, "git clone ran in the project")
        inProject.forEach { (command, env) -> assertTrue(pins.none { it in env }, "$command got $env") }
        val inWorkspace = seen.filter { (command, _) -> fx.workspace.toString() in command && "clone" !in command }
        assertTrue(inWorkspace.isNotEmpty())
        inWorkspace.forEach { (_, env) -> assertEquals(fx.workspace.resolve(".git").toString(), env[WorkspaceGit.GIT_DIR]) }
    }
}
