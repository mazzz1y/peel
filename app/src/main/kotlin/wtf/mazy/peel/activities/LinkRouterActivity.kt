package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.showOpenInPeelPicker
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.normalizedHost

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
            showOpenInPeelPicker(apps, url) {
                setNeutralButton(R.string.open_incognito) { _, _ -> openIncognito(url) }
                setNegativeButton(R.string.share) { _, _ -> shareUrl(url) }
                setOnCancelListener { finish() }
                setOnDismissListener { finish() }
            }
        }
    }

    private fun shareUrl(url: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                null,
            ),
        )
        finish()
    }

    private fun openIncognito(url: String) {
        val host = url.normalizedHost() ?: url
        val uuid = DataManager.instance.registerTransientWebApp(
            baseUrl = url,
            title = host,
            ephemeral = true,
        )
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(Const.INTENT_WEBAPP_UUID, uuid)
                .setData("app://$uuid".toUri())
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
