package loop.runner.acp

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedRootsCheckTest {
    @TempDir
    lateinit var repo: Path

    @TempDir
    lateinit var plainDir: Path

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `pre-existing committed customization files on base pass, while added, modified, or gitignored ones fail`() {
        initGitRepo(repo)
        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        repo.resolve(".gitignore").writeText(".agent/\n")
        git(repo, "add", ".agents/hooks.json", ".gitignore")
        git(repo, "commit", "-m", "Base commit")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        // Pre-existing unchanged file on base passes.
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Benign non-customization file under .agents/ passes.
        repo.resolve(".agents/README.md").writeText("docs")
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Modifying tracked hooks.json fails naming the path.
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        val modified = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(modified != null && ".agents/hooks.json" in modified, "$modified")

        // Even committing the modification and moving refs/heads/main to HEAD still fails against the pinned baseSha!
        git(repo, "add", ".agents/hooks.json")
        git(repo, "commit", "-m", "Modify hooks")
        git(repo, "update-ref", "refs/heads/main", "HEAD")
        val committed = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(committed != null && ".agents/hooks.json" in committed, "$committed")

        // Reset back to base and verify gitignored untracked file under .agent/ is still caught.
        git(repo, "reset", "--hard", baseSha)
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))
        repo.resolve(".agent").createDirectories()
        repo.resolve(".agent/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val ignored = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(ignored != null && ".agent/mcp_config.json" in ignored, "$ignored")
    }

    @Test
    fun `catches edits hidden via git rm --cached, assume-unchanged, and skip-worktree`() {
        // 1. git rm --cached then edit
        val rmCachedRepo = scratch.resolve("rm-cached").createDirectories()
        val rmBase = repoWithTrackedHooks(rmCachedRepo)
        git(rmCachedRepo, "rm", "--cached", ".agents/hooks.json")
        rmCachedRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"rm-cached"}]}""")
        val rmRefusal = ProtectedRootsCheck.refusal(rmCachedRepo, rmBase)
        assertTrue(rmRefusal != null && ".agents/hooks.json" in rmRefusal, "$rmRefusal")

        // 2. git update-index --assume-unchanged then edit
        val assumeRepo = scratch.resolve("assume-unchanged").createDirectories()
        val assumeBase = repoWithTrackedHooks(assumeRepo)
        git(assumeRepo, "update-index", "--assume-unchanged", ".agents/hooks.json")
        assumeRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"assume-unchanged"}]}""")
        val assumeRefusal = ProtectedRootsCheck.refusal(assumeRepo, assumeBase)
        assertTrue(assumeRefusal != null && ".agents/hooks.json" in assumeRefusal, "$assumeRefusal")

        // 3. git update-index --skip-worktree then edit
        val skipRepo = scratch.resolve("skip-worktree").createDirectories()
        val skipBase = repoWithTrackedHooks(skipRepo)
        git(skipRepo, "update-index", "--skip-worktree", ".agents/hooks.json")
        skipRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"skip-worktree"}]}""")
        val skipRefusal = ProtectedRootsCheck.refusal(skipRepo, skipBase)
        assertTrue(skipRefusal != null && ".agents/hooks.json" in skipRefusal, "$skipRefusal")
    }

    @Test
    fun `does not refuse nested paths agy never loads such as docs examples or test resources`() {
        initGitRepo(repo)
        repo.resolve("docs/examples/.agents/rules").createDirectories()
        repo.resolve("docs/examples/.agents/rules/a.md").writeText("original")
        git(repo, "add", "docs/examples/.agents/rules/a.md")
        git(repo, "commit", "-m", "Base")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        // Modify docs/examples/.agents/rules/a.md and commit src/test/resources/_agent/hooks/f.txt.
        repo.resolve("docs/examples/.agents/rules/a.md").writeText("updated")
        repo.resolve("src/test/resources/_agent/hooks").createDirectories()
        repo.resolve("src/test/resources/_agent/hooks/f.txt").writeText("fixture")
        git(repo, "add", "docs/examples/.agents/rules/a.md", "src/test/resources/_agent/hooks/f.txt")
        git(repo, "commit", "-m", "Nested doc and test fixtures")

        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))
    }

    @Test
    fun `resolves origin HEAD when main and master are absent and fails closed naming config keys otherwise`() {
        git(repo, "init", "-b", "develop")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "Test")
        repo.resolve("README.md").writeText("hi")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "Initial")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        // No baseRef, no main, no master, no origin/HEAD: fails closed naming the config keys.
        val missingDefault = ProtectedRootsCheck.refusal(repo, null)
        assertTrue(
            missingDefault != null &&
                "could not resolve base commit" in missingDefault &&
                "kinds.code.guards.base" in missingDefault,
            "$missingDefault",
        )

        // Explicit unresolvable baseRef also fails closed with git stderr detail.
        val badBase = ProtectedRootsCheck.refusal(repo, "refs/heads/no-such-base")
        assertTrue(badBase != null && "no-such-base" in badBase, "$badBase")

        // Setting refs/remotes/origin/HEAD allows default resolution on repos without main/master.
        git(repo, "update-ref", "refs/remotes/origin/develop", baseSha)
        git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/develop")
        assertNull(ProtectedRootsCheck.refusal(repo, null))

        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        val caught = ProtectedRootsCheck.refusal(repo, null)
        assertTrue(caught != null && ".agents/hooks.json" in caught, "$caught")
    }

    @Test
    fun `catches case variants, symlinked protected roots, symlink type changes, and unreadable directories`() {
        // Case variant in a non-git directory (macOS and Linux).
        plainDir.resolve(".Agents").createDirectories()
        plainDir.resolve(".Agents/hooks.json").writeText("""{"hooks":[]}""")
        val caseNonGit = ProtectedRootsCheck.refusal(plainDir, null)
        assertTrue(caseNonGit != null && ".Agents/hooks.json" in caseNonGit, "$caseNonGit")

        // Case variant in a git repository.
        initGitRepo(repo)
        repo.resolve("README.md").writeText("base")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "Base")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()

        repo.resolve(".Agent").createDirectories()
        repo.resolve(".Agent/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val caseGit = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(caseGit != null && ".Agent/mcp_config.json" in caseGit, "$caseGit")
        repo.resolve(".Agent").toFile().deleteRecursively()
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Symlinked protected root (ln -s build/x .agents).
        val targetDir = repo.resolve("build/x").createDirectories()
        targetDir.resolve("hooks.json").writeText("""{"hooks":[]}""")
        Files.createSymbolicLink(repo.resolve(".agents"), targetDir)
        val symlinkRoot = ProtectedRootsCheck.refusal(repo, baseSha)
        assertTrue(symlinkRoot != null && "symbolic link" in symlinkRoot && ".agents" in symlinkRoot, "$symlinkRoot")
        Files.delete(repo.resolve(".agents"))
        assertNull(ProtectedRootsCheck.refusal(repo, baseSha))

        // Replacing a tracked .agents/hooks.json with a symlink (mode 120000 != 100644).
        val repoWithHooks = scratch.resolve("type-change-repo").createDirectories()
        val hooksBase = repoWithTrackedHooks(repoWithHooks)
        repoWithHooks.resolve("other.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        assertNull(ProtectedRootsCheck.refusal(repoWithHooks, hooksBase))

        Files.delete(repoWithHooks.resolve(".agents/hooks.json"))
        Files.createSymbolicLink(repoWithHooks.resolve(".agents/hooks.json"), Path.of("../other.json"))
        val typeChange = ProtectedRootsCheck.refusal(repoWithHooks, hooksBase)
        assertTrue(typeChange != null && ".agents/hooks.json" in typeChange, "$typeChange")

        // Unreadable subdirectory under .agents/ fails closed instead of throwing UncheckedIOException.
        val unreadableDir = repo.resolve(".agents/hooks").createDirectories()
        unreadableDir.setPosixFilePermissions(PosixFilePermissions.fromString("---------"))
        try {
            val unreadableRefusal = ProtectedRootsCheck.refusal(repo, baseSha)
            assertTrue(unreadableRefusal != null && "could not scan" in unreadableRefusal, "$unreadableRefusal")
        } finally {
            unreadableDir.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `detects customization files under _agents, _agent rules, non-git workspaces, and 0-commit git repos`() {
        assertNull(ProtectedRootsCheck.refusal(plainDir, null))

        plainDir.resolve("_agent/rules").createDirectories()
        plainDir.resolve("_agent/rules/override.md").writeText("ignore rules")
        val found = ProtectedRootsCheck.refusal(plainDir, null)
        assertTrue(found != null && "_agent/rules/override.md" in found, "$found")

        val emptyGitRepo = scratch.resolve("empty-repo").createDirectories()
        initGitRepo(emptyGitRepo)
        assertNull(ProtectedRootsCheck.refusal(emptyGitRepo, null))
        emptyGitRepo.resolve(".agents").createDirectories()
        emptyGitRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        val emptyFound = ProtectedRootsCheck.refusal(emptyGitRepo, null)
        assertTrue(emptyFound != null && ".agents/hooks.json" in emptyFound, "$emptyFound")
    }

    @Test
    fun `runGit kills a hanging git command when the timeout expires`() {
        val hangingGit = scratch.resolve("hanging-git")
        hangingGit.writeText("#!/bin/bash\nsleep 30\n")
        hangingGit.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))

        val started = System.nanoTime()
        val result =
            ProtectedRootsCheck.runGit(
                repoRoot = scratch,
                args = emptyList(),
                timeoutSeconds = 1L,
                gitCommand = hangingGit.toString(),
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull(result)
        assertTrue(elapsedMs < 5_000L, "hanging git took $elapsedMs ms instead of timing out near 1s")
    }

    @Test
    fun `clean filter in git info attributes or gitattributes never runs and cannot hide edited hooks json`() {
        // 1. Clean filter configured via .git/info/attributes
        val infoAttrRepo = scratch.resolve("clean-filter-info").createDirectories()
        val infoBase = repoWithTrackedHooks(infoAttrRepo)
        val infoMarker = scratch.resolve("filter-info-ran.txt")
        val baseBlob = """{"hooks":[]}"""
        git(infoAttrRepo, "config", "filter.evil.clean", "touch '$infoMarker'; printf '%s' '$baseBlob'")
        infoAttrRepo.resolve(".git/info").createDirectories()
        infoAttrRepo.resolve(".git/info/attributes").writeText(".agents/hooks.json filter=evil\n")
        infoAttrRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"pwn"}]}""")

        val infoRefusal = ProtectedRootsCheck.refusal(infoAttrRepo, infoBase)
        assertTrue(infoRefusal != null && ".agents/hooks.json" in infoRefusal, "$infoRefusal")
        assertTrue(!Files.exists(infoMarker), "clean filter must never execute")

        // 2. Clean filter configured via untracked workspace .gitattributes
        val wsAttrRepo = scratch.resolve("clean-filter-ws").createDirectories()
        val wsBase = repoWithTrackedHooks(wsAttrRepo)
        val wsMarker = scratch.resolve("filter-ws-ran.txt")
        git(wsAttrRepo, "config", "filter.evil.clean", "touch '$wsMarker'; printf '%s' '$baseBlob'")
        wsAttrRepo.resolve(".gitattributes").writeText(".agents/hooks.json filter=evil\n")
        wsAttrRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"pwn2"}]}""")

        val wsRefusal = ProtectedRootsCheck.refusal(wsAttrRepo, wsBase)
        assertTrue(wsRefusal != null && ".agents/hooks.json" in wsRefusal, "$wsRefusal")
        assertTrue(!Files.exists(wsMarker), "clean filter via .gitattributes must never execute")
    }

    @Test
    fun `git replace on base tree, git replace on base commit, and git info grafts cannot bypass the check`() {
        // 1. git replace <baseTree> <editedTree>
        val treeRepo = scratch.resolve("replace-tree").createDirectories()
        val treeBase = repoWithTrackedHooks(treeRepo)
        val baseTree = git(treeRepo, "rev-parse", "$treeBase^{tree}").trim()
        treeRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"tree-replace"}]}""")
        git(treeRepo, "add", ".agents/hooks.json")
        val editedTree = git(treeRepo, "write-tree").trim()
        git(treeRepo, "replace", baseTree, editedTree)

        val treeRefusal = ProtectedRootsCheck.refusal(treeRepo, treeBase)
        assertTrue(treeRefusal != null && ".agents/hooks.json" in treeRefusal, "$treeRefusal")

        // 2. git replace <baseCommit> <forgedCommit> (both HEAD == base and HEAD descendant of base)
        val commitRepo = scratch.resolve("replace-commit").createDirectories()
        val commitBase = repoWithTrackedHooks(commitRepo)
        commitRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"commit-replace"}]}""")
        git(commitRepo, "add", ".agents/hooks.json")
        val forgedTree = git(commitRepo, "write-tree").trim()
        val forgedCommit = git(commitRepo, "commit-tree", forgedTree, "-m", "Forged base").trim()
        git(commitRepo, "replace", commitBase, forgedCommit)

        val headEqRefusal = ProtectedRootsCheck.refusal(commitRepo, commitBase)
        assertTrue(headEqRefusal != null && ".agents/hooks.json" in headEqRefusal, "$headEqRefusal")

        commitRepo.resolve("README.md").writeText("descendant")
        git(commitRepo, "add", "README.md")
        git(commitRepo, "commit", "-m", "Descendant commit")
        val headDescRefusal = ProtectedRootsCheck.refusal(commitRepo, commitBase)
        assertTrue(headDescRefusal != null && ".agents/hooks.json" in headDescRefusal, "$headDescRefusal")

        // 3. .git/info/grafts rewriting commit parents so merge-base moves to the edited commit
        val graftRepo = scratch.resolve("graft-bypass").createDirectories()
        val graftBase = repoWithTrackedHooks(graftRepo)
        graftRepo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"graft-pwn"}]}""")
        git(graftRepo, "add", ".agents/hooks.json")
        git(graftRepo, "commit", "-m", "Edited hooks")
        val editedCommit = git(graftRepo, "rev-parse", "HEAD").trim()
        graftRepo.resolve(".git/info").createDirectories()
        graftRepo.resolve(".git/info/grafts").writeText("$graftBase $editedCommit\n")

        val graftRefusal = ProtectedRootsCheck.refusal(graftRepo, graftBase)
        assertTrue(graftRefusal != null && ".agents/hooks.json" in graftRefusal, "$graftRefusal")
    }

    @Test
    fun `runGit uses minimal env without host secrets and places --no-replace-objects before the subcommand`() {
        val envDump = scratch.resolve("git-env.txt")
        val argvDump = scratch.resolve("git-argv.txt")
        val fakeGit = scratch.resolve("fake-git")
        fakeGit.writeText(
            """
            #!/bin/bash
            env > '$envDump'
            printf '%s\n' "$@" > '$argvDump'
            exit 0
            """.trimIndent() + "\n",
        )
        fakeGit.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))

        val result =
            ProtectedRootsCheck.runGit(
                repoRoot = scratch,
                args = listOf("ls-tree", "-r", "-z", "HEAD"),
                gitCommand = fakeGit.toString(),
                baseEnv =
                    mapOf(
                        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                        "HOME" to scratch.toString(),
                        "LANG" to "C.UTF-8",
                        "LC_ALL" to "C.UTF-8",
                        "LOOP_TEST_SECRET" to "super-secret-token-value",
                        "GEMINI_API_KEY" to "AIzaSecretHostKey",
                        "GIT_DIR" to "/tmp/hijacked-git-dir",
                    ),
            )

        assertTrue(result != null && result.exitCode == 0, "$result")
        val dumpedEnv = Files.readString(envDump)
        assertTrue("LOOP_TEST_SECRET" !in dumpedEnv, dumpedEnv)
        assertTrue("super-secret-token-value" !in dumpedEnv, dumpedEnv)
        assertTrue("GEMINI_API_KEY" !in dumpedEnv, dumpedEnv)
        assertTrue("GIT_DIR=" !in dumpedEnv, dumpedEnv)
        assertTrue("GIT_TERMINAL_PROMPT=0" in dumpedEnv, dumpedEnv)
        assertTrue("GIT_GRAFT_FILE=/dev/null" in dumpedEnv, dumpedEnv)

        val argv = Files.readAllLines(argvDump)
        val noReplaceIdx = argv.indexOf("--no-replace-objects")
        val subcmdIdx = argv.indexOf("ls-tree")
        assertTrue(noReplaceIdx >= 0 && subcmdIdx > noReplaceIdx, "expected --no-replace-objects before ls-tree: $argv")
    }

    @Test
    fun `strict CRLF fallback accepts exact autocrlf checkout of LF base and refuses lone CR or mixed endings`() {
        val crlfRepo = scratch.resolve("crlf-repo").createDirectories()
        initGitRepo(crlfRepo)
        crlfRepo.resolve(".agents").createDirectories()
        val lfContent = "{\n  \"hooks\": []\n}\n"
        crlfRepo.resolve(".agents/hooks.json").writeText(lfContent)
        git(crlfRepo, "add", ".agents/hooks.json")
        git(crlfRepo, "commit", "-m", "LF base")
        val baseSha = git(crlfRepo, "rev-parse", "HEAD").trim()

        // Exact CRLF checkout of the LF base blob passes.
        crlfRepo.resolve(".agents/hooks.json").writeText(lfContent.replace("\n", "\r\n"))
        assertNull(ProtectedRootsCheck.refusal(crlfRepo, baseSha))

        // Mixed CRLF and bare LF is refused.
        crlfRepo.resolve(".agents/hooks.json").writeText("{\r\n  \"hooks\": []\n}\r\n")
        val mixedRefusal = ProtectedRootsCheck.refusal(crlfRepo, baseSha)
        assertTrue(mixedRefusal != null && ".agents/hooks.json" in mixedRefusal, "$mixedRefusal")

        // Extra lone CR before CRLF is refused.
        crlfRepo.resolve(".agents/hooks.json").writeText("{\r\r\n  \"hooks\": []\r\n}\r\n")
        val loneCrRefusal = ProtectedRootsCheck.refusal(crlfRepo, baseSha)
        assertTrue(loneCrRefusal != null && ".agents/hooks.json" in loneCrRefusal, "$loneCrRefusal")
    }

    private fun repoWithTrackedHooks(dir: Path): String {
        initGitRepo(dir)
        dir.resolve(".agents").createDirectories()
        dir.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        git(dir, "add", ".agents/hooks.json")
        git(dir, "commit", "-m", "Base")
        return git(dir, "rev-parse", "HEAD").trim()
    }

    @Test
    fun `bl-246 every git of the check runs with lazy fetch off, whatever the base environment says`() {
        val env = ProtectedRootsCheck.minimalGitEnv(mapOf("PATH" to "/usr/bin:/bin", "GIT_NO_LAZY_FETCH" to "0"))
        assertEquals("1", env["GIT_NO_LAZY_FETCH"])

        val printing = scratch.resolve("print-env.sh")
        printing.writeText("#!/bin/sh\nenv\n")
        printing.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        val result =
            ProtectedRootsCheck.runGit(
                plainDir,
                listOf("status"),
                gitCommand = printing.toString(),
                baseEnv = mapOf("PATH" to "/usr/bin:/bin", "GIT_NO_LAZY_FETCH" to "0"),
            )

        assertTrue(result != null && "GIT_NO_LAZY_FETCH=1" in result.stdout.lines(), "${result?.stdout}")
    }

    @Test
    fun `bl-246 a promisor the agent configures in the workspace never runs from the check`() {
        initGitRepo(repo)
        repo.resolve("README.md").writeText("readme\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-m", "Base commit")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()
        val marker = scratch.resolve("lazy-fetch-ran")
        git(repo, "config", "extensions.partialClone", "ext::sh -c touch% $marker")
        git(repo, "config", "protocol.ext.allow", "always")
        repo.resolve(".git/refs/heads/main").writeText("1".repeat(40) + "\n")
        // Live: the same pins without the variable run the agent's program.
        unguardedGit(repo, ProtectedRootsCheck.GIT_PINS + listOf("rev-parse", "--verify", "-q", "refs/heads/main^{commit}"))
        assertTrue(Files.exists(marker), "the trap is not live")
        Files.delete(marker)

        ProtectedRootsCheck.refusal(repo, baseSha)

        assertFalse(Files.exists(marker), "the check ran the agent's program")
    }

    // bl-254: clone workspaces. The check follows the clone rule of WorkspaceGit (bl-253) in clone mode.

    /** What git printed and its exit code, run with no user or system config and no inherited `GIT_` variable. */
    private fun plainGit(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com") + args)
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor() == 0) { "git ${args.toList()} in $dir failed: $out" }
        return out
    }

    private fun sha256(path: Path): String =
        java.util.HexFormat
            .of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    private fun proof(
        name: String,
        text: String,
    ) = println("PROOF $name: $text")

    /** What of the project a check must leave alone. */
    private data class ProjectSnapshot(
        val refs: String,
        val files: Map<String, String>,
        val index: String,
        val status: String,
    )

    /**
     * A project whose base commit holds `.agents/hooks.json`, and its clone at `<root>/t-1`, made as Loop makes one:
     * `git clone --shared --no-checkout --no-tags`, no remote, `loop/t-1` checked out at the base. After the clone, the
     * project's `main` moves on and the project gets an untracked file, so a read of the project's refs or files shows.
     */
    private inner class CloneFixture(
        name: String,
        rootInside: Boolean = false,
    ) {
        val project: Path = scratch.resolve(name).resolve("project").createDirectories()
        val root: Path = (if (rootInside) project.resolve(".ws") else scratch.resolve(name).resolve("workspaces")).createDirectories()
        val workspace: Path = root.resolve("t-1")
        val dotGit: Path get() = workspace.resolve(".git")
        val base: String
        val projectMain: String

        init {
            plainGit(project, "init", "-q", "--initial-branch=main")
            project.resolve(".agents").createDirectories()
            project.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
            if (rootInside) project.resolve(".gitignore").writeText(".ws/\n")
            plainGit(project, "add", "-A")
            plainGit(project, "commit", "-q", "-m", "base")
            base = plainGit(project, "rev-parse", "HEAD")
            plainGit(root, "clone", "-q", "--shared", "--no-checkout", "--no-tags", project.toString(), workspace.toString())
            plainGit(workspace, "remote", "remove", "origin")
            plainGit(workspace, "checkout", "-q", "-b", "loop/t-1", base)
            workspace.resolve("clone-only.txt").writeText("the agent's untracked file\n")
            project.resolve("later.txt").writeText("the project moved on\n")
            plainGit(project, "add", "later.txt")
            plainGit(project, "commit", "-q", "-m", "later")
            projectMain = plainGit(project, "rev-parse", "HEAD")
            project.resolve("untracked.txt").writeText("the operator's own file\n")
        }

        fun snapshot(): ProjectSnapshot {
            val files =
                Files.walk(project).use { walk ->
                    walk
                        .filter { Files.isRegularFile(it) }
                        .filter { !it.startsWith(project.resolve(".git")) && !it.startsWith(root) }
                        .toList()
                        .associate { project.relativize(it).toString() to sha256(it) }
                }
            return ProjectSnapshot(
                refs = plainGit(project, "for-each-ref"),
                files = files,
                index = sha256(project.resolve(".git/index")),
                status = plainGit(project, "--no-optional-locks", "status", "--porcelain", "--untracked-files=all"),
            )
        }

        /** Git in the clone as the check runs it today: its pins, its minimal environment, no layout check, no pin. */
        fun asCheckToday(vararg args: String): String =
            ProtectedRootsCheck
                .runGit(workspace, args.toList())
                ?.stdout
                .orEmpty()
                .trim()

        fun hostileCommondir() {
            dotGit.resolve("commondir").writeText("${project.resolve(".git")}\n")
        }

        fun hostileRefStorage() {
            val config = dotGit.resolve("config").toString()
            plainGit(scratch, "config", "--file", config, "core.repositoryformatversion", "1")
            plainGit(scratch, "config", "--file", config, "extensions.refStorage", "files://${project.resolve(".git")}")
        }

        fun refusal(clone: Boolean = true) = ProtectedRootsCheck.refusal(workspace, base, clone = clone)
    }

    /** A git that appends its working folder and its environment to [log], then runs the real git. */
    private fun recordingGit(log: Path): Path {
        val script = scratch.resolve("recording-git")
        script.writeText("#!/bin/sh\n{ echo '---'; echo \"cwd=\$(pwd -P)\"; env; } >> '$log'\nexec git \"\$@\"\n")
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    /** The environment of each process [recordingGit] logged whose working folder is [dir]. */
    private fun recorded(
        log: Path,
        dir: Path,
    ): List<Map<String, String>> =
        Files
            .readString(log)
            .split("---\n")
            .filter { it.isNotBlank() }
            .map { block -> block.lines().filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') } }
            .filter { it["cwd"] == dir.toRealPath().toString() }

    @Test
    fun `bl-254 a hostile commondir set after reuse is refused in clone mode, naming it, the project unchanged`() {
        val live = CloneFixture("commondir-live")
        live.hostileCommondir()
        assertEquals(live.projectMain, live.asCheckToday("rev-parse", "refs/heads/main"))
        proof("acp commondir live", "the check's git in the clone read the project's main, ${live.projectMain}")

        val fx = CloneFixture("commondir")
        val before = fx.snapshot()
        fx.hostileCommondir()
        val refused = fx.refusal()
        assertTrue(refused != null && fx.dotGit.resolve("commondir").toString() in refused, "$refused")
        assertTrue(refused.startsWith("protected_workspace_root: Loop runs no git in the clone"), refused)
        assertEquals(before, fx.snapshot())
        proof("acp commondir guarded", refused)
    }

    @Test
    fun `bl-254 a hostile extensions refStorage set late is refused in clone mode, no project ref changes`() {
        val live = CloneFixture("refstorage-live")
        live.hostileRefStorage()
        assertEquals(live.projectMain, live.asCheckToday("rev-parse", "refs/heads/main"))
        proof("acp refStorage live", "the check's git in the clone read the project's main, ${live.projectMain}")

        val fx = CloneFixture("refstorage")
        val before = fx.snapshot()
        fx.hostileRefStorage()
        val refused = fx.refusal()
        assertTrue(refused != null && "extensions.refStorage" in refused, "$refused")
        assertEquals(before, fx.snapshot())
        proof("acp refStorage guarded", refused)
    }

    @Test
    fun `bl-254 a git file naming the project or a missing git is refused in clone mode, never walking up to the project`() {
        val file = CloneFixture("gitdir-file")
        val before = file.snapshot()
        file.dotGit.toFile().deleteRecursively()
        file.dotGit.writeText("gitdir: ${file.project.resolve(".git")}\n")
        assertEquals(file.project.resolve(".git").toRealPath().toString(), file.asCheckToday("rev-parse", "--absolute-git-dir"))
        proof("acp gitdir file live", "the check's git in the clone found the project's repository")
        val fileRefused = file.refusal()
        assertTrue(fileRefused != null && file.dotGit.toString() in fileRefused, "$fileRefused")
        assertEquals(before, file.snapshot())
        proof("acp gitdir file guarded", fileRefused)

        val missing = CloneFixture("git-missing", rootInside = true)
        // A customization file only the project has: a check that walks up to the project finds it.
        missing.project.resolve(".agents/mcp_config.json").writeText("""{"mcpServers":{}}""")
        val missingBefore = missing.snapshot()
        missing.dotGit.toFile().deleteRecursively()
        val walkedUp = missing.refusal(clone = false)
        assertTrue(walkedUp != null && ".agents/mcp_config.json" in walkedUp, "$walkedUp")
        proof("acp missing .git live", "findGitRoot walked up to the project, whose own file the check judged: $walkedUp")
        val missingRefused = missing.refusal()
        assertTrue(missingRefused != null && "${missing.dotGit} is missing" in missingRefused, "$missingRefused")
        assertFalse("mcp_config.json" in missingRefused, missingRefused)
        assertEquals(missingBefore, missing.snapshot())
        proof("acp missing .git guarded", missingRefused)
    }

    @Test
    fun `bl-254 a core worktree naming the project never lets the check read the project`() {
        for (variant in listOf("config", "config.worktree")) {
            fun setWorktree(fx: CloneFixture) {
                if (variant == "config") {
                    plainGit(fx.workspace, "config", "core.worktree", fx.project.toString())
                } else {
                    plainGit(fx.workspace, "config", "extensions.worktreeConfig", "true")
                    plainGit(fx.workspace, "config", "--worktree", "core.worktree", fx.project.toString())
                }
            }

            val live = CloneFixture("worktree-$variant-live")
            setWorktree(live)
            val listed = live.asCheckToday("ls-files", "--others", "--exclude-standard")
            assertTrue("untracked.txt" in listed && "clone-only.txt" !in listed, listed)
            proof("acp core.worktree $variant live", "the check's ls-files in the clone listed the project's files: ${listed.lines()}")

            val fx = CloneFixture("worktree-$variant")
            setWorktree(fx)
            val before = fx.snapshot()
            val log = scratch.resolve("worktree-$variant.log")
            val clean =
                ProtectedRootsCheck.refusal(fx.workspace, fx.base, clone = true, gitCommand = recordingGit(log).toString())
            assertNull(clean)
            val pinned = recorded(log, fx.workspace)
            assertTrue(pinned.isNotEmpty())
            pinned.forEach { assertEquals(fx.workspace.toRealPath().toString(), it["GIT_WORK_TREE"]) }
            fx.workspace.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
            val modified = fx.refusal()
            assertTrue(modified != null && ".agents/hooks.json" in modified, "$modified")
            assertEquals(before, fx.snapshot())
            proof(
                "acp core.worktree $variant guarded",
                "the check passed the clean clone with GIT_WORK_TREE at the clone, refused its edit ($modified); " +
                    "project files, index and status unchanged",
            )
        }
    }

    @Test
    fun `bl-254 every git of the check in a clone gets the three pins over the caller's values, with lazy fetch off`() {
        val fx = CloneFixture("pins")
        fx.workspace.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"edit"}]}""")
        val real = fx.workspace.toRealPath()
        val log = scratch.resolve("pins.log")
        val git = recordingGit(log).toString()
        val hostile =
            mapOf(
                "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                "HOME" to scratch.toString(),
                "GIT_DIR" to fx.project.resolve(".git").toString(),
                "GIT_COMMON_DIR" to fx.project.resolve(".git").toString(),
                "GIT_WORK_TREE" to fx.project.toString(),
                "GIT_NO_LAZY_FETCH" to "0",
            )

        val refused = ProtectedRootsCheck.refusal(fx.workspace, fx.base, clone = true, gitCommand = git, baseEnv = hostile)
        val direct = ProtectedRootsCheck.runGit(fx.workspace, listOf("rev-parse", "HEAD"), gitCommand = git, baseEnv = hostile, clone = true)

        assertTrue(refused != null && ".agents/hooks.json" in refused, "$refused")
        assertEquals(fx.base, direct?.stdout?.trim())
        val seen = recorded(log, fx.workspace)
        assertTrue(seen.size >= 4, "merge-base, ls-tree, hash-object and rev-parse ran: ${seen.size}")
        for (env in seen) {
            assertEquals(real.resolve(".git").toString(), env["GIT_DIR"])
            assertEquals(real.resolve(".git").toString(), env["GIT_COMMON_DIR"])
            assertEquals(real.toString(), env["GIT_WORK_TREE"])
            assertEquals("1", env["GIT_NO_LAZY_FETCH"])
        }
        val env = ProtectedRootsCheck.minimalGitEnv(hostile, ProtectedRootsCheck.clonePins(real))
        assertEquals(real.resolve(".git").toString(), env["GIT_DIR"])
        assertEquals("1", env["GIT_NO_LAZY_FETCH"])
        proof("acp pins", "${seen.size} git processes of the check, each pinned to $real")
    }

    @Test
    fun `bl-254 hooks mode and a context without the new field get neither the check nor the pins`() {
        val ctx =
            loop.api.AttemptContext(
                runId = loop.api.RunId("run-1"),
                projectId = loop.api.ProjectId("project-1"),
                issue = loop.api.Issue("issue-1", "t-1", "Clone", "todo", null, emptyList(), emptyList(), null, null),
                attemptId = loop.api.AttemptId("attempt-1"),
                workspace = scratch,
                env = emptyMap(),
            )
        assertFalse(ctx.cloneWorkspace)

        val fx = CloneFixture("hooks")
        fx.hostileCommondir()
        val log = scratch.resolve("hooks.log")
        val git = recordingGit(log).toString()
        val refused = ProtectedRootsCheck.refusal(fx.workspace, fx.base, clone = ctx.cloneWorkspace, gitCommand = git)
        assertFalse(refused.orEmpty().contains("Loop runs no git"), "$refused")
        val seen = recorded(log, fx.workspace)
        assertTrue(seen.isNotEmpty())
        seen.forEach { env ->
            assertNull(env["GIT_DIR"])
            assertNull(env["GIT_COMMON_DIR"])
            assertNull(env["GIT_WORK_TREE"])
            assertEquals("1", env["GIT_NO_LAZY_FETCH"])
        }

        // A worktree style workspace, a .git file in a subfolder of the repository, still walks up as before.
        initGitRepo(repo)
        repo.resolve(".agents").createDirectories()
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[]}""")
        git(repo, "add", ".agents/hooks.json")
        git(repo, "commit", "-m", "Base commit")
        val baseSha = git(repo, "rev-parse", "HEAD").trim()
        val nested = repo.resolve("sub").createDirectories()
        assertNull(ProtectedRootsCheck.refusal(nested, baseSha, clone = ctx.cloneWorkspace))
        repo.resolve(".agents/hooks.json").writeText("""{"hooks":[{"cmd":"evil"}]}""")
        val walked = ProtectedRootsCheck.refusal(nested, baseSha, clone = ctx.cloneWorkspace)
        assertTrue(walked != null && ".agents/hooks.json" in walked, "$walked")
    }

    /** git in [dir] with no user or system config and no `GIT_` variable, so lazy fetch stays on. */
    private fun unguardedGit(
        dir: Path,
        args: List<String>,
    ) {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") }
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
        process.inputStream.readAllBytes()
        process.waitFor()
    }

    private fun initGitRepo(dir: Path) {
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
    }

    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }
}
