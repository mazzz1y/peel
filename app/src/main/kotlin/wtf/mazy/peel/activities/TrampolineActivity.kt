package wtf.mazy.peel.activities

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.WebViewLauncher

class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        if (webappUuid != null) {
            val webapp = DataManager.instance.getWebApp(webappUuid)
            if (webapp != null) WebViewLauncher.startWebView(webapp, this)
            finish()
            return
        }

        val groupUuid = intent.getStringExtra(Const.INTENT_GROUP_UUID)
        if (groupUuid != null) {
            launchGroup(groupUuid)
            return
        }

        finish()
    }

    private fun launchGroup(groupUuid: String) {
        val group = DataManager.instance.getGroup(groupUuid)
        val apps = DataManager.instance.activeWebsitesForGroup(groupUuid)

        if (apps.isEmpty()) {
            Toast.makeText(this, getString(R.string.group_empty), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showPickerDialog(group?.title ?: "", apps)
    }

    private fun showPickerDialog(title: String, apps: List<WebApp>) {
        val adapter = ListPickerAdapter(apps) { webapp, icon, name, detail ->
            name.text = webapp.title
            icon.setImageBitmap(webapp.resolveIcon())
            detail.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(this).setTitle(title).setAdapter(adapter) { _, position ->
                WebViewLauncher.startWebView(apps[position], this)
                finish()
            }.setOnCancelListener { finish() }.setOnDismissListener { finish() }.show()
    }
}
