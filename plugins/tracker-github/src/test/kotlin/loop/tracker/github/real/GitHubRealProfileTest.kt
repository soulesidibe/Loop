package loop.tracker.github.real

import loop.api.RealIntegration
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m3-25, spec 17.8 items 3 and 4: the gate of the `github` target, run with no real token, so each path runs in every
 * build.
 */
class GitHubRealProfileTest {
    private val variable = GitHubRealProfile.TOKEN_VARIABLE

    @Test
    fun `m3-25 17_8 item 3 the github target is skipped when the profile is unset or names only codex`() {
        listOf(emptyMap(), mapOf(RealIntegration.VARIABLE to "codex")).forEach { env ->
            assertThrows<TestAbortedException> {
                RealIntegration.require("github", env) { GitHubRealProfile.missing(env) }
            }
        }
    }

    @Test
    fun `m3-25 17_8 item 4 an enabled github target with the token unset fails naming the variable`() {
        val env = mapOf(RealIntegration.VARIABLE to "github")

        val error = assertThrows<AssertionError> { RealIntegration.require("github", env) { GitHubRealProfile.missing(env) } }

        assertTrue("LOOP_REAL_INTEGRATION names github but these are missing" in error.message!!, error.message)
        assertTrue("\$$variable, which is not set" in error.message!!, error.message)
    }

    @Test
    fun `m3-25 17_8 item 4 an enabled github target with a blank token fails naming the variable`() {
        val env = mapOf(RealIntegration.VARIABLE to "github", variable to "  ")

        val error = assertThrows<AssertionError> { RealIntegration.require("github", env) { GitHubRealProfile.missing(env) } }

        assertTrue("\$$variable, which is empty" in error.message!!, error.message)
    }

    @Test
    fun `m3-25 a token with whitespace inside fails without showing its value`() {
        val env = mapOf(RealIntegration.VARIABLE to "github", variable to "github_pat_secret value")

        val missing = GitHubRealProfile.missing(env).single()

        assertTrue(variable in missing, missing)
        assertFalse("secret" in missing, missing)
    }

    @Test
    fun `m3-25 an enabled github target with a token lets the smoke test run, scoped to the run label`() {
        val env = mapOf(RealIntegration.VARIABLE to "github", variable to " github_pat_test ")

        assertEquals(emptyList(), GitHubRealProfile.missing(env))
        val settings = GitHubRealProfile.settings(env, "loop-real-0123abcd").getOrThrow()
        assertEquals("Soules-Studio-Ltd" to "loop-playground", settings.owner to settings.repo)
        assertEquals(listOf("loop-real-0123abcd"), settings.scopeLabels)
        assertEquals("github_pat_test", settings.token)
        assertEquals(variable, settings.tokenEnvName)
    }
}
