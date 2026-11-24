package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.runBlocking
import loop.api.ProjectId
import loop.api.RecordedEvent
import loop.api.Redactor
import loop.core.trackers.NamespacedTracker
import loop.store.SqliteEventStore
import loop.tracker.files.FilesTracker
import loop.tracker.github.GitHubTracker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock

/**
 * bl-78: [openProject] opens the project of a `WORKFLOW.md` once for every command, in one order: the host's loader,
 * a redactor that learns the secrets of a best effort read before the load and those of the loaded workflow after
 * it, then the tracker and the store, each only when the command asks for it.
 */
class OpenProjectTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }
    private val secret = "Lp7secretQ93xZ0vK2mN5"
    private val githubToken = "ghp_bl78Token0123456789abcdefghijklmnop"

    private fun host(extraEnv: Map<String, String> = emptyMap()) =
        Host(
            mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()) + extraEnv,
            launcherProperty = null,
            userHome = "/tmp",
        )

    @Test
    fun `a files project opens with its files tracker and its store`() {
        val scope = RedactorScope(Redactor.NONE)

        val project = host().openProject(fixture.workflow.toString(), Access.WRITE, scope)

        assertEquals(ProjectId(fixture.projectId()), project.projectId)
        assertEquals("files", project.workflow.trackerKind)
        assertSame(project.redactor, scope.redactor)
        assertIs<FilesTracker>(assertIs<NamespacedTracker>(project.tracker()).native)
        assertEquals(fixture.loopHome.resolve(SqliteEventStore.FILE_NAME), project.storeFile)
        project.store().getOrThrow().use { store ->
            assertEquals(emptyList(), runBlocking { store.list(project.projectId) }.getOrThrow())
        }
    }

    @Test
    fun `a github project opens with its github tracker and its store`() {
        fixture.workflow.writeText(
            fixture.workflow
                .readText()
                .replace("kind: files", "kind: github\n  provider:\n    repo: octocat/Hello-World\n    token: \$GITHUB_TOKEN")
                .replace("  active_states: [todo]\n", ""),
        )
        val scope = RedactorScope(Redactor.NONE)

        val project = host(mapOf("GITHUB_TOKEN" to githubToken)).openProject(fixture.workflow.toString(), Access.READ, scope)

        assertEquals("github", project.workflow.trackerKind)
        assertIs<GitHubTracker>(assertIs<NamespacedTracker>(project.tracker()).native)
        assertEquals("[redacted:GITHUB_TOKEN]", scope.redactor.redact(githubToken))
        project.store().getOrThrow().use { store ->
            assertEquals(emptyList(), runBlocking { store.list(project.projectId) }.getOrThrow())
        }
    }

    @Test
    fun `bl-70 a project opened for read defers an unset tracker token, one opened for write refuses it`() {
        fixture.workflow.writeText(
            fixture.workflow
                .readText()
                .replace("kind: files", "kind: github\n  provider:\n    repo: octocat/Hello-World\n    token: \$GITHUB_TOKEN")
                .replace("  active_states: [todo]\n", ""),
        )
        val unset = "tracker.provider.token uses \$GITHUB_TOKEN, which is not set"

        val read = host().openProject(fixture.workflow.toString(), Access.READ, RedactorScope(Redactor.NONE))
        val write =
            assertFailsWith<StartupFailure> {
                host().openProject(fixture.workflow.toString(), Access.WRITE, RedactorScope(Redactor.NONE))
            }

        assertEquals(unset, read.trackerSecret?.sentence)
        assertEquals(unset, assertFailsWith<StartupFailure> { read.tracker() }.sentence)
        read.store().getOrThrow().close()
        assertEquals(unset, write.sentence)
    }

    @Test
    fun `a secret of the workflow is redacted from its load error`() {
        useSecretInFailingWorkflow()
        val scope = RedactorScope(Redactor.NONE)

        val failure =
            assertFailsWith<StartupFailure> {
                host(mapOf("LOOP_TEST_SECRET" to secret)).openProject(fixture.workflow.toString(), Access.READ, scope)
            }

        // The load error quotes the secret, and the redactor in scope already knows it.
        assertContains(failure.sentence, secret)
        val printed = scope.redactor.redact(failure.sentence)
        assertFalse(secret in printed, printed)
        assertContains(printed, "[redacted:LOOP_TEST_SECRET]")
    }

    @Test
    fun `a command prints the load error redacted and exits 1`() {
        useSecretInFailingWorkflow()

        val result = LoopCommand(host(mapOf("LOOP_TEST_SECRET" to secret))).test(listOf("status", fixture.workflow.toString()))

        assertEquals(1, result.statusCode, result.output)
        assertFalse(secret in result.output, result.output)
        assertEquals(
            "loop status: runner.kind \"[redacted:LOOP_TEST_SECRET]\" is not supported, use command, claude-code, codex-app-server or acp.\n",
            result.stderr,
        )
    }

    @Test
    fun `the redactor learns the secrets of the loaded workflow after the load`() {
        val prToken = "Lp7prTokenW4kZ8mN2qR6"
        fixture.writeWorkflow(
            extra =
                """
                |kinds:
                |  code:
                |    delivery:
                |      pull_request:
                |        repo: acme/widgets
                |        token: ${'$'}LOOP_PR_TOKEN
                |    verify:
                |      checks:
                |        - { type: command, run: "true" }
                """.trimMargin(),
        )
        val scope = RedactorScope(Redactor.NONE)

        host(mapOf("LOOP_PR_TOKEN" to prToken)).openProject(fixture.workflow.toString(), Access.READ, scope)

        // The best effort read knows tracker.provider only: the delivery token is learned from the loaded workflow.
        assertEquals("[redacted:LOOP_PR_TOKEN]", scope.redactor.redact(prToken))
    }

    @Test
    fun `a project opened for write redacts what its store writes, one opened for read does not`() {
        useSecretTokenRef()
        val env = mapOf("LOOP_TEST_SECRET" to secret)
        val writer = host(env).openProject(fixture.workflow.toString(), Access.WRITE, RedactorScope(Redactor.NONE))
        val reader = host(env).openProject(fixture.workflow.toString(), Access.READ, RedactorScope(Redactor.NONE))

        writer.store().getOrThrow().use { runBlocking { it.record(event(writer.projectId, "written-$secret")) }.getOrThrow() }
        reader.store().getOrThrow().use { runBlocking { it.record(event(reader.projectId, "read-$secret")) }.getOrThrow() }

        val identifiers = fixture.query("SELECT issue_identifier FROM events ORDER BY seq").map { it["issue_identifier"] }
        assertEquals(listOf("written-[redacted:LOOP_TEST_SECRET]", "read-$secret"), identifiers)
    }

    @Test
    fun `a store that cannot open names its file`() {
        fixture.loopHome.writeText("a file where the loop home folder should be")

        val project = host().openProject(fixture.workflow.toString(), Access.READ, RedactorScope(Redactor.NONE))

        val error = project.store().exceptionOrNull()
        assertContains(error?.message.orEmpty(), "cannot open the store at ${project.storeFile}: ")
    }

    /** `tracker.provider.token` names `$LOOP_TEST_SECRET`, so the redactor learns its value. */
    private fun useSecretTokenRef() {
        fixture.workflow.writeText(
            fixture.workflow.readText().replace("  kind: files\n", "  kind: files\n  provider:\n    token: \$LOOP_TEST_SECRET\n"),
        )
    }

    /** As [useSecretTokenRef], and `runner.kind` holds the secret's value, so the load fails quoting it. */
    private fun useSecretInFailingWorkflow() {
        useSecretTokenRef()
        fixture.workflow.writeText(fixture.workflow.readText().replace("  kind: command\n", "  kind: $secret\n"))
    }

    private fun event(
        projectId: ProjectId,
        identifier: String,
    ) = RecordedEvent(ts = Clock.System.now(), projectId = projectId, kind = "bl78_probe", issueIdentifier = identifier)
}
