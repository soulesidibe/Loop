package loop.core.verify

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * m2-09: the criteria of an issue's `## Acceptance criteria` section, read from fixture issue bodies as the
 * trackers give them. A criterion that opens with one backticked command followed by `passes` is a check,
 * any other criterion is a judgment call.
 */
class AcceptanceCriteriaTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/acceptance/$name")) { "no fixture $name" }.readText()

    @Test
    fun `the m2-09 issue gives one command and four judgment calls`() {
        val criteria = AcceptanceCriteria.of(fixture("m2-09.md"))

        assertEquals(5, criteria.size)
        assertEquals(listOf("./gradlew --no-daemon build"), criteria.commands)
        assertEquals(
            listOf(
                "A criterion line with one backticked command produces one check; a line without a command produces " +
                    "a judgment call entry (test on fixture issues).",
                "A kind can declare `{ type: acceptance_commands }` next to its other checks (test).",
                "Commands run in the workspace with the same timeout and rerun rule as `command` checks (test).",
                "The convention for writing a runnable criterion is stated in `docs/agents/issue-tracker.md`.",
            ),
            criteria.judgmentCalls,
        )
    }

    @Test
    fun `each list item of the first section is one criterion, in order`() {
        val criteria = AcceptanceCriteria.of(fixture("shapes.md"))

        assertEquals(
            listOf(
                AcceptanceCriterion(
                    "`./gradlew :cli:test --tests \"loop.cli.TraceCommandTest\"` passes, a ticked box changes nothing.",
                    "./gradlew :cli:test --tests \"loop.cli.TraceCommandTest\"",
                ),
                AcceptanceCriterion("`scripts/check.sh` passes", "scripts/check.sh"),
                AcceptanceCriterion("`make lint` passes.", "make lint"),
                AcceptanceCriterion(
                    "`npm test` passes on a clean checkout, with `node` 22 (one command, the first span).",
                    "npm test",
                ),
                AcceptanceCriterion("`loop trace` prints the hook lines, covered by a `TraceCommandTest` test.", null),
                AcceptanceCriterion(
                    "A criterion written over two lines keeps its continuation, `true` passes is not a command here.",
                    null,
                ),
                AcceptanceCriterion("`   ` passes.", null),
                AcceptanceCriterion("The build passes: `./gradlew build` passes.", null),
                AcceptanceCriterion("`./notes.sh` passes, a deeper heading stays in the section.", "./notes.sh"),
            ),
            criteria,
        )
    }

    @Test
    fun `a backticked path or identifier alone is a judgment call, never a command`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] `docs/self-hosting.md` no longer says a first dispatch skips the sync.
                |- [ ] `./gradlew build`
                |- [ ] `./gradlew build` passed yesterday.
                |- [ ] `./gradlew build` passesmuster.
                """.trimMargin(),
            )

        assertEquals(emptyList(), criteria.commands)
        assertEquals(4, criteria.judgmentCalls.size)
    }

    @Test
    fun `no section, an empty section or no description gives no criterion`() {
        assertEquals(emptyList(), AcceptanceCriteria.of(null))
        assertEquals(emptyList(), AcceptanceCriteria.of("## What\n\n- [ ] `./ok.sh` passes"))
        assertEquals(emptyList(), AcceptanceCriteria.of("## Acceptance criteria\n\n## Comments\n"))
    }

    @Test
    fun `the heading is matched trimmed and case insensitively at any level`() {
        val criteria = AcceptanceCriteria.of("### acceptance Criteria  \n- [ ] `./ok.sh` passes\n## Next\n- [ ] `./no.sh` passes")

        assertEquals(listOf("./ok.sh"), criteria.commands)
    }

    @Test
    fun `only a span that looks like a command runs, a bare name is a judgment call`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] `TraceCommandTest` passes
                |- [ ] `make` passes.
                |- [ ] `make lint` passes.
                |- [ ] `./gradlew` passes.
                |- [ ] `/usr/bin/true` passes.
                |- [ ] `scripts/check.sh` passes.
                |- [ ] `../tools/check.sh` passes.
                """.trimMargin(),
            )

        assertEquals(listOf("make lint", "./gradlew", "/usr/bin/true", "scripts/check.sh", "../tools/check.sh"), criteria.commands)
        assertEquals(listOf("`TraceCommandTest` passes", "`make` passes."), criteria.judgmentCalls)
    }

    @Test
    fun `a continuation line that opens with inline triple backticks is not a fence, later criteria stay`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] A criterion whose continuation
                |  ```inline``` opens with three backticks.
                |- [ ] `./gradlew build` passes.
                |- [ ] A human reads it.
                """.trimMargin(),
            )

        assertEquals(3, criteria.size)
        assertEquals("A criterion whose continuation ```inline``` opens with three backticks.", criteria.first().text)
        assertEquals(listOf("./gradlew build"), criteria.commands)
    }

    @Test
    fun `a column 0 fence still hides its lines, like a fence of the readiness sections`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |~~~
                |- [ ] `echo fenced` passes
                |~~~
                |- [ ] `./gradlew build` passes.
                """.trimMargin(),
            )

        assertEquals(listOf(AcceptanceCriterion("`./gradlew build` passes.", "./gradlew build")), criteria)
    }

    @Test
    fun `the heading may carry a colon or trailing text, never another word glued to it`() {
        assertEquals(listOf("./a.sh"), AcceptanceCriteria.of("## Acceptance criteria:\n- [ ] `./a.sh` passes").commands)
        assertEquals(listOf("./b.sh"), AcceptanceCriteria.of("## Acceptance criteria (m2-09)\n- [ ] `./b.sh` passes").commands)
        assertEquals(listOf("./c.sh"), AcceptanceCriteria.of("## Acceptance criteria ##\n- [ ] `./c.sh` passes").commands)
        assertEquals(emptyList(), AcceptanceCriteria.of("## Acceptance criteriaX\n- [ ] `./d.sh` passes"))
        assertEquals(emptyList(), AcceptanceCriteria.of("## Acceptance\n- [ ] `./e.sh` passes"))
    }

    @Test
    fun `a thematic break is a rule, not a judgment call, and ends the criterion above it`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] `./gradlew build` passes.
                |- - -
                |  an indented line after a rule is no continuation
                |* * *
                |_ _ _
                |- [ ] A human reads it.
                """.trimMargin(),
            )

        assertEquals(
            listOf(
                AcceptanceCriterion("`./gradlew build` passes.", "./gradlew build"),
                AcceptanceCriterion("A human reads it.", null),
            ),
            criteria,
        )
    }

    @Test
    fun `a CRLF description reads like an LF one`() {
        val lf = "## Acceptance criteria\n\n- [ ] `./gradlew build` passes.\n- [ ] A human\n  reads it.\n\n## Comments\n"

        val criteria = AcceptanceCriteria.of(lf.replace("\n", "\r\n"))

        assertEquals(AcceptanceCriteria.of(lf), criteria)
        assertEquals(listOf("./gradlew build"), criteria.commands)
        assertEquals(listOf("A human reads it."), criteria.judgmentCalls)
    }

    @Test
    fun `a nested list item belongs to its parent, only a column 0 item can hold a command`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] A parent criterion
                |  - [ ] `./nested.sh` passes, a nested item is part of its parent.
                |- [ ] `./top.sh` passes.
                """.trimMargin(),
            )

        assertEquals(listOf("./top.sh"), criteria.commands)
        assertEquals(
            listOf("A parent criterion - [ ] `./nested.sh` passes, a nested item is part of its parent."),
            criteria.judgmentCalls,
        )
    }

    @Test
    fun `double or escaped backticks are judgment calls`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] ``./gradlew build`` passes.
                |- [ ] \`./gradlew build\` passes.
                |- [ ] `echo \`hi\`` passes.
                |- [ ] `` ./gradlew build `` passes.
                """.trimMargin(),
            )

        assertEquals(emptyList(), criteria.commands)
        assertEquals(4, criteria.judgmentCalls.size)
    }

    @Test
    fun `passes is a whole word, passes-through or passes_ is not the convention`() {
        val criteria =
            AcceptanceCriteria.of(
                """
                |## Acceptance criteria
                |
                |- [ ] `./gradlew build` passes-through the gate.
                |- [ ] `./gradlew build` passes_now.
                |- [ ] `./a.sh` passes
                |- [ ] `./b.sh` passes, then the docs.
                |- [ ] `./c.sh` passes; nothing else.
                """.trimMargin(),
            )

        assertEquals(listOf("./a.sh", "./b.sh", "./c.sh"), criteria.commands)
        assertEquals(2, criteria.judgmentCalls.size)
    }
}
