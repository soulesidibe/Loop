package loop.cli

import kotlinx.coroutines.runBlocking
import loop.api.AttemptId
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.api.RunId
import loop.core.orchestrator.QueuedRequest
import loop.core.orchestrator.REQUESTS_DIR
import loop.core.orchestrator.TOOL_CONFIG_FILE
import loop.core.orchestrator.ToolRequestQueue
import loop.core.orchestrator.attemptDirectory
import loop.core.workflow.WorkflowLoader
import loop.store.SqliteEventStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.fail
import kotlin.time.Clock

/** What one launcher run printed and returned. */
data class LoopResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    override fun toString(): String = "exit=$exitCode\n--- stdout\n$stdout\n--- stderr\n$stderr"
}

/**
 * A temporary machine for end to end cli tests: a project folder, git free unless
 * [useGitWorktrees] is called, with a minimal `WORKFLOW.md` (plan D21) and a
 * `.loop/issues/` folder, a stub agent script
 * outside the project, and separate `LOOP_HOME` and `HOME` folders, so a test never
 * touches the real home. Commands run through the installed launcher (plan D7).
 */
class LoopFixture(
    private val root: Path,
    /** The machine folder; two fixtures given the same one are two projects on one machine (m3-16). */
    val loopHome: Path = root.resolve("loop-home"),
) {
    val projectDir: Path = root.resolve("project").createDirectories()
    val homeDir: Path = root.resolve("home").createDirectories()
    val issuesDir: Path = projectDir.resolve(".loop/issues").createDirectories()
    val workflow: Path = projectDir.resolve("WORKFLOW.md")
    val agentScript: Path = root.resolve("bin").createDirectories().resolve("agent.sh")
    private val outputDir: Path = root.resolve("out").createDirectories()
    private var runs = 0

    init {
        agentScript.writeText(
            """
            |#!/bin/sh
            |set -e
            |echo "hello from ${'$'}LOOP_ISSUE_IDENTIFIER" > hello.txt
            |loop tool move review
            |
            """.trimMargin(),
        )
        Files.setPosixFilePermissions(agentScript, PosixFilePermissions.fromString("rwxr-xr-x"))
        writeWorkflow()
    }

    /**
     * Writes `WORKFLOW.md` with [activeStates], plus [extra] front matter lines
     * (for example `retries:` with `  max_attempts: 1`), and [prompt] as its template.
     */
    fun writeWorkflow(
        activeStates: List<String> = listOf("todo"),
        extra: String = "",
        prompt: String = "Work on {{ issue.identifier }}: {{ issue.title }}\n",
    ) {
        val extraLines = if (extra.isEmpty()) "" else extra.trimEnd() + "\n"
        workflow.writeText(
            "---\n" +
                "tracker:\n" +
                "  kind: files\n" +
                "  active_states: [${activeStates.joinToString(", ")}]\n" +
                "  required_labels: [ready-for-agent]\n" +
                "workspace:\n" +
                "  root: ./workspaces\n" +
                "runner:\n" +
                "  kind: command\n" +
                "runners:\n" +
                "  command:\n" +
                "    command: \"${agentScript.toAbsolutePath()}\"\n" +
                "polling:\n" +
                "  interval_ms: 1000\n" +
                extraLines +
                "---\n" +
                prompt,
        )
    }

    /** One line per agent call, the attempt id, written outside the project by an agent that logs its calls. */
    val agentCallsFile: Path = root.resolve("agent-calls.log")

    /**
     * Replaces the agent with one that fails every time: it appends its attempt id to
     * [agentCallsFile] and to `touched.log` in its workspace, then exits 1.
     */
    fun useFailingAgent() {
        agentScript.writeText(
            """
            |#!/bin/sh
            |echo "${'$'}LOOP_ATTEMPT_ID" >> "$agentCallsFile"
            |echo "${'$'}LOOP_ATTEMPT_ID" >> touched.log
            |exit 1
            |
            """.trimMargin(),
        )
    }

    /** How many times an agent that logs its calls to [agentCallsFile] ran. */
    fun agentCalls(): Int = if (Files.exists(agentCallsFile)) agentCallsFile.readText().lines().count { it.isNotBlank() } else 0

    /** Writes an issue file in the format of docs/agents/issue-tracker.md. */
    fun issue(
        id: String,
        state: String = "todo",
        labels: List<String> = listOf("ready-for-agent"),
        title: String = "Issue $id",
        criteria: String = "- [ ] hello.txt exists.",
    ): Path {
        val file = issueFile(id)
        file.writeText(
            """
            |---
            |id: $id
            |title: "$title"
            |state: $state
            |priority: 2
            |labels: [${labels.joinToString(", ")}]
            |blocked_by: []
            |---
            |
            |## Parent
            |
            |None.
            |
            |## What
            |
            |Write hello.txt in the workspace.
            |
            |## Acceptance criteria
            |
            |$criteria
            |
            |## Comments
            |
            """.trimMargin(),
        )
        return file
    }

    fun issueFile(id: String): Path = issuesDir.resolve("$id.md")

    /**
     * Opt in git mode (m1-04): copies Loop's documented hook scripts into the project's
     * `scripts/hooks/`, adds them to `WORKFLOW.md` in the documented form, with
     * [beforeRunExtra] as a second `before_run` line, then makes the project a git
     * repository on `main` holding everything written so far. Call after the issues.
     */
    fun useGitWorktrees(beforeRunExtra: String? = null): TestRepo {
        val source = Path.of(System.getProperty("loop.hooksDir") ?: fail("system property loop.hooksDir is not set"))
        val target = projectDir.resolve("scripts/hooks").createDirectories()
        Files.list(source).use { files -> files.forEach { Files.copy(it, target.resolve(it.fileName)) } }
        val hook = { name: String -> "sh \"\$(dirname \"\$LOOP_WORKFLOW\")/scripts/hooks/$name\"" }
        val beforeRun = listOfNotNull(hook("worktree-before-run.sh"), beforeRunExtra).joinToString(" && ")
        val hooks =
            """
            |hooks:
            |  after_create: ${hook("worktree-after-create.sh")}
            |  before_run: |
            |    $beforeRun
            |  before_remove: ${hook("worktree-before-remove.sh")}
            |polling:
            """.trimMargin()
        workflow.writeText(workflow.readText().replace("polling:", hooks))
        return TestRepo(projectDir, homeDir).init(seed = false)
    }

    fun stateOf(id: String): String =
        STATE_LINE
            .find(issueFile(id).readText())
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: fail("no state line in ${issueFile(id)}")

    fun workspace(identifier: String): Path = projectDir.resolve("workspaces").resolve(identifier)

    /**
     * Plan D5: first 16 hex of the SHA-256 of the real path of `WORKFLOW.md`. Computed once, so a test
     * can still use it after deleting `WORKFLOW.md` (m1-23).
     */
    fun projectId(): String =
        cachedProjectId ?: run {
            val digest = MessageDigest.getInstance("SHA-256").digest(workflow.toRealPath().toString().toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16).also { cachedProjectId = it }
        }

    private var cachedProjectId: String? = null

    val storeFile: Path get() = loopHome.resolve("store.db")

    /** The human readable run log of this project (m1-39). */
    val humanLog: Path get() = loopHome.resolve("logs").resolve("${projectId()}.log")

    /** Rows of [sql] on the store file, every column as text. */
    fun query(sql: String): List<Map<String, String?>> =
        DriverManager.getConnection("jdbc:sqlite:$storeFile").use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getString(it) })
                        }
                    }
                }
            }
        }

    /** Records [events] through the real store, as a running `loop run` would. */
    fun seed(vararg events: RecordedEvent) {
        SqliteEventStore.open(loopHome, projectDir, Redactor.NONE).getOrThrow().use { store ->
            runBlocking { events.forEach { store.record(it).getOrThrow() } }
        }
    }

    fun attemptStarted(
        attemptId: String,
        issueId: String,
        runId: String = RUN_ID,
    ): RecordedEvent =
        RecordedEvent(
            ts = Clock.System.now(),
            projectId = ProjectId(projectId()),
            kind = "attempt_started",
            runId = RunId(runId),
            issueId = issueId,
            issueIdentifier = issueId,
            attemptId = AttemptId(attemptId),
        )

    fun attemptFinished(
        attemptId: String,
        issueId: String,
        runId: String = RUN_ID,
    ): RecordedEvent = attemptStarted(attemptId, issueId, runId).copy(kind = "attempt_finished")

    /** The env Loop gives an agent process (plan D10), for the attempt [attemptId] of [issueId]. */
    fun attemptEnv(
        attemptId: String,
        issueId: String,
        runId: String = RUN_ID,
    ): Map<String, String?> =
        mapOf(
            "LOOP_WORKFLOW" to workflow.toRealPath().toString(),
            "LOOP_PROJECT_ID" to projectId(),
            "LOOP_RUN_ID" to runId,
            "LOOP_ISSUE_ID" to issueId,
            "LOOP_ISSUE_IDENTIFIER" to issueId,
            "LOOP_ATTEMPT_ID" to attemptId,
        )

    /** The attempt directory of [attemptId] in this project (m1-23). */
    fun attemptDir(attemptId: String): Path = attemptDirectory(loopHome, ProjectId(projectId()), AttemptId(attemptId))

    /**
     * What `loop run` gives an agent since m1-23: the attempt directory of [attemptId] with its
     * requests folder and a tool config snapshot of the current `WORKFLOW.md`, written by the real
     * writer, and the attempt env with `LOOP_TOOL_CONFIG` and without `LOOP_WORKFLOW`.
     */
    fun toolEnv(
        attemptId: String,
        issueId: String,
        runId: String = RUN_ID,
    ): Map<String, String?> {
        val dir = attemptDir(attemptId)
        dir.resolve(REQUESTS_DIR).createDirectories()
        val loaded = WorkflowLoader(env = emptyMap(), home = homeDir).load(workflow).getOrThrow()
        val states = trackerFor(loaded, loopHome).definedStates
        val config = dir.resolve(TOOL_CONFIG_FILE)
        ToolConfigSnapshotWriter.write(config, ProjectId(projectId()), loaded, states)
        return attemptEnv(attemptId, issueId, runId) + mapOf("LOOP_WORKFLOW" to null, "LOOP_TOOL_CONFIG" to config.toString())
    }

    /** The requests queued in the attempt directory of [attemptId], in name order. */
    fun queuedRequests(attemptId: String): List<QueuedRequest> = ToolRequestQueue(attemptDir(attemptId)).pending()

    /**
     * Runs the installed launcher with [args] in [cwd]. The env drops every inherited
     * `LOOP_*` variable, then sets `LOOP_HOME`, `HOME` and `JAVA_HOME`, then [env]
     * (a null value removes the variable).
     */
    fun loop(
        vararg args: String,
        cwd: Path = projectDir,
        env: Map<String, String?> = emptyMap(),
        timeoutSeconds: Long = 60,
    ): LoopResult = start(*args, cwd = cwd, env = env).await(timeoutSeconds)

    /** A launcher run started by [start]; the start script execs java, so [process] is the JVM. */
    inner class RunningLoop(
        val process: Process,
        private val args: List<String>,
        private val stdout: File,
        private val stderr: File,
    ) {
        /** What the run printed on stdout so far, for a command that keeps running. */
        fun stdoutSoFar(): String = read(stdout)

        /** Waits for the run to exit; kills it and fails the test after [timeoutSeconds]. */
        fun await(timeoutSeconds: Long = 60): LoopResult {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                val dump = dumpHang()
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                fail(
                    "loop ${args.joinToString(" ")} did not exit in ${timeoutSeconds}s, diagnostics in $dump\n" +
                        "${read(stdout)}\n${read(stderr)}",
                )
            }
            return LoopResult(process.exitValue(), read(stdout), read(stderr))
        }

        /**
         * Hang diagnostics, test only: `ps` of the run and its descendants, then the threads of each
         * JVM through `jcmd Thread.print`, or SIGQUIT (a dump on the JVM's stdout, never a kill) when
         * jcmd cannot attach. Written outside the @TempDir, which JUnit deletes; returns the path.
         */
        private fun dumpHang(): Path {
            val dir = Path.of(System.getProperty("java.io.tmpdir"), "loop-hangs").createDirectories()
            val file = Files.createTempFile(dir, "hang-", ".txt")
            val handles = listOf(process.toHandle()) + process.descendants().toList()
            val jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString()
            val report =
                buildString {
                    appendLine(capture(listOf("ps", "-o", "pid,ppid,stat,command", "-p", handles.joinToString(",") { "${it.pid()}" })))
                    val jvms = handles.filter { handle -> isJvm(handle) }
                    jvms.forEach { handle ->
                        val pid = handle.pid().toString()
                        val threads = capture(listOf(jcmd, pid, "Thread.print"))
                        appendLine("--- threads of pid $pid")
                        if ("\"main\"" in threads || handle != process.toHandle()) {
                            appendLine(threads)
                        } else {
                            val before = read(stdout).length
                            capture(listOf("kill", "-QUIT", pid))
                            Thread.sleep(QUIT_DUMP_WAIT_MS)
                            appendLine(read(stdout).drop(before))
                        }
                    }
                }
            file.writeText(report)
            return file
        }

        private fun isJvm(handle: ProcessHandle): Boolean {
            val command = handle.info().command()
            return command.isPresent && command.get().endsWith("java")
        }

        private fun capture(command: List<String>): String =
            try {
                val p = ProcessBuilder(command).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                text
            } catch (e: IOException) {
                "cannot run ${command.first()}: ${e.message}"
            }
    }

    /**
     * Starts the installed launcher like [loop] without waiting for it.
     *
     * [defaultSigint] starts it with SIGINT at its default, as a terminal does. A process started with SIGINT
     * ignored, as `nohup` or a detached `loop run` leaves it, passes that on, and the JVM then never sees Ctrl-C.
     */
    fun start(
        vararg args: String,
        cwd: Path = projectDir,
        env: Map<String, String?> = emptyMap(),
        defaultSigint: Boolean = false,
    ): RunningLoop {
        val launcher = System.getProperty("loop.launcher") ?: fail("system property loop.launcher is not set")
        val run = ++runs
        val stdout = outputDir.resolve("run-$run.out").toFile()
        val stderr = outputDir.resolve("run-$run.err").toFile()
        // perl execs the launcher in place, so the pid stays the launcher's.
        val prefix = if (defaultSigint) listOf("perl", "-e", SIGINT_DEFAULT) else emptyList()
        val builder =
            ProcessBuilder(prefix + launcher + args)
                .directory(cwd.toFile())
                .redirectOutput(stdout)
                .redirectError(stderr)
        val processEnv = builder.environment()
        processEnv.keys.removeIf { it.startsWith("LOOP_") || it.startsWith("GIT_") || it == "JAVA_OPTS" }
        // Hooks in git mode run git: no system config, the fake home's config only.
        processEnv["GIT_CONFIG_NOSYSTEM"] = "1"
        processEnv["LOOP_HOME"] = loopHome.toString()
        processEnv["HOME"] = homeDir.toString()
        processEnv["JAVA_HOME"] = System.getProperty("java.home")
        env.forEach { (key, value) -> if (value == null) processEnv.remove(key) else processEnv[key] = value }
        return RunningLoop(builder.start(), args.toList(), stdout, stderr)
    }

    private fun read(file: File): String = if (file.exists()) file.readText() else ""

    companion object {
        const val RUN_ID: String = "5b2f0c3d-0000-4000-8000-000000000001"
        private const val DUMP_TIMEOUT_SECONDS = 20L
        private const val QUIT_DUMP_WAIT_MS = 3_000L
        private val STATE_LINE = Regex("""^state:(.*)$""", RegexOption.MULTILINE)

        /** Sets SIGINT back to its default, then execs its arguments with no shell. */
        private const val SIGINT_DEFAULT = "\$SIG{INT} = 'DEFAULT'; exec { \$ARGV[0] } @ARGV or die \"exec \$ARGV[0]: \$!\\n\";"
    }
}
