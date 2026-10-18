package wtf.mazy.peel.gecko

import androidx.annotation.UiThread
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

class SandboxSession(settings: GeckoSessionSettings) : GeckoSession(settings) {

    private var heldContextId: String? = settings.contextId?.also(SandboxManager.usage::acquire)

    @UiThread
    override fun close() {
        super.close()
        heldContextId?.let(SandboxManager.usage::release)
        heldContextId = null
    }
}
