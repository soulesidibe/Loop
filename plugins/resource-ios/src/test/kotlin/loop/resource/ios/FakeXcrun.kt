package loop.resource.ios

import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.time.Duration

/**
 * The fake `xcrun` of the tests (m4-27, the spec's testing decisions): the script `fake-xcrun/xcrun` of the test
 * resources, installed in [root] and put first on the `PATH` of the provider, answering like `xcrun simctl` over the
 * simulators of a folder, which the tests read and seed directly, never through the provider.
 */
class FakeXcrun(
    private val root: Path,
) {
    private val bin: Path = root.resolve("bin").createDirectories()
    private val state: Path = root.resolve("state").createDirectories()
    private val devices: Path = state.resolve("devices").createDirectories()
    private var seeded = 0

    init {
        val script = bin.resolve("xcrun")
        script.writeText(FakeXcrun::class.java.getResource("/fake-xcrun/xcrun")!!.readText())
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The environment of a provider over this fake, with [extra] such as `FAKE_XCRUN_FAIL`. */
    fun environment(extra: Map<String, String> = emptyMap()): Map<String, String> =
        System.getenv() + mapOf("PATH" to "$bin:${System.getenv("PATH")}", "FAKE_XCRUN_STATE" to state.toString()) + extra

    /** The pids [provider] reads as alive, [OWN_PID] among them. */
    val alive: MutableSet<Long> = mutableSetOf(OWN_PID)

    /** A provider over this fake, with [extra] in its environment, running as the process [pid]. */
    internal fun provider(
        extra: Map<String, String> = emptyMap(),
        commandTimeout: Duration = IosSimulatorProvider.COMMAND_TIMEOUT,
        pid: Long = OWN_PID,
    ): IosSimulatorProvider = IosSimulatorProvider(Xcrun(environment(extra)), commandTimeout, pid) { it in alive }

    /** Every call so far, each as its arguments after `xcrun`. */
    fun calls(): List<List<String>> =
        state
            .resolve("calls")
            .takeIf { it.exists() }
            ?.readLines()
            ?.map { it.split('\t') } ?: emptyList()

    /** The simulators of the machine, by UDID: the seeded ones after those the fake `xcrun` created. */
    internal fun simulators(): List<Simulator> =
        devices.listDirectoryEntries().sortedBy { it.name }.map { file ->
            val (name, state) = file.readLines()
            Simulator(file.name, name, state)
        }

    /** The simulator the provider of [pid] made for the resource [id], or null. */
    internal fun loop(
        id: String,
        pid: Long = OWN_PID,
    ): Simulator? = named("loop-sim-$id-$pid")

    /** The simulator named [name], or null. */
    internal fun named(name: String): Simulator? = simulators().firstOrNull { it.name == name }

    /** Adds a simulator the provider did not create, named [name], in [state], and returns its UDID. */
    fun seed(
        name: String,
        state: String = "Shutdown",
    ): String {
        seeded++
        val udid = "SEED0000-0000-0000-0000-%012d".format(seeded)
        devices.resolve(udid).writeText("$name\n$state\n")
        return udid
    }

    /** Sets the state of the simulator [udid], as a person in Simulator.app would. */
    fun setState(
        udid: String,
        state: String,
    ) {
        val file = devices.resolve(udid)
        val name = file.readLines().first()
        file.writeText("$name\n$state\n")
    }

    companion object {
        /** The pid of the providers of the tests, unless a test gives another. */
        const val OWN_PID = 4242L
    }
}
