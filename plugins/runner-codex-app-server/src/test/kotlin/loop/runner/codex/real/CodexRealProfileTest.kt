package loop.runner.codex.real

import loop.api.RealIntegration
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m3-24, spec 17.8 items 3 and 4: the gate of the `codex` target, run with no real codex. A fake `codex` script stands
 * in for the program, its `login status` exit code chosen per test, so each path runs in every build.
 */
class CodexRealProfileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `m3-24 17_8 item 3 the codex target is skipped when the profile is unset or names only github`() {
        listOf(emptyMap(), mapOf(RealIntegration.VARIABLE to "github")).forEach { env ->
            assertThrows<TestAbortedException> {
                RealIntegration.require("codex", env) { CodexRealProfile.missing(env) }
            }
        }
    }

    @Test
    fun `m3-24 17_8 item 4 an enabled codex target with no codex on PATH fails naming the program`() {
        val env = enabled(path = dir.resolve("empty").createDirectory().toString())

        val error = assertThrows<AssertionError> { RealIntegration.require("codex", env) { CodexRealProfile.missing(env) } }

        assertTrue("the program codex of the command `codex app-server` on PATH" in error.message!!, error.message)
    }

    @Test
    fun `m3-24 17_8 item 4 an enabled codex target with no codex login fails naming the login and CODEX_HOME`() {
        val bin = fakeCodex(loginExit = 1)
        val home = dir.resolve("codex-home").createDirectory()
        val env = enabled(path = bin.toString(), codexHome = home.toString())

        val error = assertThrows<AssertionError> { RealIntegration.require("codex", env) { CodexRealProfile.missing(env) } }

        assertTrue("a codex login: `codex login status` exited 1 (CODEX_HOME=$home)" in error.message!!, error.message)
    }

    @Test
    fun `m3-24 an enabled codex target with a login lets the smoke test run`() {
        val env = enabled(path = fakeCodex(loginExit = 0).toString())

        assertEquals(emptyList(), CodexRealProfile.missing(env))
    }

    @Test
    fun `m3-24 LOOP_REAL_CODEX_COMMAND picks the program and its words, a model among them`() {
        val program = fakeCodex(loginExit = 0).resolve("codex")
        val env =
            enabled(path = dir.resolve("empty").createDirectory().toString()) +
                (CodexRealProfile.COMMAND_VARIABLE to "$program app-server -c model=gpt-6.1-sol")

        assertEquals("$program app-server -c model=gpt-6.1-sol", CodexRealProfile.command(env))
        assertEquals(emptyList(), CodexRealProfile.missing(env))
        assertEquals("codex app-server", CodexRealProfile.command(mapOf(CodexRealProfile.COMMAND_VARIABLE to " ")))
    }

    @Test
    fun `m3-24 a command the runner cannot split fails naming the variable`() {
        val env = enabled(path = "") + (CodexRealProfile.COMMAND_VARIABLE to "codex app-server | tee log")

        val missing = CodexRealProfile.missing(env).single()

        assertTrue(CodexRealProfile.COMMAND_VARIABLE in missing, missing)
    }

    /** An env that names the codex target, with [path] as `PATH` and [codexHome] as `CODEX_HOME` when given. */
    private fun enabled(
        path: String,
        codexHome: String? = null,
    ): Map<String, String> =
        buildMap {
            put(RealIntegration.VARIABLE, "codex")
            put("PATH", path)
            codexHome?.let { put("CODEX_HOME", it) }
        }

    /** A folder holding a `codex` script whose `login status` exits [loginExit]. */
    private fun fakeCodex(loginExit: Int): Path {
        val bin = dir.resolve("bin-$loginExit").createDirectory()
        bin.resolve("codex").apply {
            writeText("#!/bin/sh\n[ \"\$1 \$2\" = \"login status\" ] && exit $loginExit\nexit 2\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        return bin
    }
}
