package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.toast

class TrampolineActivity : PeelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)

        lifecycleScope.launch {
            DataManager.awaitReady()

            val webAppUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (webAppUuid != null) {
                val webApp = DataManager.webApp(webAppUuid)
                if (webApp != null) BrowserLauncher.launch(webApp, this@TrampolineActivity)
                finish()
                return@launch
            }

            val groupUuid = intent.getStringExtra(Const.INTENT_GROUP_UUID)
            if (groupUuid != null) {
                launchGroup(groupUuid)
                return@launch
            }

            finish()
        }
    }

    private fun launchGroup(groupUuid: String) {
        val group = DataManager.group(groupUuid)
        val apps = DataManager.webAppsInGroup(groupUuid)

        if (apps.isEmpty()) {
            toast(R.string.group_empty)
            finish()
            return
        }

        showPickerDialog(group?.title ?: "", apps)
    }

    private fun showPickerDialog(title: String, apps: List<WebApp>) {
        PickerDialog.show(
            activity = this,
            title = title,
            items = apps,
            onPick = { webApp ->
                BrowserLauncher.launch(webApp, this)
                finish()
            },
            configure = {
                setOnCancelListener { finish() }
                setOnDismissListener { finish() }
            },
        ) { webApp, icon, name, _, _ ->
            name.text = webApp.title
            icon.setImageBitmap(webApp.resolveIcon())
        }
    }
}
