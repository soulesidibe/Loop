package loop.runner.claudecode

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The startup checks of `loop run` with `runner.kind: claude-code`. */
class ClaudeCodePreflightTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `running as root is a startup error`() {
        val error = ClaudeCodePreflight(uid = { 0 }, managedSettings = dir.resolve("absent.json")).check().error

        assertContains(assertNotNull(error), "root")
    }

    @Test
    fun `bypass mode disabled by managed settings is a startup error that says how to fix it`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("""{"permissions":{"disableBypassPermissionsMode":"disable"}}""")

        val error = ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check().error

        assertEquals(
            "the claude-code runner needs bypassPermissions mode, but $managed sets " +
                "permissions.disableBypassPermissionsMode to \"disable\". Remove that setting or use runner.kind: command",
            error,
        )
    }

    @Test
    fun `a regular user with managed settings that keep bypass mode passes without warning`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("""{"permissions":{"deny":["WebFetch"]}}""")

        assertEquals(PreflightResult(), ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check())
        assertEquals(PreflightResult(), ClaudeCodePreflight(uid = { 501 }, managedSettings = dir.resolve("absent.json")).check())
    }

    @Test
    fun `an unparseable managed settings file passes with a warning naming the file and the error`() {
        val managed = dir.resolve("managed-settings.json")
        managed.writeText("{not json")

        val result = ClaudeCodePreflight(uid = { 501 }, managedSettings = managed).check()

        assertNull(result.error)
        val warning = result.warnings.single()
        assertContains(warning, managed.toString())
        assertContains(warning, "Unexpected JSON token")
    }

    @Test
    fun `an unknown uid passes with a warning`() {
        val result = ClaudeCodePreflight(uid = { null }, managedSettings = dir.resolve("absent.json")).check()

        assertNull(result.error)
        assertContains(result.warnings.single(), "root")
    }

    /** A preflight past the uid and managed settings checks, whose `--version` probe gives [probe]. */
    private fun versionCheck(probe: VersionProbe): PreflightResult =
        ClaudeCodePreflight(
            command = "claude",
            uid = { 501 },
            managedSettings = dir.resolve("absent.json"),
            hostEnv = mapOf("PATH" to "/opt/claude/bin"),
            pinnedVersion = "2.1.330",
            versionProbe = { argv, env ->
                assertEquals(listOf("claude", "--version"), argv)
                assertEquals(mapOf("PATH" to "/opt/claude/bin"), env)
                probe
            },
        ).check()

    @Test
    fun `bl-210 a version of the pinned major passes and is recorded`() {
        assertEquals(
            PreflightResult(fields = mapOf("runner_version" to "2.1.330")),
            versionCheck(VersionProbe.Ran(0, "2.1.330 (Claude Code)\n")),
        )
        // Only the major is compared, so a newer minor or patch release starts.
        assertEquals(
            PreflightResult(fields = mapOf("runner_version" to "2.9.1")),
            versionCheck(VersionProbe.Ran(0, "2.9.1 (Claude Code)\n")),
        )
    }

    @Test
    fun `bl-210 another major version is a startup error naming the found and pinned versions`() {
        val result = versionCheck(VersionProbe.Ran(0, "3.0.1 (Claude Code)\n"))

        assertEquals("Claude Code 3.0.1 is not of the pinned major version 2 (pinned 2.1.330)", result.error)
        assertEquals(mapOf("runner_version" to "3.0.1"), result.fields)
    }

    @Test
    fun `bl-210 an unreadable version output is a warning and the run goes on`() {
        val unreadable = versionCheck(VersionProbe.Ran(0, "Claude Code, a fine release\n"))
        assertNull(unreadable.error)
        assertEquals(
            "could not read a version in the output of claude --version, so the claude-code runner did not check " +
                "that Claude Code is of the pinned major version 2 (pinned 2.1.330)",
            unreadable.warnings.single(),
        )
        assertEquals(emptyMap(), unreadable.fields)

        val failed = versionCheck(VersionProbe.Ran(1, "2.1.330 (Claude Code)\n"))
        assertNull(failed.error)
        assertEquals(
            "claude --version exited 1, so the claude-code runner did not check " +
                "that Claude Code is of the pinned major version 2 (pinned 2.1.330)",
            failed.warnings.single(),
        )

        val notRun = versionCheck(VersionProbe.NotRun("No such file or directory"))
        assertNull(notRun.error)
        assertEquals(
            "could not run claude --version, so the claude-code runner did not check " +
                "that Claude Code is of the pinned major version 2 (pinned 2.1.330): No such file or directory",
            notRun.warnings.single(),
        )
    }

    @Test
    fun `bl-210 without a command no version is checked`() {
        val probed =
            ClaudeCodePreflight(
                uid = { 501 },
                managedSettings = dir.resolve("absent.json"),
                versionProbe = { _, _ -> error("probed") },
            ).check()

        assertEquals(PreflightResult(), probed)
    }

    @Test
    fun `bl-210 the default probe runs the command with the host env and reads stdout and stderr`() {
        val claude = dir.resolve("claude")
        claude.writeText(
            "#!/bin/sh\nif [ \"$1\" = --version ]; then echo \"${'$'}LOOP_BL210_VERSION (Claude Code)\" >&2; exit 0; fi\nexit 7\n",
        )
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"))

        val probe = ClaudeCodePreflight.runVersion(listOf(claude.toString(), "--version"), mapOf("LOOP_BL210_VERSION" to "3.0.1"))
        assertEquals(VersionProbe.Ran(0, "3.0.1 (Claude Code)\n"), probe)

        val missing = ClaudeCodePreflight.runVersion(listOf(dir.resolve("absent").toString(), "--version"), emptyMap())
        assertContains(assertIs<VersionProbe.NotRun>(missing).reason, "absent")
    }
}
