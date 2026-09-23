package wtf.mazy.peel.shortcut

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import wtf.mazy.peel.gecko.SandboxManager
import wtf.mazy.peel.model.DataSideEffects
import wtf.mazy.peel.model.IconOwner

class ShortcutSideEffects(
    private val context: Context,
    private val scope: CoroutineScope,
) : DataSideEffects {

    override fun onShortcutsRemoved(uuids: List<String>) {
        ShortcutIcons.deleteShortcuts(uuids, context)
    }

    override fun onShortcutOwnerChanged(owner: IconOwner) {
        Shortcuts.updatePinnedShortcut(owner, context)
    }

    override fun onSandboxRemoved(contextId: String) {
        SandboxManager.enqueueSandboxClear(context, contextId, scope)
    }
}
