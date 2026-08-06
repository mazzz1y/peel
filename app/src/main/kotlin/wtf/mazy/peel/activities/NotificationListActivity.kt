package wtf.mazy.peel.activities

import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.unifiedpush.android.connector.UnifiedPush
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.db.PushSubscriptionEntity
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.entitylist.EntityListActivity
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.push.PushSubscriptionItem
import wtf.mazy.peel.ui.push.PushSubscriptionListAdapter
import wtf.mazy.peel.util.AppPrefs
import wtf.mazy.peel.util.NotificationUtils

class NotificationListActivity : EntityListActivity<PushSubscriptionItem>() {

    override val titleRes: Int = R.string.notifications
    override val emptyStateRes: Int = R.string.notifications_empty_state
    override val supportsDrag: Boolean = false

    private var permissions: List<ContentPermission> = emptyList()
    private var distributorItem: MenuItem? = null
    private var distributorLabelView: TextView? = null
    private var pushSwitch: MaterialSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fab.hide()
    }

    override fun onResume() {
        super.onResume()
        pushSwitch?.isChecked = AppPrefs.isPushEnabled(this)
        updateDistributorLabel()
        refreshPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_notifications, menu)
        distributorItem = menu.findItem(R.id.action_distributor)
        distributorLabelView = distributorItem?.actionView?.findViewById(R.id.toolbar_label)
        pushSwitch = menu.findItem(R.id.action_push_enabled).actionView
            ?.findViewById<MaterialSwitch>(R.id.toolbar_switch)
            ?.apply {
                contentDescription = getString(R.string.push_enable)
                isChecked = AppPrefs.isPushEnabled(this@NotificationListActivity)
                setOnCheckedChangeListener { _, checked -> onPushSwitchChanged(checked) }
            }
        updateDistributorLabel()
        return true
    }

    private fun onPushSwitchChanged(checked: Boolean) {
        if (checked == AppPrefs.isPushEnabled(this)) return
        if (checked) {
            AppPrefs.setPushEnabled(this, true)
            updateDistributorLabel()
            refreshPermissions()
        } else {
            confirmDisable()
        }
    }

    private fun selectDistributor(distributor: String) {
        if (distributor == UnifiedPush.getSavedDistributor(this)) return
        lifecycleScope.launch {
            PushBridge.switchDistributor(this@NotificationListActivity, distributor)
            if (isFinishing || isDestroyed) return@launch
            updateDistributorLabel()
            refreshPermissions()
        }
    }

    private fun selectLocalDelivery() {
        if (PushBridge.isLocalDelivery(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.push_delivery_local)
            .setMessage(R.string.push_delivery_local_confirm)
            .setPositiveButton(R.string.push_switch_to_local) { _, _ ->
                lifecycleScope.launch {
                    PushBridge.reset(this@NotificationListActivity)
                    if (isFinishing || isDestroyed) return@launch
                    updateDistributorLabel()
                    refreshPermissions()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseDelivery() {
        val options = listOf(LOCAL_DELIVERY) + UnifiedPush.getDistributors(this)
        PickerDialog.show(
            activity = this,
            title = getString(R.string.push_distributor),
            items = options,
            onPick = { pkg ->
                if (pkg == LOCAL_DELIVERY) selectLocalDelivery() else selectDistributor(pkg)
            },
            configure = { setNegativeButton(R.string.cancel, null) },
        ) { pkg, icon, name, _, detail ->
            if (pkg == LOCAL_DELIVERY) {
                name.setText(R.string.push_delivery_local)
                detail.setText(R.string.push_delivery_local_summary)
                detail.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_symbols_cloud_off_24)
            } else {
                name.text = distributorLabel(pkg)
                runCatching { icon.setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            }
        }
    }

    private fun confirmDisable() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.push_disable)
            .setMessage(R.string.push_disable_confirm)
            .setPositiveButton(R.string.turn_off) { _, _ ->
                AppPrefs.setPushEnabled(this, false)
                lifecycleScope.launch {
                    PushBridge.reset(this@NotificationListActivity)
                    if (isFinishing || isDestroyed) return@launch
                    updateDistributorLabel()
                    refreshPermissions()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pushSwitch?.isChecked = true }
            .setOnCancelListener { pushSwitch?.isChecked = true }
            .show()
    }

    override fun createAdapter(): EntityListAdapter<PushSubscriptionItem, *> =
        PushSubscriptionListAdapter(SubscriptionActions())

    override fun loadEntities(): List<PushSubscriptionItem> {
        val subscriptions = DataManager.instance.getPushSubscriptions().toMutableList()
        val fromPermissions = permissions.map { permission ->
            val subscription = subscriptions
                .firstOrNull { PushBridge.matchesScope(permission, it.scope) }
                ?.also(subscriptions::remove)
            item(
                key = permission.uri + '|' + permission.contextId.orEmpty(),
                permission = permission,
                subscription = subscription,
                host = permission.uri.toUri().host ?: permission.uri,
                contextId = permission.contextId,
                allowed = permission.value == ContentPermission.VALUE_ALLOW,
            )
        }
        val orphaned = subscriptions.mapNotNull { subscription ->
            val host = PushBridge.scopeHost(subscription.scope) ?: return@mapNotNull null
            item(
                key = subscription.instance,
                permission = null,
                subscription = subscription,
                host = host,
                contextId = subscription.contextId,
                allowed = true,
            )
        }
        return (fromPermissions + orphaned).sortedWith(
            compareByDescending<PushSubscriptionItem> { it.allowed }
                .thenBy { it.sandboxTitle ?: "" }
                .thenBy { it.host }
        )
    }

    override fun rowEntityUuid(entity: PushSubscriptionItem): String = entity.key

    private fun item(
        key: String,
        permission: ContentPermission?,
        subscription: PushSubscriptionEntity?,
        host: String,
        contextId: String?,
        allowed: Boolean,
    ): PushSubscriptionItem {
        val owner = contextId?.takeIf(String::isNotEmpty)
            ?.let(DataManager.instance::getSandboxOwner) as? IconOwner
        return PushSubscriptionItem(
            key = key,
            permission = permission,
            subscription = subscription,
            host = host,
            sandboxTitle = owner?.title,
            allowed = allowed,
            icon = owner?.resolveIcon() ?: letterIcon(host),
        )
    }

    private fun refreshPermissions() {
        lifecycleScope.launch {
            val loaded = PushBridge.getNotificationPermissions(this@NotificationListActivity)
            if (isFinishing || isDestroyed) return@launch
            permissions = loaded
            refreshList()
        }
    }

    private fun letterIcon(host: String): Bitmap =
        LetterIconGenerator.generate(host, host, IconOwner.defaultIconSizePx())

    private fun updateDistributorLabel() {
        val enabled = AppPrefs.isPushEnabled(this)
        distributorItem?.isVisible = enabled
        emptyStateText.setText(if (enabled) emptyStateRes else R.string.notifications_disabled)
        if (!enabled) return
        val distributor = UnifiedPush.getSavedDistributor(this)
        distributorLabelView?.apply {
            text = distributor?.let(::distributorLabel) ?: getString(R.string.push_delivery_local)
            contentDescription = getString(R.string.push_distributor)
            setOnClickListener { chooseDelivery() }
        }
    }

    private fun distributorLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
    }.getOrNull()?.toString() ?: pkg

    private fun confirmDelete(item: PushSubscriptionItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.host)
            .setMessage(R.string.push_subscription_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    PushBridge.resetSite(
                        this@NotificationListActivity,
                        item.permission,
                        item.subscription,
                    )
                    if (isFinishing || isDestroyed) return@launch
                    refreshPermissions()
                    NotificationUtils.showToast(
                        this@NotificationListActivity,
                        getString(R.string.push_subscription_deleted),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class SubscriptionActions : EntityRowActions<PushSubscriptionItem> {
        override fun onItemClick(item: PushSubscriptionItem) = Unit
        override fun onItemIconClick(item: PushSubscriptionItem) = Unit
        override fun onItemMenu(view: View, item: PushSubscriptionItem) {
            confirmDelete(item)
        }
    }

    private companion object {
        const val LOCAL_DELIVERY = ""
    }
}
