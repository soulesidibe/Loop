package loop.core.workflow

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** m2-03: the `kinds:` key switches a workflow to Loop mode; without it nothing changes. */
class KindsConfigTest {
    private val folder: Path = createTempDirectory("loop-kinds").toRealPath()
    private val loader = WorkflowLoader(env = emptyMap())

    private fun write(extra: String): Path =
        folder.resolve("WORKFLOW.md").also {
            it.writeText(
                """
                |---
                |tracker:
                |  kind: files
                |runner:
                |  kind: command
                |runners:
                |  command:
                |    command: ./agent.sh
                |$extra
                |---
                |Prompt
                """.trimMargin(),
            )
        }

    private fun kinds(extra: String): KindsConfig? =
        loader
            .load(write(extra))
            .getOrThrow()
            .config.kinds

    private fun error(extra: String): String = assertIs<WorkflowError.InvalidConfig>(loader.load(write(extra)).exceptionOrNull()).message!!

    @Test
    fun `no kinds key is Symphony mode`() {
        assertNull(kinds("polling:\n  interval_ms: 1000"))
    }

    @Test
    fun `a code kind with command checks is Loop mode`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "./gradlew test" }
                |        - type: command
                |          run: ./lint.sh
                |          timeout_ms: 5000
                """.trimMargin(),
            )

        assertEquals(
            KindsConfig(
                code =
                    KindConfig(
                        checks =
                            listOf(
                                CheckConfig.Command("./gradlew test", CheckConfig.DEFAULT_TIMEOUT_MS),
                                CheckConfig.Command("./lint.sh", 5000),
                            ),
                    ),
            ),
            kinds,
        )
    }

    @Test
    fun `a kind declares acceptance_commands next to its other checks (m2-09)`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: "./gradlew test" }
                |        - { type: acceptance_commands }
                |        - { type: command, run: ./lint.sh }
                """.trimMargin(),
            )

        assertEquals(
            listOf(
                CheckConfig.Command("./gradlew test"),
                CheckConfig.AcceptanceCommands(CheckConfig.DEFAULT_TIMEOUT_MS),
                CheckConfig.Command("./lint.sh"),
            ),
            kinds!!.code.checks,
        )
    }

    @Test
    fun `acceptance_commands takes the timeout_ms of a command check (m2-09)`() {
        val kinds = kinds("kinds:\n  code:\n    verify:\n      checks:\n        - { type: acceptance_commands, timeout_ms: 600000 }")

        assertEquals(listOf(CheckConfig.AcceptanceCommands(600_000)), kinds!!.code.checks)
    }

    @Test
    fun `acceptance_commands takes no run, and only once (m2-09)`() {
        fun checks(vararg bodies: String) =
            error("kinds:\n  code:\n    verify:\n      checks:\n" + bodies.joinToString("\n") { "        - $it" })

        assertEquals(
            "kinds.code.verify.checks[0].run is not used by acceptance_commands, the commands come from the issue",
            checks("{ type: acceptance_commands, run: ./x.sh }"),
        )
        assertEquals(
            "kinds.code.verify.checks[1] repeats acceptance_commands, declare it once",
            checks("{ type: acceptance_commands }", "{ type: acceptance_commands }"),
        )
        assertEquals(
            "kinds.code.verify.checks[0].timeout_ms must be a positive integer, got -1",
            checks("{ type: acceptance_commands, timeout_ms: -1 }"),
        )
    }

    @Test
    fun `an explicit empty check list is valid`() {
        assertEquals(KindsConfig(KindConfig(emptyList())), kinds("kinds:\n  code:\n    verify:\n      checks: []"))
    }

    @Test
    fun `shapes that would switch verification off silently are config errors`() {
        val cases =
            mapOf(
                "kinds:" to "kinds must be a map with a code kind",
                "kinds: ~" to "kinds must be a map with a code kind",
                "kinds: {}" to "kinds must be a map with a code kind",
                "kinds: [code]" to "kinds must be a map",
                "kinds:\n  code:" to "kinds.code.verify is required",
                "kinds:\n  code: {}" to "kinds.code.verify is required",
                "kinds:\n  code:\n    verify: {}" to "kinds.code.verify.checks is required",
                "kinds:\n  code:\n    verify:\n      chekcs: []" to "kinds.code.verify.checks is required",
                "kinds:\n  code:\n    verify:\n      checks: ./x.sh" to "kinds.code.verify.checks must be a list",
                "kinds:\n  docs:\n    verify:\n      checks: []" to "kinds.docs is not supported, use code",
            )
        cases.forEach { (yaml, message) -> assertEquals(message, error(yaml), yaml) }
    }

    @Test
    fun `a check must be a command with a run and a positive timeout`() {
        fun check(body: String) = error("kinds:\n  code:\n    verify:\n      checks:\n        - $body")

        assertEquals("kinds.code.verify.checks[0] must be a map", check("./x.sh"))
        assertEquals("kinds.code.verify.checks[0].type is required", check("{ run: ./x.sh }"))
        assertEquals(
            "kinds.code.verify.checks[0].type \"http\" is not supported, " +
                "use command, file_exists, file_matches, diff_policy, changed_line_coverage or acceptance_commands",
            check("{ type: http, run: ./x.sh }"),
        )
        assertEquals("kinds.code.verify.checks[0].run is required", check("{ type: command }"))
        assertEquals("kinds.code.verify.checks[0].run is required", check("{ type: command, run: \"  \" }"))
        assertEquals(
            "kinds.code.verify.checks[0].timeout_ms must be a positive integer, got 0",
            check("{ type: command, run: ./x.sh, timeout_ms: 0 }"),
        )
    }

    @Test
    fun `a kind without ready has no readiness check`() {
        assertNull(kinds("kinds:\n  code:\n    verify:\n      checks: []")!!.code.ready)
    }

    @Test
    fun `ready require_sections lists the sections an issue needs`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    ready: { require_sections: [Acceptance criteria, " What "] }
                |    verify:
                |      checks: []
                """.trimMargin(),
            )

        assertEquals(ReadyConfig(listOf("Acceptance criteria", "What")), kinds!!.code.ready)
    }

    @Test
    fun `a ready shape that would switch the check off silently is a config error`() {
        fun ready(body: String) = error("kinds:\n  code:\n    ready: $body\n    verify:\n      checks: []")

        assertEquals("kinds.code.ready must be a map", ready("~"))
        assertEquals("kinds.code.ready must be a map", ready("[Acceptance criteria]"))
        assertEquals("kinds.code.ready.require_sections is required", ready("{}"))
        assertEquals("kinds.code.ready.require_sections is required", ready("{ require_section: [What] }"))
        assertEquals("kinds.code.ready.require_sections must be a list of strings", ready("{ require_sections: What }"))
        assertEquals("kinds.code.ready.require_sections must list at least one section", ready("{ require_sections: [] }"))
        assertEquals("kinds.code.ready.require_sections must not hold a blank name", ready("{ require_sections: [What, \" \"] }"))
    }

    @Test
    fun `a kind without holdout has no holdout check (m2-14)`() {
        assertEquals(emptyList<CheckConfig>(), kinds("kinds:\n  code:\n    verify:\n      checks: []")!!.code.holdout)
    }

    @Test
    fun `verify holdout lists hidden checks of the same shape as checks (m2-14)`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks: []
                |      holdout:
                |        - { type: command, run: "${'$'}LOOP_HOLDOUT/acceptance.sh", timeout_ms: 5000 }
                """.trimMargin(),
            )

        assertEquals(listOf(CheckConfig.Command("${'$'}LOOP_HOLDOUT/acceptance.sh", 5000)), kinds!!.code.holdout)
    }

    @Test
    fun `a holdout shape that would switch the hidden checks off silently is a config error (m2-14)`() {
        fun holdout(body: String) = error("kinds:\n  code:\n    verify:\n      checks: []\n      holdout: $body")

        assertEquals("kinds.code.verify.holdout must be a list", holdout("~"))
        assertEquals("kinds.code.verify.holdout must be a list", holdout("./x.sh"))
        assertEquals("kinds.code.verify.holdout[0].run is required", holdout("[{ type: command }]"))
        assertEquals(
            "kinds.code.verify.holdout[0].type \"shell\" is not supported, " +
                "use command, file_exists, file_matches, diff_policy, changed_line_coverage or acceptance_commands",
            holdout("[{ type: shell, run: ./x.sh }]"),
        )
    }

    @Test
    fun `a holdout cannot be acceptance_commands, the commands of the issue are never hidden (m2-09, m2-14)`() {
        assertEquals(
            "kinds.code.verify.holdout[1] cannot be acceptance_commands, the issue shows its commands, list it in checks",
            error(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks: []
                |      holdout:
                |        - { type: command, run: ./hidden.sh }
                |        - { type: acceptance_commands }
                """.trimMargin(),
            ),
        )
    }

    @Test
    fun `the core check types parse with their config, and guards protect feeds diff_policy (m2-12)`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: file_exists, path: docs/usage.md }
                |        - { type: file_matches, path: "./docs/usage.md", pattern: "^## Usage$" }
                |        - { type: diff_policy }
                |        - { type: command, run: ./gradlew build }
                |      guards:
                |        protect: ["scripts/", "**/src/test/**", "/WORKFLOW.md"]
                |        base: trunk
                """.trimMargin(),
            )!!

        val guards = GuardsConfig(listOf("scripts/", "**/src/test/**", "WORKFLOW.md"), "trunk")
        assertEquals(guards, kinds.code.guards)
        assertEquals(
            listOf(
                CheckConfig.FileExists("docs/usage.md"),
                CheckConfig.FileMatches("docs/usage.md", "^## Usage$"),
                CheckConfig.DiffPolicy(guards.protect, "trunk"),
                CheckConfig.Command("./gradlew build"),
            ),
            kinds.code.checks,
        )
    }

    @Test
    fun `guards protect without a diff_policy check appends one at the end, base main by default (m2-12)`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: ./gradlew build }
                |      guards: { protect: [scripts/] }
                """.trimMargin(),
            )!!

        assertEquals(
            listOf(CheckConfig.Command("./gradlew build"), CheckConfig.DiffPolicy(listOf("scripts/"), "main")),
            kinds.code.checks,
        )
    }

    @Test
    fun `guards alone add no check to an empty list, a guard proves no work (m2-12)`() {
        val kinds = kinds("kinds:\n  code:\n    verify:\n      checks: []\n      guards: { protect: [scripts/] }")!!

        assertEquals(emptyList(), kinds.code.checks)
        assertEquals(GuardsConfig(listOf("scripts/")), kinds.code.guards)
    }

    @Test
    fun `core check shapes that cannot run as meant are config errors (m2-12)`() {
        fun check(
            body: String,
            guards: String = "",
        ) = error("kinds:\n  code:\n    verify:\n      checks:\n        - $body$guards")

        val name = "kinds.code.verify.checks[0]"
        assertEquals("$name.path is required", check("{ type: file_exists }"))
        assertEquals("$name.path is required", check("{ type: file_matches, path: \" \", pattern: x }"))
        assertEquals("$name.path must be relative to the workspace, got /etc/hosts", check("{ type: file_exists, path: /etc/hosts }"))
        assertEquals("$name.path must stay inside the workspace, got ../x", check("{ type: file_exists, path: ../x }"))
        assertEquals(
            "$name.path must stay inside the workspace, got a/../../x",
            check("{ type: file_matches, path: a/../../x, pattern: y }"),
        )
        assertEquals("$name.pattern is required", check("{ type: file_matches, path: a.txt }"))
        assertEquals("$name.pattern is required", check("{ type: file_matches, path: a.txt, pattern: \"\" }"))
        assertTrue(
            check("{ type: file_matches, path: a.txt, pattern: \"(\" }").startsWith("$name.pattern is not a valid regular expression: "),
        )
        assertEquals("$name diff_policy needs kinds.code.verify.guards.protect", check("{ type: diff_policy }"))
        val guards = "\n      guards:"
        assertEquals("kinds.code.verify.guards must be a map", check("{ type: diff_policy }", "$guards [a]"))
        assertEquals("kinds.code.verify.guards.protect is required", check("{ type: diff_policy }", "$guards {}"))
        assertEquals(
            "kinds.code.verify.guards.protect must be a list of strings",
            check("{ type: diff_policy }", "$guards { protect: scripts/ }"),
        )
        assertEquals(
            "kinds.code.verify.guards.protect must list at least one path",
            check("{ type: diff_policy }", "$guards { protect: [] }"),
        )
        assertEquals(
            "kinds.code.verify.guards.protect must not hold a blank path",
            check("{ type: diff_policy }", "$guards { protect: [a, \" \"] }"),
        )
        assertEquals(
            "kinds.code.verify.guards.base must not be blank",
            check("{ type: diff_policy }", "$guards { protect: [a], base: \" \" }"),
        )
    }

    @Test
    fun `changed_line_coverage parses its reports, floor and base, the base defaulting like diff_policy (bl-117)`() {
        fun checks(
            body: String,
            guards: String = "",
        ) = kinds("kinds:\n  code:\n    verify:\n      checks:\n        - $body$guards")!!.code.checks.first()

        val glob = "**/build/reports/kover/report.xml"
        assertEquals(
            CheckConfig.ChangedLineCoverage(listOf(glob), 80.0, "main"),
            checks("{ type: changed_line_coverage, reports: [\"$glob\"], min_percent: 80 }"),
        )
        assertEquals(
            CheckConfig.ChangedLineCoverage(listOf("a/report.xml", "b/report.xml"), 62.5, "trunk"),
            checks(
                "{ type: changed_line_coverage, reports: [./a/report.xml, b/report.xml], min_percent: 62.5 }",
                "\n      guards: { protect: [scripts/], base: trunk }",
            ),
        )
        assertEquals(
            CheckConfig.ChangedLineCoverage(listOf("r.xml"), 0.0, "develop"),
            checks("{ type: changed_line_coverage, reports: [r.xml], min_percent: 0, base: develop }"),
        )
        assertEquals(
            CheckConfig.ChangedLineCoverage(listOf("r.xml"), 100.0, "main"),
            checks("{ type: changed_line_coverage, reports: [r.xml], min_percent: 100 }"),
        )
        assertEquals(
            "changed_line_coverage base=main min_percent=62.5 reports=a/report.xml,b/report.xml",
            CheckConfig.ChangedLineCoverage(listOf("a/report.xml", "b/report.xml"), 62.5).label,
        )
    }

    @Test
    fun `changed_line_coverage shapes that cannot run as meant are config errors (bl-117)`() {
        fun check(body: String) = error("kinds:\n  code:\n    verify:\n      checks:\n        - $body")

        val name = "kinds.code.verify.checks[0]"
        val type = "type: changed_line_coverage"
        assertEquals("$name.reports is required", check("{ $type, min_percent: 80 }"))
        assertEquals("$name.reports must list at least one report", check("{ $type, reports: [], min_percent: 80 }"))
        assertEquals("$name.reports must be a list of strings", check("{ $type, reports: r.xml, min_percent: 80 }"))
        assertEquals("$name.reports must not hold a blank path", check("{ $type, reports: [r.xml, \" \"], min_percent: 80 }"))
        assertEquals(
            "$name.reports must be relative to the workspace, got /tmp/r.xml",
            check("{ $type, reports: [/tmp/r.xml], min_percent: 80 }"),
        )
        assertEquals(
            "$name.reports must stay inside the workspace, got a/../../r.xml",
            check("{ $type, reports: [a/../../r.xml], min_percent: 80 }"),
        )
        assertEquals("$name.min_percent is required", check("{ $type, reports: [r.xml] }"))
        assertEquals("$name.min_percent must be a number from 0 to 100, got 101", check("{ $type, reports: [r.xml], min_percent: 101 }"))
        assertEquals("$name.min_percent must be a number from 0 to 100, got -1", check("{ $type, reports: [r.xml], min_percent: -1 }"))
        assertEquals(
            "$name.min_percent must be a number from 0 to 100, got \"most\"",
            check("{ $type, reports: [r.xml], min_percent: most }"),
        )
        assertEquals("$name.base must not be blank", check("{ $type, reports: [r.xml], min_percent: 80, base: \" \" }"))
        assertTrue(check("{ type: coverage }").endsWith("diff_policy, changed_line_coverage or acceptance_commands"))
    }

    @Test
    fun `guards deny lists the deny guards, protect is optional beside it, and languages add or replace test patterns (m2-13)`() {
        val kinds =
            kinds(
                """
                |kinds:
                |  code:
                |    verify:
                |      checks:
                |        - { type: command, run: ./gradlew build }
                |      guards:
                |        deny: [deleted_tests, added_skips, weaker_assertions]
                |        languages:
                |          - name: python
                |            files: ["tests/**/*.py"]
                |            test: "^\\s*def (test_\\w+)"
                |            skip: "@pytest\\.mark\\.skip"
                |            line_comment: "#"
                |          - { name: kotlin, files: ["**/*Spec.kt"], assertion: "shouldBe", block_comment: ["/*", "*/"] }
                """.trimMargin(),
            )!!

        val python =
            TestLanguage(
                name = "python",
                files = listOf("tests/**/*.py"),
                test = """^\s*def (test_\w+)""",
                skip = """@pytest\.mark\.skip""",
                lineComment = "#",
            )
        val kotlin = TestLanguage(name = "kotlin", files = listOf("**/*Spec.kt"), assertion = "shouldBe", blockComment = "/*" to "*/")
        val guards = GuardsConfig(emptyList(), "main", DenyGuard.entries, listOf(kotlin, TestLanguage.SWIFT, python))
        assertEquals(guards, kinds.code.guards)
        assertEquals(
            listOf(
                CheckConfig.Command("./gradlew build"),
                CheckConfig.DiffPolicy(emptyList(), "main", DenyGuard.entries, guards.languages),
            ),
            kinds.code.checks,
        )
        assertEquals("diff_policy base=main deny=deleted_tests,added_skips,weaker_assertions", kinds.code.checks[1].label)
        assertEquals(
            "diff_policy base=main protect=a,b deny=added_skips",
            CheckConfig.DiffPolicy(listOf("a", "b"), deny = listOf(DenyGuard.ADDED_SKIPS)).label,
        )
    }

    @Test
    fun `deny guard and language shapes that cannot run as meant are config errors (m2-13)`() {
        fun guards(body: String) =
            error("kinds:\n  code:\n    verify:\n      checks:\n        - { type: diff_policy }\n      guards: $body")

        val deny = "kinds.code.verify.guards.deny"
        assertEquals("kinds.code.verify.guards.protect is required", guards("{ base: main }"))
        assertEquals("$deny must be a list of strings", guards("{ deny: deleted_tests }"))
        assertEquals("$deny must list at least one guard", guards("{ deny: [] }"))
        assertEquals(
            "$deny \"deleted_test\" is not supported, use deleted_tests, added_skips or weaker_assertions",
            guards("{ deny: [deleted_test] }"),
        )
        val languages = "kinds.code.verify.guards.languages"
        assertEquals("$languages must be a list", guards("{ deny: [added_skips], languages: python }"))
        assertEquals("$languages[0] must be a map", guards("{ deny: [added_skips], languages: [python] }"))
        assertEquals("$languages[0].name is required", guards("{ deny: [added_skips], languages: [{ files: [a] }] }"))
        assertEquals("$languages[0].files is required", guards("{ deny: [added_skips], languages: [{ name: py, skip: x }] }"))
        assertEquals(
            "$languages[0].files must list at least one path",
            guards("{ deny: [added_skips], languages: [{ name: py, files: [], skip: x }] }"),
        )
        assertEquals(
            "$languages[0] needs at least one of test, skip and assertion",
            guards("{ deny: [added_skips], languages: [{ name: py, files: [a] }] }"),
        )
        assertTrue(
            guards("{ deny: [added_skips], languages: [{ name: py, files: [a], skip: \"(\" }] }")
                .startsWith("$languages[0].skip is not a valid regular expression: "),
        )
        assertEquals(
            "$languages[0].block_comment must list a start and an end marker",
            guards("{ deny: [added_skips], languages: [{ name: py, files: [a], skip: x, block_comment: [\"/*\"] }] }"),
        )
        assertEquals(
            "$languages[1].name python is listed twice",
            guards("{ deny: [added_skips], languages: [{ name: python, files: [a], skip: x }, { name: python, files: [b], skip: y }] }"),
        )
    }

    private fun delivery(yaml: String?): String {
        val line = yaml?.let { "\n    delivery: $it" }.orEmpty()
        return "kinds:\n  code:$line\n    verify:\n      checks: []"
    }

    @Test
    fun `m2-24 the delivery policy is human by default and auto on request`() {
        assertEquals(DeliveryPolicy.Human, kinds(delivery(null))?.code?.delivery?.policy)
        assertEquals(DeliveryPolicy.Human, kinds(delivery("{ policy: human }"))?.code?.delivery?.policy)
        assertEquals(DeliveryPolicy.Human, kinds(delivery("{ policy: \" Human \" }"))?.code?.delivery?.policy)
        assertEquals(DeliveryPolicy.Human, kinds(delivery("{}"))?.code?.delivery?.policy)
        assertEquals(DeliveryPolicy.Auto, kinds(delivery("{ policy: auto, auto_merge: { enabled: true } }"))?.code?.delivery?.policy)
    }

    @Test
    fun `m2-24 delivery shapes that would switch approval off silently are config errors`() {
        assertEquals("kinds.code.delivery must be a map", error(delivery("~")))
        assertEquals("kinds.code.delivery must be a map", error(delivery("human")))
        assertEquals("kinds.code.delivery.policy must be human or auto, got \"humna\"", error(delivery("{ policy: humna }")))
        assertEquals("kinds.code.delivery.policy must be human or auto, got \"\"", error(delivery("{ policy: \"\" }")))
    }
}
