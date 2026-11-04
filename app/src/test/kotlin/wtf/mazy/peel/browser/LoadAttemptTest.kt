package wtf.mazy.peel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadAttemptTest {

    private val url = "https://example.com/start"
    private val next = "https://example.com/next"

    @Test
    fun unansweredLoadReportsItsUrl() {
        val attempt = LoadAttempt()
        attempt.start(url)
        assertEquals(url, attempt.stop())
        assertEquals(url, attempt.pendingUrl)
    }

    @Test
    fun settledLoadReportsNothing() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.settleActive()
        assertNull(attempt.stop())
    }

    @Test
    fun settlingWithNoActiveLoadDoesNotTouchTheNextOne() {
        val attempt = LoadAttempt()
        attempt.settleActive()
        attempt.start(url)
        assertEquals(url, attempt.stop())
    }

    @Test
    fun settlingDoesNotCarryIntoTheNextLoad() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.settleActive()
        attempt.stop()
        attempt.start(next)
        assertEquals(next, attempt.stop())
    }

    @Test
    fun downloadReportedBeforeItsPageStartSettles() {
        val attempt = LoadAttempt()
        attempt.downloadStarted()
        attempt.start(url)
        assertNull(attempt.stop())
    }

    @Test
    fun downloadDuringTheLoadSettles() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.downloadStarted()
        assertNull(attempt.stop())
    }

    @Test
    fun downloadDoesNotCarryPastItsLoad() {
        val attempt = LoadAttempt()
        attempt.downloadStarted()
        attempt.start(url)
        attempt.stop()
        attempt.start(next)
        assertEquals(next, attempt.stop())
    }

    @Test
    fun blankDocumentsAreIgnored() {
        val attempt = LoadAttempt()
        attempt.start("about:blank")
        assertNull(attempt.stop())
        assertNull(attempt.pendingUrl)
    }

    @Test
    fun blankStartKeepsTheRealLoadPending() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.start("about:blank")
        assertEquals(url, attempt.stop())
    }

    @Test
    fun secondStopWithoutStartReportsNothing() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.stop()
        assertNull(attempt.stop())
    }

    @Test
    fun resetForgetsThePendingLoad() {
        val attempt = LoadAttempt()
        attempt.start(url)
        attempt.reset()
        assertNull(attempt.pendingUrl)
        assertNull(attempt.stop())
    }
}
