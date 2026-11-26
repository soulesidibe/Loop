package loop.runner.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgyVersionTest {
    @Test
    fun `the minimum and anything newer on the pinned major may run`() {
        for (version in listOf("1.5.18", "1.5.19", "1.6.0", "1.10.2", "1.5.18+build.7", " 1.5.18 ")) {
            assertNull(AgyVersion.check(version, pinned = "1.5.18", minimum = "1.5.18"), version)
        }
    }

    @Test
    fun `an older version, a prerelease of the minimum or an unreadable minor is too old`() {
        for (version in listOf("1.5.16", "1.5.17", "1.4.99", "1.5", "1.5.18-rc.1", "1.x", "1.5.x")) {
            assertEquals(AgyVersion.Problem.TooOld(version), AgyVersion.check(version, pinned = "1.5.18", minimum = "1.5.18"), version)
        }
    }

    @Test
    fun `another major or no major is the wrong major`() {
        for (version in listOf("2.0.0", "0.9.9", "x.1.2", "")) {
            assertEquals(AgyVersion.Problem.WrongMajor(version), AgyVersion.check(version, pinned = "1.5.18", minimum = "1.5.18"), version)
        }
    }

    @Test
    fun `the shipped minimum is no newer than the pin`() {
        assertNull(AgyVersion.check(AcpSettings.PINNED_AGY_VERSION))
    }
}
