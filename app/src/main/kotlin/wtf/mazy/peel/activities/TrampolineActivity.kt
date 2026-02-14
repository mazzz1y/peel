package wtf.mazy.peel.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.WebViewLauncher

class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uuid = intent.getStringExtra(Const.INTENT_WEBAPP_UUID)
        val webapp =
            uuid?.let {
                DataManager.instance.loadAppData()
                DataManager.instance.getWebApp(it)
            }

        if (webapp != null) {
            val target = WebViewLauncher.createWebViewIntent(webapp, this)
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(target)
            }
        }

        finish()
    }
}
