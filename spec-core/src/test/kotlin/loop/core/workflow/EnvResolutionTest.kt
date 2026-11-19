package loop.core.workflow

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvResolutionTest {
    private val folder: Path = createTempDirectory("loop-workflow-env").toRealPath()

    private fun workflow(
        root: String = "./workspaces",
        dir: String = "./issues",
        command: String = "./agent.sh",
    ): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |  provider:
                |    dir: $dir
                |workspace:
                |  root: $root
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: $command
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun load(
        env: Map<String, String>,
        path: Path,
    ): Result<WorkflowFile> = WorkflowLoader(env = env).load(path)

    @Test
    fun `unset var fails naming the variable`() {
        val error = load(mapOf("OTHER" to "x"), workflow(root = "\$WS_ROOT")).exceptionOrNull()

        assertIs<WorkflowError.MissingEnvVar>(error)
        assertEquals("workspace.root uses \$WS_ROOT, which is not set", error.message)
    }

    @Test
    fun `empty var fails as empty`() {
        val error = load(mapOf("ISSUES_DIR" to ""), workflow(dir = "\$ISSUES_DIR")).exceptionOrNull()

        assertIs<WorkflowError.MissingEnvVar>(error)
        assertEquals("tracker.provider.dir uses \$ISSUES_DIR, which is empty", error.message)
    }

    @Test
    fun `dollar var resolves from the environment`() {
        val env = mapOf("WS_ROOT" to "/tmp/loop-ws", "ISSUES_DIR" to "./from-env")

        val workflow = load(env, workflow(root = "\$WS_ROOT", dir = "\$ISSUES_DIR")).getOrThrow()

        assertEquals(Path.of("/tmp/loop-ws"), workflow.workspaceRoot)
        assertEquals(folder.resolve("from-env"), workflow.trackerDir)
    }

    @Test
    fun `a resolved var then expands the home folder`() {
        val home = createTempDirectory("loop-home").toRealPath()

        val workflow =
            WorkflowLoader(env = mapOf("WS_ROOT" to "~/ws"), home = home)
                .load(workflow(root = "\$WS_ROOT"))
                .getOrThrow()

        assertEquals(home.resolve("ws"), workflow.workspaceRoot)
    }

    @Test
    fun `a var inside a longer string is left as written`() {
        val workflow = load(mapOf("WS_ROOT" to "/tmp/x"), workflow(root = "./\$WS_ROOT/ws")).getOrThrow()

        assertEquals(folder.resolve("\$WS_ROOT/ws"), workflow.workspaceRoot)
        assertEquals(emptyList(), workflow.config.envRefs)
    }

    @Test
    fun `dollar var in a shell command is left as written`() {
        val workflow = load(mapOf("AGENT" to "/bin/agent"), workflow(command = "\$AGENT")).getOrThrow()

        assertEquals(commandRunner("\$AGENT"), workflow.runner)
    }

    @Test
    fun `resolved vars are recorded by name`() {
        val env = mapOf("WS_ROOT" to "/tmp/loop-ws", "ISSUES_DIR" to "./from-env")

        val workflow = load(env, workflow(root = "\$WS_ROOT", dir = "\$ISSUES_DIR")).getOrThrow()

        assertEquals(
            listOf(EnvRef("ISSUES_DIR", "tracker.provider.dir"), EnvRef("WS_ROOT", "workspace.root")),
            workflow.config.envRefs,
        )
    }
}
