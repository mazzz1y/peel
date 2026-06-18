package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.MenuDialogHelper
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.common.showOpenInPeelPicker
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.shareText

class LinkRouterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)

        val url =
            intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
                ?: run {
                    finish()
                    return
                }

        lifecycleScope.launch {
            DataManager.instance.loadAppData()
            val apps = DataManager.instance.activeWebsites
            if (apps.isEmpty()) {
                openIncognito(url)
                return@launch
            }
            showMenu(url, apps)
        }
    }

    private fun showMenu(url: String, apps: List<WebApp>) {
        var dialog: AlertDialog? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(MenuDialogHelper.buildHeader(this@LinkRouterActivity, null, url))
            addView(MenuDialogHelper.buildDivider(this@LinkRouterActivity))

            val match = ExternalLinkMenu.bestPeelMatch(apps, url, null)
            val icon = match?.resolveIcon()
            val iconClick = if (match != null && icon != null) {
                {
                    dialog?.dismiss()
                    BrowserLauncher.launch(match, this@LinkRouterActivity, url)
                    finish()
                }
            } else null

            addView(
                MenuDialogHelper.buildActionRow(
                    this@LinkRouterActivity,
                    getString(R.string.open_in_peel),
                    icon,
                    iconClick,
                ) {
                    dialog?.dismiss()
                    openPicker(url, apps)
                }
            )
            addView(
                MenuDialogHelper.buildActionRow(
                    this@LinkRouterActivity,
                    getString(R.string.open_incognito_action),
                ) {
                    dialog?.dismiss()
                    openIncognito(url)
                }
            )
            addView(
                MenuDialogHelper.buildActionRow(
                    this@LinkRouterActivity,
                    getString(R.string.context_menu_share_link),
                ) {
                    dialog?.dismiss()
                    shareUrl(url)
                }
            )
            addView(
                MenuDialogHelper.buildActionRow(
                    this@LinkRouterActivity,
                    getString(R.string.context_menu_copy_link),
                ) {
                    dialog?.dismiss()
                    copyLink(url)
                }
            )
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openPicker(url: String, apps: List<WebApp>) {
        showOpenInPeelPicker(apps, url, onDismiss = ::finish)
    }

    private fun shareUrl(url: String) {
        shareText(url)
        finish()
    }

    private fun copyLink(url: String) {
        copyToClipboard(url)
        finish()
    }

    private fun openIncognito(url: String) {
        BrowserLauncher.launchIncognito(this, url)
        finish()
    }
}
