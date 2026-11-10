package wtf.mazy.peel.browser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wtf.mazy.peel.gecko.SandboxManager
import wtf.mazy.peel.model.DataSideEffects
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.shortcut.ShortcutIcons
import wtf.mazy.peel.shortcut.Shortcuts

class AppDataSideEffects(
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

    override fun onWebAppsRemoved(uuids: Set<String>) {
        scope.launch(Dispatchers.Main) { SessionHostRegistry.finishOwnedBy(uuids) }
    }
}
