package loop.tracker.github

import loop.api.ProfileKey
import loop.api.ProfileType
import loop.api.TrackerConfigException
import loop.api.TrackerContext
import loop.api.TrackerProvider
import java.util.ServiceLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** m3-05: the `github` tracker plugin, registered in `META-INF/services/loop.api.TrackerProvider`. */
class GitHubTrackerProviderTest {
    private val provider = GitHubTrackerProvider()

    @Test
    fun `it is found with ServiceLoader under the github kind`() {
        val kinds = ServiceLoader.load(TrackerProvider::class.java).map { it.kind }

        assertEquals(listOf("github"), kinds)
    }

    @Test
    fun `its profile declares every provider key, the token as its secret`() {
        assertEquals(
            listOf(
                ProfileKey("repo", ProfileType.STRING, required = true),
                ProfileKey("token", ProfileType.STRING, required = true),
                ProfileKey("api_url", ProfileType.STRING, default = "https://api.github.com"),
                ProfileKey("labels", ProfileType.LIST, default = "empty"),
                ProfileKey("handoff_labels", ProfileType.MAP, default = "review: needs-review, needs_info: needs-info"),
            ),
            provider.profile.keys,
        )
        assertEquals(setOf("token"), provider.profile.secrets)
        assertEquals(emptyMap(), provider.profile.limits)
    }

    @Test
    fun `its default states are open and closed, its defined states add the handoff states`() {
        assertEquals(listOf("open"), provider.defaultActiveStates)
        assertEquals(listOf("closed"), provider.defaultTerminalStates)
        assertEquals(GitHubTracker.DEFINED_STATES, provider.definedStates)
    }

    @Test
    fun `its settings reader keeps the spec 11_4 categories`() {
        val missing = assertFailsWith<TrackerConfigException> { provider.parse(mapOf("repo" to "o/r"), emptyMap()) }
        assertEquals("missing_tracker_secret", missing.category)
        assertEquals("tracker.provider.token is required", missing.message)

        val invalid = assertFailsWith<TrackerConfigException> { provider.parse(mapOf("repo" to "nope", "token" to "t"), emptyMap()) }
        assertEquals("invalid_tracker_config", invalid.category)
    }

    @Test
    fun `it builds a github tracker from its settings and the required labels of the context`() {
        val settings = provider.parse(mapOf("repo" to "o/r", "token" to "\$TOKEN"), mapOf("TOKEN" to "ghp_provider0123456789"))

        val dir = createTempDirectory("loop-github-provider")
        assertIs<GitHubTracker>(provider.create(settings, TrackerContext(dir, dir, requiredLabels = { listOf("ready") })))
        assertFailsWith<IllegalArgumentException> { provider.create(null, TrackerContext(dir, dir)) }
    }
}
