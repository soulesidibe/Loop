package loop.cli

import loop.api.AttemptId
import loop.api.ResourceDeclaration
import loop.api.ResourceHolder
import loop.api.ResourceProvider
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m4-24: resource providers are found with `ServiceLoader` on `loop.api.ResourceProvider`, like the runner and tracker
 * plugins. [FakeResourceProvider] registers through the test resource `META-INF/services/loop.api.ResourceProvider`.
 */
class ResourcePluginsTest {
    @TempDir
    lateinit var root: Path

    private val fixture by lazy { LoopFixture(root) }

    @Test
    fun `a fake provider registered through a test services file is loaded and serves its kinds`() {
        val plugins = ResourcePlugins.discover(root.resolve("no-plugins")).getOrThrow()

        // m4-27 and m4-28: the iOS and Android providers, on the runtime classpath, serve their kinds beside the test's fake.
        assertTrue(plugins.kinds.containsAll(listOf("fake-device", "fake-emulator", "ios-simulator")), "${plugins.kinds}")
        assertEquals("loop.resource.ios.IosSimulatorProvider", plugins.provider("ios-simulator")?.javaClass?.name)
        assertIs<FakeResourceProvider>(plugins.provider("fake-device"))
        assertIs<FakeResourceProvider>(plugins.provider("fake-emulator"))
        assertNull(plugins.provider("display"))
        assertEquals(true, plugins.serves("fake-emulator").getOrThrow())
        assertEquals(false, plugins.serves("display").getOrThrow())
        assertEquals(
            mapOf("serial" to "fake-pixel"),
            plugins.provider("fake-device")!!.prepare(ResourceDeclaration("fake-device", "pixel"), AttemptId("a1")),
        )
    }

    @Test
    fun `m4-28 the Android provider of plugins resource-android is on the classpath and serves android-device and avd`() {
        val plugins = ResourcePlugins.discover(root.resolve("no-plugins")).getOrThrow()

        val android = plugins.provider("avd")
        assertEquals("loop.resource.android.AndroidResourceProvider", android?.javaClass?.name, "${plugins.kinds}")
        assertEquals(setOf("android-device", "avd"), android!!.kinds)
        assertEquals(android, plugins.provider("android-device"))
    }

    @Test
    fun `two providers serving one kind fail startup naming both, and a provider serving no kind fails too`() {
        val other =
            object : ResourceProvider by FakeResourceProvider() {
                override val kinds = setOf("fake-emulator", "avd")
            }
        val none =
            object : ResourceProvider by FakeResourceProvider() {
                override val kinds = emptySet<String>()
            }

        val twice =
            ResourcePlugins.of(
                listOf(ResourcePlugins.Found(FakeResourceProvider(), "classpath"), ResourcePlugins.Found(other, "/p/other.jar")),
            )
        val empty = ResourcePlugins.of(listOf(ResourcePlugins.Found(none, "/p/none.jar")))

        assertEquals(
            "two resource plugins serve the resource kind \"fake-emulator\": loop.cli.FakeResourceProvider from the classpath and " +
                "${other.javaClass.name} from /p/other.jar",
            twice.exceptionOrNull()?.message,
        )
        assertEquals("the resource plugin ${none.javaClass.name} from /p/none.jar serves no kind", empty.exceptionOrNull()?.message)
    }

    @Test
    fun `the host's workflow loader names a resource kind no provider serves and stays quiet for a served one`() {
        val text = fixture.workflow.readText()
        fixture.workflow.writeText(
            text.replace("polling:\n", "resources:\n  - { kind: fake-device, id: pixel }\n  - { kind: display, id: main }\npolling:\n"),
        )
        val host =
            Host(
                mapOf("LOOP_HOME" to fixture.loopHome.toString(), "HOME" to fixture.homeDir.toString()),
                launcherProperty = null,
                userHome = "/tmp",
            )

        val workflow = host.workflowLoader().load(fixture.workflow).getOrThrow()

        assertEquals(listOf("fake-device", "display"), workflow.config.resources.map { it.kind })
        assertEquals(
            listOf("resources kind \"display\" has no resource provider, Loop holds its resources as plain locks"),
            workflow.configWarnings,
        )
    }
}

/**
 * A resource provider of the cli tests, registered on the test classpath, serving two kinds. Every instance adds the
 * holders of each `recover` call to [recovered], and each `prepare`, `release` and `check` to [calls] with the id of the process
 * that ran it (m4-25), both shared by the tests of the JVM, which run in parallel: a test finds its own calls by a
 * resource id only it uses.
 */
class FakeResourceProvider : ResourceProvider {
    override val kinds: Set<String> = setOf("fake-device", "fake-emulator")

    override fun prepare(
        resource: ResourceDeclaration,
        attemptId: AttemptId,
    ): Map<String, String> {
        calls += Call("prepare", resource.id, ProcessHandle.current().pid())
        return mapOf("serial" to "fake-${resource.id}")
    }

    override fun release(resource: ResourceDeclaration) {
        calls += Call("release", resource.id, ProcessHandle.current().pid())
    }

    override fun readOnlyCheck(resource: ResourceDeclaration): List<String> {
        calls += Call("check", resource.id, ProcessHandle.current().pid())
        return if (resource.id in changed) listOf("fake difference on ${resource.id}") else emptyList()
    }

    override fun recover(liveHolders: List<ResourceHolder>) {
        recovered += listOf(liveHolders.toList())
    }

    companion object {
        /** The holders of each `recover` call of any instance, in call order. */
        val recovered: MutableList<List<ResourceHolder>> = CopyOnWriteArrayList()

        /** Each `prepare`, `release` and, since m4-26, `check` of any instance, in call order (m4-25). */
        val calls: MutableList<Call> = CopyOnWriteArrayList()

        /** The resource ids whose read only check finds a difference (m4-26), the others pass. */
        val changed: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()
    }

    /** One provider call: `prepare`, `release` or `check` of the resource [id], run by the process [pid]. */
    data class Call(
        val op: String,
        val id: String,
        val pid: Long,
    )
}
