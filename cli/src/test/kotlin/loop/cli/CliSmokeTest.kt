package loop.cli

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliSmokeTest {
    @Test
    fun `help prints the usage`() {
        val result = LoopCommand().test("--help")

        assertEquals(0, result.statusCode)
        assertContains(result.output, "Usage: loop")
    }
}
