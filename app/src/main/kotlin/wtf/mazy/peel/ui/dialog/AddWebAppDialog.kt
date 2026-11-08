package wtf.mazy.peel.ui.dialog

import android.content.Intent
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.bindDropdown
import wtf.mazy.peel.ui.proxy.ProxyOptions
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.TypedUrl

object AddWebAppDialog {

    /**
     * Asks for a web app's address, group and sandbox, starting on [initialGroup], adds it and
     * opens its settings.
     */
    fun show(
        activity: AppCompatActivity,
        prefill: String = "",
        initialGroup: WebAppGroup? = null,
        onConfirmed: () -> Unit = {},
        openSettings: (Intent) -> Unit = activity::startActivity,
    ) {
        val groups = DataManager.sortedGroups
        var group = initialGroup?.takeIf { it in groups }
        lateinit var controls: SandboxControls<ProxyChoice>
        activity.showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.add_webapp,
                hintRes = R.string.url,
                inputType = InputType.TYPE_TEXT_VARIATION_URI,
                prefill = prefill,
                initialSelection = InitialSelection.CURSOR_AT_END,
                positiveRes = R.string.ok,
                extraContent = { container ->
                    if (groups.isNotEmpty()) {
                        addGroupChooser(container, groups, { group }) { chosen ->
                            group = chosen
                            controls.setProxyOptions(proxyOptions(activity, chosen), ProxyChoice.Inherit)
                        }
                    }
                    controls = SandboxControls(
                        activity,
                        container,
                        proxyOptions(activity, group),
                        ProxyChoice.Inherit,
                        afterRow = groups.isNotEmpty(),
                    )
                },
            ),
        ) { input, _ ->
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@showInputDialogRaw
            val webApp = WebApp(
                baseUrl = TypedUrl.normalize(text),
                isolation = controls.isolation,
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

    private fun proxyOptions(activity: AppCompatActivity, group: WebAppGroup?) =
        ProxyOptions.forWebApp(activity, group, DataManager.proxies)

    private fun addGroupChooser(
        container: LinearLayout,
        groups: List<WebAppGroup>,
        current: () -> WebAppGroup?,
        onChosen: (WebAppGroup?) -> Unit,
    ) {
        val row = DialogChoiceRow(container, R.string.group, R.dimen.dialog_content_gap)
        val choices: List<WebAppGroup?> = groups + null
        row.button.bindDropdown(
            items = choices.map { it?.title ?: container.context.getString(R.string.ungrouped) },
            currentIndex = { choices.indexOf(current()) },
            onSelected = { onChosen(choices[it]) },
        )
    }
}
