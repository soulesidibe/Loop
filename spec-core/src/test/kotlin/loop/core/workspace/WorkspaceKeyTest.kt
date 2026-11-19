package loop.core.workspace

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceKeyTest {
    private fun suffix(identifier: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(identifier.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    @Test
    fun `identifier with only allowed characters is the key unchanged`() {
        assertEquals("ABC-12_x.y", WorkspaceKey.of("ABC-12_x.y").value)
    }

    @Test
    fun `identifier with a space is sanitized and gets the hash suffix`() {
        assertEquals("ABC_12-${suffix("ABC 12")}", WorkspaceKey.of("ABC 12").value)
    }

    @Test
    fun `every character outside the allowed set becomes an underscore`() {
        assertEquals("a_b_c_-${suffix("a/b:cé")}", WorkspaceKey.of("a/b:cé").value)
    }

    @Test
    fun `identifiers that sanitize alike get distinct keys`() {
        assertNotEquals(WorkspaceKey.of("a b").value, WorkspaceKey.of("a/b").value)
    }
}
