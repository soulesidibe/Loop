package calc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalcTest {
    @Test
    fun adds() {
        assertEquals(4, add(2, 2))
    }

    @Test
    fun `divides by zero`() {
        assertFailsWith<IllegalArgumentException> { divide(1, 0) }
    }
}
