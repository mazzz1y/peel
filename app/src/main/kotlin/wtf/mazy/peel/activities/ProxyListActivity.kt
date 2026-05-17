package wtf.mazy.peel.activities

import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.proxy.ProxyEditorDialog
import wtf.mazy.peel.ui.proxylist.ProxyListAdapter

class ProxyListActivity : EntityListActivity<Proxy>() {

    override val titleRes: Int = R.string.proxies
    override val emptyStateRes: Int = R.string.proxies_empty_state
    override val supportsDrag: Boolean = false

    override fun createAdapter(): EntityListAdapter<Proxy, *> =
        ProxyListAdapter(ProxyItemActions())

    override fun loadEntities(): List<Proxy> =
        DataManager.instance.getProxies().sortedBy { it.displayName().lowercase() }

    override fun rowEntityUuid(entity: Proxy): String = entity.uuid

    override fun onAddClicked() {
        ProxyEditorDialog.show(
            activity = this,
            existing = null,
            onSave = { newProxy ->
                DataManager.instance.appScope.launch {
                    DataManager.instance.addProxy(newProxy)
                }
            },
        )
    }

    private fun openEditor(proxy: Proxy) {
        ProxyEditorDialog.show(
            activity = this,
            existing = proxy,
            onSave = { updated ->
                DataManager.instance.appScope.launch {
                    DataManager.instance.replaceProxy(updated)
                }
            },
            onDelete = { confirmDelete(proxy) },
        )
    }

    private fun confirmDelete(proxy: Proxy) {
        val (apps, groups) = DataManager.instance.proxyDependents(proxy.uuid)
        val dependents = apps.size + groups.size
        val message = if (dependents == 0) {
            getString(R.string.proxy_delete_confirm)
        } else {
            getString(R.string.proxy_in_use_warning, dependents)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                DataManager.instance.appScope.launch {
                    apps.forEach { app ->
                        if (app.proxyUuid == proxy.uuid) {
                            val mutable = DataManager.instance.getWebApp(app.uuid) ?: return@forEach
                            mutable.proxyUuid = null
                            DataManager.instance.replaceWebApp(mutable)
                        }
                    }
                    groups.forEach { g ->
                        if (g.proxyUuid == proxy.uuid) {
                            val mutable = DataManager.instance.getGroup(g.uuid) ?: return@forEach
                            mutable.proxyUuid = null
                            DataManager.instance.replaceGroup(mutable)
                        }
                    }
                    DataManager.instance.removeProxy(proxy.uuid)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class ProxyItemActions : EntityRowActions<Proxy> {
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
