package wtf.mazy.peel.ui.webapplist

import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebApp

class WebAppIndicators(row: View) {
    private val sandbox: ImageView = row.requireViewById(R.id.iconSandbox)
    private val ephemeral: ImageView = row.requireViewById(R.id.iconEphemeral)
    private val proxy: ImageView = row.requireViewById(R.id.iconProxy)

    val views: List<ImageView> = listOf(sandbox, ephemeral, proxy)

    fun bind(app: WebApp) {
        val sandboxed = app.isolation.isSandboxed
        sandbox.isVisible = sandboxed
        ephemeral.isVisible = app.isolation.isEphemeral
        proxy.isVisible = sandboxed && DataManager.effectiveSandbox(app).route is ProxyRoute.Via
    }
}
