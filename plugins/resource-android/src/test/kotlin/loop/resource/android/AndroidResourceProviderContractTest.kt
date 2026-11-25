package loop.resource.android

import loop.api.ResourceDeclaration
import loop.api.ResourceProvider
import loop.api.ResourceProviderContract
import org.junit.jupiter.api.AfterEach
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * m4-28: [ResourceProviderContract] against the Android provider for an `avd`, over a fake machine whose fake
 * `emulator` boots it. A holder that leaves the emulator up is played by an `emu kill` that answers OK and kills nothing.
 */
class AndroidAvdContractTest : ResourceProviderContract() {
    private val root = Files.createTempDirectory("loop-android-avd")
    private val android = FakeAndroid(root)

    @AfterEach
    fun stopFakeEmulators() {
        android.shutdown()
        // The fake emulators see their device gone within 20 ms and exit before the folder goes.
        Thread.sleep(200)
        root.toFile().deleteRecursively()
    }

    override fun provider(): ResourceProvider = android.provider(stop = 1.seconds)

    override fun resource(): ResourceDeclaration = ResourceDeclaration(kind = "avd", id = "pixel_api35", can = listOf("ui tests"))

    override fun leftBehind(resource: ResourceDeclaration): Boolean = android.emulatorsOf(resource.id).isNotEmpty()

    override fun dirty(resource: ResourceDeclaration): Boolean {
        android.flag("sticky")
        return true
    }
}

/** m4-28: [ResourceProviderContract] against the Android provider for a connected `android-device`, which leaves nothing behind. */
class AndroidDeviceContractTest : ResourceProviderContract() {
    private val root = Files.createTempDirectory("loop-android-device")
    private val android = FakeAndroid(root).apply { connect("R58M123") }

    @AfterEach
    fun removeMachine() {
        root.toFile().deleteRecursively()
    }

    override fun provider(): ResourceProvider = android.provider()

    override fun resource(): ResourceDeclaration = ResourceDeclaration(kind = "android-device", id = "R58M123")

    override fun leftBehind(resource: ResourceDeclaration): Boolean = false
}
