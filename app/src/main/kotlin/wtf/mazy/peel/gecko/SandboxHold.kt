package wtf.mazy.peel.gecko

import androidx.lifecycle.ViewModel

// A host closes its session before opening the next one (app swap, crash recovery, recreation),
// so the sandbox would briefly look unused and an ephemeral one would be wiped mid-use.
class SandboxHold : ViewModel() {

    private var contextId: String? = null

    fun hold(contextId: String?) {
        if (contextId == this.contextId) return
        contextId?.let(SandboxManager.usage::acquire)
        this.contextId?.let(SandboxManager.usage::release)
        this.contextId = contextId
    }

    override fun onCleared() = hold(null)
}
