package loop.cli

import loop.buildlogic.version.GitVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/** The parsing of `git describe` behind the build's version (bl-159), compiled here from `build-logic`. */
class GitVersionTest {
    private val sha = "3f2a9c1d7e4b5a6978c0d1e2f3a4b5c6d7e8f901"
    private val short = "3f2a9c1d7e4b"

    private fun versionOf(describe: String?): String = GitVersion.parse(describe, sha).version

    @Test
    fun `a tag gives the tag`() {
        assertEquals("0.1.0", versionOf("0.1.0"))
        assertEquals("1.0.0-rc.1", versionOf("1.0.0-rc.1"))
    }

    @Test
    fun `a commit after a tag gives the tag, the count and the commit`() {
        assertEquals("0.1.0-5-g$short", versionOf("0.1.0-5-g$short"))
        assertEquals("1.0.0-rc-1-12-g$short", versionOf("1.0.0-rc-1-12-g$short"))
        // --abbrev is a minimum: git lengthens an ambiguous prefix.
        assertEquals("0.1.0-5-g${sha.take(14)}", versionOf("0.1.0-5-g${sha.take(14)}"))
    }

    @Test
    fun `a dirty tree keeps the dirty suffix`() {
        assertEquals("0.1.0-dirty", versionOf("0.1.0-dirty"))
        assertEquals("0.1.0-5-g$short-dirty", versionOf("0.1.0-5-g$short-dirty"))
        assertEquals("$short-dirty", versionOf("$short-dirty"))
    }

    @Test
    fun `no tag gives the abbreviated commit`() {
        assertEquals(short, versionOf(short))
    }

    @Test
    fun `a tag that is not a semver falls back to the commit`() {
        assertEquals(short, versionOf("2024-notes"))
        assertEquals("$short-dirty", versionOf("2024-notes-3-g$short-dirty"))
        assertEquals(short, versionOf("1.0+build"))
        assertEquals(short, versionOf("0.1.0-5-g000000000000"))
    }

    @Test
    fun `a failed describe falls back to the commit`() {
        assertEquals(GitVersion.Result(short, sha), GitVersion.parse(null, sha))
    }

    @Test
    fun `the commit is the full sha`() {
        assertEquals(sha, GitVersion.parse("0.1.0", sha).commit)
    }

    @Test
    fun `no git or no commit gives unknown`() {
        val unknown = GitVersion.Result("unknown", "unknown")
        assertEquals(unknown, GitVersion.parse(null, null))
        assertEquals(unknown, GitVersion.parse("0.1.0", null))
        assertEquals(unknown, GitVersion.parse("0.1.0", "not-a-sha"))
    }
}
