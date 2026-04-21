package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.PickerDialog
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.Const

class TrampolineActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this)

        lifecycleScope.launch {
            DataManager.instance.awaitReady()

            val webappUuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (webappUuid != null) {
                val webapp = DataManager.instance.getWebApp(webappUuid)
                if (webapp != null) BrowserLauncher.launch(webapp, this@TrampolineActivity)
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
        PickerDialog.show(
            activity = this,
            title = title,
            items = apps,
            onPick = { webapp ->
                BrowserLauncher.launch(webapp, this)
                finish()
            },
            configure = {
                setOnCancelListener { finish() }
                setOnDismissListener { finish() }
            },
        ) { webapp, icon, name, detail ->
            name.text = webapp.title
            icon.setImageBitmap(webapp.resolveIcon())
            detail.visibility = View.GONE
        }
    }
}
