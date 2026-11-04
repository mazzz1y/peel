package wtf.mazy.peel.browser

internal class LoadAttempt {

    var pendingUrl: String? = null
        private set

    private var active = false
    private var settled = false

    // Gecko reports a download before the page start of the load it came from.
    private var downloadPending = false

    fun start(url: String) {
        if (isBlankDocument(url)) return
        pendingUrl = url
        active = true
        settled = downloadPending
        downloadPending = false
    }

    fun settleActive() {
        if (active) settled = true
    }

    fun downloadStarted() {
        if (active) settled = true else downloadPending = true
    }

    /** Returns the URL of the load that just stopped, unless something else answered it. */
    fun stop(): String? {
        if (!active) return null
        active = false
        downloadPending = false
        return pendingUrl.takeUnless { settled }
    }

    fun reset() {
        pendingUrl = null
        active = false
        settled = false
        downloadPending = false
    }
}
