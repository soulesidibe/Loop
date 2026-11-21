package loop.cli

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated start script's choice of Java (bl-159): `$APP_HOME/runtime/bin/java` first, then JAVA_HOME, then
 * java on the PATH, and JAVA_HOME never exported, since agents inherit Loop's environment. Runs a copy of the
 * installed `bin/loop` against fake java executables that print which one ran and the JAVA_HOME they saw.
 */
class StartScriptTest {
    @TempDir
    lateinit var root: Path

    private val appHome: Path by lazy {
        root.resolve("app").also { home ->
            val script = home.resolve("bin/loop")
            script.parent.createDirectories()
            Files.copy(Path(System.getProperty("loop.launcher")), script)
            executable(script)
        }
    }

    private fun executable(file: Path) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** A fake `java` at [file] that prints [label] and the JAVA_HOME it inherited. */
    private fun fakeJava(
        file: Path,
        label: String,
    ): Path {
        file.parent.createDirectories()
        file.writeText("#!/bin/sh\necho \"java=$label JAVA_HOME=\${JAVA_HOME-unset}\"\n")
        executable(file)
        return file
    }

    /** `bin/loop --version` with only PATH (system folders after [pathFirst]) and, when given, JAVA_HOME. */
    private fun launch(
        javaHome: Path? = null,
        pathFirst: Path? = null,
    ): String {
        val process =
            ProcessBuilder(appHome.resolve("bin/loop").absolutePathString(), "--version")
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = listOfNotNull(pathFirst?.absolutePathString(), "/usr/bin", "/bin").joinToString(":")
                    if (javaHome != null) environment()["JAVA_HOME"] = javaHome.absolutePathString()
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "bin/loop did not exit" }
        assertEquals(0, process.exitValue(), output)
        return output.trim()
    }

    @Test
    fun `the runtime folder's java comes first, with JAVA_HOME unset`() {
        fakeJava(appHome.resolve("runtime/bin/java"), "runtime")

        assertEquals("java=runtime JAVA_HOME=unset", launch())
    }

    @Test
    fun `without a runtime folder JAVA_HOME's java runs`() {
        val jdk = root.resolve("jdk")
        fakeJava(jdk.resolve("bin/java"), "java_home")

        assertEquals("java=java_home JAVA_HOME=$jdk", launch(javaHome = jdk))
    }

    @Test
    fun `without a runtime folder or JAVA_HOME the java on the PATH runs`() {
        val bin = root.resolve("path-bin")
        fakeJava(bin.resolve("java"), "path")

        assertEquals("java=path JAVA_HOME=unset", launch(pathFirst = bin))
    }

    @Test
    fun `the runtime folder wins over JAVA_HOME, which reaches the child unchanged`() {
        fakeJava(appHome.resolve("runtime/bin/java"), "runtime")
        val jdk = root.resolve("jdk")
        fakeJava(jdk.resolve("bin/java"), "java_home")

        assertEquals("java=runtime JAVA_HOME=$jdk", launch(javaHome = jdk))
    }
}
