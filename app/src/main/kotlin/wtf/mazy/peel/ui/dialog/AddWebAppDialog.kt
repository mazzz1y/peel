package wtf.mazy.peel.ui.dialog

import android.content.Intent
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.proxy.ProxyOptions
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.TypedUrl

object AddWebAppDialog {

    /** Asks for a web app's address and sandbox, adds it to [group] and opens its settings. */
    fun show(
        activity: AppCompatActivity,
        prefill: String = "",
        group: WebAppGroup? = null,
        onConfirmed: () -> Unit = {},
        openSettings: (Intent) -> Unit = activity::startActivity,
    ) {
        activity.showSandboxInputDialog(
            titleRes = R.string.add_webapp,
            hintRes = R.string.url,
            proxyOptions = ProxyOptions.forWebApp(activity, group, DataManager.proxies),
            defaultProxy = ProxyChoice.Inherit,
            inputType = InputType.TYPE_TEXT_VARIATION_URI,
            prefill = prefill,
        ) { result ->
            val webApp = WebApp(
                baseUrl = TypedUrl.normalize(result.text),
                isolation = result.isolation,
                groupUuid = group?.uuid,
            )
            activity.lifecycleScope.launch {
                DataManager.addWebApp(webApp, appendOrder = true)
                openSettings(
                    Intent(activity, WebAppSettingsActivity::class.java)
                        .putExtra(Const.INTENT_WEBAPP_UUID, webApp.uuid)
                        .putExtra(Const.INTENT_AUTO_FETCH, true)
                )
                // After the settings open: the caller may finish its activity, cancelling this scope.
                onConfirmed()
            }
        }
    }
}
