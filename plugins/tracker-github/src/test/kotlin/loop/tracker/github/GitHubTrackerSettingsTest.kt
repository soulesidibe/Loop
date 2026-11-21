package loop.tracker.github

import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubTrackerSettingsTest {
    private val token = "ghp_0123456789abcdefghijklmnopqrstuvwxyz"

    private fun parse(
        provider: Map<String, Any?>,
        env: Map<String, String> = emptyMap(),
    ) = GitHubTrackerSettings.parse(provider, env)

    private fun failure(
        provider: Map<String, Any?>,
        env: Map<String, String> = emptyMap(),
    ): GitHubConfigError = assertIs<GitHubConfigError>(parse(provider, env).exceptionOrNull())

    @Test
    fun `repo and a literal token give the settings with the defaults`() {
        val settings = parse(mapOf("repo" to "Soules-Studio-Ltd/example-app", "token" to token)).getOrThrow()

        assertEquals("Soules-Studio-Ltd", settings.owner)
        assertEquals("example-app", settings.repo)
        assertEquals(token, settings.token)
        assertEquals(URI("https://api.github.com"), settings.apiUrl)
        assertEquals(emptyList(), settings.scopeLabels)
        assertNull(settings.tokenEnvName)
    }

    @Test
    fun `17_1 item 10 a dollar var token is read from the environment`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to "\$GITHUB_TOKEN"), env = mapOf("GITHUB_TOKEN" to token)).getOrThrow()

        assertEquals(token, settings.token)
        assertEquals("GITHUB_TOKEN", settings.tokenEnvName)
    }

    @Test
    fun `an unset token variable is a missing secret named without a value`() {
        val error = failure(mapOf("repo" to "o/r", "token" to "\$GITHUB_TOKEN"), env = mapOf("OTHER" to token))

        assertIs<GitHubConfigError.MissingSecret>(error)
        assertEquals("missing_tracker_secret", error.category)
        assertEquals("tracker.provider.token uses \$GITHUB_TOKEN, which is not set", error.message)
    }

    @Test
    fun `an empty or blank token variable is a missing secret too`() {
        for (value in listOf("", "  ")) {
            val error = failure(mapOf("repo" to "o/r", "token" to "\$GITHUB_TOKEN"), env = mapOf("GITHUB_TOKEN" to value))

            assertIs<GitHubConfigError.MissingSecret>(error)
            assertEquals("tracker.provider.token uses \$GITHUB_TOKEN, which is empty", error.message)
        }
    }

    @Test
    fun `a missing or blank token is a missing secret`() {
        for (provider in listOf(mapOf("repo" to "o/r"), mapOf("repo" to "o/r", "token" to " "), mapOf("repo" to "o/r", "token" to null))) {
            val error = failure(provider)

            assertIs<GitHubConfigError.MissingSecret>(error, provider.toString())
            assertEquals("tracker.provider.token is required", error.message)
        }
    }

    @Test
    fun `a token with whitespace or a control character inside is refused without showing it`() {
        val bad =
            listOf(
                "ghp_abc def",
                "ghp_abc\tdef",
                "ghp_abc\ndef",
                "ghp_abc\rdef",
                "ghp_abc\u0000def",
                "ghp_abc\u007fdef",
                "ghp_abc\u00a0def",
            )
        for (value in bad) {
            val literal = failure(mapOf("repo" to "o/r", "token" to value))
            assertIs<GitHubConfigError.Invalid>(literal, value)
            assertEquals("invalid_tracker_config", literal.category)
            assertEquals("tracker.provider.token must not hold whitespace or a control character", literal.message)

            val fromEnv = failure(mapOf("repo" to "o/r", "token" to "\$GITHUB_TOKEN"), env = mapOf("GITHUB_TOKEN" to value))
            assertIs<GitHubConfigError.Invalid>(fromEnv, value)
            assertEquals(
                "tracker.provider.token uses \$GITHUB_TOKEN, whose value holds whitespace or a control character",
                fromEnv.message,
            )
            for (error in listOf(literal, fromEnv)) {
                assertFalse("ghp_abc" in error.message!!, error.message)
                assertFalse("def" in error.message!!, error.message)
            }
        }
    }

    @Test
    fun `whitespace around a token variable is trimmed, as around a literal`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to "\$GITHUB_TOKEN"), env = mapOf("GITHUB_TOKEN" to " $token\n")).getOrThrow()

        assertEquals(token, settings.token)
    }

    @Test
    fun `a dollar inside a longer token is kept as written`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to "abc\$DEF")).getOrThrow()

        assertEquals("abc\$DEF", settings.token)
        assertNull(settings.tokenEnvName)
    }

    @Test
    fun `repo must be owner slash name`() {
        assertEquals("tracker.provider.repo is required", failure(mapOf("token" to token)).message)
        for (repo in listOf("example-app", "a/b/c", "/example-app", "owner/", "own er/x", "o/r?x=1")) {
            val error = failure(mapOf("repo" to repo, "token" to token))

            assertIs<GitHubConfigError.Invalid>(error, repo)
            assertEquals("invalid_tracker_config", error.category)
            assertEquals("tracker.provider.repo must be owner/name, got \"$repo\"", error.message)
        }
    }

    @Test
    fun `api_url takes an http or https address without a trailing slash`() {
        val enterprise = parse(mapOf("repo" to "o/r", "token" to token, "api_url" to "https://ghe.example.com/api/v3/")).getOrThrow()

        assertEquals(URI("https://ghe.example.com/api/v3"), enterprise.apiUrl)
        for (url in listOf("ftp://example.com", "example.com", "https://", "https://x.com/a?b=1")) {
            val error = failure(mapOf("repo" to "o/r", "token" to token, "api_url" to url))

            assertIs<GitHubConfigError.Invalid>(error, url)
            assertEquals("tracker.provider.api_url must be an http or https address, got \"$url\"", error.message)
        }
    }

    @Test
    fun `api_url must be https, http only for a loopback host`() {
        for (url in listOf("http://127.0.0.1:9/api", "http://127.1.2.3/api", "http://localhost:1/api", "http://[::1]:9/api")) {
            assertEquals(URI(url), parse(mapOf("repo" to "o/r", "token" to token, "api_url" to url)).getOrThrow().apiUrl, url)
        }
        for (url in listOf("http://ghe.example.com/api/v3", "http://10.0.0.1/api", "http://127.0.0.1.example.com/api")) {
            val error = failure(mapOf("repo" to "o/r", "token" to token, "api_url" to url))

            assertIs<GitHubConfigError.Invalid>(error, url)
            assertEquals(
                "tracker.provider.api_url must be an https address, http is allowed only for a loopback host, got \"$url\"",
                error.message,
            )
        }
    }

    @Test
    fun `labels is a list of scope labels, trimmed and without blanks`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to token, "labels" to listOf(" Loop ", "", "ios"))).getOrThrow()

        assertEquals(listOf("Loop", "ios"), settings.scopeLabels)
        for (labels in listOf("loop", listOf("a", listOf("b")), mapOf("a" to "b"))) {
            val error = failure(mapOf("repo" to "o/r", "token" to token, "labels" to labels))

            assertIs<GitHubConfigError.Invalid>(error, labels.toString())
            assertEquals("tracker.provider.labels must be a list of labels", error.message)
        }
    }

    @Test
    fun `m2-23 handoff labels default to needs-review and needs-info`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to token)).getOrThrow()

        assertEquals(mapOf("review" to "needs-review", "needs_info" to "needs-info"), settings.handoffLabels)
    }

    @Test
    fun `m2-23 handoff_labels sets the label of each handoff state, trimmed, and a missing one keeps its default`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to token, "handoff_labels" to mapOf("review" to " In Review "))).getOrThrow()

        assertEquals(mapOf("review" to "In Review", "needs_info" to "needs-info"), settings.handoffLabels)
    }

    @Test
    fun `m2-23 handoff_labels must map review and needs_info to labels`() {
        val cases =
            listOf(
                listOf("needs-review") to "tracker.provider.handoff_labels must map review and needs_info to a label",
                mapOf("done" to "x") to "tracker.provider.handoff_labels has \"done\", only review and needs_info have a handoff label",
                mapOf("review" to " ") to "tracker.provider.handoff_labels.review must be a label",
                mapOf("needs_info" to listOf("a")) to "tracker.provider.handoff_labels.needs_info must be a label",
            )
        for ((value, message) in cases) {
            val error = failure(mapOf("repo" to "o/r", "token" to token, "handoff_labels" to value))

            assertIs<GitHubConfigError.Invalid>(error)
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `bl-157 trusted_associations defaults to OWNER, MEMBER and COLLABORATOR`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to token)).getOrThrow()

        assertEquals(listOf("OWNER", "MEMBER", "COLLABORATOR"), settings.trustedAssociations)
        assertEquals(listOf("OWNER", "MEMBER", "COLLABORATOR"), GitHubTrackerSettings.DEFAULT_TRUSTED_ASSOCIATIONS)
    }

    @Test
    fun `bl-157 trusted_associations is read trimmed and case insensitively, duplicates once`() {
        val settings =
            parse(mapOf("repo" to "o/r", "token" to token, "trusted_associations" to listOf(" owner ", "Contributor", "OWNER")))
                .getOrThrow()

        assertEquals(listOf("OWNER", "CONTRIBUTOR"), settings.trustedAssociations)
    }

    @Test
    fun `bl-157 a widened list keeps a CONTRIBUTOR comment, a narrowed one drops a COLLABORATOR comment`() =
        runTest {
            val github = FakeGitHub()
            github.on(FakeGitHub.issueUrl(7), FakeGitHub.response(200, FakeGitHub.issue(7, comments = 2).toString()))
            github.on(
                FakeGitHub.commentsUrl(7),
                FakeGitHub.json(
                    listOf(
                        FakeGitHub.comment("drive-by", "2026-11-08T07:00:00Z", "Contributor note.", association = "CONTRIBUTOR"),
                        FakeGitHub.comment("helper", "2026-11-08T08:00:00Z", "Collaborator note.", association = "COLLABORATOR"),
                    ),
                ),
            )

            suspend fun comments(trusted: List<String>?): String {
                val provider = mapOf("repo" to "octocat/Hello-World", "token" to token)
                val settings = parse(if (trusted == null) provider else provider + ("trusted_associations" to trusted)).getOrThrow()
                val tracker = GitHubTracker(settings, github)
                val issue = tracker.fetchIssuesByIds(listOf("7")).getOrThrow().single()
                return tracker
                    .completeIssue(issue)
                    .getOrThrow()
                    .description
                    .orEmpty()
                    .substringAfter("## Comments\n\n")
            }

            assertEquals(
                "1 comment from outside tracker.provider.trusted_associations is not shown.\n\n" +
                    "2026-11-08 08:00Z helper: Collaborator note.",
                comments(null),
            )
            assertEquals(
                "1 comment from outside tracker.provider.trusted_associations is not shown.\n\n" +
                    "2026-11-08 07:00Z drive-by: Contributor note.",
                comments(listOf("OWNER", "CONTRIBUTOR")),
            )
            assertEquals(
                "2 comments from outside tracker.provider.trusted_associations are not shown.",
                comments(listOf("OWNER")),
            )
        }

    @Test
    fun `bl-157 an unknown association, an empty list or a value that is not a list of strings is invalid`() {
        val names = "OWNER, MEMBER, COLLABORATOR, CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, MANNEQUIN or NONE"
        val cases =
            listOf(
                listOf("OWNER", "MAINTAINER") to
                    "tracker.provider.trusted_associations has \"MAINTAINER\", which is not a GitHub author association, use $names",
                listOf("OWNER", " ") to
                    "tracker.provider.trusted_associations has \" \", which is not a GitHub author association, use $names",
                emptyList<String>() to "tracker.provider.trusted_associations must not be empty, list the author associations to trust",
                "OWNER" to "tracker.provider.trusted_associations must be a list of author associations, got \"OWNER\"",
                mapOf("a" to "b") to "tracker.provider.trusted_associations must be a list of author associations, got \"{a=b}\"",
                listOf("OWNER", listOf("MEMBER")) to
                    "tracker.provider.trusted_associations must be a list of author associations, got \"[OWNER, [MEMBER]]\"",
            )
        for ((value, message) in cases) {
            val error = failure(mapOf("repo" to "o/r", "token" to token, "trusted_associations" to value))

            assertIs<GitHubConfigError.Invalid>(error, value.toString())
            assertEquals("invalid_tracker_config", error.category)
            assertEquals(message, error.message)
        }
    }

    @Test
    fun `the settings never print the token`() {
        val settings = parse(mapOf("repo" to "o/r", "token" to token)).getOrThrow()

        assertFalse(token in settings.toString(), settings.toString())
        assertTrue("o/r" in settings.toString() || "owner=o" in settings.toString(), settings.toString())
    }
}
