package loop.runner.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The settings file the runner passes with `--settings`. */
class ClaudeSettingsFileTest {
    @Test
    fun `the guardrail deny rules block git push, gh and history rewrites`() {
        val deny = denyRules(ClaudeSettingsFile.json())

        val expected =
            listOf(
                "Bash(git push:*)",
                "Bash(git push --force:*)",
                "Bash(git push -f:*)",
                "Bash(git push --force-with-lease:*)",
                "Bash(gh:*)",
                "Bash(git rebase:*)",
                "Bash(git reset --hard:*)",
                "Bash(git commit --amend:*)",
                "Bash(git filter-branch:*)",
                "Bash(git filter-repo:*)",
                "Bash(git reflog expire:*)",
                "Bash(git update-ref:*)",
                "Bash(git replace:*)",
                "Bash(loop approve:*)",
                "Bash(loop resume:*)",
            )
        assertEquals(expected.sorted(), deny.sorted())
    }

    @Test
    fun `loop approve and loop resume are denied by the bare name and by the launcher path (m2-24)`() {
        val deny = denyRules(ClaudeSettingsFile.json(launcher = "/opt/loop/bin/loop"))

        listOf("loop", "/opt/loop/bin/loop").forEach { loop ->
            assertEquals(true, "Bash($loop approve:*)" in deny, deny.toString())
            assertEquals(true, "Bash($loop resume:*)" in deny, deny.toString())
        }
        assertEquals(false, deny.any { it.startsWith("Bash(/opt/loop/bin/loop tool") }, "the attempt tools stay allowed")
    }

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads and edits under the holdout folder are denied, by its absolute path (m2-14)`() {
        val holdout = tmp.toRealPath().resolve("loop-home/holdout").createDirectories()

        val deny = denyRules(ClaudeSettingsFile.json(hiddenDirs = listOf(holdout)))

        val rule = "/$holdout/**"
        assertEquals(listOf("Read($rule)", "Edit($rule)"), deny.filterNot { it.startsWith("Bash(") })
        assertEquals("//", rule.take(2), "an absolute path rule starts with //")
    }

    @Test
    fun `a holdout folder reached through a symlink is denied by its real path too (m2-14)`() {
        val real = tmp.toRealPath().resolve("real-home/holdout").createDirectories()
        val link = Files.createSymbolicLink(tmp.toRealPath().resolve("loop-home"), real.parent).resolve("holdout")

        val deny = denyRules(ClaudeSettingsFile.json(hiddenDirs = ClaudeSettingsFile.pathsOf(link)))

        listOf(link, real).forEach { dir ->
            assertEquals(true, "Read(/$dir/**)" in deny, deny.toString())
            assertEquals(true, "Edit(/$dir/**)" in deny, deny.toString())
        }
    }

    @Test
    fun `without a holdout folder only the Bash rules are written`() {
        assertFalse(denyRules(ClaudeSettingsFile.json()).any { !it.startsWith("Bash(") })
    }

    private fun denyRules(json: String): List<String> =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("permissions")
            .jsonObject
            .getValue("deny")
            .jsonArray
            .map { it.jsonPrimitive.content }
}
