package wtf.mazy.peel.activities

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRowListener
import wtf.mazy.peel.ui.proxy.ProxyEditorDialog
import wtf.mazy.peel.ui.proxylist.ProxyListAdapter

class ProxyListActivity : EntityListActivity<Proxy>() {

    override val titleRes: Int = R.string.proxies
    override val emptyStateRes: Int = R.string.proxies_empty_state
    override val supportsDrag: Boolean = false

    override fun createAdapter(): EntityListAdapter<Proxy, *> =
        ProxyListAdapter(ProxyRowListener())

    override fun loadEntities(): List<Proxy> =
        DataManager.proxies.sortedBy { it.displayName().lowercase() }

    override fun rowEntityUuid(entity: Proxy): String = entity.uuid

    override fun onAddClicked() {
        ProxyEditorDialog.show(
            activity = this,
            existing = null,
            onSave = { newProxy ->
                lifecycleScope.launch {
                    DataManager.addProxy(newProxy)
                }
            },
        )
    }

    private fun openEditor(proxy: Proxy) {
        ProxyEditorDialog.show(
            activity = this,
            existing = proxy,
            onSave = { updated ->
                lifecycleScope.launch {
                    DataManager.replaceProxy(updated)
                }
            },
            onDelete = { confirmDelete(proxy) },
        )
    }

    private fun confirmDelete(proxy: Proxy) {
        val dependents = DataManager.proxyDependents(proxy.uuid)
            .let { (apps, groups) -> apps.size + groups.size }
        val message = if (dependents == 0) {
            getString(R.string.proxy_delete_confirm)
        } else {
            getString(R.string.proxy_in_use_warning, dependents)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { DataManager.removeProxy(proxy.uuid) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class ProxyRowListener : EntityRowListener<Proxy> {
        override fun onItemClick(item: Proxy) {
            openEditor(item)
        }

        override fun onItemIconClick(item: Proxy) {
            openEditor(item)
        }

        override fun onItemMenu(view: View, item: Proxy) {
            confirmDelete(item)
        }
    }
}
