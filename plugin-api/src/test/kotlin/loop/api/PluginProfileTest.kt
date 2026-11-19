package loop.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m3-05: a plugin profile and the check of a section against it. */
class PluginProfileTest {
    private val profile =
        PluginProfile(
            keys =
                listOf(
                    ProfileKey("command", ProfileType.STRING, required = true),
                    ProfileKey("timeout_ms", ProfileType.INTEGER, default = "1000"),
                    ProfileKey("labels", ProfileType.LIST),
                    ProfileKey("options", ProfileType.MAP),
                    ProfileKey("token", ProfileType.STRING),
                ),
            secrets = setOf("token"),
            limits = mapOf("timeout_ms" to 1L..60_000L),
        )

    private fun check(
        config: Map<String, Any?>,
        env: Map<String, String> = emptyMap(),
    ) = profile.check("runners.fake", config, env)

    @Test
    fun `a section that follows the profile passes with no warning`() {
        val report =
            check(
                mapOf(
                    "command" to "./agent.sh",
                    "timeout_ms" to "500",
                    "labels" to listOf("a"),
                    "options" to mapOf("x" to "y"),
                    "token" to "\$TOKEN",
                ),
                env = mapOf("TOKEN" to "ghp_value0123456789"),
            )

        assertNull(report.problem)
        assertEquals(emptyList(), report.unknownKeys)
    }

    @Test
    fun `a missing required key is named`() {
        val problem = assertIs<ProfileProblem.Missing>(check(emptyMap()).problem)

        assertEquals("runners.fake.command", problem.key)
        assertEquals("runners.fake.command is required", problem.message)
        assertFalse(problem.secret)
    }

    @Test
    fun `a blank or null required value counts as missing`() {
        assertIs<ProfileProblem.Missing>(check(mapOf("command" to "  ")).problem)
        assertIs<ProfileProblem.Missing>(check(mapOf("command" to null)).problem)
    }

    @Test
    fun `a value of the wrong type is named without its value`() {
        val notString = assertIs<ProfileProblem.WrongType>(check(mapOf("command" to listOf("ghp_secretish"))).problem)
        assertEquals("runners.fake.command must be a string", notString.message)

        val notInteger = assertIs<ProfileProblem.WrongType>(check(mapOf("command" to "x", "timeout_ms" to "soon")).problem)
        assertEquals("runners.fake.timeout_ms must be an integer", notInteger.message)

        assertEquals(
            "runners.fake.labels must be a list",
            check(mapOf("command" to "x", "labels" to "a")).problem?.message,
        )
        assertEquals(
            "runners.fake.options must be a map",
            check(mapOf("command" to "x", "options" to listOf("a"))).problem?.message,
        )
    }

    @Test
    fun `an integer may be written as a number or a string`() {
        assertNull(check(mapOf("command" to "x", "timeout_ms" to 500)).problem)
        assertNull(check(mapOf("command" to "x", "timeout_ms" to " 500 ")).problem)
    }

    @Test
    fun `bl-72 a boolean is true or false in any case, written bare or quoted, and nothing else`() {
        val flags = PluginProfile(listOf(ProfileKey("strict", ProfileType.BOOLEAN, default = "true")))

        fun problem(value: Any?) = flags.check("runners.fake", mapOf("strict" to value), emptyMap()).problem

        listOf("true", "false", "TRUE", " False ", true, false, null).forEach { assertNull(problem(it), "$it") }
        listOf("yes", "1", "on", 1, listOf("true")).forEach { value ->
            val wrong = assertIs<ProfileProblem.WrongType>(problem(value), "$value")
            assertEquals("runners.fake.strict must be a boolean", wrong.message)
        }
    }

    @Test
    fun `a value out of its limits is named with the range`() {
        val problem = assertIs<ProfileProblem.OutOfLimits>(check(mapOf("command" to "x", "timeout_ms" to "0")).problem)

        assertEquals("runners.fake.timeout_ms", problem.key)
        assertEquals("runners.fake.timeout_ms must be between 1 and 60000, got 0", problem.message)
        assertIs<ProfileProblem.OutOfLimits>(check(mapOf("command" to "x", "timeout_ms" to "60001")).problem)
    }

    @Test
    fun `an unset secret is named by its key and variable`() {
        val problem = assertIs<ProfileProblem.UnresolvedSecret>(check(mapOf("command" to "x", "token" to "\$TOKEN")).problem)

        assertEquals("runners.fake.token", problem.key)
        assertEquals("TOKEN", problem.envName)
        assertFalse(problem.empty)
        assertTrue(problem.secret)
        assertEquals("runners.fake.token uses \$TOKEN, which is not set", problem.message)
    }

    @Test
    fun `a blank secret is named by its variable and its value is never printed`() {
        val value = " \t "
        val problem =
            assertIs<ProfileProblem.UnresolvedSecret>(
                check(mapOf("command" to "x", "token" to "\$TOKEN"), env = mapOf("TOKEN" to value)).problem,
            )

        assertTrue(problem.empty)
        assertEquals("runners.fake.token uses \$TOKEN, which is empty", problem.message)
        assertFalse(value in problem.message)
    }

    @Test
    fun `a secret written as a literal is left to the plugin`() {
        assertNull(check(mapOf("command" to "x", "token" to "ghp_literal0123456789")).problem)
    }

    @Test
    fun `a missing required secret is flagged as a secret`() {
        val secretProfile = PluginProfile(listOf(ProfileKey("token", ProfileType.STRING, required = true)), secrets = setOf("token"))

        val problem = assertIs<ProfileProblem.Missing>(secretProfile.check("tracker.provider", emptyMap(), emptyMap()).problem)

        assertTrue(problem.secret)
        assertEquals("tracker.provider.token is required", problem.message)
    }

    @Test
    fun `unknown keys are listed by their full key, in the order written`() {
        val report = check(mapOf("comand" to "x", "command" to "y", "extra" to 1))

        assertNull(report.problem)
        assertEquals(listOf("runners.fake.comand", "runners.fake.extra"), report.unknownKeys)
    }

    @Test
    fun `secrets and limits must name keys of the profile, and limits integer keys`() {
        assertFailsWith<IllegalArgumentException> { PluginProfile(emptyList(), secrets = setOf("token")) }
        assertFailsWith<IllegalArgumentException> {
            PluginProfile(listOf(ProfileKey("command", ProfileType.STRING)), limits = mapOf("command" to 1L..2L))
        }
        assertFailsWith<IllegalArgumentException> {
            PluginProfile(listOf(ProfileKey("command", ProfileType.STRING), ProfileKey("command", ProfileType.STRING)))
        }
    }
}
