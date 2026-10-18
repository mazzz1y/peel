package wtf.mazy.peel.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxUsageTest {

    private val idle = mutableListOf<String>()
    private val usage = SandboxUsage { idle += it }

    @Test
    fun idleOnlyAfterLastUserReleases() {
        usage.acquire("work")
        usage.acquire("work")
        usage.release("work")
        assertTrue(usage.isInUse("work"))
        assertEquals(emptyList<String>(), idle)

        usage.release("work")
        assertFalse(usage.isInUse("work"))
        assertEquals(listOf("work"), idle)
    }

    @Test
    fun contextsAreCountedSeparately() {
        usage.acquire("work")
        usage.acquire("notes")
        usage.release("notes")
        assertEquals(listOf("notes"), idle)
        assertTrue(usage.isInUse("work"))
    }

    @Test
    fun reacquiredContextGoesIdleAgain() {
        usage.acquire("work")
        usage.release("work")
        usage.acquire("work")
        assertTrue(usage.isInUse("work"))
        usage.release("work")
        assertEquals(listOf("work", "work"), idle)
    }

    @Test
    fun windowHoldBridgesSessionReplacement() {
        usage.acquire("work")
        usage.acquire("work")
        usage.release("work")
        usage.acquire("work")
        assertEquals(emptyList<String>(), idle)
    }
}
